package rocks.gorjan.gokixp.apps.weather

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.WeatherHour
import rocks.gorjan.gokixp.wp81.WeatherStore
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * Says that it is about to rain, once, and then leaves the user alone.
 *
 * There is one hard part here and it is not finding the rain. The forecast is re-read
 * every time the Start screen's weather tile ticks over, which is often, and a notifier
 * that simply posted whenever it saw rain in the next two hours would post the same thing
 * every few seconds for two hours. Every app that does this well is mostly a set of rules
 * about when *not* to speak, and those are what most of this file is:
 *
 *  - **Once per shower.** What is remembered is the hour the rain starts, which is the
 *    thing being announced. The same hour is never announced twice, however many times it
 *    is seen, and it survives the launcher being restarted because it is written down.
 *  - **A quiet spell afterwards.** Even a genuinely different shower is held back if one
 *    was announced within the last few hours. A day of showers is a day the user can see
 *    out of the window; it is not five notifications.
 *  - **Only the start of it.** If it is already raining, there is nothing to warn about -
 *    they are in it. This fires on the edge, not on the state.
 *  - **Not in the night.** Nothing is posted between the small hours and morning. Rain at
 *    four is not worth waking anybody for, and it will still be there at seven.
 *  - **Worth saying at all.** A fifty per cent chance is the floor. Below that the
 *    notification is wrong more often than it is right, and a forecast that cries wolf is
 *    one people turn off - which costs them the times it was right.
 *
 * And it is not free to run: the forecast is a few thousand numbers in a preferences file.
 * So it is looked at on a timer of its own rather than on every tick, and immediately
 * whenever the forecast itself has been replaced.
 */
object RainNotifier {

    /**
     * Considers whether to say anything. Cheap to call as often as the tile refreshes.
     *
     * Called from the live-tile refresh, which is what the phone has instead of a job for
     * this: the weather is already being re-read there, and a notifier with its own alarm
     * would be a second thing waking the radio to learn what the first one already knows.
     */
    fun consider(context: Context) {
        if (!WeatherStore.rainAlerts(context)) return

        // The forecast has not changed and it has not been long enough for the window to
        // have moved anywhere interesting. Both conditions, because a fetch landing is the
        // one moment the answer can change without any time passing.
        val forecastAt = WeatherStore.fetchedAt(context)
        val now = System.currentTimeMillis()
        if (forecastAt == lastForecast && now - lastLooked < LOOK_MS) return
        lastForecast = forecastAt
        lastLooked = now

        val report = WeatherStore.report(context) ?: return
        val hourNow = report.hourNow()

        // A warning whose hour has come and gone is a warning about the past. Taken down
        // rather than left for the user to swipe away, and only the hour is forgotten -
        // the time it was said stays, because the quiet spell afterwards is measured from
        // it and outlives the notification itself.
        announcedFor(context)?.let { announced ->
            if (announced < hourNow) {
                clear(context)
                forget(context)
            }
        }

        // Already raining. Announcing rain to somebody standing in it is the surest way to
        // have the setting switched off.
        val current = report.hours.firstOrNull { it.time == hourNow }
        if (current != null && current.chance >= CHANCE_FLOOR) return

        // The window. Strictly after this hour - "in the next two hours" is about what has
        // not happened yet - and the first one that qualifies, because that is when to say
        // it starts.
        val ahead = report.hours.filter { it.time > hourNow }.take(WINDOW_HOURS)
        val rain = ahead.firstOrNull { it.chance >= CHANCE_FLOOR } ?: return

        if (rain.time == announcedFor(context)) return
        if (now - announcedAt(context) < QUIET_MS) return
        if (rain.hour in NIGHT_FROM until NIGHT_UNTIL) return

        post(context, rain)
        remember(context, rain.time, now)
    }

    /** Takes the notification down. The rain has been and gone, or the setting is off. */
    fun clear(context: Context) {
        try {
            notifications(context).cancel(ID)
        } catch (e: Exception) {
            Log.w(TAG, "could not clear the rain notification", e)
        }
    }

    private fun post(context: Context, rain: WeatherHour) {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, OPEN_CODE,
            Intent(context, rocks.gorjan.gokixp.MainActivity::class.java).apply {
                action = ACTION_SHOW_WEATHER
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL)
            // The program's own mark, so somebody reading the shade knows which of the
            // phone's apps is talking to them before they have read a word of it.
            .setSmallIcon(R.drawable.wp81_glyph_weather)
            .setContentTitle("${rain.chance}% chance of rain")
            .setContentText("Starting at ${clock(context, rain.hour)}")
            .metroLook(context)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()

        try {
            notifications(context).notify(ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "the system would not take the rain notification", e)
        }
    }

    /** The hour it starts, in whichever clock the phone is set to. */
    private fun clock(context: Context, hour: Int): String {
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return "${if (hour < 10) "0$hour" else hour}:00"
        }
        val twelve = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$twelve:00 ${if (hour < 12) "am" else "pm"}"
    }

    // ------------------------------------------------------------------ what is remembered

    private fun prefs(context: Context) = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** The start hour last announced, as its own timestamp - "2026-09-02T18:00". */
    private fun announcedFor(context: Context): String? =
        prefs(context).getString(KEY_FOR, null)

    private fun announcedAt(context: Context): Long = try {
        prefs(context).getLong(KEY_AT, 0L)
    } catch (e: Exception) {
        0L
    }

    private fun remember(context: Context, hour: String, at: Long) {
        prefs(context).edit().putString(KEY_FOR, hour).putLong(KEY_AT, at).apply()
    }

    /** Drops the hour but keeps when it was said. See the caller for why. */
    private fun forget(context: Context) {
        prefs(context).edit().remove(KEY_FOR).apply()
    }

    /**
     * Held in memory as well as looked at on a timer.
     *
     * These two are the difference between reading a few thousand numbers out of
     * preferences every twelve seconds and reading them when something has happened.
     */
    @Volatile
    private var lastLooked = 0L

    @Volatile
    private var lastForecast = 0L

    private fun ensureChannel(context: Context) {
        val manager = notifications(context)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rain", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A warning when rain is due in the next couple of hours"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun notifications(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** What the launcher listens for to open Weather from the shade. */
    const val ACTION_SHOW_WEATHER = "rocks.gorjan.gokixp.SHOW_WEATHER"

    /** How far ahead is "about to". */
    private const val WINDOW_HOURS = 2

    /**
     * The chance below which this says nothing.
     *
     * Not the 20% the app itself draws a figure at: a column on a chart is there to be
     * read by somebody already looking, and a notification interrupts. At an even chance
     * it is worth interrupting for; below it, it is a guess with a sound attached.
     */
    private const val CHANCE_FLOOR = 50

    /** How long after saying something this stays quiet, whatever else it sees. */
    private const val QUIET_MS = 4L * 60L * 60L * 1000L

    /** How often the forecast is looked at when nothing about it has changed. */
    private const val LOOK_MS = 10L * 60L * 1000L

    /** The hours nothing is posted in. Rain at four will still be there at seven. */
    private const val NIGHT_FROM = 0
    private const val NIGHT_UNTIL = 7

    private const val CHANNEL = "wp81_weather_rain"
    private const val ID = 8600
    private const val OPEN_CODE = 8601

    private const val KEY_FOR = "weather_rain_notified_for"
    private const val KEY_AT = "weather_rain_notified_at"

    private const val TAG = "WP81Weather"
}
