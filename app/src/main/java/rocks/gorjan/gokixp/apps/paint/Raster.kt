package rocks.gorjan.gokixp.apps.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixel-exact drawing, the way Paint has always done it: every stroke is a run of whole
 * pixels laid down one at a time and never smoothed, so a diagonal line keeps its staircase
 * and a circle keeps its jagged rim.
 *
 * A *pen* is the shape stamped at each point of a stroke, written as a list of
 * `[dy, x0, x1]` spans around the pointer. A *row list* is the same idea for a filled
 * shape: one `[y, x0, x1]` span per scan line. Everything here trades in those two, which
 * is what keeps rectangles, ellipses and brushes landing on identical pixels.
 */
object Raster {

    /** One horizontal run of pixels: y (or dy, for a pen), first x, last x - all inclusive. */
    private const val Y = 0
    private const val X0 = 1
    private const val X1 = 2

    fun bitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)

    fun copy(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)

    /** A paint that fills whole pixels, with no anti-aliasing and no filtering. */
    fun flatPaint(): Paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        isFilterBitmap = false
        style = Paint.Style.FILL
    }

    /** The Black and white palette maps every color to one or the other. */
    fun mono(color: Int): Int =
        if (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 >= 128)
            Color.WHITE else Color.BLACK

    /** Visits every pixel of a straight line (Bresenham). */
    inline fun walk(x0: Int, y0: Int, x1: Int, y1: Int, fn: (Int, Int) -> Unit) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            fn(x, y)
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /* ---------- pens ---------- */

    fun square(n: Int): Array<IntArray> {
        val o = n / 2
        return Array(n) { intArrayOf(it - o, -o, n - 1 - o) }
    }

    fun circle(d: Int): Array<IntArray> {
        val o = d / 2
        return ellipseRows(0, 0, d - 1, d - 1)
            .map { intArrayOf(it[Y] - o, it[X0] - o, it[X1] - o) }
            .toTypedArray()
    }

    fun slash(n: Int, back: Boolean): Array<IntArray> {
        val o = n / 2
        return Array(n) {
            val x = (if (back) it else n - 1 - it) - o
            intArrayOf(it - o, x, x)
        }
    }

    /** The named brushes of Paint's brush box: "circle-7", "square-5", "slash-2", "back-8". */
    fun pen(key: String): Array<IntArray> {
        val dash = key.indexOf('-')
        val shape = key.substring(0, dash)
        val n = key.substring(dash + 1).toInt()
        return when (shape) {
            "circle" -> circle(n)
            "square" -> square(n)
            else -> slash(n, shape == "back")
        }
    }

    fun stamp(canvas: Canvas, paint: Paint, x: Int, y: Int, pen: Array<IntArray>) {
        for (s in pen) {
            canvas.drawRect(
                (x + s[X0]).toFloat(), (y + s[Y]).toFloat(),
                (x + s[X1] + 1).toFloat(), (y + s[Y] + 1).toFloat(), paint
            )
        }
    }

    fun line(canvas: Canvas, paint: Paint, x0: Int, y0: Int, x1: Int, y1: Int, pen: Array<IntArray>) {
        walk(x0, y0, x1, y1) { x, y -> stamp(canvas, paint, x, y, pen) }
    }

    fun spans(canvas: Canvas, paint: Paint, rows: List<IntArray>) {
        for (s in rows) {
            if (s[X1] >= s[X0]) {
                canvas.drawRect(
                    s[X0].toFloat(), s[Y].toFloat(),
                    (s[X1] + 1).toFloat(), (s[Y] + 1).toFloat(), paint
                )
            }
        }
    }

    /** Left, top, right, bottom of two corners, in either order. */
    fun box(x0: Int, y0: Int, x1: Int, y1: Int) =
        intArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

    /* ---------- shapes, one span per row ---------- */

    /** One `[y, x0, x1]` span per row of an ellipse inside a box, sampled at pixel centers. */
    fun ellipseRows(x0: Int, y0: Int, x1: Int, y1: Int): List<IntArray> {
        val rows = ArrayList<IntArray>(y1 - y0 + 1)
        val cx = (x0 + x1 + 1) / 2.0
        val cy = (y0 + y1 + 1) / 2.0
        val rx = (x1 - x0 + 1) / 2.0
        val ry = (y1 - y0 + 1) / 2.0
        for (y in y0..y1) {
            val dy = (y + 0.5 - cy) / ry
            val dx = rx * sqrt(max(0.0, 1 - dy * dy))
            var a = (cx - dx).roundToInt()
            var b = (cx + dx).roundToInt() - 1
            if (a > b) { a = floor(cx - 0.5).toInt(); b = ceil(cx - 0.5).toInt() }
            rows.add(intArrayOf(y, a, b))
        }
        return rows
    }

    fun roundRows(x0: Int, y0: Int, x1: Int, y1: Int): List<IntArray> {
        val r = min(8.0, min((x1 - x0 + 1) / 2.0, (y1 - y0 + 1) / 2.0))
        val rows = ArrayList<IntArray>(y1 - y0 + 1)
        for (y in y0..y1) {
            var dy = 0.0
            if (y < y0 + r) dy = (y0 + r - (y + 0.5)) / r
            else if (y > y1 - r) dy = (y + 0.5 - (y1 + 1 - r)) / r
            val inset = if (dy > 0) r - r * sqrt(max(0.0, 1 - dy * dy)) else 0.0
            rows.add(intArrayOf(y, (x0 + inset).roundToInt(), (x1 + 1 - inset).roundToInt() - 1))
        }
        return rows
    }

    fun rectRows(x0: Int, y0: Int, x1: Int, y1: Int): List<IntArray> {
        val rows = ArrayList<IntArray>(y1 - y0 + 1)
        for (y in y0..y1) rows.add(intArrayOf(y, x0, x1))
        return rows
    }

    /** A convex shape with an outline w pixels wide: the shape less the same shape w pixels in. */
    fun outlineRows(
        shape: (Int, Int, Int, Int) -> List<IntArray>,
        x0: Int, y0: Int, x1: Int, y1: Int, w: Int
    ): List<IntArray> {
        val inner = if (x1 - x0 + 1 > w * 2 && y1 - y0 + 1 > w * 2)
            shape(x0 + w, y0 + w, x1 - w, y1 - w) else emptyList()
        val byY = HashMap<Int, IntArray>(inner.size * 2)
        for (s in inner) byY[s[Y]] = s
        val out = ArrayList<IntArray>()
        for (s in shape(x0, y0, x1, y1)) {
            val i = byY[s[Y]]
            if (i == null || i[X0] > i[X1]) out.add(s)
            else {
                out.add(intArrayOf(s[Y], s[X0], i[X0] - 1))
                out.add(intArrayOf(s[Y], i[X1] + 1, s[X1]))
            }
        }
        return out
    }

    /** Even-odd scanline fill through pixel centers. */
    fun polygonRows(pts: List<IntArray>): List<IntArray> {
        if (pts.isEmpty()) return emptyList()
        val rows = ArrayList<IntArray>()
        val yMin = pts.minOf { it[1] }
        val yMax = pts.maxOf { it[1] }
        val xs = ArrayList<Double>()
        for (y in yMin..yMax) {
            val yc = y + 0.5
            xs.clear()
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                if ((a[1] + 0.5 <= yc) != (b[1] + 0.5 <= yc)) {
                    xs.add(a[0] + 0.5 + (yc - a[1] - 0.5) * (b[0] - a[0]) / (b[1] - a[1]).toDouble())
                }
            }
            xs.sort()
            var i = 0
            while (i + 1 < xs.size) {
                rows.add(intArrayOf(y, ceil(xs[i] - 0.5).toInt(), ceil(xs[i + 1] - 0.5).toInt() - 1))
                i += 2
            }
        }
        return rows
    }

    /** A cubic curve as a run of whole-pixel points. */
    fun bezier(a: IntArray, c1: IntArray, c2: IntArray, b: IntArray): List<IntArray> {
        val len = hypot((c1[0] - a[0]).toDouble(), (c1[1] - a[1]).toDouble()) +
                hypot((c2[0] - c1[0]).toDouble(), (c2[1] - c1[1]).toDouble()) +
                hypot((b[0] - c2[0]).toDouble(), (b[1] - c2[1]).toDouble())
        val n = max(8, ceil(len / 2).toInt())
        val pts = ArrayList<IntArray>(n + 1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            pts.add(intArrayOf(
                (u * u * u * a[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * b[0]).roundToInt(),
                (u * u * u * a[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * b[1]).roundToInt()
            ))
        }
        return pts
    }

    /* ---------- reading and filling whole pictures ---------- */

    fun pixel(bmp: Bitmap, x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) Color.WHITE
        else bmp.getPixel(x, y) or (0xFF shl 24)

    /** Fills the area of one exact color around a point. False when there was nothing to do. */
    fun flood(bmp: Bitmap, x: Int, y: Int, color: Int): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (x < 0 || y < 0 || x >= w || y >= h) return false
        val d = IntArray(w * h)
        bmp.getPixels(d, 0, w, 0, 0, w, h)
        val target = d[x + y * w]
        val fill = color or (0xFF shl 24)
        if (target == fill) return false
        val stack = ArrayDeque<Int>()
        stack.addLast(x + y * w)
        while (stack.isNotEmpty()) {
            val at = stack.removeLast()
            val sy = at / w
            var lx = at % w
            while (lx > 0 && d[lx - 1 + sy * w] == target) lx--
            var above = false
            var below = false
            var cx = lx
            while (cx < w && d[cx + sy * w] == target) {
                val i = cx + sy * w
                d[i] = fill
                if (sy > 0) {
                    val up = d[i - w] == target
                    if (up && !above) stack.addLast(i - w)
                    above = up
                }
                if (sy < h - 1) {
                    val dn = d[i + w] == target
                    if (dn && !below) stack.addLast(i + w)
                    below = dn
                }
                cx++
            }
        }
        bmp.setPixels(d, 0, w, 0, 0, w, h)
        return true
    }

    /** A new picture whose every pixel is read from src through fn(x, y) -> (sx, sy); misses get `fill`. */
    fun remap(src: Bitmap, w: Int, h: Int, fill: Int, fn: (Int, Int, IntArray) -> Unit): Bitmap {
        val sw = src.width
        val sh = src.height
        val s = IntArray(sw * sh)
        src.getPixels(s, 0, sw, 0, 0, sw, sh)
        val d = IntArray(w * h)
        val at = IntArray(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                fn(x, y, at)
                val sx = at[0]
                val sy = at[1]
                d[x + y * w] =
                    if (sx < 0 || sy < 0 || sx >= sw || sy >= sh) fill else s[sx + sy * sw]
            }
        }
        val out = bitmap(w, h)
        out.setPixels(d, 0, w, 0, 0, w, h)
        return out
    }

    /** Pixels of one color become see-through (a transparent selection, or text on no background). */
    fun clearColor(src: Bitmap, color: Int): Bitmap {
        val w = src.width
        val h = src.height
        val d = IntArray(w * h)
        src.getPixels(d, 0, w, 0, 0, w, h)
        val opaque = color or (0xFF shl 24)
        for (i in d.indices) if (d[i] == opaque) d[i] = 0
        val out = bitmap(w, h)
        out.setPixels(d, 0, w, 0, 0, w, h)
        return out
    }

    /** Every color turned to its opposite, the way Image > Invert Colors does. */
    fun invert(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val d = IntArray(w * h)
        src.getPixels(d, 0, w, 0, 0, w, h)
        for (i in d.indices) d[i] = d[i].inv() or (0xFF shl 24)
        val out = bitmap(w, h)
        out.setPixels(d, 0, w, 0, 0, w, h)
        return out
    }

    /* ---------- saving ---------- */

    private val VGA = intArrayOf(
        0xFF000000.toInt(), 0xFF800000.toInt(), 0xFF008000.toInt(), 0xFF808000.toInt(),
        0xFF000080.toInt(), 0xFF800080.toInt(), 0xFF008080.toInt(), 0xFFC0C0C0.toInt(),
        0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
    )

    /**
     * A Windows bitmap at 1, 4, 8 or 24 bits per pixel, written by hand because Android can
     * only compress to PNG, JPEG and WebP. Fewer colors map to the nearest of the standard
     * palette, which is what Paint's "16 Color Bitmap" and friends have always meant.
     */
    fun bmp(src: Bitmap, bits: Int = 24): ByteArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val palette = ArrayList<Int>()
        when (bits) {
            1 -> { palette.add(Color.BLACK); palette.add(Color.WHITE) }
            4 -> VGA.forEach { palette.add(it) }
            8 -> {
                VGA.forEach { palette.add(it) }
                for (r in 0..5) for (g in 0..5) for (b in 0..5)
                    palette.add(Color.rgb(r * 51, g * 51, b * 51))
                var i = 0
                while (palette.size < 256) { val v = 8 + i * 10; palette.add(Color.rgb(v, v, v)); i++ }
            }
        }

        val cache = HashMap<Int, Int>()
        fun nearest(c: Int): Int = cache.getOrPut(c and 0xFFFFFF) {
            if (bits == 1) return@getOrPut if (mono(c) == Color.WHITE) 1 else 0
            var best = 0
            var bd = Long.MAX_VALUE
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            palette.forEachIndexed { i, p ->
                val dr = (Color.red(p) - r).toLong()
                val dg = (Color.green(p) - g).toLong()
                val db = (Color.blue(p) - b).toLong()
                val dd = dr * dr + dg * dg + db * db
                if (dd < bd) { bd = dd; best = i }
            }
            best
        }

        val stride = ceil(w * bits / 32.0).toInt() * 4
        val offset = 54 + palette.size * 4
        val size = offset + stride * h
        val out = ByteArray(size)
        fun u16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        fun u32(at: Int, v: Int) {
            out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte()
            out[at + 2] = (v ushr 16).toByte(); out[at + 3] = (v ushr 24).toByte()
        }
        u16(0, 0x4D42)          // "BM"
        u32(2, size)
        u32(10, offset)
        u32(14, 40)             // BITMAPINFOHEADER
        u32(18, w)
        u32(22, h)
        u16(26, 1)
        u16(28, bits)
        u32(34, stride * h)
        u32(38, 3780)           // 96 dpi, in pixels per metre
        u32(42, 3780)
        u32(46, palette.size)
        palette.forEachIndexed { i, c ->
            out[54 + i * 4] = Color.blue(c).toByte()
            out[55 + i * 4] = Color.green(c).toByte()
            out[56 + i * 4] = Color.red(c).toByte()
        }
        for (y in 0 until h) {
            val row = offset + (h - 1 - y) * stride   // bitmaps are stored bottom-up
            for (x in 0 until w) {
                val c = px[x + y * w]
                when (bits) {
                    24 -> {
                        out[row + x * 3] = Color.blue(c).toByte()
                        out[row + x * 3 + 1] = Color.green(c).toByte()
                        out[row + x * 3 + 2] = Color.red(c).toByte()
                    }
                    8 -> out[row + x] = nearest(c).toByte()
                    4 -> {
                        val at = row + (x shr 1)
                        val v = nearest(c)
                        out[at] = (out[at].toInt() or (v shl if (x and 1 == 1) 0 else 4)).toByte()
                    }
                    else -> if (nearest(c) != 0) {
                        val at = row + (x shr 3)
                        out[at] = (out[at].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
        }
        return out
    }

    /**
     * A Windows bitmap, read back. Android decodes PNG, JPEG, GIF and WebP but not BMP, and
     * a picture Paint saved is exactly the kind of picture it should be able to open again.
     * Uncompressed 1, 4, 8, 24 and 32 bit files are read; anything else comes back null.
     */
    fun decodeBmp(bytes: ByteArray): Bitmap? {
        if (bytes.size < 54 || bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return null
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        val offset = u32(10)
        val headerSize = u32(14)
        if (headerSize < 40) return null
        val w = u32(18)
        val h = u32(22)
        val flipped = h < 0                       // a negative height means the rows run top-down
        val rows = abs(h)
        val bits = u16(28)
        val compression = u32(30)
        if (w <= 0 || rows <= 0 || compression != 0) return null
        if (bits != 1 && bits != 4 && bits != 8 && bits != 24 && bits != 32) return null

        val paletteAt = 14 + headerSize
        var paletteSize = u32(46)
        if (paletteSize == 0 && bits <= 8) paletteSize = 1 shl bits
        val palette = IntArray(paletteSize) { i ->
            val at = paletteAt + i * 4
            if (at + 2 >= bytes.size) Color.BLACK
            else Color.rgb(
                bytes[at + 2].toInt() and 0xFF,
                bytes[at + 1].toInt() and 0xFF,
                bytes[at].toInt() and 0xFF
            )
        }

        val stride = ceil(w * bits / 32.0).toInt() * 4
        if (offset + stride.toLong() * rows > bytes.size) return null
        val d = IntArray(w * rows)
        for (y in 0 until rows) {
            val row = offset + (if (flipped) y else rows - 1 - y) * stride
            for (x in 0 until w) {
                d[x + y * w] = when (bits) {
                    32 -> Color.rgb(
                        bytes[row + x * 4 + 2].toInt() and 0xFF,
                        bytes[row + x * 4 + 1].toInt() and 0xFF,
                        bytes[row + x * 4].toInt() and 0xFF
                    )
                    24 -> Color.rgb(
                        bytes[row + x * 3 + 2].toInt() and 0xFF,
                        bytes[row + x * 3 + 1].toInt() and 0xFF,
                        bytes[row + x * 3].toInt() and 0xFF
                    )
                    8 -> palette.getOrElse(bytes[row + x].toInt() and 0xFF) { Color.BLACK }
                    4 -> {
                        val byte = bytes[row + (x shr 1)].toInt() and 0xFF
                        palette.getOrElse(if (x and 1 == 0) byte shr 4 else byte and 0x0F) { Color.BLACK }
                    }
                    else -> {
                        val byte = bytes[row + (x shr 3)].toInt() and 0xFF
                        palette.getOrElse((byte shr (7 - (x and 7))) and 1) { Color.BLACK }
                    }
                }
            }
        }
        val out = bitmap(w, rows)
        out.setPixels(d, 0, w, 0, 0, w, rows)
        return out
    }

    fun encode(src: Bitmap, format: Bitmap.CompressFormat, quality: Int = 92): ByteArray {
        val out = ByteArrayOutputStream()
        src.compress(format, quality, out)
        return out.toByteArray()
    }
}
