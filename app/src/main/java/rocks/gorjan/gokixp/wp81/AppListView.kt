package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    private val list = RecyclerView(context)
    private val jumpList = JumpListView(context, palette)
    private val searchBox = android.widget.EditText(context)
    private val column = LinearLayout(context)

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
        column.orientation = LinearLayout.VERTICAL
        column.addView(searchBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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

    private fun buildSearchBox() {
        searchBox.visibility = GONE
        searchBox.hint = "search apps"
        searchBox.setSingleLine()
        searchBox.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        searchBox.inputType = android.text.InputType.TYPE_CLASS_TEXT
        searchBox.textSize = 18f
        searchBox.background = null
        searchBox.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        searchBox.setPadding(dp(24), dp(16), dp(24), dp(12))
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString().orEmpty()
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

    fun beginSearch() {
        hideJumpList()
        searchBox.visibility = VISIBLE
        searchBox.setText("")
        searchBox.requestFocus()
        searchBox.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun endSearch() {
        if (!isSearching() && query.isEmpty()) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        // Hidden from the field's own token: by the time this runs focus may already have
        // moved on, and the view's token would no longer reach the running IME.
        imm?.hideSoftInputFromWindow(searchBox.windowToken ?: windowToken, 0)
        searchBox.setText("")
        searchBox.clearFocus()
        searchBox.visibility = GONE
        query = ""
        rebuildRows()
    }

    fun isSearching(): Boolean = searchBox.visibility == VISIBLE

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
        if (needle.isEmpty()) {
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
        return false
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        p.applyToField(searchBox)
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

        init {
            val root = itemView as FrameLayout
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val pad = dp(24)
            root.setPadding(pad, dp(10), pad, dp(6))

            // Bottom left, not centred: the letter sits in the corner of its square the
            // way a tile's name sits in the corner of a tile, and the square reads as a
            // small tile rather than as a button with a letter on it.
            square.gravity = Gravity.BOTTOM or Gravity.START
            square.textSize = 20f
            square.setPadding(dp(6), 0, 0, dp(2))
            square.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            square.setTextColor(Color.WHITE)
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
        }
    }

    private inner class AppHolder(context: Context) :
        RecyclerView.ViewHolder(LinearLayout(context)) {

        private val icon = ImageView(context)
        private val name = TextView(context)
        private var bound: AppInfo? = null

        init {
            val root = itemView as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
            root.setPadding(dp(24), 0, dp(24), 0)
            root.isClickable = true

            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

            name.textSize = 16f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            root.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })

            TiltEffect.apply(root)
            root.setOnClickListener {
                bound?.let { app ->
                    val pick = onPick
                    if (pick != null) pick(app) else onLaunch?.invoke(app)
                }
            }
            root.setOnLongClickListener {
                bound?.let { app -> onLongPress?.invoke(app, anchorYOf(root)) }
                true
            }
        }

        fun bind(info: AppInfo) {
            bound = info
            name.text = info.name
            name.setTextColor(palette.foreground)
            // The app list shows real launcher icons, as WP8.1 did - only tiles get the
            // flat monochrome treatment.
            icon.setImageDrawable(info.icon)
            icon.imageTintList = null
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
    }
}
