package rocks.gorjan.gokixp.wp81

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/** One story, as a tile shows it and as tapping it opens it. */
data class NewsStory(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    /** The story's picture, if the feed offered one. Drawn behind the headline. */
    val image: String,
    /** When it was published, or 0 when the feed did not say. */
    val publishedAt: Long = 0L
)

/** A feed the user can turn on. */
data class NewsSource(val id: String, val name: String, val url: String)

/**
 * The feeds on offer.
 *
 * Public RSS rather than a news API: no key to obtain, hold or leak, no quota to run out
 * at the worst moment, and the request is identical for every user of the launcher -
 * nothing about the phone or the person holding it goes anywhere.
 *
 * Kept to outlets that have published a stable feed for years and are unlikely to move
 * it. A feed that dies simply contributes nothing; the tile carries on with the others.
 */
object NewsSources {

    val ALL: List<NewsSource> = listOf(
        NewsSource("bbc", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        NewsSource("guardian", "The Guardian", "https://www.theguardian.com/world/rss"),
        NewsSource("npr", "NPR World", "https://feeds.npr.org/1004/rss.xml"),
        NewsSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        NewsSource("dw", "Deutsche Welle", "https://rss.dw.com/rdf/rss-en-world"),
        NewsSource("sky", "Sky News", "https://feeds.skynews.com/feeds/rss/world.xml"),
        NewsSource("verge", "The Verge", "https://www.theverge.com/rss/index.xml"),
        NewsSource("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index")
    )

    /** The one that is on when the user has never said otherwise. */
    const val DEFAULT_ID = "bbc"

    fun byId(id: String): NewsSource? = ALL.firstOrNull { it.id == id }
}

/**
 * Reads the enabled feeds and keeps a shuffled run of stories for the News tile.
 *
 * Shuffled rather than concatenated: with three feeds on, running them in order would
 * give the tile twenty minutes of one outlet before it reached the next, which is not
 * what turning three of them on was meant to produce. Interleaved by position first -
 * everybody's top story, then everybody's second - so the front pages lead, and shuffled
 * within each round so no outlet is permanently first.
 */
class NewsFeed(private val onUpdated: () -> Unit) {

    @Volatile
    private var stories: List<NewsStory> = emptyList()

    /**
     * The same stories, kept as their feeds delivered them.
     *
     * The tile wants one run with everything mixed together; a reader wants each outlet's
     * own front page, in the order that outlet put it in. Both come from the one fetch.
     */
    @Volatile
    private var bySource: Map<String, List<NewsStory>> = emptyMap()

    @Volatile
    private var fetchedAt = 0L

    @Volatile
    private var fetching = false

    /** Which feeds the last fetch was for, so turning one on re-reads immediately. */
    @Volatile
    private var fetchedFor: List<String> = emptyList()

    private val main = Handler(Looper.getMainLooper())

    /** The current run of stories. Empty until the first fetch lands. */
    fun stories(): List<NewsStory> = stories

    /** Each enabled outlet's own stories, keyed by its name, in feed order. */
    fun bySource(): Map<String, List<NewsStory>> = bySource

    /** Whether a fetch is under way, so a reader can say so rather than look empty. */
    fun isFetching(): Boolean = fetching

    /**
     * Fetches, unless the same feeds were read recently enough that the answer would be
     * the same.
     *
     * The interval is deliberately long: a Start screen is glanced at, not read, and the
     * news does not turn over fast enough to justify waking the radio more often. Changing
     * which feeds are on bypasses it, because then the answer *has* changed.
     */
    fun refreshIfStale(enabled: List<String>, force: Boolean = false) {
        if (fetching) return
        val sources = enabled.mapNotNull { NewsSources.byId(it) }
        if (sources.isEmpty()) {
            if (stories.isNotEmpty()) {
                stories = emptyList()
                bySource = emptyMap()
                fetchedFor = emptyList()
                main.post { onUpdated() }
            }
            return
        }
        val changed = enabled != fetchedFor
        val fresh = stories.isNotEmpty() &&
            SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS
        if (!force && !changed && fresh) return

        fetching = true
        Thread {
            val gathered = sources.map { source ->
                try {
                    fetch(source)
                } catch (e: Exception) {
                    Log.w("NewsFeed", "Could not read ${source.name}", e)
                    emptyList()
                }
            }
            fetching = false
            val merged = newestFirst(gathered)
            if (merged.isNotEmpty()) {
                stories = merged
                bySource = sources.mapIndexed { i, source ->
                    source.name to gathered.getOrElse(i) { emptyList() }
                }.filter { it.second.isNotEmpty() }.toMap()
                fetchedAt = SystemClock.elapsedRealtime()
                fetchedFor = enabled
                main.post { onUpdated() }
            }
        }.start()
    }

