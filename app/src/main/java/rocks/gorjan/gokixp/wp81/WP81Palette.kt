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
 * The shell's text box, wherever one is: white, with black in it.
 *
 * Windows Phone's TextBox did not follow the light/dark setting the way the rest of the
 * page did. It was a filled white rectangle under both, because a field is a thing you
 * type *into* - a hole cut in the page rather than a run of words on it - and the fill is
 * what says so. The browser's address bar had been drawn that way by hand, and so had the
 * app list's search and the music app's; the rename dialog had not, which is how a shell
 * with four text boxes ended up with two kinds of them.
 *
 * Only the colours are here. How large a field is, what it is called, where it sits and
 * what its keyboard does are the caller's business and differ every time; this is the part
 * that must not.
 *
 * The caret is set explicitly for the same reason it always was: left alone it comes from
 * the activity's theme, which is one of the desktop Windows themes, and lands wherever
 * that happens to put it.
 */
fun WP81Palette.applyToField(field: EditText) {
    // White, with an edge. The fill alone is enough on a dark page and disappears on a
    // light one - a white box on a white background is not a box - and the platform's own
    // text box was a fill with a border round it under both settings for that reason.
    //
    // The padding is put back afterwards: a View takes its padding from whatever
    // background it is given, so setting one here would otherwise flatten whatever the
    // caller had already set.
    val left = field.paddingLeft
    val top = field.paddingTop
    val right = field.paddingRight
    val bottom = field.paddingBottom
    field.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
        setStroke(
            (BORDER_DP * field.resources.displayMetrics.density).toInt(),
            BORDER_ON_WHITE
        )
    }
    field.setPadding(left, top, right, bottom)
    field.setTextColor(Color.BLACK)
    field.setHintTextColor(HINT_ON_WHITE)
    field.setTextCursorDrawable(caret(field, Color.BLACK))
    field.highlightColor = selection()
}

/**
 * The same, for text that is not in a box at all.
 *
 * A note fills its page and is the page: there is no field around it, so it is set in the
 * page's own colours and only the caret and the selection band need saying. Everything
 * about [applyToField] would be wrong here - a white rectangle the size of the screen is
 * not a text box, it is a different theme.
 */
fun WP81Palette.applyToPageText(field: EditText) {
    field.setTextColor(foreground)
    field.setHintTextColor(foregroundSubtle)
    field.setTextCursorDrawable(caret(field, foreground))
    field.highlightColor = selection()
}

private fun caret(field: EditText, @ColorInt color: Int): GradientDrawable {
    val width = (CARET_WIDTH_DP * field.resources.displayMetrics.density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        // The caret is stretched to the line's height; only the width is read from here.
        setSize(width, width)
    }
}

/** The band behind selected text. Faint, because the text on top of it is being read. */
private fun WP81Palette.selection(): Int =
    Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))

/** How thick the caret is. Two device pixels was hairline on a modern screen. */
private const val CARET_WIDTH_DP = 2f

/** Grey enough to read as a prompt on the field's white, dark enough to read at all. */
private val HINT_ON_WHITE = Color.argb(140, 0, 0, 0)

/** The field's own edge, and how thick it is. Grey under either setting. */
private val BORDER_ON_WHITE = Color.argb(255, 130, 130, 130)
private const val BORDER_DP = 2f
