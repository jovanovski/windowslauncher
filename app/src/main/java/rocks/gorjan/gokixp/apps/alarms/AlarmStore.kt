package rocks.gorjan.gokixp.apps.alarms

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One alarm, as the user set it.
 *
 * [days] holds [java.util.Calendar]'s own day constants rather than an index of this
 * app's invention, because everything that has to answer "when is this next?" is going to
 * ask a Calendar anyway - see [AlarmScheduler.nextTrigger] - and a second numbering would
 * only be somewhere for an off-by-one to live. Empty means the alarm goes off once and
 * then turns itself off, which is what Windows Phone called "only once".
 */
data class Alarm(
    val id: Long,
    /** On the 24-hour clock, whatever the phone is set to *show*. */
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,
    /** Which of [AlarmSounds] rings. */
    val sound: String,
    /** What the user called it. Blank is allowed; the list then says "alarm". */
    val name: String,
    val enabled: Boolean,
    val vibrate: Boolean,
    /**
     * When a snoozed alarm is due back, or 0.
     *
     * Kept on the alarm rather than in a list of its own so that it cannot outlive the
     * thing it belongs to: deleting an alarm that happens to be snoozed takes the snooze
     * with it, and there is no second place to remember to look.
     */
    val snoozedUntil: Long = 0L,
    /**
     * One occurrence this alarm is to sit out, as the moment it would have gone off.
     *
     * What the Dismiss on the upcoming-alarm notification writes. Held as the moment rather
     * than as a flag because "skip the next one" is a question that has to still have the
     * same answer in eight hours' time: a flag would be read as skipping whatever occurrence
     * happened to be next when it was next read, which after the alarm has been dismissed
     * and the morning has passed is tomorrow's.
     */
    val dismissedFor: Long = 0L,
    /**
     * The occurrence the upcoming-alarm notification has already been shown for.
     *
     * So that it is shown once per morning rather than once per anything that happens to
     * rebuild the schedule, and so that a notification the user swiped away stays away.
     */
    val warnedFor: Long = 0L
) {
    /** Whether this one goes off once and is then done. */
    val onlyOnce: Boolean get() = days.isEmpty()

    /** What the list writes under the time. */
    fun repeatText(): String = Schedule.repeatText(days)
}

/**
 * Where the alarms are kept.
 *
 * A JSON array in the shell's own preferences, which is where everything else this shell
 * remembers already lives. There is no case here for a database: a phone has a handful of
 * alarms, they are read whole or not at all, and the thing that reads them most often is a
 * broadcast receiver woken at six in the morning with one question to ask.
 *
 * Every write goes through [AlarmScheduler.sync], not because the store cares but because
 * an alarm that is saved and not scheduled is a promise the phone has quietly dropped -
 * so the two are never separate calls anywhere in the app.
 */
object AlarmStore {

    fun all(context: Context): List<Alarm> = read(context).sortedWith(
        compareBy({ it.hour }, { it.minute }, { it.id })
    )

    fun byId(context: Context, id: Long): Alarm? = read(context).firstOrNull { it.id == id }

    /**
     * Saves an alarm, and hands back what was saved.
     *
     * An [Alarm] with id 0 is a new one and gets an id here; anything else replaces the
     * entry with its id. The scheduler is told either way.
     */
    fun save(context: Context, alarm: Alarm): Alarm {
        val alarms = read(context).toMutableList()
        val saved = if (alarm.id == 0L) alarm.copy(id = nextId(alarms)) else alarm
        val at = alarms.indexOfFirst { it.id == saved.id }
        if (at >= 0) alarms[at] = saved else alarms.add(saved)
        write(context, alarms)
        AlarmScheduler.sync(context)
        return saved
    }

    fun delete(context: Context, id: Long) {
        val alarms = read(context).filterNot { it.id == id }
        write(context, alarms)
        AlarmScheduler.sync(context)
    }

    fun setEnabled(context: Context, id: Long, enabled: Boolean) {
        update(context, id) {
            // Turning one off clears any snooze with it: the alarm the snooze belonged to
            // is not going to ring, and a snooze that outlived it would ring on its own.
            it.copy(enabled = enabled, snoozedUntil = if (enabled) it.snoozedUntil else 0L)
        }
    }

