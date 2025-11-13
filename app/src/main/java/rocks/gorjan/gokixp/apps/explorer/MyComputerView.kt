package rocks.gorjan.gokixp.apps.explorer

import android.content.Context
import android.util.AttributeSet
import rocks.gorjan.gokixp.DesktopIconView
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

/**
 * Desktop icon view for My Computer system app
 */
class MyComputerView : DesktopIconView, ThemeAware {

    private var currentTheme: AppTheme = AppTheme.WindowsXP

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        setupMyComputerBehavior()
    }

    constructor(
        context: Context,
        layoutResId: Int
    ) : super(context, layoutResId) {
        setupMyComputerBehavior()
    }

    private fun setupMyComputerBehavior() {
        // Make sure it's visible
        visibility = VISIBLE
        alpha = 1.0f

        // Override click to open My Computer app
        setOnClickListener {
            val mainActivity = context as? MainActivity
            mainActivity?.openMyComputer(this)
        }
    }

    // Override to show My Computer specific context menu
    override fun showIconContextMenu(x: Float, y: Float) {
        val mainActivity = context as? MainActivity
        mainActivity?.showMyComputerContextMenu(this, x, y)
    }

    // Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        updateIcon()
    }

    private fun updateIcon() {
        val mainActivity = context as? MainActivity
        val iconResource = if (mainActivity != null) {
            mainActivity.themeManager.getMyComputerIcon()
        } else {
            // Fallback
            when (currentTheme) {
                is AppTheme.WindowsClassic -> R.drawable.my_computer_98_icon
                is AppTheme.WindowsVista -> R.drawable.my_computer_vista_icon
                else -> R.drawable.my_computer_xp_icon
            }
        }

        val drawable = context.getDrawable(iconResource)!!
        setIconDrawable(drawable)

        // Also update the desktop icon data
        getDesktopIcon()?.let { desktopIcon ->
            desktopIcon.icon = drawable
        }
    }

    // Backward compatible method
    fun setThemeIcon(isWindows98: Boolean) {
        currentTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        updateIcon()
    }
}
