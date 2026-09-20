package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatSpinner
import rocks.gorjan.gokixp.R
import kotlin.math.roundToInt

/** How far a combo's text sits in from each edge, in design pixels. */
object WinComboInsets {
    const val LEFT = 5

    /** Enough to clear a scrollbar's width of arrow. */
    const val RIGHT = 21
}

/**
 * A drop-down that is only ever looked at - the greyed "Picture Display: Center" of the
 * Background tab, for instance. A live one is [WinSpinner].
 */
class WinComboBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var caption = WinCaption("")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinCaptioned)
            caption = WinCaption(a.getString(R.styleable.WinCaptioned_winText) ?: "")
            a.recycle()
        }
    }

    var text: String
        get() = caption.text
        set(value) {
            caption = WinCaption(value)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val unit = winUnit()
        val skin = winSkin()
        val e = WinEdges(canvas, unit)
        val designW = (width / unit).roundToInt()
        val designH = (height / unit).roundToInt()
        skin.combo(e, width.toFloat(), height.toFloat(), designW, designH, isEnabled)

        if (caption.text.isEmpty()) return
        skin.textPaint(context, unit, paint)
        skin.caption(e, caption, paint, e.px(WinComboInsets.LEFT), e.px(skin.fieldBaseline), isEnabled)
    }
}

/**
 * A live drop-down. It is a [android.widget.Spinner] underneath so existing adapters keep
 * working; all this adds is the shell's chrome and the padding that keeps the selected item
 * clear of the arrow.
 */
class WinSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatSpinner(context, attrs) {

    init {
        background = null
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unit = winUnit()
        val left = (WinComboInsets.LEFT * unit).roundToInt()
        val right = (WinComboInsets.RIGHT * unit).roundToInt()
        if (paddingLeft != left || paddingRight != right) setPadding(left, 0, right, 0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val unit = winUnit()
        val e = WinEdges(canvas, unit)
        val designW = (width / unit).roundToInt()
        val designH = (height / unit).roundToInt()
        winSkin().combo(e, width.toFloat(), height.toFloat(), designW, designH, isEnabled)
        super.onDraw(canvas)
    }
}
