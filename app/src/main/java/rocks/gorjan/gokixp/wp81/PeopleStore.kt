package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * The address book, as something you can change.
 *
 * [ContactFeed] reads it for the People tile and stops there: a wall of faces only ever
 * needs a name, a picture and whether somebody is starred. The People app is the other
 * half of the same thing - it opens a person up, and it writes back. So the reading here
 * goes deeper (numbers, addresses, which account a contact came from) and there is a
 * writing side at all.
 *
 * Kept apart from the app that uses it for the reason every provider wrapper is: the
 * queries and the batches are about Android's contacts provider, and the app is about
 * Windows Phone. Mixing the two would put `ContentProviderOperation` in the middle of a
 * panorama.
 *
 * Everything that touches the provider runs off the main thread, and every callback comes
 * back on it. A phone with four thousand contacts on it is a real phone, and the address
 * book is on disk.
 */
object PeopleStore {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Somebody in the book, as a list shows them.
     *
     * The same four facts [ContactFeed.Person] carries, plus the key: an aggregated
     * contact's *id* can change when the provider re-merges it - which is exactly what
     * editing one does - while the lookup key survives that. Anything held across a write
     * is held by key.
     */
    data class Contact(
        val id: Long,
        val lookupKey: String?,
        val name: String,
        val photoUri: String?,
        val starred: Boolean,
        /**
         * Whether they came out of a directory rather than off this phone.
         *
         * A work account's directory - a company's list of its own people - is answered
         * over the network and never stored here, so somebody found in one has no row in
         * the address book, no id worth keeping and no card to open. What they have is a
         * name, which is the thing that was missing. See [lookupDirectoryNow].
         */
        val directory: Boolean = false,
        /** The number they were found by, for a directory result that can be saved. */
        val number: String? = null
    ) {
        val initials: String get() = initialsOf(name)
    }

    /** One way of reaching somebody: the number or address, and what they call it. */
    data class Entry(val value: String, val label: String)

    /**
     * Everything a profile page shows.
     *
     * [rawId] is the row the editor writes to. An aggregated contact can be several raw
     * ones - the same person from Google and from WhatsApp, merged - and only some of
     * those are writable, so the editor is told which one it is allowed to touch rather
     * than working it out again from the id.
     */
    data class Detail(
        val contact: Contact,
        val phones: List<Entry>,
        val emails: List<Entry>,
        val rawId: Long?,
        val account: Account?,
        /** Split for the editor, which asks for the two halves of a name separately. */
        val givenName: String,
        val familyName: String
    )

    /** What the app has to be granted before any of this works. */
    fun permissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- reading

