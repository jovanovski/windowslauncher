package rocks.gorjan.gokixp.winui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar

/**
 * The two controls that show a number as a length: the trackbar you drag and the progress bar
 * you watch.
 *
 * Both are drawn rather than themed, because Android's own are Material through and through
 * and no amount of tinting makes a Material slider look like a trackbar.
 */

/** The groove a trackbar's thumb slides in - sunk on 9x, a thin line after. */
private class GrooveDrawable(private val ui: WinUi) : Drawable() {

    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = hair(ui.density)
        val thickness = ui.dp(4)
        val top = b.exactCenterY() - thickness / 2f
        val bottom = top + thickness
        if (ui.isClassic) {
            paint.color = ui.pal.face
            canvas.drawRect(b.left.toFloat(), top, b.right.toFloat(), bottom, paint)
            paint.color = ui.pal.shadow
            canvas.drawRect(b.left.toFloat(), top, b.right - h, top + h, paint)
            canvas.drawRect(b.left.toFloat(), top, b.left + h, bottom - h, paint)
            paint.color = ui.pal.dkShadow
            canvas.drawRect(b.left + h, top + h, b.right - 2 * h, top + 2 * h, paint)
            paint.color = ui.pal.hilight
            canvas.drawRect(b.left.toFloat(), bottom - h, b.right.toFloat(), bottom, paint)
            canvas.drawRect(b.right - h, top, b.right.toFloat(), bottom, paint)
            return
        }
        val r = thickness / 2f
        val rect = RectF(b.left.toFloat(), top, b.right.toFloat(), bottom)
        paint.color = ui.pal.window
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h
        paint.color = ui.pal.fieldBorder
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The thumb: a raised block on 9x, a rounded one after. */
private class ThumbDrawable(private val ui: WinUi) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = hair(ui.density)
        if (ui.isClassic) {
            paint.color = ui.pal.face
            canvas.drawRect(b, paint)
            paint.color = ui.pal.hilight
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right - h, b.top + h, paint)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + h, b.bottom - h, paint)
            paint.color = ui.pal.shadow
            canvas.drawRect(b.left + h, b.bottom - 2 * h, b.right - h, b.bottom - h, paint)
            canvas.drawRect(b.right - 2 * h, b.top + h, b.right - h, b.bottom - h, paint)
            paint.color = ui.pal.dkShadow
            canvas.drawRect(b.left.toFloat(), b.bottom - h, b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawRect(b.right - h, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
            return
        }
        val rect = RectF(b.left + h / 2, b.top + h / 2, b.right - h / 2, b.bottom - h / 2)
        val r = 2f * ui.density
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom, ui.pal.buttonTop, ui.pal.buttonBottom, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h
        paint.color = ui.pal.buttonBorder
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = ui.dp(11)
    override fun getIntrinsicHeight() = ui.dp(21)
}

/**
 * A trackbar - what the Display Properties tabs call a slider.
 *
 * A [SeekBar] underneath, so `progress`, `max` and the change listener are the ordinary ones.
 */
fun WinUi.trackBar(
    max: Int = 100,
    progress: Int = 0,
    onChange: ((Int) -> Unit)? = null,
): SeekBar = SeekBar(context).apply {
    this.max = max
    this.progress = progress
    progressDrawable = GrooveDrawable(this@trackBar)
    thumb = ThumbDrawable(this@trackBar)
    // The groove is drawn full width; the thumb needs the room its own width takes.
    setPadding(dp(6), dp(2), dp(6), dp(2))
    splitTrack = false
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(28),
    )
    onChange?.let { listener ->
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) listener(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }
}

/**
 * A progress bar.
 *
 * 9x and XP fill theirs with separate blocks and a gap between each; the Aero shells fill a
 * continuous bar and put a lighter band across the top of it. All three are green from XP on
 * and the system highlight colour before that.
 */
private class WinProgressDrawable(private val ui: WinUi) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barColor: Int get() = if (ui.isClassic) ui.pal.select else 0xFF06B025.toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = hair(ui.density)
        val fraction = level / 10000f

        // The trough.
        paint.style = Paint.Style.FILL
        paint.color = if (ui.isClassic) ui.pal.face else ui.pal.window
        if (ui.isClassic) {
            canvas.drawRect(b, paint)
            paint.color = ui.pal.shadow
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right - h, b.top + h, paint)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + h, b.bottom - h, paint)
            paint.color = ui.pal.dkShadow
            canvas.drawRect(b.left + h, b.top + h, b.right - 2 * h, b.top + 2 * h, paint)
            canvas.drawRect(b.left + h, b.top + h, b.left + 2 * h, b.bottom - 2 * h, paint)
            paint.color = ui.pal.hilight
            canvas.drawRect(b.left.toFloat(), b.bottom - h, b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawRect(b.right - h, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        } else {
            val rect = RectF(b.left + h / 2, b.top + h / 2, b.right - h / 2, b.bottom - h / 2)
            canvas.drawRoundRect(rect, 2f * ui.density, 2f * ui.density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h
            paint.color = ui.pal.fieldBorder
            canvas.drawRoundRect(rect, 2f * ui.density, 2f * ui.density, paint)
            paint.style = Paint.Style.FILL
        }
        if (fraction <= 0f) return

        val inset = if (ui.isClassic) 2 * h else 2 * h
        val left = b.left + inset
        val top = b.top + inset
        val bottom = b.bottom - inset
        val span = (b.right - inset) - left

        paint.color = barColor
        if (ui.isAero) {
            canvas.drawRect(left, top, left + span * fraction, bottom, paint)
            // The pale band Aero lays across the top third of the fill.
            paint.color = 0x55FFFFFF
            canvas.drawRect(left, top, left + span * fraction, top + (bottom - top) * 0.45f, paint)
            return
        }
        // Blocks, with a gap: the width comes from the bar's height, as it did in 9x.
        val block = (bottom - top) * 0.6f
        val step = block + 2 * ui.density
        var x = left
        val end = left + span * fraction
        while (x + block <= end) {
            canvas.drawRect(x, top, x + block, bottom, paint)
            x += step
        }
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A progress bar. `progress` and `max` work as they always do. */
fun WinUi.progressBar(max: Int = 100, progress: Int = 0): ProgressBar =
    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        this.max = max
        this.progress = progress
        isIndeterminate = false
        progressDrawable = WinProgressDrawable(this@progressBar)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18))
    }
