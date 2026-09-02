package rocks.gorjan.gokixp.apps.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Books the moments the tasks are due.
 *
 * Deliberately *not* [AlarmManager.setAlarmClock], which is what [AlarmScheduler] uses. That
 * API means "this is an alarm clock": it puts the little clock in the status bar, fills in
 * the lock screen's next-alarm line, and is exempt from every form of deferral the system
 * has. A reminder to put the bins out is none of those things, and a phone whose next-alarm
 * line said "22:00" because of one would be lying to its owner about when they are getting
 * up.
 *
 * So tasks are booked exact and allowed through Doze - punctual, because a note that
 * arrives an hour late is not a reminder - but they claim nothing beyond that.
 */
object TaskScheduler {

    /**
     * Puts the phone's tasks in step with the store.
     *
     * Same contract as [AlarmScheduler.sync]: everything booked last time comes down,
     * everything the store now says goes up, and the ids in flight are written down so that
     * a process with no memory of the last one can still find them.
     */
    fun sync(context: Context) {
        val manager = alarms(context) ?: return
        val app = context.applicationContext

        for (id in outstanding(app)) {
            pending(app, id, create = false)?.let {
                manager.cancel(it)
                it.cancel()
            }
        }

        val now = System.currentTimeMillis()
        val scheduled = mutableSetOf<Long>()
        for (task in TaskStore.read(app)) {
            if (!task.enabled) continue
            val at = nextTrigger(task, now)
            val operation = pending(app, task.id, create = true) ?: continue
            try {
                if (canBeExact(manager)) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "The system refused an exact task; falling back", e)
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
            scheduled.add(task.id)
        }
        remember(app, scheduled)

        Log.d(TAG, "Scheduled ${scheduled.size} task(s)")
    }

    fun nextTrigger(task: Task, now: Long): Long =
        Schedule.nextOccurrence(task.hour, task.minute, task.days, skip = 0L, now = now)

    /** How long until [task] is next said, in words, for the line after it is set. */
    fun timeUntil(task: Task, now: Long): String =
        Schedule.timeUntil(nextTrigger(task, now), now)

    private fun canBeExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun alarms(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * The intent that says one task.
     *
     * Its own request-code range, well clear of the alarms' - two pending intents that
     * differ only in ways that are easy to get wrong are two that will one day be the same.
     */
    private fun pending(context: Context, id: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TASK
            data = Uri.parse("gokixp://task/$id")
            putExtra(AlarmReceiver.EXTRA_ID, id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, CODE_BASE + id.toInt(), intent,
            if (create) flags else flags or PendingIntent.FLAG_NO_CREATE
        )
    }

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

    private const val KEY_SCHEDULED = "wp81_tasks_scheduled"
    private const val CODE_BASE = 700_000
    private const val TAG = "WP81Alarms"
}
