package rocks.gorjan.gokixp.apps.news

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.NewsFeed
import rocks.gorjan.gokixp.wp81.NewsImages
import rocks.gorjan.gokixp.wp81.NewsStory
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.MetroPanorama

/**
 * The News tile, opened out into something you can read.
 *
 * A tile shows one headline at a time, which is right for a wall and useless for catching
 * up. This is the same stories laid out as a panorama, on the pattern Zune set: sections
 * named across the top, moved between by pushing the page rather than by aiming at a tab.
 *
 * The sections are the outlets themselves, plus a "latest" that runs them together - which
 * is the tile's own shuffled order, so the app and the tile agree about what is new. With
 * one outlet turned on there is nothing to run together, so that section is dropped rather
 * than shown twice under two names.
 *
 * It reads the feed the shell already holds instead of fetching its own: the tile has
 * usually been at it for a while by the time anyone opens this, and a second copy of the
 * same stories would only disagree with the first.
 */
class NewsApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val feed: NewsFeed,
    private val onOpenStory: (NewsStory) -> Unit,
    private val onRefresh: () -> Unit,
    private val enabledFeeds: () -> Set<String>,
    private val onFeedsChanged: (Set<String>) -> Unit
) {

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama

    /** The settings page, while it is up. */
    private var settingsPage: View? = null

    /** The column each section fills, by the name across the top of it. */
    private val columns = linkedMapOf<String, LinearLayout>()

    /**
     * Sections whose rows have not been built yet.
     *
     * Eight outlets twelve stories deep is a hundred rows, each with an image view and two
     * text views, and building them all before the window could be shown is what made the
     * app take a visible moment to open. Only the section being looked at is built; the
     * rest are built as they are reached, which is a frame's work each.
     */
    private val stale = mutableSetOf<String>()

    /**
     * Stories a section holds but has not built rows for yet.
     *
     * A source read sixty deep is sixty rows, and nobody scrolls to the end of one before
     * deciding whether to. Each section builds a screenful, and the next as the reader
     * reaches the bottom of the last.
     */
    private val pending = mutableMapOf<String, MutableList<NewsStory>>()

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The wordmark is the panorama's title layer rather than a heading above it, so it
        // drifts as the sections are pulled past underneath it. The one command this app
        // has goes in the corner the title leaves empty: there is no room for an app bar
        // over a panorama, since the foot of the screen is the shell's keys and the sides
        // are the panorama's own gesture.
        panorama.setTitle("news")
        panorama.setTitleAccessory(
            ImageView(context).apply {
                setImageResource(R.drawable.wp81_glyph_settings)
                imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener { showSettings() }
                TiltEffect.apply(this)
            },
            SETTINGS_DP
        )
        buildSections()
        panorama.onPageSettled = { index ->
            columns.keys.elementAtOrNull(index)?.let { bindSection(it) }
        }
        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        bind()
        return root
    }

    /**
     * One section per outlet, and one for all of them.
     *
     * Built from what the feed has rather than from what is enabled, so an outlet whose
     * feed is down does not leave an empty section named after it.
     */
    private fun buildSections() {
        val outlets = feed.bySource()
        val names = buildList {
            if (outlets.size > 1) add(LATEST)
            addAll(outlets.keys)
        }.ifEmpty { listOf(LATEST) }

        for (name in names) {
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
            }
            columns[name] = list
            panorama.addPage(
                name.lowercase(),
                PullToRefresh(context, name).apply {
                    isFillViewport = true
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
                }
            )
        }
    }

    /**
     * Marks every section as needing rebuilding, and rebuilds the one on screen.
     *
     * Called when the feed changes under the app - a pull, or the tile's own refresh
     * landing - so what is being read is up to date and what is not yet built stays that
     * way until it is.
     */
    fun bind() {
        stale.addAll(columns.keys)
        columns.keys.elementAtOrNull(panorama.currentPage())?.let { bindSection(it) }
    }

    // ---------------------------------------------------------------- settings

    /**
     * The app's own settings: which feeds it reads.
     *
     * Here rather than on the shell's settings page, where they were: they are this app's
     * business and nobody looking for them would think to leave it. Shown as a page over
     * the panorama, turned in the way the shell's pages turn.
     */
    private fun showSettings() {
        if (settingsPage != null) return

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
            setPadding(dp(PAGE_MARGIN_DP), dp(6), dp(PAGE_MARGIN_DP), dp(20))
        }
        // The same header the shell's own pages carry, arrow and all: this is a page in a
        // program of the shell, and it should be left the same way as any other.
        page.addView(
            rocks.gorjan.gokixp.wp81.MetroPageHeader(context, palette).apply {
                setTitle("settings")
                onBack = { handleBack() }
            },
            wide()
        )
        page.addView(TextView(context).apply {
            text = "sources"
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(0, dp(10), 0, dp(6))
        }, wide())

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val chosen = enabledFeeds().toMutableSet()
        // A tickable square each, and several of them can be on at once - which is what
        // the square says. These used to be a plain filled block that meant the same thing
        // whether it was a switch or one of a set; the shell has one answer to that now.
        for (source in rocks.gorjan.gokixp.wp81.NewsSources.ALL) {
            val row = rocks.gorjan.gokixp.wp81.MetroChoiceRow(
                context, palette, source.name, round = false)
            row.set(source.id in chosen)
            row.onPicked = { on ->
                if (on) chosen.add(source.id) else chosen.remove(source.id)
                onFeedsChanged(chosen.toSet())
            }
            list.addView(row, wide())
        }
        page.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        settingsPage = page
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        rocks.gorjan.gokixp.wp81.MetroPageTransition(page).playIn()
    }

    /**
     * Closes the settings page, and says whether there was one.
     *
     * The host asks before it closes the window: back means "out of this page" while one
     * is open, and "out of the app" only once it is not.
     */
    fun handleBack(): Boolean {
        val page = settingsPage ?: return false
        settingsPage = null
        rocks.gorjan.gokixp.wp81.MetroPageTransition(page).playOut {
            root.removeView(page)
            // Whatever was turned on or off, the sections show it now.
            bind()
        }
        return true
    }

    /** Fills one section from the feed as it currently stands. */
    private fun bindSection(name: String) {
        if (name !in stale) return
        val list = columns[name] ?: return
        stale.remove(name)
        list.removeAllViews()

        val stories =
            if (name == LATEST) feed.stories() else feed.bySource()[name].orEmpty()
        if (stories.isEmpty()) {
            list.addView(note(
                if (feed.isFetching()) "reading the feeds…"
                else "nothing here yet.  turn a feed on in settings"
            ), wide())
            return
        }
        pending[name] = stories.drop(CHUNK).toMutableList()
        for (story in stories.take(CHUNK)) {
            list.addView(storyRow(story, showSource = name == LATEST), wide())
        }
    }

    /** Adds the next screenful of a section, if it has any left. */
    private fun extendSection(name: String) {
        val waiting = pending[name] ?: return
        if (waiting.isEmpty()) return
        val list = columns[name] ?: return
        val next = waiting.take(CHUNK)
        repeat(next.size) { waiting.removeAt(0) }
        for (story in next) {
            list.addView(storyRow(story, showSource = name == LATEST), wide())
        }
    }

    /**
     * A story: its picture, its headline, and the first line of what it says.
     *
     * The picture is what makes this a front page rather than a list of links - and the
     * one at the top gets a full-width one, the way a paper leads with something.
     */
    private fun storyRow(story: NewsStory, showSource: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Top-aligned, and stated rather than relied on: the headline's first line
            // sits level with the top edge of the picture beside it, which is what makes a
            // column of these read as a page rather than as a list of cards.
            gravity = Gravity.TOP
            setPadding(0, dp(ROW_GAP_DP), 0, dp(ROW_GAP_DP))
            isClickable = true
            setOnClickListener { onOpenStory(story) }
            TiltEffect.apply(this)
        }

        // Only where there is one. A picture column held open by an invisible view left
        // every story without a photograph indented past a blank the width of one, which
        // reads as a missing image rather than as a story that never had one. Gone, not
        // invisible, so the headline takes the row.
        //
        // As tall as whatever sits beside it, where there is a picture: a fixed square
        // left a stub against a headline, a summary and a credit line, with the last of
        // them hanging past the bottom of it.
        if (story.image.isNotBlank()) {
            val art = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(palette.inactive)
                visibility = View.GONE
            }
            NewsImages.load(story.image) { bitmap: Bitmap? ->
                if (bitmap == null) return@load
                art.setImageBitmap(bitmap)
                art.visibility = View.VISIBLE
            }
            row.addView(
                art,
                LinearLayout.LayoutParams(dp(THUMB_DP), LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = dp(12) }
            )
        }

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val title = TextView(context).apply {
            this.text = story.title
            // No leading above the first line: a font's own ascent padding is what was
            // holding the headline a few pixels below the picture it belongs to.
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            typeface = font(R.font.segoeui_semilight)
            textSize = 17f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            setLineSpacing(0f, 0.95f)
        }
        text.addView(title, wide())
        if (story.summary.isNotBlank()) {
            text.addView(TextView(context).apply {
                this.text = story.summary
                typeface = font(R.font.segoeui_semilight)
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(3), 0, 0)
                setLineSpacing(0f, 0.95f)
            }, wide())
        }

        // Who said it and when. The outlet is named only where several are mixed together:
        // under a section called "bbc world", every line of it is from BBC World.
        val credit = listOfNotNull(
            story.source.takeIf { showSource && it.isNotBlank() },
            published(story.publishedAt)
        ).joinToString("  \u00b7  ")
        if (credit.isNotBlank()) {
            text.addView(TextView(context).apply {
                this.text = credit
                typeface = font(R.font.segoeui_semilight)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.accent)
                setPadding(0, dp(4), 0, 0)
            }, wide())
        }
        // Lifted by the space a font leaves above its capitals. Turning off font padding
        // takes away the padding, not the ascent: the line box still begins where the
        // tallest possible letter would, and every headline here starts a few pixels below
        // that. Measuring the cap height is the only way to know how far.
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            topMargin = -capGapOf(title)
        })
        return row
    }

    /** How far below the top of its line box a font's capitals actually start, in pixels. */
    private fun capGapOf(view: TextView): Int {
        val metrics = view.paint.fontMetrics
        val caps = android.graphics.Rect()
        view.paint.getTextBounds("H", 0, 1, caps)
        return ((-metrics.ascent) - caps.height()).toInt().coerceAtLeast(0)
    }

    /**
     * A page that re-reads the feeds when it is pulled down from the top.
     *
     * The gesture every reader on the phone has, and the only one that makes sense here:
     * the app has no toolbar to hang a refresh button off, and a panorama's own drag is
     * sideways, so down is free.
     *
     * Deliberately not a spinner that follows the finger - the page does not move. What it
     * does is tell the reader it is reading, which the sections do themselves the moment
     * the fetch starts.
     */
    private inner class PullToRefresh(
        context: Context,
        private val section: String
    ) : ScrollView(context) {

        private var downY = 0f
        private var pulled = false
        private var startedAtTop = false

        /**
         * The rows are all clickable, so a press lands on one of them and this never sees
         * the DOWN through onTouchEvent - by the time the scroll view claims the gesture,
         * only MOVEs are left and there is nothing to measure the pull against. An
         * interceptor sees every DOWN, whoever ends up handling it.
         */
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                downY = ev.y
                pulled = false
                startedAtTop = scrollY == 0
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            // Near the end of what has been built, build some more. Measured against the
            // content rather than a row count, so it holds however tall the rows come out.
            val content = getChildAt(0) ?: return
            val remaining = content.height - (t + height)
            if (remaining < height) extendSection(section)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    pulled = false
                    startedAtTop = scrollY == 0
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val travelled = event.y - downY
                    if (startedAtTop && scrollY == 0 && travelled > 0f) {
                        // The page comes with the finger. A gesture that showed nothing
                        // until it had gone far enough gave the reader no way to know
                        // there was a gesture there at all.
                        pullBy(travelled)
                    }
                    // From the top, and once per gesture: a long drag down a short page
                    // should ask for the news once, not on every frame.
                    if (!pulled && startedAtTop && scrollY == 0 &&
                        travelled > dp(PULL_DP) && !feed.isFetching()
                    ) {
                        pulled = true
                        onRefresh()
                        showRefreshing(section)
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> release()
            }
            return super.onTouchEvent(event)
        }

        /** Damped, and hard-limited: the page gives less the further it is pulled. */
        private fun pullBy(travelled: Float) {
            val content = getChildAt(0) ?: return
            content.animate().cancel()
            val limit = PULL_GIVE_DP * resources.displayMetrics.density
            content.translationY = limit * (1f - kotlin.math.exp(-travelled / limit))
        }

        private fun release() {
            val content = getChildAt(0) ?: return
            if (content.translationY == 0f) return
            content.animate()
                .translationY(0f)
                .setDuration(PULL_RETURN_MS)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .start()
        }
    }

    /**
     * Says the feeds are being read, at the top of the section that asked.
     *
     * Removed by the rebuild that follows a successful fetch, and by a timer in case one
     * never comes: a feed that is simply down should not leave the reader watching a line
     * that says something is happening.
     */
    private fun showRefreshing(section: String) {
        val list = columns[section] ?: return
        val line = note("reading the feeds…")
        list.addView(line, 0, wide())
        list.postDelayed({
            if (line.parent === list) list.removeView(line)
        }, REFRESH_NOTE_MS)
    }

    /**
     * When a story was published, said the way someone reading it would.
     *
     * The time alone for today's news, which is nearly all of it and the case where the
     * date would be noise; the date as well for anything older. A story the feed did not
     * date says nothing rather than guessing.
     */
    private fun published(at: Long): String? {
        if (at <= 0L) return null
        val locale = java.util.Locale.getDefault()
        val moment = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val time = java.text.SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            locale
        ).format(moment.time)

        // Named days for the two that have names. "27 Aug" for something published this
        // morning is technically true and makes the reader do the arithmetic; past
        // yesterday the date is the useful thing and the day name is not.
        val day = when (daysAgo(moment)) {
            0 -> "today"
            1 -> "yesterday"
            else -> java.text.SimpleDateFormat("d MMM", locale).format(moment.time)
        }
        return "$day, $time"
    }

    /** Whole days between [moment] and now, counting from midnight rather than by hours. */
    private fun daysAgo(moment: java.util.Calendar): Int {
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val elapsed = midnight.timeInMillis - moment.timeInMillis
        if (elapsed <= 0L) return 0
        return (elapsed / DAY_MS + 1).toInt()
    }

    private fun note(message: String) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_semilight)
        textSize = 15f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), dp(16), dp(18))
        gravity = Gravity.START
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val LATEST = "latest"

        /** How far the page has to be pulled from the top before it re-reads the feeds. */
        const val PULL_DP = 96

        /** How far the page itself travels while being pulled. */
        const val PULL_GIVE_DP = 64f
        const val PULL_RETURN_MS = 260L

        /** How long the "reading" line stays up if nothing ever arrives. */
        const val REFRESH_NOTE_MS = 12_000L
        const val PAGE_MARGIN_DP = 22
        /** Picture width. Its height is whatever the text beside it comes to. */
        const val THUMB_DP = 96

        /** The settings key beside the wordmark. */
        const val SETTINGS_DP = 38

        /**
         * Air above and below each story, so half of it sits between any two.
         *
         * A list of headlines with pictures needs the gap to be doing the separating -
         * there are no rules between these rows and no cards around them, which is the
         * whole of the style.
         */
        const val ROW_GAP_DP = 14

        /** Rows built at a time, and added again as the reader nears the end of them. */
        const val CHUNK = 12

        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
