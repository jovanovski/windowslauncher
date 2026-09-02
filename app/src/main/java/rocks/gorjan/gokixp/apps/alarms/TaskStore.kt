package rocks.gorjan.gokixp.apps.alarms

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * A line to be said at a time.
 *
 * The other half of what a clock is for. An alarm wakes you and is therefore about noise,
 * a screen and a snooze; a task says one thing quietly and is over - "bins out", "take the
 * tablets", "stand up". Same schedule underneath ([Schedule]), and deliberately nothing
 * else in common: a task does not ring, does not take over the lock screen and does not
 * hold a wake lock, because none of that is what a note to yourself is for.
 */
data class Task(
    val id: Long,
    /** What the notification says. The whole of the task, as far as the user is concerned. */
    val text: String,
    /** On the 24-hour clock, whatever the phone is set to *show*. */
    val hour: Int,
    val minute: Int,
    /** [java.util.Calendar]'s day constants. Empty means once, and then it switches off. */
    val days: Set<Int>,
    val enabled: Boolean
) {
    val onlyOnce: Boolean get() = days.isEmpty()

    fun repeatText(): String = Schedule.repeatText(days)
}

/**
 * Where the tasks are kept.
 *
 * The same shape as [AlarmStore] and for the same reasons: a JSON array in the shell's own
 * preferences, read whole, and every write reschedules so that a saved task and a booked
 * one cannot come apart.
 */
object TaskStore {

    fun all(context: Context): List<Task> = read(context).sortedWith(
        compareBy({ it.hour }, { it.minute }, { it.id })
    )

    fun byId(context: Context, id: Long): Task? = read(context).firstOrNull { it.id == id }

    fun save(context: Context, task: Task): Task {
        val tasks = read(context).toMutableList()
        val saved = if (task.id == 0L) task.copy(id = nextId(tasks)) else task
        val at = tasks.indexOfFirst { it.id == saved.id }
        if (at >= 0) tasks[at] = saved else tasks.add(saved)
        write(context, tasks)
        TaskScheduler.sync(context)
        return saved
    }

    fun delete(context: Context, id: Long) {
        write(context, read(context).filterNot { it.id == id })
        TaskScheduler.sync(context)
    }

    fun setEnabled(context: Context, id: Long, enabled: Boolean) =
        update(context, id) { it.copy(enabled = enabled) }

    fun update(context: Context, id: Long, change: (Task) -> Task) {
        val tasks = read(context).toMutableList()
        val at = tasks.indexOfFirst { it.id == id }
        if (at < 0) return
        tasks[at] = change(tasks[at])
        write(context, tasks)
        TaskScheduler.sync(context)
    }

    /**
     * What a task becomes once it has been said.
     *
     * A repeating one waits for its next day. A one-shot switches itself off rather than
     * disappearing, which is the same courtesy the alarms get: "again tomorrow" is then a
     * switch rather than a retype.
     */
    fun markFired(context: Context, id: Long) = update(context, id) {
        if (it.onlyOnce) it.copy(enabled = false) else it
    }

    private fun nextId(tasks: List<Task>): Long = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L

    // ---------------------------------------------------------------- storage

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): List<Task> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> fromJson(array.optJSONObject(i)) }
        } catch (e: Exception) {
            Log.w(TAG, "The saved tasks could not be read", e)
            emptyList()
        }
    }

    private fun write(context: Context, tasks: List<Task>) {
        val array = JSONArray()
        for (task in tasks) array.put(toJson(task))
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(task: Task): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("text", task.text)
        put("hour", task.hour)
        put("minute", task.minute)
        put("days", JSONArray().also { days -> task.days.sorted().forEach { days.put(it) } })
        put("enabled", task.enabled)
    }

    private fun fromJson(json: JSONObject?): Task? {
        if (json == null) return null
        val id = json.optLong("id", 0L)
        if (id == 0L) return null
        val days = json.optJSONArray("days")
        return Task(
            id = id,
            text = json.optString("text", ""),
            hour = json.optInt("hour", 9).coerceIn(0, 23),
            minute = json.optInt("minute", 0).coerceIn(0, 59),
            days = buildSet {
                if (days != null) for (i in 0 until days.length()) add(days.optInt(i))
            },
            enabled = json.optBoolean("enabled", true)
        )
    }

    private const val KEY = "wp81_tasks"
    private const val TAG = "WP81Alarms"
}
