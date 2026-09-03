package rocks.gorjan.gokixp.wp81

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import org.json.JSONObject
import rocks.gorjan.gokixp.MainActivity

/**
 * What the user has been in lately - from both of the places this launcher has to look.
 *
 * There is no single list to read, because there are two kinds of app here and the phone
 * only knows about one of them:
 *
 *  - **Android's own apps** are real tasks, and the phone keeps a history of them. That
 *    history is [UsageStatsManager], read as a stream of ACTIVITY_RESUMED events - the
 *    moments something came to the front - which is the only account of "where the user
 *    has been" a third-party launcher is allowed. It needs a special access the user has
 *    to grant by hand, and may never be granted at all.
 *  - **This shell's own programs** - Music, People, Alarms, News, Files - are not tasks.
 *    They are windows inside the launcher's one activity, so as far as the phone is
 *    concerned opening Zune is the launcher being in the foreground, and every one of
 *    them is invisible to the history above. They have to be written down as they are
 *    opened, which is what [noteSystemApp] is for.
 *
 * Both halves carry a wall-clock millisecond, so merging them is a sort: see [recents].
 * That is the whole reason the launcher's half records a *time* rather than keeping an
 * order of its own - an order cannot be interleaved with anything.
 *
 * Nothing here can close an app. A third-party launcher has no way to kill another app's
 * task, so [dismiss] only drops an entry from this list; see its own note.
 */
