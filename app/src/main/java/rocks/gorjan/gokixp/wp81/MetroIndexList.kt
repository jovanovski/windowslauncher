package rocks.gorjan.gokixp.wp81

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 alphabetical list.
 *
 * The app list is the famous one - swipe left from Start and there it is - but the phone
 * used the same page in half a dozen places, the People hub among them, and it was the
 * same page every time: rows filed under outlined letter squares, a square tapped to open
 * the jump grid, a held letter at the top saying where the list has got to, and a search
 * band that takes the rail's place when it is asked for. Two copies of that would be two
 * lists that drift apart, so there is one of it and the things that genuinely differ are
 * named below.
 *
 * What a subclass says is what its rows are: [letterOf] to file one, [matches] to search
 * one, and a holder to draw one. What it may say is how big the squares are, whether the
 * band carries a search ring of its own, and how far in from the left the rows begin.
 * Everything else - the folding of the letters into a search and back out, the rail that
 * slides away with the ring, the held square being pushed off by the next one coming up -
 * is the platform's behaviour rather than any one list's, and belongs here.
 */
@SuppressLint("ViewConstructor")
abstract class MetroIndexList<T>(
    context: Context,
    protected var palette: WP81Palette
) : FrameLayout(context) {

    // ---------------------------------------------------------------- what a list says

    /** Which letter an item is filed under. Anything outside a-z belongs under '#'. */
    protected abstract fun letterOf(item: T): Char

    /** Whether an item answers to what has been typed. */
    protected abstract fun matches(item: T, query: String): Boolean

    /** Makes one row. Called once per recycled view, not once per item. */
    protected abstract fun createHolder(): ItemHolder

    /** The letter square's edge, and with it the height of a header row. */
    protected open val squareDp: Int get() = 44

    /** The letter itself, which wants to grow with the square it is set in. */
    protected open val letterSp: Float get() = 24f

    /**
     * The gutter down the left of the page.
     *
     * The app list keeps its search ring there, which is why it is as wide as it is; a list
     * whose search is asked for from somewhere else has no ring and no gutter.
     */
    protected open val railDp: Int get() = RING_MARGIN_DP + RING_DP + RAIL_GAP_DP

    /** Where the rows stop on the right. */
    protected open val edgeDp: Int get() = 24

    /** Whether the band carries a ring of its own to begin a search with. */
    protected open val hasSearchRing: Boolean get() = true

    protected open val searchHint: String get() = "search"

    /** Where the jump grid is put up. Its own bounds unless a host says otherwise. */
    private var jumpHost: ViewGroup = this

    // ---------------------------------------------------------------- what it reports

    var onPick: ((T) -> Unit)? = null

    /** A held row, with the row's bottom edge in this view's coordinates for a menu. */
    var onLongPress: ((T, Float) -> Unit)? = null

    /**
     * The keyboard's own search key, with what was typed and what it matched.
     *
     * The list has said everything it can by then; what happens next - open the first of
     * them, hand the words to a search engine - is the host's business.
     */
    var onSearchSubmit: ((String, List<T>) -> Unit)? = null

    /** Fired as search opens and closes, for a host that has chrome of its own to move. */
    var onSearchChanged: ((Boolean) -> Unit)? = null

    // ---------------------------------------------------------------- the parts

    private val list = RecyclerView(context)
    private val jumpList = JumpListView(context, palette)
    private val searchBox = EditText(context)

    /**
     * The band over the list: the search ring, and the field that takes its place.
     *
     * One band holding both, because they are the same thing in its two states - the ring
     * is search before it has been asked for, and the field is search once it has.
     */
    private val header = FrameLayout(context)
    private val searchRing = ImageView(context)
    private val column = LinearLayout(context)

    /**
     * Whether the list is in search mode.
     *
     * Not read off the field's visibility, which is mid-animation for a fifth of a second
     * either way - and every question asked of this in that fifth of a second, the letters
     * included, would be answered against a view still on its way out.
     */
    private var searching = false

    /**
     * Set while the letters are coming back, so a square bound during that lands rolled up
     * and unrolls rather than appearing at full height.
     */
    private var expandLetters = false

    private var railAnimator: ValueAnimator? = null

    /**
     * The letter of the section being scrolled through, held at the top of the list.
     *
     * A copy of the square from the section's own header rather than that header moved:
     * the rows keep scrolling underneath, so the one that has gone off the top has to be
     * both gone and still there. It is pushed off in its turn by the next letter coming up.
     */
    private val stickyLetter = TextView(context)

    /** The page-coloured band the held letter sits on, a row deep and the list's width. */
    private val stickyBand = FrameLayout(context)

    private val items = mutableListOf<T>()
    private val rows = mutableListOf<Row>()
    private val adapter = Adapter()
    private var query: String = ""

    /** One entry in the flattened list: either a letter header or one of the items. */
    private sealed class Row {
        data class Header(val letter: Char) : Row()
        data class Item(val index: Int) : Row()
    }

    /** A row's height, in the header's terms: the square with air above and below it. */
    private val headerDp: Int get() = squareDp + HEADER_TOP_DP + HEADER_BOTTOM_DP

    /**
     * Builds the page.
     *
     * Called by the subclass rather than from this class's own constructor, because
     * everything here reads [squareDp] and its neighbours - and an open value read during
     * a base class's construction is read before the subclass has had a chance to set it.
     */
    protected fun build() {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        buildSearchBox()
        buildHeader()
        column.orientation = LinearLayout.VERTICAL
        column.addView(list, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildStickyLetter()
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(BAND_DP)))
        applyListInset(animated = false)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
                updateStickyLetter()
            }
        })
        // The rows are re-laid-out by more than scrolling: rebuilt on a refresh, spread out
        // when the rail slides away, and measured for the first time a frame after the page
        // is put up.
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateStickyLetter() }

        jumpList.visibility = GONE
        // The globe at the end of the grid: the alphabet has run out of ways to narrow
        // things down, so hand over to the one thing that has not.
        jumpList.onSearchPicked = {
            hideJumpList()
            beginSearch()
        }
        jumpList.onLetterPicked = { letter ->
            hideJumpList()
            scrollToLetter(letter)
        }
        jumpHost.addView(jumpList, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyPalette(palette)
    }

    /**
     * Puts the jump grid somewhere other than inside this view.
     *
     * The grid measures its squares against the whole display, because that is what it
     * covers - so a list that is itself a page inside something larger, a panorama
     * section say, has to hand it a parent that is actually the size of the screen. A
     * grid clipped to a section would be a grid with its bottom rows missing.
     */
    fun setJumpListHost(host: ViewGroup) {
        if (jumpHost === host) return
        (jumpList.parent as? ViewGroup)?.removeView(jumpList)
        jumpHost = host
        host.addView(jumpList, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ---------------------------------------------------------------- the held letter

    /**
     * The square that stays at the top, set exactly as the ones in the list are.
     *
     * Same size, same corner, same type - it is standing in for a row that has scrolled
     * away, so any difference between the two would read as the list having two kinds of
     * letter. It answers a tap the same way as well, since a letter square is the way into
     * the jump list wherever it is.
     */
    private fun buildStickyLetter() {
        paintSquareType(stickyLetter)
        stickyLetter.isClickable = true
        stickyLetter.setOnClickListener { showJumpList() }
        TiltEffect.apply(stickyLetter)
        paintLetterSquare(stickyLetter)

        stickyBand.visibility = GONE
        // Clickable, so a tap on the band beside the letter stops at the band rather than
        // reaching the row hidden underneath it.
        stickyBand.isClickable = true
        stickyBand.setBackgroundColor(palette.background)
        stickyBand.addView(stickyLetter, FrameLayout.LayoutParams(
            dp(squareDp), dp(squareDp)).apply { topMargin = dp(HEADER_TOP_DP) })
        addView(stickyBand, LayoutParams(LayoutParams.MATCH_PARENT, dp(headerDp)))
    }

    /** How a letter is set in its square: bottom left, as a name is on a tile. */
    private fun paintSquareType(square: TextView) {
        square.gravity = Gravity.BOTTOM or Gravity.START
        square.textSize = letterSp
        square.setPadding(dp(6), 0, 0, dp(2))
        square.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
    }

    /**
     * A letter square's colours.
     *
     * Outlined rather than filled. A wall of solid accent squares down the side of the list
     * competes with everything beside it; an outline says the same thing and lets the list
     * be the thing being read. The letter is in the border's own colour rather than white:
     * the square is one mark, not a white letter inside a coloured frame.
     */
    private fun paintLetterSquare(square: TextView) {
        square.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), palette.accent)
        }
        square.setTextColor(palette.accent)
    }

    /**
     * Puts the held letter where the list has got to.
     *
     * Which letter is whichever section the top row belongs to, found by walking back to
     * the header above it. Where it sits is the top of the list until the next letter's own
     * row comes up into that space, at which point the two travel together: the incoming
     * square pushes the held one off by however far it has come inside a row's height, so
     * they pass each other at exactly the distance any two letters are apart in the list.
     */
    private fun updateStickyLetter() {
        val manager = list.layoutManager as? LinearLayoutManager
        val first = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val header = if (manager == null || searching || first == RecyclerView.NO_POSITION) {
            RecyclerView.NO_POSITION
        } else {
            sectionHeaderAt(first)
        }
        if (manager == null || header == RecyclerView.NO_POSITION) {
            stickyBand.visibility = GONE
            return
        }

        // Nothing to hold while the section's own header is still standing where this
        // would be drawn - it is already there, and drawing a copy on top of it is two
        // squares to keep in step. Which also covers the pull past the top of the list,
        // where the first letter travels *down* and a held copy would sit on alone.
        val own = manager.findViewByPosition(header)
        if (own != null && own.top >= 0) {
            stickyBand.visibility = GONE
            return
        }

        val text = (rows[header] as Row.Header).letter.lowercase()
        if (stickyLetter.text?.toString() != text) stickyLetter.text = text

        // Only a header that is actually on screen can be pushing this one: anything
        // further down is a screen away from touching it.
        var shift = 0
        val last = manager.findLastVisibleItemPosition()
        for (position in (first + 1)..last) {
            if (rows.getOrNull(position) !is Row.Header) continue
            val view = manager.findViewByPosition(position) ?: break
            shift = minOf(0, view.top - dp(headerDp))
            break
        }

        stickyBand.visibility = VISIBLE
        // The rail moves - it slides away for the search field - and the square is inset by
        // it exactly as the rows are.
        stickyLetter.translationX = list.paddingLeft.toFloat()
        stickyBand.translationY = (column.top + list.top + shift).toFloat()
    }

    /** Where the header for the section [position] is in, which is the one above it. */
    private fun sectionHeaderAt(position: Int): Int {
        for (index in position.coerceAtMost(rows.lastIndex) downTo 0) {
            if (rows[index] is Row.Header) return index
        }
        return RecyclerView.NO_POSITION
    }

    // ---------------------------------------------------------------- the band

    private fun buildHeader() {
        if (hasSearchRing) {
            searchRing.setBackgroundResource(R.drawable.wp81_appbar_circle)
            searchRing.setImageResource(R.drawable.wp81_nav_search)
            searchRing.scaleType = ImageView.ScaleType.FIT_CENTER
            searchRing.setPadding(
                dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP))
            searchRing.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            searchRing.clipToOutline = true
            searchRing.isClickable = true
            searchRing.setOnClickListener { beginSearch() }
            TiltEffect.apply(searchRing)
            header.addView(searchRing, FrameLayout.LayoutParams(
                dp(RING_DP), dp(RING_DP), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(RING_MARGIN_DP)
            })
        }
        // Aligned with the list as it will be once the rail has gone, not as it is now:
        // by the time the field is on screen the rows have spread out to meet it. Which is
        // the same sum the rows themselves do - so it is asked in one place, or the field
        // and the names under it start in two different columns.
        header.addView(searchBox, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, dp(FIELD_DP), Gravity.CENTER_VERTICAL).apply {
            marginStart = searchingInset()
            marginEnd = dp(edgeDp)
        })
    }

    private fun buildSearchBox() {
        searchBox.visibility = GONE
        searchBox.hint = searchHint
        searchBox.setSingleLine()
        searchBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        searchBox.inputType = android.text.InputType.TYPE_CLASS_TEXT
        searchBox.textSize = 16f
        // The shell's one text box, from the one place it is described.
        palette.applyToField(searchBox)
        searchBox.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        searchBox.setPadding(dp(10), dp(6), dp(10), dp(6))
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val typed = s?.toString().orEmpty()
                // Only when it has actually changed. A field is told its own text at both
                // ends of search - emptied as it opens, emptied again as it goes - and
                // TextView reports every one of those whether or not a letter moved. Each
                // report rebuilt the whole list, and the one at the start of a search
                // rebuilt it *with search already begun*, which took the letter squares
                // away in the same frame they had been asked to fold up in.
                if (typed == query) return
                query = typed
                rebuildRows()
                // For a list that can answer with more than it is holding. The rows above
                // are everything already here, drawn at once; anything that has to be
                // fetched arrives later and is handed back through setItems.
                onQueryChanged?.invoke(typed)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            onSearchSubmit?.invoke(query.trim(), matched())
            true
        }
    }

    /**
     * Told what has been typed, as it is typed.
     *
     * Left unset by lists that hold everything they can show - the app list is all the
     * programs there are. A list of people is not: some of them are only in a directory
     * somewhere, and the only way to find those is to go and ask as the letters arrive.
     */
    var onQueryChanged: ((String) -> Unit)? = null

    /** What is in the search field, for whoever asked and is answering late. */
    fun searchText(): String = query

    /** What the list is showing, in order, for whoever asked the keyboard's search key. */
    fun matched(): List<T> =
        rows.filterIsInstance<Row.Item>().mapNotNull { items.getOrNull(it.index) }

    // ---------------------------------------------------------------- search

    /**
     * Puts the list into search: the rail slides out, the letters fold away, the field
     * rises into the band the ring was in.
     *
     * [animated] is what the user is watching, and is right when search is entered on the
     * list itself. It is wrong when the list is on its way in from a swipe - there the page
     * is already moving, and a second animation over it makes the arrival stutter.
     *
     * [showKeyboard] is separate because a swipe can prepare the list before it is certain
     * the user is going there: the layout can be made ready under a finger that may still
     * turn back, but a keyboard must not appear until they have arrived.
     */
    fun beginSearch(animated: Boolean = true, showKeyboard: Boolean = true) {
        if (searching) {
            if (showKeyboard) showSearchKeyboard()
            return
        }
        hideJumpList()
        // Set now rather than when the rows are rebuilt: everything below reads it, and
        // search has begun the moment it was asked for.
        searching = true
        onSearchChanged?.invoke(true)
        // The list spreads into the rail as the ring leaves it, so the two are one
        // movement - the gutter slides out and the rows take the width it had.
        applyListInset(animated)

        // The rail leaves the way a column leaves: sideways, off its own edge. It is not a
        // button on a page that could fade where it stands.
        searchRing.animate().cancel()
        if (hasSearchRing) {
            if (animated) {
                searchRing.animate()
                    .translationX(-dp(RING_MARGIN_DP + RING_DP).toFloat())
                    .setDuration(SWAP_MS)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction { searchRing.visibility = GONE }
                    .start()
            } else {
                searchRing.translationX = -dp(RING_MARGIN_DP + RING_DP).toFloat()
                searchRing.visibility = GONE
            }
        }

        // Every letter square on screen rolls up into its own top edge and its row closes
        // with it, so the sections fold away instead of vanishing and leaving the rows to
        // lurch up into the space. The rows are only rebuilt once that has finished, by
        // which point the headers are flat and taking no room.
        if (animated) collapseLetters { rebuildRows() } else rebuildRows()

        searchBox.setText("")
        searchBox.visibility = VISIBLE
        searchBox.animate().cancel()
        if (animated) {
            searchBox.alpha = 0f
            searchBox.translationY = dp(FIELD_RISE_DP).toFloat()
            searchBox.animate()
                .alpha(1f).translationY(0f)
                .setDuration(SWAP_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            searchBox.alpha = 1f
            searchBox.translationY = 0f
        }

        if (showKeyboard) showSearchKeyboard()
    }

    /** Puts the cursor in the field and asks for the keyboard. */
    fun showSearchKeyboard() {
        if (!searching) return
        searchBox.requestFocus()
        searchBox.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** The way back out: everything [beginSearch] did, in reverse and on the same clock. */
    fun endSearch(animated: Boolean = true) {
        if (!searching && query.isEmpty()) return
        searching = false
        onSearchChanged?.invoke(false)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        // Hidden from the field's own token: by the time this runs focus may already have
        // moved on, and the view's token would no longer reach the running IME.
        imm?.hideSoftInputFromWindow(searchBox.windowToken ?: windowToken, 0)
        searchBox.clearFocus()
        query = ""
        applyListInset(animated)
        // Bound rolled up, and unrolled from there. Cleared once they are all out, so a
        // square bound later by an ordinary scroll is simply there.
        expandLetters = animated
        rebuildRows()
        if (animated) postDelayed({ expandLetters = false }, LETTER_MS)

        searchBox.animate().cancel()
        searchRing.animate().cancel()
        if (animated) {
            searchBox.animate()
                .alpha(0f).translationY(dp(FIELD_RISE_DP).toFloat())
                .setDuration(SWAP_MS)
                .withEndAction {
                    searchBox.visibility = GONE
                    searchBox.setText("")
                }
                .start()

            if (hasSearchRing) {
                searchRing.visibility = VISIBLE
                searchRing.animate()
                    .translationX(0f)
                    .setDuration(SWAP_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction(null)
                    .start()
            }
        } else {
            searchBox.visibility = GONE
            searchBox.setText("")
            searchBox.alpha = 1f
            searchBox.translationY = 0f
            if (hasSearchRing) {
                searchRing.visibility = VISIBLE
                searchRing.translationX = 0f
            }
        }
    }

    /**
     * Rolls every letter square on screen up into itself, then runs [after].
     *
     * Only what is on screen: the rest are rebuilt without headers before they are ever
     * laid out, and animating rows nobody is looking at is work for its own sake.
     */
    private fun collapseLetters(after: () -> Unit) {
        var any = false
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i))
                as? MetroIndexList<T>.HeaderHolder ?: continue
            holder.collapse()
            any = true
        }
        if (any) postDelayed({ after() }, LETTER_MS) else after()
    }

    /**
     * Where the list starts, on both edges, and how it gets there.
     *
     * The rail is the list's own left padding rather than an indent on every row, which is
     * what lets it *go*: entering search takes the whole gutter away and the rows spread
     * left into it, all of them together, because there is one number moving and not one
     * per row.
     *
     * The top is the band the field takes. None of it at rest - the ring is in the rail,
     * where no row goes - and all of it in search, where a result behind the field is a
     * result nobody can read.
     */
    private fun applyListInset(animated: Boolean) {
        val toLeft = if (searching) searchingInset() else dp(railDp)
        val toTop = if (searching) dp(BAND_DP) else 0
        railAnimator?.cancel()
        if (!animated) {
            setInsets(toLeft, toTop)
            return
        }
        val fromLeft = list.paddingLeft
        val fromTop = column.paddingTop
        railAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                setInsets(
                    (fromLeft + (toLeft - fromLeft) * t).toInt(),
                    (fromTop + (toTop - fromTop) * t).toInt()
                )
            }
            start()
        }
    }

    /**
     * The rail on the list, the band on the column holding it.
     *
     * Deliberately two different views. The rail is padding *inside* the list, because the
     * rows have to be able to scroll past it and be clipped by it. The band is padding on
     * the column, which moves the list itself down: as the list's own top padding it was
     * one more thing the scroll position was measured against, and a list that had been
     * touched at all opened its search with the first result sitting behind the field.
     */
    /**
     * Where the rows sit while a search is on.
     *
     * The rail is the gutter the search ring stands in, and search is when the ring goes
     * away - so a list that has one reclaims that column, moving its rows out to the page
     * edge. A list with no ring has no gutter and nothing to reclaim, and sliding it
     * sideways as the field opens would be an animation about a control it does not have.
     * See ContactList, which asks for search from the app bar instead.
     */
    private fun searchingInset(): Int = if (hasSearchRing) dp(edgeDp) else dp(railDp)

    private fun setInsets(left: Int, top: Int) {
        list.setPadding(left, 0, 0, 0)
        column.setPadding(0, top, 0, 0)
        updateStickyLetter()
    }

    fun isSearching(): Boolean = searching

    /**
     * Drops the keyboard without leaving search.
     *
     * The field and its query stay exactly as they were, so dismissing whatever needed the
     * room returns to the same filtered list.
     */
    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchBox.windowToken ?: windowToken, 0)
        searchBox.clearFocus()
    }

    // ---------------------------------------------------------------- data

    /** Whatever the list is of, in the order it should be filed. */
    fun setItems(list: List<T>) {
        items.clear()
        items.addAll(list)
        rebuildRows()
    }

    fun items(): List<T> = items

    /**
     * Rebuilds the flattened row list for the current query.
     *
     * While filtering the letter headers are dropped: a handful of matches spread under
     * their own headers reads as clutter, and the jump list has nothing to jump to.
     */
    protected fun rebuildRows() {
        rows.clear()
        val needle = query.trim().lowercase()
        // The letters belong to the list at rest. In search they are headings over nothing
        // - a section of one, or of none - so the moment search is entered they go, before
        // a single letter has been typed.
        if (needle.isEmpty() && !searching) {
            var current: Char? = null
            for ((index, item) in items.withIndex()) {
                val letter = letterOf(item)
                if (letter != current) {
                    rows.add(Row.Header(letter))
                    current = letter
                }
                rows.add(Row.Item(index))
            }
        } else {
            for ((index, item) in items.withIndex()) {
                if (matches(item, needle)) rows.add(Row.Item(index))
            }
        }
        jumpList.setAvailableLetters(
            rows.filterIsInstance<Row.Header>().map { it.letter }.toSet())
        adapter.notifyDataSetChanged()
        if (needle.isNotEmpty()) list.scrollToPosition(0)
    }

    /** WP8.1 files anything not starting with a letter under a single '#' bucket. */
    protected fun bucketOf(name: String): Char {
        val c = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun scrollToLetter(letter: Char) {
        val index = rows.indexOfFirst { it is Row.Header && it.letter == letter }
        if (index >= 0) {
            (list.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(index, 0)
        }
    }

    // ---------------------------------------------------------------- jump list

    fun showJumpList() {
        jumpList.visibility = VISIBLE
        jumpList.playEntrance()
    }

    fun hideJumpList() {
        jumpList.visibility = GONE
    }

    fun isJumpListVisible(): Boolean = jumpList.visibility == VISIBLE

    /** True if the list consumed the back press. */
    fun handleBack(): Boolean {
        if (isJumpListVisible()) {
            hideJumpList()
            return true
        }
        // Backing out of search is a step inside the list, not a way out of it: the field
        // goes, the ring comes back, the letters return.
        if (searching) {
            endSearch()
            return true
        }
        return false
    }

    open fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        // The field is white with black in it under either setting, but its selection
        // band is the accent's. The ring has to follow the page too, since a white ring on
        // a white page is no ring at all.
        p.applyToField(searchBox)
        val ink = ColorStateList.valueOf(p.foreground)
        searchRing.backgroundTintList = ink
        searchRing.imageTintList = ink
        jumpList.applyPalette(p)
        // The held letter is one of the list's squares and takes the same accent as the
        // rest, but it is not one of the adapter's rows and is not rebound by them. Its
        // band is the page it is standing in for, and takes the page's own colour.
        paintLetterSquare(stickyLetter)
        stickyBand.setBackgroundColor(p.background)
        adapter.notifyDataSetChanged()
    }

    fun scrollToTop() {
        list.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- rows

    /**
     * One row, made by the list that knows what its rows are.
     *
     * [view] is handed over once and rebound as it is recycled. Wire the row's own click
     * and hold to [pick] and [held], which is how a row reaches the list it is in.
     */
    protected abstract inner class ItemHolder(val view: View) {
        abstract fun bind(item: T)
    }

    /** A row has been chosen. */
    protected fun pick(item: T) {
        onPick?.invoke(item)
    }

    /** A row has been held, with its bottom edge for whatever is put up against it. */
    protected fun held(item: T, row: View) {
        onLongPress?.invoke(item, anchorYOf(row))
    }

    /** A row's bottom edge relative to this view, across the RecyclerView's own offset. */
    private fun anchorYOf(row: View): Float {
        val rowLoc = IntArray(2)
        val selfLoc = IntArray(2)
        row.getLocationInWindow(rowLoc)
        getLocationInWindow(selfLoc)
        return (rowLoc[1] - selfLoc[1] + row.height).toFloat()
    }

    /** The page margin a row should stop at on the right. For a subclass's own rows. */
    protected fun rowEdge(): Int = dp(edgeDp)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) HeaderHolder(context) else Wrapper(createHolder())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as MetroIndexList<T>.HeaderHolder).bind(row.letter)
                is Row.Item -> items.getOrNull(row.index)?.let {
                    (holder as MetroIndexList<T>.Wrapper).holder.bind(it)
                }
            }
        }
    }

    /** Carries a subclass's holder into the RecyclerView's own. */
    private inner class Wrapper(val holder: ItemHolder) :
        RecyclerView.ViewHolder(holder.view)

    private inner class HeaderHolder(context: Context) :
        RecyclerView.ViewHolder(FrameLayout(context)) {

        private val square = TextView(context)

        private var roll: ValueAnimator? = null

        init {
            val root = itemView as FrameLayout
            // A stated height rather than one measured from the square inside it: the row
            // is animated to nothing and back, and an animator needs a number to return to.
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(headerDp))
            // No left padding of its own: the rail is the list's, so that it can move.
            root.setPadding(0, dp(HEADER_TOP_DP), dp(edgeDp), dp(HEADER_BOTTOM_DP))

            // Bottom left, not centred: the letter sits in the corner of its square the
            // way a tile's name sits in the corner of a tile, and the square reads as a
            // small tile rather than as a button with a letter on it.
            paintSquareType(square)
            root.addView(square, FrameLayout.LayoutParams(dp(squareDp), dp(squareDp)))
            TiltEffect.apply(square)
            square.setOnClickListener { showJumpList() }
        }

        fun bind(letter: Char) {
            square.text = letter.lowercase()
            paintLetterSquare(square)

            roll?.cancel()
            square.animate().cancel()
            // Set from scratch every time, because the view this is being set on may have
            // been left flat by the last collapse it took part in.
            square.pivotY = 0f
            if (expandLetters) {
                setHeight(0)
                square.scaleY = 0f
                square.alpha = 0f
                rollTo(dp(headerDp))
                square.animate().scaleY(1f).alpha(1f)
                    .setDuration(LETTER_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                setHeight(dp(headerDp))
                square.scaleY = 1f
                square.alpha = 1f
            }
        }

        /** Up into its own top edge, taking the row's height with it. */
        fun collapse() {
            square.animate().cancel()
            square.pivotY = 0f
            square.animate().scaleY(0f).alpha(0f)
                .setDuration(LETTER_MS)
                .setInterpolator(AccelerateInterpolator())
                .start()
            rollTo(0)
        }

        private fun rollTo(target: Int) {
            roll?.cancel()
            roll = ValueAnimator.ofInt(itemView.layoutParams.height, target).apply {
                duration = LETTER_MS
                addUpdateListener { setHeight(it.animatedValue as Int) }
                start()
            }
        }

        private fun setHeight(value: Int) {
            itemView.layoutParams.height = value
            itemView.requestLayout()
        }
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        // --- the rail ------------------------------------------------------------------
        // The ring's own column, kept clear the whole way down the page so it reads as
        // being beside the list rather than in it, with the ring itself on the line of the
        // first letter square.

        const val RING_DP = 44
        const val RING_MARGIN_DP = 14

        /** Between the rail and the list it is beside. */
        const val RAIL_GAP_DP = 10

        /**
         * The band the ring and the field share.
         *
         * Deep enough to put the ring's centre on the first letter square's: that square
         * is 44dp with 10 above it, so its middle is 32 down, and a 64dp band centres on
         * the same line.
         */
        const val BAND_DP = 64

        /**
         * How far the magnifier sits inside its ring.
         *
         * Small. The mark covers less than half of its own canvas - it is drawn that way,
         * with air around it, like every glyph in the set - so an inset on top of that was
         * a magnifier of ten device-independent pixels inside a forty-four pixel circle.
         */
        private const val RING_INSET_DP = 4
        private const val FIELD_DP = 40

        /** The air above and below a letter square, which makes up its row. */
        private const val HEADER_TOP_DP = 10
        private const val HEADER_BOTTOM_DP = 6

        /** How the two of them swap: the rail slides off, the field rises in. */
        private const val SWAP_MS = 180L
        private const val FIELD_RISE_DP = 10

        /** And how long a letter square takes to roll up into itself, or back out. */
        private const val LETTER_MS = 180L

        /**
         * How far in from either edge the system's own gestures live.
         *
         * A touch that starts inside this is a back drag until proven otherwise, and
         * nothing in a list may treat it as a hold. Android's own exclusion limit is about
         * twenty; a little more is the safer side to be wrong on, since what is lost is the
         * ability to open a menu by holding the very rim of the screen.
         */
        const val SYSTEM_GESTURE_DP = 28
    }
}
