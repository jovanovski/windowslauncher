package rocks.gorjan.gokixp.winui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The surfaces a Windows program is built out of, drawn rather than screenshotted.
 *
 * One drawable per kind of surface, each taking the shell's [WinPalette] and the pixel
 * density, so the same call produces a 98 bevel, an XP capsule or an Aero gradient with
 * nothing at the call site to say which. That is the whole point: a program written against
 * these changes shell when the user does, and a new shell is a new palette rather than a
 * fourth copy of every window.
 *
 * Everything is drawn with antialiasing off on Classic and on elsewhere, because a 98 edge
 * is a run of single pixels and a blurred one reads as a mistake, while an Aero corner that
 * is not rounded smoothly reads as one too.
 */

/** One device pixel, or as near as the density gets. Edges are never thinner than this. */
internal fun hair(density: Float): Float = max(1f, density.roundToInt().toFloat())

/**
 * The Windows 9x three-dimensional edge: raised for a button, sunken for a field.
 *
 * Two rings. Raised puts white outside and grey inside at the top left, black outside and
 * mid-grey inside at the bottom right; sunken swaps them. The pressed form is the sunken
 * one a notch darker, which is what a 98 button does when it goes in.
 */
class BevelDrawable(
    private val pal: WinPalette,
    private val density: Float,
    private val style: Style,
    private val fill: Int = pal.face
) : Drawable() {

    enum class Style {
        /** A button at rest, a toolbar, a status pane that sticks out. */
        RAISED,
        /** A button being pressed, or one whose command is in force. */
        PRESSED,
        /** An edit field, a list box, the inside of a combo. */
        SUNKEN,
        /** The single line a group box and a separator are etched with. */
        ETCHED,
        /** No edge at all, just the fill - for the shells that draw flat. */
        FLAT
    }

    private val p = Paint().apply { isAntiAlias = false }
    private val lw = hair(density)

    private fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, c: Canvas) {
        if (r <= l || b <= t) return
        p.color = color
        c.drawRect(l, t, r, b, p)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bo = b.bottom.toFloat()
        rect(l, t, r, bo, fill, canvas)
        when (style) {
            Style.FLAT -> Unit
            Style.RAISED -> {
                rect(l, t, r - lw, t + lw, pal.hilight, canvas)
                rect(l, t, l + lw, bo - lw, pal.hilight, canvas)
                rect(l + lw, bo - 2 * lw, r - lw, bo - lw, pal.shadow, canvas)
                rect(r - 2 * lw, t + lw, r - lw, bo - lw, pal.shadow, canvas)
                rect(l, bo - lw, r, bo, pal.dkShadow, canvas)
                rect(r - lw, t, r, bo, pal.dkShadow, canvas)
            }
            Style.SUNKEN -> {
                rect(l, t, r - lw, t + lw, pal.shadow, canvas)
                rect(l, t, l + lw, bo - lw, pal.shadow, canvas)
                rect(l + lw, t + lw, r - 2 * lw, t + 2 * lw, pal.dkShadow, canvas)
                rect(l + lw, t + lw, l + 2 * lw, bo - 2 * lw, pal.dkShadow, canvas)
                rect(l + lw, bo - 2 * lw, r - lw, bo - lw, pal.light, canvas)
                rect(r - 2 * lw, t + lw, r - lw, bo - lw, pal.light, canvas)
                rect(l, bo - lw, r, bo, pal.hilight, canvas)
                rect(r - lw, t, r, bo, pal.hilight, canvas)
            }
            Style.PRESSED -> {
                rect(l, t, r - lw, t + lw, pal.dkShadow, canvas)
                rect(l, t, l + lw, bo - lw, pal.dkShadow, canvas)
                rect(l + lw, t + lw, r - 2 * lw, t + 2 * lw, pal.shadow, canvas)
                rect(l + lw, t + lw, l + 2 * lw, bo - 2 * lw, pal.shadow, canvas)
                rect(l, bo - lw, r, bo, pal.hilight, canvas)
                rect(r - lw, t, r, bo, pal.hilight, canvas)
            }
            Style.ETCHED -> {
                outline(canvas, l, t, r - lw, bo - lw, pal.shadow)
                outline(canvas, l + lw, t + lw, r, bo, pal.hilight)
            }
        }
    }

    private fun outline(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        rect(l, t, r, t + lw, color, c)
        rect(l, b - lw, r, b, color, c)
        rect(l, t, l + lw, b, color, c)
        rect(r - lw, t, r, b, color, c)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * A rounded, bordered, vertically graded rectangle: an XP or Aero button, field, row or
 * toolbar, depending entirely on what it is handed.
 *
 * A gradient with equal stops is a flat fill, and a border of zero alpha is no border, so
 * one class covers every flat surface in the three later shells without a branch.
 */
class GradientPanelDrawable(
    private val top: Int,
    private val bottom: Int,
    private val border: Int,
    private val density: Float,
    private val radiusDp: Float,
    /** A second, paler gradient over the upper half - Aero's glassy sheen. */
    private val gloss: Boolean = false,
    /** Which sides get a border. Everything by default. */
    private val sides: Sides = Sides.ALL
) : Drawable() {

    enum class Sides { ALL, TOP_BOTTOM, BOTTOM, NONE }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hair(density)
    }
    private val flat = Paint().apply { isAntiAlias = false }
    private val radius = radiusDp * density

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = hair(density) / 2f
        val r = RectF(
            b.left + inset, b.top + inset,
            b.right - inset, b.bottom - inset
        )
        body.shader =
            if (top == bottom) null
            else LinearGradient(
                0f, r.top, 0f, r.bottom,
                top, bottom, Shader.TileMode.CLAMP
            )
        body.color = top
        canvas.drawRoundRect(r, radius, radius, body)
        body.shader = null

        if (gloss) {
            val half = RectF(r.left, r.top, r.right, r.top + r.height() / 2f)
            body.shader = LinearGradient(
                0f, half.top, 0f, half.bottom,
                0x40FFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(half, radius, radius, body)
            body.shader = null
        }

        if (border ushr 24 == 0) return
        line.color = border
        when (sides) {
            Sides.ALL -> canvas.drawRoundRect(r, radius, radius, line)
            Sides.TOP_BOTTOM -> {
                flat.color = border
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + hair(density), flat)
                canvas.drawRect(b.left.toFloat(), b.bottom - hair(density), b.right.toFloat(), b.bottom.toFloat(), flat)
            }
            Sides.BOTTOM -> {
                flat.color = border
                canvas.drawRect(b.left.toFloat(), b.bottom - hair(density), b.right.toFloat(), b.bottom.toFloat(), flat)
            }
            Sides.NONE -> Unit
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The dotted focus rectangle, which every one of these shells still draws the same way.
 *
 * One pixel on, one off, in black over whatever is underneath. Worth having because it is
 * the only thing that says which row a keyboard would act on, and because its absence is
 * one of the tells that a window was drawn rather than run.
 */
class FocusRectDrawable(private val density: Float) : Drawable() {
    private val p = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeWidth = hair(density)
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(hair(density), hair(density)), 0f
        )
        color = 0xFF000000.toInt()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = hair(density) / 2f
        canvas.drawRect(
            b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, p
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
