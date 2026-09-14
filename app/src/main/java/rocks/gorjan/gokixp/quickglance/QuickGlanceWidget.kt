package rocks.gorjan.gokixp.quickglance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.getSafeFloat

class QuickGlanceWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Move mode, entered from the context menu's "Move": the whole widget becomes the drag handle
    private var isMoveMode = false
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false
    private val longPressRunnable = Runnable {
        Log.d("QuickGlanceWidget", "Long press detected - showing context menu")
        showQuickGlanceContextMenu()
    }

    // Movement threshold for distinguishing between tap, drag, and long press
    private val MOVEMENT_THRESHOLD = 10f

    // SharedPreferences keys for position and settings - use MainActivity.PREFS_NAME for consistency
    private val KEY_WIDGET_X = "widget_x"
    private val KEY_WIDGET_Y = "widget_y"
    private val KEY_SHOW_CALENDAR_EVENTS = "show_calendar_events"
    private val KEY_ALIGN_RIGHT = "quick_glance_align_right"

    // UI components
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
        orientation = VERTICAL

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

        // Once, here - not from updateDotsIndicator, which runs on every panel refresh: a
        // clock tick, a weather reading, a notification. Registering there added a callback
        // per refresh and removed none, so the list grew for as long as the launcher had
        // been up, every swipe ran all of them, and each one walked the dots setting
        // backgrounds. A heap dump after a day's use had 13,365 of these registered.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDotSelection(position)
            }
        })

        addView(viewPager)

        // Create dots indicator (initially hidden)
        dotsIndicator = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.START
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * context.resources.displayMetrics.density).toInt()
            }
            visibility = View.GONE // Hide by default
        }
        addView(dotsIndicator)

        // Set up initial panels
        initializePanels()

        // Apply persisted appearance settings (alignment)
        applyLayoutConfig()
    }

    /**
     * Applies the "Align right" setting to the live layout. Safe to call repeatedly.
     */
    private fun applyLayoutConfig() {
        val gravity = if (isAlignRightEnabled()) android.view.Gravity.END else android.view.Gravity.START
        panelAdapter.setTextGravity(gravity)
        dotsIndicator.gravity = gravity
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
    }

    private fun updateDotSelection(position: Int) {
        for (i in 0 until dotsIndicator.childCount) {
            val dot = dotsIndicator.getChildAt(i)
            dot.setBackgroundResource(if (i == position) R.drawable.dot_indicator else R.drawable.dot_indicator_inactive)
            dot.alpha = if (i == position) 1.0f else 0.5f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Sized here rather than in layout params: MainActivity adds the widget with WRAP_CONTENT,
        // which let the panels stretch it across the whole desktop. At rest it reaches from where
        // it sits to the desktop's right edge, so text isn't cut short; while moving it's half the
        // screen, leaving room to drag it sideways. Measured each pass, so it follows rotation.
        val halfWidth = resources.displayMetrics.widthPixels / 2
        val widgetWidth = if (isMoveMode) {
            halfWidth
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - x.toInt()).coerceAtLeast(halfWidth)
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(widgetWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    override fun setTranslationX(translationX: Float) {
        super.setTranslationX(translationX)
        // At rest the width depends on where the widget sits, so a new position needs a new measure
        if (!isMoveMode) requestLayout()
    }

    fun setMoveMode(enabled: Boolean) {
        if (isMoveMode == enabled) return
        isMoveMode = enabled
        removeCallbacks(longPressRunnable)
        hasMoved = false
        requestLayout()
        // Dotted selection outline so it's clear the next drag moves the widget
        foreground = if (enabled) context.getDrawable(R.drawable.quick_glance_move_outline) else null
        Log.d("QuickGlanceWidget", "Move mode: $enabled")
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // In move mode the panels must not see the touch - no swiping, no tap actions
        return isMoveMode || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isMoveMode) return handleMoveTouch(event)

        // Only touches the panels don't consume land here (the padding around them),
        // so all that's left to detect is a long press for the context menu
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                postDelayed(longPressRunnable, 500)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.rawX - initialTouchX) > MOVEMENT_THRESHOLD ||
                    Math.abs(event.rawY - initialTouchY) > MOVEMENT_THRESHOLD) {
                    removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleMoveTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = x
                initialY = y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                hasMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                if (!hasMoved && (Math.abs(deltaX) > MOVEMENT_THRESHOLD || Math.abs(deltaY) > MOVEMENT_THRESHOLD)) {
                    hasMoved = true
                }
                if (hasMoved) {
                    // Keep the widget on the desktop so it can't be dragged out of reach
                    val desktop = parent as View
                    x = (initialX + deltaX).coerceIn(0f, (desktop.width - width).coerceAtLeast(0).toFloat())
                    y = (initialY + deltaY).coerceIn(0f, (desktop.height - height).coerceAtLeast(0).toFloat())
                }
            }

            MotionEvent.ACTION_UP -> {
                // A drop saves the new spot; a tap without dragging just cancels the move
                if (hasMoved) {
                    Log.d("QuickGlanceWidget", "Drag completed, saving position")
                    savePosition()
                }
                setMoveMode(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                // Gesture was taken away mid-drag - put the widget back and let them try again
                x = initialX
                y = initialY
                hasMoved = false
            }
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
        val savedX = prefs.getSafeFloat(KEY_WIDGET_X, -1f)
        val savedY = prefs.getSafeFloat(KEY_WIDGET_Y, -1f)
        
        if (savedX >= 0 && savedY >= 0) {
            x = savedX
            y = savedY
            Log.d("QuickGlanceWidget", "Restored Quick Glance position: x=$savedX, y=$savedY")
        } else {
            Log.d("QuickGlanceWidget", "No saved position found, using default placement")
        }
    }
    
    // Public methods to update widget content
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
                // Also refresh the default panel to update weather/AQI data
                refreshDefaultPanel()
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

    // "Align right" setting management
    fun isAlignRightEnabled(): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ALIGN_RIGHT, false) // Default to false (left aligned)
    }

    fun setAlignRight(enabled: Boolean) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ALIGN_RIGHT, enabled).apply()
        applyLayoutConfig()
        Log.d("QuickGlanceWidget", "Align right setting changed to: $enabled")
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
        removeCallbacks(longPressRunnable)
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
    private var currentGravity: Int = android.view.Gravity.START

    fun setTextGravity(gravity: Int) {
        if (currentGravity == gravity) return
        currentGravity = gravity
        notifyDataSetChanged()
    }

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
        holder.titleView.gravity = currentGravity
        holder.subtitleView.gravity = currentGravity

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