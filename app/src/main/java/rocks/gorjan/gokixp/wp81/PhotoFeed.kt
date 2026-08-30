package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * The user's own recent pictures and videos, for the Photos tile.
 *
 * WP8.1's Photos tile was a slideshow of the camera roll, and it is the one live tile
 * whose content the phone already has: no feed to fetch, no key to hold, nothing to keep
 * current beyond asking MediaStore what is new.
 *
 * The camera roll, and nothing else. MediaStore's idea of an image is every picture on the
 * phone - saved memes, WhatsApp forwards, app artwork, screenshots of a bank balance - and
 * a tile turning through those on a home screen is not a slideshow of anything, it is a
 * leak. Only what the camera wrote is looked at.
 *
 * Video as well as stills, interleaved by when they were taken: what a phone camera
 * records is part of the same roll, and a tile that skipped every clip would be showing a
 * gappy version of a day out. A clip is handed back marked as one, so the tile can play it
 * where its first frame would otherwise sit.
 *
 * Only the most recent handful are offered. A tile turns over every few seconds and shows
 * one picture at a time, so the whole library would be a queue nobody ever reaches the end
 * of - where the last twenty are the ones the user would recognise, which is what makes a
 * glance at the tile worth anything.
 *
 * Decoded small: stills through [ImageDecoder], which applies the EXIF rotation a camera
 * writes - read with the raw decoder, a portrait photograph arrives on its side - and
 * clips through MediaStore's own thumbnailer, which is the only thing that will produce a
 * frame at all. The frame is what the tile shows while the clip is opening, and what it
 * falls back to if it cannot be played.
 */
object PhotoFeed {

    /** How far back the tile looks. See the note above about queues. */
    const val RECENT = 20

    /**
     * Where the camera writes. MediaStore stores a trailing slash on these, so
     * `DCIM/Camera/` matches the pattern itself as well as everything under it.
     */
    private const val CAMERA_PATH = "DCIM/Camera/%"

    /** The fallback, for a camera that files its pictures somewhere else under DCIM. */
    private const val DCIM_PATH = "DCIM/%"

    /** Wide enough to fill a tile on any phone; these are drawn behind one, not viewed. */
    private const val DECODE_PX = 720

    /**
     * Android 14 lets the user grant a *selection* of photos rather than all of them,
     * which arrives as a permission of its own. Named as a string because it is an API 34
     * constant and this compiles against older artwork in the same source set.
     */
    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * What to ask for, which is a different permission on every other Android version.
     *
     * On 14 and later both are requested together: that is what makes the system offer
     * "select photos" alongside "allow all", and being handed a chosen few is a perfectly
     * good answer for a tile that only ever shows one at a time.
     */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Whether the tile may look at anything at all - all of the library, or a selection. */
    fun hasAccess(context: Context): Boolean = permissions().any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** One thing the camera recorded. */
    data class Shot(
        val uri: String,
        /** A clip rather than a still, so the tile knows there is something to play. */
        val isVideo: Boolean,
        /** When it was taken. Only used to interleave the two collections. */
        val takenAt: Long
    )

    /**
     * The [RECENT] newest shots from the camera roll, newest first.
     *
     * Queried off the main thread: MediaStore is a database, and a library of thousands
     * is not something to ask about while a tile is waiting to be drawn.
     *
     * `DCIM/Camera` first, which is where the stock camera on essentially every phone
     * writes. A few - older models, and some manufacturers' own camera apps - use another
     * folder under `DCIM` instead, so when the first look comes back empty the whole of
     * `DCIM` is tried rather than leaving the tile blank on those phones. Never wider than
     * that: outside `DCIM` is everything the phone has ever downloaded.
     */
    fun recent(context: Context, limit: Int = RECENT, onReady: (List<Shot>) -> Unit) {
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val shots = try {
                roll(resolver, CAMERA_PATH, limit).ifEmpty { roll(resolver, DCIM_PATH, limit) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the camera roll", e)
                emptyList()
            }
            main.post { onReady(shots) }
        }
    }

    /**
     * The newest [limit] stills and clips in [path], interleaved and newest first.
     *
     * Two collections rather than one - MediaStore keeps images and video apart, and there
     * is no query that spans them - so each is asked for its own newest [limit] and the
     * two are merged. Taking [limit] from each is what makes the merge correct: a roll of
     * twenty clips and one photograph has to be able to come back as twenty clips.
     */
    private fun roll(
        resolver: android.content.ContentResolver,
        path: String,
        limit: Int
    ): List<Shot> = (
        query(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, path, limit, false) +
            query(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, path, limit, true)
        )
        .sortedByDescending { it.takenAt }
        .take(limit)

    /** The newest [limit] items of one collection whose folder matches [path]. */
    private fun query(
        resolver: android.content.ContentResolver,
        collection: android.net.Uri,
        path: String,
        limit: Int,
        isVideo: Boolean
    ): List<Shot> {
        val found = mutableListOf<Shot>()
        resolver.query(
            collection,
            // The generic columns, so the same read serves images and video alike.
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(path),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            // Stopped at the limit rather than asked for with a LIMIT clause: the
            // sort-order-with-LIMIT trick is not honoured by every provider, and walking
            // twenty rows of a cursor costs nothing.
            while (cursor.moveToNext() && found.size < limit) {
                found += Shot(
                    ContentUris.withAppendedId(collection, cursor.getLong(id)).toString(),
                    isVideo,
                    cursor.getLong(added)
                )
            }
        }
        return found
    }

    /** A tenth of the heap. Evicting a picture only costs a re-decode. */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 10).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Pictures already tried and found unreadable, so a dead URI is not decoded twice. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Hands [onReady] the picture at [uri], now if it is already held and later if not.
     *
     * The callback runs on the main thread, and may hand back null: a picture can be
     * deleted between the query that found it and the tile that comes round to it.
     */
    fun load(context: Context, uri: String, onReady: (Bitmap?) -> Unit) {
        if (uri.isBlank() || uri in failed) {
            onReady(null)
            return
        }
        cache.get(uri)?.let {
            onReady(it)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val bitmap = decode(app, uri)
            if (bitmap == null) failed.add(uri) else cache.put(uri, bitmap)
            main.post { onReady(bitmap) }
        }
    }

    /** Forgets what has been read, so the next look starts from the library as it is now. */
    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    private fun decode(context: Context, uri: String): Bitmap? = try {
        val parsed = Uri.parse(uri)
        if (context.contentResolver.getType(parsed)?.startsWith("video/") == true) {
            // A clip has no still to decode. MediaStore's thumbnailer pulls a frame out of
            // it, and is also the only thing on the phone that knows how.
            context.contentResolver.loadThumbnail(
                parsed, android.util.Size(DECODE_PX, DECODE_PX), null)
        } else {
            decodeImage(context, parsed)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a picture", e)
        null
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software rather than hardware: the tile draws its backdrop into a Rect on a
            // canvas it also washes and clips, and a hardware bitmap is not welcome
            // everywhere that happens.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > DECODE_PX) {
                val scale = DECODE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    }

    private const val TAG = "WP81Photos"
}
