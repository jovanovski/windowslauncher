package rocks.gorjan.gokixp.theme

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * Font style options for theme fonts.
 */
enum class FontStyle {
    Normal,
    Bold
}

/**
 * Manages font resources for different themes.
 * Provides centralized font loading and application.
 */
class FontManager(private val context: Context) {

    /**
     * Gets the appropriate font for the given theme and style.
     *
     * @param theme The theme to get the font for
     * @param style The font style (Normal or Bold)
     * @return The Typeface for the theme, or null if not found
     */
    fun getFontForTheme(theme: AppTheme, style: FontStyle = FontStyle.Normal): Typeface? {
        val fontRes = when {
            theme is AppTheme.WindowsClassic && style == FontStyle.Bold -> R.font.micross_block_bold
            theme is AppTheme.WindowsClassic -> R.font.micross_block
            theme is AppTheme.WindowsXP && style == FontStyle.Bold -> R.font.tahoma  // No separate bold
            theme is AppTheme.WindowsXP -> R.font.tahoma
            else -> R.font.tahoma  // Default fallback
        }
        return ResourcesCompat.getFont(context, fontRes)
    }

    /**
     * Gets the font family resource ID for the given theme.
     * This can be used directly in XML or for programmatic font family setting.
     *
     * @param theme The theme to get the font family for
     * @return The font family resource ID
     */
    fun getFontFamilyRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.font.micross_font_family
        AppTheme.WindowsXP -> R.font.tahoma_font_family
        AppTheme.WindowsVista -> R.font.tahoma_font_family
    }

    /**
     * Applies the theme font to a TextView.
     *
     * @param textView The TextView to apply the font to
     * @param theme The theme to use for font selection
     * @param style The font style to apply
     */
    fun applyThemeFont(textView: TextView, theme: AppTheme, style: FontStyle = FontStyle.Normal) {
        textView.typeface = getFontForTheme(theme, style)
    }

    /**
     * Applies the theme font to multiple TextViews at once.
     *
     * @param textViews The TextViews to apply the font to
     * @param theme The theme to use for font selection
     * @param style The font style to apply
     */
    fun applyThemeFontToViews(textViews: List<TextView>, theme: AppTheme, style: FontStyle = FontStyle.Normal) {
        val typeface = getFontForTheme(theme, style)
        textViews.forEach { it.typeface = typeface }
    }
}
