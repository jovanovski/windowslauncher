package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * The phone's text messages, read and written where every other app on it can see them.
 *
 * The same bargain [PeopleStore] and [PhoneHistory] make: nothing here is this app's own.
 * Messages are read out of the platform's message store and sent through the platform's
 * own [SmsManager], so a conversation held in People is the same conversation the phone's
 * messaging app is showing, and it survives this launcher being uninstalled.
 *
 * SMS only. Multimedia messages and RCS are a different protocol carried by a different
 * provider table, and an app that showed the text half of a picture message would be worse
 * than one that plainly does not do them - see [read], which files drafts and MMS rows
 * away rather than half-rendering them.
 *
 * ### Who writes it down
 *
 * Since Android 4.4 only the phone's *default* messaging app may write to the message
 * store, and this works either way round - which it has to, because holding that role is
 * something the user says yes to rather than something this app assumes.
 *
 *  - **Not the default.** Sending still works: the platform writes a message sent through
 *    [SmsManager] by a non-default app to the store on its behalf. Arriving messages are
 *    written by whichever app *is* the default, and read out of the store here. Nothing
 *    is this app's responsibility, and nothing it does is visible to the rest of the phone
 *    beyond the messages themselves.
 *  - **The default.** Both of those become this app's job and the platform does neither.
 *    An arriving message is delivered to [SmsDeliverReceiver] and has to be written down
 *    and announced by it or it is simply lost; a sent one is written here, before it
 *    leaves, and marked as sent or failed when the network says which. Marking a
 *    conversation read starts working, because that is a write as well.
 *
 * The one thing that does not survive the role is multimedia messages. They arrive as a
 * push that only the default messaging app is handed, and this app cannot fetch one - see
 * [MmsDeliverReceiver], which says so out loud rather than dropping them in silence.
 */
object MessageStore {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Sending, on a thread of its own.
     *
     * Not the executor above, which is where the reads queue - and a read now asks the
     * address book to name the numbers in it, which can mean a directory somewhere
     * answering over the network. A message waiting its turn behind that is a message that
     * leaves seconds late, and when the thing waiting is a reply typed into a notification,
     * seconds late is a broadcast that never finished. See MessageActionReceiver.
     */
    private val sender = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    /**
     * One conversation, as the list of them shows it.
     *
     * Folded by number rather than by the store's own `thread_id`, for the reason
     * [PhoneHistory.forNumber] matches calls by number: the same person texted from a
     * contact card and from a number typed by hand can end up in two threads, and two rows
     * for one person is a filing system showing through.
     */
    data class Conversation(
        val address: String,
        /** Who the number belongs to, where the address book knows. */
        val contact: PeopleStore.Contact?,
        val snippet: String,
        val at: Long,
        /** Whether the last thing said was said by this phone. */
        val outgoing: Boolean,
        val unread: Int
    ) {
        val title: String get() = contact?.name?.takeIf { it.isNotBlank() } ?: address
    }

    /** One message in a conversation. */
    data class Message(
        val id: Long,
        val body: String,
        val at: Long,
        val outgoing: Boolean,
        /** The store says this one never went. */
        val failed: Boolean,
        /** Still on its way out of the phone. */
        val sending: Boolean
    )

    /** What a read came back with, and whether it came back at all. See [PhoneHistory.Result]. */
    data class Result(val conversations: List<Conversation>, val failed: Boolean)

    /** Reading the messages, and sending one. Asked for separately, where each is needed. */
    fun readPermissions(): Array<String> = arrayOf(Manifest.permission.READ_SMS)

