package rocks.gorjan.gokixp.apps.alarms

import android.content.Context
import android.os.SystemClock

/**
 * The stopwatch, and the fact that it keeps running when nobody is watching it.
 *
 * State rather than a ticker: nothing counts here. The running time is the difference
 * between two moments, worked out whenever somebody asks, which means closing the app,
 * locking the phone or letting the launcher be killed for memory costs nothing - the watch
 * was never a timer that had to keep being fed.
 *
 * Measured on [SystemClock.elapsedRealtime], which counts through sleep and cannot be
 * moved by the user, a time server or a change of time zone. The one thing it cannot
 * survive is a reboot, where it goes back to zero - so the wall-clock moment the phone
 * booted is written down alongside, and a watch that finds itself on a different boot is
 * cleared rather than reporting a duration that is off by however long the phone was on
 * last time.
 */
object Stopwatch {

    /** Where the watch is, as one answer, so nothing can read half of a change. */
    data class State(
        val running: Boolean,
        /** Time banked by previous runs, in milliseconds. */
        val banked: Long,
        /** When the current run started, on the elapsed-realtime clock. 0 when stopped. */
        val startedAt: Long,
        /** Total elapsed time at each lap, newest first. */
        val laps: List<Long>
    ) {
        fun elapsed(): Long =
            if (running) banked + (SystemClock.elapsedRealtime() - startedAt) else banked

        val isClear: Boolean get() = !running && banked == 0L && laps.isEmpty()
    }

    fun state(context: Context): State {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_BOOT, 0L) != 0L && rebooted(prefs.getLong(KEY_BOOT, 0L))) {
            clear(context)
            return State(false, 0L, 0L, emptyList())
        }
        return State(
            running = prefs.getBoolean(KEY_RUNNING, false),
            banked = prefs.getLong(KEY_BANKED, 0L),
            startedAt = prefs.getLong(KEY_STARTED, 0L),
            laps = prefs.getString(KEY_LAPS, "").orEmpty()
                .split(',').mapNotNull { it.toLongOrNull() }
        )
    }

    fun start(context: Context) {
        val now = state(context)
        if (now.running) return
        write(context, now.copy(running = true, startedAt = SystemClock.elapsedRealtime()))
    }

    fun pause(context: Context) {
        val now = state(context)
        if (!now.running) return
        write(context, now.copy(running = false, banked = now.elapsed(), startedAt = 0L))
    }

    /**
     * Marks the moment without stopping.
     *
     * Newest first, because the lap somebody just took is the one they are looking for and
     * a list that grows downwards puts it off the bottom of the screen by the tenth.
     */
    fun lap(context: Context) {
        val now = state(context)
        if (!now.running) return
        write(context, now.copy(laps = listOf(now.elapsed()) + now.laps))
    }

    fun reset(context: Context) = clear(context)

    private fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_RUNNING).remove(KEY_BANKED).remove(KEY_STARTED)
            .remove(KEY_LAPS).remove(KEY_BOOT)
            .apply()
    }

    private fun write(context: Context, state: State) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, state.running)
            .putLong(KEY_BANKED, state.banked)
            .putLong(KEY_STARTED, state.startedAt)
            .putString(KEY_LAPS, state.laps.joinToString(","))
            .putLong(KEY_BOOT, bootMoment())
            .apply()
    }

    /** Roughly when this phone last started, in wall-clock time. */
    private fun bootMoment(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Whether the phone has been restarted since the watch was last written.
     *
     * Compared with a few seconds of slack: both clocks drift a little against each other,
     * and the wall clock in particular is corrected by the network, so an exact comparison
     * would decide the phone had rebooted every time it synchronised.
     */
    private fun rebooted(saved: Long): Boolean =
        kotlin.math.abs(bootMoment() - saved) > SLACK_MS

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private const val SLACK_MS = 10_000L

    private const val KEY_RUNNING = "wp81_stopwatch_running"
    private const val KEY_BANKED = "wp81_stopwatch_banked"
    private const val KEY_STARTED = "wp81_stopwatch_started"
    private const val KEY_LAPS = "wp81_stopwatch_laps"
    private const val KEY_BOOT = "wp81_stopwatch_boot"
}
