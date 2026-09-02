package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 context menu.
 *
 * Long-pressing anything in WP8.1 dims the whole screen and swings a short list of plain
 * lowercase commands in from the top edge, each item rotating about its own top edge on a
 * slight stagger. There is no chrome, no icons and no nesting - just the verbs, left
 * aligned in Segoe.
 *
 * Positioned near whatever was pressed, but clamped so it never runs off either end of
 * the screen.
 */
@SuppressLint("ViewConstructor")
class WP81ContextMenu(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    data class Item(val label: String, val action: () -> Unit)

    private val scrim = View(context)
    private val panel = LinearLayout(context)

    /**
     * Names what the commands apply to.
     *
     * The menu dims the whole screen, which takes the pressed item down with it - so
     * without this there is nothing on screen saying which app or tile is about to be
     * renamed or uninstalled.
     */
    private val heading = TextView(context)

    init {
        visibility = GONE
        isClickable = true

        scrim.setBackgroundColor(SCRIM)
        scrim.setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        heading.typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        // Said here as well as in applyPalette: a menu built and shown without the palette
        // ever changing under it - the browser's is one - would otherwise put its heading
        // up in whatever colour the window's theme happens to leave on a bare TextView.
        heading.setTextColor(palette.accent)
        heading.textSize = 12f
        heading.maxLines = 1
        heading.ellipsize = android.text.TextUtils.TruncateAt.END
        heading.letterSpacing = 0.08f
        heading.setPadding(dp(18), dp(14), dp(18), dp(6))

        panel.orientation = LinearLayout.VERTICAL
        addView(panel, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            marginStart = dp(28)
            marginEnd = dp(28)
        })
    }

    /**
     * Shows [items] near vertical position [anchorY] (in this view's coordinates).
     */
    fun show(title: String?, items: List<Item>, anchorY: Float) {
        panel.removeAllViews()
        if (!title.isNullOrBlank()) {
            // Small caps: a label, not another command to be read as tappable.
            heading.text = title.uppercase()
            panel.addView(heading)
        }
        for (item in items) panel.addView(buildRow(item))

        // A show can land while a dismiss is still fading, which is exactly what one
        // command list opening another does: the row dismisses this view and then runs an
        // action that shows it again. Without cancelling, the fade's end action arrives a
        // moment later and hides the list that has just been put up.
        animate().cancel()
        generation++

        // Made visible *before* measuring: a GONE view is never laid out, so checking its
        // width first would see 0 forever and the menu would never appear.
        visibility = VISIBLE
        alpha = 1f
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(140).start()

        if (width == 0 || height == 0) post { position(anchorY) } else position(anchorY)
    }

    /** Places the panel near the anchor, clamped inside the screen, then animates it in. */
    private fun position(anchorY: Float) {
        val available = (width - dp(56)).coerceAtLeast(dp(120))
        panel.measure(
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val panelHeight = panel.measuredHeight
        val maxTop = (height - panelHeight - dp(16)).coerceAtLeast(dp(16))
        (panel.layoutParams as LayoutParams).topMargin =
            anchorY.toInt().coerceIn(dp(16), maxTop)
        panel.requestLayout()
        playEntrance()
    }

    private fun buildRow(item: Item): View = TextView(context).apply {
        // WP8.1 command lists are lowercase; it is a big part of the look.
        text = item.label.lowercase()
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textSize = 17f
        setTextColor(palette.foreground)
        setPadding(dp(18), dp(15), dp(18), dp(15))
        isClickable = true
        setOnClickListener {
            dismiss()
            item.action()
        }
        TiltEffect.apply(this)
    }

    /** Each row swings down about its own top edge, slightly staggered. */
    private fun playEntrance() {
        panel.setBackgroundColor(palette.background)
        for (i in 0 until panel.childCount) {
            val row = panel.getChildAt(i)
            row.cameraDistance = 8000f * resources.displayMetrics.density
            row.pivotX = 0f
            row.pivotY = 0f
            row.rotationX = -90f
            row.alpha = 0f
            row.animate()
                .rotationX(0f)
                .alpha(1f)
                .setStartDelay(i * STAGGER_MS)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        // Which list this fade belongs to. A cancelled ViewPropertyAnimator still runs its
        // end action, so cancelling alone is not enough to stop an outgoing fade from
        // hiding the list that replaced it.
        val fading = generation
        animate().alpha(0f)
            .setDuration(130)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (fading != generation) return@withEndAction
                visibility = GONE
                alpha = 1f
            }
            .start()
    }

    /**
     * Counts the lists that have been put up.
     *
     * A fade-out that finishes after a newer list has opened belongs to the old one and
     * has no business hiding anything.
     */
    private var generation = 0

    fun isShowing(): Boolean = visibility == VISIBLE

    fun applyPalette(p: WP81Palette) {
        palette = p
        panel.setBackgroundColor(p.background)
        heading.setTextColor(p.accent)
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            if (child !== heading) (child as? TextView)?.setTextColor(p.foreground)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCRIM = 0xB3000000.toInt()
        private const val STAGGER_MS = 30L
    }
}