    fun sendPermissions(): Array<String> = arrayOf(Manifest.permission.SEND_SMS)

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun canSend(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether this app is the phone's messaging app.
     *
     * Asked of `RoleManager`, the same way [rocks.gorjan.gokixp.apps.people.PeopleApp]
     * asks whether it is the phone, and *not* of `Telephony.Sms.getDefaultSmsPackage` -
     * which is the obvious call to make here and is wrong from an ordinary app's process.
     * It ends up in `SmsApplication`, which asks the role service for the holder through a
     * system-only call and hands back null when an unprivileged app asks; so an app that
     * holds the role is told it does not.
     *
     * That was worth more than a stale line of text on the page. This also decides whether
     * a sent message is written to the store, and while the role is held nothing else
     * writes it - so an app wrongly told it was not the messaging app sent messages that
     * were delivered and then recorded nowhere.
     *
     * [android.app.role.RoleManager.isRoleHeld] answers about the caller and needs no
     * permission to do it, which is the whole reason it exists.
     */
    fun isTheMessenger(context: Context): Boolean {
        try {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null) {
                return roles.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask whether this app handles messages", e)
        }
        // Only for a phone with no role service at all, where the setting is still the
        // whole of the answer.
        return try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask which app handles messages", e)
            false
        }
    }

    // ---------------------------------------------------------------- reading

