package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * A desktop folder, opened as a Metro page.
 *
 * Folders are a shell concept, not a program, so unlike Solitaire or Internet Explorer
 * they have no business appearing in a Vista window here - opening one stays inside the
 * phone UI. The page follows the WP8.1 header idiom: a back arrow and an oversized
 * lowercase title, with the contents below.
 *
 * The contents are a [StartScreenView], not a grid of their own. A folder is a Start
 * screen that happens to be nested, and the alternative - a second tile surface with its
 * own reduced set of gestures - would mean a tile behaving differently depending on which
 * screen it was sitting on.
 *
 * Real WP8.1 folders (the Live Folders of Update 1) expanded inline on Start rather than
 * pushing a page. A page is used here because these folders carry arbitrarily many icons
 * and inline expansion would push the rest of Start far off-screen.
 */
@SuppressLint("ViewConstructor")
class FolderPageView(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /** The folder's tiles. Carries the full editing behaviour of the Start screen. */
    val contents = StartScreenView(context, palette)

    /** Tapping the back arrow beside the title. */
    var onBack: (() -> Unit)? = null

    private val header = MetroPageHeader(context, palette)
    private val emptyLabel = TextView(context)

    init {
        isClickable = true

        emptyLabel.text = "this folder is empty"
        emptyLabel.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        emptyLabel.textSize = 15f
        emptyLabel.visibility = GONE

        // The message sits in the space the tiles would have filled, centred in it.
        // Centring it on the page instead measured the title band as part of the room it
        // had, which put it above the middle of the area it is actually describing - and
        // on a short page, close enough to the header to read as attached to it.
        val body = FrameLayout(context).apply {
            addView(contents, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(emptyLabel, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        header.onBack = { onBack?.invoke() }

        applyPalette(palette)
    }

    fun show(
        folderName: String,
        tiles: List<Tile>,
        notifications: (Tile) -> List<TileView.Line> = { emptyList() },
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        header.setTitle(folderName)
        contents.setTiles(
            tiles, liveWidget = { null }, tileColors = tileColors, glyphs = glyphs)
        contents.setNotifications(notifications)
        emptyLabel.visibility = if (tiles.isEmpty()) VISIBLE else GONE

        transition.playIn()
        header.playEntrance()
        contents.playEntrance()
    }

    fun setNotifications(lookup: (Tile) -> List<TileView.Line>) =
        contents.setNotifications(lookup)

    fun setFolderPreviews(lookup: (Tile) -> List<FolderPreviewView.Entry>) =
        contents.setFolderPreviews(lookup)

    fun hide() {
        contents.exitEditMode()
        transition.playOut()
    }

    /** The page turns in and out rather than appearing. See [MetroPageTransition]. */
    private val transition = MetroPageTransition(this)

    /** Open, and not in the middle of leaving. */
    fun isOnScreen(): Boolean = transition.isOnScreen

    /** True if the page consumed the back press. */
    fun handleBack(): Boolean = contents.handleBack()

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        header.applyPalette(p)
        contents.applyPalette(p)
        emptyLabel.setTextColor(p.foregroundSubtle)
    }
}

/**
 * The header every Metro page shares: a back arrow and an oversized lowercase title.
 *
 * WP8.1 pages that were pushed onto a stack carried this arrow beside their title, so the
 * way out was visible rather than something the user had to remember was on the key strip.
 */
@SuppressLint("ViewConstructor")
class MetroPageHeader(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null

    private val backArrow = ImageView(context)
    private val title = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(18), dp(20), dp(14))

        backArrow.setImageResource(R.drawable.wp81_nav_back)
        backArrow.scaleType = ImageView.ScaleType.FIT_CENTER
        backArrow.isClickable = true
        // Sized to stand level with the title rather than tucked beside it - the arrow is
        // the way out of the page, and at handle size it read as an afterthought. The view
        // is bigger than the glyph so the touch target stays generous.
        backArrow.setPadding(dp(6), dp(6), dp(6), dp(6))
        backArrow.setOnClickListener { onBack?.invoke() }
        TiltEffect.apply(backArrow)
        addView(backArrow, LayoutParams(dp(ARROW_VIEW_DP), dp(ARROW_VIEW_DP)).apply {
            // Nudged down to sit on the title's baseline rather than its box: Segoe Light
            // carries noticeable ascender space, so centring the two by their bounds
            // leaves the arrow riding high.
            topMargin = dp(ARROW_BASELINE_NUDGE_DP)
        })

        title.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        title.textSize = TITLE_SP
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.includeFontPadding = false
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })

        applyPalette(palette)
    }

    /** WP8.1 page titles are lowercase; it is one of the most recognisable things about it. */
    fun setTitle(text: String) {
        title.text = text.lowercase()
    }

    /**
     * A name the user typed, at the head of the page showing it - as they typed it.
     *
     * The lowercasing above is a rule about *this platform's own words*: "tabs", "notes",
     * "settings". A note called "AirCare" is not one of the platform's words, and a shell
     * that quietly renamed it to "aircare" wherever it was shown would be correcting the
     * user's typing on their behalf.
     */
    fun setName(text: String) {
        title.text = text
    }

    fun playEntrance() {
        translationY = dp(16).toFloat()
        alpha = 0f
        animate().translationY(0f).alpha(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        title.setTextColor(p.foreground)
        backArrow.imageTintList = ColorStateList.valueOf(p.foreground)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TITLE_SP = 34f

        /** Arrow view edge. Its glyph then matches the title's height once padding is off. */
        private const val ARROW_VIEW_DP = 46

        /** Drop needed to line the arrow up with the title's baseline. */
        private const val ARROW_BASELINE_NUDGE_DP = 5
    }
}
