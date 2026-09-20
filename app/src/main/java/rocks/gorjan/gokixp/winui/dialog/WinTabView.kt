package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The tab control at the top of a property sheet: the row of tabs plus the page they sit on.
 *
 * It draws only - the pages themselves are ordinary siblings stacked over it, which is how a
 * property sheet works in Windows too. What a tab looks like, and how much bigger the selected
 * one is, belongs to the skin: 9x notches its corners and spreads two pixels each way, Luna
 * rounds the top and lays an orange band across it, Aero keeps it square and only grows it
 * upwards.
 */
class WinTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** [width] is the tab's own width in design pixels; a disabled tab is drawn greyed. */
    data class Tab(val title: String, val width: Int, val enabled: Boolean = true)

    companion object {
        /** Design-pixel geometry shared by every shell's property sheet. */
        const val FIRST_TAB_X = 9
        const val PAGE_LEFT = 7
        const val PAGE_TOP = 27
        const val PAGE_RIGHT = 392
        const val PAGE_BOTTOM = 386
    }

    private var tabs: List<Tab> = emptyList()
    private var captions: List<WinCaption> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var selected: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Fired for a tab the user can actually reach; disabled tabs are ignored. */
    var onTabSelected: ((Int) -> Unit)? = null

    fun setTabs(newTabs: List<Tab>) {
        tabs = newTabs
        captions = newTabs.map { WinCaption(it.title) }
        invalidate()
    }

    /** Inclusive design-pixel left edge of tab [index] when it is not the selected one. */
    private fun tabLeft(index: Int): Int {
        var x = FIRST_TAB_X
        for (i in 0 until index) x += tabs[i].width
        return x
    }

    override fun onDraw(canvas: Canvas) {
        if (tabs.isEmpty()) return
        val unit = winUnit()
        val skin = winSkin()
        val e = WinEdges(canvas, unit)

        // The page first: the selected tab paints over its top edge afterwards.
        skin.page(e, PAGE_LEFT, PAGE_TOP, PAGE_RIGHT, PAGE_BOTTOM)

        skin.textPaint(context, unit, paint)
        for (i in tabs.indices) if (i != selected) drawTab(e, skin, i, false)
        if (selected in tabs.indices) drawTab(e, skin, selected, true)
    }

    private fun drawTab(e: WinEdges, skin: WinSkin, index: Int, isSelected: Boolean) {
        val left = tabLeft(index)
        val right = left + tabs[index].width - 1
        skin.tab(e, left, right, isSelected, PAGE_TOP)

        val caption = captions[index]
        if (caption.text.isEmpty()) return
        skin.fitCaption(paint, caption.text, (tabs[index].width - 5) * e.unit, e.unit)
        val centre = (left + right) / 2f
        val x = e.px(centre + 0.5f) - paint.measureText(caption.text) / 2f
        skin.caption(e, caption, paint, x, e.px(skin.tabTextBaseline), tabs[index].enabled)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tabs.isEmpty()) return false
        val skin = winSkin()
        val unit = winUnit()
        val y = event.y / unit
        if (y < skin.selectedTabTop || y > PAGE_TOP) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x / unit
        for (i in tabs.indices) {
            val l = tabLeft(i)
            if (x >= l && x < l + tabs[i].width && tabs[i].enabled) {
                if (i != selected) {
                    selected = i
                    onTabSelected?.invoke(i)
                }
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
