package rocks.gorjan.gokixp.apps.alarms

import java.util.Calendar

/**
 * A time of day, and the days of the week it comes round on.
 *
 * The one idea an alarm and a task have entirely in common. Both are "seven o'clock, on
 * these days, and here is one occurrence to sit out" - what differs is only what happens
 * when the moment arrives, which is a ringing phone in one case and a line in the shade in
 * the other. Kept in one place so the two cannot disagree about when Tuesday is.
 */
object Schedule {

    /**
     * Monday first.
     *
     * The order the days are offered in and read back in, which is not the order [Calendar]'s
     * own constants happen to run in - those start at Sunday because the class was written
     * for an American calendar. A week that a working week starts is a week that starts on
     * Monday, and the weekend is the two days at the end of it, where the word says they are.
     */
    val ORDER = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )

    /** One letter each, for the row of day keys on an editor. */
    val INITIAL = mapOf(
        Calendar.MONDAY to "m", Calendar.TUESDAY to "t", Calendar.WEDNESDAY to "w",
        Calendar.THURSDAY to "t", Calendar.FRIDAY to "f", Calendar.SATURDAY to "s",
        Calendar.SUNDAY to "s"
    )

    val SHORT = mapOf(
        Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
        Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat",
        Calendar.SUNDAY to "sun"
    )

    val EVERY_DAY = ORDER.toSet()
    val WEEKDAYS = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY
    )
    val WEEKENDS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)

    /**
     * What a list writes under the time.
     *
     * The three sets that have their own names get them, because "mon, tue, wed, thu, fri"
     * is a list the reader has to add up and "weekdays" is not.
     */
    fun repeatText(days: Set<Int>): String = when {
        days.isEmpty() -> "only once"
        days == EVERY_DAY -> "every day"
        days == WEEKDAYS -> "weekdays"
        days == WEEKENDS -> "weekends"
        else -> ORDER.filter { it in days }.joinToString(", ") { SHORT[it].orEmpty() }
    }

    /**
     * The next time the clock reads [hour]:[minute] on a day this repeats, that is not the
     * one occurrence being sat out.
     *
     * Built by walking forward a day at a time rather than by arithmetic on milliseconds,
     * which is the only way to be right across a daylight-saving change: the day the clocks
     * go forward is twenty-three hours long, and "tomorrow at seven" is not "in twenty-four
     * hours". A Calendar knows that and a Long does not.
     *
     * [skip] is a moment rather than a count, so that "not this one" still means the same
     * occurrence when the question is asked again eight hours later. Empty [days] means it
     * happens once, at the next time that clock time comes round.
     */
    fun nextOccurrence(
        hour: Int,
        minute: Int,
        days: Set<Int>,
        skip: Long,
        now: Long
    ): Long {
        val at = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Two weeks and a day of tries. A week covers the repeat days and the extra day
        // covers today's time having already gone by - and the second week is for the
        // skipped occurrence, since something that repeats on one day of the week and has
        // had that day skipped next comes round seven days after it.
        repeat(15) {
            val due = at.timeInMillis > now
            val rightDay = days.isEmpty() || at.get(Calendar.DAY_OF_WEEK) in days
            val skipped = skip != 0L && at.timeInMillis == skip
            if (due && rightDay && !skipped) return at.timeInMillis
            at.add(Calendar.DAY_OF_YEAR, 1)
        }
        return at.timeInMillis
    }

    /** How long until [at], in words, for the line a list shows after something is set. */
    fun timeUntil(at: Long, now: Long): String {
        val minutes = ((at - now) / 60_000L).coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val rest = minutes % 60
        return buildList {
            if (days > 0) add("$days day${plural(days)}")
            if (hours > 0) add("$hours hour${plural(hours)}")
            // Minutes are dropped once it is days away: "in 2 days and 14 minutes" is a
            // precision nobody asked for from something they just set for Thursday.
            if (rest > 0 && days == 0L) add("$rest minute${plural(rest)}")
        }.ifEmpty { listOf("less than a minute") }.joinToString(" and ")
    }

    private fun plural(count: Long) = if (count == 1L) "" else "s"
}
