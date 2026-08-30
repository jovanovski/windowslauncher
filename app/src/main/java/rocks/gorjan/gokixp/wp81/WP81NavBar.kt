package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import rocks.gorjan.gokixp.R

/**
 * The three capacitive keys along the bottom of every Windows Phone screen: back, Start,
 * and search.
 *
 * They are fixed, as the hardware's were. On Start back has nowhere to go and does
 * nothing, which is what it did on the phone too - a key that turns into a different
 * command depending on the page is a key you have to look at before pressing. Settings
 * is reached by holding Start, along with the shell's other commands.
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

    /** Editing a tile on Start: make a folder. */
    var onNewFolder: (() -> Unit)? = null

    /** Editing a tile in a folder: take it back out. */
    var onRemoveFromFolder: (() -> Unit)? = null

    /** Editing a tile: open its command list. */
    var onTileMenu: (() -> Unit)? = null

    /** Inside a folder, nothing selected: put another app in it. */
    var onAddApp: (() -> Unit)? = null

    /** Editing a tile: choose the colour it is painted in. */
    var onTileColor: (() -> Unit)? = null

    /**
     * Whether a tile is selected, and so whether the colour key has anything to act on.
     */
    var hasSelection: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            setMode(mode)
        }

    /**
     * What the key strip is currently for.
     *
     * The three hardware keys only make sense while the user is navigating. Once they are
     * arranging tiles or filling a folder, the strip is the natural place for the commands
     * that belong to *that* job - which is how WP8.1 used its own app bar.
     */
    enum class Mode {
        /** Navigating: the three hardware keys. */
        NORMAL,

        /** A tile on Start is selected. */
        EDIT_START,

        /** A tile inside a folder is selected. */
        EDIT_FOLDER,

        /** A folder page is open with nothing selected. */
        FOLDER
    }

    private val backButton = button(R.drawable.wp81_nav_back) { onBack?.invoke() }
    private val startButton = button(R.drawable.wp81_nav_windows) { onStart?.invoke() }
        .apply {
            isLongClickable = true
            setOnLongClickListener {
                // No tick of its own. Claiming a long press is what makes the framework
                // give the system's own, and one fired here as well was a second buzz on
                // top of it - see the note on the tap below.
                onStartLongPress?.invoke()
                true
            }
        }
    private val searchButton = button(R.drawable.wp81_nav_search) { onSearch?.invoke() }
    private val newFolderButton = button(R.drawable.wp81_nav_new_folder) { onNewFolder?.invoke() }
    private val removeButton = button(R.drawable.wp81_nav_remove) { onRemoveFromFolder?.invoke() }
    private val menuButton = button(R.drawable.wp81_handle_menu) { onTileMenu?.invoke() }
    private val addButton = button(R.drawable.wp81_nav_add) { onAddApp?.invoke() }
    private val colorButton = button(R.drawable.wp81_nav_color) { onTileColor?.invoke() }

    /** Every key there is, in no particular order: [setMode] decides what the strip shows. */
    private val allButtons = listOf(
        backButton, startButton, searchButton,
        newFolderButton, removeButton, menuButton, addButton, colorButton
    )

    var mode: Mode = Mode.NORMAL
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setMode(Mode.NORMAL)
        applyPalette(palette)
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        val shown = when (mode) {
            Mode.NORMAL -> listOf(backButton, startButton, searchButton)
            // No "done": back, or a tap anywhere, already ends editing, and a key that
            // only ever means "stop" is one the user has to learn for nothing.
            // Taking a tile off Start - unpinning it, or putting a widget away - moved
            // into the command list: both are things you do once and neither is worth a
            // key of its own, where recolouring is a thing you do repeatedly until it
            // looks right.
            Mode.EDIT_START -> listOfNotNull(
                newFolderButton,
                colorButton.takeIf { hasSelection },
                menuButton
            )
            // Just the command list inside a folder: "remove" lives in it, and a bar with
            // one command beside a menu holding one more is a menu with extra steps.
            Mode.EDIT_FOLDER -> listOf(menuButton)
            Mode.FOLDER -> listOf(addButton)
        }
        // Re-added rather than shown and hidden in place: a key's position in the strip
        // has to be its position in the list above, not the order the buttons happened to
        // be constructed in.
        //
        // A gap at each end as well as between the keys, so all the gaps are the same
        // size: keys pinned to the corners read as pulled apart, and the outer two sitting
        // in from the edge by as much as they stand off each other is what makes the strip
        // look spaced rather than justified. It also means one key lands in the middle
        // without a case of its own.
        removeAllViews()
        addView(spacer())
        for (key in shown) {
            addKey(key)
            addView(spacer())
        }
    }

    private fun addKey(key: View) {
        key.visibility = VISIBLE
        addView(key, LayoutParams(dp(HEIGHT_DP), LayoutParams.MATCH_PARENT))
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
                // key drawn on glass has nothing else to confirm it was hit. VIRTUAL_KEY is
                // the constant Android uses for exactly those, so this follows the system
                // haptics setting instead of vibrating regardless of it.
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
