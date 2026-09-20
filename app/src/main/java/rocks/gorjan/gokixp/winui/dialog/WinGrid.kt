package rocks.gorjan.gokixp.winui.dialog

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Controls drawn on a Windows dialog's own pixel grid.
 *
 * The sibling [rocks.gorjan.gokixp.winui.WinUi] builds ordinary Android views in dp, which is
 * what a program wants. This is for the other job: reproducing a *particular* dialog - one we
 * have a 1:1 screenshot of - exactly. Everything here is measured in **design pixels**, the
 * pixels of that screenshot, and a [WinLayout] works out how many device pixels one is worth.
 * So the numbers in a layout file are the numbers a pixel ruler reads off the screenshot, and
 * they stay true whatever the window is scaled to.
 *
 * What a control *is* lives in the view classes; what it *looks like* lives in a [WinSkin],
 * one per shell. Adding a shell means writing a skin, not touching a layout.
 */

/** Something that knows the scale and the skin in force for the views under it. */
interface WinHost {
    val unitPx: Float
    val skin: WinSkin
}

/** The scale in force here, or a guess from the display density outside a [WinLayout]. */
fun View.winUnit(): Float = winHost()?.unitPx ?: (resources.displayMetrics.density * 0.9f)

/** The skin in force here; Windows Classic when a control is used outside a [WinLayout]. */
fun View.winSkin(): WinSkin = winHost()?.skin ?: ClassicSkin

private fun View.winHost(): WinHost? {
    var p = parent
    while (p != null) {
        if (p is WinHost) return p
        p = (p as? View)?.parent
    }
    return null
}

/** A design size in device pixels, for code that has to size something itself. */
fun View.winPx(designPx: Int): Int = (designPx * winUnit()).roundToInt()

/** The state a control is drawn in. Windows calls these normal, hot, pushed and disabled. */
enum class WinState { NORMAL, HOT, PRESSED, DISABLED }

/**
 * Splits a Windows caption at its `&` accelerator: the ampersand goes away and the character
 * after it gets an underline. `&&` is a literal ampersand.
 */
class WinCaption(raw: String) {
    val text: String

    /** Index into [text] of the underlined character, or -1. */
    val accelerator: Int

    init {
        val sb = StringBuilder()
        var acc = -1
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '&' && i + 1 < raw.length) {
                if (raw[i + 1] == '&') {
                    sb.append('&'); i += 2; continue
                }
                if (acc < 0) acc = sb.length
                i++
                continue
            }
            sb.append(ch)
            i++
        }
        text = sb.toString()
        accelerator = acc
    }
}

/**
 * A canvas that thinks in design pixels.
 *
 * Bounds are device-pixel floats, exclusive at the right and bottom the way [Canvas] wants
 * them; [px] and [fillDesign] convert. The bevel helpers are the Windows 95 edge vocabulary -
 * the later shells drew rounded gradients instead and reach for [roundRect] and [gradient].
 */
class WinEdges(val c: Canvas, val unit: Float) {

    /** One design pixel, never thinner than a device pixel. */
    val lw: Float = max(1f, unit.roundToInt().toFloat())

    private val p = Paint()
    private val rect = RectF()

    /** Device-pixel position of design pixel [v]'s leading edge. */
    fun px(v: Int): Float = (v * unit).roundToInt().toFloat()

    fun px(v: Float): Float = (v * unit).roundToInt().toFloat()

    fun fill(l: Float, t: Float, r: Float, b: Float, color: Int) {
        if (r <= l || b <= t) return
        p.reset()
        p.color = color
        c.drawRect(l, t, r, b, p)
    }

    /** Fills an inclusive design-pixel rectangle. */
    fun fillDesign(l: Int, t: Int, r: Int, b: Int, color: Int) =
        fill(px(l), px(t), px(r + 1), px(b + 1), color)

    /** A one-pixel rectangle outline. */
    fun outline(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float = lw) {
        fill(l, t, r, t + width, color)
        fill(l, b - width, r, b, color)
        fill(l, t, l + width, b, color)
        fill(r - width, t, r, b, color)
    }

