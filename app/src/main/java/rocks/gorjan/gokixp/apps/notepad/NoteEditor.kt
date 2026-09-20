package rocks.gorjan.gokixp.apps.notepad

import android.content.Context
import android.text.InputFilter
import android.text.Spanned
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatEditText
import rocks.gorjan.gokixp.Helpers
import kotlin.math.abs

/**
 * The note's text, with a way to hear its caret move and a finger for its to-do boxes.
 *
 * EditText has no listener for the caret, and the markdown styling needs one: a mark is
 * only shown while the caret is at it. A property set after construction rather than
 * anything passed in, because TextView places the caret while it is still being built -
 * before a subclass's own fields exist - and a callback read then has to be able to be null.
 *
 * Inflated from program_notepad.xml, so it keeps the two-argument constructor.
 */
class NoteEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onSelection: (() -> Unit)? = null

    /** The box a finger went down on, while the touch is still only a tap. */
    private var pressed: IntRange? = null
    private var down: MotionEvent? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke()
    }

    /**
     * A tap on a to-do's box ticks it.
     *
     * The tap is kept from the text altogether - no caret dropped beside the box, no
     * keyboard - so a list can be ticked off while it is only being read. A finger that
     * moves before it lifts was starting a scroll, not ticking anything, and the text is
     * handed the press it missed so that the scroll carries on from there.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                val box = boxUnder(event.x, event.y)
                if (box != null) {
                    pressed = box
                    down = MotionEvent.obtain(event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val first = down ?: return super.onTouchEvent(event)
                if (abs(event.x - first.x) <= slop && abs(event.y - first.y) <= slop) return true
                super.onTouchEvent(first)
                release()
            }
            MotionEvent.ACTION_UP -> {
                val box = pressed
                if (box != null) {
                    release()
                    NoteMarkdown.toggle(text ?: return true, box)
                    Helpers.performHapticFeedback(context)
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (pressed != null) {
                release()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun release() {
        pressed = null
        down?.recycle()
        down = null
    }

    /**
     * The to-do box under a finger at [x], [y], if there is one.
     *
     * Reached for generously to the left, where there is only the page's margin, and
     * barely at all to the right, where the item's own words start: a tap meant to put the
     * caret at the start of an item should put it there.
     */
    private fun boxUnder(x: Float, y: Float): IntRange? {
        val layout = layout ?: return null
        val boxes = NoteMarkdown.boxes(text ?: return null)
        if (boxes.isEmpty()) return null
        val lx = x - totalPaddingLeft + scrollX
        val ly = y - totalPaddingTop + scrollY
        val density = resources.displayMetrics.density
        for (box in boxes) {
            val line = layout.getLineForOffset(box.first)
            if (ly < layout.getLineTop(line) || ly >= layout.getLineBottom(line)) continue
            val left = layout.getPrimaryHorizontal(box.first) - REACH_LEFT_DP * density
            val right = layout.getPrimaryHorizontal(box.last) + REACH_RIGHT_DP * density
            if (lx in left..right) return box
        }
        return null
    }

    private companion object {
        const val REACH_LEFT_DP = 16f
        const val REACH_RIGHT_DP = 4f
    }
}

/**
 * Return, in a list: the next line starts with the list's next mark.
 *
 * A filter on what is about to go in rather than a watcher on what has, so that the mark
 * goes in with the new line as one edit - and the keyboard, which puts the caret after what
 * it typed, puts it after the mark. Added after the fact, the keyboard's own caret lands
 * between the new line and the mark, and the next word is typed in front of the bullet.
 *
 * Ending a list takes a mark off rather than putting one in, which a filter cannot do: it
 * decides only what goes in. So there the return is swallowed and [onEnd] takes the mark off
 * straight afterwards.
 */
internal class ListReturn(private val onEnd: (Int, String) -> Unit) : InputFilter {

    override fun filter(
        source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        if (end - start != 1 || source[start] != '\n') return null
        return when (val next = NoteFormat.onReturn(dest, dstart, dend)) {
            is NoteFormat.Continue.Next -> "\n" + next.mark
            is NoteFormat.Continue.End -> {
                onEnd(next.start, next.mark)
                ""
            }
            null -> null
        }
    }
}
