package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

class ContextMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), ThemeAware {

    private var onItemClickListener: ((ContextMenuItem) -> Unit)? = null
    private var onMenuHiddenListener: (() -> Unit)? = null
    private var currentTheme: AppTheme = AppTheme.WindowsXP

    // Backward compatible property
    private var isWindows98Theme = false
        get() = currentTheme is AppTheme.WindowsClassic

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.context_menu_background)
        // Elevation is set in XML (160dp) to be above start menu (150dp)
        visibility = GONE
    }

    fun showMenu(items: List<ContextMenuItem>, x: Float, y: Float) {
        // Trigger haptic feedback when opening context menu
        Helpers.performHapticFeedback(context)
        
        // Clear existing items
        removeAllViews()
        
        // Add new items
        items.forEach { item ->
            addMenuItem(item)
        }
        
        // Position the menu
        positionMenu(x, y)
        
        // Show the menu
        visibility = VISIBLE
        
        // Ensure it renders above everything else
        bringToFront()
    }
    
    fun hideMenu() {
        visibility = GONE
        onMenuHiddenListener?.invoke()
    }

    fun setOnItemClickListener(listener: (ContextMenuItem) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnMenuHiddenListener(listener: () -> Unit) {
        onMenuHiddenListener = listener
    }

    fun setThemeBackground(isWindows98: Boolean) {
        currentTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        updateExistingMenuItemFonts(isWindows98)
        if (currentTheme is AppTheme.WindowsClassic) {
            // Create Windows 98 border: top/left black, bottom/right white
            val borderDrawable = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: android.graphics.Canvas) {
                    val bounds = getBounds()
                    val paint = android.graphics.Paint()
                    val borderSize = (1 * context.resources.displayMetrics.density).toInt()

                    // Fill background with #d3cec7
                    paint.color = android.graphics.Color.parseColor("#d3cec7")
                    canvas.drawRect(bounds, paint)

                    // Top border (2dp, white)
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawRect(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        (bounds.top + borderSize).toFloat(),
                        paint
                    )

                    // Left border (2dp, white)
                    canvas.drawRect(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        (bounds.left + borderSize).toFloat(),
                        bounds.bottom.toFloat(),
                        paint
                    )

                    // Bottom border (2dp, black)
                    paint.color = android.graphics.Color.BLACK
                    canvas.drawRect(
                        bounds.left.toFloat(),
                        (bounds.bottom - borderSize).toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat(),
                        paint
                    )

                    // Right border (2dp, black)
                    canvas.drawRect(
                        (bounds.right - borderSize).toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat(),
                        paint
                    )
                }

                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
                override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
            }

            background = borderDrawable
        } else {
            setBackgroundResource(R.drawable.context_menu_background)
        }
    }

    private fun updateExistingMenuItemFonts(isWindows98: Boolean) {
        // Update fonts for all existing menu items
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is LinearLayout) {
                val textView = child.findViewById<TextView>(R.id.menu_item_text)
                textView?.let { tv ->
                    MainActivity.getInstance()?.applyThemeFontToTextView(tv)
                }
            }
        }
    }

    private fun addMenuItem(item: ContextMenuItem) {
        when {
            item.title.isEmpty() -> {
                // Add divider - different styles for different themes
                if (isWindows98Theme) {
                    // Windows 98 style: 2px divider with #909090 top, #FFFFFF bottom
                    val dividerContainer = LinearLayout(context)
                    dividerContainer.orientation = LinearLayout.VERTICAL
                    val containerParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    containerParams.setMargins(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                    dividerContainer.layoutParams = containerParams

                    // First row: #909090
                    val topDivider = View(context)
                    val topParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx())
                    topDivider.layoutParams = topParams
                    topDivider.setBackgroundColor(android.graphics.Color.parseColor("#909090"))

                    // Second row: #FFFFFF
                    val bottomDivider = View(context)
                    val bottomParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx())
                    bottomDivider.layoutParams = bottomParams
                    bottomDivider.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))

                    dividerContainer.addView(topDivider)
                    dividerContainer.addView(bottomDivider)
                    addView(dividerContainer)
                } else {
                    // Windows XP style: original divider
                    val divider = View(context)
                    val dividerParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
                    dividerParams.setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
                    divider.layoutParams = dividerParams
                    divider.setBackgroundColor(context.getColor(R.color.context_menu_divider))
                    addView(divider)
                }
            }
            else -> {
                // Add menu item
                val itemView = LayoutInflater.from(context)
                    .inflate(R.layout.context_menu_item, this, false) as LinearLayout
                
                val textView = itemView.findViewById<TextView>(R.id.menu_item_text)
                val arrowView = itemView.findViewById<TextView>(R.id.menu_item_arrow)
                val checkboxView = itemView.findViewById<TextView>(R.id.menu_item_checkbox)
                val separatorView = itemView.findViewById<View>(R.id.menu_item_separator)
                val subActionIconView = itemView.findViewById<ImageView>(R.id.menu_item_sub_action_icon)

                textView.text = item.title

                // Apply theme-appropriate font
                MainActivity.getInstance()?.applyThemeFontToTextView(textView)
                if (item.isEnabled) {
                    textView.setTextColor(context.getColorStateList(R.color.context_menu_text_selector))
                } else {
                    textView.setTextColor(context.getColor(R.color.context_menu_text_disabled))
                }
                
                // Set background
                itemView.setBackgroundResource(
                    if (item.isEnabled) R.drawable.context_menu_item_enabled
                    else R.drawable.context_menu_item_disabled
                )
                
                // Show/hide checkbox
                checkboxView.visibility = if (item.hasCheckbox) VISIBLE else GONE
                if (item.hasCheckbox) {
                    checkboxView.visibility = VISIBLE
                    if (item.isChecked) {
                        checkboxView.alpha = 1.0f
                        checkboxView.setTextColor(context.getColor(android.R.color.black))
                    } else {
                        checkboxView.alpha = 0.2f
                        checkboxView.setTextColor(context.getColor(android.R.color.black))
                    }
                }
                
                // Show/hide submenu arrow
                arrowView.visibility = if (item.hasSubmenu) VISIBLE else GONE

                // Show/hide sub-action icon and separator
                if (item.subActionIcon != null && item.subAction != null) {
                    separatorView.visibility = VISIBLE
                    subActionIconView.visibility = VISIBLE
                    subActionIconView.setImageResource(item.subActionIcon)

                    // Apply enabled/disabled state to sub-action icon
                    if (item.isEnabled) {
                        subActionIconView.alpha = 1.0f
                        subActionIconView.isEnabled = true
                    } else {
                        subActionIconView.alpha = 0.5f
                        subActionIconView.isEnabled = false
                    }

                    // Set click listener for sub-action icon
                    if (item.isEnabled) {
                        subActionIconView.setOnClickListener {
                            // Play click sound for menu options
                            (context as? MainActivity)?.let { mainActivity ->
                                mainActivity.playClickSound()
                            }
                            item.subAction.invoke()
                            onItemClickListener?.invoke(item)
                            hideMenu()
                        }
                    }
                } else {
                    separatorView.visibility = GONE
                    subActionIconView.visibility = GONE
                }

                // Set click listener
                if (item.isEnabled && item.action != null) {
                    itemView.setOnClickListener {
                        // Play click sound for menu options
                        (context as? MainActivity)?.let { mainActivity ->
                            mainActivity.playClickSound()
                        }
                        item.action.invoke()
                        onItemClickListener?.invoke(item)
                        hideMenu()
                    }
                } else {
                    // Consume clicks on disabled items to prevent them from passing through
                    itemView.setOnClickListener {
                        // Do nothing, just consume the click
                    }
                }

                addView(itemView)
            }
        }
    }
    
    private fun positionMenu(x: Float, y: Float) {
        // Force measure to get actual dimensions
        measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val menuWidth = measuredWidth
        val menuHeight = measuredHeight
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels


        // Get taskbar position to avoid showing menu below it
        val taskbarTop = getTaskbarTopPosition()
        val availableHeight = if (taskbarTop > 0) taskbarTop else screenHeight

        // Smart positioning logic like Windows
        var finalX = x
        var finalY = y

        // Handle horizontal positioning
        if (x + menuWidth + 250 > screenWidth) {
            // Would go off right edge - position to the left of click point
            finalX = (x - menuWidth).coerceAtLeast(0f)
        }

        // Handle vertical positioning - check against taskbar top, not screen bottom
        if (y + menuHeight > availableHeight) {
            // Would go below taskbar - position above click point
            finalY = (y - menuHeight).coerceAtLeast(0f)
        }

        // Ensure menu doesn't go off left edge
        if (finalX < 0) {
            finalX = 0f
        }

        // Ensure menu doesn't go off top edge
        if (finalY < 0) {
            finalY = 0f
        }

        // Apply position
        translationX = finalX
        translationY = finalY
    }

    private fun getTaskbarTopPosition(): Int {
        try {
            // Try to find the taskbar container in the activity
            val activity = context as? MainActivity
            val taskbarContainer = activity?.findViewById<View>(R.id.taskbar_container)

            if (taskbarContainer != null) {
                // Get taskbar position on screen
                val location = IntArray(2)
                taskbarContainer.getLocationOnScreen(location)
                return location[1] // Y position of taskbar top
            }
        } catch (e: Exception) {
            // Ignore - we'll fall back to screen height
        }

        return 0 // Return 0 to indicate taskbar not found (use screen height instead)
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    // Phase 3: Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        updateBackground()
        updateExistingMenuItemFonts(currentTheme is AppTheme.WindowsClassic)
    }

    private fun updateBackground() {
        val mainActivity = context as? MainActivity
        if (mainActivity != null) {
            background = mainActivity.drawableManager.getContextMenuBackground(currentTheme)
        } else {
            // Fallback if not in MainActivity context
            if (currentTheme is AppTheme.WindowsClassic) {
                setThemeBackground(true)
            } else {
                setBackgroundResource(R.drawable.context_menu_background)
            }
        }
    }
}