package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import rocks.gorjan.gokixp.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lays its children out on a Windows dialog's pixel grid.
 *
 * Children give their position and size in design pixels (`app:layout_winX` and friends) and
 * this container works out the one number everything else scales by: how many device pixels a
 * design pixel is worth. The outermost one divides the space it is given by its design size; a
 * nested one inherits the same value, and the same skin, so the whole dialog stays on one grid
 * in one shell's clothes.
 *
 * It can also draw a frame around itself, which is what turns it into a list box's well.
 */
open class WinLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs), WinHost {

    companion object {
        const val FRAME_NONE = 0
        const val FRAME_LIST = 1
    }

    var designWidth = 399
    var designHeight = 423
    private var frame = FRAME_NONE
    private var paintsFace = false

    final override var unitPx: Float = 1f
        private set

    final override var skin: WinSkin = ClassicSkin
        private set

    /** Set by whoever opens the dialog, from the shell the launcher is wearing. */
    private var assignedSkin: WinSkin? = null

    /** Where the design grid starts inside this view - non-zero only when the root is letterboxed. */
    protected var offsetX = 0f
        private set
    protected var offsetY = 0f
        private set

    init {
        setWillNotDraw(false)
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinLayout)
            designWidth = a.getInt(R.styleable.WinLayout_winDesignWidth, designWidth)
            designHeight = a.getInt(R.styleable.WinLayout_winDesignHeight, designHeight)
            frame = a.getInt(R.styleable.WinLayout_winFrame, FRAME_NONE)
            paintsFace = a.getBoolean(R.styleable.WinLayout_winFace, false)
            a.recycle()
        }
    }

    /** Dresses this layout, and everything under it, in one shell's skin. */
    fun applySkin(newSkin: WinSkin) {
        assignedSkin = newSkin
        skin = newSkin
        requestLayout()
        invalidate()
    }

    private fun host(): WinHost? {
        var p = parent
        while (p != null) {
            if (p is WinHost) return p
            p = (p as? View)?.parent
        }
        return null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        val host = host()
        skin = assignedSkin ?: host?.skin ?: ClassicSkin
        if (host != null) {
            unitPx = host.unitPx
        } else {
            // The dialog decides the scale. Fit the whole design box so nothing is ever cut
            // off, however the window has been resized.
            val byWidth = if (wMode == MeasureSpec.UNSPECIFIED) unitPx else wSize / designWidth.toFloat()
            val byHeight = if (hMode == MeasureSpec.UNSPECIFIED) byWidth else hSize / designHeight.toFloat()
            unitPx = min(byWidth, byHeight)
        }

        val designW = (designWidth * unitPx).roundToInt()
        val designH = (designHeight * unitPx).roundToInt()
        val width = if (wMode == MeasureSpec.UNSPECIFIED) designW else wSize
        val height = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(hSize, designH)
            else -> designH
        }
        offsetX = if (host == null) ((width - designW) / 2f) else 0f
        offsetY = if (host == null) ((height - designH) / 2f) else 0f

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val cw = childSpec(lp.winW, lp.width, designW - px(lp.winX))
            val ch = childSpec(lp.winH, lp.height, designH - px(lp.winY))
            child.measure(cw, ch)
        }
        setMeasuredDimension(width, height)
    }

    private fun px(designPx: Int) = (designPx * unitPx).roundToInt()

    private fun childSpec(design: Int, layout: Int, available: Int): Int = when {
        design >= 0 -> MeasureSpec.makeMeasureSpec(px(design), MeasureSpec.EXACTLY)
        layout == ViewGroup.LayoutParams.MATCH_PARENT ->
            MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.EXACTLY)
        layout >= 0 -> MeasureSpec.makeMeasureSpec(layout, MeasureSpec.EXACTLY)
        else -> MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.AT_MOST)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val x = (offsetX + lp.winX * unitPx).roundToInt()
            val y = (offsetY + lp.winY * unitPx).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!paintsFace && frame == FRAME_NONE) return
        val e = WinEdges(canvas, unitPx)
        val left = offsetX
        val top = offsetY
        val right = width - offsetX
        val bottom = height - offsetY
        if (paintsFace) e.fill(left, top, right, bottom, skin.face)
        if (frame == FRAME_LIST) {
            canvas.save()
            canvas.translate(left, top)
            skin.field(e, right - left, bottom - top, true)
            canvas.restore()
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    /** Position and size in design pixels; -1 means "fall back to the ordinary layout rules". */
    class LayoutParams : ViewGroup.LayoutParams {
        var winX = 0
        var winY = 0
        var winW = -1
        var winH = -1

        // Deliberately not super(context, attrs): that insists on android:layout_width, and a
        // child positioned on the design grid has no business repeating its size in dp.
        constructor(context: Context, attrs: AttributeSet) : super(WRAP_CONTENT, WRAP_CONTENT) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinLayout_Layout)
            width = a.getLayoutDimension(R.styleable.WinLayout_Layout_android_layout_width, WRAP_CONTENT)
            height = a.getLayoutDimension(R.styleable.WinLayout_Layout_android_layout_height, WRAP_CONTENT)
            winX = a.getInt(R.styleable.WinLayout_Layout_layout_winX, 0)
            winY = a.getInt(R.styleable.WinLayout_Layout_layout_winY, 0)
            winW = a.getInt(R.styleable.WinLayout_Layout_layout_winW, -1)
            winH = a.getInt(R.styleable.WinLayout_Layout_layout_winH, -1)
            a.recycle()
        }

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams) : super(source)
    }
}
