package rocks.gorjan.gokixp

/**
 * The places on the desktop that hand off to another app on the phone, each of which can be
 * pointed at an app of the user's choosing from Settings > Default Apps.
 *
 * The weather and swipe right keys predate the settings page - both were only ever set from
 * an app's context menu in the Start menu - so they keep their old names and nobody who set
 * one loses it.
 */
enum class DefaultAppRole(val prefKey: String, val label: String) {
    WEATHER("weather_app", "Weather"),
    CLOCK("clock_app", "Clock"),
    CALENDAR("calendar_app", "Calendar"),
    SWIPE_RIGHT("swipe_right_app", "Swipe right")
}
