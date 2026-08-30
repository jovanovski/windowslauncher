package rocks.gorjan.gokixp.wp81

import android.graphics.Bitmap

/**
 * A separable box blur, run three times to approximate a Gaussian.
 *
 * Simply downscaling an image and letting the draw stretch it back up does *not* blur it:
 * bilinear interpolation between a handful of surviving samples reads as a soft mosaic,
 * with the block structure still plainly visible. A real low-pass filter is needed, and
 * three box passes converge close enough to a Gaussian that the difference is invisible
 * at wallpaper scale - while costing a handful of adds per pixel instead of a kernel
 * multiply.
 *
 * Deliberately hand-rolled: RenderScript is deprecated and slated for removal, and
 * [android.graphics.RenderEffect] would put an API 31 floor under a minSdk 29 app.
 *
 * Blurring is done at a reduced working size ([WORKING_MAX_DIMENSION]); the result is
 * stretched back up at draw time, which costs nothing extra and hides nothing, because
 * the image has no high frequencies left to lose.
 */
object Blur {

    /** Longest edge the blur actually runs at. Bigger buys no visible quality. */
    private const val WORKING_MAX_DIMENSION = 400

    /** Box radius at full strength, in working-size pixels. See [apply] for the curve. */
    private const val MAX_RADIUS = 40

    private const val PASSES = 3

    /**
     * Blurs [source] by [amount], 0 (untouched) to 1, on a squared response curve.
     *
     * Returns a new bitmap; [source] is left alone. Returns [source] itself when there is
     * nothing to do, so callers must not blindly recycle the result.
     */
    fun apply(source: Bitmap, amount: Float): Bitmap {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0.01f) return source

        val working = downscale(source)
        // Squared, not linear. Perceived blur climbs with the radius far faster than the
        // radius climbs with the slider: a third of the way along, a linear mapping was
        // already at radius 13 on the 400px working image - a wash of colour - and the
        // remaining two thirds of the travel bought differences nobody can see. Squaring
        // spreads the range that is actually worth having, a photo pushed gently back but
        // still legible, across the first half of the slider where a hand naturally lands.
        val radius = (strength * strength * MAX_RADIUS).toInt().coerceAtLeast(1)

        val w = working.width
        val h = working.height
        val a = IntArray(w * h)
        working.getPixels(a, 0, w, 0, 0, w, h)
        if (working !== source) working.recycle()

        val b = IntArray(w * h)
        repeat(PASSES) {
            boxBlurHorizontal(a, b, w, h, radius)
            boxBlurVertical(b, a, w, h, radius)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= WORKING_MAX_DIMENSION) return source
        val scale = WORKING_MAX_DIMENSION / longest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * One horizontal box pass, using a sliding window so the cost is independent of the
     * radius: each step adds the pixel entering the window and drops the one leaving.
     * Edges clamp rather than wrap, so the border does not bleed round the image.
     */
    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val span = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0

            // Prime the window at x = 0, clamping everything left of the edge.
            for (i in -radius..radius) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sumA += p ushr 24 and 0xFF
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
            }

            for (x in 0 until w) {
                dst[row + x] = (sumA / span shl 24) or (sumR / span shl 16) or
                        (sumG / span shl 8) or (sumB / span)

                val outIndex = (x - radius).coerceIn(0, w - 1)
                val inIndex = (x + radius + 1).coerceIn(0, w - 1)
                val out = src[row + outIndex]
                val inp = src[row + inIndex]
                sumA += (inp ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                sumR += (inp shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sumG += (inp shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sumB += (inp and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** The same pass down each column. */
    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val span = radius * 2 + 1
        for (x in 0 until w) {
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0

            for (i in -radius..radius) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sumA += p ushr 24 and 0xFF
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
            }

            for (y in 0 until h) {
                dst[y * w + x] = (sumA / span shl 24) or (sumR / span shl 16) or
                        (sumG / span shl 8) or (sumB / span)

                val out = src[(y - radius).coerceIn(0, h - 1) * w + x]
                val inp = src[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                sumA += (inp ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                sumR += (inp shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sumG += (inp shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sumB += (inp and 0xFF) - (out and 0xFF)
            }
        }
    }
}
