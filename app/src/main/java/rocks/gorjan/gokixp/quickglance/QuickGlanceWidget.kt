package rocks.gorjan.gokixp.quickglance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

class QuickGlanceWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Drag functionality
    private var isDragging = false
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var longPressRunnable: Runnable? = null
    private var isLongPress = false
    private var hasMoved = false
    
    // Movement threshold for distinguishing between tap, drag, and long press
    private val MOVEMENT_THRESHOLD = 10f
    
    // SharedPreferences keys for position and settings - use MainActivity.PREFS_NAME for consistency
    private val KEY_WIDGET_X = "widget_x"
    private val KEY_WIDGET_Y = "widget_y"
    private val KEY_SHOW_CALENDAR_EVENTS = "show_calendar_events"
    
    // UI components
    private lateinit var iconView: ImageView
    private lateinit var viewPager: ViewPager2
    private lateinit var dotsIndicator: LinearLayout
    private lateinit var panelAdapter: QuickGlancePanelAdapter
    
    // Data management
    private var dataManager: QuickGlanceDataManager? = null
    private var panels = mutableListOf<QuickGlancePanel>()
    private var permissionRequestCallback: (() -> Unit)? = null
    private var contextMenuCallback: ((Float, Float) -> Unit)? = null

    init {
        // Set orientation to horizontal
        orientation = HORIZONTAL
        
        // Set padding and background
        setPadding(16, 8, 16, 8)
        setBackgroundResource(R.drawable.clippy_background) // Reuse the semi-transparent background
        
        // Make sure view is visible
        visibility = View.VISIBLE
        
        setupLayout()
        
        Log.d("QuickGlanceWidget", "QuickGlanceWidget initialization completed")
    }
    
    private fun setupLayout() {
        // Calculate 80% of screen width for the widget
        val screenWidth = context.resources.displayMetrics.widthPixels
        val widgetWidth = (screenWidth * 0.8f).toInt()

        // Set fixed width for the widget and change orientation to vertical
        orientation = VERTICAL
        layoutParams = LayoutParams(widgetWidth, LayoutParams.WRAP_CONTENT)

        // Create horizontal container for icon and content
        val horizontalContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Create icon
        iconView = ImageView(context).apply {
            val iconSize = (48 * context.resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, 12, 0) // Right margin for spacing
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.clippy_still) // Clippy icon
        }
        horizontalContainer.addView(iconView)

        // Create content container (ViewPager2 for swiping)
        val contentContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            // Fill remaining space after icon and margins
            val iconSize = (48 * context.resources.displayMetrics.density).toInt()
            val padding = (16 * context.resources.displayMetrics.density).toInt() * 2 // Left and right padding
            val iconMargin = (12 * context.resources.displayMetrics.density).toInt()
            val availableWidth = widgetWidth - iconSize - iconMargin - padding
            layoutParams = LayoutParams(availableWidth, LayoutParams.WRAP_CONTENT)
        }

        // Create ViewPager2 for swipeable panels
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }

        // Initialize adapter
        panelAdapter = QuickGlancePanelAdapter()
        panelAdapter.setOnPanelTapListener { panel ->
            handlePanelTap(panel)
        }
        panelAdapter.setOnPanelLongPressListener {
            showQuickGlanceContextMenu()
        }
        viewPager.adapter = panelAdapter

        contentContainer.addView(viewPager)
        horizontalContainer.addView(contentContainer)
        addView(horizontalContainer)

        // Create dots indicator (initially hidden)
        dotsIndicator = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.START
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * context.resources.displayMetrics.density).toInt()
                leftMargin = (48 * context.resources.displayMetrics.density).toInt()
            }
            visibility = View.GONE // Hide by default
        }
        addView(dotsIndicator)

        // Set up initial panels
        initializePanels()
    }

    private fun initializePanels() {
        // Always add default panel (date/weather)
        val defaultPanel = createDefaultPanel()
        panels.clear()
        panels.add(defaultPanel)
        updatePanelsDisplay()
    }

    private fun createDefaultPanel(): QuickGlancePanel {
        val defaultData = QuickGlanceDefaults.createDefaultContent(context)
        return QuickGlancePanel(
            id = "default",
            title = defaultData.title,
            subtitle = defaultData.subtitle,
            tapAction = defaultData.tapAction,
            priority = 1 // Default panel has lower priority than calendar
        )
    }

    fun refreshDefaultPanel() {
        // Find and update the default panel with fresh weather data
        val defaultPanelIndex = panels.indexOfFirst { it.id == "default" }
        if (defaultPanelIndex != -1) {
            val newDefaultPanel = createDefaultPanel()
            panels[defaultPanelIndex] = newDefaultPanel
            updatePanelsDisplay()
        }
    }

    private fun handlePanelTap(panel: QuickGlancePanel) {
        panel.tapAction?.let { tapAction ->
            executeTapAction(tapAction)
        }
    }

    private fun updatePanelsDisplay() {
        panelAdapter.updatePanels(panels)
        updateDotsIndicator()
    }

    private fun updateDotsIndicator() {
        dotsIndicator.removeAllViews()

        if (panels.size <= 1) {
            dotsIndicator.visibility = View.GONE
            return
        }

        dotsIndicator.visibility = View.VISIBLE
        val dotSize = (6 * context.resources.displayMetrics.density).toInt()
        val dotMargin = (2 * context.resources.displayMetrics.density).toInt()

        for (i in panels.indices) {
            val dot = View(context).apply {
                layoutParams = LayoutParams(dotSize, dotSize).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                setBackgroundResource(if (i == viewPager.currentItem) R.drawable.dot_indicator else R.drawable.dot_indicator_inactive)
                alpha = if (i == viewPager.currentItem) 1.0f else 0.5f
            }
            dotsIndicator.addView(dot)
        }

        // Set up page change listener for dots
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDotSelection(position)
            }
        })
    }

    private fun updateDotSelection(position: Int) {
        for (i in 0 until dotsIndicator.childCount) {
            val dot = dotsIndicator.getChildAt(i)
            dot.setBackgroundResource(if (i == position) R.drawable.dot_indicator else R.drawable.dot_indicator_inactive)
            dot.alpha = if (i == position) 1.0f else 0.5f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Check if touch is on the icon for dragging
        val iconLocation = IntArray(2)
        iconView.getLocationInWindow(iconLocation)
        val iconLeft = iconLocation[0]
        val iconTop = iconLocation[1]
        val iconRight = iconLeft + iconView.width
        val iconBottom = iconTop + iconView.height

        val touchX = event.rawX.toInt()
        val touchY = event.rawY.toInt()

        val isTouchOnIcon = touchX >= iconLeft && touchX <= iconRight && touchY >= iconTop && touchY <= iconBottom

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPress = false
                hasMoved = false
                initialTouchX = event.rawX
                initialTouchY = event.rawY

                if (isTouchOnIcon) {
                    // Handle icon dragging
                    isDragging = true
                    initialX = x
                    initialY = y
                }

                // Start long press detection for both icon and content area
                longPressRunnable = Runnable {
                    // Only trigger long press if the finger hasn't moved
                    if (!hasMoved) {
                        isLongPress = true
                        Log.d("QuickGlanceWidget", "Long press detected - showing context menu")
                        showQuickGlanceContextMenu()
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(longPressRunnable!!, 500) // 500ms for long press

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                // Check if user has moved beyond threshold
                if (Math.abs(deltaX) > MOVEMENT_THRESHOLD || Math.abs(deltaY) > MOVEMENT_THRESHOLD) {
                    // Mark as moved and cancel long press immediately
                    if (!hasMoved) {
                        hasMoved = true
                        longPressRunnable?.let {
                            Handler(Looper.getMainLooper()).removeCallbacks(it)
                            longPressRunnable = null
                        }
                        Log.d("QuickGlanceWidget", "Movement detected, canceling long press")
                    }

                    // Only update position if dragging the icon
                    if (isDragging && isTouchOnIcon) {
                        x = initialX + deltaX
                        y = initialY + deltaY
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Cancel long press if still pending
                longPressRunnable?.let {
                    Handler(Looper.getMainLooper()).removeCallbacks(it)
                    longPressRunnable = null
                }

                if (isDragging) {
                    if (hasMoved && !isLongPress) {
                        // Save position when user finishes dragging (but not on long press)
                        Log.d("QuickGlanceWidget", "Drag completed, saving position")
                        savePosition()
                    } else if (isLongPress) {
                        Log.d("QuickGlanceWidget", "Long press completed, context menu already shown")
                    }

                    // Reset state
                    isDragging = false
                }

                // Reset common state
                hasMoved = false
                isLongPress = false

                // If it was just a tap on content area (not icon) and not a long press, let ViewPager2 handle it
                if (!isTouchOnIcon && !isLongPress) {
                    return super.onTouchEvent(event)
                }

                return true
            }
        }

        // Let ViewPager2 handle swipe gestures for content area
        if (!isTouchOnIcon) {
            return super.onTouchEvent(event)
        }

        return true
    }
    
    
    private fun executeTapAction(tapAction: TapAction) {
        try {
            when (tapAction) {
                is TapAction.OpenApp -> {
                    val intent = context.packageManager.getLaunchIntentForPackage(tapAction.packageName)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        Log.d("QuickGlanceWidget", "Launched app: ${tapAction.packageName}")
                    } else {
                        Log.w("QuickGlanceWidget", "App not found: ${tapAction.packageName}, using fallback")
                        tapAction.fallbackAction?.invoke()
                    }
                }
                
                is TapAction.OpenIntent -> {
                    context.startActivity(tapAction.intent)
                    Log.d("QuickGlanceWidget", "Launched intent: ${tapAction.intent}")
                }
                
                is TapAction.CustomAction -> {
                    tapAction.action.invoke()
                    Log.d("QuickGlanceWidget", "Executed custom action")
                }
            }
        } catch (e: Exception) {
            Log.e("QuickGlanceWidget", "Error executing tap action", e)
            Toast.makeText(context, "Could not open app", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun savePosition() {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_WIDGET_X, x)
            putFloat(KEY_WIDGET_Y, y)
            apply()
        }
        Log.d("QuickGlanceWidget", "Saved Quick Glance position: x=$x, y=$y")
    }
    
    fun restorePosition() {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getFloat(KEY_WIDGET_X, -1f)
        val savedY = prefs.getFloat(KEY_WIDGET_Y, -1f)
        
        if (savedX >= 0 && savedY >= 0) {
            x = savedX
            y = savedY
            Log.d("QuickGlanceWidget", "Restored Quick Glance position: x=$savedX, y=$savedY")
        } else {
            Log.d("QuickGlanceWidget", "No saved position found, using default placement")
        }
    }
    
    // Public methods to update widget content
    fun setIcon(resourceId: Int) {
        iconView.setImageResource(resourceId)
    }

    fun setThemeFont(isWindows98: Boolean) {
        val fontName = if (isWindows98) "Microsoft Sans Serif" else "Tahoma"
        Log.d("QuickGlanceWidget", "Setting theme font to: $fontName")

        val font = MainActivity.getInstance()?.getThemePrimaryFont()
        // Update fonts for all panels in the adapter
        panelAdapter.updateThemeFont(font)
        Log.d("QuickGlanceWidget", "Theme font updated in adapter")
    }

    // Data management methods
    fun initializeDataManager() {
        // Stop existing manager if any
        dataManager?.stopUpdates()

        dataManager = QuickGlanceDataManager(context)

        // Only add calendar provider if calendar events are enabled and permission is granted
        if (isShowCalendarEventsEnabled() && hasCalendarPermission()) {
            val calendarProvider = CalendarDataProvider(context)
            dataManager?.addProvider(calendarProvider)
            Log.d("QuickGlanceWidget", "Data manager initialized with calendar provider")
        } else {
            Log.d("QuickGlanceWidget", "Data manager initialized without calendar provider (disabled or no permission)")
        }

        // Start updates
        dataManager?.startUpdates { data ->
            updatePanelsWithData(data)
        }

        // Always ensure we have at least the default panel
        if (panels.isEmpty()) {
            initializePanels()
        }
    }
    
    fun refreshData() {
        Log.d("QuickGlanceWidget", "Forcing data refresh...")
        // Try to force refresh first, fallback to reinitialize if needed
        CoroutineScope(Dispatchers.Main).launch {
            try {
                dataManager?.forceRefresh()
                Log.d("QuickGlanceWidget", "Force refresh completed")
            } catch (e: Exception) {
                Log.w("QuickGlanceWidget", "Force refresh failed, reinitializing data manager", e)
                // Fallback: reinitialize the data manager to pick up permission changes
                initializeDataManager()
            }
        }
    }
    
    fun forceCalendarRefresh() {
        // Force refresh specifically for calendar provider and update display
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val calendarProvider = dataManager?.providers?.find { it.getProviderId() == "calendar" } as? CalendarDataProvider
                calendarProvider?.forceRefresh()

                // Also force a complete refresh of the data manager to ensure UI updates
                val data = calendarProvider?.getCurrentData()
                if (data != null) {
                    updatePanelsWithData(data)
                } else {
                    // If no calendar data, remove calendar panels and ensure default panel exists
                    panels.removeAll { it.id == "calendar" || it.id == "calendar_permission" }
                    if (panels.none { it.id == "default" }) {
                        panels.add(createDefaultPanel())
                    }
                    updatePanelsDisplay()
                }

                Log.d("QuickGlanceWidget", "Calendar force refresh completed with display update")
            } catch (e: Exception) {
                Log.e("QuickGlanceWidget", "Error forcing calendar refresh", e)
            }
        }
    }
    
    private fun updatePanelsWithData(data: QuickGlanceData?) {
        if (data != null) {
            when (data.sourceId) {
                "calendar" -> {
                    // Add or update calendar panel
                    val calendarPanel = QuickGlancePanel(
                        id = "calendar",
                        title = data.title,
                        subtitle = data.subtitle,
                        tapAction = data.tapAction,
                        priority = 0 // Calendar has highest priority
                    )

                    // Remove existing calendar panel and add new one
                    panels.removeAll { it.id == "calendar" }
                    panels.add(calendarPanel)

                }

                "calendar_permission" -> {
                    // Add calendar permission request panel
                    val permissionPanel = QuickGlancePanel(
                        id = "calendar_permission",
                        title = data.title,
                        subtitle = data.subtitle,
                        tapAction = TapAction.CustomAction { permissionRequestCallback?.invoke() },
                        priority = 0 // High priority for permission requests
                    )

                    panels.removeAll { it.id == "calendar_permission" }
                    panels.add(permissionPanel)

                    Log.d("QuickGlanceWidget", "Added calendar permission panel")
                }

                "default_fallback" -> {
                    // This is the default fallback content (date + weather/Clippy)
                    // It's already handled by the default panel creation below
                    Log.d("QuickGlanceWidget", "Using default fallback content: ${data.title}")
                }

                else -> {
                    // Handle other data sources if needed
                    Log.d("QuickGlanceWidget", "Received data from unknown source: ${data.sourceId}")
                }
            }
        } else {
            // Remove calendar-related panels when no data
            panels.removeAll { it.id == "calendar" || it.id == "calendar_permission" }
            Log.d("QuickGlanceWidget", "Removed calendar panels due to null data")
        }

        // Ensure we always have a default panel
        if (panels.none { it.id == "default" }) {
            panels.add(createDefaultPanel())
        }

        updatePanelsDisplay()
    }
    
    fun setPermissionRequestCallback(callback: () -> Unit) {
        permissionRequestCallback = callback
    }
    
    fun setContextMenuCallback(callback: (Float, Float) -> Unit) {
        contextMenuCallback = callback
    }
    
    private fun showQuickGlanceContextMenu() {
        // Get screen position for context menu
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screenX = location[0] + width / 2f
        val screenY = location[1] + height / 2f
        
        contextMenuCallback?.invoke(screenX, screenY)
    }
    
    // Calendar events setting management
    fun isShowCalendarEventsEnabled(): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHOW_CALENDAR_EVENTS, false) // Default to false (unchecked)
    }

    fun setShowCalendarEvents(enabled: Boolean) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHOW_CALENDAR_EVENTS, enabled).apply()

        if (enabled) {
            // If enabling calendar events but no permission, request it
            if (!hasCalendarPermission()) {
                Log.d("QuickGlanceWidget", "Calendar events enabled but no permission - requesting permission")
                permissionRequestCallback?.invoke()
            } else {
                // Reinitialize data manager to include calendar provider
                initializeDataManager()
            }
        } else {
            // If disabling calendar events, remove calendar panels immediately and reinitialize
            Log.d("QuickGlanceWidget", "Calendar events disabled - removing calendar panels")
            panels.removeAll { it.id == "calendar" || it.id == "calendar_permission" }

            // Ensure we always have a default panel
            if (panels.none { it.id == "default" }) {
                panels.add(createDefaultPanel())
            }

            // Update the display immediately
            updatePanelsDisplay()

            // Reinitialize data manager without calendar provider
            initializeDataManager()
        }

        Log.d("QuickGlanceWidget", "Calendar events setting changed to: $enabled")
    }

    fun handleCalendarPermissionGranted() {
        // Called when calendar permission is granted - reinitialize data manager
        if (isShowCalendarEventsEnabled()) {
            Log.d("QuickGlanceWidget", "Calendar permission granted - reinitializing data manager")
            initializeDataManager()
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
               PackageManager.PERMISSION_GRANTED
    }

    fun destroy() {
        dataManager?.stopUpdates()
        dataManager = null
        permissionRequestCallback = null
        contextMenuCallback = null
    }
}

