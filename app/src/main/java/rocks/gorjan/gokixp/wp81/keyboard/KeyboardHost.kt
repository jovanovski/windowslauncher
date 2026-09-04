package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Everything the input method puts on screen, in the three arrangements it has.
 *
 * A vertical stack of three things - a panel, the suggestion bar, and the keys - of which any
 * combination can be showing:
 *
 *  - **Typing.** Bar and keys. The ordinary keyboard.
 *  - **Emoji.** The panel alone, at the full height, with the keys and the bar out of the way.
 *  - **Searching emoji.** The panel *and* the keys, with the bar hidden. This is the one the
 *    arrangement exists for. An input method cannot type into a text field of its own, so a
 *    search box inside a panel that has replaced the keyboard is a box with no way to put
 *    anything in it - it looked like a search bar and did nothing when tapped. The keys have
 *    to come back underneath, and the panel has to shrink to make room for them.
 *
 * Visibility rather than adding and removing views: the panel holds three and a half thousand
 * emoji and a scroll position, and rebuilding that on every press of the comma key would be a
 * stutter every time.
 */
@SuppressLint("ViewConstructor")
internal class KeyboardHost(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    val bar = CandidateBar(context, palette)
    val keyboard = KeyboardView(context, palette)

    private var panel: View? = null

    init {
        orientation = VERTICAL
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        bar.applyPalette(p)
        keyboard.applyPalette(p)
    }

    /**
     * Shows [view] above the keys, or in place of them.
     *
     * @param withKeys true while the panel's own search box is being typed into, which is the
     *   only time both are up at once.
     */
    fun showPanel(view: View, withKeys: Boolean) {
        if (panel !== view) {
            panel?.let { removeView(it) }
            panel = view
            keyboard.hideAlternates()
            // First, so that it sits above the bar and the keys in the stack.
            addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        view.visibility = VISIBLE
        bar.visibility = GONE
        keyboard.visibility = if (withKeys) VISIBLE else GONE
    }

    fun hidePanel() {
        panel?.visibility = GONE
        bar.visibility = VISIBLE
        keyboard.visibility = VISIBLE
    }

    val panelShowing: Boolean get() = panel?.visibility == VISIBLE

    /**
     * Works out what a key measures before anything is measured, and tells the bar.
     *
     * The bar sizes its text and its glyphs in key widths and is laid out above the keys, so
     * it is measured first and cannot ask them. Both get the answer from the same function
     * rather than each computing it - see [KeyboardView.unitWidth].
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            // The reference column count, not the current layout's: the bar is the same
            // height whichever language is up, for the same reason the keys are.
            val unit = KeyboardView.verticalUnit(resources, width, KeyboardView.REFERENCE_COLUMNS)
            bar.setMetrics(unit, unit * KeyboardView.GAP)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