    /**
     * Everybody, in the order the alphabet puts them.
     *
     * `SORT_KEY_PRIMARY` rather than the display name: it is the column the provider files
     * people under, so a contact filed by surname sorts where the phone's own list would
     * put them, and a name in a script that does not sort by its first code point sorts
     * the way that script does.
     */
    fun all(context: Context, onReady: (List<Contact>) -> Unit) {
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val people = try {
                readAll(resolver)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the address book", e)
                emptyList()
            }
            main.post { onReady(people) }
        }
    }

    private fun readAll(resolver: ContentResolver): List<Contact> {
        val people = mutableListOf<Contact>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED
            ),
            // The same filter the tile uses: the visible ones, plus every favourite
            // whatever group its account files it under. See ContactFeed.read.
            "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1" +
                " OR ${ContactsContract.Contacts.STARRED} = 1",
            null,
            ContactsContract.Contacts.SORT_KEY_PRIMARY
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val key = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val name =
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photo = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
            val starred = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
            while (cursor.moveToNext()) {
                val display = cursor.getString(name)?.trim().orEmpty()
                // A row with no name on it is a sync artefact rather than a person - there
                // is nothing to file it under and nothing to put in the list.
                if (display.isEmpty()) continue
                people.add(
                    Contact(
                        id = cursor.getLong(id),
                        lookupKey = cursor.getString(key),
                        name = display,
                        photoUri = cursor.getString(photo),
                        starred = cursor.getInt(starred) != 0
                    )
                )
            }
        }
        return people
    }

    /** One person, opened up. Null if they have been deleted since the list was read. */
    fun detail(context: Context, id: Long, onReady: (Detail?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val detail = try {
                readDetail(app, id)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read a contact", e)
                null
            }
            main.post { onReady(detail) }
        }
    }

    private fun readDetail(context: Context, id: Long): Detail? {
        val resolver = context.contentResolver
        val contact = resolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED
            ),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Contact(
                id = cursor.getLong(0),
                lookupKey = cursor.getString(1),
                name = cursor.getString(2)?.trim().orEmpty(),
                photoUri = cursor.getString(3),
                starred = cursor.getInt(4) != 0
            )
        } ?: return null

        val phones = mutableListOf<Entry>()
        val emails = mutableListOf<Entry>()
        var given = ""
        var family = ""

        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(id.toString()),
            null
        )?.use { cursor ->
            val resources = context.resources
            while (cursor.moveToNext()) {
                val value = cursor.getString(1)?.trim().orEmpty()
                when (cursor.getString(0)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        if (value.isEmpty()) continue
                        val type = cursor.getInt(2)
                        val custom = cursor.getString(3)
                        phones.add(
                            Entry(
                                value,
                                ContactsContract.CommonDataKinds.Phone
                                    .getTypeLabel(resources, type, custom)
                                    .toString().lowercase()
                            )
                        )
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        if (value.isEmpty()) continue
                        val type = cursor.getInt(2)
                        val custom = cursor.getString(3)
                        emails.add(
                            Entry(
                                value,
                                ContactsContract.CommonDataKinds.Email
                                    .getTypeLabel(resources, type, custom)
                                    .toString().lowercase()
                            )
                        )
                    }
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        given = cursor.getString(2)?.trim().orEmpty()
                        family = cursor.getString(3)?.trim().orEmpty()
                    }
                }
            }
        }

        val raw = writableRawOf(resolver, id)
        return Detail(
            contact = contact,
            // The same number synced twice by two accounts is one number to the person
            // reading it, however many rows the provider keeps for it.
            phones = phones.distinctBy { normalise(it.value) },
            emails = emails.distinctBy { it.value.lowercase() },
            rawId = raw?.first,
            account = raw?.second,
            givenName = given,
            familyName = family
        )
    }

    /**
     * The raw row an edit should land on, and the account it belongs to.
     *
     * A merged contact has several, and writing to the wrong one is how an edit ends up
     * invisible: a name written to the WhatsApp raw contact is overwritten by the next
     * sync, and one written to a read-only row is rejected outright. So the ones whose
     * sync adapter says it can upload come first, and a raw contact with no account at all
     * - a contact that only exists on this phone - is always writable.
     */
    private fun writableRawOf(
        resolver: ContentResolver,
        contactId: Long
    ): Pair<Long, Account?>? {
        val rows = mutableListOf<Triple<Long, String?, String?>>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME
            ),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        if (rows.isEmpty()) return null
        val uploading = uploadingAccountTypes()
        val best = rows.firstOrNull { it.second == null || it.second in uploading } ?: rows.first()
        val (rawId, type, name) = best
        val account = if (type != null && name != null) Account(name, type) else null
        return rawId to account
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Where a new contact can be put, best first.
     *
     * Read off the raw contacts already on the phone rather than from the account manager:
     * the accounts that hold contacts are the accounts the user syncs contacts with, and
     * asking that way needs no permission beyond the one this app already has. Filtered to
     * the sync adapters that say they can upload, since an account that only reads is not
     * somewhere a new contact can go.
     *
     * The list always ends with null, which means this phone and nothing else - the
     * fallback for a device with no accounts at all, and a perfectly good answer for
     * somebody who does not want a new number leaving the handset.
     */
    fun accounts(context: Context, onReady: (List<Account?>) -> Unit) {
        cachedAccounts?.let {
            onReady(it)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val found = try {
                readAccounts(app)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read which accounts hold contacts", e)
                listOf(null)
            }
            main.post {
                cachedAccounts = found
                onReady(found)
            }
        }
    }

    /**
     * Held for the life of the process.
     *
     * The answer is a walk of every raw contact on the phone, and it changes when an
     * account is added or removed - which is a trip to the system settings, and takes the
     * launcher's process with it often enough that re-asking on every new contact would be
     * paying for a fact that has not moved.
     */
    private var cachedAccounts: List<Account?>? = null

    /** Drops that cache, for the one thing that can add an account: saving into a new one. */
    fun forgetAccounts() {
        cachedAccounts = null
    }

    private fun readAccounts(context: Context): List<Account?> {
        val resolver = context.contentResolver
        val uploading = uploadingAccountTypes()
        val found = linkedSetOf<Account>()
        try {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (type in uploading) found.add(Account(name, type))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read which accounts hold contacts", e)
        }
        // Google first: it is the one that will still have the contact when the phone is
        // replaced, which is the only thing that distinguishes these to somebody choosing.
        return found.sortedBy { if (it.type == GOOGLE) 0 else 1 } + listOf(null)
    }

    /**
     * Who owns [number], if anybody does.
     *
     * `PhoneLookup` rather than a scan of the book: the provider keeps an index of numbers
     * in the shape that matches them - country codes, trunk zeros and punctuation already
     * dealt with - and matching by hand against a list this app happens to be holding
     * would get the easy cases right and the interesting ones wrong.
     */
    fun lookup(context: Context, number: String, onReady: (Contact?) -> Unit) {
        if (number.isBlank() || !canRead(context)) {
            onReady(null)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val found = lookupNow(app, number)
            main.post { onReady(found) }
        }
    }

    /**
     * The same question, answered where it is asked.
     *
     * For callers that are already off the main thread and have several numbers to put
     * names to - a list of conversations, a page of calls - where handing each answer back
     * through a handler would be a queue of posts to wait on. Never call it from the main
     * thread; that is what [lookup] is for.
     */
    fun lookupNow(context: Context, number: String): Contact? {
        if (number.isBlank() || !canRead(context)) return null
        // The provider's index first, because it knows things a digit comparison cannot -
        // what this phone's country is, and so whether a number written one way and dialled
        // another are the same number. The book itself second, for everything it misses.
        return byPhoneLookup(context, number) ?: byNumber(context, number)
    }

    /**
     * `PhoneLookup`, taking the best answer rather than the first.
     *
     * A number saved in more than one place - a person from Google, the same person from
     * WhatsApp or Viber - comes back as several rows, and they are only one row each if
     * the provider has aggregated them, which it does not always do. `moveToFirst` then
     * picks whichever the provider happened to order first, which can be the copy some
     * messaging app made with no name on it. So every row is read and the one that
     * actually says who this is wins. See [rank].
     */
    private fun byPhoneLookup(context: Context, number: String): Contact? = try {
        context.contentResolver.query(
            Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.STARRED
            ),
            null, null, null
        )?.use { cursor ->
            var best: Contact? = null
            while (cursor.moveToNext()) {
                val found = Contact(
                    id = cursor.getLong(0),
                    lookupKey = cursor.getString(1),
                    name = cursor.getString(2)?.trim().orEmpty(),
                    photoUri = cursor.getString(3),
                    starred = cursor.getInt(4) != 0
                )
                if (best == null || rank(found) > rank(best!!)) best = found
            }
            // A row with no name on it is not an answer to "who is this": it is the same
            // unknown number with a contact id attached. Let the book have a go instead.
            best?.takeIf { it.name.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not match a number to a contact", e)
        null
    }

    /** How much a row looks like somebody, rather than like a copy of a number. */
    private fun rank(contact: Contact): Int {
        var score = 0
        if (contact.name.any { it.isLetter() }) score += 4
        if (!contact.photoUri.isNullOrBlank()) score += 2
        if (contact.starred) score += 1
        return score
    }

    /**
     * The whole book's numbers, compared as numbers.
     *
     * `PhoneLookup` is an index the provider builds, and a number can be in the book and
     * not in it: written in a country's format the phone is not set to, saved by an
     * account whose sync wrote the row without normalising it, or simply arrived since the
     * index was last touched. When that happens a person is in the address book and their
     * calls still say nothing but a number.
     *
     * So the fallback is the direct question - every phone row on the phone, whatever
     * account put it there, matched on [normalise], which is the last nine digits and
     * therefore blind to country codes, trunk zeros and punctuation alike.
     */
    private fun byNumber(context: Context, number: String): Contact? {
        val wanted = normalise(number)
        if (wanted.isEmpty()) return null
        return numberIndex(context)[wanted]
    }

    private fun numberIndex(context: Context): Map<String, Contact> {
        val held = cachedNumbers
        if (held != null && System.currentTimeMillis() - cachedNumbersAt < NUMBER_CACHE_MS) {
            return held
        }
        val built = readNumbers(context)
        cachedNumbers = built
        cachedNumbersAt = System.currentTimeMillis()
        return built
    }

    /**
     * One pass over every number in the book.
     *
     * A phone with a few thousand contacts is one query and a map, on a worker thread, and
     * it is only ever built because something was not found - so a page of calls from
     * people who are all in the book never pays for it at all.
     */
    private fun readNumbers(context: Context): Map<String, Contact> {
        val index = HashMap<String, Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val key = normalise(cursor.getString(0).orEmpty())
                    if (key.isEmpty()) continue
                    val found = Contact(
                        id = cursor.getLong(1),
                        lookupKey = cursor.getString(2),
                        name = cursor.getString(3)?.trim().orEmpty(),
                        photoUri = cursor.getString(4),
                        starred = cursor.getInt(5) != 0
                    )
                    if (found.name.isBlank()) continue
                    val standing = index[key]
                    // The same number from three accounts is one person; the row that says
                    // most about them is the one worth keeping.
                    if (standing == null || rank(found) > rank(standing)) index[key] = found
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not index the book's numbers", e)
        }
        return index
    }

    private var cachedNumbers: Map<String, Contact>? = null
    private var cachedNumbersAt = 0L

    /** Dropped whenever the book is written to, so an edit shows up in the next lookup. */
    fun forgetNumbers() {
        cachedNumbers = null
        cachedNumbersAt = 0L
    }

    // ---------------------------------------------------------------- directories

    /**
     * Puts names to a pile of numbers, using everything this phone can reach.
     *
     * The address book first, for all of them, because that is a local query and nearly
     * always the answer. Then the directories, for the ones still nameless - and only for
     * some of those.
     *
     * The budget is the point of this being one call rather than a loop of [lookupNow].
     * A directory is a question asked over the network, and a call log can hold forty
     * numbers nobody has ever saved; asking about every one of them would turn opening the
     * calls page into a few hundred round trips. So a pass asks about a handful, the
     * answers are kept for the life of the process - the misses too, which are most of
     * them - and the rest are named the next time the page is read.
     */
    fun nameNumbers(context: Context, numbers: Collection<String>): Map<String, Contact> {
        val named = HashMap<String, Contact>()
        if (!canRead(context)) return named
        val nameless = mutableListOf<String>()
        for (number in numbers.distinct()) {
            val local = lookupNow(context, number)
            if (local != null) named[number] = local else nameless.add(number)
        }
        var budget = DIRECTORY_BUDGET
        for (number in nameless) {
            if (budget <= 0) break
            // Only a question actually put to the network costs anything. One already
            // asked and remembered - found or not found - is free and does not count.
            if (!directoryAnswers.containsKey(normalise(number))) budget--
            lookupDirectoryNow(context, number)?.let { named[number] = it }
        }
        return named
    }

    /**
     * Who owns [number], according to a directory this phone can ask.
     *
     * The case this exists for: somebody in a work account's company directory. They are
     * in the account, they show up in the accounts' own contacts app, and they are not on
     * the phone - a directory is queried live and nothing is written down - so every local
     * query says the number is nobody, and their calls and messages read as a bare number.
     *
     * Never on the main thread: this goes to the network.
     */
    fun lookupDirectoryNow(context: Context, number: String): Contact? {
        if (number.isBlank() || !canRead(context)) return null
        val key = normalise(number)
        if (key.isEmpty()) return null
        if (directoryAnswers.containsKey(key)) return directoryAnswers[key]

        var found: Contact? = null
        for (id in directories(context)) {
            found = askDirectory(context, id, number, key)
            if (found != null) break
        }
        // Remembered either way. A number that no directory knows is the common case, and
        // asking again on every read of the calls page is the expensive way to learn it.
        directoryAnswers[key] = found
        return found
    }

    private fun askDirectory(
        context: Context,
        directory: Long,
        number: String,
        key: String
    ): Contact? = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon()
                .appendPath(number)
                .appendQueryParameter(
                    ContactsContract.DIRECTORY_PARAM_KEY, directory.toString())
                .build(),
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)?.trim().orEmpty()
                // A filter is a search, not a match: a directory is free to answer with
                // anything the text resembled. Only a row whose number really is this
                // number is an answer to the question that was asked.
                if (name.isEmpty() || normalise(cursor.getString(2).orEmpty()) != key) continue
                return@use Contact(
                    id = 0L,
                    lookupKey = null,
                    name = name,
                    photoUri = cursor.getString(1),
                    starred = false,
                    directory = true,
                    number = cursor.getString(2)
                )
            }
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "A directory would not answer", e)
        null
    }

    /**
     * People a directory knows by name, for the search band.
     *
     * The other half of the same problem: somebody who is not on the phone cannot be found
     * by typing their name either, however complete the local list is. Asked only from a
     * search that has had a few letters typed into it, because every one of these is a
     * network round trip.
     */
    fun searchDirectories(context: Context, query: String, onReady: (List<Contact>) -> Unit) {
        val text = query.trim()
        if (text.length < DIRECTORY_SEARCH_FROM || !canRead(context)) {
            onReady(emptyList())
            return
        }
        val app = context.applicationContext
        executor.execute {
            val found = mutableListOf<Contact>()
            val seen = mutableSetOf<String>()
            for (id in directories(app)) {
                if (found.size >= DIRECTORY_SEARCH_LIMIT) break
                found.addAll(searchDirectory(app, id, text, seen))
            }
            main.post { onReady(found) }
        }
    }

    private fun searchDirectory(
        context: Context,
        directory: Long,
        text: String,
        seen: MutableSet<String>
    ): List<Contact> = try {
        val people = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon()
                .appendPath(text)
                .appendQueryParameter(
                    ContactsContract.DIRECTORY_PARAM_KEY, directory.toString())
                .appendQueryParameter(
                    ContactsContract.LIMIT_PARAM_KEY, DIRECTORY_SEARCH_LIMIT.toString())
                .build(),
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext() && people.size < DIRECTORY_SEARCH_LIMIT) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val number = cursor.getString(2)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                // One row per person, not per number: a directory answers with each of
                // somebody's numbers separately and a list that showed all of them would
                // be the same colleague four times over.
                if (!seen.add(name.lowercase() + "\u001f" + normalise(number))) continue
                people.add(
                    Contact(
                        id = 0L,
                        lookupKey = null,
                        name = name,
                        photoUri = cursor.getString(1),
                        starred = false,
                        directory = true,
                        number = number
                    )
                )
            }
        }
        people
    } catch (e: Exception) {
        Log.w(TAG, "A directory would not answer a search", e)
        emptyList()
    }

    /**
     * Every directory but the phone's own two.
     *
     * Zero and one are the local address book seen twice - everything visible, and the
     * contacts hidden from the list - and both are already read by every other query here.
     * What is left is the interesting kind: an account's own server-side directory, and a
     * work account's company one.
     */
    private fun directories(context: Context): List<Long> {
        cachedDirectories?.let { return it }
        val found = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                ContactsContract.Directory.CONTENT_URI,
                arrayOf(ContactsContract.Directory._ID),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id == ContactsContract.Directory.DEFAULT) continue
                    if (id == ContactsContract.Directory.LOCAL_INVISIBLE) continue
                    found.add(id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the directories", e)
        }
        cachedDirectories = found
        return found
    }

    private var cachedDirectories: List<Long>? = null

    /** Answers already had from the directories, misses included. See [lookupDirectoryNow]. */
    private val directoryAnswers =
        java.util.Collections.synchronizedMap(HashMap<String, Contact?>())

    /** How many numbers one pass may ask the network about. See [nameNumbers]. */
    private const val DIRECTORY_BUDGET = 12

    /** How many letters a search needs before it is worth asking a directory. */
    private const val DIRECTORY_SEARCH_FROM = 3

    /** How many people a directory search brings back. */
    private const val DIRECTORY_SEARCH_LIMIT = 20

    /**
     * How long the index stands before it is built again.
     *
     * Short enough that a contact synced down from an account turns up on the calls page
     * within a few minutes of arriving, long enough that scrolling a history is not a
     * scan of the address book per screenful.
     */
    private const val NUMBER_CACHE_MS = 3 * 60 * 1000L

    /** A short name for an account, for the picker. */
    fun labelOf(account: Account?): String = when {
        account == null -> "phone"
        else -> account.name
    }

    private fun uploadingAccountTypes(): Set<String> = try {
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask which accounts sync contacts", e)
        emptySet()
    }

    // ---------------------------------------------------------------- writing

    /**
     * Stars somebody, or takes the star away.
     *
     * Written against the aggregated contact rather than a raw one on purpose: being a
     * favourite is a fact about the person, and the provider spreads it across whichever
     * rows make them up.
     */
    fun setStarred(context: Context, id: Long, starred: Boolean, onDone: () -> Unit = {}) {
        forgetNumbers()
        val app = context.applicationContext
        executor.execute {
            try {
                app.contentResolver.update(
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
                    ContentValues().apply {
                        put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                    },
                    null, null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not change a favourite", e)
            }
            main.post { onDone() }
        }
    }

    /** Removes somebody from the phone, every account's copy of them included. */
    fun delete(context: Context, contact: Contact, onDone: (Boolean) -> Unit) {
        forgetNumbers()
        val app = context.applicationContext
        executor.execute {
            val gone = try {
                // By lookup key where there is one: it survives the re-merges that change
                // an id, and this call may be the second thing to happen to a contact that
                // was edited a moment ago.
                val uri = contact.lookupKey?.let {
                    Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, it)
                } ?: ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, contact.id)
                app.contentResolver.delete(uri, null, null) > 0
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete a contact", e)
                false
            }
            main.post { onDone(gone) }
        }
    }

    /** What the editor hands back: a whole contact, as typed. */
    data class Draft(
        val givenName: String,
        val familyName: String,
        val phones: List<Entry>,
        val emails: List<Entry>,
        /** The picture, if one was chosen this time round. Null leaves the old one alone. */
        val photo: Bitmap?
    ) {
        val isEmpty: Boolean
            get() = givenName.isBlank() && familyName.isBlank() &&
                phones.none { it.value.isNotBlank() } && emails.none { it.value.isNotBlank() }
    }

    /**
     * Writes a new person into [account], and hands back who they became.
     *
     * One batch, because a contact is not one row: the raw contact has to exist before
     * anything can point at it, and back-references are how that is said without a round
     * trip per field.
     */
    fun create(
        context: Context,
        account: Account?,
        draft: Draft,
        onDone: (Long?) -> Unit
    ) {
        val app = context.applicationContext
        executor.execute {
            val id = try {
                val ops = arrayListOf<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name)
                        .build()
                )
                addFields(ops, draft, backReference = 0, rawId = null)
                val results = app.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                val rawId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
                if (rawId != null && draft.photo != null) writePhoto(app, rawId, draft.photo)
                rawId?.let { contactIdOf(app.contentResolver, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not create a contact", e)
                null
            }
            main.post { onDone(id) }
        }
    }

    /**
     * Replaces the name, numbers and addresses on an existing person.
     *
     * The old rows go and the typed ones take their place, rather than each field being
     * matched up and patched. An editor that shows four numbers and takes back three has
     * to be able to say that a number was removed, and there is no way to say that by
     * updating rows - only by saying what the set is now.
     *
     * Confined to one raw contact. The others belong to accounts that will overwrite
     * anything put in them at their next sync, and the point of picking the writable row
     * in [writableRawOf] is that this is the one edit that will still be there tomorrow.
     */
    fun update(
        context: Context,
        rawId: Long,
        draft: Draft,
        onDone: (Boolean) -> Unit
    ) {
        val app = context.applicationContext
        executor.execute {
            val ok = try {
                val ops = arrayListOf<ContentProviderOperation>()
                for (mime in EDITED_MIMETYPES) {
                    ops.add(
                        ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                                    "${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), mime)
                            )
                            .build()
                    )
                }
                addFields(ops, draft, backReference = null, rawId = rawId)
                app.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                if (draft.photo != null) writePhoto(app, rawId, draft.photo)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not save a contact", e)
                false
            }
            main.post { onDone(ok) }
        }
    }

    /**
     * The name, numbers and addresses of a draft, as insert operations.
     *
     * Either against a raw contact that already exists, or against one being made in the
     * same batch - which is what [backReference] is: the row this insert belongs to has no
     * id yet, so it is named by its position in the batch instead.
     */
    private fun addFields(
        ops: MutableList<ContentProviderOperation>,
        draft: Draft,
        backReference: Int?,
        rawId: Long?
    ) {
        fun insert(mime: String): ContentProviderOperation.Builder {
            val builder =
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            if (backReference != null) {
                builder.withValueBackReference(
                    ContactsContract.Data.RAW_CONTACT_ID, backReference)
            } else {
                builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            }
            return builder.withValue(ContactsContract.Data.MIMETYPE, mime)
        }

        if (draft.givenName.isNotBlank() || draft.familyName.isNotBlank()) {
            ops.add(
                insert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                        draft.givenName.trim().ifBlank { null })
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                        draft.familyName.trim().ifBlank { null })
                    .build()
            )
        }
        for (phone in draft.phones) {
            if (phone.value.isBlank()) continue
            ops.add(
                insert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.value.trim())
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE, phoneTypeOf(phone.label))
                    .build()
            )
        }
        for (email in draft.emails) {
            if (email.value.isBlank()) continue
            ops.add(
                insert(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value.trim())
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.TYPE, emailTypeOf(email.label))
                    .build()
            )
        }
    }

    /**
     * Puts a picture on a raw contact.
     *
     * Written as a blob on the row rather than as a file: the provider makes its own
     * thumbnail and display photo from it and hands both back through `PHOTO_URI`, which
     * is what every other app on the phone - this launcher's own tile included - will read
     * it by. Compressed first because the column is passed through a Binder transaction,
     * and a full-size camera picture is larger than one of those may carry.
     */
    private fun writePhoto(context: Context, rawId: Long, photo: Bitmap) {
        val scaled = fit(photo, PHOTO_PX)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)
            out.toByteArray()
        }
        val resolver = context.contentResolver
        val where = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(
            rawId.toString(),
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        )
        val values = ContentValues().apply {
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
        }
        val updated = resolver.update(ContactsContract.Data.CONTENT_URI, values, where, args)
        if (updated == 0) {
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            values.put(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
            )
            resolver.insert(ContactsContract.Data.CONTENT_URI, values)
        }
    }

    /** Shrinks a picture to fit a square of [edge], leaving anything smaller alone. */
    private fun fit(source: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= edge) return source
        val scale = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun contactIdOf(resolver: ContentResolver, rawId: Long): Long? = resolver.query(
        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
        arrayOf(ContactsContract.RawContacts.CONTACT_ID),
        null, null, null
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    // ---------------------------------------------------------------- labels

    /**
     * The kinds of number the editor offers.
     *
     * Four, not the provider's twenty. Windows Phone asked for "mobile, home, work,
     * other" and nobody has ever needed to record which of two fax machines a number
     * belongs to on a phone.
     */
    val PHONE_LABELS = listOf("mobile", "home", "work", "other")
    val EMAIL_LABELS = listOf("personal", "work", "other")

    fun phoneTypeOf(label: String): Int = when (label.lowercase()) {
        "mobile", "cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
    }

    fun emailTypeOf(label: String): Int = when (label.lowercase()) {
        "personal", "home" -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
        "work" -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
        else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
    }

    /** Brings a provider label back to one of ours, so the editor opens on the right one. */
    fun nearestPhoneLabel(label: String): String {
        val lower = label.lowercase()
        return PHONE_LABELS.firstOrNull { it in lower } ?: "mobile"
    }

    fun nearestEmailLabel(label: String): String {
        val lower = label.lowercase()
        return EMAIL_LABELS.firstOrNull { it in lower }
            ?: if ("home" in lower) "personal" else "other"
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A number reduced to what makes it that number.
     *
     * Used to tell two rows apart, not to dial: "+38970123456" and "070 123 456" are one
     * number written twice, and a profile page listing both is a profile page that has
     * shown its filing system to somebody who wanted a phone number.
     */
    fun normalise(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > MATCH_DIGITS) digits.takeLast(MATCH_DIGITS) else digits
    }

    /**
     * A name shortened to the pair of letters that stands in for a picture.
     *
     * One from the first word and one from the last, which is how a name is abbreviated -
     * and, for somebody with a single word to their name, the first two letters of that
     * word. A lone letter in a square that size reads as something that went wrong rather
     * than as an abbreviation, and "Gorjan" is as entitled to a pair as "Gorjan Jovanovski".
     *
     * Nothing at all for a name with no letters in it. A row can be titled with a
     * telephone number - a message or a call from somebody the book does not know - and
     * "+3" is not anybody's initials. The square is left as the bare accent there, which
     * is the honest answer to a person with neither a picture nor a name.
     *
     * The one rule for the whole shell: the contact list, the favourites wall, the
     * conversations, the call screen and the Start tile all abbreviate a name through here,
     * so nobody is two different pairs of letters depending on where they are being shown.
     */
    fun initialsOf(name: String): String {
        val words = name.split(' ', '\t').filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        if (name.none { it.isLetter() }) return ""
        val first = lettersOf(words.first(), 1)
        val last = if (words.size > 1) lettersOf(words.last(), 1) else ""
        return (if (last.isNotEmpty()) first + last else lettersOf(words.first(), 2)).uppercase()
    }

    /**
     * The first [count] letters of a word, skipping anything that is not one.
     *
     * Read by code point rather than by character, so a name written in a script outside
     * the basic plane is not cut in half - and letters only, so a name in quotes or
     * brackets abbreviates to itself rather than to its punctuation.
     */
    private fun lettersOf(word: String, count: Int): String {
        val out = StringBuilder()
        var i = 0
        var taken = 0
        while (i < word.length && taken < count) {
            val point = word.codePointAt(i)
            if (Character.isLetter(point)) {
                out.appendCodePoint(point)
                taken++
            }
            i += Character.charCount(point)
        }
        return out.toString()
    }

    /** Which letter block somebody is filed under. Anything not a-z goes under '#'. */
    fun initialOf(name: String): Char {
        val first = name.trim().firstOrNull()?.lowercaseChar() ?: '#'
        return if (first in 'a'..'z') first else '#'
    }

    private const val GOOGLE = "com.google"

    /** Rewritten wholesale by an edit. The photo is not among them - it is written apart. */
    private val EDITED_MIMETYPES = listOf(
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
    )

    /**
     * How much of a number is compared when two are matched.
     *
     * The last nine digits: enough that two different people never collide, few enough
     * that a country code, a trunk zero or neither still comes out the same number.
     */
    private const val MATCH_DIGITS = 9

    /** What a contact picture is stored at. The provider thumbnails it from here. */
    private const val PHOTO_PX = 720
    private const val PHOTO_QUALITY = 88

    private const val TAG = "WP81People"
}