// Data class for individual panels
data class QuickGlancePanel(
    val id: String,
    val title: String,
    val subtitle: String,
    val tapAction: TapAction? = null,
    val priority: Int = 0 // Lower number = higher priority (shows first)
)

// ViewPager2 adapter for panels
class QuickGlancePanelAdapter : RecyclerView.Adapter<QuickGlancePanelAdapter.PanelViewHolder>() {

    private var panels = listOf<QuickGlancePanel>()
    private var onPanelTap: ((QuickGlancePanel) -> Unit)? = null
    private var currentFont: android.graphics.Typeface? = null
    private var onPanelLongPress: (() -> Unit)? = null

    fun updatePanels(newPanels: List<QuickGlancePanel>) {
        panels = newPanels.sortedBy { it.priority }
        notifyDataSetChanged()
    }

    fun setOnPanelTapListener(listener: (QuickGlancePanel) -> Unit) {
        onPanelTap = listener
    }

    fun setOnPanelLongPressListener(listener: () -> Unit) {
        onPanelLongPress = listener
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PanelViewHolder {
        val textContainer = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Create title
        val titleView = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(parent.context.resources.getColor(R.color.white, null))
            setShadowLayer(4f, 2f, 2f, parent.context.resources.getColor(R.color.black, null))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = currentFont ?: MainActivity.getInstance()?.getThemePrimaryFont()
        }
        val titleLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (4 * parent.context.resources.displayMetrics.density).toInt()
        }
        textContainer.addView(titleView, titleLayoutParams)

