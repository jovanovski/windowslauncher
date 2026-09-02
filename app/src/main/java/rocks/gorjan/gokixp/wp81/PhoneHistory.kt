package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import java.util.concurrent.Executors

/**
 * The call log, as Windows Phone's history page read it.
 *
 * The provider keeps one row per call. The phone showed one row per *conversation*: three
 * calls to the same person in an afternoon were one line saying so, with the count beside
 * the name. That is the whole of what this does beyond querying - collapse a run of calls
 * to the same number into a single entry, keep the most recent one's time and direction,
 * and count how many were folded in.
 *
 * Runs of them, not totals. Two calls this morning and one last Tuesday are two entries,
 * because the history is a diary and the Tuesday call happened on Tuesday. Only calls that
 * were *next to each other* in the log are folded, which is exactly what the phone did.
 */
object PhoneHistory {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    enum class Direction { INCOMING, OUTGOING, MISSED }

    /** A run of calls to or from one number, newest first in the list. */
    data class Entry(
        val number: String,
        /** The name the log had for them, or empty for a number that is nobody yet. */
        val name: String,
        val direction: Direction,
        /** When the most recent of the run happened. */
        val at: Long,
        /** How many calls were folded together. One for most rows. */
        val count: Int,
        /** How long the most recent of them lasted, in seconds. Zero if it never connected. */
        val seconds: Long,
        /** The aggregated contact behind the number, where the log knew of one. */
        val contactId: Long?
    ) {
        /** What to put on the row: their name if the phone knows it, the number if not. */
        val title: String get() = name.ifBlank { number }
    }

