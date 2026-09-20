package rocks.gorjan.gokixp.apps.briefcase

import android.content.Context
import android.util.AttributeSet
import rocks.gorjan.gokixp.DesktopIconView
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

/**
 * The My Briefcase icon on the desktop.
 *
 * The same shape as My Computer's: its own icon per theme, its own context menu, and a tap
 * that opens its own window rather than launching anything.
 */
class BriefcaseView : DesktopIconView, ThemeAware {

    private var currentTheme: AppTheme = AppTheme.WindowsXP

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        setupBriefcaseBehavior()
    }

    constructor(context: Context, layoutResId: Int) : super(context, layoutResId) {
        setupBriefcaseBehavior()
    }

    private fun setupBriefcaseBehavior() {
        visibility = VISIBLE
        alpha = 1.0f

        setOnClickListener {
            (context as? MainActivity)?.openBriefcase(this)
        }
    }

    override fun showIconContextMenu(x: Float, y: Float) {
        (context as? MainActivity)?.showBriefcaseContextMenu(this, x, y)
    }

    override fun onThemeChanged(theme: AppTheme) {
        super.onThemeChanged(theme) // font
        currentTheme = theme
        // Don't overwrite a user's custom Briefcase icon (updateAllCustomIcons restores it);
        // only swap in the themed icon when none is set.
        val packageName = getDesktopIcon()?.packageName
        val hasCustomIcon = packageName != null && (context as? MainActivity)?.hasCustomIcon(packageName) == true
        if (!hasCustomIcon) updateIcon()
    }

    private fun updateIcon() {
        val mainActivity = context as? MainActivity
        val iconResource = mainActivity?.themeManager?.getBriefcaseIcon()
            ?: when (currentTheme) {
                is AppTheme.WindowsClassic -> rocks.gorjan.gokixp.R.drawable.briefcase_98_icon
                is AppTheme.WindowsVista -> rocks.gorjan.gokixp.R.drawable.briefcase_vista_icon
                is AppTheme.Windows7 -> rocks.gorjan.gokixp.R.drawable.briefcase_win7_icon
                else -> rocks.gorjan.gokixp.R.drawable.briefcase_xp_icon
            }

        val drawable = context.getDrawable(iconResource) ?: return
        setIconDrawable(drawable)
        getDesktopIcon()?.icon = drawable
    }

    /** Matches the other system icons: the boolean predates the Aero themes. */
    fun setThemeIcon(isWindows98: Boolean) {
        currentTheme = (context as? MainActivity)?.themeManager?.getSelectedTheme()
            ?: if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        updateIcon()
    }
}
