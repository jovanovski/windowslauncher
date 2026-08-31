package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The strip along the bottom of a Metro program.
 *
 * Everything one of these programs can be told to do is here and nothing is anywhere else:
 * no menu bar, no toolbar, no buttons among the content. A row of open rings in the middle
 * for the handful of things reached for most, and behind the dots at the end of that row,
 * a list of the rest in words.
 *
 * It is the same piece of furniture as [WP81SecondaryBar], which is the shell's own strip
 * over Start, doing the same job one level in - same height, same rings, same distance
 * apart. Its black is its own rather than the palette's: this surface sits over a page
 * rather than being part of one, and has to stay legible whatever theme or accent is
 * behind it.
 */
@SuppressLint("ViewConstructor")
class MetroAppBar(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    /** One command in the list behind the dots. */
    data class Item(val label: String, val action: () -> Unit)

    /**
     * What that list holds, asked for each time it opens rather than set once.
     *
     * A command list is written in the present tense - "archive" or "restore", "draw
     * three" or "draw one" - and which of those it says depends on the state of the thing
     * at the moment the dots are tapped.
     *
     * Left unset where there is nothing behind the dots, and then there are no dots: a
     * strip with one command on it is a strip with one command on it, and an ellipsis that
     * opens nothing is a button that appears to be broken.
     */
    var menu: (() -> List<Item>)? = null
        set(value) {
            field = value
            syncOverflow()
        }

    private val menuColumn = LinearLayout(context)
    private val group = LinearLayout(context)
    private var overflow: View? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(BAR_COLOUR)
        // The strip is the bottom of the program: a tap on it is a tap on the strip, not on
        // whatever of the page is behind it.
        isClickable = true

        menuColumn.orientation = VERTICAL
        menuColumn.visibility = GONE
        menuColumn.setPadding(0, dp(6), 0, dp(6))
        addView(menuColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        group.orientation = HORIZONTAL
        group.gravity = Gravity.CENTER
        val row = FrameLayout(context)
        row.addView(group, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEIGHT_DP)))
    }

    /**
     * Adds a ring to the row, and hands it back so the caller can keep hold of it.
     *
     * A command that says something about the state it is in - a mode that is on, a move
     * there is none of left - is one the program has to be able to reach again; see
     * [setCommandOn] and [setCommandEnabled].
     */
    fun addCommand(iconAsset: String, onTap: () -> Unit): ImageView {
        val button = ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            setImageDrawable(SvgIcon.fromAsset(context, iconAsset))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                onTap()
            }
            TiltEffect.apply(this)
        }
        addToGroup(button)
        return button
    }

    /**
     * Marks a command as the one currently in force.
     *
     * The accent, filled, against the white outlines of the commands that are merely
     * available: a mode that is on is a fact about the program, and a ring that only
     * differed by being a slightly brighter white would be a fact nobody could see.
     */
    fun setCommandOn(button: ImageView, on: Boolean) {
        val ink = if (on) palette.accent else Color.WHITE
        button.backgroundTintList = ColorStateList.valueOf(ink)
        button.imageTintList = ColorStateList.valueOf(ink)
    }

    /** Dims a command that has nothing to do, and stops it answering. */
    fun setCommandEnabled(button: ImageView, enabled: Boolean) {
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** The three dots, in a ring like every other command, kept at the end of the row. */
    private fun overflowButton(): View {
        val holder = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                if (menuColumn.visibility == VISIBLE) closeMenu() else openMenu()
            }
        }
        // Drawn rather than typed: an ellipsis character is a row of full stops sitting on
        // the baseline, and what the phone had was three round dots centred in the button.
        val dots = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(3) { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }, LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply {
                if (i > 0) marginStart = dp(DOT_GAP_DP)
            })
        }
        holder.addView(dots, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        TiltEffect.apply(holder)
        return holder
    }

    /** Puts a ring in the row, behind whatever is already in it. */
    private fun addToGroup(button: View) {
        overflow?.let { group.removeView(it) }
        group.addView(button, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
            if (group.childCount > 0) marginStart = dp(GAP_DP)
        })
        syncOverflow()
    }

    /**
     * Keeps the dots at the end of the row, or off it.
     *
     * Taken out and put back as each command arrives, because what is behind them is the
     * tail of the list and has to be read last - and the commands are added one at a time
     * in the order the program wants them read.
     */
    private fun syncOverflow() {
        overflow?.let { group.removeView(it) }
        if (menu == null) return
        val dots = overflow ?: overflowButton().also { overflow = it }
        group.addView(dots, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
            if (group.childCount > 0) marginStart = dp(GAP_DP)
        })
    }

    fun openMenu() {
        val items = menu?.invoke().orEmpty()
        if (items.isEmpty()) return
        menuColumn.removeAllViews()
        for (item in items) menuColumn.addView(menuRow(item))
        menuColumn.visibility = VISIBLE
        // Each row swings down about its own top edge, on a stagger. Same as the shell's
        // own command lists - see WP81ContextMenu.
        for (i in 0 until menuColumn.childCount) {
            val row = menuColumn.getChildAt(i)
            row.cameraDistance = 8000f * resources.displayMetrics.density
            row.pivotX = 0f
            row.pivotY = 0f
            row.rotationX = -90f
            row.alpha = 0f
            row.animate().rotationX(0f).alpha(1f)
                .setStartDelay(i * STAGGER_MS)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Whether the list is up, which is what the back key asks before it leaves the page. */
    fun isMenuOpen(): Boolean = menuColumn.visibility == VISIBLE

    fun closeMenu(): Boolean {
        if (menuColumn.visibility != VISIBLE) return false
        menuColumn.visibility = GONE
        menuColumn.removeAllViews()
        return true
    }

    private fun menuRow(item: Item): View =
        TextView(context).apply {
            // Lowercase, like every command list in this shell.
            text = item.label.lowercase()
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                item.action()
            }
            TiltEffect.apply(this)
        }

    fun applyPalette(p: WP81Palette) {
        palette = p
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The strip's own near-black, as against the page's background. */
        const val BAR_COLOUR = 0xFF1F1F1F.toInt()

        /** The row of rings, at the shell's own strip height. */
        const val HEIGHT_DP = WP81SecondaryBar.HEIGHT_DP

        private const val BUTTON_DP = 44

        /** Between the rings. Wide, so they read as a row of commands and not as a block. */
        private const val GAP_DP = 28

        /** How far a glyph sits inside its ring. */
        private const val GLYPH_INSET_DP = 5

        private const val DOT_DP = 5
        private const val DOT_GAP_DP = 4

        private const val STAGGER_MS = 30L

        /** What is left of a command with nothing to do. */
        private const val DISABLED_ALPHA = 0.35f
    }
}
