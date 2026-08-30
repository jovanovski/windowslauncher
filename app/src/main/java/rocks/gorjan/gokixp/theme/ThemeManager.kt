package rocks.gorjan.gokixp.theme

import android.content.Context
import androidx.core.content.edit
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

/**
 * Which desktop chrome a theme renders its windows, dialogs and in-window assets with.
 *
 * This is deliberately separate from [AppTheme]: a theme can replace the whole shell
 * (Start screen, taskbar, navigation) while still drawing its windows in a borrowed
 * chrome. Windows Phone 8.1 does exactly that - it has no desktop of its own, but its
 * windows are Vista windows.
 *
 * Resource lookups branch on this rather than on [AppTheme] so that adding a theme which
 * reuses an existing chrome costs one declaration instead of an arm in ~40 `when` blocks.
 */
enum class DesktopChrome { CLASSIC, XP, VISTA }

/**
 * Sealed class representing available themes.
 * toString() returns the exact string stored in SharedPreferences for backward compatibility.
 */
sealed class AppTheme {

    /** The window chrome this theme draws with. See [DesktopChrome]. */
    abstract val chrome: DesktopChrome

    /**
     * Where the icons the user picked by hand for this theme are kept.
     *
     * Deliberately keyed on the theme and not on [chrome]: an icon is chosen for the shell
     * that is on screen, and Windows Phone's shell is its own even though its windows are
     * Vista's. Keying this on the chrome had a tile icon overwrite a desktop one.
     */
    abstract val customIconsKey: String

    object WindowsXP : AppTheme() {
        override val chrome = DesktopChrome.XP
        override val customIconsKey = "custom_icons_xp"
        override fun toString() = "Windows XP"
    }

    object WindowsClassic : AppTheme() {
        override val chrome = DesktopChrome.CLASSIC
        override val customIconsKey = "custom_icons_98"
        override fun toString() = "Windows Classic"
    }

    object WindowsVista : AppTheme() {
        override val chrome = DesktopChrome.VISTA
        override val customIconsKey = "custom_icons_vista"
        override fun toString() = "Windows Vista"
    }

    /**
     * Windows Phone 8.1. Replaces the entire desktop shell with a phone UI (Start screen
     * of live tiles, app list, three navigation buttons) but renders its windows - Solitaire,
     * Internet Explorer, Winamp and the rest - in Vista chrome.
     */
    object WindowsPhone81 : AppTheme() {
        override val chrome = DesktopChrome.VISTA
        override val customIconsKey = "custom_icons_wp8"
        override fun toString() = "Windows Phone 8"
    }

