package rocks.gorjan.gokixp.wp81

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pictures for the News tile, fetched once and remembered.
 *
 * Loaded on demand rather than up front: a run of thirty stories is thirty photographs,
 * and the tile shows one at a time. Asking for them as they come round means a tile that
 * has been turning for a minute has fetched six, not thirty.
 *
 * Decoded small - these are drawn behind a tile, not viewed - and in RGB_565, which halves
 * the memory for an image that is about to be dimmed under a colour wash anyway.
 */
object NewsImages {

    private const val DECODE_PX = 480

    /** A twelfth of the heap. Evicting a photo only costs a re-fetch. */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 12).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Everything already tried and found wanting, so a dead URL is not fetched twice. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Hands [onReady] the picture at [url], now if it is already held and later if not.
     *
     * The callback runs on the main thread, and may never run at all - there is no picture
     * for a great many stories, and a tile with none simply shows its colour.
     */
    fun load(url: String, onReady: (Bitmap?) -> Unit) {
        if (url.isBlank() || url in failed) {
            onReady(null)
            return
        }
        cache.get(url)?.let {
            onReady(it)
            return
        }
        executor.execute {
            val bitmap = fetch(url)
            if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { stream ->
                // Read once into memory: the stream cannot be rewound for the second
                // decode, and a news thumbnail is tens of kilobytes.
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > DECODE_PX * 2) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w("NewsImages", "Could not fetch a story picture", e)
        null
    }

    private const val TIMEOUT_MS = 10_000
}
