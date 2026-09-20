package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatEditText
import rocks.gorjan.gokixp.R
import kotlin.math.roundToInt

/**
 * An edit field, sunk behind whichever edge the shell draws.
 *
 * Its insets are given in design pixels (`app:winPadStart` and friends) rather than dp, so the
 * text sits where a screenshot puts it and a field that has to make room for a spin control can
 * just ask for a wider one on that side.
 */
class WinEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    private var padStart = 2
    private var padTop = 4
    private var padEnd = 2

    init {
        background = null
        includeFontPadding = false
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinEditText)
            padStart = a.getInt(R.styleable.WinEditText_winPadStart, padStart)
            padTop = a.getInt(R.styleable.WinEditText_winPadTop, padTop)
            padEnd = a.getInt(R.styleable.WinEditText_winPadEnd, padEnd)
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unit = winUnit()
        val skin = winSkin()
        typeface = skin.typeface(context)
        setTextColor(skin.windowText)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, skin.fontPx * unit)
        val start = (padStart * unit).roundToInt()
        val top = (padTop * unit).roundToInt()
        val end = (padEnd * unit).roundToInt()
        if (paddingStart != start || paddingTop != top || paddingEnd != end) {
            setPaddingRelative(start, top, end, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The text size follows the dialog's scale, so a field that was briefly measured small
        // can be left scrolled to somewhere the text no longer reaches - which hides it and
        // drags the frame off with it. Once it all fits, there is nowhere to scroll to.
        val l = layout ?: return
        val room = h - compoundPaddingTop - compoundPaddingBottom
        val lineRoom = w - compoundPaddingLeft - compoundPaddingRight
        val y = if (l.height <= room) 0 else scrollY
        val x = if (l.lineCount <= 1 && l.getLineWidth(0) <= lineRoom) 0 else scrollX
        if (x != scrollX || y != scrollY) scrollTo(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        // A TextView is drawn through a canvas the editor has already scrolled; the frame
        // belongs to the view, not to the text, so put the origin back first.
        canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        val e = WinEdges(canvas, winUnit())
        winSkin().field(e, width.toFloat(), height.toFloat(), isEnabled)
        canvas.restore()
        super.onDraw(canvas)
    }
}
