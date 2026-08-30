package rocks.gorjan.gokixp.wp81

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import androidx.annotation.ColorInt
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * The resolved Windows Phone 8.1 colour scheme.
 *
 * WP8.1 has two independent knobs, both live user settings:
 *  - an **accent**, one of twenty fixed colours, used for tiles, headers and controls;
 *  - a **background**, either Dark (black) or Light (white), which flips all chrome.
 *
 * Everything is applied programmatically rather than through theme attributes so both
 * can change without recreating the activity.
 */
data class WP81Palette(
    @get:ColorInt val accent: Int,
    @get:ColorInt val background: Int,
    @get:ColorInt val foreground: Int,
    /** Secondary text - app-list section letters, status detail, tile back content. */
    @get:ColorInt val foregroundSubtle: Int,
    /** Chrome that sits behind the accent, e.g. the app-list jump-list's empty letters. */
    @get:ColorInt val inactive: Int,
    val isDark: Boolean
) {
    /** Foreground to draw *on top of* an accent fill. Accent tiles are always white-on-accent. */
    @ColorInt
    fun onAccent(): Int = Color.WHITE

    /**
     * The accent's opposite on the colour wheel.
     *
     * For the one mark that has to be seen rather than read: the unread dot sits on the
     * accent itself, and in white it was one more white thing on a tile whose glyph, label
     * and text are all white. Half a turn round the wheel is the furthest a hue can get
     * from its background while still belonging to the same scheme.
     *
     * Saturation and brightness are floored rather than kept: a muted accent would hand
     * back an equally muted opposite, which is the one thing this colour must not be.
     */
    @ColorInt
    fun accentOpposite(): Int = opposite(accent)

    companion object {

        /** Half a turn round the colour wheel from [color]. See [accentOpposite]. */
        @ColorInt
        fun opposite(@ColorInt color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + 180f) % 360f
            hsv[1] = hsv[1].coerceAtLeast(0.65f)
            hsv[2] = hsv[2].coerceAtLeast(0.95f)
            return Color.HSVToColor(hsv)
        }

        fun from(themeManager: ThemeManager): WP81Palette =
            of(themeManager.getWP81Accent(), themeManager.isWP81Dark())

        fun of(@ColorInt accent: Int, isDark: Boolean): WP81Palette =
            if (isDark) {
                WP81Palette(
                    accent = accent,
                    background = Color.BLACK,
                    foreground = Color.WHITE,
                    foregroundSubtle = Color.argb(153, 255, 255, 255),
                    inactive = Color.argb(51, 255, 255, 255),
                    isDark = true
                )
            } else {
                WP81Palette(
                    accent = accent,
                    background = Color.WHITE,
                    foreground = Color.BLACK,
                    foregroundSubtle = Color.argb(153, 0, 0, 0),
                    inactive = Color.argb(38, 0, 0, 0),
                    isDark = false
                )
            }
    }
}

/**
 * Gives a text field built in code the scheme's own text, caret and selection.
 *
 * Everything but the caret was already being set by hand. The caret was not, so it came
 * from the activity's theme - which is one of the desktop Windows themes, where it is
 * black. On a black Start screen that is a field with no cursor in it at all: the text
 * types, but there is nothing to show where.
 *
 * Drawn in the foreground colour rather than the accent, because it has to be legible
 * against the background under every one of the twenty accents, which a caret in a dark
 * accent on a black page is not.
 */
fun WP81Palette.applyToField(field: EditText) {
    field.setTextColor(foreground)
    field.setHintTextColor(foregroundSubtle)
    val width = (CARET_WIDTH_DP * field.resources.displayMetrics.density).toInt()
    field.setTextCursorDrawable(GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(foreground)
        // The caret is stretched to the line's height; only the width is read from here.
        setSize(width, width)
    })
    // The band behind selected text, which comes from the same theme and has the same
    // problem. Faint, because the text on top of it is being read.
    field.highlightColor = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
}

/** How thick the caret is. Two device pixels was hairline on a modern screen. */
private const val CARET_WIDTH_DP = 2f
