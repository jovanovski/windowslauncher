package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Windows Phone loop selector: one column of values, turned past a fixed centre.
 *
 * The control the phone set a time on. Not a dropdown and not a dialog with a wheel drawn
 * in it - a column of plain numbers in Segoe that you flick, which stops with one of them
 * in the middle. Three of these side by side is the time picker; three with different
 * values in is the countdown's.
 *
 * It draws rather than holding a view per value, and that is the whole design. The obvious
 * way to get a column that loops is to lay the values out enough times over that a flick
 * cannot reach an end - which for sixty minutes and a hard flick is a couple of thousand
 * TextViews, and a page with three columns on it that took several seconds to open. Here
 * the values are *positions*: the column has a scroll offset in pixels, the value at any
 * row is that row's number modulo the list, and the loop is arithmetic rather than
 * repetition. Nothing is built, nothing is measured, and there is no end to reach.
 *
 * Shell furniture rather than any one program's, on the same terms as [MetroPanorama]:
 * anything here that asks for a number out of a short list of them should ask this way.
 */
@SuppressLint("ViewConstructor")
class MetroLoopSelector(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /** Fired when the column settles on a value, with its index into what was set. */
    var onPicked: ((Int) -> Unit)? = null

    private var values: List<String> = emptyList()

    /** Row height in pixels. One turn of the column moves exactly this far. */
    private val rowHeight = dp(ROW_DP).toFloat()

    /**
     * Where the column is, in pixels.
     *
     * Value *i* is in the middle when this is `i * rowHeight`, and there is deliberately
     * no range: it runs as far in either direction as it is pushed, and which value that
     * lands on is worked out modulo the list. That is the loop.
     */
    private var offset = 0f

    private var selected = 0

    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, ROW_SP, resources.displayMetrics)
    }

    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var downY = 0f
    private var dragging = false

    /** A fling is running and the column still has to be brought onto a value afterwards. */
    private var snapAfterFling = false

    /** The settle itself is running, so its own end is not another settle. */
    private var settling = false

    private val configuration = ViewConfiguration.get(context)

    init {
        minimumHeight = (rowHeight * VISIBLE_ROWS).toInt()
        isClickable = true
    }

    /**
     * What the column holds, and which of it is showing.
     *
     * Cheap enough to call whenever the column changes job - hours to minutes, minutes to
     * seconds - because there is nothing to build: it is a list of strings and a number.
     */
    fun setValues(values: List<String>, selected: Int) {
        this.values = values
        this.selected = if (values.isEmpty()) 0 else selected.coerceIn(0, values.size - 1)
        offset = this.selected * rowHeight
        invalidate()
    }

    /** The value in the middle, as an index into what was last handed to [setValues]. */
    fun selectedIndex(): Int = selected

    /** Moves the column to [index] without the user having asked. Silent, like a seed. */
    fun setSelectedIndex(index: Int) {
        if (values.isEmpty()) return
        selected = index.coerceIn(0, values.size - 1)
        offset = selected * rowHeight
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty() || height == 0) return
        val centre = height / 2f
        // Only the rows that can actually be seen, however far the column has been pushed.
        val first = floor((offset - centre) / rowHeight).toInt()
        val last = ceil((offset + centre) / rowHeight).toInt()
        // Text is placed by its own middle rather than its baseline, so a row's value sits
        // in the row and not on the line under it.
        val baseline = -(ink.descent() + ink.ascent()) / 2f

        for (row in first..last) {
            val y = centre + (row * rowHeight - offset)
            val distance = abs(row * rowHeight - offset)
            val chosen = distance < rowHeight / 2f
            // The value in the middle is the one being chosen and says so; the rest are
            // dimmed rather than hidden, because being able to see what is coming is the
            // whole reason this reads as a dial.
            ink.color = if (chosen) palette.foreground else palette.foregroundSubtle
            ink.alpha = if (chosen) 255 else (DIM_ALPHA * 255).toInt()
            canvas.drawText(valueAt(row), width / 2f, y + baseline, ink)
        }
    }

    /** The value at a row number, which may be any integer. This is where the loop lives. */
    private fun valueAt(row: Int): String = values[Math.floorMod(row, values.size)]

    // ---------------------------------------------------------------- the gesture

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        val velocity = tracker ?: VelocityTracker.obtain().also { tracker = it }
        velocity.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A loop selector is nearly always inside a page that scrolls vertically
                // too - the alarm editor is one - and two vertical scrollers inside each
                // other is a fight the outer one wins, because it is the one that gets to
                // intercept. So the column claims the gesture at the first touch.
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                snapAfterFling = false
                settling = false
                dragging = false
                downY = event.y
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = event.y - downY
                if (!dragging && abs(moved) > configuration.scaledTouchSlop) dragging = true
                if (dragging) {
                    // Dragging down brings earlier values towards the middle, so the offset
                    // moves against the finger.
                    offset -= event.y - lastY
                    lastY = event.y
                    report(feedback = true)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    velocity.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val speed = velocity.yVelocity
                    if (abs(speed) > configuration.scaledMinimumFlingVelocity) fling(speed)
                    else settle()
                } else {
                    // A tap rather than a drag: the row that was tapped comes to the middle,
                    // which is what the phone did and what makes the ends reachable without
                    // a flick.
                    offset += event.y - height / 2f
                    settle()
                    performClick()
                }
                release()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                settle()
                release()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun release() {
        dragging = false
        tracker?.recycle()
        tracker = null
    }

    private fun fling(velocityY: Float) {
        snapAfterFling = true
        // Unbounded either way: the column has no ends, so a fling is only ever stopped by
        // its own friction.
        scroller.fling(
            0, offset.roundToInt(), 0, -velocityY.roundToInt(),
            0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2
        )
        postInvalidateOnAnimation()
    }

    /**
     * Brings the nearest value to the middle.
     *
     * A flick in this control does not end where the finger left it - it ends on a value,
     * because the thing being chosen is one of a set and half of one is not a time.
     */
    private fun settle() {
        val target = (offset / rowHeight).roundToInt() * rowHeight
        if (target == offset) {
            report(feedback = false)
            return
        }
        settling = true
        scroller.startScroll(
            0, offset.roundToInt(), 0, (target - offset).roundToInt(), SNAP_MS)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat()
            report(feedback = false)
            invalidate()
            postInvalidateOnAnimation()
            return
        }
        when {
            snapAfterFling -> {
                snapAfterFling = false
                settle()
            }
            settling -> {
                settling = false
                // Landed exactly, rather than a fraction of a pixel out after a scroll of
                // several thousand: the offset is what everything else is worked out from.
                offset = (offset / rowHeight).roundToInt() * rowHeight
                report(feedback = false)
                invalidate()
            }
        }
    }

    /**
     * Notices that a different value is in the middle.
     *
     * The tick is fired only under the finger. A fling crosses forty values in half a
     * second, and a phone buzzing forty times is not feedback.
     */
    private fun report(feedback: Boolean) {
        if (values.isEmpty()) return
        val index = Math.floorMod((offset / rowHeight).roundToInt(), values.size)
        if (index == selected) return
        selected = index
        if (feedback) Haptics.key(this)
        onPicked?.invoke(index)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** How tall one value is, and so how far one turn of the column moves. */
        const val ROW_DP = 56

        /** What a column asks for when nothing else decides: the value and one either side. */
        const val VISIBLE_ROWS = 3

        private const val ROW_SP = 32f

        private const val SNAP_MS = 220

        private const val DIM_ALPHA = 0.45f
    }
}
