package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * The address book, for the People tile.
 *
 * WP8.1's People tile was a wall of faces turning over one square at a time, and the
 * phone already holds everything it needs: the aggregated contacts, the pictures their
 * accounts synced, and which of them are starred.
 *
 * Handed over in two parts rather than one list. The favourites are who the tile is
 * *about* - an address book on a phone today is not the fifty people it was, it is every
 * courier, letting agent and one-time colleague an account has ever synced, and a wall
 * shuffling through those is a wall of strangers. But a tile with more squares than the
 * user has favourites would be a wall with holes in it, so the rest of the book stands
 * behind them: the tile takes as many as it needs to fill itself, and a few more to have
 * somebody to turn over to. Who gets that job is [TileView.setPeopleMosaic]'s to decide,
 * because the number of squares is a property of the tile and not of the phone.
 *
 * Faces first within each part. A mosaic is made of pictures, so the people who have one
 * come before the people who do not - see [people]. Those fall back to initials rather
 * than to a row of identical silhouettes.
 */
object ContactFeed {

    /**
     * How many people the tile keeps in hand, of each kind.
     *
     * Far more than a tile has squares, which is the point: the cap is here so a phone
     * with three thousand contacts on it still costs a tile one walk of the cursor and a
     * list it can hold, rather than a queue nobody would reach the end of anyway.
     */
    const val LIMIT = 60

    /**
     * How large a face is decoded.
     *
     * A square of a mosaic is small, but the same picture fills the whole tile when the
     * mosaic turns one of them over to the front - so it is decoded for that rather than
     * for the square, and the squares get a picture that is sharper than they need.
     */
    private const val DECODE_PX = 384

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** One person, as a square of the mosaic shows them. */
    data class Person(
        /** The aggregated contact id, which is the identity the rotation is keyed on. */
        val id: String,
        val name: String,
        /** Their picture, or null for the ones who never got round to one. */
        val photoUri: String?,
        /** What stands in for a picture: one or two letters of their name. */
        val initials: String
    )

    /**
     * The address book in the two parts the tile fills itself from.
     *
     * [others] is only ever drawn on to make up the numbers - see the note on the class -
     * so the tile stays about the people the user marked however few of those there are.
     */
    data class Book(
        val favourites: List<Person>,
        val others: List<Person>
    ) {
        val isEmpty: Boolean get() = favourites.isEmpty() && others.isEmpty()
    }

    fun permissions(): Array<String> = arrayOf(Manifest.permission.READ_CONTACTS)

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The favourites and everybody else, each with the pictured ones first.
     *
     * Four bands collapsed into two: starred-with-a-picture, starred-without, then the
     * same pair for the rest. Pictures first because a mosaic is made of them, and a tile
     * that opened on a grid of letters when there were faces to be had further down the
     * list would be showing the address book's filing order rather than the people in it.
     *
     * Read off the main thread: this is a query against every contact on the phone.
     */
    fun people(context: Context, limit: Int = LIMIT, onReady: (Book) -> Unit) {
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val book = try {
                read(resolver, limit)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the address book", e)
                Book(emptyList(), emptyList())
            }
            main.post { onReady(book) }
        }
    }

    private fun read(resolver: ContentResolver, limit: Int): Book {
        val starredPictured = mutableListOf<Person>()
        val starredNamed = mutableListOf<Person>()
        val pictured = mutableListOf<Person>()
        val named = mutableListOf<Person>()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED
            ),
            // The visible ones, and every favourite whether or not it is one of them:
            // `IN_VISIBLE_GROUP` is what keeps the sync scratch rows and half-merged
            // duplicates out of the backfill, and a contact the user went and starred is
            // one they know about whatever their accounts think of the group it is in.
            "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1" +
                " OR ${ContactsContract.Contacts.STARRED} = 1",
            null,
            ContactsContract.Contacts.SORT_KEY_PRIMARY
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val name =
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photo = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
            val starred = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
            val bands = listOf(starredPictured, starredNamed, pictured, named)
            while (cursor.moveToNext()) {
                // Every band full: nothing further down the alphabet can change what the
                // tile shows, so the walk stops rather than reading out the rest.
                if (bands.all { it.size >= limit }) break
                val display = cursor.getString(name)?.trim().orEmpty()
                // A contact with no name at all is a row from a sync, not a person: there
                // is nothing to put in the square and nothing to put under the picture.
                if (display.isEmpty()) continue
                val picture = cursor.getString(photo)
                val person = Person(
                    id = cursor.getString(id) ?: continue,
                    name = display,
                    photoUri = picture,
                    initials = PeopleStore.initialsOf(display)
                )
                val band = when {
                    cursor.getInt(starred) != 0 && picture != null -> starredPictured
                    cursor.getInt(starred) != 0 -> starredNamed
                    picture != null -> pictured
                    else -> named
                }
                if (band.size < limit) band.add(person)
            }
        }

        return Book(
            favourites = (starredPictured + starredNamed).take(limit),
            others = (pictured + named).take(limit)
        )
    }

    /** A tenth of the heap, shared by every tile showing faces. */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 10).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Pictures already tried and found unreadable, so a dead URI is not decoded twice. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** The picture at [uri] if it has already been decoded. For drawing, which cannot wait. */
    fun cached(uri: String): Bitmap? = cache.get(uri)

    /**
     * Hands [onReady] the picture at [uri], now if it is already held and later if not.
     *
     * The callback runs on the main thread, and may hand back null: a contact can lose
     * their picture between the query that found it and the square that comes round to it.
     */
    fun load(context: Context, uri: String, onReady: (Bitmap?) -> Unit) {
        if (uri.isBlank() || uri in failed) {
            onReady(null)
            return
        }
        cache.get(uri)?.let {
            onReady(it)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val bitmap = decode(app, uri)
            if (bitmap == null) failed.add(uri) else cache.put(uri, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    private fun decode(context: Context, uri: String): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software rather than hardware: the mosaic draws these into a canvas it also
            // rotates and clips, and a hardware bitmap is not welcome everywhere that
            // happens. The same reasoning as PhotoFeed's.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > DECODE_PX) {
                val scale = DECODE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a contact picture", e)
        null
    }

    private const val TAG = "WP81People"
}
