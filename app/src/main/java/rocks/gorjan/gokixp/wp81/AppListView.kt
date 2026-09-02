package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.AppInfo
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 app list - the alphabetical page you reach by swiping left from
 * Start.
 *
 * Everything that makes it that page - the letter squares, the jump grid behind them, the
 * held letter at the top, the search band that takes the rail's place - is
 * [MetroIndexList], which the People hub's own list is built on too. What is left here is
 * the part that is about *apps*: a row is an icon and a name, the shell's own programs
 * wear a glyph on a square of the accent, and a search that matches nothing installed is
 * handed on to the web.
 */
@SuppressLint("ViewConstructor")
class AppListView(
    context: Context,
    palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : MetroIndexList<AppInfo>(context, palette) {

    /**
     * Opening an app, as against choosing one.
     *
     * [onPick] - the list's own, from [MetroIndexList] - is set while the list is being
     * used to *choose* an app rather than open one, filling a folder for instance, and
     * takes precedence. The same list serves both jobs without a second screen that looks
     * identical and behaves differently.
     */
    var onLaunch: ((AppInfo) -> Unit)? = null

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

    /**
     * Pressing search with a query that matched nothing installed.
     *
     * The list has said everything it can at that point; where the query goes next is the
     * host's business, not this view's.
     */
    var onSearchWeb: ((String) -> Unit)? = null

    override val searchHint: String get() = "search apps"

    init {
        build()
        onSearchSubmit = { typed, matches ->
            when {
                // The one at the top, however many there are. The list is already sorted
                // by how well each answers the query, so the first of them is the app the
                // user was typing towards - waiting for the field to narrow to exactly one
                // meant pressing search usually did nothing.
                matches.isNotEmpty() -> onLaunch?.invoke(matches.first())
                // Nothing on the phone answers to it, so the phone is not where the answer
                // is. Pressing search having been shown an empty list is a clear enough
                // request to look further afield.
                typed.isNotBlank() -> onSearchWeb?.invoke(typed)
            }
        }
    }

    fun setApps(apps: List<AppInfo>) {
        setItems(apps.sortedBy { it.name.lowercase() })
    }

    override fun letterOf(item: AppInfo): Char = bucketOf(item.name)

    override fun matches(item: AppInfo, query: String): Boolean =
        item.name.lowercase().contains(query)

    override fun createHolder(): ItemHolder = AppHolder()

    private inner class AppHolder : ItemHolder(LinearLayout(context)) {

        private val icon = ImageView(context)
        private val name = TextView(context)
        private var bound: AppInfo? = null

        /** Whether this row's press began in the system's own gesture strip. */
        private var fromEdge = false

        init {
            val root = view as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP))
            root.setPadding(0, 0, rowEdge(), 0)
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
                    if (onPick != null) pick(app) else onLaunch?.invoke(app)
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
                bound?.let { app -> held(app, root) }
                true
            }
        }

        override fun bind(item: AppInfo) {
            bound = item
            name.text = item.name
            name.setTextColor(palette.foreground)
            // The shell's own programs are drawn the way the phone drew the ones that came
            // with it: the glyph in white on a square of the accent. Everything installed
            // from a shop keeps its own icon, as WP8.1 did - the square is what tells the
            // two apart at a glance, and it is the same square those apps' tiles wear.
            val glyph = metroGlyph?.invoke(item)
            if (glyph != null) {
                icon.setImageResource(glyph)
                icon.imageTintList = ColorStateList.valueOf(palette.onAccent())
                icon.setBackgroundColor(palette.accent)
                val inset = dp(GLYPH_INSET_DP)
                icon.setPadding(inset, inset, inset, inset)
            } else {
                icon.setImageDrawable(item.icon)
                icon.imageTintList = null
                icon.background = null
                icon.setPadding(0, 0, 0, 0)
            }
        }
    }

    private companion object {
        const val ROW_DP = 62

        /** The square a Metro app's glyph sits on, and the box every other icon fills. */
        const val ICON_DP = 42

        /**
         * How far a Metro app's glyph sits inside its accent square.
         *
         * A hair. These marks are drawn with their own air - the shape covers about half of
         * the canvas it is centred on - so an inset on top of that is air twice over. What
         * is left keeps the glyph off the square's own edge and no more: a 40dp glyph on
         * the 42dp square.
         */
        const val GLYPH_INSET_DP = 1
    }
}
