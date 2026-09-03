package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The mark beside a choice, in the shape that says what kind of choice it is.
 *
 * Round is one of a set - Celsius or Fahrenheit, dark or light - where choosing this one
 * unchooses its neighbour. Square is a switch, which answers only for itself. The shape
 * carries the whole of that distinction, so a page of settings says which of its choices
 * are exclusive without a word about it.
 *
 * This lived inside the shell's own settings page, where it was the only place in the
 * phone that drew a choice properly: the News reader and the Weather app each had a plain
 * filled square standing in for both kinds, which said everything was somehow on. It is
 * here now because a shell whose programs are built out of its furniture should not have
 * three answers to what a checkbox looks like.
 *
 * Off is an outline either way. On fills it: the round one with a dot inside its ring, the
 * way a radio button has shown it since Windows had them, the square one solid with a tick.
 */
object MetroMarker {

    fun drawable(context: Context, palette: WP81Palette, round: Boolean, on: Boolean): Drawable {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val frame = GradientDrawable().apply {
            shape = if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            // The square fills; the circle keeps its middle for the dot.
            setColor(if (on && !round) palette.accent else Color.TRANSPARENT)
            setStroke(dp(2), if (on) palette.accent else palette.foregroundSubtle)
        }
        if (!on) return frame

        val inner: Drawable = if (round) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
        } else {
            ResourcesCompat.getDrawable(context.resources, R.drawable.ic_check_windows, null)
                ?.mutate()?.apply { setTint(palette.onAccent()) } ?: return frame
        }
        val inset = if (round) dp(5) else dp(3)
        return LayerDrawable(arrayOf(frame, inner)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    /** How big the mark is drawn, wherever one appears. */
    const val SIZE_DP = 20

    /** Between the mark and what it is a mark for. */
    const val GAP_DP = 14
}

/**
 * A line with a choice on it: the mark, and what it is a mark for.
 *
 * Two kinds, and [round] picks which. A round one is a radio - clicking it chooses it and
 * nothing else, and clicking the one already chosen does nothing, because there is nothing
 * for it to do. A square one is a switch and flips.
 *
 * The row states nothing about its own margins. A settings page indents its rows to the
 * page gutter and a panorama section does not, and both are right where they are; what has
 * to be the same everywhere is the mark and the distance from it to the words.
 */
@SuppressLint("ViewConstructor")
class MetroChoiceRow(
    context: Context,
    private var palette: WP81Palette,
    text: String,
    private val round: Boolean = false
) : LinearLayout(context) {

    /**
     * Fired when the user works the row, with what it now says.
     *
     * Not fired by [set], which only shows a fact - a page binding a dozen of these from
     * what is saved would otherwise save each of them straight back.
     */
    var onPicked: ((Boolean) -> Unit)? = null

    private val marker = View(context)
    private val label = TextView(context)
    private var on = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(11), 0, dp(11))
        isClickable = true
        setOnClickListener {
            // A radio already chosen has nothing to do. A switch always has.
            if (round && on) return@setOnClickListener
            Haptics.tap(it)
            on = if (round) true else !on
            repaint()
            onPicked?.invoke(on)
        }
        TiltEffect.apply(this)

        addView(marker, LayoutParams(dp(MetroMarker.SIZE_DP), dp(MetroMarker.SIZE_DP)))

        label.text = text
        label.textSize = LABEL_SP
        label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        addView(label, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(MetroMarker.GAP_DP)
        })

        repaint()
    }

    fun isOn(): Boolean = on

    /** Shows what the setting actually is. Silent - see [onPicked]. */
    fun set(value: Boolean) {
        if (on == value) return
        on = value
        repaint()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        repaint()
    }

    private fun repaint() {
        marker.background = MetroMarker.drawable(context, palette, round, on)
        label.setTextColor(palette.foreground)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val LABEL_SP = 17f
    }
}
