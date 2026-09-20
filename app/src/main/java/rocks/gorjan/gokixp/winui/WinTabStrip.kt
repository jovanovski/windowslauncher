package rocks.gorjan.gokixp.winui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.winui.dialog.WinCaption
import rocks.gorjan.gokixp.winui.dialog.WinEdges
import rocks.gorjan.gokixp.winui.dialog.WinSkin
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A tab control, in a window that can be resized.
 *
 * Every pixel of it is drawn by [WinSkin] - the same skins the Display Properties sheet
 * uses, `skin.page` and `skin.tab`, so a tab here and a tab there are the same control and
 * there is only one place that knows what a Windows tab looks like. What differs is the
 * layout policy, and only that: a property sheet is a 1:1 reproduction of a dialog that was
 * always 399 design pixels wide, laid out on
 * [rocks.gorjan.gokixp.winui.dialog.WinLayout]'s fixed grid, while a program window can be
 * dragged to any size and its tabs have to follow. So the geometry is computed here and the
 * drawing is borrowed from there.
 *
 * The join is the part that matters and the part the skins already get right: the page's
 * border is drawn first and complete, the unselected tabs go on behind it, and the selected
 * tab is drawn last reaching down *past* the strip so it paints over the border it crosses.
 * That break in the line is what says which tab the page belongs to. A row of buttons above
 * a box cannot say it, however the buttons are coloured.
 *
 * One design pixel is one dp here, which makes the strip 27dp tall - the height a property
 * sheet's tabs have always been.
 */
@SuppressLint("ViewConstructor")
class WinTabStrip(
    context: Context,
    theme: AppTheme,
    private val density: Float
) : ViewGroup(context) {

    /** Where the selected tab's contents go. The only child this has. */
    val page = FrameLayout(context)

    var onSelect: ((Int) -> Unit)? = null

    var selected: Int = 0
        private set

    private val skin: WinSkin = WinSkin.of(theme)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val titles = mutableListOf<String>()
    private val captions = mutableListOf<WinCaption>()

    /** Each tab's left edge and width, in design pixels. */
    private val lefts = mutableListOf<Int>()
    private val widths = mutableListOf<Int>()

    init {
        setWillNotDraw(false)
        addView(page)
        // Far enough in that the page's own border is not underneath its contents. The
        // skins draw a two-line bevel on Classic and a single line elsewhere.
        val inset = (PAGE_INSET_DP * density).roundToInt()
        page.setPadding(inset, inset, inset, inset)
    }

    fun setTabs(names: List<String>) {
        titles.clear()
        titles.addAll(names)
        captions.clear()
        names.forEach { captions.add(WinCaption(it)) }
        if (selected >= titles.size) selected = 0
        requestLayout()
        invalidate()
    }

    fun select(index: Int, notify: Boolean = true) {
        if (index !in titles.indices || index == selected) return
        selected = index
        invalidate()
        if (notify) onSelect?.invoke(index)
    }

    // ---------------------------------------------------------------- geometry

    /**
     * How wide each tab is, in design pixels.
     *
     * Natural widths, the way Windows lays them out: a tab is as wide as its word needs
     * and no wider. Squeezed proportionally if the row would run off the edge, because a
     * tab control you have to scroll to find a tab in has stopped being one.
     */
    private fun measureTabs(widthPx: Int) {
        lefts.clear()
        widths.clear()
        if (titles.isEmpty()) return
        skin.textPaint(context, density, paint)

        val natural = titles.map {
            max(MIN_TAB_DP, (paint.measureText(it) / density).roundToInt() + 2 * TAB_PAD_DP)
        }
        val available = (widthPx / density).roundToInt() - 2 * FIRST_TAB_X
        val total = natural.sum()
        val scale = if (total > available && total > 0) available.toFloat() / total else 1f

        var x = FIRST_TAB_X
        for (w in natural) {
            val scaled = max(1, (w * scale).roundToInt())
            lefts.add(x)
            widths.add(scaled)
            x += scaled
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        measureTabs(width)
        val top = (PAGE_TOP * density).roundToInt()
        page.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(0, height - top), MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = (PAGE_TOP * density).roundToInt()
        page.layout(0, top, r - l, b - t)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (titles.isEmpty()) return
        val e = WinEdges(canvas, density)
        val right = (width / density).roundToInt() - 1
        val bottom = (height / density).roundToInt() - 1

        // The page first, complete. The selected tab opens it afterwards.
        skin.page(e, 0, PAGE_TOP, right, bottom)

        for (i in titles.indices) if (i != selected) drawTab(e, i, false)
        if (selected in titles.indices) drawTab(e, selected, true)
    }

    private fun drawTab(e: WinEdges, index: Int, isSelected: Boolean) {
        val left = lefts[index]
        val right = left + widths[index] - 1
        skin.tab(e, left, right, isSelected, PAGE_TOP)

        val caption = captions[index]
        if (caption.text.isEmpty()) return
        skin.textPaint(context, density, paint)
        skin.fitCaption(paint, caption.text, (widths[index] - 5) * density, density)
        val centre = (left + right) / 2f
        val x = e.px(centre + 0.5f) - paint.measureText(caption.text) / 2f
        skin.caption(e, caption, paint, x, e.px(skin.tabTextBaseline), true)
    }

    // ---------------------------------------------------------------- touch

    /**
     * The strip takes touches; the page does not.
     *
     * Without the intercept being this narrow, a `ViewGroup` that handles touch handles all
     * of it, and nothing inside the page - a list, a field - would ever see a finger.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.y <= PAGE_TOP * density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (titles.isEmpty()) return false
        if (event.y > PAGE_TOP * density) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = (event.x / density).roundToInt()
        for (i in titles.indices) {
            if (x >= lefts[i] && x < lefts[i] + widths[i]) {
                select(i)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun addView(child: View?) {
        check(childCount == 0) { "A tab control has one page" }
        super.addView(child)
    }

    private companion object {
        /**
         * Where the page's top edge is, in design pixels - and so how tall the strip is.
         *
         * The property sheet's own number. The skins' tab geometry is written against it:
         * `tabTop`, `tabBottom`, `selectedTabTop` and `tabTextBaseline` are all measured
         * from the same origin, so changing this without changing those would leave the
         * tabs floating off the page.
         */
        const val PAGE_TOP = 27

        /** Where the first tab starts, leaving the page's corner visible beside it. */
        const val FIRST_TAB_X = 9

        const val TAB_PAD_DP = 10
        const val MIN_TAB_DP = 40
        const val PAGE_INSET_DP = 4
    }
}
