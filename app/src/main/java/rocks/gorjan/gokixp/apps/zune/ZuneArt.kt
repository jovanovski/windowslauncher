package rocks.gorjan.gokixp.apps.zune

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Album art for the lists, decoded off the main thread and remembered.
 *
 * Keyed by album rather than by track, which is what makes art in a song list affordable
 * at all: a thousand songs are usually a few dozen records, so a list that looks like a
 * thousand decodes is a few dozen.
 *
 * Art is deliberately decoded small. These are 48dp squares in a list; decoding a 3000px
 * cover to draw it at 140px is most of the cost of the whole screen.
 */
object ZuneArt {

    private const val DECODE_PX = 160

    /** A tenth of the heap. Album art is the only thing this app holds in memory. */
    private val cache = object : LruCache<Long, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 10).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    // Two threads: enough to keep a scrolling list ahead of the eye, few enough that a
    // library full of art cannot crowd out everything else the launcher is doing.
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

    /**
     * Puts [albumId]'s cover into [target], or leaves it empty if there is none.
     *
     * The view is tagged with what it was asked for, so a decode that finishes after the
     * list has moved on is dropped instead of painting one record's art over another's.
     */
    fun into(context: Context, albumId: Long, target: ImageView, onMissing: () -> Unit = {}) {
        target.tag = albumId
        val cached = cache.get(albumId)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(null)
        onMissing()
        executor.execute {
            val bitmap = decode(context, albumId)
            main.post {
                if (target.tag != albumId) return@post
                if (bitmap != null) target.setImageBitmap(bitmap) else onMissing()
            }
        }
    }

    /** The cover as a bitmap, for callers that want it for something other than a list. */
    fun load(context: Context, albumId: Long, onReady: (Bitmap?) -> Unit) {
        val cached = cache.get(albumId)
        if (cached != null) {
            onReady(cached)
            return
        }
        executor.execute {
            val bitmap = decode(context, albumId)
            main.post { onReady(bitmap) }
        }
    }

    private fun decode(context: Context, albumId: Long): Bitmap? {
        if (albumId <= 0) return null
        val uri = ContentUris.withAppendedId(ALBUM_ART, albumId)
        return try {
            // Bounds first, so the full-size cover is never actually decoded.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > DECODE_PX * 2) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            bitmap?.also { cache.put(albumId, it) }
        } catch (e: Exception) {
            // A missing cover is the normal case, not a fault: most libraries have some.
            null
        }
    }
}
