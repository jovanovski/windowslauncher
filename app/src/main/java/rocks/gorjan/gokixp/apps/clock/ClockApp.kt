package rocks.gorjan.gokixp.apps.clock

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import rocks.gorjan.gokixp.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Clock app logic and UI controller
 */
class ClockApp(
    private val context: Context,
    private val onSoundPlay: () -> Unit,
    private val onCloseWindow: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    // UI references
    private var monthTextView: TextView? = null
    private var yearTextView: TextView? = null
    private var timeTextView: TextView? = null
    private var timezoneTextView: TextView? = null
    private var okButton: View? = null
    private var cancelButton: View? = null
    private var calendarGridView: GridView? = null
    private var hourHand: ImageView? = null
    private var minuteHand: ImageView? = null
    private var secondHand: ImageView? = null
    private var openCalendarView: View? = null
    private var openClockView: View? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        monthTextView = contentView.findViewById(R.id.clock_month)
        yearTextView = contentView.findViewById(R.id.clock_year)
        timeTextView = contentView.findViewById(R.id.clock_time)
        timezoneTextView = contentView.findViewById(R.id.clock_timezone)
        okButton = contentView.findViewById(R.id.clock_ok)
        cancelButton = contentView.findViewById(R.id.clock_cancel)
        calendarGridView = contentView.findViewById(R.id.clock_calendar)
        hourHand = contentView.findViewById(R.id.clock_arrow_hour)
        minuteHand = contentView.findViewById(R.id.clock_arrow_minute)
        secondHand = contentView.findViewById(R.id.clock_arrow_second)
        openCalendarView = contentView.findViewById(R.id.clock_open_calendar)
        openClockView = contentView.findViewById(R.id.clock_open_clock)

        // Setup clock hand pivot points
        val hourMinutePivotDp = 19f
        val secondPivotDp = 8f

        val hourMinutePivotPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            hourMinutePivotDp,
            context.resources.displayMetrics
        )

        val secondPivotPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            secondPivotDp,
            context.resources.displayMetrics
        )

        hourHand?.apply {
            pivotX = (width / 2f)
            pivotY = hourMinutePivotPx
        }

        minuteHand?.apply {
            pivotX = (width / 2f)
            pivotY = hourMinutePivotPx
        }

        secondHand?.apply {
            pivotX = (width / 2f)
            pivotY = secondPivotPx
        }

        // Post to ensure views are laid out before setting pivot
        contentView.post {
            hourHand?.apply {
                pivotX = (width / 2f)
                pivotY = hourMinutePivotPx
            }

            minuteHand?.apply {
                pivotX = (width / 2f)
                pivotY = hourMinutePivotPx
            }

            secondHand?.apply {
                pivotX = (width / 2f)
                pivotY = secondPivotPx
            }
        }

        // Setup button click listeners
        okButton?.setOnClickListener {
            onSoundPlay()
            onCloseWindow()
        }

        cancelButton?.setOnClickListener {
            onSoundPlay()
            onCloseWindow()
        }

        // Setup click listeners for opening default apps
        openCalendarView?.setOnClickListener {
            onSoundPlay()
            openDefaultCalendar()
        }

        openClockView?.setOnClickListener {
            onSoundPlay()
            openDefaultClock()
        }

        // Setup calendar
        setupCalendar()

        // Start updating the clock
        startClockUpdate()

        return contentView
    }

    /**
     * Start periodic clock updates
     */
    private fun startClockUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateClock()
                handler.postDelayed(this, 1000) // Update every second
            }
        }
        updateRunnable?.let { handler.post(it) }
    }

    /**
     * Update clock fields with current time
     */
    private fun updateClock() {
        val calendar = Calendar.getInstance()

        // Month
        val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
        monthTextView?.text = monthFormat.format(calendar.time)

        // Year
        val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
        yearTextView?.text = yearFormat.format(calendar.time)

        // Time (HH:mm:ss format)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        timeTextView?.text = timeFormat.format(calendar.time)

        // Timezone
        val timezone = TimeZone.getDefault()
        val offsetMillis = timezone.getOffset(calendar.timeInMillis)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = (offsetMillis / (1000 * 60)) % 60

        val offsetString = String.format(
            "(GMT %s%02d:%02d) %s",
            if (offsetHours >= 0) "+" else "",
            offsetHours,
            Math.abs(offsetMinutes),
            timezone.displayName
        )

        timezoneTextView?.text = offsetString

        // Update clock hands
        updateClockHands(calendar)
    }

    /**
     * Update the rotation of clock hands based on current time
     */
    private fun updateClockHands(calendar: Calendar) {
        val hours = calendar.get(Calendar.HOUR) // 12-hour format
        val minutes = calendar.get(Calendar.MINUTE)
        val seconds = calendar.get(Calendar.SECOND)

        // Calculate rotation angles
        // All hands start at 6 (180 degrees), so we need to add 180 to base rotation

        // Hour hand: 30 degrees per hour + 0.5 degrees per minute
        val hourAngle = 180f + (hours * 30f) + (minutes * 0.5f)

        // Minute hand: 6 degrees per minute + 0.1 degrees per second
        val minuteAngle = 180f + (minutes * 6f) + (seconds * 0.1f)

        // Second hand: 6 degrees per second
        val secondAngle = 180f + (seconds * 6f)

        // Apply rotations
        hourHand?.rotation = hourAngle
        minuteHand?.rotation = minuteAngle
        secondHand?.rotation = secondAngle
    }

    /**
     * Setup calendar grid with current month's days
     */
    private fun setupCalendar() {
        val calendar = Calendar.getInstance()
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)

        // Get first day of month
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1 = Sunday, 2 = Monday, etc.
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Create list of calendar items
        val calendarDays = mutableListOf<CalendarDay>()

        // Add empty cells before the first day
        // For Mon-Sun layout: Monday = 0, Tuesday = 1, ..., Sunday = 6
        // Calendar.DAY_OF_WEEK: Sunday = 1, Monday = 2, ..., Saturday = 7
        // So offset = (firstDayOfWeek + 5) % 7
        val offset = if (firstDayOfWeek == 1) 6 else firstDayOfWeek - 2
        for (i in 0 until offset) {
            calendarDays.add(CalendarDay(0, false))
        }

        // Add all days of the month
        for (day in 1..daysInMonth) {
            calendarDays.add(CalendarDay(day, day == currentDay))
        }

        // Set adapter
        calendarGridView?.adapter = CalendarAdapter(calendarDays)
    }

    /**
     * Data class for calendar day
     */
    private data class CalendarDay(val day: Int, val isToday: Boolean)

    /**
     * Adapter for calendar grid
     */
    private inner class CalendarAdapter(private val days: List<CalendarDay>) : BaseAdapter() {
        override fun getCount(): Int = days.size

        override fun getItem(position: Int): Any = days[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val textView = (convertView as? TextView) ?: TextView(context)
            val day = days[position]

            textView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Set font
                typeface = context.resources.getFont(R.font.micross_block)

                if (day.day == 0) {
                    // Empty cell
                    text = ""
                    setBackgroundColor(Color.TRANSPARENT)
                } else {
                    // Day cell
                    text = day.day.toString()
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER

                    if (day.isToday) {
                        // Current day - blue background with white text
                        setBackgroundColor(Color.parseColor("#0A246A"))
                        setTextColor(Color.WHITE)
                    } else {
                        // Regular day - black text
                        setBackgroundColor(Color.TRANSPARENT)
                        setTextColor(Color.BLACK)
                    }
                }
            }

            return textView
        }
    }

    /**
     * Open the default calendar app
     */
    private fun openDefaultCalendar() {
        try {
            val calendarIntent = Intent(Intent.ACTION_VIEW)
            calendarIntent.data = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .build()
            calendarIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(calendarIntent)
        } catch (e: Exception) {
            Log.e("ClockApp", "Error opening calendar", e)
            // Fallback: try to open calendar app directly
            try {
                val fallbackIntent = Intent(Intent.ACTION_MAIN)
                fallbackIntent.addCategory(Intent.CATEGORY_APP_CALENDAR)
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("ClockApp", "Error opening calendar fallback", e2)
            }
        }
    }

    /**
     * Open the default clock/alarm app
     */
    private fun openDefaultClock() {
        try {
            val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            clockIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(clockIntent)
        } catch (e: Exception) {
            Log.e("ClockApp", "Error opening clock", e)
            // Fallback: try generic clock action
            try {
                val fallbackIntent = Intent(Intent.ACTION_MAIN)
                fallbackIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                fallbackIntent.setPackage("com.google.android.deskclock")
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("ClockApp", "Error opening clock fallback", e2)
            }
        }
    }

    /**
     * Stop clock updates
     */
    fun cleanup() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }
}
