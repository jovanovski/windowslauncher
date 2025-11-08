package rocks.gorjan.gokixp.theme

import android.content.Context
import androidx.core.content.edit
import rocks.gorjan.gokixp.R

/**
 * Sealed class representing available themes.
 * toString() returns the exact string stored in SharedPreferences for backward compatibility.
 */
sealed class AppTheme {
    object WindowsXP : AppTheme() {
        override fun toString() = "Windows XP"
    }

    object WindowsClassic : AppTheme() {
        override fun toString() = "Windows Classic"
    }

    object WindowsVista : AppTheme() {
        override fun toString() = "Windows Vista"
    }

    // Future themes can be added here:
    // object Windows7 : AppTheme() {
    //     override fun toString() = "Windows 7"
    // }

    companion object {
        /**
         * Converts string from SharedPreferences to AppTheme.
         * Maintains backward compatibility with existing user preferences.
         */
        fun fromString(value: String?): AppTheme = when (value) {
            "Windows Classic" -> WindowsClassic
            "Windows Vista" -> WindowsVista
            "Windows XP" -> WindowsXP
            else -> WindowsXP // Default to XP if unknown
        }

        /**
         * Returns all available themes.
         */
        fun all(): List<AppTheme> = listOf(WindowsXP, WindowsClassic, WindowsVista)
    }
}

/**
 * Centralized theme management class.
 * Handles theme selection, persistence, and resource mapping.
 *
 * BACKWARD COMPATIBILITY:
 * - Uses existing SharedPreferences key "selected_theme"
 * - Preserves string values "Windows XP" and "Windows Classic"
 * - No breaking changes to user settings
 */
class ThemeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


    /**
     * Gets the currently selected theme from SharedPreferences.
     * Reads the same key used by legacy code.
     */
    fun getSelectedTheme(): AppTheme {
        val stored = prefs.getString(KEY_SELECTED_THEME, "Windows XP")
        return AppTheme.fromString(stored)
    }

    /**
     * Sets the selected theme in SharedPreferences.
     * Writes the same key and string values as legacy code.
     */
    fun setSelectedTheme(theme: AppTheme) {
        prefs.edit {
            putString(KEY_SELECTED_THEME, theme.toString())
        }
    }

    /**
     * Returns true if the current theme is Windows Classic (98).
     * Convenience method for boolean checks.
     */
    fun isClassicTheme(): Boolean = getSelectedTheme() is AppTheme.WindowsClassic

    /**
     * Returns true if the current theme is Windows XP.
     * Convenience method for boolean checks.
     */
    fun isXPTheme(): Boolean = getSelectedTheme() is AppTheme.WindowsXP
    fun isVistaTheme(): Boolean = getSelectedTheme() is AppTheme.WindowsVista

    // ========== Resource Mapping Methods ==========
    // These methods centralize all theme-specific resource lookups

    /**
     * Gets the theme style resource ID for the given theme.
     */
    fun getThemeStyleRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.style.Theme_GokiXP_Classic
        AppTheme.WindowsXP -> R.style.Base_Theme_GokiXP
        AppTheme.WindowsVista -> R.style.Theme_GokiXP_Vista
    }

    /**
     * Gets the taskbar layout resource ID for the given theme.
     */
    fun getTaskbarLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.taskbar_98
        AppTheme.WindowsXP -> R.layout.taskbar_xp
        AppTheme.WindowsVista -> R.layout.taskbar_vista
    }

    /**
     * Gets the start menu layout resource ID for the given theme.
     */
    fun getStartMenuLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.start_menu_98
        AppTheme.WindowsXP -> R.layout.start_menu_xp
        AppTheme.WindowsVista -> R.layout.start_menu_vista
    }

    /**
     * Gets the dialog content layout resource ID for the given theme.
     */
    fun getDialogLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.windows_dialog_content_98
        AppTheme.WindowsXP -> R.layout.windows_dialog_content_xp
        AppTheme.WindowsVista -> R.layout.windows_dialog_content_vista
    }

    /**
     * Gets the spinner item layout resource ID for the given theme.
     */
    fun getSpinnerItemLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.spinner_item_classic
        AppTheme.WindowsXP -> R.layout.spinner_item_xp
        AppTheme.WindowsVista -> R.layout.spinner_item_vista
    }

    /**
     * Gets the spinner dropdown layout resource ID for the given theme.
     */
    fun getSpinnerDropdownLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.spinner_dropdown_item_classic
        AppTheme.WindowsXP -> R.layout.spinner_dropdown_item_xp
        AppTheme.WindowsVista -> R.layout.spinner_dropdown_item_vista
    }

    fun getIELayout(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.layout.program_internet_explorer
        AppTheme.WindowsXP -> R.layout.program_internet_explorer
        AppTheme.WindowsVista -> R.layout.program_internet_explorer_7
    }



    fun getIEIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.ie6
        AppTheme.WindowsXP -> R.drawable.ie6
        AppTheme.WindowsVista -> R.drawable.ie7
    }

    fun getWindowsIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.windows_logo
        AppTheme.WindowsXP -> R.drawable.xp_logo
        AppTheme.WindowsVista -> R.drawable.logo_vista
    }



    fun getRegeditIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.regedit_icon_98
        AppTheme.WindowsXP -> R.drawable.regedit_icon_xp
        AppTheme.WindowsVista -> R.drawable.regedit_icon_vista
    }

    fun getSolitareIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.solitare_icon
        AppTheme.WindowsXP -> R.drawable.solitare_icon
        AppTheme.WindowsVista -> R.drawable.solitare_icon_vista
    }


    fun getWinampIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.winamp_icon_98
        AppTheme.WindowsXP -> R.drawable.winamp_icon_xp
        AppTheme.WindowsVista -> R.drawable.winamp_icon_xp
    }


    fun getMinesweeperIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.minesweeper_icon_98
        AppTheme.WindowsXP -> R.drawable.minesweeper_icon_xp
        AppTheme.WindowsVista -> R.drawable.minesweeper_icon_vista
    }


    fun getNotepadIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.notepad_icon_98
        AppTheme.WindowsXP -> R.drawable.notepad_icon_xp
        AppTheme.WindowsVista -> R.drawable.notepad_icon_vista
    }

    fun getMsnIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.msn_icon
        AppTheme.WindowsXP -> R.drawable.msn_icon
        AppTheme.WindowsVista -> R.drawable.msn_icon_vista
    }

    fun getMaximizeIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.win98_title_bar_maximize
        AppTheme.WindowsXP -> R.drawable.xp_title_bar_maximize
        AppTheme.WindowsVista -> R.drawable.vista_title_bar_maximize
    }

    fun getRestoreIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.win98_title_bar_restore
        AppTheme.WindowsXP -> R.drawable.xp_title_bar_restore
        AppTheme.WindowsVista -> R.drawable.vista_title_bar_restore
    }

    /**
     * Gets the taskbar button layout resource ID for the given theme.
     */
    fun getTaskbarButtonLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.taskbar_button_98
        AppTheme.WindowsXP -> R.layout.taskbar_button_xp
        AppTheme.WindowsVista -> R.layout.taskbar_button_vista
    }

    /**
     * Gets the Windows Explorer layout resource ID for the given theme.
     */
    fun getWindowsExplorerLayoutRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.layout.windows_explorer_98
        AppTheme.WindowsXP -> R.layout.windows_explorer_xp
        AppTheme.WindowsVista -> R.layout.windows_explorer_vista
    }

    // ========== Icon Resource Mappings ==========

    /**
     * Gets the folder icon drawable resource ID for the given theme.
     */
    fun getFolderIconRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.drawable.folder_98
        AppTheme.WindowsXP -> R.drawable.folder_xp
        AppTheme.WindowsVista -> R.drawable.folder_vista
    }

    /**
     * Gets the recycle bin icon drawable resource ID for the given theme.
     * @param isEmpty Whether the recycle bin is empty
     */
    fun getRecycleBinIconRes(theme: AppTheme, isEmpty: Boolean): Int = when (theme) {
        AppTheme.WindowsClassic -> R.drawable.recycle_98
        AppTheme.WindowsXP -> R.drawable.recycle
        AppTheme.WindowsVista -> R.drawable.recycle_vista
    }

    /**
     * Gets the start button drawable resource ID for the given theme.
     */
    fun getStartButtonRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.drawable.start_98
        AppTheme.WindowsXP -> R.drawable.start
        AppTheme.WindowsVista -> R.drawable.start_vista
    }

    // ========== Font Resource Mappings ==========

    /**
     * Gets the primary font family resource ID for the given theme.
     */
    fun getPrimaryFontRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.font.micross_font_family
        AppTheme.WindowsXP -> R.font.tahoma_font_family
        AppTheme.WindowsVista -> R.font.tahoma_font_family  // Use Tahoma for now, can be replaced with Segoe UI
    }

    /**
     * Gets the bold font resource ID for the given theme.
     */
    fun getBoldFontRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.font.micross_block_bold
        AppTheme.WindowsXP -> R.font.tahoma
        AppTheme.WindowsVista -> R.font.tahoma  // Use Tahoma for now
    }

    // ========== Scrollbar Styling ==========

    /**
     * Applies themed scrollbar drawables to a view that supports scrollbars.
     * Supports Windows XP, Windows Classic, and Windows Vista themes.
     *
     * @param view The view to apply scrollbars to (must support scrollbars, e.g., EditText, RecyclerView)
     * @param theme The theme to apply (optional, defaults to current selected theme)
     */
    fun applyThemedScrollbars(view: android.view.View, theme: AppTheme = getSelectedTheme()) {
        // Get theme-specific scrollbar drawables
        val (trackRes, thumbRes) = when (theme) {
            AppTheme.WindowsXP -> R.drawable.scrollbar_track_xp to R.drawable.scrollbar_thumb_xp
            AppTheme.WindowsClassic -> R.drawable.scrollbar_track_98 to R.drawable.win98_start_menu_border
            AppTheme.WindowsVista -> R.drawable.scrollbar_track_vista to R.drawable.scrollbar_thumb_vista
        }

        // Get theme-appropriate drawables
        val trackDrawable = androidx.core.content.ContextCompat.getDrawable(context, trackRes) ?: return
        val thumbDrawable = androidx.core.content.ContextCompat.getDrawable(context, thumbRes) ?: return

        // API 29+ has direct methods to set scrollbar drawables
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Set scrollbar size to 16dp
            val scrollBarSize = (16 * context.resources.displayMetrics.density).toInt()

            // Enable vertical scrollbar
            view.isVerticalScrollBarEnabled = true
            view.scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
            view.isScrollbarFadingEnabled = false

            // Set scrollbar size if the view supports it
            when (view) {
                is android.widget.TextView -> view.scrollBarSize = scrollBarSize
                is androidx.recyclerview.widget.RecyclerView -> view.scrollBarSize = scrollBarSize
            }

            // Use the direct API methods (API 29+)
            view.setVerticalScrollbarThumbDrawable(thumbDrawable)
            view.setVerticalScrollbarTrackDrawable(trackDrawable)

            android.util.Log.d("ThemeManager", "Successfully set scrollbar drawables using API 29+ methods")
        } else {
            // Fallback for older APIs - just log that it's not supported
            android.util.Log.w("ThemeManager", "Themed scrollbars require API 29+, current API is ${android.os.Build.VERSION.SDK_INT}")
        }
    }

    companion object {
        private const val PREFS_NAME = "taskbar_widget_prefs"  // Must match MainActivity's PREFS_NAME
        private const val KEY_SELECTED_THEME = "selected_theme"
    }
}