    /**
     * Sits out the next occurrence, and only that one.
     *
     * What the Dismiss on the upcoming-alarm notification does, and it means what it says
     * on an alarm clock: not this morning, same time as usual after that. A repeating alarm
     * therefore records which occurrence it is skipping and stays on; a one-shot has no
     * "after that" to come back for, so dismissing it is the whole of its life and it goes
     * off - the same end it would have reached by ringing.
     */
    fun dismissNext(context: Context, id: Long) {
        val alarm = byId(context, id) ?: return
        if (alarm.onlyOnce) {
            update(context, id) {
                it.copy(enabled = false, snoozedUntil = 0L, dismissedFor = 0L, warnedFor = 0L)
            }
        } else {
            val skipping = AlarmScheduler.nextOccurrence(alarm, System.currentTimeMillis())
            update(context, id) {
                it.copy(dismissedFor = skipping, snoozedUntil = 0L, warnedFor = 0L)
            }
        }
    }

    /** Notes that the upcoming-alarm notification has been shown for [occurrence]. */
    fun markWarned(context: Context, id: Long, occurrence: Long) =
        update(context, id) { it.copy(warnedFor = occurrence) }

    /** Reads, changes one alarm, writes back, reschedules. */
    fun update(context: Context, id: Long, change: (Alarm) -> Alarm) {
        val alarms = read(context).toMutableList()
        val at = alarms.indexOfFirst { it.id == id }
        if (at < 0) return
        alarms[at] = change(alarms[at])
        write(context, alarms)
        AlarmScheduler.sync(context)
    }

    /**
     * What an alarm becomes once it has gone off.
     *
     * A repeating one is simply un-snoozed and waits for its next day. A one-shot has done
     * the only thing it was for, and turns itself off rather than disappearing - the phone
     * did it that way, and it means "same time tomorrow" is a switch rather than a retype.
     */
    fun markFired(context: Context, id: Long) = update(context, id) {
        // The skip and the warning both belonged to the occurrence that has just happened.
        val spent = it.copy(snoozedUntil = 0L, dismissedFor = 0L, warnedFor = 0L)
        if (it.onlyOnce) spent.copy(enabled = false) else spent
    }

    fun snooze(context: Context, id: Long, until: Long) =
        update(context, id) { it.copy(snoozedUntil = until, enabled = true) }

    private fun nextId(alarms: List<Alarm>): Long = (alarms.maxOfOrNull { it.id } ?: 0L) + 1L

    // ---------------------------------------------------------------- storage

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> fromJson(array.optJSONObject(i)) }
        } catch (e: Exception) {
            Log.w(TAG, "The saved alarms could not be read", e)
            emptyList()
        }
    }

    private fun write(context: Context, alarms: List<Alarm>) {
        val array = JSONArray()
        for (alarm in alarms) array.put(toJson(alarm))
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(alarm: Alarm): JSONObject = JSONObject().apply {
        put("id", alarm.id)
        put("hour", alarm.hour)
        put("minute", alarm.minute)
        put("days", JSONArray().also { days -> alarm.days.sorted().forEach { days.put(it) } })
        put("sound", alarm.sound)
        put("name", alarm.name)
        put("enabled", alarm.enabled)
        put("vibrate", alarm.vibrate)
        put("snoozedUntil", alarm.snoozedUntil)
        put("dismissedFor", alarm.dismissedFor)
        put("warnedFor", alarm.warnedFor)
    }

    private fun fromJson(json: JSONObject?): Alarm? {
        if (json == null) return null
        val id = json.optLong("id", 0L)
        if (id == 0L) return null
        val days = json.optJSONArray("days")
        return Alarm(
            id = id,
            hour = json.optInt("hour", 7).coerceIn(0, 23),
            minute = json.optInt("minute", 0).coerceIn(0, 59),
            days = buildSet {
                if (days != null) for (i in 0 until days.length()) add(days.optInt(i))
            },
            sound = json.optString("sound", AlarmSounds.DEFAULT),
            name = json.optString("name", ""),
            enabled = json.optBoolean("enabled", true),
            vibrate = json.optBoolean("vibrate", true),
            snoozedUntil = json.optLong("snoozedUntil", 0L),
            dismissedFor = json.optLong("dismissedFor", 0L),
            warnedFor = json.optLong("warnedFor", 0L)
        )
    }

    private const val KEY = "wp81_alarms"
    private const val TAG = "WP81Alarms"
}
