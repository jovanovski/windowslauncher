package rocks.gorjan.gokixp.quickglance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

class CalendarDataProvider(private val context: Context) : QuickGlanceDataProvider {
    
    private var updateJob: Job? = null
    private var callback: ((QuickGlanceData?) -> Unit)? = null
    
    override suspend fun getCurrentData(): QuickGlanceData? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if we have calendar permission
                if (!hasCalendarPermission()) {
                    return@withContext QuickGlanceData(
                        title = "Calendar Access Needed",
                        subtitle = "Tap to grant permission",
                        iconResourceId = R.drawable.clippy_still, // TODO: Use permission icon
                        priority = 50,
                        sourceId = "calendar_permission"
                    )
                }
                
                getNextCalendarEvent()
            } catch (e: Exception) {
                Log.e("CalendarDataProvider", "Error getting calendar data", e)
                // Return permission request if it's a security exception
                if (e is SecurityException) {
                    return@withContext QuickGlanceData(
                        title = "Calendar Access Needed",
                        subtitle = "Tap to grant permission",
                        iconResourceId = R.drawable.clippy_still, // TODO: Use permission icon
                        priority = 50,
                        sourceId = "calendar_permission"
                    )
                }
                null
            }
        }
    }
    
    override fun startUpdates(callback: (QuickGlanceData?) -> Unit) {
        this.callback = callback
        updateJob?.cancel()
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            // Immediate first update
            val initialData = getCurrentData()
            callback(initialData)
            
            // Then continue with regular updates
            while (isActive) {
                delay(30000) // Update every 30 seconds for better responsiveness
                val data = getCurrentData()
                callback(data)
            }
        }
    }
    
    override fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
        callback = null
    }
    
    override fun getProviderId(): String = "calendar"
    
    fun forceRefresh() {
        // Immediately check for new events and notify callback
        CoroutineScope(Dispatchers.Main).launch {
            val data = getCurrentData()
            callback?.invoke(data)
//            Log.d("CalendarDataProvider", "Forced calendar refresh completed")
        }
    }
    
    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun createCalendarTapAction(): TapAction {
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
                    openCalendarWithIntent()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // This package is not installed, try next one
            }
        }
        
        // No specific calendar app found, use generic calendar intent
        return TapAction.CustomAction {
            openCalendarWithIntent()
        }
    }
    
    private fun openCalendarWithIntent() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to generic calendar view intent
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("content://com.android.calendar/time")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("CalendarDataProvider", "Could not open calendar app", e2)
            }
        }
    }


    private fun getNextCalendarEvent(): QuickGlanceData? {
        val contentResolver = context.contentResolver
        val now = System.currentTimeMillis()
        
        // Query for events in the next 6 hours and ongoing events from the last 15 minutes
        val startTime = now - (15 * 60 * 1000) // 15 minutes ago
        val endTime = now + (6 * 60 * 60 * 1000) // 6 hours from now
        
//        Log.d("CalendarDataProvider", "Querying calendar events from ${java.util.Date(startTime)} to ${java.util.Date(endTime)}")

        // First, let's check if there are any calendars at all
        try {
            val calendarsCursor = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.VISIBLE,
                    CalendarContract.Calendars.SYNC_EVENTS
                ),
                null,
                null,
                null
            )

            calendarsCursor?.use { cursor ->
//                Log.d("CalendarDataProvider", "Found ${cursor.count} calendars:")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val visible = cursor.getInt(2)
                    val syncEvents = cursor.getInt(3)
//                    Log.d("CalendarDataProvider", "  Calendar: ID=$id, Name='$name', Visible=$visible, SyncEvents=$syncEvents")
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarDataProvider", "Error querying calendars", e)
        }

        // Now let's check events directly (not instances) - specifically look for General Blocking events in our time window
        try {
            val eventsCursor = contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Events.CALENDAR_ID
                ),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(startTime.toString(), endTime.toString()),
                CalendarContract.Events.DTSTART + " ASC"
            )

            eventsCursor?.use { cursor ->
//                Log.d("CalendarDataProvider", "Found ${cursor.count} events in Events table within time window")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1)
                    val start = cursor.getLong(2)
                    val end = cursor.getLong(3)
                    val calName = cursor.getString(4)
                    val calId = cursor.getLong(5)
//                    Log.d("CalendarDataProvider", "  Event: ID=$id, Title='$title', Calendar='$calName' (ID=$calId), Start=${java.util.Date(start)}")
                }
            }

            // Also specifically check General Blocking calendar (ID=28)
            val blockingEventsCursor = contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf("28", startTime.toString(), endTime.toString()),
                CalendarContract.Events.DTSTART + " ASC"
            )

            blockingEventsCursor?.use { cursor ->
//                Log.d("CalendarDataProvider", "Found ${cursor.count} events in 'General Blocking' calendar within time window")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1)
                    val start = cursor.getLong(2)
                    val end = cursor.getLong(3)
//                    Log.d("CalendarDataProvider", "  Blocking Event: ID=$id, Title='$title', Start=${java.util.Date(start)}, End=${java.util.Date(end)}")
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarDataProvider", "Error querying events", e)
        }

        // Use Instances URI to get expanded recurring events
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startTime.toString())
            .appendPath(endTime.toString())
            .build()

