package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * One shelf of the grid: a header - null for a flat run of search results - and the emoji
 * under it. Kept at file scope, not nested in [EmojiPanel.GridView], because building the
 * list of these is [EmojiPanel]'s job (it knows about recents and about what is being
 * searched for) while drawing and scrolling them is [EmojiPanel.GridView]'s, and Kotlin's
 * `private` is per class body - a class nested in one of the two would not be visible to
 * the other.
 */
private data class EmojiSection(val label: String?, val icon: Drawable?, val items: List<Emoji>)

/**
 * One drawn row of [EmojiPanel]'s grid: a section header, or a run of cells up to `columns`
 * wide. File scope for the same reason [EmojiSection] is - Kotlin does not allow a class
 * nested directly inside an `inner class` the way [EmojiPanel.GridView] is nested inside
 * [EmojiPanel], so this sits beside it instead, private to the file both are built from.
 */
private sealed class EmojiRow {
    data class Header(val label: String, val icon: Drawable?) : EmojiRow()
    data class Items(val items: List<Emoji>) : EmojiRow()
}

/**
 * Which emoji were used last, most-recent-first.
 *
 * Its own [SharedPreferences] file rather than the shell's `taskbar_widget_prefs`: that file
 * is read and written wholesale by the Drive backup this app already has, and which emoji
 * someone happened to type most recently is exactly the kind of device-local scribble that
 * a backup should not be carrying to a second phone.
 */
private class RecentEmoji(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Most-recent-first. */
    fun list(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split(DELIMITER).filter { it.isNotEmpty() }
    }

    /**
     * Notes that [glyph] was used, without writing anything yet.
     *
     * Held back on purpose. Emoji are picked in runs, and writing each one straight into the
     * recents means the recents row reorders itself between one tap and the next - so the
     * emoji that was under the thumb a moment ago has moved, and picking two of the same
     * thing in a row becomes a game of catch. Nothing changes on screen until the panel is
     * closed, and [commit] is what makes it stick.
     */
    fun record(glyph: String) {
        pending.remove(glyph)
        pending.add(0, glyph)
    }

    /** Writes what was picked, newest first. Called when the panel goes away. */
    fun commit() {
        if (pending.isEmpty()) return
        val next = pending.toMutableList()
        for (glyph in list()) if (glyph !in next) next.add(glyph)
        while (next.size > MAX) next.removeAt(next.lastIndex)
        prefs.edit().putString(KEY, next.joinToString(DELIMITER)).apply()
        pending.clear()
    }

    /** Picked since the panel was opened, most recent first. */
    private val pending = mutableListOf<String>()

    private companion object {
        const val PREFS_FILE = "wp81_keyboard"
        const val KEY = "wp81_kb_emoji_recents"

        // U+0001. No emoji sequence contains it - every one is built from codepoints
        // Unicode assigned a graphic meaning to, and a control character is not one - so it
        // is safe as a plain join delimiter without a real serialisation format behind it.
        const val DELIMITER = "\u0001"

        const val MAX = 30
    }
}

/**
 * The keyboard's emoji key, opened.
 *
 * Not a popup and not an overlay: it takes the key grid's own place, the way the symbol
 * pages do, because that is the one way an input method's window does not visibly resize as
 * you switch to it. [setMetrics] is how it is kept exactly as tall as the grid it replaces -
 * see the note there.
 *
 * Two things shape everything below it:
 *
 *  - **Tapping an emoji must not close this.** The point of a full search-and-browse panel
 *    is to drop several emoji into a message in a row, and a picker that closes itself after
 *    one would turn every second emoji into a fresh tap of the keyboard's own emoji key. So
 *    there is [onBackToLetters] instead - an explicit `abc` - and [onBackspace], because
 *    picking several in a row is exactly when you want to take one back.
 *  - **The grid can be several thousand emoji long**, and [KeyView] - forty views painting
 *    themselves - does not scale to that. So unlike the letter keyboard, which is
 *    [KeyView]s all the way down, only the two keys that do not vary with the data (`abc`,
 *    backspace) are real [KeyView]s here; the search bar is a handful of ordinary widgets,
 *    since there is exactly one of it, and the grid itself is one [View] that lays its own
 *    rows out on a canvas and scrolls them by hand, the way [KeyView] draws a key's own face
 *    rather than being three child views for the same reason.
 */
@SuppressLint("ViewConstructor")
internal class EmojiPanel(context: Context, private var palette: WP81Palette) : ViewGroup(context) {

    var onEmojiPicked: ((String) -> Unit)? = null
    var onBackToLetters: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onSearchTapped: (() -> Unit)? = null

    /** A GIF was tapped. Getting one into the message is the host's problem - see [GifGrid]. */
    var onGifPicked: ((Gif) -> Unit)? = null

