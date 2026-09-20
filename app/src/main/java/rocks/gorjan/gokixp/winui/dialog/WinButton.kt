package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import rocks.gorjan.gokixp.R

/**
 * A push button.
 *
 * 75x23 is not a round number picked for looks: it is 50x14 dialog units at 8 point, which is
 * what every OK button in Windows has measured since 1995. What happens when it goes down is
 * the skin's business - 9x flips its bevel over and shifts the caption a pixel, the later
 * shells just change colour.
 */
class WinButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val WIDTH = 75
        const val HEIGHT = 23
    }

    private var caption = WinCaption("")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var down = false

    init {
        isClickable = true
        isFocusable = true
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

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setDown(true)
            MotionEvent.ACTION_MOVE -> setDown(
                event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setDown(false)
        }
        return super.onTouchEvent(event)
    }

    private fun setDown(value: Boolean) {
        if (down != value) {
            down = value
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val unit = winUnit()
        val skin = winSkin()
        val e = WinEdges(canvas, unit)
        val w = width.toFloat()
        val h = height.toFloat()
        val state = when {
            !isEnabled -> WinState.DISABLED
            down -> WinState.PRESSED
            else -> WinState.NORMAL
        }
        skin.button(e, w, h, state)

        if (caption.text.isEmpty()) return
        skin.textPaint(context, unit, paint)
        skin.fitCaption(paint, caption.text, w - 8 * unit, unit)
        // Measured off the screenshots: on a 23px button the caps start 6 rows down and the
        // baseline lands on row 15. A taller button keeps the text on the same offset.
        val designH = height / unit
        val baseline = (skin.buttonBaseline + (designH - HEIGHT) / 2f) * unit
        val x = (w - paint.measureText(caption.text)) / 2f
        val shift = if (down && skin.movesCaptionWhenPressed) e.lw else 0f
        skin.caption(e, caption, paint, x + shift, baseline + shift, isEnabled)
    }
}
