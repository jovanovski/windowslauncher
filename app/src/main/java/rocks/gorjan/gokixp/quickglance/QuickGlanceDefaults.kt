package rocks.gorjan.gokixp.quickglance

import android.content.Context
import android.util.Log
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import java.text.SimpleDateFormat
import java.util.*

object QuickGlanceDefaults {

    fun createDefaultContent(context: Context): QuickGlanceData {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)

        // Create ordinal suffix for day
        val ordinalSuffix = when {
            dayOfMonth in 11..13 -> "th" // Special cases for 11th, 12th, 13th
            dayOfMonth % 10 == 1 -> "st"
            dayOfMonth % 10 == 2 -> "nd"
            dayOfMonth % 10 == 3 -> "rd"
            else -> "th"
        }

        val formattedDate = dateFormat.format(calendar.time) + ordinalSuffix

        // Get weather from cached data
        val weatherSubtitle = getWeatherSubtitle()

        return QuickGlanceData(
            title = formattedDate,
            subtitle = weatherSubtitle,
            iconResourceId = R.drawable.clippy_still,
            priority = 10, // Low priority, only shows when no events
            sourceId = "default_fallback",
            tapAction = createCalendarTapAction(context)
        )
    }

    private fun getWeatherSubtitle(): String {
        return try {
            // Get MainActivity instance to access cached weather
            val mainActivity = MainActivity.getInstance()
            val weatherJson = mainActivity?.getCachedWeatherJson()

            if (weatherJson != null && mainActivity.isWeatherDataFresh(60)) {
                val currentWeather = weatherJson.getJSONObject("current")
                val temperature = currentWeather.getDouble("temperature_2m")
                val weatherCode = currentWeather.getInt("weather_code")
                val formattedTemp = mainActivity.formatTemperatureForWidget(temperature)

                val condition = getWeatherCondition(weatherCode)
                "$formattedTemp and $condition"
            } else {
                "your pal, Clippy"
            }
        } catch (e: Exception) {
            Log.e("QuickGlanceDefaults", "Error getting weather subtitle", e)
            "your pal, Clippy"
        }
    }

    private fun getWeatherCondition(weatherCode: Int): String {
        return when (weatherCode) {
            0 -> "clear"
            1, 2, 3 -> "partly cloudy"
            45, 48 -> "foggy"
            51, 53, 55 -> "drizzling"
            56, 57 -> "freezing drizzle"
            61, 63, 65 -> "rainy"
            66, 67 -> "freezing rain"
            71, 73, 75 -> "snowy"
            77 -> "snow grains"
            80, 81, 82 -> "rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorms"
            96, 99 -> "heavy thunderstorms"
            else -> "unknown"
        }
    }

    private fun createCalendarTapAction(context: Context): TapAction {
        // Try common calendar apps in order of preference
        val calendarPackages = listOf(
            "com.google.android.calendar",     // Google Calendar
            "com.samsung.android.calendar",    // Samsung Calendar
            "com.android.calendar",            // AOSP Calendar
            "com.htc.calendar",                // HTC Calendar
            "com.lge.calendar",                // LG Calendar
            "com.miui.calendar",               // MIUI Calendar
            "com.huawei.calendar"              // Huawei Calendar
        )

        // Find the first installed calendar app
        for (packageName in calendarPackages) {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                return TapAction.OpenApp(packageName) {
                    // Fallback: open default calendar intent
                    openCalendarWithIntent(context)
                }
            } catch (e: Exception) {
                // Package not found, try next
            }
        }

        // No specific calendar app found, use generic calendar intent
        return TapAction.CustomAction {
            openCalendarWithIntent(context)
        }
    }

    private fun openCalendarWithIntent(context: Context) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to generic calendar view intent
            try {
                val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("content://com.android.calendar/time")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("QuickGlanceDefaults", "Could not open calendar app", e2)
            }
        }
    }
}