    fun permissions(): Array<String> = arrayOf(Manifest.permission.READ_CALL_LOG)

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The most recent calls, folded and newest first.
     *
     * [limit] is on the rows read rather than on the entries returned: it is a cap on the
     * work, and a phone that called one person twenty times over lunch should not have its
     * history cut short by that.
     */
    fun recent(context: Context, limit: Int = LIMIT, onReady: (Result) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = try {
                Result(fold(read(app, limit)), failed = false)
            } catch (e: Exception) {
                // Said out loud rather than folded into an empty list. A log that cannot be
                // read and a phone that has made no calls are different facts, and showing
                // the second when the first is true is how a broken query hides: the page
                // says "no calls yet" and looks like it is working.
                Log.w(TAG, "Could not read the call log", e)
                Result(emptyList(), failed = true)
            }
            main.post { onReady(result) }
        }
    }

    /** What a read came back with, and whether it came back at all. */
    data class Result(val entries: List<Entry>, val failed: Boolean)

    private data class Call(
        val number: String,
        val name: String,
        val direction: Direction,
        val at: Long,
        val seconds: Long,
        val contactId: Long?
    )

    private fun read(context: Context, limit: Int): List<Call> {
        val calls = mutableListOf<Call>()
        context.contentResolver.query(
            // The cap goes on the URI, not on the sort order.
            //
            // `ORDER BY date DESC LIMIT 400` is valid SQL and was how everybody wrote this
            // for years, but the providers now build their queries strictly and throw the
            // whole statement out for having a keyword in the sort clause - which came back
            // here as a history that was simply empty. The call log has a query parameter
            // for exactly this, and it is the only form that is guaranteed to survive.
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                .build(),
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0)?.trim().orEmpty()
                // A withheld number has nothing to call back and nothing to file under.
                if (number.isEmpty() || number == "-1" || number == "-2") continue
                calls.add(
                    Call(
                        number = number,
                        name = cursor.getString(1)?.trim().orEmpty(),
                        direction = when (cursor.getInt(2)) {
                            CallLog.Calls.OUTGOING_TYPE -> Direction.OUTGOING
                            // A rejected or blocked call is one that came in and was not
                            // answered, which is what missed means to the person reading.
                            CallLog.Calls.INCOMING_TYPE -> Direction.INCOMING
                            else -> Direction.MISSED
                        },
                        at = cursor.getLong(3),
                        seconds = cursor.getLong(4),
                        contactId = null
                    )
                )
            }
        }
        return resolveContacts(context, calls)
    }

    /**
     * Attaches the contact behind each number, where there is one.
     *
     * The log's own `CACHED_NAME` is a copy taken when the call happened, so it is right
     * about the name and says nothing about who to open when the row is tapped - and it is
     * stale for anyone renamed since. Looked up once per distinct number rather than once
     * per call, because a history of forty calls is rarely forty people.
     */
    private fun resolveContacts(context: Context, calls: List<Call>): List<Call> {
        if (calls.isEmpty()) return calls
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return calls

        // Through PeopleStore rather than a PhoneLookup of its own, which is what this
        // used to be. One resolver for the whole app: the calls page, the conversations
        // and the missed-call notification all have the same question to ask, and three
        // answers to it meant a person could be named in one place and a bare number in
        // another. See PeopleStore.lookupNow, which reads every account there is.
        val byNumber = PeopleStore.nameNumbers(context, calls.map { it.number })
        return calls.map { call ->
            val found = byNumber[call.number]
            if (found == null) call
            // No id for somebody who came out of a directory: they have a name and no row
            // in the book, so the row offers to save them rather than to open a card that
            // does not exist. See PeopleStore.Contact.directory.
            else call.copy(
                contactId = found.id.takeIf { !found.directory },
                name = found.name.ifBlank { call.name }
            )
        }
    }

    /** Collapses each run of calls to the same number into one entry. See the class note. */
    private fun fold(calls: List<Call>): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (call in calls) {
            val last = entries.lastOrNull()
            // The same person and the same kind of call. A missed call followed by them
            // ringing back is two things that happened, not one thing twice.
            if (last != null &&
                PeopleStore.normalise(last.number) == PeopleStore.normalise(call.number) &&
                last.direction == call.direction
            ) {
                entries[entries.size - 1] = last.copy(count = last.count + 1)
                continue
            }
            entries.add(
                Entry(
                    number = call.number,
                    name = call.name,
                    direction = call.direction,
                    at = call.at,
                    count = 1,
                    seconds = call.seconds,
                    contactId = call.contactId
                )
            )
        }
        return entries
    }

    /**
     * Every call to or from one number, newest first and not folded.
     *
     * The history page folds a run of calls into one line, which is the right summary and
     * the wrong thing entirely when the question is "when did we speak, and for how long".
     * So this is the same log read again without the folding, narrowed to one number.
     *
     * Matched on the normalised number rather than by asking the provider for it: the log
     * holds whatever form each call arrived in - with a country code, without, spaced,
     * punctuated - and a `WHERE number = ?` against one of those forms finds a fraction of
     * the calls with the same person. See [PeopleStore.normalise].
     */
    fun forNumber(context: Context, number: String, onReady: (List<Entry>) -> Unit) {
        val app = context.applicationContext
        val wanted = PeopleStore.normalise(number)
        executor.execute {
            val entries = try {
                read(app, DEEP_LIMIT)
                    .filter { PeopleStore.normalise(it.number) == wanted }
                    .map {
                        Entry(
                            number = it.number,
                            name = it.name,
                            direction = it.direction,
                            at = it.at,
                            count = 1,
                            seconds = it.seconds,
                            contactId = it.contactId
                        )
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read one number's calls", e)
                emptyList()
            }
            main.post { onReady(entries) }
        }
    }

    /** Empties the log. Behind a confirmation wherever it is offered. */
    fun clear(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                app.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear the call log", e)
            }
            main.post { onDone() }
        }
    }

    /**
     * How many rows are read. Deep enough that scrolling the history reaches last month,
     * shallow enough that opening the app is one short query.
     */
    private const val LIMIT = 400

    /**
     * How deep one person's own history reads.
     *
     * Further than the main list, because it is being sifted for one number and most of
     * what it reads will be thrown away - a page that showed three calls with somebody
     * because the fourth was four hundred calls ago would be worse than useless.
     */
    private const val DEEP_LIMIT = 2000

    private const val TAG = "WP81People"
}
