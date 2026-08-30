package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

/**
 * Draws an SVG from the assets, in white, at whatever size it is given.
 *
 * Android has no SVG decoder, which usually means converting a set like this to PNGs and
 * choosing a resolution to be wrong at - too small on a wide tile, too large everywhere
 * else, and several times the size on disk. It does, however, ship a parser for SVG *path
 * data*: [PathParser] turns a `d` attribute into a [Path]. These icons are one or two
 * filled paths on a 76-unit square, which is exactly what that covers, so they can be read
 * as they are and drawn as vectors.
 *
 * White by default rather than by editing the files: the set is drawn black, a tile is a
 * block of colour with white on it, and a drawable that knows what colour it should be is
 * one less thing that has to be true of a thousand files on disk.
 */
class SvgIcon private constructor(
    private val paths: List<Path>,
    private val viewportWidth: Float,
    private val viewportHeight: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** Reused across draws: scaling the paths themselves would compound every pass. */
    private val scaled = Path()

    override fun draw(canvas: Canvas) {
        if (paths.isEmpty() || bounds.isEmpty) return
        val scale = minOf(
            bounds.width() / viewportWidth,
            bounds.height() / viewportHeight
        )
        // Centred in whatever box it was given, so a square icon in a wider view sits in
        // the middle rather than in the corner.
        val dx = bounds.left + (bounds.width() - viewportWidth * scale) / 2f
        val dy = bounds.top + (bounds.height() - viewportHeight * scale) / 2f

        val saved = canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        for (path in paths) canvas.drawPath(path, paint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    /** Honoured so an ImageView's own tint reaches it, the way it would any other drawable. */
    override fun setTintList(tint: android.content.res.ColorStateList?) {
        paint.color = tint?.defaultColor ?: Color.WHITE
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = viewportWidth.toInt()

    override fun getIntrinsicHeight(): Int = viewportHeight.toInt()

    companion object {

        /** Reads [path] out of the assets, or null if it is not an SVG this can draw. */
        fun fromAsset(context: Context, path: String): SvgIcon? = try {
            context.assets.open(path).use { parse(it) }
        } catch (e: Exception) {
            Log.w("SvgIcon", "Could not read $path", e)
            null
        }

        private fun parse(input: java.io.InputStream): SvgIcon? {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            val paths = mutableListOf<Path>()
            var width = DEFAULT_VIEWPORT
            var height = DEFAULT_VIEWPORT

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "svg" -> {
                        // The viewBox is what the path coordinates are in; width and
                        // height are only what the file suggests it be drawn at.
                        val box = parser.getAttributeValue(null, "viewBox")
                            ?.trim()?.split(Regex("[ ,]+"))
                        if (box != null && box.size == 4) {
                            width = box[2].toFloatOrNull() ?: DEFAULT_VIEWPORT
                            height = box[3].toFloatOrNull() ?: DEFAULT_VIEWPORT
                        } else {
                            width = parser.getAttributeValue(null, "width")
                                ?.toFloatOrNull() ?: DEFAULT_VIEWPORT
                            height = parser.getAttributeValue(null, "height")
                                ?.toFloatOrNull() ?: DEFAULT_VIEWPORT
                        }
                    }

                    "path" -> {
                        val data = parser.getAttributeValue(null, "d") ?: continue
                        val built = try {
                            PathParser.createPathFromPathData(data)
                        } catch (e: Exception) {
                            Log.w("SvgIcon", "Unreadable path data", e)
                            null
                        } ?: continue
                        // Holes are cut out with an even-odd rule in this set; without it
                        // an icon drawn as one outline fills solid.
                        if (parser.getAttributeValue(null, "fill-rule") == "evenodd") {
                            built.fillType = Path.FillType.EVEN_ODD
                        }
                        paths.add(built)
                    }
                }
            }
            return if (paths.isEmpty()) null else SvgIcon(paths, width, height)
        }

        /** What the set is drawn on, and the fallback for a file that does not say. */
        private const val DEFAULT_VIEWPORT = 76f
    }
}