    /** A filled rounded rectangle; [radius] is in design pixels. */
    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        if (r <= l || b <= t) return
        p.reset()
        p.isAntiAlias = true
        p.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius * unit, radius * unit, p)
    }

    /** A rounded rectangle outline one design pixel thick, stroked on the given bounds. */
    fun roundOutline(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, width: Float = lw) {
        if (r <= l || b <= t) return
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.color = color
        rect.set(l + width / 2f, t + width / 2f, r - width / 2f, b - width / 2f)
        c.drawRoundRect(rect, radius * unit, radius * unit, p)
    }

    /**
     * A vertical gradient, optionally clipped to rounded corners. [stops] are fractions of the
     * height; pass null for evenly spaced colours.
     */
    fun gradient(
        l: Float, t: Float, r: Float, b: Float,
        colors: IntArray, stops: FloatArray? = null,
        radius: Float = 0f,
    ) {
        if (r <= l || b <= t) return
        p.reset()
        p.isAntiAlias = radius > 0f
        p.shader = LinearGradient(0f, t, 0f, b, colors, stops, Shader.TileMode.CLAMP)
        rect.set(l, t, r, b)
        if (radius > 0f) c.drawRoundRect(rect, radius * unit, radius * unit, p) else c.drawRect(rect, p)
        p.shader = null
    }

    /** A gradient running from the top-left corner to the bottom-right, as Luna's combo button does. */
    fun diagonalGradient(l: Float, t: Float, r: Float, b: Float, from: Int, to: Int) {
        if (r <= l || b <= t) return
        p.reset()
        p.shader = LinearGradient(l, t, r, b, from, to, Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, b, p)
        p.shader = null
    }

    fun fillPath(path: Path, color: Int) {
        p.reset()
        p.isAntiAlias = true
        p.color = color
        c.drawPath(path, p)
    }

    // ---- The Windows 95 edge vocabulary -----------------------------------------------------

    /**
     * A raised edge - buttons, the tab page, the monitor bezel.
     *
     *     WWWWWWWWD      white outer top/left, dark outer bottom/right,
     *     W......SD      shadow one pixel in from the bottom/right
     *     WSSSSSSSD
     *     DDDDDDDDD
     */
    fun raised(l: Float, t: Float, r: Float, b: Float, hi: Int, light: Int, shadow: Int, dark: Int) {
        fill(l, t, r - lw, t + lw, hi)
        fill(l, t, l + lw, b - lw, hi)
        fill(l + lw, t + lw, r - 2 * lw, t + 2 * lw, light)
        fill(l + lw, t + lw, l + 2 * lw, b - 2 * lw, light)
        fill(l + lw, b - 2 * lw, r - lw, b - lw, shadow)
        fill(r - 2 * lw, t + lw, r - lw, b - lw, shadow)
        fill(l, b - lw, r, b, dark)
        fill(r - lw, t, r, b, dark)
    }

    /**
     * A sunken client edge - list boxes, edit fields, the body of a combo.
     *
     *     SSSSSSSSW      shadow outer, dark inner, white outer bottom/right,
     *     SD.....LW      "light" one pixel in from the bottom/right
     *     SLLLLLLLW
     *     WWWWWWWWW
     */
    fun sunken(l: Float, t: Float, r: Float, b: Float, hi: Int, light: Int, shadow: Int, dark: Int) {
        fill(l, t, r - lw, t + lw, shadow)
        fill(l, t, l + lw, b - lw, shadow)
        fill(l + lw, t + lw, r - 2 * lw, t + 2 * lw, dark)
        fill(l + lw, t + lw, l + 2 * lw, b - 2 * lw, dark)
        fill(l + lw, b - 2 * lw, r - lw, b - lw, light)
        fill(r - 2 * lw, t + lw, r - lw, b - lw, light)
        fill(l, b - lw, r, b, hi)
        fill(r - lw, t, r, b, hi)
    }

    /** The etched groove a group box is drawn with: a shadow rectangle and a white one under it. */
    fun etched(l: Float, t: Float, r: Float, b: Float, shadow: Int, hi: Int) {
        outline(l, t, r - lw, b - lw, shadow)
        outline(l + lw, t + lw, r, b, hi)
    }

    /**
     * The little raised square inside a combo box or a spin control: same idea as [raised], but
     * the white starts a pixel in and the dark ring sits a pixel further out, which is how
     * Windows draws a button living inside somebody else's sunken frame.
     */
    fun smallRaised(l: Float, t: Float, r: Float, b: Float, hi: Int, shadow: Int, dark: Int) {
        fill(l + lw, t + lw, r - 2 * lw, t + 2 * lw, hi)
        fill(l + lw, t + lw, l + 2 * lw, b - 2 * lw, hi)
        fill(l + lw, b - 2 * lw, r - lw, b - lw, shadow)
        fill(r - 2 * lw, t + lw, r - lw, b - lw, shadow)
        fill(l, b - lw, r, b, dark)
        fill(r - lw, t, r, b, dark)
    }

    /** The same square, pushed in. */
    fun smallPressed(l: Float, t: Float, r: Float, b: Float, shadow: Int, dark: Int) {
        fill(l + lw, t + lw, r - lw, t + 2 * lw, shadow)
        fill(l + lw, t + lw, l + 2 * lw, b - lw, shadow)
        fill(l, b - lw, r, b, dark)
        fill(r - lw, t, r, b, dark)
    }

    // ---- Glyphs -----------------------------------------------------------------------------

    /**
     * A solid arrow of the kind that sits in a combo box or a spin button: [rows] rows of odd
     * widths, widest first when [down], centred on design column [cx] starting at design row
     * [top]. Four rows gives the 7-5-3-1 combo arrow, two the 3-1 spin arrow.
     */
    fun arrow(cx: Int, top: Int, rows: Int, down: Boolean, color: Int) {
        for (i in 0 until rows) {
            val k = if (down) rows - 1 - i else i
            fillDesign(cx - k, top + i, cx + k, top + i, color)
        }
    }

    /** A smooth solid triangle, which is how the Aero shells draw the same glyph. */
    fun triangle(cx: Float, cy: Float, halfWidth: Float, height: Float, down: Boolean, color: Int) {
        val path = Path()
        val dir = if (down) 1f else -1f
        path.moveTo(cx - halfWidth, cy - dir * height / 2f)
        path.lineTo(cx + halfWidth, cy - dir * height / 2f)
        path.lineTo(cx, cy + dir * height / 2f)
        path.close()
        fillPath(path, color)
    }

    /**
     * Luna's chevron: an outline triangle rather than a solid one, two arms two pixels thick
     * meeting at a point. [cx] is a design column, [top] a design row.
     */
    fun chevron(cx: Int, top: Int, color: Int) {
        // The 9x6 mask out of Luna's ComboButtonGlyph.bmp, as rows of (from, to) design columns.
        for (i in 0 until 5) {
            fillDesign(cx - 4 + i, top + i, cx - 3 + i, top + i, color)
            fillDesign(cx + 3 - i, top + i, cx + 4 - i, top + i, color)
        }
        fillDesign(cx, top + 5, cx, top + 5, color)
    }
}