    /** Which of the two halves of this panel is showing. */
    enum class Mode { EMOJI, GIF }

    /**
     * Emoji or GIFs.
     *
     * The same panel either way, and deliberately so: the control row, the search box and the
     * backspace are the same three things whichever is underneath, and a second panel would
     * have been the same frame drawn twice. Only what fills the space below the row changes,
     * and what the search box searches.
     */
    var mode: Mode = Mode.EMOJI
        set(value) {
            if (field == value) return
            field = value
            gifKey.key = gifKey.key.copy(label = if (value == Mode.GIF) EMOJI_LABEL else GIF_LABEL)
            gifKey.invalidate()
            grid.visibility = if (value == Mode.EMOJI) VISIBLE else GONE
            gifGrid.visibility = if (value == Mode.GIF) VISIBLE else GONE
            searchBar.refresh()
            if (value == Mode.GIF) {
                // Opened, not resumed. Whatever was being looked at last time is not where
                // this time starts, and a list that opens halfway down hides its own first
                // row - which on a search is the best answer it has.
                gifGrid.scrollToTop()
                findGifs(now = true)
            } else {
                stopGifSearch()
            }
        }

    /**
     * The current search text. Empty means "not searching": the grid shows recents and the
     * nine categories instead of a flat list of matches.
     *
     * A plain property with a side-effecting setter, not a function, because the host is
     * meant to be able to write `panel.query = query + char` on every keystroke without
     * knowing anything about how the grid underneath redraws itself.
     */
    var query: String = ""
        set(value) {
            if (field == value) return
            field = value
            searchBar.refresh()
            if (mode == Mode.GIF) findGifs(now = false) else refreshGrid()
        }

    // ---------------------------------------------------------------- glyphs
    //
    // Read once, here, for the reason given on KeyboardView's own `glyphs` map: SvgIcon
    // re-parses its file on every call, so a panel that fetched one per draw would be
    // parsing XML in the input path. Four of them cover everything drawn: search leads with
    // a magnifying glass, a typed query can be cleared with the cross, Recent is headed by
    // a clock the way the phone's own history lists were, and a search with nothing in it
    // is answered by the same smiley the keyboard's own emoji key wears.
    private val magnifyIcon = SvgIcon.fromAsset(context, "$ICONS/appbar.magnify.svg")?.mutate()
    private val closeIcon = SvgIcon.fromAsset(context, "$ICONS/appbar.close.svg")?.mutate()
    private val clockIcon = SvgIcon.fromAsset(context, "$ICONS/appbar.clock.svg")?.mutate()
    private val smileyIcon = SvgIcon.fromAsset(context, "$ICONS/appbar.smiley.happy.svg")?.mutate()
    private val backspaceIcon = context.getDrawable(R.drawable.wp81_calc_backspace)?.mutate()

    // ---------------------------------------------------------------- data
    //
    // Loaded once per process - see EmojiData.load - and kept here rather than re-read per
    // panel, since the emoji key can be tapped, closed and tapped again many times in one
    // typing session.
    private val data: EmojiData = EmojiData.load(context)
    private val recents = RecentEmoji(context)

    /** Set by [setMetrics]; everything below sizes itself off these. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f
    private var bottomInset = 0

    /**
     * How tall the keys are that this replaces, gutters included.
     *
     * Handed in rather than worked out, because it is no longer four equal rows - the
     * keyboard's bottom row is shorter than the letters - and a panel that computed its own
     * height from the row pitch would come out taller and resize the window on opening.
     */
    private var contentHeight = 0

