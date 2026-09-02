package rocks.gorjan.gokixp.apps.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * What turns a saved alarm into a phone that actually goes off.
 *
 * Everything here goes through [AlarmManager.setAlarmClock], which is not one exact-alarm
 * API among several - it is the one Android means by "an alarm clock". It is exempt from
 * Doze and from app standby, it is the only kind the system will not batch or slide, and
 * it is what puts the little clock in the status bar and fills in "next alarm" on the lock
 * screen. An alarm app that used `setExact` would be an app whose alarms the system is
 * entitled to defer while the phone is asleep, which is the one condition every alarm is
 * set for.
 *
 * One pending intent per alarm, keyed by its id. A snooze is not a second alarm - it is
 * the same alarm due sooner - so [nextTrigger] simply answers with whichever of the two
 * comes first and there is only ever one thing scheduled per row in the list.
 *
 * [sync] is the only way anything is scheduled: it takes down everything it put up last
 * time and puts up what the store says now. That makes the store the single account of
 * what the phone is going to do, and makes a stale alarm - one deleted while its intent
 * was still pending - impossible rather than merely unlikely. The ids it has outstanding
 * are written down for the same reason: after a reboot or an update, this process has no
 * memory of what the last one scheduled.
 */
object AlarmScheduler {

    // ---------------------------------------------------------------- scheduling

    /**
     * Puts the phone's alarms in step with the store.
     *
     * Cheap enough to call after every change, and it is: a handful of ids, one cancel and
     * one set each. Called from the store's own writes so nothing has to remember to.
     */
    fun sync(context: Context) {
        val manager = alarms(context) ?: return
        val app = context.applicationContext

        val alarms = AlarmStore.read(app)
        val live = alarms.map { it.id }.toSet()

        for (id in outstanding(app)) {
            pending(app, id, create = false)?.let {
                manager.cancel(it)
                it.cancel()
            }
            warning(app, id, create = false)?.let {
                manager.cancel(it)
                it.cancel()
            }
            // An alarm that has been deleted takes its notice with it. Alarms that are
            // still here have theirs decided below, one at a time.
            if (id !in live) AlarmWarning.clear(app, id)
        }

        val now = System.currentTimeMillis()
        val scheduled = mutableSetOf<Long>()
        for (alarm in alarms) {
            val at = nextTrigger(alarm, now)
            if (at == null) {
                // Switched off. Nothing is coming, so nothing should be saying so.
                AlarmWarning.clear(app, alarm.id)
                continue
            }
            val operation = pending(app, alarm.id, create = true) ?: continue
            set(manager, app, at, operation)
            scheduled.add(alarm.id)
            bookWarning(manager, app, alarm, now)
        }
        remember(app, scheduled)

        Log.d(TAG, "Scheduled ${scheduled.size} alarm(s)")
    }

    /**
     * Snoozes an alarm that is going off.
     *
     * Written to the alarm rather than scheduled directly, so that it survives this
     * process being killed between now and nine minutes' time - which, on a phone that has
     * just been woken at six in the morning to ring, is not a remote possibility. The
     * store's write reschedules.
     */
    fun snooze(context: Context, id: Long, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        AlarmStore.snooze(context.applicationContext, id, until)
    }