        // Create subtitle
        val subtitleView = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(parent.context.resources.getColor(R.color.white, null))
            alpha = 0.8f
            setShadowLayer(4f, 2f, 2f, parent.context.resources.getColor(R.color.black, null))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = currentFont ?: MainActivity.getInstance()?.getThemePrimaryFont()
        }
        textContainer.addView(subtitleView)

        return PanelViewHolder(textContainer, titleView, subtitleView)
    }

    override fun onBindViewHolder(holder: PanelViewHolder, position: Int) {
        val panel = panels[position]
        holder.titleView.text = panel.title
        holder.subtitleView.text = panel.subtitle

        holder.itemView.setOnClickListener {
            onPanelTap?.invoke(panel)
        }

        holder.itemView.setOnLongClickListener {
            onPanelLongPress?.invoke()
            true
        }
    }

    override fun getItemCount(): Int = panels.size

    fun updateThemeFont(font: android.graphics.Typeface?) {
        // Store the new font and force recreation of views
        Log.d("QuickGlancePanelAdapter", "updateThemeFont called, font: $font")
        currentFont = font
        notifyDataSetChanged()
        Log.d("QuickGlancePanelAdapter", "notifyDataSetChanged called, adapter will recreate views")
    }

    class PanelViewHolder(
        itemView: android.view.View,
        val titleView: TextView,
        val subtitleView: TextView
    ) : RecyclerView.ViewHolder(itemView)
}