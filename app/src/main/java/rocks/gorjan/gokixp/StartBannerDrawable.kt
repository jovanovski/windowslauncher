package rocks.gorjan.gokixp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The 9x Start menu's vertical banner. It scales to the frame's width only and sits on the
 * bottom edge, so the art stays the same size however tall the menu is: a shorter menu crops
 * the top, a taller one is filled above with the banner's own top colour.
 */
class StartBannerDrawable(private val bitmap: Bitmap) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint().apply { color = bitmap.getPixel(0, 0) }
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scaledHeight = bitmap.height * b.width().toFloat() / bitmap.width
        val top = b.bottom - scaledHeight

        canvas.save()
        canvas.clipRect(b)
        if (top > b.top) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), top, fill)
        dst.set(b.left.toFloat(), top, b.right.toFloat(), b.bottom.toFloat())
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restore()
    }

    // No intrinsic size, so the banner never pushes the menu taller than its items.
    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1

    override fun getPadding(padding: Rect) = false

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
