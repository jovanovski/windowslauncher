package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import rocks.gorjan.gokixp.R

/**
 * The strip of commands that belongs to what the user is *doing*, as against where they
 * are - WP8.1's app bar, which is what this is on the Start screen.
 *
 * It exists because the three keys below it must not change. They stand in for capacitive
 * hardware, and hardware does not rearrange itself: a key that means "back" until a tile
 * is held and something else afterwards is a key you have to look at before pressing.
 * Holding a tile now leaves them alone and slides this up instead.
 *
 * ```
 *   |  tiles, undisturbed            |
 *   |        ( o )      ( ... )      |   <- this, sliding up over them
 *   |    <-        [#]        Q      |   <- the nav bar, never changing
 * ```
 *
 * The slide comes out from underneath the nav bar, which is drawn after it and so hides
 * it while it is parked. Nothing on screen moves to make room: the bar is drawn over the
 * bottom of the wall, and the wall does not know it is there. A tile the bar happens to
 * cover is one dismissal away, and re-laying out the whole Start screen every time a tile
 * was held would be a far louder thing than the bar itself.
 *
 * Its near-black and white rings are IE's app bar, not the palette's: this is a surface
 * that sits *over* the shell rather than part of it, and the commands on it have to stay
 * legible over any accent or a white theme. See [applyPalette]'s absence.
 */
class WP81SecondaryBar(context: Context) : LinearLayout(context) {

    /** Editing a tile: choose the colour it is painted in. */
    var onTileColor: (() -> Unit)? = null

    /** Editing a tile: open its command list. */
    var onTileMenu: (() -> Unit)? = null

    /** Inside a folder, nothing selected: put another app in it. */
    var onAddApp: (() -> Unit)? = null

    /**
     * What the bar is currently for. [Mode.NONE] is the usual case - navigating, with
     * nothing held - and takes the bar off the screen.
     */
    enum class Mode {
        /** Nothing to command: the bar is away. */
        NONE,

        /** A tile on Start is selected. */
        EDIT_START,

        /** A tile inside a folder is selected. */
        EDIT_FOLDER,

        /** A folder page is open with nothing selected. */
        FOLDER
    }

    private val colorButton = circleButton(R.drawable.wp81_nav_color) { onTileColor?.invoke() }
    private val menuButton = circleButton(R.drawable.wp81_handle_menu) { onTileMenu?.invoke() }
    private val addButton = circleButton(R.drawable.wp81_nav_add) { onAddApp?.invoke() }

    var mode: Mode = Mode.NONE
        private set

    /** Whether the bar is out, as against parked behind the keys. */
    private var out = false

    init {
        orientation = HORIZONTAL
        // Centred rather than spread: three hardware keys are spread because they are the
        // whole width of the phone and always the same three. Two commands spread to the
        // corners read as two unrelated buttons; together in the middle they read as the
        // pair of things this tile can be told to do.
        gravity = Gravity.CENTER
        setBackgroundColor(BAR_COLOUR)
        // A tap on the bar is a tap on the bar. Without this it would fall through and
        // dismiss the very tile whose commands are being offered.
        isClickable = true
        visibility = GONE
        translationY = parkedY()
    }

    /**
     * Chooses the commands, and with them whether the bar is on screen at all.
     *
     * [hasSelection] is separate from the mode because both editing commands act on the
     * selected tile: with nothing selected there is nothing for the bar to say, and an
     * empty strip is worse than no strip.
     */
    fun setMode(mode: Mode, hasSelection: Boolean) {
        val shown = when (mode) {
            Mode.NONE -> emptyList()
            // "New folder" is gone: folders are made by holding one tile over another,
            // which is how the phone did it and needs no key. Unpinning and hiding live in
            // the command list - each is a thing you do once, where recolouring is a thing
            // you do repeatedly until it looks right.
            Mode.EDIT_START -> if (hasSelection) listOf(colorButton, menuButton) else emptyList()
            // Inside a folder the tile's own colour is the folder's business, not the
            // wall's, so only the command list is offered.
            Mode.EDIT_FOLDER -> if (hasSelection) listOf(menuButton) else emptyList()
            Mode.FOLDER -> listOf(addButton)
        }
        if (shown.isEmpty()) {
            this.mode = mode
            slideAway()
            return
        }
        // Nothing to do only if the bar is already out with these very commands on it:
        // the same mode with the bar parked - a tile reselected after being let go - still
        // has to bring it back.
        if (out && mode == this.mode && shown == currentButtons()) return
        this.mode = mode

        // Re-added rather than shown and hidden in place, so a command's position on the
        // bar is its position in the list above rather than the order the buttons happened
        // to be constructed in.
        removeAllViews()
        for ((i, button) in shown.withIndex()) {
            addView(button, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                if (i > 0) marginStart = dp(GAP_DP)
            })
        }
        slideOut()
    }

    private fun currentButtons(): List<View> = (0 until childCount).map { getChildAt(it) }

    // ---------------------------------------------------------------- the slide

    private fun slideOut() {
        if (out) return
        out = true
        visibility = VISIBLE
        animate().cancel()
        animate()
            .translationY(0f)
            .setDuration(SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            // Cleared explicitly: a ViewPropertyAnimator keeps the end action it was last
            // given, so without this the hide's "go away" would fire at the end of a show.
            .withEndAction(null)
            .start()
    }

    private fun slideAway() {
        if (!out) return
        out = false
        animate().cancel()
        animate()
            .translationY(parkedY())
            .setDuration(SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!out) visibility = GONE }
            .start()
    }

    /**
     * Where the bar waits: exactly its own height lower, which puts it inside the nav
     * bar's band. The nav bar is added after it and so covers it there.
     *
     * Measured from the constant rather than from [getHeight], which is zero until the
     * first layout - and the first hold on a tile can come before one.
     */
    private fun parkedY(): Float = dp(HEIGHT_DP).toFloat()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * A white ring, open in the middle, with a white glyph inside it.
     *
     * The same button IE puts on its own app bar - see wp81_appbar_circle. Unfilled: there
     * is nothing behind it but the bar, so the ring alone is the button.
     */
    private fun circleButton(iconRes: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    companion object {
        const val HEIGHT_DP = 62

        /** IE's app bar black, which is not the palette's and never was. */
        private const val BAR_COLOUR = 0xFF1F1F1F.toInt()

        private const val BUTTON_DP = 44
        private const val GAP_DP = 28

        /** How far the glyph sits inside its ring. */
        private const val GLYPH_INSET_DP = 5

        private const val SLIDE_MS = 200L
    }
}
