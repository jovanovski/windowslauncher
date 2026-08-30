package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * The Windows Phone slider: a thin track with a square thumb, filled in the accent up to
 * the current value and left flat beyond it.
 *
 * Hand-rolled rather than a themed [android.widget.SeekBar], which drags Material's
 * ripple, elevation and rounded thumb along with it and fights the flat look everywhere
 * else in this shell.
 */
class MetroSlider(context: Context) : View(context) {

    var onValueChanged: ((Float) -> Unit)? = null

    /** Current position, 0 to 1. */
    var value: Float = 0f
        set(v) {
            val clamped = v.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    private var trackColor = 0x33FFFFFF
    private var fillColor = 0xFF1BA1E2.toInt()
    private var thumbColor = 0xFFFFFFFF.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun applyPalette(palette: WP81Palette) {
        trackColor = palette.inactive
        fillColor = palette.accent
        thumbColor = palette.foreground
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            (HEIGHT_DP * resources.displayMetrics.density).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val trackH = TRACK_DP * density
        val thumbW = THUMB_W_DP * density
        val thumbH = THUMB_H_DP * density

        // The thumb must stay fully on screen at both ends, so the track it travels is
        // inset by half a thumb on each side.
        val left = thumbW / 2f
        val right = width - thumbW / 2f
        val centreY = height / 2f
        val x = left + (right - left) * value

        paint.color = trackColor
        canvas.drawRect(left, centreY - trackH / 2, right, centreY + trackH / 2, paint)

        paint.color = fillColor
        canvas.drawRect(left, centreY - trackH / 2, x, centreY + trackH / 2, paint)

        paint.color = thumbColor
        canvas.drawRect(
            x - thumbW / 2, centreY - thumbH / 2,
            x + thumbW / 2, centreY + thumbH / 2, paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(touchX: Float) {
        val thumbW = THUMB_W_DP * resources.displayMetrics.density
        val left = thumbW / 2f
        val right = width - thumbW / 2f
        if (right <= left) return
        val next = ((touchX - left) / (right - left)).coerceIn(0f, 1f)
        if (next != value) {
            value = next
            onValueChanged?.invoke(next)
        }
    }

    companion object {
        private const val HEIGHT_DP = 44
        private const val TRACK_DP = 3
        private const val THUMB_W_DP = 10
        private const val THUMB_H_DP = 26
    }
}
