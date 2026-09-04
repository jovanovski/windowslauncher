package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import pl.droidsonroids.gif.GifDrawable
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The field of GIFs that takes the emoji grid's place.
 *
 * A separate view rather than a mode of `EmojiPanel.GridView`, because almost nothing is
 * shared: emoji are text drawn in equal square cells and these are files fetched over the
 * network, of every shape there is, each one a running animation. What *is* shared - drawn
 * by hand on one canvas, scrolled and flung by hand - is shared because the reason holds
 * here too: a real view per cell, in an input method's window, on the path where a tap has
 * to be answered inside a frame.
 *
 * Laid out in columns of equal width and unequal length, each GIF placed in whichever column
 * is currently shortest. A GIF is as wide as it is and as tall as it is; forcing them into
 * squares crops the caption off half of them, which is usually the part that made the GIF
 * worth sending.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class GifGrid(context: Context, private var palette: WP81Palette) : View(context) {

    /** A GIF was tapped. The panel decides what that means. */
    var onPicked: ((Gif) -> Unit)? = null

    /**
     * One GIF and the box it was given.
     *
     * The drawable arrives later than the box does - that is the whole reason the API's own
     * width and height are carried on [Gif], so the grid can be laid out before a single byte
     * of it has been fetched and does not reflow under the thumb as the pictures land.
     */
    private class Cell(val gif: Gif, val box: RectF) {
        var picture: GifDrawable? = null
        var asked = false
    }

    private var cells: List<Cell> = emptyList()
    private var message: String? = null

    /**
     * Which set of cells is current.
     *
     * A preview is fetched over the network and handed back whenever it arrives, by which time
     * the grid may hold a different search entirely, or have been emptied on the way out of the
     * panel. Answers stamped with a number that is no longer this one are dropped - the same
     * trick `ContactFace` plays for a face that decodes after its row has been recycled.
     */
    private var generation = 0

    private var unitW = 0f
    private var gap = 0f

    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var contentHeight = 0f

    private var dragging = false
    private var downY = 0f
    private var downScroll = 0f
    private var downCell: Cell? = null
    private var highlighted: Cell? = null
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val face = Paint()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private val bodyFont = ResourcesCompat.getFont(context, R.font.segoeui_regular)

    /**
     * How a running GIF asks to be redrawn.
     *
     * Not the view itself as the callback, which is the obvious thing and does not work:
     * `View.invalidateDrawable` only acts on drawables `verifyDrawable` claims, and these are
     * not backgrounds or compound drawables of anything. So the callback is its own object,
     * and scheduling goes through the view's own message queue - which is also what stops the
     * animations dead when the view goes away, since the queue goes with it.
     */
    private val ticking = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = invalidate()

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            postDelayed(what, `when` - SystemClock.uptimeMillis())
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            removeCallbacks(what)
        }
    }

    init {
        isClickable = true
    }

    // ---------------------------------------------------------------- public surface

    fun configure(keyW: Float, gap: Float) {
        unitW = keyW
        this.gap = gap
        ink.textSize = keyW * MESSAGE_TEXT
        relayout()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    /**
     * Shows [items], from the top.
     *
     * Back to the top on purpose: these are the answer to a different question than the ones
     * before them, and keeping the scroll would open a new search halfway down it.
     */
    fun setItems(items: List<Gif>, emptyMessage: String) {
        release()
        generation++
        cells = items.map { Cell(it, RectF()) }
        message = if (items.isEmpty()) emptyMessage else null
        scrollToTop()
        relayout()
    }

    /** Nothing to show yet, and a word about why. */
    fun setMessage(text: String) {
        release()
        generation++
        cells = emptyList()
        message = text
        scrollToTop()
        relayout()
    }

    /**
     * Back to the first row, and a fling in progress abandoned with it.
     *
     * Stopping the scroller is the half that is easy to forget and the half that shows: an
     * offset set to zero underneath a fling that is still running is an offset the next frame
     * overwrites, so the list opens where it was left and then slides.
     */
    fun scrollToTop() {
        scroller.forceFinished(true)
        scrollOffset = 0f
        invalidate()
    }

    /**
     * Frees every decoded GIF.
     *
     * Worth being explicit about rather than left to the collector: a screenful of these is
     * a screenful of frame buffers, and an input method's process is long-lived and is
     * expected to stay small - it is loaded into every app on the phone that has a text box.
     */
    fun release() {
        generation++
        for (cell in cells) {
            cell.picture?.let {
                it.callback = null
                it.stop()
                it.recycle()
            }
            cell.picture = null
            cell.asked = false
        }
    }

    // ---------------------------------------------------------------- geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    /**
     * Places every cell into the shortest column, once.
     *
     * Cheap arithmetic over the whole list rather than anything incremental, because the list
     * is capped at what one search returns and this runs when a search lands or the panel
     * changes size, never while scrolling.
     */
    private fun relayout() {
        if (width <= 0 || unitW <= 0f || cells.isEmpty()) {
            contentHeight = 0f
            maxScroll = 0f
            invalidate()
            return
        }
        val columns = (width / (unitW * COLUMN_UNITS)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val columnW = (width - gap * (columns + 1)) / columns
        val heights = FloatArray(columns) { gap }

        for (cell in cells) {
            var shortest = 0
            for (c in 1 until columns) if (heights[c] < heights[shortest]) shortest = c
            // Clamped, because a GIF can be a strip four times as tall as it is wide, and one
            // of those in a column is the rest of the results pushed off the bottom of a panel
            // that is only a few hundred pixels deep to begin with.
            val ratio = (cell.gif.height.toFloat() / cell.gif.width.coerceAtLeast(1))
                .coerceIn(MIN_RATIO, MAX_RATIO)
            val top = heights[shortest]
            val left = gap + shortest * (columnW + gap)
            val cellH = columnW * ratio
            cell.box.set(left, top, left + columnW, top + cellH)
            heights[shortest] = top + cellH + gap
        }

        contentHeight = heights.max()
        maxScroll = (contentHeight - height).coerceAtLeast(0f)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        invalidate()
    }

    // ---------------------------------------------------------------- touch

    private fun cellAt(x: Float, yContent: Float): Cell? =
        cells.firstOrNull { it.box.contains(x, yContent) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = event.y
                downScroll = scrollOffset
                dragging = false
                downCell = cellAt(event.x, event.y + scrollOffset)
                highlighted = downCell
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = event.y - downY
                if (!dragging && kotlin.math.abs(dy) > touchSlop) {
                    dragging = true
                    highlighted = null
                }
                if (dragging) scrollOffset = (downScroll - dy).coerceIn(0f, maxScroll)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val flingV = -(velocityTracker?.yVelocity ?: 0f)
                    scroller.fling(
                        0, scrollOffset.toInt(), 0, flingV.toInt(),
                        0, 0, 0, maxScroll.toInt()
                    )
                    postInvalidateOnAnimation()
                } else {
                    val cell = cellAt(event.x, event.y + scrollOffset)
                    if (cell != null && cell === downCell) {
                        KeyboardHaptics.key(this)
                        onPicked?.invoke(cell.gif)
                    }
                }
                endTouch()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                endTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endTouch() {
        dragging = false
        downCell = null
        highlighted = null
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---------------------------------------------------------------- paint

    override fun onDraw(canvas: Canvas) {
        if (cells.isEmpty()) {
            drawMessage(canvas)
            return
        }
        for (cell in cells) {
            val top = cell.box.top - scrollOffset
            val bottom = cell.box.bottom - scrollOffset
            // A screen's worth either side counts as visible: it is what decides both which
            // GIFs are fetched and which of them are left running, and fetching only what is
            // strictly on screen means every flick lands on a panel of empty boxes.
            val near = bottom >= -height && top <= height * 2
            if (!near) {
                cell.picture?.stop()
                continue
            }
            request(cell)

            val picture = cell.picture
            if (picture == null) {
                face.color = blend(PLACEHOLDER_ALPHA)
                canvas.drawRect(cell.box.left, top, cell.box.right, bottom, face)
            } else {
                if (!picture.isRunning) picture.start()
                picture.setBounds(
                    cell.box.left.toInt(), top.toInt(),
                    cell.box.right.toInt(), bottom.toInt()
                )
                picture.draw(canvas)
            }

            // Under the finger the accent is drawn over the picture rather than behind it,
            // since there is nothing of the cell left showing to tint. Half-covered, so what
            // was tapped is still recognisable while the tap is being made.
            if (cell === highlighted) {
                face.color = ColorUtils.setAlphaComponent(palette.accent, PRESS_ALPHA)
                canvas.drawRect(cell.box.left, top, cell.box.right, bottom, face)
            }
        }
    }

    /** Fetches a cell's preview, once, and keeps it. */
    private fun request(cell: Cell) {
        if (cell.asked) return
        cell.asked = true
        val mine = generation
        GifSearch.preview(cell.gif.previewUrl) { bytes ->
            // See [generation]. A search typed one letter at a time is several of these
            // overlapping, and the panel can have been shut under all of them.
            if (bytes == null || mine != generation) return@preview
            cell.picture = try {
                GifDrawable(bytes).also {
                    it.callback = ticking
                    it.start()
                }
            } catch (e: Exception) {
                // Not every rendition is a GIF the decoder will take. One blank cell.
                null
            }
            invalidate()
        }
    }

    private fun drawMessage(canvas: Canvas) {
        val text = message ?: return
        if (unitW <= 0f) return
        ink.typeface = bodyFont
        ink.textSize = unitW * MESSAGE_TEXT
        ink.textAlign = Paint.Align.CENTER
        ink.color = palette.foregroundSubtle
        ink.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(
            text,
            width / 2f,
            height / 2f - (bounds.top + bounds.bottom) / 2f,
            ink
        )
    }

    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    private companion object {

        /**
         * How wide a column is, in key widths.
         *
         * Three, which on the ten-across English keyboard gives three columns and on the
         * twelve-across Macedonian one gives four - the same physical width either way, which
         * is the point of measuring it in keys rather than in columns.
         */
        const val COLUMN_UNITS = 3f
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 4

        /** How far from square a cell is allowed to get. See the note in `relayout`. */
        const val MIN_RATIO = 0.4f
        const val MAX_RATIO = 1.9f

        /** A cell with nothing in it yet: the search bar's own grey, so it reads as a slot. */
        const val PLACEHOLDER_ALPHA = 0.302f

        /** The accent laid over the picture under a finger, out of 255. */
        const val PRESS_ALPHA = 120

        const val MESSAGE_TEXT = 0.34f
    }
}
