package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import rocks.gorjan.gokixp.R

/**
 * The three capacitive keys along the bottom of every Windows Phone screen: back, Start,
 * and search.
 *
 * They are fixed, as the hardware's were, and they are the same three everywhere - on
 * Start, in a folder, with a tile held. A key that turns into a different command
 * depending on the page is a key you have to look at before pressing, and these stand in
 * for keys you could find without looking. On Start back has nowhere to go and does
 * nothing, which is what it did on the phone too. Settings is reached by holding Start,
 * along with the shell's other commands.
 *
 * Commands that belong to what the user is doing - recolouring a tile, filling a folder -
 * are not here: they slide up on [WP81SecondaryBar], which is the app bar WP8.1 put above
 * these keys for exactly that.
 *
 * On real hardware these sat below the display; here they are drawn as a bar the shell
 * reserves space for, which is why the floating-window container's bottom margin is
 * re-based onto [HEIGHT_DP] when the WP8.1 shell is active.
 */
@SuppressLint("ViewConstructor")
class WP81NavBar(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null

    /** Holding the Start key: the commands that belong to the shell itself. */
    var onStartLongPress: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null

    private val backButton = button(R.drawable.wp81_nav_back) { onBack?.invoke() }
    private val startButton = button(R.drawable.wp81_nav_windows) { onStart?.invoke() }
        .apply {
            isLongClickable = true
            setOnLongClickListener {
                // No tick of its own: claiming a long press is what makes the framework
                // give the shell's, and one fired here as well was a second buzz on top of
                // it. See Haptics.
                onStartLongPress?.invoke()
                true
            }
        }
    private val searchButton = button(R.drawable.wp81_nav_search) { onSearch?.invoke() }

    private val allButtons = listOf(backButton, startButton, searchButton)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        // A gap at each end as well as between the keys, so all the gaps are the same
        // size: keys pinned to the corners read as pulled apart, and the outer two sitting
        // in from the edge by as much as they stand off each other is what makes the strip
        // look spaced rather than justified. It also means Start lands in the middle
        // without a case of its own.
        addView(spacer())
        for (key in allButtons) {
            addView(key, LayoutParams(dp(HEIGHT_DP), LayoutParams.MATCH_PARENT))
            addView(spacer())
        }
        applyPalette(palette)
    }

    /** The gap that does the spreading. */
    private fun spacer(): View =
        View(context).apply { layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(iconRes: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            // Sized explicitly rather than by padding, so the glyph does not shrink when
            // the bar height or the system inset changes.
            scaleType = ImageView.ScaleType.FIT_CENTER
            // No padding under the glyph: the strip sits on the bottom edge of the screen,
            // and a key floating with a gap beneath it reads as a row of buttons on a bar
            // rather than as the bottom of the phone. What was under them is now above.
            val inset = ((HEIGHT_DP - GLYPH_DP) / 2f * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, 0)
            isClickable = true
            setOnClickListener {
                // The capacitive keys these stand in for buzzed under the finger, and a
                // key drawn on glass has nothing else to confirm it was hit. The shell's
                // one tick, so a key feels like every other thing that answers a touch.
                Haptics.tap(it)
                onClick()
            }
            TiltEffect.apply(this)
        }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        val tint = ColorStateList.valueOf(p.foreground)
        for (b in allButtons) b.imageTintList = tint
    }

    companion object {
        const val HEIGHT_DP = 64

        /** Glyph edge. Deliberately large - these are the only navigation on screen. */
        private const val GLYPH_DP = 46
    }
}