    /** Every conversation this phone has had, most recently spoken in first. */
    fun conversations(context: Context, onReady: (Result) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = try {
                Result(fold(app, read(app, LIST_SCAN, LIST_SCAN, wanted = null)), failed = false)
            } catch (e: Exception) {
                // Said out loud rather than folded into an empty list, exactly as the call
                // log is: a store that cannot be read and a phone nobody has texted are
                // different facts, and showing the second when the first is true is how a
                // broken query hides behind a page that looks like it is working.
                Log.w(TAG, "Could not read the messages", e)
                Result(emptyList(), failed = true)
            }
            main.post { onReady(result) }
        }
    }

    /** Everything said to and from one number, oldest first - the order it is read in. */
    fun conversation(context: Context, address: String, onReady: (List<Message>) -> Unit) {
        val app = context.applicationContext
        val wanted = keyOf(address)
        executor.execute {
            val messages = try {
                read(app, THREAD_KEEP, THREAD_SCAN, wanted)
                    .map {
                        Message(
                            id = it.id,
                            body = it.body,
                            at = it.at,
                            outgoing = isOutgoing(it.type),
                            failed = it.type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                            sending = it.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                                it.type == Telephony.Sms.MESSAGE_TYPE_QUEUED
                        )
                    }
                    // Read newest first, because that is the end of the store worth
                    // reading; shown oldest first, because that is how a conversation goes.
                    .reversed()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read one conversation", e)
                emptyList()
            }
            main.post { onReady(messages) }
        }
    }

    /**
     * The last few messages with somebody, read where the caller stands.
     *
     * For the notification, which is built on a receiver's worker thread and wants the
     * conversation the message it is announcing belongs to - a text that arrives while an
     * earlier one is still unread should show both, and the store is the only place that
     * knows there was an earlier one. Never call it from the main thread.
     */
    fun recentNow(context: Context, address: String, limit: Int): List<Message> = try {
        read(context, limit, THREAD_SCAN, keyOf(address))
            .map {
                Message(
                    id = it.id,
                    body = it.body,
                    at = it.at,
                    outgoing = isOutgoing(it.type),
                    failed = it.type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                    sending = it.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                        it.type == Telephony.Sms.MESSAGE_TYPE_QUEUED
                )
            }
            .reversed()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a conversation", e)
        emptyList()
    }

    private data class Row(
        val id: Long,
        val address: String,
        val body: String,
        val at: Long,
        val type: Int,
        val read: Boolean
    )

    /**
     * Rows out of the message store, newest first.
     *
     * Two caps rather than one. [keep] is how many rows are wanted; [scan] is how many are
     * looked at to find them, which matters because [wanted] filters in this loop rather
     * than in the query - the store keeps an address in whatever form it arrived in, and a
     * `WHERE address = ?` against one of those forms finds a fraction of a conversation.
     * See [keyOf].
     *
     * No `LIMIT` on the query either. The call log has a query parameter for it and this
     * provider has none, and a limit written into the sort order is thrown out wholesale
     * by a provider that builds its statements strictly - so the cap is applied by walking
     * away from the cursor, which fetches in windows and never reads the rest.
     */
    private fun read(context: Context, keep: Int, scan: Int, wanted: String?): List<Row> {
        val rows = mutableListOf<Row>()
        var seen = 0
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && seen < scan && rows.size < keep) {
                seen++
                val address = cursor.getString(1)?.trim().orEmpty()
                // Nothing to file it under and nothing to answer. A message from a withheld
                // number is a thing that happened to a phone, not a conversation.
                if (address.isEmpty()) continue
                val type = cursor.getInt(4)
                // Something typed and never sent belongs to whichever app it was typed in.
                if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                if (wanted != null && keyOf(address) != wanted) continue
                rows.add(
                    Row(
                        id = cursor.getLong(0),
                        address = address,
                        body = cursor.getString(2).orEmpty(),
                        at = cursor.getLong(3),
                        type = type,
                        read = cursor.getInt(5) != 0
                    )
                )
            }
        }
        return rows
    }

    /** Collapses the rows into one entry per person, keeping the newest of each. */
    private fun fold(context: Context, rows: List<Row>): List<Conversation> {
        val byKey = LinkedHashMap<String, MutableList<Row>>()
        // Insertion order is the order the rows came in, which is newest first - so the
        // conversations come out in the order they were last spoken in, for free.
        for (row in rows) byKey.getOrPut(keyOf(row.address)) { mutableListOf() }.add(row)

        // Once per conversation rather than once per message - a list of four hundred
        // messages is rarely four hundred people - and in one call, so the directories are
        // asked about a handful of the unknown numbers rather than all of them. See
        // PeopleStore.nameNumbers.
        val named = PeopleStore.nameNumbers(context, byKey.values.map { it.first().address })
        return byKey.values.map { thread ->
            val newest = thread.first()
            Conversation(
                address = newest.address,
                contact = named[newest.address],
                snippet = newest.body.replace('\n', ' ').trim(),
                at = newest.at,
                outgoing = isOutgoing(newest.type),
                // Only what was scanned. A conversation whose unread messages are older
                // than the last few hundred on the phone is one the count understates,
                // which is the harmless direction for a number nobody acts on.
                unread = thread.count { !it.read && !isOutgoing(it.type) }
            )
        }
    }

    private fun isOutgoing(type: Int): Boolean = type != Telephony.Sms.MESSAGE_TYPE_INBOX

    /**
     * What two addresses have to share to be one conversation.
     *
     * A number reduced the way [PeopleStore.normalise] reduces one, so "+38970123456" and
     * "070 123 456" are the same person. Anything without enough digits to be a telephone
     * number - a bank, a network, a two-factor code, all of which arrive from a *word* -
     * is filed under itself instead: normalising those would leave every one of them with
     * the same empty key, and the page would show one conversation with everybody.
     */
    fun keyOf(address: String): String {
        val digits = address.count { it.isDigit() }
        if (digits < SHORT_CODE_DIGITS) return address.trim().lowercase()
        return PeopleStore.normalise(address)
    }

    // ---------------------------------------------------------------- writing

    /**
     * Sends a message, and says what happened.
     *
     * [onDone] is handed null when it went and a sentence when it did not - which is worth
     * having rather than assuming, because a message can fail for reasons that have
     * nothing to do with this app: no service, no credit, a number the network will not
     * take. Long messages are split into as many parts as the encoding needs and reported
     * on once, when the last part is accounted for.
     *
     * Written to the store here only while this app is the phone's messaging app, because
     * that is exactly when the platform stops doing it. See the note on the object.
     */
    fun send(
        context: Context,
        address: String,
        body: String,
        /**
         * Called once the message is with the radio, before the network has said whether
         * it arrived. For a caller that is holding something open until the send is under
         * way - a broadcast, which is on a clock - and must not hold it until the answer.
         */
        onDispatched: (() -> Unit)? = null,
        onDone: (String?) -> Unit
    ) {
        if (address.isBlank() || body.isEmpty()) {
            onDispatched?.invoke()
            onDone("There is nothing to send")
            return
        }
        if (!canSend(context)) {
            onDispatched?.invoke()
            onDone("This app has not been allowed to send messages")
            return
        }
        val app = context.applicationContext
        val manager = smsManager(app)
        if (manager == null) {
            onDispatched?.invoke()
            onDone("This phone cannot send messages")
            return
        }
        sender.execute {
            // Written down before it leaves rather than after it arrives, so a message
            // sent on a bad signal is a row that says it is on its way instead of nothing
            // at all until the network answers. The row is finished either way; see [finish].
            val row = if (isTheMessenger(app)) writeOutgoing(app, address, body) else null
            try {
                dispatch(app, manager, address, body, row, onDone)
            } finally {
                onDispatched?.invoke()
            }
        }
    }

    /** Hands the parts to the radio and waits to be told about every one of them. */
    private fun dispatch(
        app: Context,
        manager: SmsManager,
        address: String,
        body: String,
        row: android.net.Uri?,
        onDone: (String?) -> Unit
    ) {
        val parts = try {
            manager.divideMessage(body)
        } catch (e: Exception) {
            Log.w(TAG, "Could not divide the message", e)
            arrayListOf(body)
        }

        // An action of its own per send, so two messages leaving at once are told apart.
        val action = SENT_ACTION + "." + (nextSend++)
        var outstanding = parts.size
        var failure: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK && failure == null) {
                    failure = reasonFor(resultCode)
                }
                // Every part has to be accounted for before the answer is given: a message
                // in three parts of which the second failed did not arrive.
                outstanding--
                if (outstanding > 0) return
                try {
                    app.unregisterReceiver(this)
                } catch (e: Exception) {
                    Log.d(TAG, "The sent-message receiver was already gone", e)
                }
                val problem = failure
                executor.execute {
                    finish(app, row, failed = problem != null)
                    main.post { onDone(problem) }
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }

        val reports = ArrayList<PendingIntent>(parts.size)
        for (part in parts.indices) {
            reports.add(
                PendingIntent.getBroadcast(
                    app,
                    // Distinct codes, or the parts would all be the one pending intent and
                    // only one report would ever arrive.
                    part,
                    Intent(action).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        try {
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, reports, null)
            } else {
                manager.sendTextMessage(address, null, body, reports[0], null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "The message would not go", e)
            try {
                app.unregisterReceiver(receiver)
            } catch (e2: Exception) {
                Log.d(TAG, "The sent-message receiver was already gone", e2)
            }
            executor.execute {
                finish(app, row, failed = true)
                main.post { onDone("The message could not be sent") }
            }
        }
    }

    private fun smsManager(context: Context): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (e: Exception) {
        Log.w(TAG, "This phone has no message service", e)
        null
    }

    /** What went wrong, in words somebody who was texting would recognise. */
    private fun reasonFor(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "The radio is off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "The message could not be sent"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Too many messages have been sent"
        else -> "The message could not be sent"
    }

    /** A message on its way out, in the store, so the conversation has it while it goes. */
    private fun writeOutgoing(context: Context, address: String, body: String): android.net.Uri? =
        try {
            context.contentResolver.insert(
                Telephony.Sms.Outbox.CONTENT_URI,
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    // Your own message is not something you have to be told about.
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    threadIdFor(context, address)?.let { put(Telephony.Sms.THREAD_ID, it) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a sent message down", e)
            null
        }

    /**
     * Marks a written row as sent or as failed, once the network has said which.
     *
     * A row left in the outbox is a message that is permanently about to be sent, which is
     * the one state a conversation must never be able to get stuck in.
     */
    private fun finish(context: Context, row: android.net.Uri?, failed: Boolean) {
        if (row == null) return
        try {
            context.contentResolver.update(
                row,
                ContentValues().apply {
                    put(
                        Telephony.Sms.TYPE,
                        if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED
                        else Telephony.Sms.MESSAGE_TYPE_SENT
                    )
                },
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark a sent message", e)
        }
    }

    /**
     * Forgets one message.
     *
     * Only used for a failed one that is being sent again: the row is a record of an
     * attempt rather than of anything that was said, and leaving it beside the new attempt
     * would make one message that eventually went look like two, one of which never did.
     * Refused unless this app is the phone's messaging app, and quietly, like [markRead].
     */
    fun forget(context: Context, id: Long) {
        val app = context.applicationContext
        executor.execute {
            try {
                app.contentResolver.delete(
                    android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.d(TAG, "A message could not be forgotten", e)
            }
        }
    }

    /**
     * Writes an arriving message down. Called from [SmsDeliverReceiver], on its thread.
     *
     * The whole of what being the phone's messaging app means, in one insert: a message
     * that is not written here does not exist for any app on the phone, this one included.
     * So it is done first, before the notification and before anything else that could
     * fail, and it says whether it worked.
     */
    fun writeIncoming(
        context: Context,
        address: String,
        body: String,
        sentAt: Long,
        subscription: Int
    ): Boolean = try {
        context.contentResolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                // When it reached the phone, and when it was sent. They differ by however
                // long the message spent in the network, which is minutes often enough.
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                if (sentAt > 0L) put(Telephony.Sms.DATE_SENT, sentAt)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                if (subscription >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscription)
                threadIdFor(context, address)?.let { put(Telephony.Sms.THREAD_ID, it) }
            }
        ) != null
    } catch (e: Exception) {
        Log.w(TAG, "Could not write an arriving message down", e)
        false
    }

    /**
     * The store's own id for the conversation with somebody.
     *
     * Written into the row so every other app on the phone groups the message the way this
     * one does. The provider will work it out for itself if it is left off, but only from
     * the address as given - and asking outright is the same question every messaging app
     * asks, which is what keeps the answers the same.
     */
    private fun threadIdFor(context: Context, address: String): Long? = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        Log.d(TAG, "The store would not name a conversation", e)
        null
    }

    /**
     * Marks a conversation as read.
     *
     * Only the phone's messaging app may write this, so it works when this app is that and
     * is quietly refused when it is not - which is the honest shape for it: on a phone
     * where People holds the role, reading a message here clears its mark everywhere; on
     * one where it does not, the mark stays until it is read where it was delivered.
     * Neither is worth interrupting somebody who is reading to explain.
     */
    fun markRead(context: Context, address: String) {
        if (!canRead(context)) return
        val app = context.applicationContext
        val wanted = keyOf(address)
        executor.execute {
            try {
                val unread = read(app, THREAD_KEEP, THREAD_SCAN, wanted)
                    .filter { !it.read && !isOutgoing(it.type) }
                    .map { it.id }
                if (unread.isEmpty()) return@execute
                app.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    },
                    Telephony.Sms._ID + " IN (" + unread.joinToString(",") + ")",
                    null
                )
            } catch (e: Exception) {
                Log.d(TAG, "The messages could not be marked read", e)
            }
        }
    }

    // ---------------------------------------------------------------- watching

    /**
     * Says when the message store changes, which is how messages arrive here.
     *
     * There is no broadcast to listen for - the one an arriving message raises goes to the
     * app that has to write it down - so what is watched is the writing itself. A page
     * holding one of these re-reads and redraws, which is a message appearing in the
     * conversation while it is open, and a conversation moving to the top of the list.
     */
    fun watch(context: Context, onArrival: () -> Unit): ContentObserver? {
        if (!canRead(context)) return null
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onArrival()
        }
        return try {
            context.applicationContext.contentResolver
                .registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            observer
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch for messages", e)
            null
        }
    }

    fun unwatch(context: Context, observer: ContentObserver?) {
        if (observer == null) return
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.d(TAG, "The message watch was already gone", e)
        }
    }

    /** Counts up so two sends in flight at once report to different actions. See [send]. */
    private var nextSend = 0

    private const val SENT_ACTION = "rocks.gorjan.gokixp.sms.SENT"

    /**
     * How many rows the list of conversations reads.
     *
     * Deep enough that a phone which gets a lot of one-way traffic from its bank still
     * shows the people it talks to, shallow enough that opening the page is one query.
     */
    private const val LIST_SCAN = 600

    /** How many messages one conversation shows, and how far back it looks to find them. */
    private const val THREAD_KEEP = 300
    private const val THREAD_SCAN = 4000

    /**
     * How many digits an address needs before it is treated as a telephone number.
     *
     * Four, which is the shortest a real short code gets. Below that there is nothing to
     * normalise and the address is a word. See [keyOf].
     */
    private const val SHORT_CODE_DIGITS = 4

    private const val TAG = "WP81People"
}
