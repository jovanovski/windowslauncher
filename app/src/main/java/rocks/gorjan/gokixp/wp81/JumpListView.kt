package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The letter grid Windows Phone 8.1 showed when you tapped a section header in the app
 * list (or the People hub).
 *
 * Letters that have apps behind them are filled with the accent and are tappable;
 * letters with nothing under them are drawn flat and inert. Squares fade and scale in
 * on a short per-square stagger, which is what gives the grid its cascade.
 */
@SuppressLint("ViewConstructor")
class JumpListView(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    var onLetterPicked: ((Char) -> Unit)? = null

    /** The globe at the end of the grid: search, rather than a letter. */
    var onSearchPicked: (() -> Unit)? = null

    private val grid = GridLayout(context)
    private val squares = mutableListOf<TextView>()
    private val searchSquare = android.widget.ImageView(context)
    private var available: Set<Char> = emptySet()

    /** '#' first, matching the bucket AppListView files non-alphabetic names under. */
    private val letters: List<Char> = listOf('#') + ('a'..'z').toList()

    init {
        isClickable = true   // swallow taps so they don't fall through to the app list
        grid.columnCount = COLUMNS
        grid.useDefaultMargins = false
        addView(grid, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        buildSquares()
        applyPalette(palette)
    }

    /**
     * The grid is measured against the screen rather than drawn at a fixed size.
     *
     * Twenty-eight squares in four columns is seven rows, and whichever of the two runs
     * out first - the width or the height - is what sets the square. That is what makes it
     * fill the display on any phone instead of sitting in the middle of it looking small
     * on a tall screen and cropped on a short one.
     */
    private fun squareSize(): Int {
        val gap = dp(GAP_DP)
        val metrics = resources.displayMetrics
        val rows = (letters.size + 1 + COLUMNS - 1) / COLUMNS
        val across = (metrics.widthPixels - dp(MARGIN_DP) * 2 - gap * (COLUMNS - 1)) / COLUMNS
        val down = (metrics.heightPixels - dp(MARGIN_DP) * 2 - gap * (rows - 1)) / rows
        return minOf(across, down).coerceAtLeast(dp(24))
    }

    private fun buildSquares() {
        val size = squareSize()
        val gap = dp(GAP_DP)
        for (letter in letters) {
            val square = TextView(context).apply {
                text = letter.toString()
                // Bottom left, as the letters are on the list's own headers and as a name
                // is on a tile. Everything on this platform is set into a corner.
                gravity = Gravity.BOTTOM or Gravity.START
                textSize = 20f
                setPadding(dp(8), 0, 0, dp(4))
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
                setTextColor(Color.WHITE)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                }
                setOnClickListener {
                    if (letter.uppercaseChar() in available || letter in available) {
                        onLetterPicked?.invoke(letter.uppercaseChar())
                    }
                }
                TiltEffect.apply(this)
            }
            squares.add(square)
            grid.addView(square)
        }

        // And one more at the end, which is not a letter: everything the alphabet cannot
        // narrow down is what search is for, and the grid is where you already are when
        // you find that out.
        searchSquare.setImageResource(R.drawable.wp81_glyph_globe)
        searchSquare.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        searchSquare.setPadding(dp(14), dp(14), dp(14), dp(14))
        searchSquare.isClickable = true
        searchSquare.setOnClickListener { onSearchPicked?.invoke() }
        searchSquare.layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
        }
        TiltEffect.apply(searchSquare)
        grid.addView(searchSquare)
    }

    fun setAvailableLetters(letters: Set<Char>) {
        available = letters
        repaint()
    }

    private fun repaint() {
        for ((i, square) in squares.withIndex()) {
            val letter = this.letters[i]
            val enabled = letter.uppercaseChar() in available || letter in available
            // Outlined in the accent over black, matching the headers in the list behind.
            // A letter with nothing under it keeps the square and loses the colour, so the
            // alphabet stays whole and only some of it is live.
            square.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(2), if (enabled) palette.accent else palette.inactive)
            }
            square.setTextColor(if (enabled) Color.WHITE else palette.foregroundSubtle)
            square.isClickable = enabled
        }
        searchSquare.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(2), palette.accent)
        }
        searchSquare.imageTintList =
            android.content.res.ColorStateList.valueOf(Color.WHITE)
    }

    /** Staggered fade-and-scale, the way WP8.1 revealed the grid. */
    fun playEntrance() {
        for ((i, square) in (squares + listOf<View>(searchSquare)).withIndex()) {
            square.alpha = 0f
            square.scaleX = ENTRANCE_SCALE
            square.scaleY = ENTRANCE_SCALE
            square.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(i * STAGGER_MS)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        repaint()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLUMNS = 4
        private const val STAGGER_MS = 12L
        private const val ENTRANCE_SCALE = 0.6f

        /** Between the squares, and around the whole grid. */
        private const val GAP_DP = 6
        private const val MARGIN_DP = 20
    }
}
