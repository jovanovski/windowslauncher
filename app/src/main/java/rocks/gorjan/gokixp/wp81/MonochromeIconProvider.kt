package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import rocks.gorjan.gokixp.NotificationListenerService

/**
 * Resolves the glyph a third-party app's Start tile should show.
 *
 * Windows Phone tiles are flat: a single white glyph on an accent fill. Android apps
 * don't ship WP tile art, so we look for the closest thing they do ship, in order:
 *
 *  1. **The themed-icon (monochrome) layer.** Android 13's Material You icon - artwork
 *     the developer authored specifically to be drawn in one colour. This is the right
 *     answer when it exists, and it needs no notification and no permission.
 *  2. **The notification small icon.** Android requires these to be flat silhouettes,
 *     so they suit a tile well, but they only exist once an app has actually posted a
 *     notification. Harvested opportunistically, so this fills in over time.
 *  3. **The full-colour launcher icon**, unmodified. Always recognisable - and honest
 *     to the platform: real WP8.1 third-party tiles used whatever art the developer
 *     supplied, so a Start screen mixing flat glyphs with full-colour logos is what
 *     WP8.1 actually looked like. Deliberately *not* an auto-generated silhouette,
 *     which turns detailed icons into unreadable blobs.
 */
class MonochromeIconProvider(private val context: Context) {

    /** What we found, so callers can decide whether to tint and how to size. */
    sealed class Glyph {
        /**
         * Flat artwork; caller tints it (normally white on the accent fill).
         *
         * [contentRatio] is how much of its own canvas the artwork actually covers, so
         * the caller can scale every glyph to the same optical size. See [measureContentRatio].
         */
        data class Monochrome(val drawable: Drawable, val contentRatio: Float = 1f) : Glyph()

        /** The app's real icon; must be drawn as-is, never tinted. */
        data class FullColor(val drawable: Drawable, val contentRatio: Float = 1f) : Glyph()
    }

    fun glyphFor(packageName: String, fallback: Drawable?): Glyph? {
        monochromeLayer(packageName)?.let {
            return Glyph.Monochrome(it, ratioFor("mono:$packageName", it))
        }
        NotificationListenerService.getSmallIcon(context, packageName)?.let {
            return Glyph.Monochrome(it, ratioFor("notif:$packageName", it))
        }
        return fallback?.let { Glyph.FullColor(it, ratioFor("full:$packageName", it)) }
    }

    private val ratioCache = mutableMapOf<String, Float>()

    /**
     * Forgets cached measurements for a package.
     *
     * Its artwork has changed, so the proportion of its canvas the content covers has to be
     * measured again - otherwise a new icon is drawn scaled for the old one.
     */
    fun invalidate(packageName: String) {
        ratioCache.keys.removeAll { it.endsWith(":$packageName") }
    }

    /** Cached [measureContentRatio]; measuring means rasterising, so do it once per app. */
    fun ratioFor(key: String, drawable: Drawable): Float =
        ratioCache.getOrPut(key) { measureContentRatio(drawable) }

    /**
     * The Android 13+ themed-icon layer, or null when the platform is older or the app
     * ships no monochrome artwork. [AdaptiveIconDrawable.getMonochrome] is API 33; the
     * app's minSdk is 29, so this is a real branch, not a formality.
     */
    private fun monochromeLayer(packageName: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            (icon as? AdaptiveIconDrawable)?.monochrome
        } catch (e: Exception) {
            Log.d(TAG, "No monochrome layer for $packageName: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WP81Icons"

        private const val PROBE_PX = 72

        /** Anything below this is treated as unmeasurable and left unscaled. */
        private const val MIN_RATIO = 0.2f

        /**
         * How much of its own canvas a drawable's visible content actually covers, from 0
         * to 1.
         *
         * Source artwork pads itself wildly differently: an adaptive icon's monochrome
         * layer sits inside the central 72dp safe zone of a 108dp canvas and so covers
         * about two thirds, while a notification small icon or a flat app logo fills its
         * bounds. Drawn at a single fixed size, those land at visibly different optical
         * sizes on neighbouring tiles.
         *
         * Rather than guessing per source - which mis-sizes any app that does not follow
         * the convention - this rasterises the drawable once and measures the alpha
         * bounding box, so the caller can scale each glyph to a consistent optical size.
         */
        fun measureContentRatio(drawable: Drawable): Float {
            return try {
                // Measured through the *same* transform the tile draws with. The ImageView
                // uses FIT_CENTER, which preserves aspect; measuring inside a forced square
                // stretched any non-square icon before the bounding box was taken, so its
                // content was mis-measured and it ended up scaled to the wrong size - which
                // is how one app's glyph came out visibly bigger than its neighbours'.
                val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: PROBE_PX
                val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: PROBE_PX
                val scale = PROBE_PX / maxOf(intrinsicW, intrinsicH).toFloat()
                val boxW = (intrinsicW * scale).toInt().coerceAtLeast(1)
                val boxH = (intrinsicH * scale).toInt().coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, boxW, boxH)
                drawable.draw(canvas)

                val pixels = IntArray(boxW * boxH)
                bmp.getPixels(pixels, 0, boxW, 0, 0, boxW, boxH)
                bmp.recycle()

                var left = boxW; var top = boxH; var right = -1; var bottom = -1
                for (y in 0 until boxH) {
                    for (x in 0 until boxW) {
                        // 8 of 255 ignores anti-aliased fringes and near-transparent glows.
                        if ((pixels[y * boxW + x] ushr 24) > 8) {
                            if (x < left) left = x
                            if (x > right) right = x
                            if (y < top) top = y
                            if (y > bottom) bottom = y
                        }
                    }
                }
                if (right < left || bottom < top) return 1f

                // Against the longer edge of the box, which is what FIT_CENTER fits to.
                val ratio = maxOf(right - left + 1, bottom - top + 1) /
                    maxOf(boxW, boxH).toFloat()
                if (ratio < MIN_RATIO) 1f else ratio
            } catch (e: Exception) {
                Log.w(TAG, "Could not measure glyph content", e)
                1f
            }
        }
    }
}
