package rocks.gorjan.gokixp.theme

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.MainActivity

/**
 * Manages custom icon mappings for each theme.
 *
 * BACKWARD COMPATIBILITY:
 * - Uses existing SharedPreferences keys: "custom_icons_98", "custom_icons_xp"
 * - Preserves existing JSON format for custom icon mappings
 * - User custom icons are fully preserved during migration
 */
class IconManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    /**
     * Saves custom icon mappings for a specific theme.
     * Uses the same SharedPreferences keys as legacy code.
     *
     * @param theme The theme to save icons for
     * @param icons Map of package names to icon file paths
     */
    fun saveCustomIcons(theme: AppTheme, icons: Map<String, String>) {
        val key = getIconKey(theme)
        val json = gson.toJson(icons)
        prefs.edit {
            putString(key, json)
        }
    }

    /**
     * Loads custom icon mappings for a specific theme.
     * Reads from the same SharedPreferences keys as legacy code.
     *
     * @param theme The theme to load icons for
     * @return Map of package names to icon file paths, empty if none exist
     */
    fun loadCustomIcons(theme: AppTheme): Map<String, String> {
        val key = getIconKey(theme)
        val json = prefs.getString(key, "{}") ?: "{}"
        return try {
            gson.fromJson(json, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Gets a custom icon path for a specific package in a theme.
     *
     * @param theme The theme to check
     * @param packageName The package name to look up
     * @return The custom icon path, or null if not set
     */
    fun getCustomIcon(theme: AppTheme, packageName: String): String? {
        return loadCustomIcons(theme)[packageName]
    }

    /**
     * Sets a custom icon for a specific package in a theme.
     *
     * @param theme The theme to set the icon for
     * @param packageName The package name
     * @param iconPath The path to the custom icon file
     */
    fun setCustomIcon(theme: AppTheme, packageName: String, iconPath: String) {
        val icons = loadCustomIcons(theme).toMutableMap()
        icons[packageName] = iconPath
        saveCustomIcons(theme, icons)
    }

    /**
     * Clears a custom icon for a specific package in a theme.
     *
     * @param theme The theme to clear the icon from
     * @param packageName The package name
     */
    fun clearCustomIcon(theme: AppTheme, packageName: String) {
        val icons = loadCustomIcons(theme).toMutableMap()
        icons.remove(packageName)
        saveCustomIcons(theme, icons)
    }

    /**
     * Clears all custom icons for a specific theme.
     *
     * @param theme The theme to clear all icons from
     */
    fun clearAllCustomIcons(theme: AppTheme) {
        saveCustomIcons(theme, emptyMap())
    }

    /**
     * Gets the number of custom icons set for a theme.
     *
     * @param theme The theme to check
     * @return The number of custom icons
     */
    fun getCustomIconCount(theme: AppTheme): Int {
        return loadCustomIcons(theme).size
    }

    /**
     * Checks if a package has a custom icon in a theme.
     *
     * @param theme The theme to check
     * @param packageName The package name
     * @return true if a custom icon is set
     */
    fun hasCustomIcon(theme: AppTheme, packageName: String): Boolean {
        return loadCustomIcons(theme).containsKey(packageName)
    }

    /**
     * Gets the SharedPreferences key for storing custom icons for a theme.
     * Uses the same keys as legacy code for backward compatibility.
     *
     * @param theme The theme
     * @return The SharedPreferences key
     */
    private fun getIconKey(theme: AppTheme): String = when (theme) {
        AppTheme.WindowsClassic -> KEY_CUSTOM_ICONS_98
        AppTheme.WindowsXP -> KEY_CUSTOM_ICONS_XP
        AppTheme.WindowsVista -> KEY_CUSTOM_ICONS_VISTA
    }

    companion object {
        private const val KEY_CUSTOM_ICONS_98 = "custom_icons_98"
        private const val KEY_CUSTOM_ICONS_XP = "custom_icons_xp"
        private const val KEY_CUSTOM_ICONS_VISTA = "custom_icons_vista"
    }
}
