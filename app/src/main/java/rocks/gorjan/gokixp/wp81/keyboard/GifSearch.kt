package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import rocks.gorjan.gokixp.theme.ThemeManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * One GIF, in the two sizes the keyboard has any use for.
 *
 * Deliberately not the whole of what the service returns. A picker needs something small
 * enough to animate a screenful of at once and something large enough to be worth sending,
 * and everything between those two is weight carried through the panel for nothing.
 */
internal data class Gif(
    val id: String,
    /** Small and animated: what the grid draws. */
    val previewUrl: String,
    /** What goes into the message. */
    val sendUrl: String,
    /** The preview's own proportions, so the grid can lay a cell out before it has the file. */
    val width: Int,
    val height: Int
)

/**
 * Where the GIFs come from.
 *
 * GIPHY, over its plain HTTP API, on the same shape as [rocks.gorjan.gokixp.wp81.NewsImages]:
 * a couple of background threads, an [LruCache] in front of them, and every answer handed back
 * on the main thread. There is no client library and there does not need to be one - two
 * endpoints, one of which is the other with a query string.
 *
 * **A key is required and is not shipped with the app.** GIPHY hands them out per person and
 * per application, so there is no key that could sensibly be built into a launcher: one in the
 * source would be one key answering for every install, against one rate limit, in a public
 * repository. Each user pastes in their own on the keyboard's settings page, and until one is
 * there the panel says so plainly rather than looking broken. See
 * [ThemeManager.getWP81KeyboardGiphyKey].
 */
internal object GifSearch {

    /** How many come back. A screenful is about nine; this is a few scrolls' worth. */
    private const val LIMIT = 30

    /**
     * What the search is willing to return.
     *
     * `pg-13` rather than `g`, which is close to useless, and rather than `r`, which is not
     * what a keyboard should put one tap from a message to anybody.
     */
    private const val RATING = "pg-13"

    private const val ENDPOINT = "https://api.giphy.com/v1/gifs"
    private const val TIMEOUT_MS = 8000

    /** Where a GIF on its way into a message is written. See [download]. */
    private const val OUTBOX = "keyboard-gifs"

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /**
     * The last few searches, so that backspacing through a query does not re-ask for every
     * prefix on the way out. Keyed by the query, with trending under the empty string.
     */
    private val results = LruCache<String, List<Gif>>(24)

    /**
     * The preview files themselves. An eighth of the heap, which at the size these are
     * fetched at is a few hundred of them - far more than a session goes through.
     */
    private val previews = object : LruCache<String, ByteArray>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(4096)
    ) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size / 1024
    }

    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Whether there is a key to search with at all. The panel asks before it asks for GIFs. */
    fun hasKey(context: Context): Boolean = key(context).isNotEmpty()

    /**
     * Read on each use rather than held.
     *
     * The panel is built once and kept for the life of the process - an input method's
     * process outlives any one app it comes up in - so a key cached at construction would be
     * the key that was there when the user first opened a text box, and pasting one into
     * settings would not take effect until the phone was restarted.
     */
    private fun key(context: Context): String =
        ThemeManager(context).getWP81KeyboardGiphyKey()

    /**
     * Trending when [query] is blank, matches when it is not.
     *
     * [onReady] runs on the main thread, and is handed null - as distinct from an empty list -
     * when the search could not be made at all. The panel says different things about the two:
     * nothing matched is an answer, and no answer is a failure.
     */
    fun find(context: Context, query: String, onReady: (List<Gif>?) -> Unit) {
        val q = query.trim()
        val apiKey = key(context)
        if (apiKey.isEmpty()) {
            onReady(null)
            return
        }
        results.get(q)?.let {
            onReady(it)
            return
        }
        executor.execute {
            val url = if (q.isEmpty()) {
                "$ENDPOINT/trending?api_key=$apiKey&limit=$LIMIT&rating=$RATING"
            } else {
                "$ENDPOINT/search?api_key=$apiKey&q=${Uri.encode(q)}&limit=$LIMIT&rating=$RATING"
            }
            val found = fetchJson(url)?.let(::parse)
            if (found != null) results.put(q, found)
            main.post { onReady(found) }
        }
    }

    /**
     * The bytes of one preview, now if they are held and later if not.
     *
     * Bytes rather than a decoded drawable, because a `GifDrawable` is a running animation
     * with a frame buffer and a scheduler behind it - one cached and handed to two grids would
     * be one animation drawn in two places, and freeing it under either would empty both.
     */
    fun preview(url: String, onReady: (ByteArray?) -> Unit) {
        if (url.isEmpty() || url in failed) {
            onReady(null)
            return
        }
        previews.get(url)?.let {
            onReady(it)
            return
        }
        executor.execute {
            val bytes = fetchBytes(url)
            if (bytes == null) failed.add(url) else previews.put(url, bytes)
            main.post { onReady(bytes) }
        }
    }

    /**
     * Puts [gif] in a file the app can hand out, and answers with it.
     *
     * A content URI is the only way a GIF reaches another app - see the commit path in
     * [WP81KeyboardService] - and a content URI needs a file behind it. The cache directory,
     * because the receiving app copies what it is given and nothing here needs the file
     * afterwards; the name is the GIF's own id, so picking the same one twice is one fetch.
     */
    fun download(context: Context, gif: Gif, onReady: (File?) -> Unit) {
        executor.execute {
            val outbox = File(context.cacheDir, OUTBOX)
            val file = File(outbox, "${gif.id}.gif")
            if (file.isFile && file.length() > 0L) {
                main.post { onReady(file) }
                return@execute
            }
            val bytes = fetchBytes(gif.sendUrl)
            val written = if (bytes == null) null else try {
                outbox.mkdirs()
                file.writeBytes(bytes)
                file
            } catch (e: Exception) {
                null
            }
            main.post { onReady(written) }
        }
    }

    // ---------------------------------------------------------------- the wire

    private fun fetchJson(url: String): JSONObject? =
        fetchBytes(url)?.let {
            try {
                JSONObject(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchBytes(url: String): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The answer, reduced to [Gif]s.
     *
     * Every field is read defensively and an entry that cannot supply both a preview and
     * something to send is dropped rather than carried as a hole in the grid. GIPHY gives
     * widths and heights as strings, which is why they are parsed rather than read as ints.
     */
    private fun parse(json: JSONObject): List<Gif> {
        val array = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Gif>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val images = entry.optJSONObject("images") ?: continue
            // Downsampled first: same width as the plain fixed-width rendition with a
            // fraction of the frames, which is what a hundred-pixel cell can show anyway.
            val preview = pick(images, "fixed_width_downsampled", "fixed_width_small", "preview_gif")
                ?: continue
            // And the other way for the one being sent: the largest that is still small
            // enough to go through a message, falling back to whatever there is.
            val send = pick(images, "downsized_medium", "downsized", "original") ?: continue
            val url = preview.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val sendUrl = send.optString("url").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                Gif(
                    id = entry.optString("id").takeIf { it.isNotEmpty() } ?: continue,
                    previewUrl = url,
                    sendUrl = sendUrl,
                    width = preview.optString("width").toIntOrNull() ?: 1,
                    height = preview.optString("height").toIntOrNull() ?: 1
                )
            )
        }
        return out
    }

    /** The first of [names] the answer actually carries. */
    private fun pick(images: JSONObject, vararg names: String): JSONObject? {
        for (name in names) images.optJSONObject(name)?.let { return it }
        return null
    }
}
