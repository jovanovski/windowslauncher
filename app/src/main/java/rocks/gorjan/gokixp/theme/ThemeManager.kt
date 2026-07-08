package rocks.gorjan.gokixp.theme

import android.content.Context
import androidx.core.content.edit
import rocks.gorjan.gokixp.MainActivity
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
    private val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)


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
     * Also resets the Plus! slug to "default" whenever the theme leaves Classic.
     */
    fun setSelectedTheme(theme: AppTheme) {
        prefs.edit {
            putString(KEY_SELECTED_THEME, theme.toString())
            if (theme !is AppTheme.WindowsClassic) {
                putString(KEY_PLUS95_THEME, PLUS95_DEFAULT)
            }
        }
    }

    // ========== Plus! 95 theme support ==========

    data class Plus95Theme(
        val slug: String,
        val displayName: String,
        val menuColor: Int,
        val busyAsset: String?,
        val soundAsset: String?,
        val startupAsset: String?
    )

    fun getAllPlus95Themes(): List<Plus95Theme> = PLUS95_THEMES

    fun getPlus95Slug(): String = prefs.getString(KEY_PLUS95_THEME, PLUS95_DEFAULT) ?: PLUS95_DEFAULT

    fun setPlus95Slug(slug: String) {
        prefs.edit { putString(KEY_PLUS95_THEME, slug) }
    }

    fun getActivePlus95(): Plus95Theme? {
        if (getSelectedTheme() !is AppTheme.WindowsClassic) return null
        val slug = getPlus95Slug()
        if (slug == PLUS95_DEFAULT) return null
        return PLUS95_THEMES.firstOrNull { it.slug == slug }
    }

    fun plus95Path(slug: String, filename: String): String = "plus95/$slug/$filename"

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

    fun getWmpIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.wmp_98_icon
        AppTheme.WindowsXP -> R.drawable.wmp_xp_icon
        AppTheme.WindowsVista -> R.drawable.wmp_vista_icon
    }

    fun getPhotosIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.photos_98_icon
        AppTheme.WindowsXP -> R.drawable.photos_xp_icon
        AppTheme.WindowsVista -> R.drawable.photos_vista_icon
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


    fun getClockIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.icon_clock_98
        AppTheme.WindowsXP -> R.drawable.icon_clock_xp
        AppTheme.WindowsVista -> R.drawable.icon_clock_vista
    }

    fun getMsnIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.msn_icon
        AppTheme.WindowsXP -> R.drawable.msn_icon
        AppTheme.WindowsVista -> R.drawable.msn_icon_vista
    }

    fun getMyComputerIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.my_computer_98_icon
        AppTheme.WindowsXP -> R.drawable.my_computer_xp_icon
        AppTheme.WindowsVista -> R.drawable.my_computer_vista_icon
    }

    fun getFileGenericIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.file_generic_98
        AppTheme.WindowsXP -> R.drawable.file_generic_xp
        AppTheme.WindowsVista -> R.drawable.file_generic_vista
    }

    fun getFileImageIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.file_image_98
        AppTheme.WindowsXP -> R.drawable.file_image_xp
        AppTheme.WindowsVista -> R.drawable.file_image_vista
    }


    fun getPDFImageIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.file_pdf_98
        AppTheme.WindowsXP -> R.drawable.file_pdf_xp
        AppTheme.WindowsVista -> R.drawable.file_pdf_vista
    }

    fun getFileAudioIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.file_audio_98
        AppTheme.WindowsXP -> R.drawable.file_audio_xp
        AppTheme.WindowsVista -> R.drawable.file_audio_vista
    }

    fun getFileVideoIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.file_video_98
        AppTheme.WindowsXP -> R.drawable.file_video_xp
        AppTheme.WindowsVista -> R.drawable.file_video_vista
    }

    fun getDriveFloppyIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.drive_floppy_98
        AppTheme.WindowsXP -> R.drawable.drive_floppy_xp
        AppTheme.WindowsVista -> R.drawable.drive_floppy_vista
    }

    fun getDriveLocalIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.drive_local_98
        AppTheme.WindowsXP -> R.drawable.drive_local_xp
        AppTheme.WindowsVista -> R.drawable.drive_local_vista
    }

    fun getDriveOpticalIcon(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.drawable.drive_optical_98
        AppTheme.WindowsXP -> R.drawable.drive_optical_xp
        AppTheme.WindowsVista -> R.drawable.drive_optical_vista
    }

    fun getWmpLayout(): Int = when (getSelectedTheme()){
        AppTheme.WindowsClassic -> R.layout.program_wmp_98
        AppTheme.WindowsXP -> R.layout.program_wmp_xp
        AppTheme.WindowsVista -> R.layout.program_wmp_vista
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
        private const val KEY_SELECTED_THEME = "selected_theme"
        const val KEY_PLUS95_THEME = "plus95_theme"
        const val PLUS95_DEFAULT = "default"

        val PLUS95_THEMES: List<Plus95Theme> = listOf(
            Plus95Theme("dangerous_creatures", "Dangerous Creatures", 0xFF707070.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("inside_your_computer", "Inside your Computer", 0xFFA8C8A8.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("leonardo_da_vinci", "Leonardo da Vinci", 0xFFBFA59F.toInt(), "busy.png", "default.wav", "start.wav"),
            Plus95Theme("more_windows", "More Windows", 0xFF9098A0.toInt(), null, null, null),
            Plus95Theme("mystery", "Mystery", 0xFF687868.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("nature", "Nature", 0xFFD8C0A0.toInt(), "busy.png", "default.wav", "start.wav"),
            Plus95Theme("science", "Science", 0xFF8399B1.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("sports", "Sports", 0xFFB0E0A0.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("the_60s_usa", "The 60's USA", 0xFFD068D8.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("the_golden_era", "The Golden Era", 0xFFB8C8B8.toInt(), null, "default.wav", "start.wav"),
            Plus95Theme("travel", "Travel", 0xFF908070.toInt(), null, "default.wav", "start.wav"),
        )

        const val CLASSIC_GRAY: Int = 0xFFD3CEC7.toInt()
    }
}
