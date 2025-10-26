package rocks.gorjan.gokixp.theme

/**
 * Interface for components that need to respond to theme changes.
 *
 * Components implementing this interface can be registered with MainActivity
 * to receive notifications when the theme changes.
 *
 * Example usage:
 * ```
 * class MyAdapter : RecyclerView.Adapter<ViewHolder>(), ThemeAware {
 *     private var currentTheme: AppTheme = AppTheme.WindowsXP
 *
 *     override fun onThemeChanged(theme: AppTheme) {
 *         currentTheme = theme
 *         notifyDataSetChanged()
 *     }
 * }
 * ```
 */
interface ThemeAware {
    /**
     * Called when the theme is changed.
     * Implementations should update their UI/state to reflect the new theme.
     *
     * @param theme The new theme that has been applied
     */
    fun onThemeChanged(theme: AppTheme)
}
