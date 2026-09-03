package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
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
 * the part that is about *apps*: a row is an icon and a name, an app with flat artwork
 * wears it in white on a square of the accent, and a search that matches nothing installed
 * is handed on to the web.
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
     * The host decides which apps are the shell's own - it is the only thing that knows
     * what a package opens into - and hands back the drawable to draw. Everything else is
     * asked of [MonochromeIconProvider], which finds the app's own flat artwork where it
     * has any; see [glyphOf]. This outranks the provider, because a glyph written for this
     * shell says what the program *is* where a themed icon only says who made it.
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
        // A fresh list is the one moment the art can have changed underneath the cache:
        // it is what an install, an uninstall, a chosen icon and a theme swap all end in.
        glyphs.clear()
        setItems(apps.sortedBy { it.name.lowercase() })
    }

    /**
     * The mark for one app, resolved once and kept.
     *
     * Resolving means asking the package manager for the app's icon and rasterising it to
     * measure - far too much to do on every bind of a list that recycles its rows under a
     * flicked finger. Emptied whenever the list is set again, which is what every change
     * to an app's artwork ends in. See [setApps].
     */
    private val glyphs = mutableMapOf<String, MonochromeIconProvider.Glyph?>()

    private fun glyphOf(app: AppInfo): MonochromeIconProvider.Glyph? {
        if (glyphs.containsKey(app.packageName)) return glyphs[app.packageName]
        val resolved = resolveGlyph(app)
        glyphs[app.packageName] = resolved
        return resolved
    }

    private fun resolveGlyph(app: AppInfo): MonochromeIconProvider.Glyph? {
        metroGlyph?.invoke(app)?.let { res ->
            val drawable = AppCompatResources.getDrawable(context, res) ?: return null
            return MonochromeIconProvider.Glyph.Monochrome(
                drawable, iconProvider.ratioFor("res:$res", drawable))
        }
        return iconProvider.glyphFor(app.packageName, app.icon)
    }

    /**
     * Puts one glyph on its accent square at [GLYPH_DP], wherever its ink happens to sit
     * inside the artwork it arrived in.
     *
     * Nothing about the source is taken on trust. Every source pads itself differently - a
     * themed layer keeps the adaptive-icon safe zone, a notification silhouette fills its
     * bounds, this shell's own glyphs cover about half of theirs - and some pad so heavily
     * that the mark is a speck in the middle of a mostly empty picture. Worse, the padding
     * is not always symmetrical, so the ink is not always in the middle of it. So the
     * measured ink box is what gets placed: its longer side is scaled to [GLYPH_DP] and its
     * centre put at the square's centre, which makes every mark in the list the same size
     * and on the same axis whatever it was drawn on.
     *
     * Placed by matrix rather than by padding, because a glyph that covers a seventh of its
     * canvas needs a canvas seven times the square to show at the right size, and no amount
     * of padding can give it one. What hangs over the edge is that artwork's own margin,
     * and the square clips it.
     */
    private fun placeGlyph(
        view: ImageView,
        drawable: android.graphics.drawable.Drawable,
        packageName: String
    ) {
        // Keyed by package, which is what the provider forgets by when an app's artwork
        // changes - and what this list resolves one glyph per, so the two stay in step.
        val ink = iconProvider.inkFor("ink:$packageName", drawable)
        val canvasW = drawable.intrinsicWidth.toFloat()
        val canvasH = drawable.intrinsicHeight.toFloat()
        // Artwork that drew nothing, or that will not say how large it is: an ImageView
        // ignores the matrix for a drawable with no intrinsic size, so there is nothing to
        // place and the plain fit is the only honest answer.
        if (ink == null || canvasW <= 0f || canvasH <= 0f) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val inkW = ink.width() * canvasW
        val inkH = ink.height() * canvasH
        val scale = dp(GLYPH_DP) / maxOf(inkW, inkH)
        val centre = dp(ICON_DP) / 2f
        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                centre - scale * (ink.left * canvasW + inkW / 2f),
                centre - scale * (ink.top * canvasH + inkH / 2f)
            )
        }
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
            // Flat artwork - this shell's own glyph, an app's themed monochrome layer, or
            // its notification silhouette - is drawn the way the phone drew the programs it
            // came with: white on a square of the accent. An app with none of those keeps
            // the icon it was installed with, unboxed, which is also what WP8.1 did with
            // art a developer had not drawn for a tile.
            when (val glyph = glyphOf(item)) {
                is MonochromeIconProvider.Glyph.Monochrome -> {
                    icon.setImageDrawable(glyph.drawable)
                    icon.imageTintList = ColorStateList.valueOf(palette.onAccent())
                    icon.setBackgroundColor(palette.accent)
                    placeGlyph(icon, glyph.drawable, item.packageName)
                }
                // The app's own icon, filling the slot rather than sitting on a square: it
                // is a picture, not a mark, and there is nothing for it to line up with.
                is MonochromeIconProvider.Glyph.FullColor -> {
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.setImageDrawable(glyph.drawable)
                    icon.imageTintList = null
                    icon.background = null
                }
                null -> {
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.setImageDrawable(null)
                    icon.imageTintList = null
                    icon.background = null
                }
            }
        }
    }

    private companion object {
        const val ROW_DP = 62

        /** The square a Metro app's glyph sits on, and the box every other icon fills. */
        const val ICON_DP = 42

        /**
         * How large the *visible* mark on an accent square is, whatever it was drawn on.
         *
         * Every mark is put at this size rather than at whatever its own padding happened
         * to give; see [placeGlyph]. Twenty-four of the square's forty-two: four more than
         * where this shell's own glyphs sat on their own, which was a mark with more square
         * around it than the square needed.
         */
        const val GLYPH_DP = 24
    }
}
