package rocks.gorjan.gokixp.apps.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log

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
