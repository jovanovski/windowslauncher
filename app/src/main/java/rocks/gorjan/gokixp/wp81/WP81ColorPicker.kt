package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Picks a tile's colour, from the same twenty the phone's accent comes from.
 *
 * A wall where every tile is the accent is the Windows Phone default and the right one -
 * but the accent is also the only thing distinguishing one tile from another at a glance,
 * and on a full screen of them that is nothing at all. Letting a few be recoloured is how
 * the Start screen stops being a grid and starts being a place, and confining the choice
 * to the system's own palette is what keeps it looking like the OS rather than like a
 * theme somebody applied.
 */
@SuppressLint("ViewConstructor")
class WP81ColorPicker(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /** The chosen colour, or null for "whatever the accent is". */
    var onPicked: ((Int?) -> Unit)? = null

    private val heading = TextView(context)
    private val grid = LinearLayout(context)

    /**
     * The way back to the accent.
     *
     * A command rather than a swatch, and under the grid rather than in it: the twenty are
     * a set of colours to choose between, and "leave it alone" is not one of them - it is
     * the thing you do instead of choosing. Filled with the accent it stands for, so it
     * still shows what it will do.
     */
    private val defaultButton = TextView(context)

    /** Every swatch, against the colour it sets. */
    private val swatches = mutableListOf<Pair<View, Int>>()

    /** The colour the tile is on now, or null for the accent. */
    private var selected: Int? = null

    init {
        visibility = GONE
        isClickable = true

        heading.typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
        heading.textSize = 30f
        heading.text = "tile color"
        heading.includeFontPadding = false
        heading.setPadding(dp(22), dp(20), dp(22), dp(8))

        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(18), 0, dp(18), dp(10))
        buildSwatches()
        buildDefaultButton()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, wide())
            addView(grid, wide())
            // Lined up with the swatches: the grid's own padding plus a swatch's margin.
            addView(defaultButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(22), 0, dp(22), dp(28)) })
        }
        addView(
            ScrollView(context).apply {
                overScrollMode = OVER_SCROLL_NEVER
                addView(column, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        applyPalette(palette)
    }

    /** The twenty accents, four to a row, all the same size. */
    private fun buildSwatches() {
        var row: LinearLayout? = null
        for ((i, entry) in ThemeManager.WP81_ACCENTS.withIndex()) {
            if (i % COLUMNS == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, wide())
            }
            val (name, color) = entry
            val swatch = View(context).apply {
                contentDescription = name
                isClickable = true
                setOnClickListener { pick(color) }
                TiltEffect.apply(this)
            }
            swatches.add(swatch to color)
            paintSwatch(swatch, color)
            row?.addView(swatch, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
    }

    private fun buildDefaultButton() {
        defaultButton.text = "default"
        defaultButton.gravity = Gravity.CENTER
        defaultButton.textSize = 17f
        defaultButton.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        defaultButton.setPadding(dp(12), dp(16), dp(12), dp(16))
        defaultButton.isClickable = true
        defaultButton.setOnClickListener { pick(null) }
        TiltEffect.apply(defaultButton)
    }

    /**
     * A flat block of colour, with a white edge on the one the tile is wearing.
     *
     * White rather than the page's foreground: the border sits on a saturated block of
     * colour, not on the page, and white is the one edge that reads on all twenty of them
     * in either background - the same reason everything drawn on a tile is white.
     */
    private fun paintSwatch(swatch: View, color: Int) {
        swatch.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            if (color == selected) setStroke(dp(SELECTED_STROKE_DP), palette.onAccent())
        }
    }

    /** Filled with the accent it hands the tile back to, and bordered when that is where
     *  the tile already is. */
    private fun paintDefaultButton() {
        defaultButton.setTextColor(palette.onAccent())
        defaultButton.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(palette.accent)
            if (selected == null) setStroke(dp(SELECTED_STROKE_DP), palette.onAccent())
        }
    }

    private fun pick(color: Int?) {
        selected = color
        repaintSwatches()
        onPicked?.invoke(color)
        dismiss()
    }

    /** Opens on [current], so the colour the tile is already wearing is the marked one. */
    fun show(forLabel: String, current: Int?) {
        heading.text = forLabel.lowercase().ifEmpty { "tile color" }
        selected = current
        repaintSwatches()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        visibility = GONE
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    /**
     * Marks the colour the tile is on, and leaves every other one alone.
     *
     * The border is the whole of the signal. The swatches used to shrink back from the
     * chosen one as well, which on a grid of twenty read as a wall of gaps with one block
     * in it rather than as a set of colours with one of them picked.
     */
    private fun repaintSwatches() {
        for ((swatch, color) in swatches) paintSwatch(swatch, color)
        paintDefaultButton()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        heading.setTextColor(p.foreground)
        // The default button is drawn in the accent, so a change of accent repaints it.
        repaintSwatches()
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 4

        /** The edge that marks the colour in use. Heavy enough to see on a small swatch. */
        const val SELECTED_STROKE_DP = 4
    }
}
