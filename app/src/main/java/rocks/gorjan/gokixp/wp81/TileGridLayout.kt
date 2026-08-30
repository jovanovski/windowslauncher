package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The Windows Phone 8.1 Start screen tile grid.
 *
 * WP8.1 lays tiles out on a fixed four-column grid of small cells. A medium tile is a
 * 2x2 block, a wide tile spans the whole row, and tiles are packed in order into the
 * first rectangle that fits - which is why a small tile will slide up to fill the gap
 * a medium one left beside it, rather than starting a new row.
 *
 * ### Metrics
 * Taken from the WVGA (480x800) reference layout, where a small tile is 99px, medium
 * 210, wide 432x210, the gap 12 and the outer margin 24. Those numbers are internally
 * exact - `2*99 + 12 = 210` and `4*99 + 3*12 = 432`, with `432 + 2*24 = 480` - so they
 * are re-expressed here as fractions of the available width and scale to any screen:
 *
 * ```
 *   outer margin = 5.0%  of width
 *   gap          = 2.5%  of width
 *   small cell   = (width - 2*margin - 3*gap) / 4  ~= 20.6% of width
 * ```
 */
class TileGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * How many small cells fit across.
     *
     * Four is WP8.1's phone default and what every metric here is derived from. Three is
     * offered as well, for the same reason the phone offered six: a cell is a share of the
     * width, so fewer of them is bigger tiles rather than a wider screen. The packer, the
     * cell size and the resize drag all read this, so changing it is the whole change.
     */
    var columns = COLUMNS
        set(value) {
            val next = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            if (field == next) return
            field = next
            requestLayout()
        }

    /**
     * The width the margins and gaps are worked out from, or 0 for this view's own.
     *
     * Set by the wall to the phone's shorter side, so a tile and the air around it are the
     * same size whichever way up the phone is being held. See [columnsFor].
     */
    var metricBasis: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var cellPx = 0
    private var gapPx = 0
    private var marginPx = 0

    /**
     * Empty height kept under the last row, on top of what the tiles need.
     *
     * A selected tile's resize handle is centred on its bottom-right corner and so reaches
     * below the row it is on. On the last row that is below the grid itself, and a parent
     * clips a child to the child's own bounds - so the wall grows by the overhang rather
     * than the handle being cut in half by the end of it.
     *
     * Placement ignores this: it is height the grid has, not height it lays anything out
     * in, so nothing moves and the room only shows once the wall is scrolled to the end.
     */
    var bottomReservePx = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Resolved top-left cell of each child, parallel to child index. */
    private val placements = mutableListOf<Placement>()

    data class Placement(val col: Int, val row: Int, val cols: Int, val rows: Int)

    // ---------------------------------------------------------------- inline expansion

    /**
     * A folder opened in place, and the room made for it.
     *
     * Windows Phone's Live Folders did not push a page: the wall parted under the folder
     * and its contents appeared in the gap. That is what this is - one full-width child
     * laid out in a band between two rows, with everything below it moved down by exactly
     * the band's height.
     *
     * The band is not packed with the tiles. It is a child of this layout so that it
     * scrolls and clips with the wall, but the packer steps over it: it occupies no cells,
     * it occupies a gap between rows.
     */
    var bandView: View? = null
        private set

    /** The first row that has to move down to make the gap. */
    private var bandRow = Int.MAX_VALUE

    /** The tile the band hangs under, by id. Resolved to a row on every pack. */
    private var bandUnderTag: Any? = null

    /**
     * How much of the band's height is currently being given, 0 to 1.
     *
     * The opening slide is a change of height rather than a translation, because the wall
     * below has to move with it: the tiles are being pushed down, not slid over.
     */
    var bandProgress: Float = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            requestLayout()
        }

    var bandFullHeight = 0
        private set

    private val bandHeight: Int get() = (bandFullHeight * bandProgress).toInt()

    /**
     * Whether the band is held to its own bounds while it is drawn.
     *
     * The band cannot do this for itself. This layout does not clip its children - an edit
     * handle has to be able to hang off a tile - and a child that is not clipped by its
     * parent is drawn whole however tall the box it was laid out in: the folder's tiles
     * were being painted straight over the wall below while the gap was still opening.
     *
     * So the clip is applied from here, and only while the gap is moving. Once it is fully
     * open the tiles inside it are ordinary tiles again and their handles need the same
     * freedom as everything else's.
     */
    var bandClipped = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun drawChild(
        canvas: android.graphics.Canvas,
        child: View,
        drawingTime: Long
    ): Boolean {
        if (child !== bandView || !bandClipped) return super.drawChild(canvas, child, drawingTime)
        val saved = canvas.save()
        canvas.clipRect(child.left, child.top, child.right, child.bottom)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(saved)
        return drawn
    }

    /**
     * Puts [view] in the wall under whichever tile carries [underTag], or takes it away.
     */
    /**
     * How many of the children are tiles.
     *
     * The band is a child of this layout as well, and it is not a tile. Anything that
     * treats a child index as a position in the wall's own list of tiles has to stop short
     * of it, or the list is asked for an entry one past its end - which is what a drag out
     * of a folder was doing.
     */
    val tileCount: Int get() = childCount - (if (bandView != null) 1 else 0)

    /** Adds a tile, keeping it ahead of the band so child order matches tile order. */
    fun addTile(view: View) {
        addView(view, tileCount)
    }

    fun setBand(view: View?, underTag: Any?) {
        bandView?.let { if (it !== view) removeView(it) }
        bandView = view
        bandUnderTag = underTag
        if (view != null && view.parent == null) addView(view)
        requestLayout()
    }

    /**
     * The side of one small cell, in pixels.
     *
     * Read by the tiles: what a widget sets its reading at is worked out from a single
     * cell rather than from the tile it happens to be on, so every reading on the wall
     * comes out the same size. See TileView.sizeAsNumber.
     */
    val cellSize: Int
        get() = cellPx

    /** Pixel bounds of a placement, relative to this view. */
    fun boundsOf(p: Placement): android.graphics.Rect {
        val left = marginPx + p.col * (cellPx + gapPx)
        val top = p.row * (cellPx + gapPx) + if (p.row >= bandRow) bandHeight else 0
        return android.graphics.Rect(
            left, top,
            left + spanPx(p.cols), top + spanPx(p.rows)
        )
    }

    fun placementAt(index: Int): Placement? = placements.getOrNull(index)

    // ---------------------------------------------------------------- handle overhang

    /**
     * The tile whose edit handle the current gesture began on, if it began outside it.
     *
     * A handle is centred on its tile's corner, so half of it hangs into this grid rather
     * than sitting on the tile. A parent only offers a child the touches that land inside
     * that child, so the outer half would be dead: the tile never hears about it, and
     * whatever is behind it does.
     *
     * The grid is the view that *does* hear about it, so the grid hands it on. Only the
     * two handle rectangles are claimed - not the whole overhang - so a tap that merely
     * strays near a selected tile still belongs to whatever it actually landed on.
     */
    private var handleTouchTarget: TileView? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            handleTouchTarget = handleOwnerAt(ev.x, ev.y)
        }
        val target = handleTouchTarget ?: return super.dispatchTouchEvent(ev)

        // Handed on in the tile's own coordinates. The selected tile is neither scaled nor
        // translated - the wall around it is what stands back, and a tile being dragged is
        // claimed by the scroller long before this - so the offset is the whole transform.
        val local = android.view.MotionEvent.obtain(ev)
        local.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = try {
            target.dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> handleTouchTarget = null
        }
        return handled
    }

    /** The selected tile, if [x],[y] falls on one of its handles rather than on it. */
    private fun handleOwnerAt(x: Float, y: Float): TileView? {
        for (i in childCount - 1 downTo 0) {
            val tile = getChildAt(i) as? TileView ?: continue
            if (!tile.isEditMode) continue
            // Only one tile is ever selected, so there is nothing else to look at.
            val local = tile.handleHit(x - tile.left, y - tile.top)
            return if (local) tile else null
        }
        return null
    }

    private fun spanPx(span: Int) = span * cellPx + (span - 1) * gapPx

    private fun tileSizeOf(child: View): TileSize =
        (child as? TileView)?.tile?.size ?: TileSize.MEDIUM

    /**
     * Packs children into the grid, first-fit in child order.
     *
     * Rows grow on demand, so the occupancy map is a list of row bitmasks rather than a
     * fixed rectangle. Returns the number of rows used.
     *
     * The scan starts from the row the *previous* tile landed on rather than from the top.
     * That keeps the two behaviours that matter and which pull against each other:
     *
     *  - a small tile can still backfill a gap beside the tile before it, which is what
     *    lets a row of small tiles sit next to a medium one;
     *  - but it can never jump back above tiles that precede it in the order.
     *
     * Scanning from row 0 every time broke the second: a small tile dragged down the grid
     * would find the hole it had just vacated near the top and snap straight back into it,
     * so small tiles appeared to be stuck in the first row.
     */
    private fun pack(): Int {
        placements.clear()
        val occupied = mutableListOf<BooleanArray>()

        fun rowAt(r: Int): BooleanArray {
            while (occupied.size <= r) occupied.add(BooleanArray(columns))
            return occupied[r]
        }

        fun fits(col: Int, row: Int, w: Int, h: Int): Boolean {
            if (col + w > columns) return false
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) if (cells[c]) return false
            }
            return true
        }

        fun occupy(col: Int, row: Int, w: Int, h: Int) {
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) cells[c] = true
            }
        }

        // The earliest row any subsequent tile may occupy. Never moves backwards.
        var frontier = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // The band lives between rows rather than in them, so the packer steps over it
            // exactly as it steps over a hidden tile.
            if (child.visibility == GONE || child === bandView) {
                placements.add(Placement(0, 0, 0, 0))
                continue
            }
            val size = tileSizeOf(child)
            val w = size.cols.coerceAtMost(columns)
            val h = size.rows

            var placed = false
            var row = frontier
            while (!placed) {
                for (col in 0..(columns - w)) {
                    if (fits(col, row, w, h)) {
                        occupy(col, row, w, h)
                        placements.add(Placement(col, row, w, h))
                        // Left at this tile's own row, not the row after it, so the next
                        // tile can still share the band beside it.
                        frontier = row
                        placed = true
                        break
                    }
                }
                if (!placed) row++
            }
        }
        return occupied.size
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        // Measured against the phone's shorter side rather than against this wall's width.
        // The margin and the gap are a share of the screen, and on a screen held sideways -
        // twice as wide and no taller - a share of the width is a chasm: the air between
        // tiles would double while the tiles themselves stayed the same size.
        val basis = if (metricBasis > 0) metricBasis else width
        marginPx = (basis * MARGIN_FRACTION).toInt()
        gapPx = (basis * GAP_FRACTION).toInt()
        cellPx = ((width - 2 * marginPx - (columns - 1) * gapPx) / columns.toFloat()).toInt()

        val rows = pack()
        resolveBandRow()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child === bandView) continue
            val p = placements[i]
            child.measure(
                MeasureSpec.makeMeasureSpec(spanPx(p.cols), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(spanPx(p.rows), MeasureSpec.EXACTLY)
            )
        }

        // Measured at its natural height and then given whatever share of it the slide has
        // reached: the band knows how tall it wants to be, and the animation only decides
        // how much of that the wall has made room for so far.
        bandView?.let { band ->
            band.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            bandFullHeight = band.measuredHeight
        } ?: run { bandFullHeight = 0 }

        val height = if (rows == 0) 0 else rows * cellPx + (rows - 1) * gapPx
        setMeasuredDimension(width, height + bandHeight + bottomReservePx)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child === bandView) continue
            val bounds = boundsOf(placements[i])
            child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        // Laid out at its full height in a box that is only as tall as the slide has
        // opened. The band clips its own children, so what has not been made room for yet
        // is simply not drawn - which is what makes the contents appear to come down out
        // of the tile rather than to be squashed into the gap.
        bandView?.let { band ->
            val top = bandRow * (cellPx + gapPx)
            band.layout(0, top, width, top + bandHeight)
        }
    }

    /**
     * Finds the row the band sits above: the one after the tile it belongs to.
     *
     * Re-derived on every measure rather than remembered, because the tile it hangs under
     * moves - a resize above it, a reorder, a change of column count - and a band pinned to
     * a stale row would open in the middle of somebody else's row.
     */
    private fun resolveBandRow() {
        bandRow = Int.MAX_VALUE
        val tag = bandUnderTag ?: return
        var row = -1
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TileView ?: continue
            if (child.tile.id != tag) continue
            row = (placements.getOrNull(i) ?: return).let { it.row + it.rows }
            break
        }
        if (row < 0) return

        // The gap has to fall on a boundary no tile crosses. A 1x1 folder on the top row
        // next to a 2x2 ends one row down, and that row is the middle of its neighbour -
        // opening there put the folder straight through the tile beside it, because a tile
        // is only pushed down when it *starts* below the gap.
        //
        // So the boundary is walked down past anything straddling it, repeatedly: pushing
        // past one tile can land in the middle of a taller one.
        var moved = true
        while (moved) {
            moved = false
            for (i in 0 until childCount) {
                if (getChildAt(i) === bandView) continue
                val p = placements.getOrNull(i) ?: continue
                if (p.rows == 0) continue
                if (p.row < row && p.row + p.rows > row) {
                    row = p.row + p.rows
                    moved = true
                }
            }
        }
        bandRow = row
    }

    /**
     * How many grid columns a pixel width spans, rounded to the nearest whole column.
     * Used while dragging the resize handle to turn a width into a tile size.
     */
    fun columnsForWidth(px: Float): Int {
        if (cellPx <= 0) return 1
        val span = ((px + gapPx) / (cellPx + gapPx)).let { Math.round(it) }
        return span.coerceIn(1, columns)
    }

    /**
     * How many rows a pixel height spans.
     *
     * Needed alongside [columnsForWidth] because width alone no longer identifies a size:
     * the banner and the medium tile are both two cells across and differ only in height.
     */
    fun rowsForHeight(px: Float): Int {
        if (cellPx <= 0) return 1
        val span = ((px + gapPx) / (cellPx + gapPx)).let { Math.round(it) }
        return span.coerceAtLeast(1)
    }

    /**
     * The child index whose cell contains the given point, or -1.
     *
     * [insetFraction] shrinks each cell before testing, which is what stops a drag hovering
     * on a boundary from reordering back and forth every frame: the finger has to commit to
     * being over a tile, not merely touching its edge.
     */
    fun indexAt(x: Float, y: Float, insetFraction: Float = 0f): Int {
        for (i in 0 until childCount) {
            if (getChildAt(i).visibility == GONE) continue
            val bounds = boundsOf(placements[i])
            if (insetFraction > 0f) {
                val dx = (bounds.width() * insetFraction).toInt()
                val dy = (bounds.height() * insetFraction).toInt()
                bounds.inset(dx, dy)
            }
            if (bounds.contains(x.toInt(), y.toInt())) return i
        }
        return -1
    }

    companion object {
        /**
         * How many columns of the usual size fit across [available].
         *
         * [basis] is the phone's shorter side and [portraitColumns] what the user set the
         * wall to across it - so the cell that pair implies is worked out first, and then
         * as many of them as the width in hand will take. Given the phone upright the two
         * are the same number and this returns the setting untouched; laid on its side it
         * returns however many more fit, which is what keeps a tile the size it is either
         * way instead of stretching four of them across a screen twice as wide.
         */
        fun columnsFor(available: Int, basis: Int, portraitColumns: Int): Int {
            if (available <= 0 || basis <= 0) return portraitColumns
            val margin = basis * MARGIN_FRACTION
            val gap = basis * GAP_FRACTION
            val cell = (basis - 2 * margin - (portraitColumns - 1) * gap) / portraitColumns
            if (cell <= 0f) return portraitColumns
            return Math.round((available - 2 * margin + gap) / (cell + gap))
                .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        }

        const val COLUMNS = 4
        /** Read by the wall, so a folder's heading starts where the tiles start. */
        const val MARGIN_FRACTION = 0.05f
        private const val GAP_FRACTION = 0.025f

        // What the wall can be set to. Two would make a medium tile the whole width; the
        // ceiling is for the screen on its side, where the count is the portrait one
        // scaled by how much wider the screen has become - see StartScreenView.applyColumns.
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 12
    }
}