    /**
     * Books the notice, or leaves standing the one already given.
     *
     * The alarm remembers which occurrence it has been warned about, and that is what makes
     * this idempotent: the schedule is rebuilt on every save, every switch and every start
     * of the launcher, and without it each rebuild would put back a notice the user had
     * already swiped away.
     *
     * An alarm set for less than [WARN_AHEAD_MS] away gets its notice at once rather than
     * not at all - the window it is about is already open - which is booked a second out so
     * that it arrives the same way every other one does, through the receiver.
     */
    private fun bookWarning(
        manager: AlarmManager,
        context: Context,
        alarm: Alarm,
        now: Long
    ) {
        val occurrence = nextOccurrence(alarm, now)
        if (alarm.warnedFor == occurrence) return

        // Anything still up is about a different morning.
        AlarmWarning.clear(context, alarm.id)

        val at = (occurrence - WARN_AHEAD_MS).coerceAtLeast(now + 1_000L)
        if (at >= occurrence) return

        val operation = warning(context, alarm.id, create = true) ?: return
        try {
            if (canBeExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "The system refused the upcoming-alarm notice", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    /** The countdown's own alarm, on the same footing as the others. */
    fun setCountdown(context: Context, at: Long) {
        val manager = alarms(context) ?: return
        val app = context.applicationContext
        val operation = countdownPending(app, create = true) ?: return
        set(manager, app, at, operation)
    }

    fun cancelCountdown(context: Context) {
        val app = context.applicationContext
        val operation = countdownPending(app, create = false) ?: return
        alarms(app)?.cancel(operation)
        operation.cancel()
    }

    /**
     * Books the moment, by the strongest means the phone will allow.
     *
     * [AlarmManager.setAlarmClock] needs an exact-alarm permission on Android 12 and
     * later. This app declares USE_EXACT_ALARM, which is granted at install to an app
     * whose job includes being an alarm clock, so in practice the first branch is the one
     * that runs. The fallback is not decoration though: the permission can be absent on an
     * Android 12 device, where only the revocable SCHEDULE_EXACT_ALARM exists, and an
     * alarm that is a few minutes late is enormously better than one that throws.
     */
    private fun set(manager: AlarmManager, context: Context, at: Long, operation: PendingIntent) {
        try {
            if (canBeExact(manager)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(context)), operation)
            } else {
                // Still allowed while the phone is dozing, which is what matters most.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "The system refused an exact alarm; falling back", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun canBeExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    /** Whether this phone will let the alarms be exact, for the app to say so if not. */
    fun exactAlarmsAllowed(context: Context): Boolean =
        alarms(context)?.let { canBeExact(it) } ?: false

    // ---------------------------------------------------------------- when

    /**
     * When this alarm is next due, or null if it is not due at all.
     *
     * Whichever comes first of the snooze it is under and its own next occurrence. A
     * repeating alarm snoozed at ten past six is due at nineteen minutes past, not
     * tomorrow morning; a one-shot that has been snoozed is due at the snooze and then
     * never again.
     */
    fun nextTrigger(alarm: Alarm, now: Long): Long? {
        if (!alarm.enabled) return null
        val snooze = alarm.snoozedUntil.takeIf { it > now }
        val occurrence = nextOccurrence(alarm, now)
        return listOfNotNull(snooze, occurrence).minOrNull()
    }

    /** When this alarm next comes round, the morning it is sitting out excepted. */
    fun nextOccurrence(alarm: Alarm, now: Long): Long =
        Schedule.nextOccurrence(alarm.hour, alarm.minute, alarm.days, alarm.dismissedFor, now)

    /** How long until [alarm] next goes off, in words, for the toast after it is set. */
    fun timeUntil(alarm: Alarm, now: Long): String {
        val at = nextTrigger(alarm, now) ?: return ""
        return Schedule.timeUntil(at, now)
    }

    // ---------------------------------------------------------------- plumbing

    private fun alarms(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * The intent that fires one alarm.
     *
     * The id goes in the data as well as in an extra, because a pending intent is matched
     * on everything *but* its extras - two alarms whose intents differed only by an extra
     * would be the same pending intent, and setting the second would silently rewrite the
     * first.
     */
    private fun pending(context: Context, id: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("gokixp://alarm/$id")
            putExtra(AlarmReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags(create))
    }

    /**
     * The intent that puts up one alarm's half-hour notice.
     *
     * Its own request code rather than relying on the action and the data being different
     * from the ring's: two pending intents that differ only in ways that are easy to get
     * wrong are two pending intents that will one day be the same one.
     */
    private fun warning(context: Context, id: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_WARN
            data = Uri.parse("gokixp://alarm/warn/$id")
            putExtra(AlarmReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, WARN_CODE_BASE + id.toInt(), intent, flags(create))
    }

    private fun countdownPending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_COUNTDOWN
            data = Uri.parse("gokixp://countdown")
        }
        return PendingIntent.getBroadcast(context, COUNTDOWN_CODE, intent, flags(create))
    }

    private fun flags(create: Boolean): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (create) base else base or PendingIntent.FLAG_NO_CREATE
    }

    /**
     * Where the status bar's alarm icon goes when it is tapped.
     *
     * The Alarms app, opened in the launcher - which is what the user means by tapping the
     * thing that says an alarm is set.
     */
    private fun showIntent(context: Context): PendingIntent {
        val open = Intent(context, rocks.gorjan.gokixp.MainActivity::class.java).apply {
            action = ACTION_SHOW_ALARMS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context, SHOW_CODE, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------------------------------------------------------- what is outstanding

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private fun outstanding(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY_SCHEDULED, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    private fun remember(context: Context, ids: Set<Long>) {
        prefs(context).edit()
            .putStringSet(KEY_SCHEDULED, ids.map { it.toString() }.toSet())
            .apply()
    }

    /** What MainActivity watches for, to open Alarms when the status-bar clock is tapped. */
    const val ACTION_SHOW_ALARMS = "rocks.gorjan.gokixp.SHOW_ALARMS"

    /** How long before an alarm the notice is given. */
    const val WARN_AHEAD_MS = 2 * 60 * 60 * 1000L

    private const val KEY_SCHEDULED = "wp81_alarms_scheduled"

    /** Request codes outside the id space, which starts at 1 and counts up. */
    private const val COUNTDOWN_CODE = -101
    private const val SHOW_CODE = -102
    private const val WARN_CODE_BASE = 500_000

    private const val TAG = "WP81Alarms"
}
