package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The up/down pair that sits inside a spin box - "Wait: 30 seconds" and its like.
 *
 * Fifteen design pixels wide and sixteen tall, split down the middle. Only the half under the
 * finger goes down, which is what Windows does and what makes a spin box feel like two buttons
 * rather than one.
 */
class WinSpinButtons @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onUp: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null

    /** -1 none, 0 up, 1 down. */
    private var pushed = -1

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pushed = if (event.y < height / 2f) 0 else 1
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val inside = event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                if (inside && pushed == 0) onUp?.invoke()
                if (inside && pushed == 1) onDown?.invoke()
                pushed = -1
                invalidate()
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                pushed = -1
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val unit = winUnit()
        val skin = winSkin()
        val e = WinEdges(canvas, unit)
        val half = height / 2f
        val w = width.toFloat()

        fun state(which: Int) = when {
            !isEnabled -> WinState.DISABLED
            pushed == which -> WinState.PRESSED
            else -> WinState.NORMAL
        }
        skin.spinButton(e, 0f, 0f, w, half, true, state(0))
        skin.spinButton(e, 0f, half, w, height.toFloat(), false, state(1))
    }
}
