package rocks.gorjan.gokixp.apps.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * What AlarmManager wakes when an alarm comes due.
 *
 * Not exported, and it does not need to be: everything that reaches it is a pending intent
 * this app built and handed to the system, which is delivered to the component by name.
 * The boot broadcast, which does come from outside, has a receiver of its own for exactly
 * that reason - see [AlarmBootReceiver]. One receiver serving both would have to be
 * exported, and an exported receiver that starts an alarm ringing is a doorbell any app on
 * the phone can press.
 *
 * It does almost nothing itself. A broadcast receiver has about ten seconds of life and
 * runs on the main thread, so the work here is to write down that the alarm has gone off -
 * which reschedules the next one - and hand the ringing to a service that can outlive it.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> fire(context, intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_WARN -> warn(context, intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_DISMISS_NEXT -> dismissNext(context, intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_TASK -> task(context, intent.getLongExtra(EXTRA_ID, 0L))
            ACTION_COUNTDOWN -> countdown(context)
            else -> Log.w(TAG, "Ignoring ${intent.action}")
        }
    }

    private fun fire(context: Context, id: Long) {
        val alarm = AlarmStore.byId(context, id)
        if (alarm == null) {
            Log.w(TAG, "Alarm $id went off but is no longer there")
            return
        }
        if (!alarm.enabled) {
            Log.w(TAG, "Alarm $id went off while switched off")
            return
        }
        // The notice was about this moment, and this moment has arrived: from here on the
        // alarm is either ringing or it is not, and a notification saying it is coming is
        // out of date either way.
        AlarmWarning.clear(context, id)

        // Written down before it rings, not after. This is what turns a one-shot off and
        // books a repeating one for its next day, and doing it first means an alarm that
        // is dismissed - or a process that dies while ringing - has already had its
        // consequences recorded.
        AlarmStore.markFired(context, id)
        AlarmRingService.ring(context, AlarmRingService.Ringing.of(alarm))
    }

    /**
     * Two hours to go: says so, once.
     *
     * Which occurrence it is about is worked out here rather than carried in the intent,
     * because the alarm may have been edited in the meantime and the notice should be about
     * the alarm as it now stands. Writing that occurrence down is what stops the notice
     * being put back up by the next thing that rebuilds the schedule.
     */
    private fun warn(context: Context, id: Long) {
        val alarm = AlarmStore.byId(context, id) ?: return
        if (!alarm.enabled) return
        val now = System.currentTimeMillis()
        val occurrence = AlarmScheduler.nextOccurrence(alarm, now)
        if (alarm.warnedFor == occurrence) return
        // The occurrence this was booked for may not be the next one any more - it can have
        // been dismissed, or the alarm edited, in the two hours since. Warning about the one
        // after it would be a notice given a week early.
        val ahead = occurrence - now
        if (ahead < 0 || ahead > AlarmScheduler.WARN_AHEAD_MS + SLACK_MS) {
            Log.d(TAG, "Alarm $id is no longer within the notice window; none given")
            return
        }
        AlarmWarning.post(context, alarm, occurrence)
        AlarmStore.markWarned(context, id, occurrence)
    }

    /**
     * The Dismiss on that notice: not this morning, same time as usual after that.
     *
     * The notification goes first so the tap feels immediate; the store's write follows and
     * reschedules, which is what actually moves the alarm on.
     */
    private fun dismissNext(context: Context, id: Long) {
        AlarmWarning.clear(context, id)
        AlarmStore.dismissNext(context, id)
    }

    /**
     * A task has come due: it is said, and then it is written down as said.
     *
     * The write reschedules, which for a repeating task books its next day and for a
     * one-shot switches it off - the same shape as an alarm firing, minus everything that
     * makes an alarm loud.
     */
    private fun task(context: Context, id: Long) {
        val task = TaskStore.byId(context, id)
        if (task == null) {
            Log.w(TAG, "Task $id came due but is no longer there")
            return
        }
        if (!task.enabled) return
        TaskNotifier.post(context, task)
        TaskStore.markFired(context, id)
    }

    private fun countdown(context: Context) {
        val sound = Countdown.state(context).sound
        Countdown.markFired(context)
        AlarmRingService.ring(context, AlarmRingService.Ringing.countdown(sound))
    }

    companion object {
        const val ACTION_FIRE = "rocks.gorjan.gokixp.ALARM_FIRE"
        const val ACTION_WARN = "rocks.gorjan.gokixp.ALARM_WARN"
        const val ACTION_DISMISS_NEXT = "rocks.gorjan.gokixp.ALARM_DISMISS_NEXT"
        const val ACTION_TASK = "rocks.gorjan.gokixp.TASK_DUE"
        const val ACTION_COUNTDOWN = "rocks.gorjan.gokixp.COUNTDOWN_FIRE"
        const val EXTRA_ID = "alarm_id"

        /** Room for the notice itself to have been delivered a little late. */
        private const val SLACK_MS = 60_000L

        private const val TAG = "WP81Alarms"
    }
}

/**
 * The one that has to be exported: everything the system tells an alarm clock about.
 *
 * A reboot wipes every pending alarm the phone was holding, and an app update takes the
 * process's own with it. Moving the clock or the time zone does not wipe them but does
 * change what they mean - an alarm booked for seven o'clock is booked as a moment, and
 * flying to another country moves seven o'clock without moving the moment. In all four
 * cases the answer is the same: throw away what is scheduled and work it out again from
 * what the user actually asked for.
 */
class AlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Rebuilding the alarms after ${intent.action}")
        AlarmScheduler.sync(context)
        TaskScheduler.sync(context)
        Countdown.resync(context)
    }

    private companion object {
        const val TAG = "WP81Alarms"
    }
}
