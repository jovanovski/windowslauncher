package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import rocks.gorjan.gokixp.R

/**
 * Pixel art at the dialog's scale.
 *
 * The bits Windows shipped as bitmaps rather than as drawing code - the little monitor, the
 * Energy Star globe - are stored at their original size and blown up with no smoothing, so
 * they stay pixel art instead of turning into a blur.
 */
class WinSprite @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val src = Rect()
    private val dst = RectF()

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinSprite)
            val res = a.getResourceId(R.styleable.WinSprite_winSrc, 0)
            a.recycle()
            if (res != 0) setImageResource(res)
        }
    }

    fun setImageResource(resId: Int) {
        val options = BitmapFactory.Options().apply { inScaled = false }
        bitmap = BitmapFactory.decodeResource(resources, resId, options)
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
