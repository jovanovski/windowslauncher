package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.TiltEffect

/**
 * The wall of favourites, and the drag that arranges it.
 *
 * A grid of squares that can be picked up and put down somewhere else, which is the one
 * gesture Windows Phone's Start screen taught everybody who used it: hold, carry, drop.
 * The tiles under the one being carried get out of its way as it passes rather than
 * waiting for it to land, so the arrangement you are looking at while you drag is the
 * arrangement you will get.
 *
 * The order it produces is the app's to keep. Android's address book has no notion of one
 * - a contact is starred or it is not - so a wall arranged here is a fact about this app
 * and nowhere else, and [onReordered] hands back the keys in their new order for the app
 * to write down.
 *
 * Hold and release without moving and it is not a drag at all: that is [Tile.onHeld], the
 * long press, which is where the commands for a tile live. One gesture with two endings,
 * told apart by whether the finger went anywhere - which is how a phone that has no room
 * for a right mouse button gets both.
 */
@SuppressLint("ViewConstructor")
class FavouritesGrid(
    context: Context,
    private val columns: Int,
    private val gap: Int,
    /** The tiles' keys, in the order they now sit in. Fired when one is put down. */
    private val onReordered: (List<String>) -> Unit
) : ViewGroup(context) {

    /**
     * One square.
     *
     * A [TiltEffect.Target] because it is not always at rest: a tile being carried sits
     * slightly larger, and the tilt's own release - which fires the moment the drag takes
     * the gesture off it - would otherwise put it straight back down while the finger is
     * still holding it.
     */
    class Tile(context: Context) : FrameLayout(context), TiltEffect.Target {
        var key: String = ""

        /** What a hold that went nowhere does. See the note on the grid. */
        var onHeld: (() -> Unit)? = null

        var lifted = false

        override fun restingScale(): Float = if (lifted) LIFT else 1f
    }

    /**
     * The tiles in the order they are laid out in, which is not the order they are children
     * in.
     *
     * Kept apart on purpose: reordering by taking a child out and putting it back would
     * mean removing a view in the middle of the touch it is the subject of, and the drag
     * would be carrying a view that had just been detached. The children never move; this
     * list does, and [onLayout] reads it.
     */
    private val order = mutableListOf<Tile>()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val holdMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var cell = 0

    private var candidate: Tile? = null
    private var dragging: Tile? = null

    /** Where inside the tile the finger landed, so that spot stays under it throughout. */
    private var grabX = 0f
    private var grabY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var downX = 0f
    private var downY = 0f

    /** Whether the finger has gone anywhere since the tile was picked up. */
    private var travelled = false

    /** Whether the arrangement actually changed, so a drag that went nowhere writes nothing. */
    private var rearranged = false

    private val hold = Runnable { beginDrag() }

    /**
     * Where each tile sat before the last rearrangement.
     *
     * Read once, by the layout pass that follows: a tile whose slot has changed is put in
     * its new one and then animated from its old one, which is the only way to move a view
     * whose position is decided by a layout rather than by an animator.
     */
    private val previous = mutableMapOf<Tile, Pair<Int, Int>>()

    init {
        // The tiles are square and the drag needs a tile that has left its slot to still be
        // drawn, which the parent's bounds would otherwise cut off.
        clipChildren = false
    }

    fun addTile(tile: Tile) {
        order.add(tile)
        addView(tile)
    }

    /** The keys in the order they are arranged, for whoever is keeping that order. */
    fun keys(): List<String> = order.map { it.key }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
        val rows = (order.size + columns - 1) / columns
        val height = if (rows == 0) 0 else rows * cell + (rows - 1) * gap
        val square = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (tile in order) tile.measure(square, square)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((index, tile) in order.withIndex()) {
            val x = (index % columns) * (cell + gap)
            val y = (index / columns) * (cell + gap)
            tile.layout(x, y, x + cell, y + cell)
        }
        // The tiles that moved over to make room, sent back to where they were and let go.
        if (previous.isNotEmpty()) {
            for (tile in order) {
                if (tile === dragging) continue
                val (wasX, wasY) = previous[tile] ?: continue
                val dx = (wasX - tile.left).toFloat()
                val dy = (wasY - tile.top).toFloat()
                if (dx == 0f && dy == 0f) continue
                tile.translationX = dx
                tile.translationY = dy
                tile.animate().translationX(0f).translationY(0f)
                    .setDuration(SETTLE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            previous.clear()
        }
        dragging?.let { follow(it) }
    }

    // ---------------------------------------------------------------- the gesture

    /**
     * Watches for the hold, and takes the gesture over once one has happened.
     *
     * The press itself is left to the tile - it is a tap until it has been held long
     * enough to be something else, and taking it early would cost every tile its tilt and
     * its click. Only once [hold] has fired does this claim the rest of the gesture, at
     * which point the tile is sent a cancel and stops hearing about it.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pointerX = ev.x
                pointerY = ev.y
                candidate = tileAt(ev.x, ev.y)
                travelled = false
                rearranged = false
                if (candidate != null) postDelayed(hold, holdMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != null) {
                    pointerX = ev.x
                    pointerY = ev.y
                    return true
                }
                // Gone before the hold was up: this is the page being scrolled, not a tile
                // being picked up, and the page is welcome to it.
                if (kotlin.math.hypot(ev.x - downX, ev.y - downY) > slop) cancelHold()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                // Finished here rather than passed on: claiming the gesture on an UP sends
                // the tile a cancel and ends the dispatch, so this view's own touch handler
                // is never called for it and a drag ended by a still finger would never be
                // put down.
                if (dragging != null) {
                    endDrag(held = ev.actionMasked == MotionEvent.ACTION_UP && !travelled)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tile = dragging ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                pointerX = event.x
                pointerY = event.y
                if (kotlin.math.hypot(event.x - downX, event.y - downY) > slop) travelled = true
                follow(tile)
                rearrangeFor(tile)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                endDrag(held = event.actionMasked == MotionEvent.ACTION_UP && !travelled)
        }
        return true
    }

    private fun cancelHold() {
        removeCallbacks(hold)
        candidate = null
    }

    private fun beginDrag() {
        val tile = candidate ?: return
        dragging = tile
        travelled = false
        grabX = downX - tile.left
        grabY = downY - tile.top
        tile.lifted = true
        tile.animate().cancel()
        tile.animate()
            .scaleX(LIFT).scaleY(LIFT).alpha(CARRIED_ALPHA)
            .setDuration(LIFT_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // Raised so it passes over the tiles it is being carried across rather than under
        // them: a ViewGroup draws its children in the order it holds them, and this one is
        // not necessarily last.
        tile.elevation = LIFT_Z * resources.displayMetrics.density
        Haptics.tap(tile)
        // The page under this scrolls and the panorama either side of it pages. Neither is
        // allowed to read a carried tile as one of theirs.
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** Keeps the spot the finger landed on under the finger. */
    private fun follow(tile: Tile) {
        tile.translationX = pointerX - grabX - tile.left
        tile.translationY = pointerY - grabY - tile.top
    }

    /**
     * Moves the carried tile to whichever slot it is now over.
     *
     * Measured from the middle of the tile rather than from the finger: what the eye is
     * judging is where the square is, and a tile grabbed by its corner would otherwise
     * drop a whole slot away from where it looks like it is going.
     */
    private fun rearrangeFor(tile: Tile) {
        if (cell <= 0 || order.size < 2) return
        val centreX = pointerX - grabX + cell / 2f
        val centreY = pointerY - grabY + cell / 2f
        val column = (centreX / (cell + gap)).toInt().coerceIn(0, columns - 1)
        val row = (centreY / (cell + gap)).toInt().coerceAtLeast(0)
        val target = (row * columns + column).coerceIn(0, order.size - 1)
        val from = order.indexOf(tile)
        if (target == from || from < 0) return

        for (other in order) previous[other] = other.left to other.top
        order.removeAt(from)
        order.add(target, tile)
        rearranged = true
        requestLayout()
    }

    private fun endDrag(held: Boolean) {
        val tile = dragging ?: return
        dragging = null
        cancelHold()
        tile.lifted = false
        tile.animate().cancel()
        tile.animate()
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(SETTLE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { tile.elevation = 0f }
            .start()
        parent?.requestDisallowInterceptTouchEvent(false)

        // A hold that went nowhere was never a drag; it was the long press.
        if (held) tile.onHeld?.invoke()
        else if (rearranged) onReordered(keys())
        rearranged = false
    }

    private fun tileAt(x: Float, y: Float): Tile? = order.firstOrNull {
        x >= it.left && x < it.right && y >= it.top && y < it.bottom
    }

    private companion object {
        /** How much larger a tile sits while it is being carried. */
        const val LIFT = 1.06f
        const val CARRIED_ALPHA = 0.92f

        /** How far off the wall it is lifted, in dp, so it passes over its neighbours. */
        const val LIFT_Z = 8f

        const val LIFT_MS = 120L
        const val SETTLE_MS = 180L
    }
}
