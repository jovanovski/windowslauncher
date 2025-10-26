package rocks.gorjan.gokixp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import rocks.gorjan.gokixp.theme.AppTheme

open class DesktopIconView : LinearLayout {

    private val iconImage: ImageView
    private val iconText: TextView
    private val notificationDot: View
    private val shortcutIcon: ImageView
    private var desktopIcon: DesktopIcon? = null
    private var isDragging = false
    private var isGesturing = false
    private var downTime = 0L
    private var downEvent: MotionEvent? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var wasOverRecycleBin = false
    private var wasOverFolder = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressed = false
    private var isMoveMode = false
    private var isSelected = false
    private var customLongClickHandler: ((Float, Float) -> Unit)? = null
    private var hadSignificantMovement = false

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        LayoutInflater.from(context).inflate(R.layout.desktop_icon, this, true)
        iconImage = findViewById(R.id.icon_image)
        iconText = findViewById(R.id.icon_text)
        notificationDot = findViewById(R.id.notification_dot)
        shortcutIcon = findViewById(R.id.shortcut_icon)
    }

    constructor(
        context: Context,
        layoutResId: Int
    ) : super(context) {
        LayoutInflater.from(context).inflate(layoutResId, this, true)
        iconImage = findViewById(R.id.icon_image)
        iconText = findViewById(R.id.icon_text)
        notificationDot = findViewById(R.id.notification_dot)
        shortcutIcon = findViewById(R.id.shortcut_icon)
    }

    init {
        
        // Set click listener for launching apps
        setOnClickListener {
            desktopIcon?.let { icon ->
                try {
                    // Check if this is a system app
                    if (MainActivity.isSystemApp(icon.packageName)) {
                        // Launch system app
                        val mainActivity = context as? MainActivity
                        mainActivity?.launchSystemApp(icon.packageName)
                    } else {
                        // Launch regular app
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(icon.packageName)
                        launchIntent?.let { intent ->
                            context.startActivity(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DesktopIconView", "Error launching app: ${icon.packageName}", e)
                }
            }
        }
    }

    fun setDesktopIcon(icon: DesktopIcon) {
        desktopIcon = icon
        
        // Force clear any cached drawable and set new one
        iconImage.setImageDrawable(null)
        
        // Ensure the drawable has proper bounds set
        val drawable = icon.icon
        if (drawable.bounds.isEmpty) {
            // Set bounds to a reasonable size (will be scaled by ImageView)
            drawable.setBounds(0, 0, 288, 288)
        }
        
//        // For AdaptiveIconDrawable, also ensure nested drawables have proper bounds
//        if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
//            drawable.foreground?.let { foreground ->
//                if (foreground.bounds.isEmpty) {
//                    foreground.setBounds(0, 0, 288, 288)
//                }
//
//                // If foreground is LayerDrawable, ensure each layer has bounds
//                if (foreground is android.graphics.drawable.LayerDrawable) {
//                    for (i in 0 until foreground.numberOfLayers) {
//                        val layer = foreground.getDrawable(i)
//                        if (layer != null && layer.bounds.isEmpty) {
//                            layer.setBounds(0, 0, 288, 288)
//                        }
//                    }
//                }
//            }
//
//            drawable.background?.let { background ->
//                if (background.bounds.isEmpty) {
//                    background.setBounds(0, 0, 288, 288)
//                }
//            }
//        }
        
        iconImage.setImageDrawable(drawable)

        // Scale the icon by 1.1x if Vista theme
        val mainActivity = context as? MainActivity
//        val isVista = mainActivity?.themeManager?.getSelectedTheme() is AppTheme.WindowsVista
//        if (isVista) {
//            iconImage.scaleX = 1.1f
//            iconImage.scaleY = 1.1f
//        } else {
            iconImage.scaleX = 1.0f
            iconImage.scaleY = 1.0f
//        }

        // Force invalidate and request layout to ensure visual update
        iconImage.invalidate()
        iconImage.requestLayout()
        
        // Use custom name if available, otherwise use original name
        val displayName = if (icon.packageName != "recycle.bin") {
            mainActivity?.getCustomOrOriginalName(icon.packageName, icon.name) ?: icon.name
        } else {
            icon.name
        }
        // Replace "\n" with actual newline character
        iconText.text = displayName.replace("\\n", "\n")

        // Set initial shortcut arrow visibility based on preferences
        val isShortcutArrowVisible = mainActivity?.isShortcutArrowVisible() ?: true
        updateShortcutArrowVisibility(isShortcutArrowVisible)

        // Position will be set by the caller after adding to container
    }
    
    fun getDesktopIcon(): DesktopIcon? = desktopIcon
    
    fun setIconDrawable(drawable: android.graphics.drawable.Drawable) {
        iconImage.setImageDrawable(drawable)
    }
    
    fun setIconText(text: String) {
        // Replace "\n" with actual newline character
        iconText.text = text.replace("\\n", "\n")
    }

    fun setTextColor(color: Int) {
        iconText.setTextColor(color)
    }

    fun removeTextShadow() {
        iconText.setShadowLayer(0F,0F,0F,0)
    }

    fun setThemeFont(isWindows98: Boolean) {
        MainActivity.getInstance()?.applyThemeFontToTextView(iconText)
        val currentTheme = MainActivity.getInstance()?.themeManager?.getSelectedTheme() ?: AppTheme.WindowsXP


        // Increase line height by 1dp for both themes
        val lineSpacingExtra = (1 * resources.displayMetrics.density).toInt()
        if(currentTheme == AppTheme.WindowsClassic) {
            iconText.setLineSpacing(lineSpacingExtra.toFloat(), 1.0f)
        }
        else if(currentTheme == AppTheme.WindowsVista){
            iconText.setLineSpacing(lineSpacingExtra.toFloat(), 0.8f)
            iconText.textScaleX = 1.025f
        }
        else{
            iconText.setLineSpacing(lineSpacingExtra.toFloat(), 0.85f)
        }

    }

    fun updateNotificationDot(hasNotification: Boolean) {
        notificationDot.visibility = if (hasNotification) View.VISIBLE else View.GONE
    }

    fun updateShortcutArrowVisibility(isVisible: Boolean) {
        // Get current theme from MainActivity
        val mainActivity = context as? MainActivity
        val currentTheme = mainActivity?.themeManager?.getSelectedTheme() ?: AppTheme.WindowsXP

        // Set the appropriate shortcut overlay based on theme
        val overlayDrawable = if (currentTheme is AppTheme.WindowsVista) {
            R.drawable.overlay_shortcut_vista
        } else {
            R.drawable.overlay_shortcut
        }
        shortcutIcon.setImageResource(overlayDrawable)

        shortcutIcon.visibility = if (isVisible) View.VISIBLE else View.GONE
    }



    fun setIconMargin(left: Int, top: Int, right: Int, bottom: Int) {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(left, top, right, bottom)
        layoutParams = params
    }

    fun setIconPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }
    
    fun setMoveMode(enabled: Boolean) {
        isMoveMode = enabled

        // Log for folders
        desktopIcon?.let { icon ->
            if (icon.type == IconType.FOLDER) {
                Log.d("DesktopIconView", "MOVE_MODE: Folder ${icon.name} move mode = $enabled")
            }
        }

        // Reset touch state when entering move mode to ensure clean state
        if (enabled) {
            resetTouchState()
        }
    }

    fun setCustomLongClickHandler(handler: ((Float, Float) -> Unit)?) {
        customLongClickHandler = handler
    }
    
    private fun resetTouchState() {
        // Cancel any pending long press
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

        // Reset all touch-related flags
        isDragging = false
        isLongPressed = false
        isGesturing = false
        hadSignificantMovement = false
        wasOverRecycleBin = false
        wasOverFolder = false

        // Reset initial position to current position (for move mode)
        initialX = x
        initialY = y

        // Clean up any stored down event
        downEvent?.recycle()
        downEvent = null
    }
    
    private fun vibrateShort() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                initialX = x
                initialY = y
                isDragging = false
                isLongPressed = false
                isGesturing = false
                hadSignificantMovement = false
                downTime = System.currentTimeMillis()
                downEvent = MotionEvent.obtain(event)

                // Log for folders
                desktopIcon?.let { icon ->
                    if (icon.type == IconType.FOLDER) {
                        Log.d("DesktopIconView", "ACTION_DOWN: Folder ${icon.name}, isMoveMode=$isMoveMode")
                    }
                }

                // If already in move mode, skip long press and prepare for immediate dragging
                if (isMoveMode) {
                    // Don't set up long press detection - user can immediately start dragging
                    isLongPressed = true // Act as if long press already happened
                } else {
                    // Set up long press detection for context menu
                    longPressRunnable = Runnable {
                        vibrateShort() // Vibrate on long press
                        isLongPressed = true

                        // Show context menu on long press (only if not in move mode)
                        if (!isMoveMode) {
                            showIconContextMenu(event.rawX, event.rawY)
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 250) // 250ms for long press
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Ignore MOVE events if we didn't receive a DOWN event first
                // This prevents gesture navigation from triggering long press
                if (downEvent == null) {
                    return false
                }

                val deltaX = event.rawX - lastTouchX
                val deltaY = event.rawY - lastTouchY

                // Track if there was significant movement (likely a gesture)
                // Even small movements (15px+) could be the start of a system gesture
                if (!hadSignificantMovement && (Math.abs(deltaX) > 15 || Math.abs(deltaY) > 15)) {
                    hadSignificantMovement = true
                    // Cancel any pending long press - this is likely a gesture
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }

                // Cancel long press only on significant movement (unless in move mode or already long pressed)
                // Allow small movements (up to 30px) to still register as long press
                if (!isMoveMode && !isLongPressed && (Math.abs(deltaX) > 30 || Math.abs(deltaY) > 30)) {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    isLongPressed = false
                }

                // Detect if this might be a gesture (significant movement)
                if (!isGesturing && (Math.abs(deltaX) > 80 || Math.abs(deltaY) > 80)) {
                    isGesturing = true
                    return true // We're tracking this as a potential gesture
                }

                // Start dragging if moved enough in move mode
                if (!isDragging && isMoveMode && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    isDragging = true
                    wasOverRecycleBin = false

                    // Log for folders
                    desktopIcon?.let { icon ->
                        if (icon.type == IconType.FOLDER) {
                            Log.d("DesktopIconView", "DRAG_START: Folder ${icon.name} started dragging")
                        }
                    }
                }
                
                // Continue with regular position updates
                if (isDragging && isMoveMode) {
                    x = initialX + deltaX
                    y = initialY + deltaY

                    // Update the desktop icon position
                    desktopIcon?.let { icon ->
                        icon.x = x
                        icon.y = y

                        // Log position updates for folders
                        if (icon.type == IconType.FOLDER) {
                            Log.d("DesktopIconView", "DRAG: Updating folder ${icon.name} position to x=${icon.x}, y=${icon.y}")
                        }
                    }

                    if(this !is RecycleBinView && this !is FolderView) {
                        val mainActivity = context as? MainActivity

                        // Check if over folder first (higher priority than recycle bin)
                        val folderUnder = mainActivity?.isOverFolder(x + width / 2, y + height / 2)

                        if (folderUnder != null && !wasOverFolder) {
                            vibrateShort() // Vibrate when entering folder area
                            wasOverFolder = true
                            wasOverRecycleBin = false
                        } else if (folderUnder == null && wasOverFolder) {
                            wasOverFolder = false
                        }

                        // Check if over recycle bin (only if not over folder)
                        if (folderUnder == null) {
                            val isOverRecycleBin = mainActivity?.isOverRecycleBin(x + width / 2, y + height / 2) == true

                            if (isOverRecycleBin && !wasOverRecycleBin) {
                                vibrateShort() // Vibrate when entering recycle bin area
                                wasOverRecycleBin = true
                            } else if (!isOverRecycleBin && wasOverRecycleBin) {
                                wasOverRecycleBin = false
                            }
                        }
                    }
                }
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                // Ignore UP events if we didn't receive a DOWN event first
                // This prevents gesture navigation from triggering actions
                if (downEvent == null) {
                    return false
                }

                // Cancel any pending long press
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                // If this was a gesture, cancel long press state to prevent context menu
                if (isGesturing) {
                    isLongPressed = false
                }

                if (isDragging && isMoveMode) {
                    isDragging = false

                    val mainActivity = context as? MainActivity

                    // Check if dropped on folder first (only for regular icons, not folders or recycle bin)
                    if (this !is RecycleBinView && this !is FolderView) {
                        val folderUnder = mainActivity?.isOverFolder(x + width/2, y + height/2)
                        if (folderUnder != null) {
                            // Add icon to folder
                            mainActivity.addIconToFolder(this, folderUnder)
                            mainActivity.exitIconMoveMode()
                            downEvent?.recycle()
                            downEvent = null
                            return true
                        }
                    }

                    // Check if dropped on recycle bin using coordinates
                    if (mainActivity?.isOverRecycleBin(x + width/2, y + height/2) == true && this !is RecycleBinView) {
                        // Delete the icon (but not if this is the recycle bin itself)
                        mainActivity.deleteDesktopIcon(this)
                    } else {
                        // Check if snap to grid is enabled and snap if needed
                        if (mainActivity?.isSnapToGridEnabled() == true) {
                            mainActivity.snapSingleIconToGrid(this)
                        }

                        // Log position before saving for folders
                        desktopIcon?.let { icon ->
                            if (icon.type == IconType.FOLDER) {
                                Log.d("DesktopIconView", "SAVE: About to save folder ${icon.name} with position x=${icon.x}, y=${icon.y}")
                                Log.d("DesktopIconView", "SAVE: View position is x=${x}, y=${y}")
                            }
                        }

                        // Save the new position
                        mainActivity?.saveDesktopIconPosition(desktopIcon)
                    }

                    // Exit move mode
                    mainActivity?.exitIconMoveMode()

                    // Clean up the stored down event
                    downEvent?.recycle()
                    downEvent = null
                    return true
                }
                
                // If this was a gesture, detect direction and execute appropriate action
                if (isGesturing) {
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY
                    val mainActivity = context as? MainActivity
                    
                    // Determine gesture direction and execute action
                    val isSwipeDown = deltaY > 0 && Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > 80
                    val isSwipeUp = deltaY < 0 && Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > 80
                    val isSwipeRight = deltaX > 0 && Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 80
                    
                    when {
                        isSwipeDown -> {
                            // Swipe down - check if start menu is open first
                            if (mainActivity?.isStartMenuVisible == true) {
                                mainActivity.hideStartMenu()
                            } else {
                                mainActivity?.expandNotificationShade()
                            }
                        }
                        isSwipeUp -> {
                            // Swipe up - open start menu with search
                            mainActivity?.showStartMenuWithSearch()
                        }
                        isSwipeRight -> {
                            // Swipe right - launch swipe right app (configured in settings)
                            mainActivity?.launchSwipeRightApp()
                        }
                    }
                    
                    // Clean up
                    downEvent?.recycle()
                    downEvent = null
                    return true
                }
                
                // If not dragging and not long pressed, treat as click
                if (!isLongPressed) {
                    performClick()
                }
                
                // Clean up the stored down event
                downEvent?.recycle()
                downEvent = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // System cancelled the touch (e.g., gesture navigation took over)
                // Cancel any pending long press
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                isLongPressed = false

                // Clean up the stored down event
                downEvent?.recycle()
                downEvent = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    
    override fun setSelected(selected: Boolean) {
        isSelected = selected
        updateSelectionTint()
    }
    
    protected open fun showIconContextMenu(x: Float, y: Float) {
        // Use custom handler if set, otherwise use default desktop context menu
        if (customLongClickHandler != null) {
            customLongClickHandler?.invoke(x, y)
        } else {
            val mainActivity = context as? MainActivity
            mainActivity?.showDesktopIconContextMenu(this, x, y)
        }
    }
    
    private fun updateSelectionTint() {
        if (isSelected) {
            // Apply semi-transparent blue background only (don't color the icon)
            setBackgroundColor(Color.parseColor("#803399FF")) // Blue background with 0.5 opacity (80 in hex = ~50%)
        } else {
            // Remove background
            setBackgroundColor(Color.TRANSPARENT)
        }
    }
}