    /**
     * Whether the letter keys are showing underneath this.
     *
     * When they are, the panel is only the control row and a couple of rows of results, and
     * the keys below reserve the navigation bar rather than this doing it. When they are not,
     * it has the whole height to itself.
     */
    var searchMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            searchBar.refresh()
            requestLayout()
        }

    /**
     * Both keys answer through this rather than through [KeyView.Listener] on [EmojiPanel]
     * itself, so that implementing the interface stays this file's business and does not
     * leak into the public shape the rest of the keyboard is promised.
     */
    private val keyListener = object : KeyView.Listener {
        override fun onKeyUp(view: KeyView) {
            when (view.key.action) {
                Action.LETTERS -> onBackToLetters?.invoke()
                Action.BACKSPACE -> onBackspace?.invoke()
                Action.GIF -> mode = if (mode == Mode.GIF) Mode.EMOJI else Mode.GIF
                else -> Unit
            }
        }

        /** Nothing on this panel changes the page, so nothing needs to act on the press. */
        override fun onKeyPress(view: KeyView) = Unit

        override fun onKeyLongPress(view: KeyView) = Unit
        override fun onKeyDrag(view: KeyView, x: Float, y: Float) = Unit
        override fun onKeyRelease(view: KeyView) = Unit

        /** No space bar on this panel, so no caret slide either. */
        override fun onCursorSlide(view: KeyView, steps: Int) = Unit

        /** Backspace repeats while held - [KeyView] already drives that; this just answers it. */
        override fun onKeyRepeat(view: KeyView) {
            if (view.key.action == Action.BACKSPACE) onBackspace?.invoke()
        }
    }

    private val searchBar = SearchBarView(context)
    private val grid = GridView(context)
    private val gifGrid = GifGrid(context, palette)

    /**
     * A search is made when the typing stops, not on every letter.
     *
     * The emoji grid can afford a search per keystroke - it is a scan of a list already in
     * memory - and this cannot: it is a request over the network, and typing "birthday" one
     * letter at a time would be eight of them, seven of which are thrown away.
     */
    private val gifDebounce = Handler(Looper.getMainLooper())
    private val gifSearch = Runnable { findGifs(now = true) }

    /**
     * Reuses [Action.LETTERS] - "back to letters from a symbol page" in the enum's own
     * words - for exactly that purpose one layer further out: back to letters from here.
     * [WP81KeyboardService] already answers this action by showing the letter keyboard, so
     * the label and behaviour the rest of the keyboard already gives that action are
     * inherited for free rather than re-declared under a name of this file's own.
     */
    private val abcKey = KeyView(
        context,
        Key(label = "abc", style = Style.FUNCTION, action = Action.LETTERS),
        palette
    ).apply { listener = keyListener }

    /**
     * The other half of the panel, and the way back from it.
     *
     * Labelled with where it goes rather than with where you are, which is how every other
     * key of this kind on this keyboard reads: `&123` takes you to the symbols and says so,
     * and the key that brings you back says `abc`. So this says `gif` among the emoji and
     * `emoji` among the GIFs.
     */
    private val gifKey = KeyView(
        context,
        Key(label = GIF_LABEL, style = Style.FUNCTION, action = Action.GIF),
        palette
    ).apply { listener = keyListener }

    private val backspaceKey = KeyView(
        context,
        Key(style = Style.FUNCTION, action = Action.BACKSPACE),
        palette
    ).apply {
        listener = keyListener
        glyph = backspaceIcon
    }

    init {
        addView(searchBar)
        addView(grid)
        addView(gifGrid)
        addView(abcKey)
        addView(gifKey)
        addView(backspaceKey)
        gifGrid.visibility = GONE
        gifGrid.onPicked = { onGifPicked?.invoke(it) }
        applyPalette(palette)
        // Nothing else populates this: `query` starts at "" by initializer, which does not
        // run its own setter, so the grid would otherwise stay the empty view it was
        // constructed with until something changed the search text.
        refreshGrid()
    }

    // ---------------------------------------------------------------- public surface

    /**
     * The panel is going away: write the recents and show them next time.
     *
     * Rebuilding here rather than on each pick is the whole point - see [RecentEmoji.record].
     */
    fun onClosed() {
        recents.commit()
        refreshGrid()
        // Back to the emoji, and the GIFs freed. The panel outlives its use - it is built
        // once and kept for the process - so a screenful of running animations left in it is
        // a screenful of frame buffers held for the rest of the day.
        mode = Mode.EMOJI
        stopGifSearch()
        gifGrid.release()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(blend(GROUND_ALPHA))
        magnifyIcon?.setTint(palette.foregroundSubtle)
        closeIcon?.setTint(palette.foregroundSubtle)
        searchBar.applyPalette()
        grid.applyPalette()
        abcKey.applyPalette(p)
        gifKey.applyPalette(p)
        backspaceKey.applyPalette(p)
        gifGrid.applyPalette(p)
        invalidate()
    }

    /**
     * The keyboard's own metrics, so this lands pixel-for-pixel where the key grid was.
     *
     * [keyW] is the unit almost everything else here scales from, the same way it is for
     * [KeyboardView]. [keyH] and [gap] together with it are what fix this panel's own
     * height: the grid it replaces is four rows tall, so this asks for `4 * (keyH + gap)`
     * too, plus [bottomInset] - the strip at the foot the system's own chevron and globe
     * paint over, which [KeyboardView] already discovered has to be measured defensively
     * rather than trusted to the window insets alone. Asking for anything else would make
     * the input method's window visibly resize the moment the emoji key is tapped.
     */
    fun setMetrics(keyW: Float, keyH: Float, gap: Float, bottomInset: Int, contentHeight: Int) {
        if (keyW == this.keyW && keyH == this.keyH && gap == this.gap &&
            bottomInset == this.bottomInset && contentHeight == this.contentHeight
        ) return
        this.contentHeight = contentHeight
        this.keyW = keyW
        this.keyH = keyH
        this.gap = gap
        this.bottomInset = bottomInset
        searchBar.configure(keyW, gap)
        grid.configure(keyW, gap)
        gifGrid.configure(keyW, gap)
        // The control row is shorter than a key row, so these two are told that height and
        // size their contents against it - otherwise `abc` and the backspace would be drawn
        // for a key twice as tall as the one they are actually in.
        abcKey.setMetrics(keyW, keyW * CONTROL_ROW_FRACTION, gap)
        gifKey.setMetrics(keyW, keyW * CONTROL_ROW_FRACTION, gap)
        backspaceKey.setMetrics(keyW, keyW * CONTROL_ROW_FRACTION, gap)
        requestLayout()
    }

    // ---------------------------------------------------------------- geometry

    /**
     * One control row, then as much grid as is left.
     *
     * The control row carries everything: `abc` to leave, the search box, and backspace. It
     * used to be two rows - a tall search bar at the top and `abc` and backspace along the
     * bottom - which spent half the panel on three controls and left the emoji squeezed into
     * the middle. One row of the same height as the suggestion bar is enough for all three
     * and gives the grid an extra row and a half.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val controlH = (keyW * CONTROL_ROW_FRACTION).toInt()
        val rowPitch = keyH + gap

        // With the keys underneath there is only room for a glance at the results; without
        // them the panel has the lot. The keys reserve the navigation bar in the first case,
        // so reserving it here as well would leave a band of nothing above them.
        val gridH: Int
        val reserved: Int
        if (searchMode) {
            gridH = (rowPitch * SEARCH_GRID_ROWS).toInt()
            reserved = 0
        } else {
            // A full four rows of grid *under* the control row, so the panel comes to exactly
            // the height of what it replaces: the suggestion bar plus four rows of keys. The
            // control row is the same height as the bar, so subtracting it here - which is
            // the obvious reading of "as tall as the keyboard" - left the panel a bar short
            // and the window opened with a band of nothing above it.
            // The control row stands in for the suggestion bar, and the grid stands in for
            // the keys - so the panel comes to exactly the height of what it replaces.
            gridH = contentHeight
            reserved = bottomInset
        }

        val abcW = (keyW * ABC_FRACTION).toInt()
        val gifW = (keyW * GIF_FRACTION).toInt()
        val backW = (keyW * BACKSPACE_FRACTION).toInt()
        val row = MeasureSpec.makeMeasureSpec(controlH, MeasureSpec.EXACTLY)
        abcKey.measure(MeasureSpec.makeMeasureSpec(abcW, MeasureSpec.EXACTLY), row)
        gifKey.measure(MeasureSpec.makeMeasureSpec(gifW, MeasureSpec.EXACTLY), row)
        backspaceKey.measure(MeasureSpec.makeMeasureSpec(backW, MeasureSpec.EXACTLY), row)
        searchBar.measure(
            MeasureSpec.makeMeasureSpec(
                (w - abcW - gifW - backW).coerceAtLeast(0), MeasureSpec.EXACTLY
            ),
            row
        )
        // Both grids, though only one of them is ever visible: they occupy the same box and
        // swapping between them must not be a measure pass, which on this panel would be a
        // window resize seen as the keyboard jumping.
        val gridSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY) to
            MeasureSpec.makeMeasureSpec(gridH, MeasureSpec.EXACTLY)
        grid.measure(gridSpec.first, gridSpec.second)
        gifGrid.measure(gridSpec.first, gridSpec.second)
        setMeasuredDimension(w, controlH + gridH + reserved)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val controlH = abcKey.measuredHeight
        var x = 0
        for (key in listOf(abcKey, gifKey)) {
            key.layout(x, 0, x + key.measuredWidth, controlH)
            x += key.measuredWidth
        }
        searchBar.layout(x, 0, x + searchBar.measuredWidth, controlH)
        x += searchBar.measuredWidth
        backspaceKey.layout(x, 0, x + backspaceKey.measuredWidth, controlH)
        grid.layout(0, controlH, grid.measuredWidth, controlH + grid.measuredHeight)
        gifGrid.layout(0, controlH, gifGrid.measuredWidth, controlH + gifGrid.measuredHeight)
    }

    // ---------------------------------------------------------------- content

    /** What the grid should be showing right now, and what to say if that is nothing. */
    private fun sectionsFor(q: String): Pair<List<EmojiSection>, String?> {
        if (q.isNotEmpty()) {
            val matches = data.search(q)
            return if (matches.isEmpty()) {
                emptyList<EmojiSection>() to NO_MATCHES_MESSAGE
            } else {
                listOf(EmojiSection(null, null, matches)) to null
            }
        }

        val sections = mutableListOf<EmojiSection>()
        val recent = recents.list().mapNotNull(data::find)
        if (recent.isNotEmpty()) sections.add(EmojiSection(RECENT_LABEL, clockIcon, recent))
        for (i in 0 until EmojiCategories.COUNT) {
            val bucket = data.categories.getOrNull(i) ?: continue
            if (bucket.isNotEmpty()) sections.add(EmojiSection(EmojiCategories.LABELS[i], null, bucket))
        }
        return sections to (if (sections.isEmpty()) NO_EMOJI_MESSAGE else null)
    }

    private fun refreshGrid() {
        val (sections, empty) = sectionsFor(query)
        grid.setContent(sections, empty)
    }

    /**
     * Asks for GIFs, or schedules asking.
     *
     * `now` is the difference between the two callers: opening the GIFs, or clearing the box,
     * should show something at once, while a letter arriving means more letters are coming and
     * the request waits to see. Either way the answer is checked against the query that is
     * current when it lands - several searches can be in the air at once, and they do not
     * necessarily come back in the order they went out.
     */
    private fun findGifs(now: Boolean) {
        if (mode != Mode.GIF) return
        gifDebounce.removeCallbacks(gifSearch)
        if (!GifSearch.hasKey(context)) {
            gifGrid.setMessage(NO_GIF_KEY_MESSAGE)
            return
        }
        if (!now) {
            gifDebounce.postDelayed(gifSearch, GIF_DEBOUNCE_MS)
            return
        }
        val asked = query
        gifGrid.setMessage(SEARCHING_MESSAGE)
        GifSearch.find(context, asked) { found ->
            if (mode != Mode.GIF || query != asked) return@find
            if (found == null) gifGrid.setMessage(NO_GIF_SERVICE_MESSAGE)
            else gifGrid.setItems(found, NO_GIFS_MESSAGE)
        }
    }

    private fun stopGifSearch() {
        gifDebounce.removeCallbacks(gifSearch)
    }

    /** An emoji was tapped in the grid. Records it and hands it to the host - see [onEmojiPicked]. */
    private fun commitEmoji(emoji: Emoji) {
        recents.record(emoji.glyph)
        onEmojiPicked?.invoke(emoji.glyph)
        // Nothing on screen changes. The recents are written and the grid rebuilt when the
        // panel closes - see [onClosed] - so that a run of picks is not interrupted by the
        // row under the thumb reordering itself between one tap and the next.
    }

    @ColorInt
    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    // ==================================================================== search bar

    /**
     * The bar itself: a magnifying glass, the query or a placeholder, and a cross to clear
     * it. Ordinary widgets rather than a canvas, unlike the grid below - there is exactly
     * one of this, so nothing is bought by hand-rolling it.
     *
     * It is display-only. Making it an `EditText` was the first thing tried, and it cannot
     * work: an input method cannot itself become the target of *another* input method, so a
     * real text field here would either refuse to take focus or - worse - would take it and
     * then have nothing type into it. [query] is written by the host instead, from the
     * letter keys it shows beneath this once [onSearchTapped] fires.
     */
    @SuppressLint("ViewConstructor")
    private inner class SearchBarView(context: Context) : LinearLayout(context) {

        private val magnify = ImageView(context)
        private val label = TextView(context)
        private val clear = ImageView(context)

        /** Half a gutter: what the fill is held in from the view's bounds. See [configure]. */
        private var inset = 0f
        private val fill = Paint()

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            // Drawn rather than set as a background, because a background fills the whole
            // view and the point here is that it must not.
            setWillNotDraw(false)

            magnify.setImageDrawable(magnifyIcon)
            magnify.scaleType = ImageView.ScaleType.FIT_CENTER

            label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            label.includeFontPadding = false
            // The label is MATCH_PARENT tall so that tapping anywhere along the row opens the
            // search, which means its *text* has to be centred within it explicitly. Without
            // this the row's own CENTER_VERTICAL centres the label's box and the text sits at
            // the top of that box - so the query appeared on one line and the magnifier on
            // another, which is what made the bar look two rows tall.
            label.gravity = Gravity.CENTER_VERTICAL
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END

            clear.setImageDrawable(closeIcon)
            clear.scaleType = ImageView.ScaleType.FIT_CENTER
            clear.isClickable = true
            // A local clear, not a callback: there is nothing for the host to be told. It
            // already owns whatever buffer it is feeding into `query`, and the next
            // character it forwards starts a fresh search exactly as if this were the first
            // tap - the host does not have to be told the box now reads empty for that to
            // keep working, only for the placeholder to reappear here in the meantime.
            clear.setOnClickListener { query = "" }
            TiltEffect.apply(clear)

            addView(magnify)
            addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(clear)

            setOnClickListener { onSearchTapped?.invoke() }
            TiltEffect.apply(this)
        }

        fun configure(keyW: Float, gap: Float) {
            val pad = (keyW * BAR_PAD_FRACTION).toInt()
            val icon = (keyW * BAR_ICON_FRACTION).toInt()
            // Half a gutter of padding all round, and the fill drawn inside it, so the box
            // comes out exactly the size of the keys either side of it. A KeyView paints its
            // face inset by half a gutter and this did not, which made the search box a
            // gutter taller than `abc` and backspace and left it looking oversized next to
            // them - the two are measured to the same height and only looked different.
            inset = gap / 2f
            setPadding(pad, inset.toInt(), pad, inset.toInt())
            magnify.layoutParams = LayoutParams(icon, icon).apply { marginEnd = pad }
            clear.layoutParams = LayoutParams(icon, icon).apply { marginStart = pad }
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, keyW * BAR_TEXT_FRACTION)
            refresh()
        }

        fun applyPalette() {
            fill.color = blend(FUNCTION_ALPHA)
            refresh()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(inset, inset, width - inset, height - inset, fill)
        }

        fun refresh() {
            if (query.isEmpty()) {
                label.text = if (mode == Mode.GIF) GIF_PLACEHOLDER else SEARCH_PLACEHOLDER
                label.setTextColor(palette.foregroundSubtle)
            } else {
                label.text = query
                label.setTextColor(palette.foreground)
            }
            clear.visibility = if (query.isEmpty()) INVISIBLE else VISIBLE
        }
    }

    // ==================================================================== the grid

    /**
     * The scrollable field of emoji.
     *
     * Painted by hand, on one [View], the way [KeyView] paints a key's own face rather than
     * being three child views for a label, a hint and a glyph - except here the reason is
     * scale rather than frequency. A category alone can run past three hundred entries and
     * the whole set is upward of three thousand; a real view per cell is a real view per
     * cell of a `RecyclerView` that recycles nothing, in an input method's window, on the
     * one path where every extra millisecond is felt as the keyboard lagging behind a tap.
     * A flat run of rows built once per [setContent] and walked with simple arithmetic is
     * what a key-sized cell actually costs to draw: one `drawText` and, on the cell under a
     * finger, one `drawRect`.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class GridView(context: Context) : View(context) {

        private var sections: List<EmojiSection> = emptyList()
        private var emptyMessage: String? = null
        private var rows: List<EmojiRow> = emptyList()

        /** How many cells fit across, given the last width this view was laid out at. */
        private var columns = 1
        private var unitW = 0f
        private var cellPitch = 0f
        private var headerHeight = 0f

        private var scrollOffset = 0f
        private var maxScroll = 0f

        private var dragging = false
        private var downY = 0f
        private var downScroll = 0f
        private var downCell: Pair<Int, Int>? = null
        private var highlighted: Pair<Int, Int>? = null
        private var velocityTracker: VelocityTracker? = null
        private val scroller = OverScroller(context)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private val face = Paint()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val headerInk = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()
        private val headerFont = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        private val bodyFont = ResourcesCompat.getFont(context, R.font.segoeui_regular)

        init {
            isClickable = true
        }

        fun configure(keyW: Float, gap: Float) {
            // The unit still sets the type and the headers, which belong to the keyboard's
            // own scale. Only the cells are measured off the panel instead - see below.
            unitW = keyW
            headerHeight = keyW * HEADER_FRACTION
            headerInk.typeface = headerFont
            headerInk.textSize = keyW * HEADER_TEXT_FRACTION
            recomputeColumns()
            rebuild()
        }

        fun setContent(sections: List<EmojiSection>, emptyMessage: String?) {
            this.sections = sections
            this.emptyMessage = emptyMessage
            rebuild()
        }

        fun applyPalette() {
            clockIcon?.setTint(palette.foregroundSubtle)
            smileyIcon?.setTint(palette.foregroundSubtle)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recomputeColumns()
            rebuild()
        }

        /**
         * Eight across, filling the width exactly.
         *
         * It used to be as many key-sized cells as fitted, which is the obvious thing and was
         * wrong twice over. A key's pitch divides the panel almost exactly ten times, so the
         * answer rested on the last decimal place of a float - land a hair under ten and the
         * count drops to nine, leaving a whole empty column against the right edge. And ten
         * emoji across is smaller than an emoji wants to be drawn: they are pictures, not
         * letters, and the grid is read by looking rather than by scanning.
         *
         * So the count is fixed and the cell is whatever the width divided by it comes to.
         * Nothing is left over by construction, and the same row appears on the ten-column
         * English keyboard and the twelve-column Macedonian one.
         */
        private fun recomputeColumns() {
            columns = EMOJI_COLUMNS
            cellPitch = if (width > 0) width / columns.toFloat() else 0f
        }

        /** Lays [sections] out into fixed-height rows once, so scrolling is pure arithmetic. */
        private fun rebuild() {
            val built = mutableListOf<EmojiRow>()
            for (section in sections) {
                if (section.label != null) built.add(EmojiRow.Header(section.label, section.icon))
                var i = 0
                while (i < section.items.size) {
                    built.add(EmojiRow.Items(section.items.subList(i, minOf(i + columns, section.items.size))))
                    i += columns
                }
            }
            rows = built

            var content = 0f
            for (row in rows) content += if (row is EmojiRow.Header) headerHeight else cellPitch
            maxScroll = (content - height).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
            invalidate()
        }

        // -------------------------------------------------------------- hit testing

        /** Which row and column [x], [yContent] - in scrolled content coordinates - lands on. */
        private fun cellAt(x: Float, yContent: Float): Pair<Int, Int>? {
            var y = 0f
            for ((rowIndex, row) in rows.withIndex()) {
                val h = if (row is EmojiRow.Header) headerHeight else cellPitch
                if (yContent >= y && yContent < y + h) {
                    if (row is EmojiRow.Items) {
                        val col = (x / cellPitch).toInt()
                        if (col in row.items.indices) return rowIndex to col
                    }
                    return null
                }
                y += h
            }
            return null
        }

        private fun emojiAt(cell: Pair<Int, Int>): Emoji? =
            (rows.getOrNull(cell.first) as? EmojiRow.Items)?.items?.getOrNull(cell.second)

        // -------------------------------------------------------------- touch

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scroller.forceFinished(true)
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downY = event.y
                    downScroll = scrollOffset
                    dragging = false
                    downCell = cellAt(event.x, event.y + scrollOffset)
                    highlighted = downCell
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dy = event.y - downY
                    if (!dragging && kotlin.math.abs(dy) > touchSlop) {
                        dragging = true
                        highlighted = null
                    }
                    if (dragging) scrollOffset = (downScroll - dy).coerceIn(0f, maxScroll)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val flingV = -(velocityTracker?.yVelocity ?: 0f)
                        scroller.fling(
                            0, scrollOffset.toInt(), 0, flingV.toInt(),
                            0, 0, 0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    } else {
                        val cell = cellAt(event.x, event.y + scrollOffset)
                        if (cell != null && cell == downCell) {
                            emojiAt(cell)?.let {
                                KeyboardHaptics.key(this)
                                commitEmoji(it)
                            }
                        }
                    }
                    endTouch()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    endTouch()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun endTouch() {
            dragging = false
            downCell = null
            highlighted = null
            velocityTracker?.recycle()
            velocityTracker = null
            invalidate()
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollOffset = scroller.currY.toFloat().coerceIn(0f, maxScroll)
                postInvalidateOnAnimation()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            scroller.forceFinished(true)
            velocityTracker?.recycle()
            velocityTracker = null
        }

        // -------------------------------------------------------------- paint

        override fun onDraw(canvas: Canvas) {
            if (rows.isEmpty()) {
                drawEmptyState(canvas)
                return
            }
            var y = -scrollOffset
            for ((index, row) in rows.withIndex()) {
                val h = if (row is EmojiRow.Header) headerHeight else cellPitch
                if (y + h >= 0f && y <= height) {
                    when (row) {
                        is EmojiRow.Header -> drawHeader(canvas, row, y)
                        is EmojiRow.Items -> drawItems(canvas, row, y, index)
                    }
                }
                y += h
            }
        }

        private fun drawHeader(canvas: Canvas, row: EmojiRow.Header, y: Float) {
            val pad = unitW * HEADER_PAD_FRACTION
            var x = pad
            row.icon?.let {
                val side = (headerHeight * HEADER_ICON_FRACTION).toInt()
                val top = (y + (headerHeight - side) / 2f).toInt()
                it.setBounds(x.toInt(), top, x.toInt() + side, top + side)
                it.draw(canvas)
                x += side + pad / 2f
            }
            headerInk.color = palette.foregroundSubtle
            headerInk.textAlign = Paint.Align.LEFT
            headerInk.getTextBounds(row.label, 0, row.label.length, bounds)
            canvas.drawText(row.label, x, y + headerHeight / 2f - (bounds.top + bounds.bottom) / 2f, headerInk)
        }

        private fun drawItems(canvas: Canvas, row: EmojiRow.Items, y: Float, rowIndex: Int) {
            // Null: emoji are drawn by the system's own colour-emoji font, found through
            // Android's ordinary glyph-fallback search regardless of what typeface is set,
            // so there is nothing Segoe could contribute here and asking for it would only
            // risk a font that quietly has no colour table for these code points at all.
            ink.typeface = null
            ink.textSize = cellPitch * EMOJI_TEXT_FRACTION
            ink.textAlign = Paint.Align.CENTER
            ink.color = palette.foreground
            for ((col, emoji) in row.items.withIndex()) {
                val left = col * cellPitch
                if (highlighted?.first == rowIndex && highlighted?.second == col) {
                    face.color = palette.accent
                    canvas.drawRect(
                        left + gap / 2f, y + gap / 2f,
                        left + cellPitch - gap / 2f, y + cellPitch - gap / 2f, face
                    )
                }
                val cx = left + cellPitch / 2f
                val cy = y + cellPitch / 2f
                ink.getTextBounds(emoji.glyph, 0, emoji.glyph.length, bounds)
                canvas.drawText(emoji.glyph, cx, cy - (bounds.top + bounds.bottom) / 2f, ink)
            }
        }

        private fun drawEmptyState(canvas: Canvas) {
            val message = emptyMessage ?: return
            if (unitW <= 0f) return
            val cx = width / 2f
            val cy = height / 2f

            smileyIcon?.let {
                val side = (unitW * NO_RESULTS_ICON_FRACTION).toInt()
                val left = (cx - side / 2f).toInt()
                val top = (cy - side * 0.9f).toInt()
                it.setBounds(left, top, left + side, top + side)
                it.draw(canvas)
            }

            ink.typeface = bodyFont
            ink.textSize = unitW * NO_RESULTS_TEXT_FRACTION
            ink.textAlign = Paint.Align.CENTER
            ink.color = palette.foregroundSubtle
            ink.getTextBounds(message, 0, message.length, bounds)
            canvas.drawText(message, cx, cy + unitW * 0.9f - (bounds.top + bounds.bottom) / 2f, ink)
        }
    }

    private companion object {

        const val ICONS = "custom_icons_8"

        const val SEARCH_PLACEHOLDER = "Search emoji"
        const val GIF_PLACEHOLDER = "Search GIFs"
        const val RECENT_LABEL = "Recent"
        const val NO_MATCHES_MESSAGE = "No emoji found"
        const val NO_EMOJI_MESSAGE = "No emoji available"

        /** What the key says in each of the two modes. It names where it goes, not where you are. */
        const val GIF_LABEL = "gif"
        const val EMOJI_LABEL = "emoji"

        const val SEARCHING_MESSAGE = "Searching\u2026"
        const val NO_GIFS_MESSAGE = "No GIFs found"
        const val NO_GIF_SERVICE_MESSAGE = "GIFs are not answering"

        /** See [KeyboardSettingsActivity], which is where a key is pasted in. */
        const val NO_GIF_KEY_MESSAGE = "Add a GIPHY key in keyboard settings"

        /** How long the typing has to settle before the GIFs are asked. See [findGifs]. */
        const val GIF_DEBOUNCE_MS = 350L

        /** The keyboard's own ground. See `KeyboardView.GROUND_ALPHA` - the two must match. */
        const val GROUND_ALPHA = 0.102f

        /**
         * The control row, and what shares it. All fractions of one key's width.
         *
         * [CONTROL_ROW_FRACTION] is the suggestion bar's height, so the panel's top row lines
         * up with the row it replaces. `abc` and backspace take the same widths the shoulder
         * keys have on the letter keyboard, so they land under the same thumbs.
         */
        const val CONTROL_ROW_FRACTION = 1.26f
        const val ABC_FRACTION = 1.55f

        /** The `gif` key, the same width as the `abc` it stands beside. */
        const val GIF_FRACTION = 1.55f
        const val BACKSPACE_FRACTION = 1.45f

        /**
         * How much of the grid survives when the keys are up underneath it.
         *
         * A whole number of rows. At 1.7 the bottom row was sliced through the middle, which
         * reads as the panel being cut off rather than as there being more to scroll to.
         */
        const val SEARCH_GRID_ROWS = 2f

        /** A function key's fill, reused for the search bar so it reads as a control. */
        const val FUNCTION_ALPHA = 0.302f

        /** The search bar: its icons, its padding, its text - all fractions of one key's width. */
        const val BAR_ICON_FRACTION = 0.40f
        const val BAR_PAD_FRACTION = 0.14f
        const val BAR_TEXT_FRACTION = 0.32f

        /** A section header's height and text size, and how much of the height its icon fills. */
        const val HEADER_FRACTION = 0.46f
        const val HEADER_TEXT_FRACTION = 0.30f
        const val HEADER_ICON_FRACTION = 0.62f
        const val HEADER_PAD_FRACTION = 0.18f

        /** How large an emoji is drawn inside its own cell. */
        /** How many emoji to a row, and how much of a cell one of them fills. */
        const val EMOJI_COLUMNS = 8
        const val EMOJI_TEXT_FRACTION = 0.56f

        /** The "nothing here" state: an icon a little larger than an emoji, and its caption. */
        const val NO_RESULTS_ICON_FRACTION = 1.6f
        const val NO_RESULTS_TEXT_FRACTION = 0.34f
    }
}
