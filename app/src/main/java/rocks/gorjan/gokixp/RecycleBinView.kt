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

        // If a Plus! 95 theme is active, prefer its recye.png asset
        val plus95 = mainActivity?.themeManager?.getActivePlus95()
        if (plus95 != null) {
            try {
                context.assets.open(mainActivity.themeManager.plus95Path(plus95.slug, "recye.png")).use { stream ->
                    val d = android.graphics.drawable.Drawable.createFromStream(stream, "recye.png")
                    if (d != null) {
                        setIconDrawable(d)
                        getDesktopIcon()?.icon = d
                        return
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RecycleBinView", "Failed to load Plus! recycle bin icon", e)
            }
        }

        val iconResource = if (mainActivity != null) {
            mainActivity.themeManager.getRecycleBinIconRes(currentTheme, isEmpty = false)
        } else {
            if (currentTheme is AppTheme.WindowsClassic) {
                R.drawable.recycle_98
            } else {
                R.drawable.recycle
            }
        }

        val drawable = context.getDrawable(iconResource)!!
        setIconDrawable(drawable)
        getDesktopIcon()?.icon = drawable
    }

    // Backward compatible method
    fun setThemeIcon(isWindows98: Boolean) {
        currentTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        updateIcon()
    }
}