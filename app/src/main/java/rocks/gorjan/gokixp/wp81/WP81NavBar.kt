package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
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
 * along with the shell's other commands, and the task switcher by holding back - both on
 * the key whose tap already means the nearest thing to them.
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

    /**
     * Holding the back key: the task switcher.
     *
     * The gesture the phone itself used, on the key it used. Back is where it belongs
     * because the switcher is what back means taken further - one press goes back a step,
     * a hold goes back to a whole app - and because it is the only one of the three keys
     * whose hold was not already spoken for. See [WP81RecentsView].
     */
    var onRecents: (() -> Unit)? = null

    private val backButton =
        button(R.drawable.wp81_nav_back, onHold = { onRecents?.invoke() }) { onBack?.invoke() }
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

    /**
     * The strip swallows whatever misses a key.
     *
     * Without this, a touch that landed on the black between the keys fell through to the
     * desktop underneath, which is still there behind the shell and still watching for the
     * hold that opens its own right-click menu - so holding the bottom bar of a Windows
     * Phone raised a Windows 95 context menu. The bar is a piece of the phone's hardware,
     * not a window onto the desktop: nothing behind it should hear a finger on it.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(
        iconRes: Int,
        /**
         * What a full second on this key means, or null for a key that only taps.
         *
         * Taken here rather than set by the caller afterwards, because a view has one
         * touch listener and [TiltEffect] is already using it - see [applyHold].
         */
        onHold: (() -> Unit)? = null,
        onClick: () -> Unit
    ): ImageView =
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
            if (onHold == null) TiltEffect.apply(this) else applyHold(this, onHold)
        }

    /**
     * A key that means one thing tapped and another held.
     *
     * Not [View.setOnLongClickListener], which the Start key uses: the framework's long
     * press fires at its own timeout and this key is pressed dozens of times a session as
     * plain back, so the threshold wants to be this shell's own decision rather than the
     * platform's. It is nonetheless set close to it. A second was tried first, on the
     * reasoning that a slow deliberate back press should never open the switcher by
     * accident, and it was simply too long: a hold that outlasts the user's certainty that
     * anything is going to happen reads as the phone having missed the press.
     *
     * The tilt is already on this view's one touch listener, so the timer rides along
     * inside it rather than replacing it - see [TiltEffect.apply].
     *
     * A completed hold spends the gesture: the release that follows is swallowed, so the
     * key does not also go back on the way out of a switcher it has just opened. That is
     * also why the tick is fired by hand here. A view that claims a long press is given
     * the shell's tick by the framework; nothing is being claimed here, because nothing
     * framework-side is involved, so the buzz that says the hold has landed has to be
     * asked for. See [Haptics].
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun applyHold(key: ImageView, onHold: () -> Unit) {
        var fired = false
        val timer = Runnable {
            fired = true
            Haptics.tap(key)
            onHold()
        }
        TiltEffect.apply(key) { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    view.postDelayed(timer, HOLD_MS)
                    false
                }
                // A finger that has wandered off the key is on its way somewhere else -
                // the system's own gesture strip is directly below these - and is no
                // longer holding anything.
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.y < 0 ||
                        event.x > view.width || event.y > view.height
                    ) view.removeCallbacks(timer)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(timer)
                    // Consuming the release means View.onTouchEvent never sees it, so the
                    // pressed state it set on the way down is cleared here instead.
                    if (fired) view.isPressed = false
                    fired
                }
                else -> false
            }
        }
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

        /** How long the back key has to be held to mean the switcher. See [applyHold]. */
        private const val HOLD_MS = 200L
    }
}
