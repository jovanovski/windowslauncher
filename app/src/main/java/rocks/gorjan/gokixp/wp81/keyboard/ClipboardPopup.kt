package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The rest of the clipboard, opened by holding the paste pill.
 *
 * A window rather than a panel, and it opens *downwards* over the keys rather than upwards
 * over the app - which is the opposite of what [AlternatesPopup] does, for the opposite
 * reason. That one shows a row under a finger that is still down, so it has to clear the
 * finger; this one is a list to read and choose from with a second, deliberate tap, and the
 * keyboard is the one rectangle on screen this is entitled to cover. It also means the list
 * can be as tall as the keys without any of the trouble that comes with drawing outside an
 * input method's own window.
 *
 * Deliberately not focusable. A focusable window opened from an input method takes the focus
 * away from the very text field the paste is meant to land in; this one only takes touches,
 * and a touch outside it puts it away.
 *
 * Ordinary views here, unlike the emoji grid and the GIFs, because there are at most twenty
 * rows of it and it is opened by a hold - there is no frame budget to defend on a list that
 * appears once every few minutes.
 */
internal class ClipboardPopup(
    private val context: Context,
    private var palette: WP81Palette
) {

    /** A clip was chosen. The window has already put itself away by the time this runs. */
    var onPicked: ((Clip) -> Unit)? = null

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val scroll = ScrollView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private val window = PopupWindow(scroll).apply {
        isFocusable = false
        isTouchable = true
        isOutsideTouchable = true
        setBackgroundDrawable(null)
        animationStyle = 0
    }

    /**
     * When it last went away, which is how tapping the mark a second time closes it.
     *
     * The obvious version - "showing? then dismiss" - does not work on its own, because an
     * outside-touchable window is already gone by the time the tap is finished: the press
     * lands on the bar, which is outside this, so this dismisses on the way down and the
     * finger comes up on a mark that now believes nothing is open and opens it again. What
     * actually distinguishes the second tap from a fresh one is that this shut a moment ago.
     */
    private var dismissedAt = 0L

    init {
        window.setOnDismissListener { dismissedAt = SystemClock.uptimeMillis() }
    }

    val isShowing: Boolean get() = window.isShowing

    fun applyPalette(p: WP81Palette) {
        palette = p
        scroll.setBackgroundColor(blend(GROUND_ALPHA))
    }

    /**
     * Opens the list under [anchor] - the suggestion bar - and over the keys.
     *
     * @param height how much room there is below the bar. The list takes what it needs up to
     *   that and scrolls beyond it, so a long history never pushes the window past the keys
     *   it is covering.
     */
    fun show(anchor: View, clips: List<Clip>, keyW: Float, height: Int) {
        if (clips.isEmpty() || keyW <= 0f || height <= 0) return
        build(clips, keyW)
        scroll.setBackgroundColor(blend(GROUND_ALPHA))

        val rowH = (keyW * ROW_HEIGHT).toInt()
        val wanted = (rowH * clips.size + (keyW * PAD).toInt() * 2).coerceAtMost(height)
        window.width = anchor.width
        window.height = wanted
        if (window.isShowing) window.dismiss()
        window.showAsDropDown(anchor, 0, 0, Gravity.START)
    }

    /**
     * Opens the list, or closes it if the same tap is what put it away.
     *
     * The mark on the bar is one control and this is what it does: a way in, and the way
     * back out of it, which is how the emoji key and `abc` behave one layer down.
     */
    fun toggle(anchor: View, clips: List<Clip>, keyW: Float, height: Int) {
        if (window.isShowing) {
            dismiss()
            return
        }
        if (SystemClock.uptimeMillis() - dismissedAt < REOPEN_MS) return
        show(anchor, clips, keyW, height)
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }

    private fun build(clips: List<Clip>, keyW: Float) {
        column.removeAllViews()
        val pad = (keyW * PAD).toInt()
        column.setPadding(0, pad, 0, pad)
        for (clip in clips) column.addView(row(clip, keyW), wide())
    }

    /**
     * One clip: what it says, and how long ago it was said.
     *
     * The age is what makes a list of near-identical fragments usable at all - two copies of
     * the same shape of thing are told apart by "just now" against "an hour ago" far more
     * reliably than by re-reading both. Masked clips are masked here exactly as they are on
     * the pill, and their age is not a secret.
     */
    private fun row(clip: Clip, keyW: Float): View {
        val text = TextView(context).apply {
            this.text = clip.display()
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keyW * TEXT)
            setTextColor(palette.foreground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val age = TextView(context).apply {
            this.text = ageOf(clip.at)
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keyW * AGE_TEXT)
            setTextColor(palette.foregroundSubtle)
            maxLines = 1
            includeFontPadding = false
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = (keyW * PAD * 2).toInt()
            setPadding(side, 0, side, 0)
            minimumHeight = (keyW * ROW_HEIGHT).toInt()
            addView(
                text,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                age,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = side }
            )
            isClickable = true
            setOnClickListener {
                KeyboardHaptics.key(it)
                // Put away first, so that what happens next happens against the field and not
                // underneath a window that is still on top of it.
                dismiss()
                onPicked?.invoke(clip)
            }
            TiltEffect.apply(this)
        }
    }

    /** How long ago, in the coarsest terms that still tell two entries apart. */
    private fun ageOf(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000L).toInt()
        return when {
            minutes < 1 -> "just now"
            minutes == 1 -> "1 min ago"
            minutes < 60 -> "$minutes min ago"
            minutes < 120 -> "1 hour ago"
            else -> "2 hours ago"
        }
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    private companion object {

        /**
         * The list's ground, and its measurements, all in key widths like the rest of the
         * keyboard. A row is deeper than a key is tall: it holds two sizes of text side by
         * side and is chosen from by reading rather than by aim.
         */
        /**
         * How long after closing a tap is read as the one that closed it. See [dismissedAt].
         *
         * A quarter of a second: longer than the press that dismissed it takes to finish, and
         * short enough that somebody who genuinely wants it back straight away can have it.
         */
        const val REOPEN_MS = 250L

        const val GROUND_ALPHA = 0.200f
        const val ROW_HEIGHT = 1.5f
        const val PAD = 0.22f
        const val TEXT = 0.38f
        const val AGE_TEXT = 0.28f
    }
}
