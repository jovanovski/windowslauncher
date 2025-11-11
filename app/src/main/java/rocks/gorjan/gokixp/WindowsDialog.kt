package rocks.gorjan.gokixp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Data class to store window state for persistence
 */
data class WindowState(
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int,
    val isMaximized: Boolean
)

class WindowsDialog @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    initialTheme: AppTheme = AppTheme.WindowsXP  // Phase 3: Changed from Boolean to AppTheme
) : LinearLayout(context, attrs, defStyleAttr) {

    // Root overlay (full-screen) and the actual movable window frame inside it
    private lateinit var overlayRoot: FrameLayout
    private lateinit var windowFrame: LinearLayout

    // Title bar + content
    private lateinit var titleBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var closeButton: ImageView
    private lateinit var minimizeButton: ImageView
    private var maximizeButton: ImageView? = null
    private lateinit var contentArea: LinearLayout
    private var windowIcon: ImageView? = null
    private var windowBorder: FrameLayout? = null

    // Listeners
    private var onCloseListener: (() -> Unit)? = null
    private var onMinimizeListener: (() -> Unit)? = null
    private var onMaximizeListener: (() -> Unit)? = null

    // Theme
    private var currentTheme: AppTheme = initialTheme

    // Taskbar
    private var taskbarButton: View? = null
    private var taskbarContainer: LinearLayout? = null
    private var taskbarIconResId: Int = R.drawable.executable

    // Minimized state
    private var isMinimized = false

    // Borderless state
    private var isBorderless = false

    // Maximize state
    private var canMaximize = false
    private var isMaximized = false
    private var savedWidth = 0
    private var savedHeight = 0
    private var savedX = 0f
    private var savedY = 0f

    // State persistence control
    private var shouldSaveState = true

    // Keyboard handling for maximized windows
    private var keyboardHeight = 0
    private var isKeyboardVisible = false

    // Window identifier (for tracking unique instances like app package or folder path)
    var windowIdentifier: String? = null

    // Reference to InternetExplorerApp instance (if this window is IE)
    var internetExplorerApp: Any? = null

    // Dragging on the title bar
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Resizing from bottom-right corner
    private var resizeDragView: ImageView? = null
    private var isResizing = false
    private var initialWidth = 0
    private var initialHeight = 0
    private var resizeInitialTouchX = 0f
    private var resizeInitialTouchY = 0f

    // Minimum window dimensions in dp (can be set per-app via AppInfo)
    private var minWindowWidthDp: Int = 300
    private var minWindowHeightDp: Int = 250

    // Current window position (for shake animation)
    private var currentWindowX = 0f
    private var currentWindowY = 0f

    // Window manager reference for closing
    private var windowManager: FloatingWindowManager? = null

    // Context menu reference (from MainActivity)
    private var contextMenuView: ContextMenuView? = null

    // Gesture detector for double-tap to maximize
    private lateinit var gestureDetector: GestureDetector

    init {
        orientation = VERTICAL
        elevation = 0f // Don't set elevation - let container control z-order
        setBackgroundColor(Color.TRANSPARENT)

        // The root view (this) is not the draggable one anymore.
        clipChildren = false
        clipToPadding = false

        setupDialogLayout()
        setupKeyboardListener()
    }

    private fun setupDialogLayout() {
        removeAllViews()

        // Phase 3: Use ThemeManager to get the layout
        val mainActivity = context as? MainActivity
        val dialogLayoutResId = if (mainActivity != null) {
            mainActivity.themeManager.getDialogLayoutRes(currentTheme)
        } else {
            // Fallback if not in MainActivity context
            if (currentTheme is AppTheme.WindowsClassic) {
                R.layout.windows_dialog_content_98
            } else if (currentTheme is AppTheme.WindowsVista){
                R.layout.windows_dialog_content_vista
            }
            else {
                R.layout.windows_dialog_content_xp
            }
        }

        val dialogLayout = LayoutInflater.from(context).inflate(dialogLayoutResId, this, true)

        // Bind overlay and inner window frame
        overlayRoot = findViewById(R.id.dialog_overlay)
        windowFrame = findViewById(R.id.window_frame)

        // Core parts
        titleBar = findViewById(R.id.dialog_title_bar)
        titleText = findViewById(R.id.dialog_title_text)
        closeButton = findViewById(R.id.dialog_close_button)
        minimizeButton = findViewById(R.id.dialog_minimize_button)
        maximizeButton = findViewById(R.id.dialog_maximize_button)
        contentArea = findViewById(R.id.dialog_content_area)
        windowIcon = findViewById(R.id.dialog_window_icon)

        // Get border frame only for Windows XP theme
        if (currentTheme is AppTheme.WindowsXP || currentTheme is AppTheme.WindowsVista) {
            windowBorder = findViewById(R.id.window_border)


//            // Setup Windows Aero blur effect for Vista theme
//            if (currentTheme is AppTheme.WindowsVista) {
//                // Delay blur setup until views are laid out to avoid crashes
//                post {
//                    try {
//                        val blurView = findViewById<BlurView>(R.id.title_blur_view)
//                        val blurTarget = findViewById<BlurTarget>(R.id.blur_target)
//
//                        // Get the window's decor view for background drawable
//                        val decorView = (context as? Activity)?.window?.decorView
//                        val windowBackground = decorView?.background
//
//                        // Setup BlurView to blur content behind it
//                        blurView.setupWith(blurTarget)
//                            .setFrameClearDrawable(windowBackground) // Optional: makes background opaque
//                            .setBlurRadius(15f) // Adjust blur intensity (0-25 recommended)
//                    } catch (e: Exception) {
//                        Log.e("WindowsDialog", "Error setting up blur effect", e)
//                    }
//                }
//            }
        }

        // Set window frame width to 80% of screen, height wraps content
        val display = resources.displayMetrics
        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            width = (display.widthPixels * 0.8f).toInt()
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        }

        // Clicks
        closeButton.setOnClickListener {
            closeWindow()
        }

        minimizeButton.setOnClickListener {
            minimizeWindow()
        }

        maximizeButton?.setOnClickListener {
            // Only allow maximize/restore if enabled
            if (!canMaximize) return@setOnClickListener

            if (isMaximized) {
                restoreWindow()
            } else {
                maximizeWindow()
            }
        }

        // Hide maximize button by default (shown when setMaximizable(true) is called)
        maximizeButton?.visibility = View.GONE

        // DON'T set overlay as clickable - we'll handle this through onInterceptTouchEvent
        overlayRoot.isClickable = false
        overlayRoot.isFocusable = false

        // Hide window initially until it's centered
        windowFrame.visibility = View.INVISIBLE

        // Setup gesture detector for double-tap to maximize
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (canMaximize && !isMaximized) {
                    maximizeWindow()
                } else if (canMaximize && isMaximized) {
                    restoreWindow()
                }
                return true
            }
        })

        // Drag the window by its title bar within the overlayRoot
        titleBar.setOnTouchListener { v, event ->
            // Pass event to gesture detector for double-tap detection
            gestureDetector.onTouchEvent(event)

            // Don't allow dragging when maximized
            if (isMaximized) {
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = windowFrame.x
                    initialY = windowFrame.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // Calculate new position
                        var newX = initialX + dx
                        var newY = initialY + dy

                        // Clamp to overlay bounds
                        val maxX = overlayRoot.width - windowFrame.width
                        val maxY = overlayRoot.height - windowFrame.height

                        newX = newX.coerceIn(0f, maxX.toFloat())
                        newY = newY.coerceIn(0f, maxY.toFloat())

                        windowFrame.x = newX
                        windowFrame.y = newY
                        currentWindowX = newX
                        currentWindowY = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) v.performClick()
                    val wasDragging = isDragging
                    isDragging = false
                    // Save position after dragging
                    if (wasDragging) {
                        saveWindowState()
                    }
                    true
                }
                else -> false
            }
        }

    }

    fun setupFloatingWindow(windowManager: FloatingWindowManager) {
        // Store window manager reference
        this.windowManager = windowManager

        // Immediately make visible and restore saved state or center in the next frame
        post {
            // Request layout to ensure measurements are available
            windowFrame.requestLayout()

            // Restore saved state or center immediately if dimensions are available, otherwise wait
            if (windowFrame.width > 0 && windowFrame.height > 0 && overlayRoot.width > 0 && overlayRoot.height > 0) {
                // Try to restore saved state first, fallback to centering
                if (windowIdentifier != null) {
                    restoreWindowState()
                    // Only center if no saved state was found
                    if (windowFrame.x == 0f && windowFrame.y == 0f) {
                        centerWindowFrame()
                    }
                } else {
                    centerWindowFrame()
                }
                windowFrame.visibility = View.VISIBLE
            } else {
                // Only wait for layout if dimensions aren't ready yet
                windowFrame.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        windowFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        // Try to restore saved state first, fallback to centering
                        if (windowIdentifier != null) {
                            restoreWindowState()
                            // Only center if no saved state was found
                            if (windowFrame.x == 0f && windowFrame.y == 0f) {
                                centerWindowFrame()
                            }
                        } else {
                            centerWindowFrame()
                        }
                        windowFrame.visibility = View.VISIBLE
                    }
                })
            }
        }

        // Register with taskbar
        autoRegisterWithTaskbar()
    }

    private fun updateTouchableRegion() {
        // Get window frame position on screen
        val location = IntArray(2)
        windowFrame.getLocationOnScreen(location)

        // Create region covering only the window frame
        val region = android.graphics.Region()
        region.set(
            location[0],
            location[1],
            location[0] + windowFrame.width,
            location[1] + windowFrame.height
        )

        // Set as touchable region (requires API 29+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            overlayRoot.rootView.requestLayout()
        }
    }

    private fun centerWindowFrame() {
        val w = windowFrame.width
        val h = windowFrame.height
        val overlayW = overlayRoot.width
        val overlayH = overlayRoot.height

        // Center within overlay bounds
        var x = (overlayW - w) / 2f
        var y = (overlayH - h) / 2f

        // Only clamp if window is smaller than overlay (otherwise keep centered)
        if (w < overlayW) {
            x = x.coerceIn(0f, (overlayW - w).toFloat())
        }
        if (h < overlayH) {
            y = y.coerceIn(0f, (overlayH - h).toFloat())
        }

        windowFrame.x = x
        windowFrame.y = y
        currentWindowX = x
        currentWindowY = y
    }

    // ——— Public API ———

    fun setTitle(title: String) {
        titleText.text = title
        updateTaskbarButtonTitle(title)
    }

    fun setTaskbarIcon(iconResId: Int) {
        taskbarIconResId = iconResId
        taskbarButton?.findViewById<ImageView>(R.id.taskbar_button_icon)?.setImageResource(iconResId)
        windowIcon?.apply {
            setImageResource(iconResId)
            visibility = View.VISIBLE
        }
    }

    fun setContentView(view: View) {
        contentArea.removeAllViews()
        contentArea.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        // Setup resize functionality after content is added
        setupResizeDragView()
    }

    fun setWindowSize(widthDp: Int? = null, heightDp: Int? = null) {
        val density = resources.displayMetrics.density
        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            if (widthDp != null) {
                width = (widthDp * density).toInt()
            }
            if (heightDp != null) {
                height = (heightDp * density).toInt()
            }
        }

        // When setting explicit height, make content area expand to fill available space
        if (heightDp != null) {
            windowBorder?.updateLayoutParams<LayoutParams> {
                height = LayoutParams.MATCH_PARENT
            }
            contentArea.updateLayoutParams<FrameLayout.LayoutParams> {
                height = FrameLayout.LayoutParams.MATCH_PARENT
            }
        }
    }

    fun setWindowSizePercentage(widthPercent: Float? = null, heightPercent: Float? = null) {
        val display = resources.displayMetrics
        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            if (widthPercent != null) {
                width = (display.widthPixels * (widthPercent / 100f)).toInt()
            }
            if (heightPercent != null) {
                height = (display.heightPixels * (heightPercent / 100f)).toInt()
            }
        }

        // When setting explicit height, make content area expand to fill available space
        if (heightPercent != null) {
            windowBorder?.updateLayoutParams<LayoutParams> {
                height = LayoutParams.MATCH_PARENT
            }
            contentArea.updateLayoutParams<FrameLayout.LayoutParams> {
                height = FrameLayout.LayoutParams.MATCH_PARENT
            }
        }
    }

    fun setContentView(layoutResId: Int) {
        contentArea.removeAllViews()
        LayoutInflater.from(context).inflate(layoutResId, contentArea, true)
        // Setup resize functionality after content is added
        setupResizeDragView()
    }

    fun setOnCloseListener(listener: () -> Unit) { onCloseListener = listener }
    fun setOnMinimizeListener(listener: () -> Unit) { onMinimizeListener = listener }
    fun setOnMaximizeListener(listener: () -> Unit) { onMaximizeListener = listener }

    /**
     * Set minimum window dimensions for resizing (in dp)
     * Call this before setContentView() or call setupResizeDragView() after to apply changes
     */
    fun setMinimumWindowSize(widthDp: Int, heightDp: Int) {
        minWindowWidthDp = widthDp
        minWindowHeightDp = heightDp
    }

    /**
     * Set minimum window dimensions from AppInfo
     * Call this before setContentView() or call setupResizeDragView() after to apply changes
     */
    fun setMinimumWindowSize(appInfo: AppInfo) {
        minWindowWidthDp = appInfo.minWindowWidthDp
        minWindowHeightDp = appInfo.minWindowHeightDp
    }

    /**
     * Sets up the resize drag view functionality if it exists in the content
     */
    private fun setupResizeDragView() {
        // Find the resize drag view in the content area
        resizeDragView = contentArea.findViewById(R.id.resize_drag_view)

        // Calculate minimum size in pixels using configurable dp values
        val density = resources.displayMetrics.density
        val minSizeHeightPx = (minWindowHeightDp * density).toInt()
        val minSizeWidthPx = (minWindowWidthDp * density).toInt()

        setupResizeHandling(density, minSizeHeightPx, minSizeWidthPx)
    }

    /**
     * Sets up keyboard visibility listener to adjust maximized window height
     */
    private fun setupKeyboardListener() {
        // Use WindowInsets API to detect keyboard (API 30+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                if (!isMaximized) return@setOnApplyWindowInsetsListener insets

                val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())

                val wasKeyboardVisible = isKeyboardVisible
                isKeyboardVisible = imeVisible
                keyboardHeight = if (imeVisible) imeInsets.bottom else 0

                Log.d("WindowsDialog", "Keyboard state: visible=$imeVisible, height=$keyboardHeight")

                // Only adjust if keyboard state changed
                if (wasKeyboardVisible != isKeyboardVisible) {
                    adjustMaximizedWindowForKeyboard()
                }

                insets
            }
        } else {
            // Fallback for older Android versions
            viewTreeObserver.addOnGlobalLayoutListener {
                if (!isMaximized) return@addOnGlobalLayoutListener

                val rect = android.graphics.Rect()
                windowFrame.getWindowVisibleDisplayFrame(rect)
                val screenHeight = windowFrame.rootView.height
                val keypadHeight = screenHeight - rect.bottom

                val wasKeyboardVisible = isKeyboardVisible
                if (keypadHeight > screenHeight * 0.15) { // Keyboard is visible
                    isKeyboardVisible = true
                    keyboardHeight = keypadHeight
                } else {
                    isKeyboardVisible = false
                    keyboardHeight = 0
                }

                // Only adjust if keyboard state changed
                if (wasKeyboardVisible != isKeyboardVisible) {
                    adjustMaximizedWindowForKeyboard()
                }
            }
        }
    }

    /**
     * Adjusts maximized window bottom margin to accommodate keyboard
     */
    private fun adjustMaximizedWindowForKeyboard() {
        if (!isMaximized) return

        Log.d("WindowsDialog", "Adjusting window for keyboard: visible=$isKeyboardVisible, height=$keyboardHeight")

        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            if (isKeyboardVisible) {
                // The floating windows container has 70dp bottom margin for taskbar
                // We need to subtract that from keyboard height to get the actual adjustment needed
                val density = resources.displayMetrics.density
                val taskbarMarginPx = (70 * density).toInt()
                val adjustedMargin = (keyboardHeight - taskbarMarginPx).coerceAtLeast(0)

                bottomMargin = adjustedMargin
                Log.d("WindowsDialog", "Keyboard height=$keyboardHeight, taskbar margin=$taskbarMarginPx, adjusted margin=$adjustedMargin")
            } else {
                // Fill entire screen
                bottomMargin = 0
                Log.d("WindowsDialog", "Cleared windowFrame bottomMargin")
            }
        }

        // Also adjust content area
        windowBorder?.updateLayoutParams<LayoutParams> {
            height = LayoutParams.MATCH_PARENT
        }
        contentArea.updateLayoutParams<FrameLayout.LayoutParams> {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    private fun setupResizeHandling(density: Float, minSizeHeightPx: Int, minSizeWidthPx: Int) {
        resizeDragView?.setOnTouchListener { v, event ->
            // Don't allow resizing when maximized
            if (isMaximized) {
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = false
                    // Store initial dimensions
                    initialWidth = windowFrame.width
                    initialHeight = windowFrame.height
                    resizeInitialTouchX = event.rawX
                    resizeInitialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - resizeInitialTouchX
                    val dy = event.rawY - resizeInitialTouchY

                    if (!isResizing && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        isResizing = true
                    }
                    if (isResizing) {
                        val newWidth = (initialWidth + dx).toInt().coerceAtLeast(minSizeWidthPx)
                        val newHeight = (initialHeight + dy).toInt().coerceAtLeast(minSizeHeightPx)

                        // Ensure window doesn't exceed overlay bounds
                        val maxWidth = (overlayRoot.width - windowFrame.x).toInt()
                        val maxHeight = (overlayRoot.height - windowFrame.y).toInt()

                        val finalWidth = newWidth.coerceAtMost(maxWidth)
                        val finalHeight = newHeight.coerceAtMost(maxHeight)

                        // Update window frame size in real-time
                        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
                            width = finalWidth
                            height = finalHeight
                        }

                        // Make content area expand to fill available space
                        windowBorder?.updateLayoutParams<LayoutParams> {
                            height = LayoutParams.MATCH_PARENT
                        }
                        contentArea.updateLayoutParams<FrameLayout.LayoutParams> {
                            height = FrameLayout.LayoutParams.MATCH_PARENT
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isResizing) v.performClick()
                    val wasResizing = isResizing
                    isResizing = false
                    // Save size after resizing
                    if (wasResizing) {
                        saveWindowState()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Enable or disable maximize functionality via double-tap on title bar and maximize button
     * @param enabled True to enable maximize, false to disable (default: false)
     */
    fun setMaximizable(enabled: Boolean) {
        canMaximize = enabled
        // Show or hide maximize button based on enabled state
        maximizeButton?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Enable or disable saving/restoring window state (position and size).
     * Useful for windows that should have a fixed size per theme.
     */
    fun setSaveState(enabled: Boolean) {
        shouldSaveState = enabled
    }

    /**
     * Triggers the close listener (used when closing via back button)
     */
    fun triggerCloseListener() {
        onCloseListener?.invoke()
    }

    fun getContentArea(): LinearLayout = contentArea

    fun setThemeBackground(isWindows98: Boolean) {
        val newTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        if (this.currentTheme != newTheme) {
            currentTheme = newTheme
            val currentTitle = if (::titleText.isInitialized) titleText.text.toString() else ""
            setupDialogLayout()
            setTitle(currentTitle)
        }
    }

    /**
     * Public method to close the window - used by both bordered and borderless windows
     */
    fun closeWindow() {
        hideContextMenu()
        windowManager?.removeWindow(this)
        onCloseListener?.invoke()
    }

    /**
     * Move window by vertical offset (for keyboard adjustments)
     * @param offsetY Vertical offset in pixels (negative to move up, 0 to reset to original position)
     */
    fun moveWindowVertical(offsetY: Int) {
        if (!::windowFrame.isInitialized) return

        // Store original position if not yet stored
        if (offsetY != 0 && initialY == 0f) {
            initialY = windowFrame.y
        }

        // Calculate new Y position
        val newY = if (offsetY == 0) {
            // Reset to original position
            initialY
        } else {
            // Move by offset from original position
            initialY + offsetY
        }

        // Ensure window stays within bounds
        val overlayH = overlayRoot.height
        val maxY = overlayH - windowFrame.height
        val finalY = newY.coerceIn(0f, maxY.toFloat())
        windowFrame.y = finalY
        currentWindowY = finalY
    }

    /**
     * Shake the window (for nudge animation)
     */
    fun shakeWindow() {
        if (!::windowFrame.isInitialized) return

        // Use the tracked position instead of reading from windowFrame
        val startX = currentWindowX
        val startY = currentWindowY

        // Debug: Log the starting position
        android.util.Log.d("WindowsDialog", "Shake starting at x=$startX, y=$startY")

        val shakeDistance = 10f // pixels to shake
        val shakeDuration = 50L // duration of each shake step in ms
        val shakeCount = 10 // number of shakes in 1 second (1000ms / 100ms per cycle)

        val handler = Handler(Looper.getMainLooper())
        var currentShake = 0

        val shakeRunnable = object : Runnable {
            override fun run() {
                if (currentShake >= shakeCount * 2) {
                    // Return to the position where shake started
                    windowFrame.x = startX
                    windowFrame.y = startY
                    currentWindowX = startX
                    currentWindowY = startY
                    android.util.Log.d("WindowsDialog", "Shake ended, returned to x=$startX, y=$startY")
                    return
                }

                // Alternate between offsets to create shake effect, relative to start position
                when (currentShake % 4) {
                    0 -> {
                        windowFrame.x = startX + shakeDistance
                        windowFrame.y = startY
                    }
                    1 -> {
                        windowFrame.x = startX - shakeDistance
                        windowFrame.y = startY
                    }
                    2 -> {
                        windowFrame.x = startX
                        windowFrame.y = startY + shakeDistance
                    }
                    3 -> {
                        windowFrame.x = startX
                        windowFrame.y = startY - shakeDistance
                    }
                }

                currentShake++
                handler.postDelayed(this, shakeDuration)
            }
        }

        handler.post(shakeRunnable)
    }

    /**
     * Public method to minimize the window - used by both bordered and borderless windows
     */
    fun minimizeWindow() {
        minimize()
    }

    /**
     * Makes the window borderless by hiding the title bar and removing the window frame background.
     * The content will use a custom draggable view with the ID dialog_title_bar.
     * Automatically binds minimize and close buttons if they exist in the content with IDs:
     * - dialog_minimize_button
     * - dialog_close_button
     */
    fun setBorderless(customDragView: View? = null) {
        // Mark as borderless
        isBorderless = true

        // Hide the default title bar
        if (::titleBar.isInitialized) {
            titleBar.visibility = View.GONE
        }

        // Remove the window frame background to make it truly borderless
        if (::windowFrame.isInitialized) {
            windowFrame.background = null
            windowFrame.setBackgroundColor(Color.TRANSPARENT)
            windowFrame.setPadding(0, 0, 0, 0)
        }

        // Remove title bar background
        if (::titleBar.isInitialized) {
            titleBar.background = null
            titleBar.setBackgroundColor(Color.TRANSPARENT)
        }

        // Remove window border background and padding (this has the drawable background)
        // Try to find it directly by ID in case windowBorder wasn't initialized
        val windowBorder = findViewById<FrameLayout>(R.id.window_border)
        windowBorder?.let {
            it.background = null
            it.setBackgroundColor(Color.TRANSPARENT)
            @Suppress("DEPRECATION")
            it.setBackgroundDrawable(null)
            it.setPadding(0, 0, 0, 0)
        }


        // Remove padding and background from content area (this has #f0f0f0 background)
        if (::contentArea.isInitialized) {
            contentArea.setPadding(0, 0, 0, 0)
            contentArea.background = null
            contentArea.setBackgroundColor(Color.TRANSPARENT)
            @Suppress("DEPRECATION")
            contentArea.setBackgroundDrawable(null)
        }

        // Remove overlay background
        if (::overlayRoot.isInitialized) {
            overlayRoot.background = null
            overlayRoot.setBackgroundColor(Color.TRANSPARENT)
        }

        // Find and bind minimize/close buttons in the content area (if they exist)
        post {
            // Try to find minimize button in content
            val contentMinimizeButton = contentArea.findViewById<ImageView>(R.id.dialog_minimize_button)
            contentMinimizeButton?.setOnClickListener {
                minimizeWindow()
            }

            // Try to find close button in content
            val contentCloseButton = contentArea.findViewById<ImageView>(R.id.dialog_close_button)
            contentCloseButton?.setOnClickListener {
                closeWindow()
            }
        }

        // If a custom drag view is provided, set up dragging on it
        customDragView?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = windowFrame.x
                    initialY = windowFrame.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // Calculate new position
                        var newX = initialX + dx
                        var newY = initialY + dy

                        // Clamp to overlay bounds
                        val maxX = overlayRoot.width - windowFrame.width
                        val maxY = overlayRoot.height - windowFrame.height

                        newX = newX.coerceIn(0f, maxX.toFloat())
                        newY = newY.coerceIn(0f, maxY.toFloat())

                        windowFrame.x = newX
                        windowFrame.y = newY
                        currentWindowX = newX
                        currentWindowY = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) v.performClick()
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }


    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // If context menu is visible, intercept ALL touches to prevent passthrough
                if (contextMenuView != null && contextMenuView?.visibility == View.VISIBLE) {
                    // Check if touch is within window frame
                    val x = event.x
                    val y = event.y
                    val frameLeft = windowFrame.x
                    val frameTop = windowFrame.y
                    val frameRight = frameLeft + windowFrame.width
                    val frameBottom = frameTop + windowFrame.height

                    val isInsideFrame = x >= frameLeft && x <= frameRight && y >= frameTop && y <= frameBottom

                    if (isInsideFrame) {
                        // Touch is inside frame - hide context menu and let it propagate to children
                        hideContextMenu()
                        return false // Don't intercept, let children handle it
                    } else {
                        // Touch is OUTSIDE frame - consume it to prevent passthrough
                        hideContextMenu()
                        return true // Intercept and handle in onTouchEvent
                    }
                }

                // Normal case: no context menu visible
                // Check if touch is within window frame
                val x = event.x
                val y = event.y
                val frameLeft = windowFrame.x
                val frameTop = windowFrame.y
                val frameRight = frameLeft + windowFrame.width
                val frameBottom = frameTop + windowFrame.height

                val isInsideFrame = x >= frameLeft && x <= frameRight && y >= frameTop && y <= frameBottom

                if (isInsideFrame) {
                    // Bring this window to front
                    windowManager?.bringToFront(this)
                }
            }
        }
        // Don't intercept - let touches pass through to children or below
        return false
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // If context menu was visible, consume the touch event to prevent passthrough
        if (event?.actionMasked == MotionEvent.ACTION_DOWN &&
            contextMenuView != null && contextMenuView?.visibility == View.VISIBLE) {
            return true // Consume the event
        }
        // Don't consume touches - let them pass through
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return super.dispatchTouchEvent(ev)
    }

    // ——— Taskbar ———

    private fun autoRegisterWithTaskbar() {
        try {
            val activity = resolveActivity(context)
            val taskbarContainerId = R.id.taskbar_empty_space
            val container = activity?.findViewById<LinearLayout>(taskbarContainerId)
            if (container != null) {
                registerWithTaskbar(container)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun autoRegisterWithTaskbar(dialog: AlertDialog) {
        try {
            val activity = resolveActivity(context)
            val taskbarContainerId = R.id.taskbar_empty_space
            val container = activity?.findViewById<LinearLayout>(taskbarContainerId)
            if (container != null) {
                registerWithTaskbar(container, dialog)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun resolveActivity(ctx: Context): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun registerWithTaskbar(taskbarContainerView: LinearLayout) {
        taskbarContainer = taskbarContainerView
        val buttonLayoutResId = ThemeManager(context).getTaskbarButtonLayoutRes(currentTheme)
        taskbarButton = LayoutInflater.from(context).inflate(buttonLayoutResId, taskbarContainer, false)
        taskbarButton?.findViewById<ImageView>(R.id.taskbar_button_icon)?.setImageResource(taskbarIconResId)
        taskbarButton?.findViewById<TextView>(R.id.taskbar_button_text)?.text = titleText.text

        // Regular click - minimize/restore/focus
        taskbarButton?.setOnClickListener {
            // Toggle behavior based on window state
            when {
                isMinimized -> restore() // If minimized, restore
                isInFocus() -> minimize() // If in focus, minimize
                else -> windowManager?.bringToFront(this) // Otherwise, bring to front
            }
        }

        // Long press - show context menu
        taskbarButton?.setOnLongClickListener { view ->
            // Perform haptic feedback
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // Get button position on screen
            val location = IntArray(2)
            view.getLocationOnScreen(location)

            // Show context menu at button position
            showTaskbarContextMenu(location[0].toFloat(), location[1].toFloat())
            true
        }

        taskbarContainer?.addView(taskbarButton)
    }

    fun registerWithTaskbar(taskbarContainerView: LinearLayout, dialogRef: AlertDialog) {
        taskbarContainer = taskbarContainerView
        val buttonLayoutResId = ThemeManager(context).getTaskbarButtonLayoutRes(currentTheme)
        taskbarButton = LayoutInflater.from(context).inflate(buttonLayoutResId, taskbarContainer, false)
        taskbarButton?.findViewById<ImageView>(R.id.taskbar_button_icon)?.setImageResource(taskbarIconResId)
        taskbarButton?.findViewById<TextView>(R.id.taskbar_button_text)?.text = titleText.text

        // Regular click - minimize/restore/focus
        taskbarButton?.setOnClickListener {
            // Toggle behavior based on window state
            when {
                isMinimized -> restore() // If minimized, restore
                isInFocus() -> minimize() // If in focus, minimize
                else -> {
                    // Bring to front: ensure windowFrame is last in z-order
                    windowFrame.bringToFront()
                    overlayRoot.invalidate()
                }
            }
        }

        // Long press - show context menu
        taskbarButton?.setOnLongClickListener { view ->
            // Perform haptic feedback
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // Get button position on screen
            val location = IntArray(2)
            view.getLocationOnScreen(location)

            // Show context menu at button position
            showTaskbarContextMenu(location[0].toFloat(), location[1].toFloat())
            true
        }

        taskbarContainer?.addView(taskbarButton)
    }

    fun unregisterFromTaskbar() {
        taskbarButton?.let { taskbarContainer?.removeView(it) }
        taskbarButton = null
        taskbarContainer = null
    }

    fun updateTaskbarButtonTitle(title: String) {
        taskbarButton?.findViewById<TextView>(R.id.taskbar_button_text)?.text = title
    }

    fun setTaskbarButtonIcon(iconResId: Int) {
        taskbarButton?.findViewById<ImageView>(R.id.taskbar_button_icon)?.setImageResource(iconResId)
    }

    fun setContextMenuView(contextMenu: ContextMenuView) {
        this.contextMenuView = contextMenu
    }

    fun hideContextMenu() {
        contextMenuView?.hideMenu()
    }

    fun showContextMenu(menuItems: List<ContextMenuItem>, x: Float, y: Float) {
        contextMenuView?.let { menu ->
            // Calculate absolute screen position
            val overlayLocation = IntArray(2)
            overlayRoot.getLocationOnScreen(overlayLocation)

            val screenX = overlayLocation[0] + x
            val screenY = overlayLocation[1] + y

            // Show menu at screen coordinates
            menu.showMenu(menuItems, screenX, screenY)
        }
    }

    /**
     * Shows the taskbar button context menu with Minimize/Restore and Close options
     */
    private fun showTaskbarContextMenu(x: Float, y: Float) {
        contextMenuView?.let { menu ->
            // Create menu items based on window state
            val menuItems = ContextMenuItems.getTaskbarMenuItems(
                isMinimized = isMinimized,
                onMinimizeRestore = {
                    if (isMinimized) {
                        restore()
                    } else {
                        minimize()
                    }
                },
                onClose = {
                    windowManager?.removeWindow(this)
                    onCloseListener?.invoke()
                }
            )

            // Show menu at taskbar button position
            menu.showMenu(menuItems, x, y)
        }
    }

    // ——— Minimize/Restore ———

    /**
     * Minimizes the window by hiding it while keeping it registered in the taskbar
     */
    fun minimize() {
        if (!isMinimized) {
            isMinimized = true
            windowFrame.visibility = View.GONE
            onMinimizeListener?.invoke()
        }
    }

    /**
     * Restores the window from minimized state and brings it to front
     */
    fun restore() {
        if (isMinimized) {
            isMinimized = false
            windowFrame.visibility = View.VISIBLE
            windowManager?.bringToFront(this)
        }
    }

    // ——— Maximize/Restore ———

    /**
     * Maximizes the window to fill the entire floating windows container
     */
    private fun maximizeWindow() {
        if (!canMaximize || isMaximized) return

        // Save current dimensions and position
        savedWidth = windowFrame.width
        savedHeight = windowFrame.height
        savedX = windowFrame.x
        savedY = windowFrame.y

        // Fill the overlay container completely
        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            bottomMargin = 0 // Reset margin
        }

        // Make content area expand to fill
        windowBorder?.updateLayoutParams<LayoutParams> {
            height = LayoutParams.MATCH_PARENT
        }
        contentArea.updateLayoutParams<FrameLayout.LayoutParams> {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }

        // Position at top-left corner of container
        windowFrame.x = 0f
        windowFrame.y = 0f
        currentWindowX = 0f
        currentWindowY = 0f

        isMaximized = true

        // Update maximize button icon to show restore icon (if it exists)
        updateMaximizeButtonIcon()

        onMaximizeListener?.invoke()

        // Save the maximized state
        saveWindowState()
    }

    /**
     * Restores the window to its original size and position before maximizing
     */
    private fun restoreWindow() {
        if (!isMaximized) return

        // Reset keyboard state
        isKeyboardVisible = false
        keyboardHeight = 0

        // Restore original dimensions
        windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
            width = savedWidth
            height = savedHeight
            bottomMargin = 0 // Clear keyboard margin
        }

        // Restore original position
        windowFrame.x = savedX
        windowFrame.y = savedY
        currentWindowX = savedX
        currentWindowY = savedY

        isMaximized = false

        // Update maximize button icon to show maximize icon
        updateMaximizeButtonIcon()

        // Center the window after a brief delay to allow layout to update
        post {
            centerWindowFrame()
            // Save the restored state after centering is complete
            saveWindowState()
        }
    }

    /**
     * Updates the maximize button icon based on current state
     */
    private fun updateMaximizeButtonIcon() {
        maximizeButton?.let { button ->
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                val iconRes = if (isMaximized) {
                    mainActivity.themeManager.getRestoreIcon()
                } else {
                    mainActivity.themeManager.getMaximizeIcon()
                }
                button.setImageResource(iconRes)
            }
        }
    }

    /**
     * Checks if this window is currently in focus (front-most window)
     */
    fun isInFocus(): Boolean {
        return windowManager?.getFrontWindow() == this
    }

    /**
     * Checks if this window is currently minimized
     */
    fun isMinimized(): Boolean {
        return isMinimized
    }

    // ——— Focus State ———

    /**
     * Sets the window as focused (active) with active title bar background
     */
    fun setFocused() {
        // Skip if borderless
        if (isBorderless) return

        if (::titleBar.isInitialized) {
            val activeBackground = if (currentTheme is AppTheme.WindowsClassic) {
                R.drawable.windows_98_dialog_title_bar
            }  else if (currentTheme is AppTheme.WindowsVista) {
                R.drawable.windows_vista_title_bar_rounded
            } else {
                R.drawable.windows_dialog_title_bar
            }
            titleBar.setBackgroundResource(activeBackground)

            // Update border for Windows XP theme
            if (currentTheme is AppTheme.WindowsXP) {
                windowBorder?.setBackgroundResource(R.drawable.windows_xp_dialog_border)
            }
        }
    }

    /**
     * Sets the window as unfocused (inactive) with inactive title bar background
     */
    fun setUnfocused() {
        // Skip if borderless
        if (isBorderless) return

        if (::titleBar.isInitialized) {
            if (currentTheme is AppTheme.WindowsClassic || currentTheme is AppTheme.WindowsXP) {
                val inactiveBackground = if (currentTheme is AppTheme.WindowsClassic) {
                    R.drawable.windows_98_dialog_title_bar_inactive
                } else {
                    R.drawable.windows_dialog_title_bar_inactive
                }
                titleBar.setBackgroundResource(inactiveBackground)
            }

            // Update border for Windows XP theme
            if (currentTheme is AppTheme.WindowsXP) {
                windowBorder?.setBackgroundResource(R.drawable.windows_xp_dialog_border_inactive)
            }
        }
    }

    /**
     * Save the current window state to SharedPreferences
     * Only saves position and size when window is not maximized
     */
    private fun saveWindowState() {
        // Skip saving if state saving is disabled
        if (!shouldSaveState) return

        val identifier = windowIdentifier ?: return

        try {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val gson = com.google.gson.Gson()

            // Load existing window states
            val existingStatesJson = prefs.getString("window_states", "{}")
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, WindowState>>() {}.type
            val windowStates: MutableMap<String, WindowState> = gson.fromJson(existingStatesJson, type) ?: mutableMapOf()

            // Create current state
            val currentState = WindowState(
                x = if (isMaximized) savedX else windowFrame.x,
                y = if (isMaximized) savedY else windowFrame.y,
                width = if (isMaximized) savedWidth else windowFrame.width,
                height = if (isMaximized) savedHeight else windowFrame.height,
                isMaximized = isMaximized
            )

            // Update state for this window
            windowStates[identifier] = currentState

            // Save back to preferences
            val updatedJson = gson.toJson(windowStates)
            prefs.edit().putString("window_states", updatedJson).apply()

            Log.d("WindowsDialog", "Saved state for '$identifier': x=${currentState.x}, y=${currentState.y}, " +
                    "width=${currentState.width}, height=${currentState.height}, maximized=${currentState.isMaximized}")
        } catch (e: Exception) {
            Log.e("WindowsDialog", "Error saving window state", e)
        }
    }

    /**
     * Restore the saved window state from SharedPreferences
     * Called during window setup
     */
    private fun restoreWindowState() {
        // Skip restoration if state saving is disabled
        if (!shouldSaveState) return

        val identifier = windowIdentifier ?: return

        try {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val gson = com.google.gson.Gson()

            // Load existing window states
            val existingStatesJson = prefs.getString("window_states", "{}")
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, WindowState>>() {}.type
            val windowStates: MutableMap<String, WindowState> = gson.fromJson(existingStatesJson, type) ?: mutableMapOf()

            // Get state for this window
            val savedState = windowStates[identifier] ?: return

            Log.d("WindowsDialog", "Restoring state for '$identifier': x=${savedState.x}, y=${savedState.y}, " +
                    "width=${savedState.width}, height=${savedState.height}, maximized=${savedState.isMaximized}")

            // Wait for layout to be ready, then apply the state
            windowFrame.post {
                // First apply size and position (non-maximized state)
                if (savedState.width > 0 && savedState.height > 0) {
                    windowFrame.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = savedState.width
                        height = savedState.height
                    }
                }

                // Clamp position to valid bounds
                val maxX = (overlayRoot.width - savedState.width).coerceAtLeast(0)
                val maxY = (overlayRoot.height - savedState.height).coerceAtLeast(0)
                val clampedX = savedState.x.coerceIn(0f, maxX.toFloat())
                val clampedY = savedState.y.coerceIn(0f, maxY.toFloat())

                windowFrame.x = clampedX
                windowFrame.y = clampedY
                currentWindowX = clampedX
                currentWindowY = clampedY

                // Save the restored position as the new saved position for maximize/restore
                savedX = clampedX
                savedY = clampedY
                savedWidth = savedState.width
                savedHeight = savedState.height

                // If it was maximized, maximize it now
                if (savedState.isMaximized && canMaximize) {
                    maximizeWindow()
                }
            }
        } catch (e: Exception) {
            Log.e("WindowsDialog", "Error restoring window state", e)
        }
    }
}
