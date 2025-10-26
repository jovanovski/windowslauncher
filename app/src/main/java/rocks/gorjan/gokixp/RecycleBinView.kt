package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

class RecycleBinView : DesktopIconView, ThemeAware {

    private var currentTheme: AppTheme = AppTheme.WindowsXP

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        setupRecycleBinBehavior()
    }

    constructor(
        context: Context,
        layoutResId: Int
    ) : super(context, layoutResId) {
        setupRecycleBinBehavior()
    }

    private fun setupRecycleBinBehavior() {
        // Make sure it's visible
        visibility = VISIBLE
        alpha = 1.0f

        // Add click listener for testing visibility
        setOnClickListener {
            android.util.Log.d("RecycleBinView", "Recycle bin clicked!")
        }
    }
    
    // Override to show recycle bin specific context menu
    override fun showIconContextMenu(x: Float, y: Float) {
        val mainActivity = context as? MainActivity
        mainActivity?.showRecycleBinContextMenu(this, x, y)
    }

    // Phase 3: Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        updateIcon()
    }

    private fun updateIcon() {
        val mainActivity = context as? MainActivity
        val iconResource = if (mainActivity != null) {
            mainActivity.themeManager.getRecycleBinIconRes(currentTheme, isEmpty = false)
        } else {
            // Fallback
            if (currentTheme is AppTheme.WindowsClassic) {
                R.drawable.recycle_98
            } else {
                R.drawable.recycle
            }
        }

        android.util.Log.d("RecycleBinView", "Using icon resource: $iconResource")

        val drawable = context.getDrawable(iconResource)!!
        setIconDrawable(drawable)

        android.util.Log.d("RecycleBinView", "Icon drawable set successfully")

        // Also update the desktop icon data
        getDesktopIcon()?.let { desktopIcon ->
            desktopIcon.icon = drawable
            android.util.Log.d("RecycleBinView", "Desktop icon data updated")
        }
    }

    // Backward compatible method
    fun setThemeIcon(isWindows98: Boolean) {
        currentTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        updateIcon()
    }
}