package rocks.gorjan.gokixp.apps.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Somebody's picture, decoded for the notification shade.
 *
 * Read here rather than through the tile's own cache, which hands its answer back on the
 * main thread and belongs to a process that may not be running: the callers are receivers
 * on a worker thread with a few moments to live, and they want the bitmap now.
 *
 * Sized down to what a notification actually shows. The stored picture is a camera
 * photograph and it has to cross a Binder transaction to reach the shade, which is a
 * limit measured in kilobytes rather than megapixels.
 */
fun facePhoto(context: Context, uri: String?): Bitmap? {
    if (uri.isNullOrBlank()) return null
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > FACE_PX) {
                val scale = FACE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        Log.w("WP81People", "Could not read somebody's picture", e)
        null
    }
}

/** How large a face is decoded for the shade. */
private const val FACE_PX = 256

/**
 * The stand-in for somebody with no picture: the shell's own mark, filling the circle.
 *
 * Without an icon on the [androidx.core.app.Person], the platform draws its own default
 * avatar - a grey circle - and then badges the notification's small icon into the corner
 * of it, so a text from a number with no photo arrives as a large nothing with a tiny
 * speech bubble stuck to it. The mark is the only part of that which says anything, so it
 * is drawn as the whole avatar instead.
 *
 * White on the accent, which is what [rocks.gorjan.gokixp.wp81.metroLook] makes of a small
 * icon anyway - the large one then reads as the same mark, larger, rather than as a second
 * unrelated thing. The [glyph] is expected to be a white silhouette; the accent is the
 * background it sits on.
 *
 * Sized and inset for [androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap],
 * which is how the callers pass a face: an adaptive bitmap shows only its middle two
 * thirds, so the colour has to reach the edges and the mark has to stay well inside them.
 */
fun glyphFace(context: Context, @DrawableRes glyph: Int): Bitmap? {
    return try {
        val mark = ContextCompat.getDrawable(context, glyph)
            ?: return null
        val bitmap = Bitmap.createBitmap(FACE_PX, FACE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ThemeManager(context).getWP81Accent())
        val side = (FACE_PX * MARK).toInt()
        val edge = (FACE_PX - side) / 2
        mark.setBounds(edge, edge, edge + side, edge + side)
        mark.draw(canvas)
        bitmap
    } catch (e: Exception) {
        Log.w("WP81People", "Could not draw a stand-in face", e)
        null
    }
}

/** How much of the square the mark is given. See the note about adaptive bitmaps above. */
private const val MARK = 0.34f