    /**
     * The newest first, whoever published it.
     *
     * Sorting by time rather than interleaving by position mixes the outlets anyway - they
     * publish all day - and answers the question the run is actually for, which is what has
     * just happened rather than which paper said it. A story with no date on it goes last:
     * absent is not new.
     */
    private fun newestFirst(feeds: List<List<NewsStory>>): List<NewsStory> =
        feeds.flatten()
            .sortedWith(compareByDescending<NewsStory> { it.publishedAt }.thenBy { it.title })
            .take(MAX_STORIES)

    private fun fetch(source: NewsSource): List<NewsStory> {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // Some feeds refuse the default Java agent outright.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != 200) {
                Log.w("NewsFeed", "${source.name} returned ${connection.responseCode}")
                return emptyList()
            }
            return connection.inputStream.use { parse(it, source.name) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Pulls the title, summary and link out of each entry.
     *
     * Handles RSS and Atom together: `item`/`entry` and `description`/`summary` are the
     * same idea under two names, and an Atom link carries its address in an attribute
     * rather than as text. A pull parser rather than a DOM - this wants a few short
     * strings out of a few dozen kilobytes and has no use for a tree.
     */
    private fun parse(input: java.io.InputStream, sourceName: String): List<NewsStory> {
        val items = mutableListOf<NewsStory>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var inItem = false
        var title = ""
        var summary = ""
        var link = ""
        var image = ""
        var published = ""
        var tag: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    when {
                        tag.equals("item", true) || tag.equals("entry", true) -> {
                            inItem = true
                            title = ""
                            summary = ""
                            link = ""
                            image = ""
                            published = ""
                        }
                        // Feeds attach pictures in whichever of the three ways their
                        // publisher settled on years ago. The first one found wins, since
                        // a feed that offers several offers the same picture at several
                        // sizes and the first is the one it led with.
                        inItem && image.isEmpty() && (
                            tag.equals("media:thumbnail", true) ||
                                tag.equals("media:content", true) ||
                                tag.equals("enclosure", true)
                            ) -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val medium = parser.getAttributeValue(null, "medium").orEmpty()
                            val looksLikeImage = type.startsWith("image") ||
                                medium == "image" ||
                                tag.equals("media:thumbnail", true)
                            if (url.isNotBlank() && looksLikeImage) image = url
                        }
                        // Atom puts the address on the tag: <link href="..."/>
                        inItem && tag.equals("link", true) && link.isEmpty() -> {
                            parser.getAttributeValue(null, "href")?.let { link = it }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (!inItem) continue
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    when {
                        tag.equals("title", true) -> title += text
                        tag.equals("description", true) || tag.equals("summary", true) ->
                            summary += text
                        tag.equals("link", true) -> link += text
                        // RSS says pubDate, Atom says published or updated, and the older
                        // feeds say dc:date. All four mean the same thing.
                        published.isEmpty() && (
                            tag.equals("pubDate", true) ||
                                tag.equals("published", true) ||
                                tag.equals("updated", true) ||
                                tag.equals("dc:date", true)
                            ) -> published = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                        inItem = false
                        if (title.isNotBlank()) {
                            items.add(
                                NewsStory(
                                    title = clean(title),
                                    summary = clean(summary),
                                    link = link.trim(),
                                    source = sourceName,
                                    image = image.trim(),
                                    publishedAt = parseDate(published)
                                )
                            )
                        }
                        if (items.size >= PER_SOURCE) return items
                    }
                    tag = null
                }
            }
        }
        return items
    }

    /**
     * A feed's idea of a date, as milliseconds.
     *
     * Tried against the handful of shapes feeds actually use rather than one: RFC-822 with
     * and without seconds, with a zone name or an offset, and ISO-8601 for the Atom feeds.
     * Anything else is treated as undated, which puts it at the back rather than at the
     * front with a date of zero pretending to be 1970.
     */
    private fun parseDate(value: String): Long {
        val text = value.trim()
        if (text.isEmpty()) return 0L
        for (pattern in DATE_PATTERNS) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                format.isLenient = true
                return format.parse(text)?.time ?: continue
            } catch (e: Exception) {
                // Next shape.
            }
        }
        Log.w("NewsFeed", "Unreadable date: $text")
        return 0L
    }

    /** Feeds put markup in their summaries; a tile shows text. */
    private fun clean(value: String): String =
        value.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android) GokiXP live tile"
        const val TIMEOUT_MS = 10_000

        /**
         * How deep into each feed to read.
         *
         * A section is a front page and is meant to be scrolled; a dozen stories was a
         * glance. Feeds run to a few dozen items and weigh tens of kilobytes, so reading
         * the lot costs nothing the request has not already paid for - the rows are what
         * cost, and those are built a screenful at a time as the reader gets to them.
         */
        const val PER_SOURCE = 60

        /** And how long the newest-first run is. That one is a summary, not a section. */
        const val MAX_STORIES = 20

        val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ss"
        )

        const val REFRESH_MS = 30 * 60 * 1000L
    }
}