//        Log.d("CalendarDataProvider", "Instances URI: $instancesUri")

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances._ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.VISIBLE  // Add visible field to see if this helps
        )

        // Try to include events from hidden calendars by querying all calendars first
        val calendarIds = mutableListOf<String>()
        try {
            val calendarsCursor = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null, // Include both visible and hidden calendars
                null,
                null
            )
            calendarsCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendarIds.add(cursor.getLong(0).toString())
                }
            }
//            Log.d("CalendarDataProvider", "Including events from ${calendarIds.size} calendars (both visible and hidden)")
        } catch (e: Exception) {
//            Log.e("CalendarDataProvider", "Error getting calendar IDs", e)
        }

        // Create selection to include events from all calendars (visible and hidden)
        val selection: String? = if (calendarIds.isNotEmpty()) {
            val placeholders = calendarIds.map { "?" }.joinToString(",")
            "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
        } else {
            null
        }
        val selectionArgs: Array<String>? = if (calendarIds.isNotEmpty()) {
            calendarIds.toTypedArray()
        } else {
            null
        }

        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        val cursor: Cursor? = try {
            contentResolver.query(
                instancesUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
        } catch (e: Exception) {
            Log.e("CalendarDataProvider", "Error querying instances", e)
            null
        }
        
        var eventCount = 0
        cursor?.use {
//            Log.d("CalendarDataProvider", "Found ${it.count} calendar instances")
            
            while (it.moveToNext()) {
                eventCount++
                val title = it.getString(0) ?: "Untitled Event"
                val beginTime = it.getLong(1)
                val endTime = it.getLong(2)
                val allDay = it.getInt(3)
                val calendarName = it.getString(4) ?: "Unknown Calendar"
                val availability = if (it.isNull(5)) CalendarContract.Events.AVAILABILITY_BUSY else it.getInt(5)
                val eventId = it.getLong(6)
                val instanceId = it.getLong(7)
                val calendarId = it.getLong(8)
                val visible = it.getInt(9) // New visible field

                if(allDay==1){
                    continue
                }

                // Log each event found for debugging
//                Log.d("CalendarDataProvider", "Event #$eventCount: '$title' in '$calendarName' " +
//                        "from ${java.util.Date(beginTime)} to ${java.util.Date(endTime)} " +
//                        "(eventId=$eventId, instanceId=$instanceId, calendarId=$calendarId, " +
//                        "availability=$availability, allDay=$allDay, visible=$visible)")
                
                // Process all events regardless of availability
                val eventData = processEvent(title, beginTime, endTime, now, calendarName)
                if (eventData != null) {
//                    Log.d("CalendarDataProvider", "Returning event: '$title' from '$calendarName'")
                    return eventData
                } else {
//                    Log.d("CalendarDataProvider", "Event '$title' was filtered out by processEvent")
                }
            }
        }
        
//        Log.d("CalendarDataProvider", "No suitable events found out of $eventCount total events")
        // No events found, return fallback with current date
        return QuickGlanceDefaults.createDefaultContent(context)
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
            Log.e("CalendarDataProvider", "Error getting weather subtitle", e)
            "your pal, Clippy"
        }
    }
    
    private fun getWeatherCondition(weatherCode: Int): String {
        return when (weatherCode) {
            0 -> "clear sky"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45 -> "fog"
            48 -> "rime fog"
            51 -> "light drizzle"
            53 -> "moderate drizzle"
            55 -> "dense drizzle"
            56 -> "light freezing drizzle"
            57 -> "dense freezing drizzle"
            61 -> "light rain"
            63 -> "moderate rain"
            65 -> "heavy rain"
            66 -> "light freezing rain"
            67 -> "heavy freezing rain"
            71 -> "light snowfall"
            73 -> "moderate snowfall"
            75 -> "heavy snowfall"
            77 -> "snow grains"
            80 -> "light rain showers"
            81 -> "moderate rain showers"
            82 -> "violent rain showers"
            85 -> "light snow showers"
            86 -> "heavy snow showers"
            95 -> "thunderstorm"
            96 -> "thunderstorm with slight hail"
            99 -> "thunderstorm with heavy hail"
            else -> "unknown conditions"
        }
    }
    
    private fun processEvent(
        title: String, 
        startTime: Long, 
        endTime: Long, 
        now: Long,
        calendarName: String?
    ): QuickGlanceData? {
        
        val isOngoing = now >= startTime && now < endTime
        val isUpcoming = startTime > now
        val isPast = endTime <= now

//        Log.d("CalendarDataProvider", "Processing event '$title': " +
//                "now=$now, start=$startTime, end=$endTime, " +
//                "isOngoing=$isOngoing, isUpcoming=$isUpcoming, isPast=$isPast")

        when {
            isOngoing -> {
                val minutesAgo = kotlin.math.floor((now - startTime) / 60000.0).toInt()
//                Log.d("CalendarDataProvider", "Event '$title' is ongoing, started $minutesAgo minutes ago")
                if (minutesAgo <= 15) { // Only show events that started within the last 15 minutes
                    val subtitle = if (minutesAgo == 0) {
                        "just started"
                    } else if (minutesAgo == 1) {
                        "started 1 minute ago"
                    } else {
                        "started $minutesAgo minutes ago"
                    }
                    return QuickGlanceData(
                        title = title,
                        subtitle = subtitle,
                        iconResourceId = R.drawable.clippy_still, // TODO: Use calendar icon
                        priority = 100, // High priority for ongoing events
                        sourceId = "calendar",
                        tapAction = createCalendarTapAction()
                    )
                }
                // Ignore events ongoing for more than 15 minutes
//                Log.d("CalendarDataProvider", "Event '$title' is ongoing but started more than 15 minutes ago, ignoring")
                return null
            }
            
            isUpcoming -> {
                val minutesUntil = kotlin.math.ceil((startTime - now) / 60000.0).toInt()
                val hoursUntil = minutesUntil / 60
                val remainingMinutes = minutesUntil % 60
                
//                Log.d("CalendarDataProvider", "Event '$title' is upcoming in $minutesUntil minutes ($hoursUntil hours, $remainingMinutes min)")
                
                when {
                    minutesUntil <= 60 -> {
                        val subtitle = when (minutesUntil) {
                            0 -> "starting now"
                            1 -> "in 1 minute"
                            else -> "in $minutesUntil minutes"
                        }
                        return QuickGlanceData(
                            title = title,
                            subtitle = subtitle,
                            iconResourceId = R.drawable.clippy_still, // TODO: Use calendar icon
                            priority = 90 - minutesUntil, // Higher priority for sooner events
                            sourceId = "calendar",
                            tapAction = createCalendarTapAction()
                        )
                    }
                    
                    hoursUntil <= 6 -> {
                        val subtitle = when {
                            remainingMinutes == 0 -> {
                                if (hoursUntil == 1) "In 1 hour" else "In $hoursUntil hours"
                            }
                            hoursUntil == 1 -> {
                                if (remainingMinutes == 1) {
                                    "in 1 hour and 1 minute"
                                } else {
                                    "in 1 hour and $remainingMinutes minutes"
                                }
                            }
                            else -> {
                                if (remainingMinutes == 1) {
                                    "in $hoursUntil hours and 1 minute"
                                } else {
                                    "in $hoursUntil hours and $remainingMinutes minutes"
                                }
                            }
                        }
                        return QuickGlanceData(
                            title = title,
                            subtitle = subtitle,
                            iconResourceId = R.drawable.clippy_still, // TODO: Use calendar icon
                            priority = 30 - hoursUntil, // Lower priority for later events
                            sourceId = "calendar",
                            tapAction = createCalendarTapAction()
                        )
                    }
                    
                    else -> {
//                        Log.d("CalendarDataProvider", "Event '$title' is more than 6 hours away ($hoursUntil hours), ignoring")
                        return null // Ignore events more than 6 hours away
                    }
                }
            }
            
            else -> {
//                Log.d("CalendarDataProvider", "Event '$title' is in the past, ignoring")
                return null // Past event
            }
        }
    }
}