class RecentAppsStore(
    private val context: Context,
    /**
     * Whether the phone will answer at all.
     *
     * Handed in rather than worked out here, because the launcher already asks this
     * question in several places and one answer is worth more than a second copy of the
     * AppOpsManager call that produces it.
     */
    private val hasUsageAccess: () -> Boolean
) {

    /** One app, and when it was last in front. */
    data class Visit(
        /** A package name, or one of the shell's own `system.*` identifiers. */
        val id: String,
        val at: Long
    )

    // ---------------------------------------------------------------- the phone's half

    /**
     * The last app the phone itself had in front, whoever opened it.
     *
     * What back-back on Start is after: an app reached from a notification, or from a link
     * inside another app, is somewhere the user was just as much as a tile they tapped.
     * Null when the access has not been granted, or when nothing in the window qualifies.
     */
    fun lastForegroundApp(): String? = androidVisits().firstOrNull()?.id

    /**
     * Everything the phone will admit to, most recent first and one entry per app.
     *
     * A day covers every ordinary case in one short scan. The week is only reached when
     * that finds nothing at all - a phone picked up the morning after - and is as far back
     * as Android keeps events anyway.
     */
    fun androidVisits(): List<Visit> {
        if (!hasUsageAccess()) return emptyList()
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        return try {
            val now = System.currentTimeMillis()
            androidVisits(usage, now - LOOKBACK_MS, now)
                .ifEmpty { androidVisits(usage, now - LOOKBACK_LONG_MS, now) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the phone's app history: ${e.message}")
            emptyList()
        }
    }

    /**
     * One scan of the history window, boiled down to one entry per app.
     *
     * An app visited five times in an afternoon is one thing the user has been in, not
     * five, so the map is rewritten in place and each package ends up carrying the latest
     * time it was seen at rather than the first.
     */
    private fun androidVisits(usage: UsageStatsManager, from: Long, to: Long): List<Visit> {
        val events = usage.queryEvents(from, to)
        val event = UsageEvents.Event()
        // Whether a package can be opened at all is asked of the package manager once per
        // package rather than once per event; a day of history is a few thousand events.
        val launchable = HashMap<String, Boolean>()
        val latest = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // The moment an activity came to the front. Events arrive oldest first, so the
            // last one written for a package is the most recent.
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = event.packageName ?: continue
            // The launcher itself is never one of these. Its own programs are the other
            // half of this list and are counted there, by name; counted here it would be
            // one entry called "GokiXP" standing for all of them.
            if (pkg == context.packageName) continue
            // Keyboards, dialogs and the system's own screens come through here too, and
            // none of them is an app the user would say they had been in.
            if (!launchable.getOrPut(pkg) {
                    context.packageManager.getLaunchIntentForPackage(pkg) != null
                }
            ) continue
            latest[pkg] = event.timeStamp
        }
        return latest.map { (pkg, at) -> Visit(pkg, at) }.sortedByDescending { it.at }
    }

    // ---------------------------------------------------------------- the shell's half

    /**
     * Notes that one of the shell's own programs has just been opened.
     *
     * Written to preferences rather than held in a field for the same reason the last
     * launched app is: a launcher is killed between visits more often than any other app
     * on the phone, and a switcher that forgot everything the moment the process went away
     * would be empty exactly when it is reached for.
     */
    fun noteSystemApp(id: String) {
        if (!MainActivity.isSystemApp(id)) return
        write(KEY_SYSTEM_OPENS, read(KEY_SYSTEM_OPENS) + (id to System.currentTimeMillis()))
    }

    /**
     * The shell's own programs that have been opened, limited to those [available] under
     * the shell currently running.
     *
     * The filter is what keeps the Phone Dialer off the phone and Music off the desktop
     * without this knowing either rule: the caller passes the list it is already building
     * for the app list, which has the rules applied to it. See MainActivity's
     * isWindowsPhoneOnlyApp and isDesktopOnlyApp.
     */
    private fun systemVisits(available: Set<String>): List<Visit> =
        read(KEY_SYSTEM_OPENS)
            .filterKeys { it in available }
            .map { (id, at) -> Visit(id, at) }

    // ---------------------------------------------------------------- the merge

    /**
     * Both halves as one list, most recent first.
     *
     * A straight sort on the timestamp, which works only because both halves are recording
     * the same clock: [UsageEvents.Event.timeStamp] and [System.currentTimeMillis] are the
     * same milliseconds. So an Android app opened between two of the shell's own programs
     * lands between them, which is the whole point of merging rather than showing two
     * lists side by side.
     */
    fun recents(availableSystemApps: Set<String>): List<Visit> {
        val dismissed = read(KEY_DISMISSED)
        return (androidVisits() + systemVisits(availableSystemApps))
            // Dismissed *as of then*, not for ever. An app the user has since gone back
            // into is somewhere they have been, and a switcher that refused to admit it
            // because of a tap last Tuesday would be wrong rather than tidy.
            .filter { it.at > (dismissed[it.id] ?: 0L) }
            .sortedByDescending { it.at }
            .take(LIMIT)
    }

    /**
     * Drops one app off the list.
     *
     * It does **not** close anything. Killing another app's task is not something a
     * third-party launcher can do - `ActivityManager.killBackgroundProcesses` needs a
     * permission this app does not hold and would not honestly deserve, and the task
     * snapshot the real switcher's X closed is signature-gated in system_server. So the
     * card is removed from this list and the app carries on exactly as it was. What is
     * written down is the moment of the dismissal, so the entry stays gone until the app
     * is actually used again - see [recents].
     */
    fun dismiss(id: String) {
        write(KEY_DISMISSED, read(KEY_DISMISSED) + (id to System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------- storage

    private fun prefs() =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(key: String): Map<String, Long> = try {
        val json = JSONObject(prefs().getString(key, "{}") ?: "{}")
        buildMap {
            for (id in json.keys()) put(id, json.optLong(id))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable $key, starting over: ${e.message}")
        emptyMap()
    }

    /**
     * Writes one of the two maps back, keeping only the newest [REMEMBER] of it.
     *
     * Both maps are written to on every open and every dismissal and read on every look at
     * the switcher, and neither has anything that would ever take an entry out of it: an
     * uninstalled app's row would sit in there for the life of the install. The pruning is
     * by time rather than by count of what is still installed, because what is still
     * installed is not a question this can answer for a `system.*` identifier.
     */
    private fun write(key: String, values: Map<String, Long>) {
        val json = JSONObject()
        for ((id, at) in values.entries.sortedByDescending { it.value }.take(REMEMBER)) {
            json.put(id, at)
        }
        prefs().edit().putString(key, json.toString()).apply()
    }

    private companion object {
        const val TAG = "WP81Recents"

        /**
         * How far back to read the phone's app history.
         *
         * See [androidVisits] for why there are two windows rather than one.
         */
        const val LOOKBACK_MS = 24L * 60 * 60 * 1000
        const val LOOKBACK_LONG_MS = 7L * 24 * 60 * 60 * 1000

        /** Where the two halves live, both as `{ id: millis }`. */
        const val KEY_SYSTEM_OPENS = "wp81_recents_system_opens"
        const val KEY_DISMISSED = "wp81_recents_dismissed"

        /**
         * How many cards the switcher offers.
         *
         * Windows Phone held about this many and no more, and for a good reason: the
         * switcher is flicked through with a thumb, and a strip long enough to need
         * scrolling *through* rather than *across* is a list, which is what the app list
         * already is.
         */
        const val LIMIT = 10

        /** How many entries either map keeps. Comfortably more than [LIMIT] can show. */
        const val REMEMBER = 40
    }
}
