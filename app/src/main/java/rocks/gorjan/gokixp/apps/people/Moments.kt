package rocks.gorjan.gokixp.apps.people

import android.content.Context

/**
 * When something happened, said the way somebody reading a list of them would.
 *
 * The time alone for today, which is most of a call log or a conversation and the case
 * where the date is noise; the day's name for yesterday; the date for anything older. In
 * the phone's own 12- or 24-hour setting, because that is a fact about the reader.
 *
 * Shared between the call history and the messages rather than written twice: the two
 * pages sit next to each other in one panorama, and a call and a message that arrived in
 * the same minute have to say the same minute in the same words.
 */
fun momentOf(context: Context, at: Long): String {
    if (at <= 0L) return ""
    val locale = java.util.Locale.getDefault()
    val moment = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val time = timeOf(context, at)
    return when (daysAgo(moment)) {
        0 -> time
        1 -> "yesterday, $time"
        else -> java.text.SimpleDateFormat("d MMM, ", locale).format(moment.time) + time
    }
}

/**
 * The clock alone, with nothing about which day it was.
 *
 * For a list that says the day in a heading over the rows instead - see [dayHeadingOf] -
 * where a date on every row is the same thing said twice, once in a place nobody is
 * reading. In the phone's own 12- or 24-hour setting, like every other time here.
 */
fun timeOf(context: Context, at: Long): String {
    if (at <= 0L) return ""
    return java.text.SimpleDateFormat(
        if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
        java.util.Locale.getDefault()
    ).format(java.util.Date(at))
}

/**
 * Which day [at] fell on, as something two moments can be compared by.
 *
 * The midnight that started it, in the reader's own zone - so two calls either side of
 * lunch match and two either side of midnight do not, which is the whole question a list
 * grouped by day is asking. Zero for a moment there is no date for.
 */
fun dayOf(at: Long): Long {
    if (at <= 0L) return 0L
    return java.util.Calendar.getInstance().apply {
        timeInMillis = at
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * What to write over a day's worth of rows.
 *
 * The two days that have names of their own have them, and everything older is a date -
 * which is what a reader scrolling back is looking for by then, the names having run out
 * after yesterday. The year only once it is not this one, because writing it on every
 * heading is a word of noise for the eleven months where it cannot be anything else.
 */
fun dayHeadingOf(at: Long): String {
    if (at <= 0L) return ""
    val locale = java.util.Locale.getDefault()
    val moment = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return when (daysAgo(moment)) {
        0 -> "today"
        1 -> "yesterday"
        else -> {
            val thisYear = moment.get(java.util.Calendar.YEAR) ==
                java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            java.text.SimpleDateFormat(if (thisYear) "d MMMM" else "d MMMM yyyy", locale)
                .format(moment.time)
                .lowercase(locale)
        }
    }
}

/** Whole days between [moment] and now, counted from midnight rather than by hours. */
private fun daysAgo(moment: java.util.Calendar): Int {
    val midnight = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val elapsed = midnight.timeInMillis - moment.timeInMillis
    if (elapsed <= 0L) return 0
    return (elapsed / DAY_MS + 1).toInt()
}

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * The same moment, in a column that has room for two words.
 *
 * For a list where the time is a fact in the margin rather than the point of the row -
 * the conversations, where what matters is who and what they said and the time is how you
 * tell this week from last. The clock for today, the day's name for the last week, the
 * date beyond that.
 */
fun briefMomentOf(context: Context, at: Long): String {
    if (at <= 0L) return ""
    val locale = java.util.Locale.getDefault()
    val moment = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return when (daysAgo(moment)) {
        0 -> java.text.SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            locale
        ).format(moment.time)
        1 -> "yesterday"
        in 2..6 -> java.text.SimpleDateFormat("EEE", locale).format(moment.time).lowercase()
        else -> java.text.SimpleDateFormat("d MMM", locale).format(moment.time)
    }
}
