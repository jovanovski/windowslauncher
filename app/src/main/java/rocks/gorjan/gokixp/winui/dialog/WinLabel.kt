package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import rocks.gorjan.gokixp.R

/**
 * A static text label.
 *
 * The view's top edge is the top of the capital letters, so a label placed at design row 244
 * has its caps on row 244 exactly as a screenshot shows them; the baseline follows nine rows
 * down. A disabled label is embossed or merely grey depending on the shell.
 */
class WinLabel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val ALIGN_LEFT = 0
        private const val ALIGN_CENTER = 1
        private const val ALIGN_RIGHT = 2
    }

    private var caption = WinCaption("")
    private var align = ALIGN_LEFT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinCaptioned)
            caption = WinCaption(a.getString(R.styleable.WinCaptioned_winText) ?: "")
            align = a.getInt(R.styleable.WinCaptioned_winAlign, ALIGN_LEFT)
            a.recycle()
        }
    }

    var text: String
        get() = caption.text
        set(value) {
            caption = WinCaption(value)
            invalidate()
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (caption.text.isEmpty()) return
        val unit = winUnit()
        val skin = winSkin()
        val e = WinEdges(canvas, unit)
        skin.textPaint(context, unit, paint)
        skin.fitCaption(paint, caption.text, width.toFloat(), unit)
        val textWidth = paint.measureText(caption.text)
        val x = when (align) {
            ALIGN_CENTER -> (width - textWidth) / 2f
            ALIGN_RIGHT -> width - textWidth
            else -> 0f
        }
        skin.caption(e, caption, paint, x, skin.labelBaseline * unit, isEnabled)
    }
}