    companion object {
        /**
         * Converts string from SharedPreferences to AppTheme.
         * Maintains backward compatibility with existing user preferences.
         */
        fun fromString(value: String?): AppTheme = when (value) {
            "Windows Classic" -> WindowsClassic
            "Windows Vista" -> WindowsVista
            "Windows XP" -> WindowsXP
            // Both spellings: the theme was called 8.1 for a while, and that string is
            // sitting in the preferences of everyone who chose it.
            "Windows Phone 8", "Windows Phone 8.1" -> WindowsPhone81
            else -> WindowsXP // Default to XP if unknown
        }

        /**
         * Returns all available themes.
         */
        fun all(): List<AppTheme> = listOf(WindowsXP, WindowsClassic, WindowsVista, WindowsPhone81)
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
        /** Asset played by MainActivity.playClickSound in place of the default UI click. */
        val soundAsset: String?,
        /** Asset played as the startup sound at launch and whenever this theme is applied. */
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

    /**
     * True only for the actual Windows Vista theme.
     *
     * Use this for *shell*-level decisions - taskbar height, startup sound, cursor,
     * desktop icon fades - none of which run under Windows Phone 8.1. For anything
     * drawn inside a window, use [isVistaChrome] instead, so WP8.1 (which renders its
     * windows in Vista chrome) takes the same branch.
     */
    fun isVistaTheme(): Boolean = getSelectedTheme() is AppTheme.WindowsVista

    /**
     * True when windows are drawn with Vista chrome - i.e. Windows Vista *or*
     * Windows Phone 8.1. This is the check in-window UI wants.
     */
    fun isVistaChrome(): Boolean = getSelectedTheme().chrome == DesktopChrome.VISTA

    /**
     * Built-in tiles the user has hidden from Start.
     *
     * Settings is deliberately not hideable - it is the only way back to this screen, and
     * hiding it would strand the user with no way to change anything.
     */
    fun getWP81HiddenTiles(): Set<String> =
        prefs.getString(KEY_WP81_HIDDEN_TILES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    fun setWP81HiddenTiles(ids: Set<String>) {
        prefs.edit { putString(KEY_WP81_HIDDEN_TILES, ids.joinToString(",")) }
    }

    /** True when the Windows Phone 8.1 shell is active. */
    fun isWindowsPhone81(): Boolean = getSelectedTheme() is AppTheme.WindowsPhone81

    /**
     * The theme name that legacy raw-string `when (selectedTheme)` blocks should branch on.
     *
     * A number of sites still read the "selected_theme" pref directly and switch on the
     * string with a silent `else -> XP` fallback. Those are not compiler-checked, so
     * WP8.1 would quietly fall through to XP assets. In-window callers should use this
     * instead of the raw pref so WP8.1 resolves to Vista.
     */
    fun chromeThemeString(): String = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> "Windows Classic"
        DesktopChrome.XP -> "Windows XP"
        DesktopChrome.VISTA -> "Windows Vista"
    }

    // ========== Windows Phone 8.1 accent + background ==========

    /** The accent colour driving tiles, headers and controls in the WP8.1 shell. */
    fun getWP81Accent(): Int = prefs.getInt(KEY_WP81_ACCENT, WP81_DEFAULT_ACCENT)

    fun setWP81Accent(color: Int) {
        prefs.edit { putInt(KEY_WP81_ACCENT, color) }
    }

    /**
     * Asset path of the Start background image, or null for a plain accent-on-black
     * (or white) Start screen. WP8.1 8.1 added exactly this - a photo behind the tiles.
     */
    fun getWP81StartBackground(): String? =
        prefs.getString(KEY_WP81_START_BACKGROUND, null)

    /** Horizontal framing of a Start background wider than the screen: 0 left, 1 right. */
    fun getWP81StartBackgroundFocusX(): Float =
        prefs.getFloat(KEY_WP81_START_BACKGROUND_FOCUS_X, 0.5f)

    /**
     * How much the Start background is blurred, 0 (sharp) to 1. Blurring pushes the photo
     * back so the tiles and their labels stay readable over it.
     */
    fun getWP81StartBackgroundBlur(): Float =
        prefs.getFloat(KEY_WP81_START_BACKGROUND_BLUR, 0f)

    fun setWP81StartBackgroundBlur(amount: Float) {
        prefs.edit { putFloat(KEY_WP81_START_BACKGROUND_BLUR, amount.coerceIn(0f, 1f)) }
    }

    /**
     * Whether the Start background wanders behind the tiles as the phone is moved.
     *
     * Off by default: it holds a sensor while Start is up, which is not something to sign
     * a user up for without being asked.
     */
    fun getWP81StartBackgroundDrift(): Boolean =
        prefs.getBoolean(KEY_WP81_START_BACKGROUND_DRIFT, false)

    fun setWP81StartBackgroundDrift(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_START_BACKGROUND_DRIFT, enabled) }
    }

    /**
     * Whether tiles the user painted are shown in the accent instead, so the Start
     * background can be seen through them.
     *
     * The colours themselves are left where they are - this hides them, it does not
     * unset them - so turning it back off restores the wall exactly as it was.
     */
    fun getWP81HideTileColors(): Boolean =
        prefs.getBoolean(KEY_WP81_HIDE_TILE_COLORS, false)

    fun setWP81HideTileColors(hidden: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_HIDE_TILE_COLORS, hidden) }
    }

    /**
     * Whether a tile says *how much* is waiting rather than merely that something is.
     *
     * On by default, because it is what Windows Phone did and it is strictly more than the
     * dot tells you. Off returns the mark to the corner, for anyone who wants the wall to
     * be a wall of colour rather than a wall of numbers.
     */
    fun getWP81TileCounts(): Boolean = prefs.getBoolean(KEY_WP81_TILE_COUNTS, true)

    fun setWP81TileCounts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_TILE_COUNTS, enabled) }
    }

    /**
     * How many cells the Start screen is wide.
     *
     * Four is the phone default; three makes every tile larger, since a cell is a share of
     * the width rather than a fixed size. Sizes are stored in cells, so a wall arranged at
     * four still reads at three - the tiles simply take more of the screen.
     */
    fun getWP81Columns(): Int = prefs.getInt(KEY_WP81_COLUMNS, 4)

    fun setWP81Columns(columns: Int) {
        prefs.edit { putInt(KEY_WP81_COLUMNS, columns) }
    }

    /**
     * The news feeds the News tile reads, by id.
     *
     * Unset means the default rather than none: a News tile that has never been given a
     * feed would otherwise sit there empty with no hint that it wants configuring.
     */
    fun getWP81NewsFeeds(): Set<String> =
        prefs.getStringSet(KEY_WP81_NEWS_FEEDS, null)
            ?: setOf(rocks.gorjan.gokixp.wp81.NewsSources.DEFAULT_ID)

    /**
     * Tiles the user has painted a colour of their own, by tile id.
     *
     * Absent means the accent, which is what almost every tile is - only the exceptions
     * are stored, so changing the accent still moves the whole wall except those.
     */
    fun getWP81TileColors(): Map<String, Int> {
        val raw = prefs.getString(KEY_WP81_TILE_COLORS, null) ?: return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            val color = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            parts[0] to color
        }.toMap()
    }

    /** Paints one tile, or hands it back to the accent with null. */
    fun setWP81TileColor(tileId: String, color: Int?) {
        val colors = getWP81TileColors().toMutableMap()
        if (color == null) colors.remove(tileId) else colors[tileId] = color
        prefs.edit {
            putString(KEY_WP81_TILE_COLORS, colors.entries.joinToString(";") { "${it.key}:${it.value}" })
        }
    }

    fun setWP81NewsFeeds(ids: Set<String>) {
        // A copy: SharedPreferences does not defensively copy the set it is handed, and a
        // caller mutating it afterwards would quietly change what was stored.
        prefs.edit { putStringSet(KEY_WP81_NEWS_FEEDS, ids.toSet()) }
    }

    fun setWP81StartBackgroundFocusX(focusX: Float) {
        prefs.edit { putFloat(KEY_WP81_START_BACKGROUND_FOCUS_X, focusX.coerceIn(0f, 1f)) }
    }

    fun setWP81StartBackground(assetPath: String?) {
        prefs.edit {
            if (assetPath == null) remove(KEY_WP81_START_BACKGROUND)
            else putString(KEY_WP81_START_BACKGROUND, assetPath)
        }
    }

    /** True when the WP8.1 shell uses the dark (black) background rather than the light one. */
    fun isWP81Dark(): Boolean = prefs.getBoolean(KEY_WP81_DARK, true)

    fun setWP81Dark(dark: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_DARK, dark) }
    }

    // ========== Resource Mapping Methods ==========
    // These methods centralize all theme-specific resource lookups

    /**
     * Gets the theme style resource ID for the given theme.
     */
    // Deliberately keyed on the theme, not its chrome: WP8.1 borrows Vista's window
    // chrome but needs its own style for the accent colour and the Segoe weights.
    fun getThemeStyleRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.style.Theme_GokiXP_Classic
        AppTheme.WindowsXP -> R.style.Base_Theme_GokiXP
        AppTheme.WindowsVista -> R.style.Theme_GokiXP_Vista
        AppTheme.WindowsPhone81 -> R.style.Theme_GokiXP_WP81
    }

    /**
     * Gets the taskbar layout resource ID for the given theme.
     */
    fun getTaskbarLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.taskbar_98
        DesktopChrome.XP -> R.layout.taskbar_xp
        DesktopChrome.VISTA -> R.layout.taskbar_vista
    }

    /**
     * Gets the start menu layout resource ID for the given theme.
     */
    fun getStartMenuLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.start_menu_98
        DesktopChrome.XP -> R.layout.start_menu_xp
        DesktopChrome.VISTA -> R.layout.start_menu_vista
    }

    /**
     * Gets the dialog content layout resource ID for the given theme.
     */
    fun getDialogLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.windows_dialog_content_98
        DesktopChrome.XP -> R.layout.windows_dialog_content_xp
        DesktopChrome.VISTA -> R.layout.windows_dialog_content_vista
    }

    /**
     * Gets the spinner item layout resource ID for the given theme.
     */
    fun getSpinnerItemLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.spinner_item_classic
        DesktopChrome.XP -> R.layout.spinner_item_xp
        DesktopChrome.VISTA -> R.layout.spinner_item_vista
    }

    /**
     * Gets the spinner dropdown layout resource ID for the given theme.
     */
    fun getSpinnerDropdownLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.spinner_dropdown_item_classic
        DesktopChrome.XP -> R.layout.spinner_dropdown_item_xp
        DesktopChrome.VISTA -> R.layout.spinner_dropdown_item_vista
    }

    fun getIELayout(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.layout.program_internet_explorer
        DesktopChrome.XP -> R.layout.program_internet_explorer
        DesktopChrome.VISTA -> R.layout.program_internet_explorer_7
    }



    fun getIEIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.ie6
        DesktopChrome.XP -> R.drawable.ie6
        DesktopChrome.VISTA -> R.drawable.ie7
    }

    fun getWindowsIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.windows_logo
        DesktopChrome.XP -> R.drawable.xp_logo
        DesktopChrome.VISTA -> R.drawable.logo_vista
    }



    fun getRegeditIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.regedit_icon_98
        DesktopChrome.XP -> R.drawable.regedit_icon_xp
        DesktopChrome.VISTA -> R.drawable.regedit_icon_vista
    }

    fun getSolitareIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.solitare_icon
        DesktopChrome.XP -> R.drawable.solitare_icon
        DesktopChrome.VISTA -> R.drawable.solitare_icon_vista
    }


    fun getWinampIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.winamp_icon_98
        DesktopChrome.XP -> R.drawable.winamp_icon_xp
        DesktopChrome.VISTA -> R.drawable.winamp_icon_xp
    }

    fun getWmpIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.wmp_98_icon
        DesktopChrome.XP -> R.drawable.wmp_xp_icon
        DesktopChrome.VISTA -> R.drawable.wmp_vista_icon
    }

    fun getPhotosIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.photos_98_icon
        DesktopChrome.XP -> R.drawable.photos_xp_icon
        DesktopChrome.VISTA -> R.drawable.photos_vista_icon
    }

    fun getMinesweeperIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.minesweeper_icon_98
        DesktopChrome.XP -> R.drawable.minesweeper_icon_xp
        DesktopChrome.VISTA -> R.drawable.minesweeper_icon_vista
    }


    fun getNotepadIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.notepad_icon_98
        DesktopChrome.XP -> R.drawable.notepad_icon_xp
        DesktopChrome.VISTA -> R.drawable.notepad_icon_vista
    }


    fun getClockIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.icon_clock_98
        DesktopChrome.XP -> R.drawable.icon_clock_xp
        DesktopChrome.VISTA -> R.drawable.icon_clock_vista
    }

    fun getMsnIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.msn_icon
        DesktopChrome.XP -> R.drawable.msn_icon
        DesktopChrome.VISTA -> R.drawable.msn_icon_vista
    }

    fun getMyComputerIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.my_computer_98_icon
        DesktopChrome.XP -> R.drawable.my_computer_xp_icon
        DesktopChrome.VISTA -> R.drawable.my_computer_vista_icon
    }

    fun getFileGenericIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.file_generic_98
        DesktopChrome.XP -> R.drawable.file_generic_xp
        DesktopChrome.VISTA -> R.drawable.file_generic_vista
    }

    fun getFileImageIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.file_image_98
        DesktopChrome.XP -> R.drawable.file_image_xp
        DesktopChrome.VISTA -> R.drawable.file_image_vista
    }


    fun getPDFImageIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.file_pdf_98
        DesktopChrome.XP -> R.drawable.file_pdf_xp
        DesktopChrome.VISTA -> R.drawable.file_pdf_vista
    }

    fun getFileAudioIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.file_audio_98
        DesktopChrome.XP -> R.drawable.file_audio_xp
        DesktopChrome.VISTA -> R.drawable.file_audio_vista
    }

    fun getFileVideoIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.file_video_98
        DesktopChrome.XP -> R.drawable.file_video_xp
        DesktopChrome.VISTA -> R.drawable.file_video_vista
    }

    fun getDriveFloppyIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.drive_floppy_98
        DesktopChrome.XP -> R.drawable.drive_floppy_xp
        DesktopChrome.VISTA -> R.drawable.drive_floppy_vista
    }

    fun getDriveLocalIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.drive_local_98
        DesktopChrome.XP -> R.drawable.drive_local_xp
        DesktopChrome.VISTA -> R.drawable.drive_local_vista
    }

    fun getDriveOpticalIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.drive_optical_98
        DesktopChrome.XP -> R.drawable.drive_optical_xp
        DesktopChrome.VISTA -> R.drawable.drive_optical_vista
    }

    fun getWmpLayout(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.layout.program_wmp_98
        DesktopChrome.XP -> R.layout.program_wmp_xp
        DesktopChrome.VISTA -> R.layout.program_wmp_vista
    }

    fun getMaximizeIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.win98_title_bar_maximize
        DesktopChrome.XP -> R.drawable.xp_title_bar_maximize
        DesktopChrome.VISTA -> R.drawable.vista_title_bar_maximize
    }

    fun getRestoreIcon(): Int = when (getSelectedTheme().chrome) {
        DesktopChrome.CLASSIC -> R.drawable.win98_title_bar_restore
        DesktopChrome.XP -> R.drawable.xp_title_bar_restore
        DesktopChrome.VISTA -> R.drawable.vista_title_bar_restore
    }

    /**
     * Gets the taskbar button layout resource ID for the given theme.
     */
    fun getTaskbarButtonLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.taskbar_button_98
        DesktopChrome.XP -> R.layout.taskbar_button_xp
        DesktopChrome.VISTA -> R.layout.taskbar_button_vista
    }

    /**
     * Gets the Windows Explorer layout resource ID for the given theme.
     */
    fun getWindowsExplorerLayoutRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.layout.windows_explorer_98
        DesktopChrome.XP -> R.layout.windows_explorer_xp
        DesktopChrome.VISTA -> R.layout.windows_explorer_vista
    }

    // ========== Icon Resource Mappings ==========

    /**
     * Gets the folder icon drawable resource ID for the given theme.
     */
    fun getFolderIconRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.drawable.folder_98
        DesktopChrome.XP -> R.drawable.folder_xp
        DesktopChrome.VISTA -> R.drawable.folder_vista
    }

    /**
     * Gets the recycle bin icon drawable resource ID for the given theme.
     * @param isEmpty Whether the recycle bin is empty
     */
    fun getRecycleBinIconRes(theme: AppTheme, isEmpty: Boolean): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.drawable.recycle_98
        DesktopChrome.XP -> R.drawable.recycle
        DesktopChrome.VISTA -> R.drawable.recycle_vista
    }

    /**
     * Gets the start button drawable resource ID for the given theme.
     */
    fun getStartButtonRes(theme: AppTheme): Int = when (theme.chrome) {
        DesktopChrome.CLASSIC -> R.drawable.start_98
        DesktopChrome.XP -> R.drawable.start
        DesktopChrome.VISTA -> R.drawable.start_vista
    }

    // ========== Font Resource Mappings ==========

    /**
     * Gets the primary font family resource ID for the given theme.
     */
    // Keyed on the theme, not the chrome: WP8.1 needs Segoe where Vista keeps Tahoma.
    fun getPrimaryFontRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.font.micross_font_family
        AppTheme.WindowsXP -> R.font.tahoma_font_family
        AppTheme.WindowsVista -> R.font.tahoma_font_family  // Use Tahoma for now, can be replaced with Segoe UI
        AppTheme.WindowsPhone81 -> R.font.segoe_wp_family
    }

    /**
     * Gets the bold font resource ID for the given theme.
     */
    fun getBoldFontRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.font.micross_block_bold
        AppTheme.WindowsXP -> R.font.tahoma
        AppTheme.WindowsVista -> R.font.tahoma  // Use Tahoma for now
        AppTheme.WindowsPhone81 -> R.font.segoeui_semibold
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
        val (trackRes, thumbRes) = when (theme.chrome) {
            DesktopChrome.XP -> R.drawable.scrollbar_track_xp to R.drawable.scrollbar_thumb_xp
            DesktopChrome.CLASSIC -> R.drawable.scrollbar_track_98 to R.drawable.win98_start_menu_border
            DesktopChrome.VISTA -> R.drawable.scrollbar_track_vista to R.drawable.scrollbar_thumb_vista
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
        const val KEY_WP81_ACCENT = "wp81_accent"
        const val KEY_WP81_DARK = "wp81_background_dark"
        const val KEY_WP81_START_BACKGROUND = "wp81_start_background"
        const val KEY_WP81_START_BACKGROUND_FOCUS_X = "wp81_start_background_focus_x"
        const val KEY_WP81_START_BACKGROUND_BLUR = "wp81_start_background_blur"
        const val KEY_WP81_START_BACKGROUND_DRIFT = "wp81_start_background_drift"
        const val KEY_WP81_NEWS_FEEDS = "wp81_news_feeds"
        const val KEY_WP81_TILE_COLORS = "wp81_tile_colors"
        const val KEY_WP81_HIDE_TILE_COLORS = "wp81_hide_tile_colors"
        const val KEY_WP81_TILE_COUNTS = "wp81_tile_counts"
        const val KEY_WP81_COLUMNS = "wp81_columns"
        const val KEY_WP81_HIDDEN_TILES = "wp81_hidden_tiles"

        /** WP8.1 shipped Cyan as the out-of-box accent. */
        val WP81_DEFAULT_ACCENT: Int = 0xFF1BA1E2.toInt()

        /**
         * The twenty accent colours Windows Phone 8.1 offered, in the order the
         * Settings > theme picker listed them.
         */
        val WP81_ACCENTS: List<Pair<String, Int>> = listOf(
            "Lime" to 0xFFA4C400.toInt(),
            "Green" to 0xFF60A917.toInt(),
            "Emerald" to 0xFF008A00.toInt(),
            "Teal" to 0xFF00ABA9.toInt(),
            "Cyan" to 0xFF1BA1E2.toInt(),
            "Cobalt" to 0xFF0050EF.toInt(),
            "Indigo" to 0xFF6A00FF.toInt(),
            "Violet" to 0xFFAA00FF.toInt(),
            "Pink" to 0xFFF472D0.toInt(),
            "Magenta" to 0xFFD80073.toInt(),
            "Crimson" to 0xFFA20025.toInt(),
            "Red" to 0xFFE51400.toInt(),
            "Orange" to 0xFFFA6800.toInt(),
            "Amber" to 0xFFF0A30A.toInt(),
            "Yellow" to 0xFFE3C800.toInt(),
            "Brown" to 0xFF825A2C.toInt(),
            "Olive" to 0xFF6D8764.toInt(),
            "Steel" to 0xFF647687.toInt(),
            "Mauve" to 0xFF76608A.toInt(),
            "Taupe" to 0xFF87794E.toInt(),
        )
        const val KEY_PLUS95_THEME = "plus95_theme"
        const val PLUS95_DEFAULT = "default"

        val PLUS95_THEMES: List<Plus95Theme> = listOf(
            Plus95Theme("architecture", "Architecture", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("baseball", "Baseball", 0xFFD0A870.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("cityscape", "Cityscape", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("dangerous_creatures", "Dangerous Creatures", 0xFF707070.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("falling_leaves", "Falling Leaves", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("fashion", "Fashion", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("garfield", "Garfield", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("geometry", "Geometry", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("golf", "Golf", 0xFFE0C8A0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("inside_your_computer", "Inside your Computer", 0xFFA8C8A8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("jazz", "Jazz", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("jungle", "Jungle", 0xFFB8A068.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("leonardo_da_vinci", "Leonardo da Vinci", 0xFFBFA59F.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("mystery", "Mystery", 0xFF687868.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("nature", "Nature", 0xFFD8C0A0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("rock_n_roll", "Rock 'n' Roll", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("sci_fi", "Sci-Fi", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("science", "Science", 0xFF8399B1.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("space", "Space", 0xFF809098.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("sports", "Sports", 0xFFB0E0A0.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("the_60s_usa", "The 60's USA", 0xFFD068D8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("the_golden_era", "The Golden Era", 0xFFB8C8B8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("travel", "Travel", 0xFF908070.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("tropical_interlude", "Tropical Interlude", 0xFFB0A888.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("underwater", "Underwater", 0xFF3868C8.toInt(), "busy.png", "menu.ogg", "start.ogg"),
        )

        const val CLASSIC_GRAY: Int = 0xFFD3CEC7.toInt()
    }
}
