package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

class FolderView : DesktopIconView, ThemeAware {

    private var currentTheme: AppTheme = AppTheme.WindowsXP
    private var disableContextMenu: Boolean = false

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : super(context, attrs, defStyleAttr) {
        setupFolderBehavior()
    }

    constructor(
        context: Context,
        layoutResId: Int
    ) : super(context, layoutResId) {
        setupFolderBehavior()
    }

    constructor(
        context: Context,
        theme: AppTheme,
        layoutResId: Int
    ) : super(context, layoutResId) {
        currentTheme = theme
        setupFolderBehavior()
        updateIcon()
    }

    private fun setupFolderBehavior() {
        // Make sure it's visible
        visibility = VISIBLE
        alpha = 1.0f

        // Override click listener to open folder instead of launching an app
        setOnClickListener {
            android.util.Log.d("FolderView", "Folder clicked!")
            val mainActivity = context as? MainActivity
            mainActivity?.showFolderWindow(this)
        }
    }

    // Override to show folder specific context menu
    override fun showIconContextMenu(x: Float, y: Float) {
        // Don't show context menu if disabled (e.g., for read-only file browser)
        if (disableContextMenu) {
            return
        }
        val mainActivity = context as? MainActivity
        mainActivity?.showFolderContextMenu(this, x, y)
    }

    // Method to disable context menu (for read-only views like Windows Explorer)
    fun setContextMenuEnabled(enabled: Boolean) {
        disableContextMenu = !enabled
    }

    // Phase 3: Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        updateIcon()
    }

    private fun updateIcon() {
        val mainActivity = context as? MainActivity
        val iconResource = if (mainActivity != null) {
            mainActivity.themeManager.getFolderIconRes(currentTheme)
        } else {
            // Fallback
            if (currentTheme is AppTheme.WindowsClassic) {
                R.drawable.folder_98
            } else if (currentTheme is AppTheme.WindowsVista) {
                R.drawable.folder_vista
            } else {
                R.drawable.folder_xp
            }
        }

        android.util.Log.d("FolderView", "Using folder icon resource: $iconResource")

        val drawable = context.getDrawable(iconResource)!!
        setIconDrawable(drawable)

        // Also update the desktop icon data
        getDesktopIcon()?.let { desktopIcon ->
            desktopIcon.icon = drawable
            android.util.Log.d("FolderView", "Desktop icon data updated")
        }
    }

    // Backward compatible method - pulls current theme from MainActivity
    fun setThemeIcon(isWindows98: Boolean) {
        val mainActivity = context as? MainActivity
        currentTheme = mainActivity?.themeManager?.getSelectedTheme() ?: AppTheme.WindowsXP
        updateIcon()
    }

    // New method that accepts AppTheme directly
    fun setThemeIcon(theme: AppTheme) {
        currentTheme = theme
        updateIcon()
    }
}
