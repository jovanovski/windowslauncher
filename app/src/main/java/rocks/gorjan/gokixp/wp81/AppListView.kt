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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.AppInfo
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 app list - the alphabetical page you reach by swiping left from
 * Start.
 *
 * Apps are grouped under accent-coloured letter headers. Tapping any header collapses the
 * list into the **jump list**: a grid of every letter, with the ones that have no apps
 * greyed out. Picking a letter there drops you back at that section.
 */
@SuppressLint("ViewConstructor")
class AppListView(
    context: Context,
    private var palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : FrameLayout(context) {

    var onLaunch: ((AppInfo) -> Unit)? = null

    /**
     * Set while the list is being used to *choose* an app rather than open one - filling a
     * folder, for instance. Takes precedence over [onLaunch] so the same list serves both
     * jobs without a second screen that looks identical but behaves differently.
     */
    var onPick: ((AppInfo) -> Unit)? = null
    /** Long-press on a row. Reports the app and the row's bottom edge in this view's
     *  coordinates, so the host can anchor a context menu to it. */
    var onLongPress: ((AppInfo, Float) -> Unit)? = null

    /**
     * The mark to draw for one of the shell's own Metro apps, or null for anything else.
     *
     * Windows Phone drew its own programs as a flat white glyph on a square of the accent,
     * and everything installed from a shop as that app's own icon. This is the first half
     * of that: an app the host recognises as one of the shell's hands back the drawable to
     * use, and the row paints the square round it. The host decides which those are - it is
     * the only thing that knows what a package opens into.
     */
    var metroGlyph: ((AppInfo) -> Int?)? = null

    private val list = RecyclerView(context)
    private val jumpList = JumpListView(context, palette)
    private val searchBox = android.widget.EditText(context)

    /**
     * The band over the list: the search button, and the field that takes its place.
     *
     * One band holding both, because they are the same thing in its two states - the
     * button is search before it has been asked for, and the field is search once it has.
     * A fixed height, so the list underneath does not step up and down as they swap.
     */
    private val header = FrameLayout(context)
    private val searchButton = ImageView(context)
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
     *
     * Read by the header holder as it binds, which is where a square learns its state - a
     * row is bound as it arrives, and there is no frame in between to catch it in.
     */
    private var expandLetters = false

    /** The rail widening or closing. See [applyListInset]. */
    private var railAnimator: ValueAnimator? = null

    private val allApps = mutableListOf<AppInfo>()
    private val rows = mutableListOf<Row>()
    private val adapter = Adapter()
    private var query: String = ""

    /** One entry in the flattened list: either a letter header or an app. */
    private sealed class Row {
        data class Header(val letter: Char) : Row()
        data class App(val info: AppInfo) : Row()
    }

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        // Deliberately a plain RecyclerView with MATCH_PARENT height. The start menu once
        // stalled ~800ms because an UNSPECIFIED height spec made RecyclerView measure every
        // row up front (fixed by BoundedHeightRecyclerView); a bounded height avoids that
        // whole class of problem here.

        buildSearchBox()
        buildHeader()
        column.orientation = LinearLayout.VERTICAL
        column.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Over the list rather than above it. The button belongs in the rail beside the
        // first letter, on its line - a band of its own over the top would be a row of
        // nothing but the button, and would push the whole list down a button's height to
        // make room for it. The rail is empty of everything else, so there is nothing for
        // it to sit on top of. See ROW_INSET_DP.
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(BAND_DP)))
        applyListInset(animated = false)

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
        addView(jumpList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyPalette(palette)
    }

    // ---------------------------------------------------------------- search

    /**
     * The band's two states, side by side and never both at once.
     *
     * The button is at the left, in the gutter the list has been moved right of: a ring
     * with the magnifier in it, the same button the app bars wear. Tapping it hands the
     * band over to the field - see [beginSearch].
     */
    private fun buildHeader() {
        searchButton.setBackgroundResource(R.drawable.wp81_appbar_circle)
        searchButton.setImageResource(R.drawable.wp81_nav_search)
        searchButton.scaleType = ImageView.ScaleType.FIT_CENTER
        searchButton.setPadding(dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP))
        searchButton.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        searchButton.clipToOutline = true
        searchButton.isClickable = true
        searchButton.setOnClickListener { beginSearch() }
        TiltEffect.apply(searchButton)
        header.addView(searchButton, FrameLayout.LayoutParams(
            dp(RING_DP), dp(RING_DP), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(RING_MARGIN_DP)
        })
        // Aligned with the list as it will be once the rail has gone, not as it is now:
        // by the time the field is on screen the rows have spread out to meet it.
        header.addView(searchBox, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, dp(FIELD_DP), Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(EDGE_DP)
            marginEnd = dp(EDGE_DP)
        })
    }

    private fun buildSearchBox() {
        searchBox.visibility = GONE
        searchBox.hint = "search apps"
        searchBox.setSingleLine()
        searchBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        searchBox.inputType = android.text.InputType.TYPE_CLASS_TEXT
        searchBox.textSize = 16f
        // The shell's one text box, from the one place it is described. See
        // WP81Palette.applyToField.
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
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            val matches = rows.filterIsInstance<Row.App>()
            when {
                // The one at the top, however many there are. The list is already sorted
                // by how well each answers the query, so the first of them is the app the
                // user was typing towards - waiting for the field to narrow to exactly one
                // meant pressing search usually did nothing.
                matches.isNotEmpty() -> onLaunch?.invoke(matches.first().info)
                // Nothing on the phone answers to it, so the phone is not where the answer
                // is. Pressing search having been shown an empty list is a clear enough
                // request to look further afield.
                query.isNotBlank() -> onSearchWeb?.invoke(query.trim())
            }
            true
        }
    }

    /** Opens the list in search mode: field shown, focused, keyboard up. */
    /**
     * Pressing search with a query that matched nothing installed.
     *
     * The list has said everything it can at that point; where the query goes next is the
     * host's business, not this view's.
     */
    var onSearchWeb: ((String) -> Unit)? = null

    /**
     * Hands the band over to the field, and the list with it.
     *
     * Three things move together and none of them jumps: the button shrinks away, the
     * field rises into the space it leaves, and the list behind them changes shape - the
     * letters go, because a search has no sections and their squares would be headings
     * over nothing. The list is faded across that change rather than snapping, since what
     * is being removed is every third row of it.
     */
    fun beginSearch() {
        if (searching) return
        hideJumpList()
        // Set now rather than when the rows are rebuilt: everything below reads it, and
        // search has begun the moment it was asked for.
        searching = true
        // The list spreads into the rail as the button leaves it, so the two are one
        // movement - the gutter slides out and the apps take the width it had.
        applyListInset(animated = true)

        // The rail leaves the way a column leaves: sideways, off its own edge. It is not a
        // button on a page that could fade where it stands.
        searchButton.animate().cancel()
        searchButton.animate()
            .translationX(-dp(RING_MARGIN_DP + RING_DP).toFloat())
            .setDuration(SWAP_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { searchButton.visibility = GONE }
            .start()

        // Every letter square on screen rolls up into its own top edge and its row closes
        // with it, so the sections fold away instead of vanishing and leaving the apps to
        // lurch up into the space. The rows are only rebuilt once that has finished, by
        // which point the headers are flat and taking no room.
        collapseLetters { rebuildRows() }

        searchBox.setText("")
        searchBox.visibility = VISIBLE
        searchBox.alpha = 0f
        searchBox.translationY = dp(FIELD_RISE_DP).toFloat()
        searchBox.animate().cancel()
        searchBox.animate()
            .alpha(1f).translationY(0f)
            .setDuration(SWAP_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        searchBox.requestFocus()
        searchBox.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** The way back out: everything [beginSearch] did, in reverse and on the same clock. */
    fun endSearch() {
        if (!searching && query.isEmpty()) return
        searching = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        // Hidden from the field's own token: by the time this runs focus may already have
        // moved on, and the view's token would no longer reach the running IME.
        imm?.hideSoftInputFromWindow(searchBox.windowToken ?: windowToken, 0)
        searchBox.clearFocus()
        query = ""
        applyListInset(animated = true)
        // Bound rolled up, and unrolled from there. Cleared once they are all out, so a
        // square bound later by an ordinary scroll is simply there.
        expandLetters = true
        rebuildRows()
        postDelayed({ expandLetters = false }, LETTER_MS)

        searchBox.animate().cancel()
        searchBox.animate()
            .alpha(0f).translationY(dp(FIELD_RISE_DP).toFloat())
            .setDuration(SWAP_MS)
            .withEndAction {
                searchBox.visibility = GONE
                searchBox.setText("")
            }
            .start()

        searchButton.visibility = VISIBLE
        searchButton.animate().cancel()
        searchButton.animate()
            .translationX(0f)
            .setDuration(SWAP_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
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
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? HeaderHolder ?: continue
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
     * per row. At rest that padding is the button's column; in search it is the plain page
     * margin, the button having slid off the same edge the list is expanding over.
     *
     * The top is the band the field takes. None of it at rest - the button is in the rail,
     * where no row goes, so the first letter sits on the button's line rather than under
     * it - and all of it in search, where a result behind the field is a result nobody can
     * read.
     */
    private fun applyListInset(animated: Boolean) {
        val toLeft = if (searching) dp(EDGE_DP) else dp(ROW_INSET_DP)
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
    private fun setInsets(left: Int, top: Int) {
        list.setPadding(left, 0, 0, 0)
        column.setPadding(0, top, 0, 0)
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

    fun setApps(apps: List<AppInfo>) {
        allApps.clear()
        allApps.addAll(apps.sortedBy { it.name.lowercase() })
        rebuildRows()
    }

    /**
     * Rebuilds the flattened row list for the current query.
     *
     * While filtering the letter headers are dropped: a handful of matches spread under
     * their own headers reads as clutter, and the jump list has nothing to jump to.
     */
    private fun rebuildRows() {
        rows.clear()
        val needle = query.trim().lowercase()
        // The letters belong to the list at rest. In search they are headings over nothing
        // - a section of one, or of none - so the moment search is entered they go, before
        // a single letter has been typed.
        if (needle.isEmpty() && !searching) {
            var current: Char? = null
            for (app in allApps) {
                val letter = bucketOf(app.name)
                if (letter != current) {
                    rows.add(Row.Header(letter))
                    current = letter
                }
                rows.add(Row.App(app))
            }
        } else {
            for (app in allApps) {
                if (app.name.lowercase().contains(needle)) rows.add(Row.App(app))
            }
        }
        jumpList.setAvailableLetters(rows.filterIsInstance<Row.Header>().map { it.letter }.toSet())
        adapter.notifyDataSetChanged()
        if (needle.isNotEmpty()) list.scrollToPosition(0)
    }

    /** WP8.1 files anything not starting with a letter under a single '#' bucket. */
    private fun bucketOf(name: String): Char {
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

    /** True if the view consumed the back press. */
    fun handleBack(): Boolean {
        if (isJumpListVisible()) {
            hideJumpList()
            return true
        }
        // Backing out of search is a step inside the list, not a way out of it: the field
        // goes, the button comes back, the letters return.
        if (searching) {
            endSearch()
            return true
        }
        return false
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        // The field is white with black in it under either setting, but its selection
        // band is the accent's - see WP81Palette.applyToField. The ring has to follow the
        // page too, since a white ring on a white page is no ring at all.
        p.applyToField(searchBox)
        val ink = ColorStateList.valueOf(p.foreground)
        searchButton.backgroundTintList = ink
        searchButton.imageTintList = ink
        jumpList.applyPalette(p)
        adapter.notifyDataSetChanged()
    }

    fun scrollToTop() {
        list.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_APP

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) HeaderHolder(context) else AppHolder(context)

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.letter)
                is Row.App -> (holder as AppHolder).bind(row.info)
            }
        }
    }

    private inner class HeaderHolder(context: Context) :
        RecyclerView.ViewHolder(FrameLayout(context)) {

        private val square = TextView(context)

        private var roll: ValueAnimator? = null

        init {
            val root = itemView as FrameLayout
            // A stated height rather than one measured from the square inside it: the row
            // is animated to nothing and back, and an animator needs a number to return to.
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_DP))
            // No left padding of its own: the rail is the list's, so that it can move.
            root.setPadding(0, dp(10), dp(EDGE_DP), dp(6))

            // Bottom left, not centred: the letter sits in the corner of its square the
            // way a tile's name sits in the corner of a tile, and the square reads as a
            // small tile rather than as a button with a letter on it.
            square.gravity = Gravity.BOTTOM or Gravity.START
            square.textSize = LETTER_SP
            square.setPadding(dp(6), 0, 0, dp(2))
            square.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            root.addView(square, FrameLayout.LayoutParams(dp(44), dp(44)))
            TiltEffect.apply(square)
            square.setOnClickListener { showJumpList() }
        }

        fun bind(letter: Char) {
            square.text = letter.lowercase()
            // Outlined rather than filled. A wall of solid accent squares down the side of
            // the list competes with the tiles it is sitting next to; an outline says the
            // same thing and lets the list be the thing being read.
            square.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.BLACK)
                setStroke(dp(2), palette.accent)
            }
            // The letter in the border's own colour rather than in white. The square is
            // one mark, not a white letter inside a coloured frame - and set in the accent
            // it stops competing with the app names beside it, which are the white thing
            // on this page.
            square.setTextColor(palette.accent)

            roll?.cancel()
            square.animate().cancel()
            // Set from scratch every time, because the view this is being set on may have
            // been left flat by the last collapse it took part in.
            square.pivotY = 0f
            if (expandLetters) {
                setHeight(0)
                square.scaleY = 0f
                square.alpha = 0f
                rollTo(dp(HEADER_DP))
                square.animate().scaleY(1f).alpha(1f)
                    .setDuration(LETTER_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                setHeight(dp(HEADER_DP))
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

    private inner class AppHolder(context: Context) :
        RecyclerView.ViewHolder(LinearLayout(context)) {

        private val icon = ImageView(context)
        private val name = TextView(context)
        private var bound: AppInfo? = null

        /** Whether this row's press began in the system's own gesture strip. */
        private var fromEdge = false

        init {
            val root = itemView as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
            root.setPadding(0, 0, dp(EDGE_DP), 0)
            root.isClickable = true

            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))

            name.textSize = 16f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            root.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })

            TiltEffect.apply(root) { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val edge = dp(SYSTEM_GESTURE_DP)
                    val width = resources.displayMetrics.widthPixels
                    fromEdge = event.rawX < edge || event.rawX > width - edge
                }
                false
            }
            root.setOnClickListener {
                bound?.let { app ->
                    val pick = onPick
                    if (pick != null) pick(app) else onLaunch?.invoke(app)
                }
            }
            // The tick is the row's to give, not the framework's. A claimed long press is
            // answered with one automatically, and that fires the moment the listener says
            // it handled the press - including when all it did was refuse an edge drag, so
            // a back gesture buzzed on its way past. Turned off here and given by hand
            // below, ignoring the view's own setting rather than the user's.
            root.isHapticFeedbackEnabled = false
            root.setOnLongClickListener {
                // Not from the very edge of the screen. That is where the system's back
                // gesture starts, and a back drag begins as a finger held still against
                // the edge for as long as it takes to decide - which is long enough for a
                // row under it to call that a press-and-hold. The menu then opened on the
                // way out of the list and was still standing over Start when the drag
                // landed there.
                //
                // Claimed rather than declined, so the release that follows is not read as
                // a tap and does not launch whatever the finger happened to be resting on.
                if (fromEdge) return@setOnLongClickListener true
                root.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
                bound?.let { app -> onLongPress?.invoke(app, anchorYOf(root)) }
                true
            }
        }

        fun bind(info: AppInfo) {
            bound = info
            name.text = info.name
            name.setTextColor(palette.foreground)
            // The shell's own programs are drawn the way the phone drew the ones that came
            // with it: the glyph in white on a square of the accent. Everything installed
            // from a shop keeps its own icon, as WP8.1 did - the square is what tells the
            // two apart at a glance, and it is the same square those apps' tiles wear.
            val glyph = metroGlyph?.invoke(info)
            if (glyph != null) {
                icon.setImageResource(glyph)
                icon.imageTintList = ColorStateList.valueOf(palette.onAccent())
                icon.setBackgroundColor(palette.accent)
                val inset = dp(GLYPH_INSET_DP)
                icon.setPadding(inset, inset, inset, inset)
            } else {
                icon.setImageDrawable(info.icon)
                icon.imageTintList = null
                icon.background = null
                icon.setPadding(0, 0, 0, 0)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A row's bottom edge relative to this view, across the RecyclerView's own offset. */
    private fun anchorYOf(row: View): Float {
        val rowLoc = IntArray(2)
        val selfLoc = IntArray(2)
        row.getLocationInWindow(rowLoc)
        getLocationInWindow(selfLoc)
        return (rowLoc[1] - selfLoc[1] + row.height).toFloat()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1

        /** The letter over its section. A fifth larger than it was, and in the accent. */
        private const val LETTER_SP = 24f

        /** The square a Metro app's glyph sits on, and the box every other icon fills. */
        private const val ICON_DP = 42

        /**
         * How far a Metro app's glyph sits inside its accent square.
         *
         * A hair. These marks are drawn with their own air - the shape covers about half of
         * the canvas it is centred on - so an inset on top of that is air twice over. What
         * is left keeps the glyph off the square's own edge and no more: a 40dp glyph on
         * the 42dp square.
         */
        private const val GLYPH_INSET_DP = 1

        // --- the rail ------------------------------------------------------------------
        // The button's own column, kept clear the whole way down the page so it reads as
        // being beside the list rather than in it, with the button itself on the line of
        // the first letter square.

        private const val RING_DP = 44
        private const val RING_MARGIN_DP = 14

        /** Between the rail and the list it is beside. */
        private const val RAIL_GAP_DP = 10

        /**
         * Where the rows start, and where they stop.
         *
         * Everything in a row - the letter squares, the icons, the names - begins on the
         * far side of the rail. The right is the ordinary page margin.
         */
        private const val ROW_INSET_DP = RING_MARGIN_DP + RING_DP + RAIL_GAP_DP
        private const val EDGE_DP = 24

        /**
         * The band the button and the field share.
         *
         * Deep enough to put the button's centre on the first letter square's: that square
         * is 44dp with 10 above it, so its middle is 32 down, and a 64dp band centres on
         * the same line.
         */
        private const val BAND_DP = 64

        /**
         * How far the magnifier sits inside its ring.
         *
         * Small. The mark covers less than half of its own canvas - it is drawn that way,
         * with air around it, like every glyph in the set - so an inset on top of that was
         * a magnifier of ten device-independent pixels inside a forty-four pixel circle.
         */
        private const val RING_INSET_DP = 4
        private const val FIELD_DP = 40

        /** A letter header's row: the square, and the air above and below it. */
        private const val HEADER_DP = 60

        /** How the two of them swap: the rail slides off, the field rises in. */
        private const val SWAP_MS = 180L
        private const val FIELD_RISE_DP = 10

        /** And how long a letter square takes to roll up into itself, or back out. */
        private const val LETTER_MS = 180L

        /**
         * How far in from either edge the system's own gestures live.
         *
         * A touch that starts inside this is a back drag until proven otherwise, and
         * nothing in the list may treat it as a hold. Android's own exclusion limit is
         * about twenty; a little more is the safer side to be wrong on, since what is lost
         * is the ability to open a menu by holding the very rim of the screen.
         */
        private const val SYSTEM_GESTURE_DP = 28
    }
}
