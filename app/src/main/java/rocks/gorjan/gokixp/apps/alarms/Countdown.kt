package rocks.gorjan.gokixp.apps.alarms

import android.content.Context

/**
 * The countdown, which is an alarm that was set by duration instead of by time of day.
 *
 * That is not a figure of speech about how it looks - it is how it is built. A running
 * countdown books its moment through [AlarmScheduler] exactly as an alarm does and rings
 * through the same service, so it goes off with the app closed, the screen off and the
 * phone dozing, which is the entire reason anyone sets one. A countdown implemented as a
 * handler ticking inside a view is a countdown that stops when the view goes away.
 *
 * On the wall clock rather than the elapsed-realtime clock the stopwatch uses, because the
 * thing being remembered here is a future *moment* that AlarmManager also has to
 * understand, and AlarmManager books moments in wall-clock time.
 */
object Countdown {

    data class State(
        val running: Boolean,
        /** What the user dialled in, in milliseconds. Kept so reset knows what to go back to. */
        val duration: Long,
        /** When it is due, in wall-clock time. 0 unless running. */
        val endsAt: Long,
        /** What was left when it was paused. Equals [duration] before it has been started. */
        val remaining: Long,
        val sound: String
    ) {
        /** Milliseconds left, never below zero. */
        fun left(now: Long = System.currentTimeMillis()): Long =
            if (running) (endsAt - now).coerceAtLeast(0L) else remaining

        val isSet: Boolean get() = duration > 0L
    }

    fun state(context: Context): State {
        val prefs = prefs(context)
        val duration = prefs.getLong(KEY_DURATION, 0L)
        return State(
            running = prefs.getBoolean(KEY_RUNNING, false),
            duration = duration,
            endsAt = prefs.getLong(KEY_ENDS, 0L),
            remaining = prefs.getLong(KEY_REMAINING, duration),
            sound = prefs.getString(KEY_SOUND, AlarmSounds.DEFAULT) ?: AlarmSounds.DEFAULT
        )
    }

    /** Sets the length, which stops whatever was running: a new duration is a new timer. */
    fun setDuration(context: Context, millis: Long) {
        AlarmScheduler.cancelCountdown(context)
        write(context, state(context).copy(
            running = false, duration = millis, endsAt = 0L, remaining = millis))
    }

    fun setSound(context: Context, id: String) {
        write(context, state(context).copy(sound = id))
    }

    fun start(context: Context) {
        val now = state(context)
        if (now.running || now.remaining <= 0L) return
        val endsAt = System.currentTimeMillis() + now.remaining
        write(context, now.copy(running = true, endsAt = endsAt))
        AlarmScheduler.setCountdown(context, endsAt)
    }

    fun pause(context: Context) {
        val now = state(context)
        if (!now.running) return
        AlarmScheduler.cancelCountdown(context)
        write(context, now.copy(running = false, endsAt = 0L, remaining = now.left()))
    }

    /** Back to the length it was set to, and nothing booked. */
    fun reset(context: Context) {
        val now = state(context)
        AlarmScheduler.cancelCountdown(context)
        write(context, now.copy(running = false, endsAt = 0L, remaining = now.duration))
    }

    /** Called when it has gone off: it is no longer running, and it is back to full. */
    fun markFired(context: Context) = reset(context)

    /** Puts back a countdown that a reboot forgot. Called from [AlarmReceiver]. */
    fun resync(context: Context) {
        val now = state(context)
        if (!now.running) return
        if (now.endsAt <= System.currentTimeMillis()) {
            // It came due while the phone was off. There is nothing to ring about now -
            // an alarm nobody could have heard is not one to raise hours later - so it is
            // simply put back to where it started.
            reset(context)
            return
        }
        AlarmScheduler.setCountdown(context, now.endsAt)
    }

    private fun write(context: Context, state: State) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, state.running)
            .putLong(KEY_DURATION, state.duration)
            .putLong(KEY_ENDS, state.endsAt)
            .putLong(KEY_REMAINING, state.remaining)
            .putString(KEY_SOUND, state.sound)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_RUNNING = "wp81_countdown_running"
    private const val KEY_DURATION = "wp81_countdown_duration"
    private const val KEY_ENDS = "wp81_countdown_ends"
    private const val KEY_REMAINING = "wp81_countdown_remaining"
    private const val KEY_SOUND = "wp81_countdown_sound"
}
