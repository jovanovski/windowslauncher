package rocks.gorjan.gokixp

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The start menu app list's LayoutManager: stackFromEnd, so a short filtered result hugs the
 * bottom edge, plus a [pinToTop] that actually survives opening the menu.
 *
 * A plain scrollToPosition(0) doesn't. RecyclerView lays the list out inside onMeasure whenever
 * the width spec isn't exact, and the start menu's RelativeLayouts probe it at full display
 * height first. That layout consumes the pending position; the real one, at the list's actual
 * (shorter) height, then re-anchors on the bottom row the way stackFromEnd does - so the list
 * opened scrolled down by (display height - list height), somewhere in the middle.
 *
 * So the pin is re-applied on every layout until something else moves the list: a scroll, or
 * any other scrollToPosition (the search box's, which wants stackFromEnd's bottom anchoring).
 */
class AppListLayoutManager(context: Context) : LinearLayoutManager(context) {

    private var pinnedToTop = false

    init {
        stackFromEnd = true
    }

    fun pinToTop() {
        pinnedToTop = true
        super.scrollToPositionWithOffset(0, 0)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State) {
        // An explicit offset anchors from the top edge; without one stackFromEnd anchors from the bottom
        if (pinnedToTop && state.itemCount > 0) super.scrollToPositionWithOffset(0, 0)
        super.onLayoutChildren(recycler, state)
    }

    override fun scrollToPosition(position: Int) {
        pinnedToTop = false
        super.scrollToPosition(position)
    }

    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        pinnedToTop = false
        super.scrollToPositionWithOffset(position, offset)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        pinnedToTop = false
        return super.scrollVerticallyBy(dy, recycler, state)
    }
}
