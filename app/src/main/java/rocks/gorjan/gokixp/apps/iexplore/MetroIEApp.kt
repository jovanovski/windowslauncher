package rocks.gorjan.gokixp.apps.iexplore

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/** One open page, as it is written down between sessions. See MetroIEApp.saveTabs. */
internal data class SavedTab(val url: String, val title: String)

/**
 * One page this browser has been to. See MetroIEApp.recordVisit.
 *
 * The title is the only part that can change after the fact: a page is written down the
 * moment it finishes loading, and some of them do not say what they are called until
 * afterwards. See MetroIEApp.noteTitle.
 */
internal data class HistoryEntry(val url: String, var title: String, val visited: Long)

/**
 * Internet Explorer, as the phone had it.
 *
 * The desktop themes give the browser a window with a toolbar, a status bar and eight
 * buttons across the top. The phone gave it none of that: the page has the whole screen,
 * and everything you can do to it lives on one dark strip along the bottom - the tabs on
 * the left, the address in the middle, and a row of dots on the right that lifts the rest
 * of the commands into view.
 *
 * The strip is deliberately not palette-coloured. Every other page in this shell follows
 * the light/dark setting, but IE's app bar was the same near-black on every phone, because
 * it sits under an arbitrary web page and has to stay legible against whatever that page
 * happens to be. The one accent-free surface in the shell is the correct one.
 *
 * It shares its state with the desktop browser rather than keeping its own: the same
 * favourites, the same home page, the same last address. Switching themes changes what the
 * browser looks like, not what is in it.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MetroIEApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val onShowNotification: (String, String, (() -> Unit)?) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    /**
     * Back has run out of page in a tab another app handed over. See [handleBack].
     */
    private val onReturnToLinkCaller: () -> Unit = {}
) {

    /**
     * One open page.
     *
     * Every tab keeps its own WebView, which is what makes switching back to a tab a
     * return to the page as it was - its history, its scroll position, its half-filled
     * form - rather than a reload of the same address. All of them stay in the page
     * container; the ones that are not being looked at are simply hidden, so a background
     * tab goes on loading and is ready when it is reached.
     */
    private inner class Tab {
        val webView = WebView(webContext)
        var title: String = ""
        var url: String = ""

        /**
         * An address read back from a previous session that has not been loaded yet.
         *
         * Restoring nine tabs means nine WebViews, and fetching nine pages the moment the
         * launcher comes back would spend the phone's first few seconds on eight pages
         * nobody is looking at. A restored tab is a name and an address until it is
         * reached; the one being reached loads at once. See [activate].
         */
        var pending: String? = null

        /** The last picture of this page, for the tabs grid. Captured on the way out. */
        var thumbnail: Bitmap? = null

        var loading = false

        /** Set when the page could not be reached, so the error shows again on return. */
        var failedUrl: String? = null
        var failed = false

        /**
         * This page was opened by another app - a link followed in Reddit or a mail.
         *
         * The tab belongs to that app's errand rather than to the browser, and back walks
         * out of it the way back walks out of anything else that app opened: through the
         * page's own history, then out of the launcher entirely. Not written down with the
         * tab, because the app it would send the user to is gone by the next session.
         */
        var external = false
    }

    private lateinit var root: FrameLayout

    /** Holds every tab's WebView. Only the current one is visible. */
    private lateinit var pages: FrameLayout

    /**
     * What the pages are built with, rather than the window's own context.
     *
     * The grips either end of a text selection are drawn from the theme the WebView was
     * given, and every theme in this shell paints its controls black so that the desktop
     * chrome is not tinted - which had the browser dropping two black grips onto pages
     * that are themselves black, where there was nothing to see and nothing to take hold
     * of. The overlay replaces those three drawables and nothing else, and it is put on
     * the pages alone: the rest of the shell keeps the controls it was drawn with.
     */
    private val webContext =
        ContextThemeWrapper(context, R.style.ThemeOverlay_GokiXP_WP81_PageSelection)

    /** The commands a hold on the page puts up, over the page. See [onPagePressed]. */
    private lateinit var pressMenu: WP81ContextMenu

    /** Where the last touch landed in the page, so a held one opens its list there. */
    private var lastPressY = 0f

    /** Downloads this browser started and what they are called, until they land. */
    private val arriving = mutableMapOf<Long, String>()

    /** Listens for them landing. Built on the first download, and not before. */
    private var landings: BroadcastReceiver? = null

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null

    /** The strip along the bottom: the menu, when it is open, sitting on top of the row. */
    private lateinit var appBar: LinearLayout
    private lateinit var menuPanel: LinearLayout

    /** Holds [menuPanel], and stops a long favourites list running off the top edge. */
    private lateinit var menuScroller: ScrollView

    private lateinit var addressBar: EditText

    /**
     * Where the browser has been that looks like what is being typed, over the bar.
     *
     * Its own strip rather than the menu's: the two are never up at once - taking the
     * field closes the menu - but they are different things, one a list of commands the
     * user asked for and one a list of pages that appears as a side effect of typing,
     * and building them out of the same views made every keystroke rebuild the menu.
     */
    private lateinit var suggestionScroller: ScrollView
    private lateinit var suggestionPanel: LinearLayout

    /** The left button: how many pages are open, and the way to them. */
    private lateinit var tabsButton: ImageView

    /** Reload, or stop while a page is coming in. Lives in the address bar itself. */
    private lateinit var reloadButton: ImageView

    /** The blue line over the address bar. Scaled from the left rather than resized. */
    private lateinit var progressFill: View

    /** Swallows taps on the page while the menu is open, so the first one only closes it. */
    private lateinit var menuCatcher: View

    /** Shown in place of the page when a page cannot be reached at all. */
    private lateinit var errorPage: LinearLayout
    private lateinit var errorDetail: TextView

    /** What has been fetched, and what still is. Covers the browser like the tabs page. */
    private lateinit var downloadsPage: FrameLayout
    private lateinit var downloadsList: LinearLayout
    private lateinit var downloadsScroller: ScrollView

    /** The tabs page, which covers the browser entirely - app bar included. */
    private lateinit var tabsPage: FrameLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabsScroller: ScrollView

    private var homepage: String = DEFAULT_HOMEPAGE
    private val favourites = mutableListOf<Favourite>()

    /**
     * Everywhere this browser has been, newest first.
     *
     * Kept for the address bar rather than as a record: a phone browser has no history
     * page, and what the list is for is finishing an address somebody has started typing.
     * Which is also why it is short - see MAX_HISTORY - and why the menu can empty it.
     */
    private val history = mutableListOf<HistoryEntry>()

    /** What the address bar should say once the user stops editing it. */
    private var currentUrl: String = ""

    /**
     * Whether sites are being asked for the version they would send a computer.
     *
     * One setting for the whole browser rather than one per tab, which is how the phone
     * had it: IE's settings offered "website preference - mobile version or desktop
     * version" and meant it about the browser. A page that has to be asked again is asked
     * again on every tab at once, so switching tabs never switches the answer.
     */
    private var desktopMode = false

    /**
     * What the WebView calls itself when it is left alone.
     *
     * Read off the first page built rather than asked for up front: it is a property of
     * the WebView, and building one to ask costs a WebView. Kept because turning desktop
     * mode back off has to put back exactly what was there, not an approximation of it.
     */
    private var mobileAgent: String? = null

    fun createView(initialUrl: String? = null, fromAnotherApp: Boolean = false): View {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        homepage = prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        favourites.clear()
        favourites.addAll(loadFavourites())
        history.clear()
        history.addAll(loadHistory())

        root = FrameLayout(context).apply {
            setBackgroundColor(palette.background)
            // Takes the initial focus itself. Otherwise the address field - the only
            // focusable thing in here - claims it as the window opens, and the browser
            // arrives with a caret blinking in the bar and the page behind it untouched.
            isFocusableInTouchMode = true
        }

        // The page gets everything above the strip. The strip is laid over the top of it
        // rather than beside it so the menu can grow upward over the page instead of
        // squeezing it - which would reflow the whole document every time it opened.
        pages = FrameLayout(context)
        root.addView(pages, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            bottomMargin = dp(BAR_DP)
        })

        errorPage = buildErrorPage()
        root.addView(errorPage, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            bottomMargin = dp(BAR_DP)
        })

        menuCatcher = View(context).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeMenu() }
        }
        root.addView(menuCatcher, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            bottomMargin = dp(BAR_DP)
        })

        appBar = buildAppBar()
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        tabsPage = buildTabsPage()
        root.addView(tabsPage, FrameLayout.LayoutParams(MATCH, MATCH))

        downloadsPage = buildDownloadsPage()
        root.addView(downloadsPage, FrameLayout.LayoutParams(MATCH, MATCH))

        // Over everything, including the strip: it dims the whole browser the way a hold
        // dims the whole screen everywhere else in the shell.
        pressMenu = WP81ContextMenu(context, palette)
        root.addView(pressMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        // What was open last time. A browser on a phone is a place rather than a document
        // - the pages you left in it are still yours when you come back - and the launcher
        // is restarted often enough (a theme change, a rotation, the system reclaiming it)
        // that losing them to that would make tabs useless.
        val restored = loadTabs()
        if (restored.isEmpty()) {
            val lastUrl = prefs.getString(InternetExplorerApp.KEY_LAST_URL, null)
            val opened = openTab(initialUrl ?: lastUrl ?: homepage)
            if (initialUrl != null) opened?.external = fromAnotherApp
        } else {
            for (saved in restored) restoreTab(saved)
            val active = prefs.getInt(KEY_ACTIVE_TAB, 0).coerceIn(0, tabs.size - 1)
            activate(tabs[active])
            // An address that arrived with the window is a new thing to read, and goes in
            // its own tab on top of what was already there.
            if (initialUrl != null) openTab(initialUrl)?.external = fromAnotherApp
        }
        root.requestFocus()
        return root
    }

    // ---------------------------------------------------------------- tabs

    /**
     * Opens a page in a tab of its own and goes to it.
     *
     * Nine is the ceiling, which is what the button can say: there is a card for one
     * through nine and nothing past it, so a tenth page would be open without the bar
     * being able to admit it. IE Mobile stopped at six for the same kind of reason, and
     * there has to be a limit somewhere regardless - every tab is a live WebView, and a
     * launcher that runs out of memory takes the home screen down with it.
     */
    private fun openTab(url: String): Tab? {
        if (tabs.size >= MAX_TABS) {
            notify("Internet Explorer", "Nine pages is as many as it will hold")
            return null
        }
        val tab = Tab()
        configure(tab)
        tabs.add(tab)
        pages.addView(tab.webView, FrameLayout.LayoutParams(MATCH, MATCH))
        activate(tab)
        load(tab, url)
        saveTabs()
        return tab
    }

    /** Puts back a tab read from the last session, without fetching it. See [Tab.pending]. */
    private fun restoreTab(saved: SavedTab) {
        val tab = Tab()
        configure(tab)
        tab.url = saved.url
        tab.title = saved.title
        tab.pending = saved.url
        tabs.add(tab)
        pages.addView(tab.webView, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Brings [tab] to the front and points the app bar at it. */
    private fun activate(tab: Tab) {
        if (current === tab) return
        current?.let {
            // Photographed on the way out, while it is still on screen at full size: a
            // hidden view has nothing to draw, so a thumbnail taken after the switch is a
            // white rectangle.
            capture(it)
            it.webView.visibility = View.GONE
        }
        current = tab
        tab.webView.visibility = View.VISIBLE
        showAddress(tab.url)
        showError(if (tab.failed) tab.failedUrl else null)
        paintLoading(tab)
        onUpdateWindowTitle(tab.title.ifBlank { "Internet Explorer" })
        paintTabsButton()
        // Reached for the first time since the launcher came back: now it is worth
        // fetching, and not a moment before.
        tab.pending?.let { url ->
            tab.pending = null
            load(tab, url)
        }
        saveTabs()
    }

    /**
     * Closes [tab], and with the last one closes nothing: a browser with no pages in it is
     * a blank screen with an address bar, so the last close starts a new page instead.
     */
    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.remove(tab)
        pages.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (current === tab) {
            current = null
            // The one before it, which is where the eye already was.
            val next = tabs.getOrNull(index - 1) ?: tabs.firstOrNull()
            if (next != null) activate(next) else openTab(homepage)
        }
        paintTabsButton()
        saveTabs()
    }

    /** The mark on the left button: one card per open page, and nine for nine or more. */
    private fun paintTabsButton() {
        val shown = tabs.size.coerceIn(1, CARD_ICONS)
        tabsButton.setImageDrawable(SvgIcon.fromAsset(context, "$ICON_DIR/appbar.card.$shown.svg"))
    }

    /** Draws the page as it stands, small enough to keep one of per tab. */
    private fun capture(tab: Tab) {
        val width = tab.webView.width
        val height = tab.webView.height
        if (width <= 0 || height <= 0) return
        try {
            val scale = dp(THUMB_WIDTH_DP).toFloat() / width
            val bitmap = Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            tab.webView.draw(canvas)
            tab.thumbnail = bitmap
        } catch (e: Exception) {
            // A page too large to photograph is not a page that should stop working.
            Log.w(TAG, "Could not capture a thumbnail", e)
        }
    }

    // ---------------------------------------------------------------- the tabs page

    /**
     * Every open page at once, the way the phone showed them.
     *
     * IE Mobile had no tab strip - there is no room for one, and nobody can hit a tab the
     * width of a word on a phone. Tabs were somewhere you *went*: a page of screenshots,
     * two across, each with its title under it and a cross in the corner, and one button
     * at the foot of it for a new one. It is the same shape as this shell's own folder and
     * settings pages, which is why it belongs here.
     */
    private fun buildTabsPage(): FrameLayout {
        val page = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(palette.background)
            // Nothing behind it is reachable while it is up.
            isClickable = true
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val header = MetroPageHeader(context, palette)
        header.setTitle("tabs")
        header.onBack = { closeTabs() }
        column.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        tabsGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(20))
        }
        tabsScroller = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(tabsGrid, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(tabsScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Its own app bar, in the same near-black as the browser's: this page has one
        // command, and it is the same kind of thing as the browser's own strip.
        //
        // Centred and unlabelled, because it is the only thing on the bar. A lone button
        // in the left corner reads as the first of a row that never arrives, and a plus
        // says "another one" without help.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BAR_COLOUR)
            isClickable = true
        }
        bar.addView(circleButton(NEW_ICON) {
            closeTabs()
            openTab(homepage)
        }, LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        column.addView(bar, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))

        page.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    private fun openTabs() {
        closeMenu()
        addressBar.clearFocus()
        hideKeyboard()
        current?.let { capture(it) }
        buildTabsGrid()
        tabsPage.visibility = View.VISIBLE
        tabsScroller.scrollTo(0, 0)
        // Turned in the way the shell's own pages turn, because that is what it is.
        // Deferred a frame: a view that has been GONE has no height yet, and a turn
        // measured against one pivots around the wrong place.
        tabsPage.post { MetroPageTransition(tabsPage).playIn() }
    }

    private fun closeTabs() {
        if (tabsPage.visibility != View.VISIBLE) return
        tabsPage.visibility = View.GONE
        tabsGrid.removeAllViews()
    }

    /** Two across, in the order they were opened. */
    private fun buildTabsGrid() {
        tabsGrid.removeAllViews()
        var row: LinearLayout? = null
        tabs.forEachIndexed { index, tab ->
            if (index % TABS_PER_ROW == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                tabsGrid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            row?.addView(tabCell(tab), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = if (index % TABS_PER_ROW == 0) 0 else dp(8)
                bottomMargin = dp(12)
            })
        }
        // The odd tab out would otherwise stretch across the whole width, twice the size of
        // every other card and looking like the one that matters.
        if (tabs.size % TABS_PER_ROW != 0) {
            row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun tabCell(tab: Tab): View {
        val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val shot = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                closeTabs()
                activate(tab)
            }
            TiltEffect.apply(this)
            // The page it is standing in for is white until it says otherwise, and an
            // empty frame on a black background reads as a hole rather than a page.
            setBackgroundColor(if (tab.thumbnail != null) Color.WHITE else palette.inactive)
        }

        val image = ImageView(context).apply {
            // Scaled by hand rather than by a scaleType: the card wants the *top* of the
            // page at the card's own width, and none of the stock ones do that. FIT_START
            // fits the whole screenshot and leaves a tall page as a thin strip down the
            // left; CENTER_CROP fills the card with the middle of the page, which is the
            // one part of it nobody recognises.
            scaleType = ImageView.ScaleType.MATRIX
            tab.thumbnail?.let { setImageBitmap(it) }
        }
        image.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val bitmap = tab.thumbnail ?: return@addOnLayoutChangeListener
            val scale = (right - left).toFloat() / bitmap.width
            (view as ImageView).imageMatrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
            }
        }
        shot.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))

        // The one open now, marked. A border rather than a tint: the card is a photograph,
        // and colouring it over would make the page itself look wrong.
        if (tab === current) {
            shot.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), palette.accent)
                }
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // On the corner of the card, half on and half off, like the tile handles: it sits
        // over an arbitrary screenshot and a ringed disc is the only thing that reads
        // against all of them.
        val close = ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_handle_circle)
            setImageDrawable(SvgIcon.fromAsset(context, REMOVE_ICON))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeTab(tab)
                buildTabsGrid()
            }
            TiltEffect.apply(this)
        }
        shot.addView(close, FrameLayout.LayoutParams(
            dp(CLOSE_DP), dp(CLOSE_DP), Gravity.TOP or Gravity.END))

        cell.addView(shot, LinearLayout.LayoutParams(MATCH, dp(THUMB_HEIGHT_DP)))
        cell.addView(TextView(context).apply {
            text = tab.title.ifBlank { hostOf(tab.url) }
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foreground)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        return cell
    }

    // ---------------------------------------------------------------- the downloads page

    /**
     * Everything the browser has fetched, and everything it is still fetching.
     *
     * A phone browser saves a file and then loses it: the system's own notification is in
     * a shade nobody opens, the band that replaces it here is gone in seconds, and after
     * that a downloaded file exists only in a folder the user has to go and find with
     * another program. IE Mobile answered that with a page of its own under the dots, and
     * so does this - the same shape as the tabs page, because it is the same kind of thing:
     * a list of what the browser is holding, reached from the menu and left with back.
     *
     * Read from the download manager rather than kept here. It already knows what is
     * running, what finished and how far along the rest is, and it goes on knowing across
     * a restart - which a list this browser wrote down itself would not.
     */
    private fun buildDownloadsPage(): FrameLayout {
        val page = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(palette.background)
            // Nothing behind it is reachable while it is up.
            isClickable = true
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val header = MetroPageHeader(context, palette)
        header.setTitle("downloads")
        header.onBack = { closeDownloads() }
        column.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        downloadsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(20))
        }
        downloadsScroller = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(downloadsList, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(downloadsScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        page.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /**
     * The turn this page arrives and leaves on.
     *
     * One per page rather than one per opening: it counts its own turns, which is what
     * stops an exit that finishes late from hiding a page that has since been opened
     * again. Built on the first opening, by which time the page itself exists.
     */
    private val downloadsTurn by lazy { MetroPageTransition(downloadsPage) }

    private fun openDownloads() {
        closeMenu()
        addressBar.clearFocus()
        hideKeyboard()
        paintDownloads()
        downloadsPage.visibility = View.VISIBLE
        downloadsScroller.scrollTo(0, 0)
        // Turned in the way the shell's own pages turn. Deferred a frame, like the tabs
        // page: a view that has been GONE has no height to pivot around yet.
        downloadsPage.post { downloadsTurn.playIn() }
    }

    /**
     * Turns the page back out, rather than taking it away between two frames.
     *
     * A page that arrives on the turnstile and leaves by disappearing is half a
     * transition, and the half that is missing is the one that says where it went.
     *
     * The list is emptied at the end of it and not before: the page is still on screen for
     * as long as it is leaving, and clearing it first would turn a blank rectangle out.
     */
    private fun closeDownloads() {
        if (!downloadsTurn.isOnScreen) return
        root.removeCallbacks(downloadsTick)
        downloadsTurn.playOut { downloadsList.removeAllViews() }
    }

    /**
     * Draws the list again while anything is still coming in.
     *
     * Only while the page is up and only while something is unfinished: a browser that
     * wakes every second to redraw a list of files that all arrived yesterday is a browser
     * keeping the screen busy for nothing. See [paintDownloads], which schedules this.
     */
    private val downloadsTick = Runnable {
        if (downloadsTurn.isOnScreen) paintDownloads()
    }

    private fun paintDownloads() {
        val items = readDownloads()
        downloadsList.removeAllViews()
        if (items.isEmpty()) {
            downloadsList.addView(TextView(context).apply {
                text = "nothing has been downloaded yet"
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
                textSize = 15f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(20), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            for (item in items) downloadsList.addView(downloadRow(item))
        }

        root.removeCallbacks(downloadsTick)
        if (items.any { it.unfinished }) root.postDelayed(downloadsTick, DOWNLOADS_TICK_MS)
    }

    /** One file: what it is called, how it is getting on, and how far along it is. */
    private fun downloadRow(item: Download): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                openDownload(item)
            }
            TiltEffect.apply(this)
        }

        row.addView(TextView(context).apply {
            text = item.name
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 17f
            setTextColor(palette.foreground)
            maxLines = 1
            // From the middle: the tail of a file name is its extension, which is half of
            // what says what the file is.
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        row.addView(TextView(context).apply {
            text = describe(item)
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        // The line, for a file whose size is known and which has not all arrived. Without
        // a total there is nothing to be a fraction of, and a full bar over a download
        // that is a tenth done would be a lie; the line is left off and the text says how
        // much has come in instead.
        if (item.unfinished && item.total > 0) {
            val track = FrameLayout(context)
            track.addView(View(context).apply {
                setBackgroundColor(palette.accent)
                pivotX = 0f
                scaleX = (item.soFar.toFloat() / item.total).coerceIn(0f, 1f)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            row.addView(track, LinearLayout.LayoutParams(MATCH, dp(PROGRESS_DP)).apply {
                topMargin = dp(10)
            })
        }
        return row
    }

    private fun describe(item: Download): String = when (item.status) {
        DownloadManager.STATUS_PENDING -> "waiting to start"
        DownloadManager.STATUS_PAUSED -> "paused"
        DownloadManager.STATUS_RUNNING ->
            if (item.total > 0) "${size(item.soFar)} of ${size(item.total)}"
            else "${size(item.soFar)} so far"
        DownloadManager.STATUS_SUCCESSFUL -> size(maxOf(item.total, item.soFar))
        else -> "did not finish"
    }

    private fun size(bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

    private fun openDownload(item: Download) {
        if (item.unfinished) {
            notify("Internet Explorer", "${item.name} has not finished arriving")
            return
        }
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            notify("Internet Explorer", "${item.name} did not finish downloading")
            return
        }
        val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = downloads.getUriForDownloadedFile(item.id) ?: return
        open(file, item.mime)
    }

    /**
     * Why a download that started did not arrive, in words rather than in a number.
     *
     * The download manager keeps a reason on the row: an HTTP status when the server
     * answered, and one of its own codes when nothing came back at all. Somebody who has
     * just watched a file fail is owed the difference between a site that said no and a
     * phone with no room left, because only one of those is worth trying again.
     */
    private fun whyNot(downloads: DownloadManager, id: Long): String {
        val reason = try {
            downloads.query(DownloadManager.Query().setFilterById(id))?.use { row ->
                val column = row.getColumnIndex(DownloadManager.COLUMN_REASON)
                if (column >= 0 && row.moveToFirst()) row.getInt(column) else 0
            } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not read why download $id failed", e)
            0
        }
        Log.w(TAG, "Download $id failed with reason $reason")
        return when (reason) {
            401, 403 -> "could not be downloaded - the site would not hand it over"
            404, 410 -> "is not there any more"
            in 500..599 -> "could not be downloaded - the site is having trouble"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "would not fit on this phone"
            DownloadManager.ERROR_DEVICE_NOT_FOUND,
            DownloadManager.ERROR_FILE_ERROR,
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "could not be saved"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_CANNOT_RESUME -> "did not finish downloading"
            else -> "could not be downloaded"
        }
    }

    /** One row of the download manager's own list. */
    private data class Download(
        val id: Long,
        val name: String,
        val status: Int,
        val soFar: Long,
        val total: Long,
        val mime: String?,
        /** When it last moved, which is what puts the newest at the top. */
        val at: Long
    ) {
        val unfinished: Boolean
            get() = status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PAUSED
    }

    /**
     * What the download manager is holding for this app, newest first.
     *
     * It answers per app, so this is the browser's own list and not the phone's. The
     * order is put on afterwards rather than asked for: the query's own sort is not part
     * of the published API.
     */
    private fun readDownloads(): List<Download> {
        val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            downloads.query(DownloadManager.Query())?.use { row ->
                val found = mutableListOf<Download>()
                val id = row.getColumnIndex(DownloadManager.COLUMN_ID)
                val title = row.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val status = row.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val soFar = row.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = row.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mime = row.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val uri = row.getColumnIndex(DownloadManager.COLUMN_URI)
                val at = row.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (row.moveToNext()) {
                    found.add(Download(
                        id = row.getLong(id),
                        // The title is the file name this browser set when it asked for the
                        // file; anything else in the list falls back to its address.
                        name = row.getString(title)?.takeIf { it.isNotBlank() }
                            ?: row.getString(uri).orEmpty(),
                        status = row.getInt(status),
                        soFar = row.getLong(soFar),
                        total = row.getLong(total),
                        mime = row.getString(mime),
                        at = row.getLong(at)
                    ))
                }
                found.sortedByDescending { it.at }.take(MAX_DOWNLOADS)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the downloads", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------- the page

    private fun configure(tab: Tab) {
        val webView = tab.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Both already what a desktop page needs: a page with no viewport of its own is
        // laid out at its full width and then zoomed out to fit, rather than cropped.
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        applyDesktopMode(webView)
        // Pinch to zoom, without the pair of grey +/- buttons that come with it by
        // default and that no phone browser has had since about 2011.
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // White rather than the palette's background: a page that has not painted yet is
        // about to be white, and flashing black in between is worse than being early.
        webView.setBackgroundColor(Color.WHITE)
        webView.visibility = View.GONE

        // Read on the way past - the page still gets every touch - so that a list opened
        // by holding a link appears at the link rather than in the middle of the screen.
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) lastPressY = event.y
            false
        }
        webView.setOnLongClickListener { onPagePressed(tab) }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleScheme(tab, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                if (url != null) handleScheme(tab, url) else false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.failed = false
                tab.loading = true
                if (url != null) tab.url = url
                if (tab === current) {
                    showError(null)
                    paintLoading(tab)
                    if (url != null) showAddress(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.loading = false
                if (url != null) tab.url = url
                tab.title = view?.title?.takeIf { it.isNotBlank() }.orEmpty()
                // A page that arrived. One that did not is not somewhere the user has
                // been, and offering it back to them later would be offering an error.
                if (url != null && !tab.failed) recordVisit(url, tab.title)
                if (tab === current) {
                    paintLoading(tab)
                    if (url != null) {
                        showAddress(url)
                        saveLastUrl(url)
                    }
                    onUpdateWindowTitle(tab.title.ifBlank { "Internet Explorer" })
                }
                // Where this tab is now, so a restart puts it back here rather than on
                // whatever it was opened at.
                saveTabs()
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only the page itself. An image or a tracker that fails to load is not a
                // page that could not be reached, and covering the article over because
                // one of its ads timed out is worse than the ad being missing.
                if (request?.isForMainFrame != true) return
                tab.loading = false
                tab.failed = true
                tab.failedUrl = request.url?.toString()
                if (tab === current) {
                    paintLoading(tab)
                    showError(tab.failedUrl)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab === current) setProgress(newProgress / 100f)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title?.takeIf { it.isNotBlank() }.orEmpty()
                noteTitle(tab.url, tab.title)
                if (tab === current) {
                    onUpdateWindowTitle(tab.title.ifBlank { "Internet Explorer" })
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            download(url, Caller(tab.url, userAgent), contentDisposition, mimetype)
        }
    }

    /**
     * Hands anything that is not a web page to whatever app owns it.
     *
     * A `tel:` or a `mailto:` in a page is a request for the dialer or the mail app, and a
     * WebView asked to load one shows an error instead. `intent://` links carry their own
     * fallback address for exactly this case, so an app link on a phone with no app still
     * lands on the web page it was standing in for.
     */
    private fun handleScheme(tab: Tab, url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false

        if (url.startsWith("intent://")) {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                } else {
                    intent.getStringExtra("browser_fallback_url")?.let { load(tab, it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not follow $url", e)
            }
            return true
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                notify("Internet Explorer", "Nothing on this phone opens that link")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $url", e)
        }
        return true
    }

    /** Asks the download manager for a file. See [asThePageAsked]. */
    private fun download(
        url: String, from: Caller, contentDisposition: String?, mimetype: String?
    ) {
        // blob: and data: are the page's own memory rather than an address, and the
        // download manager has no way to reach into this process and read them.
        if (!URLUtil.isNetworkUrl(url)) {
            Log.w(TAG, "Nothing to fetch for $url")
            notify("Internet Explorer", "That file cannot be saved from here")
            return
        }
        val fileName = downloadFileName(url, contentDisposition, mimetype)
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading from Internet Explorer")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType(mimetype)
                .asThePageAsked(url, from.agent, from.referer)
            val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            watchDownloads()
            val id = downloads.enqueue(request)
            arriving[id] = fileName
            // Where it is being fetched from, to go with the reason if it fails. Without
            // the query, which on a share link is the part that is the key to the file.
            val at = Uri.parse(url)
            Log.d(TAG, "Download $id is $fileName from ${at.host}${at.path}")
            notify("Downloading", fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Could not download $url", e)
            notify("Internet Explorer", "That file could not be downloaded")
        }
    }

    /**
     * Says so when a download lands, and offers to open it.
     *
     * The download manager puts up a notification of its own, in the shade, behind
     * whatever the user is looking at - which on a phone shell that has its own way of
     * saying things is somewhere nobody looks. A file that has finished arriving is
     * announced the way everything else in here is announced, on the band, and the band is
     * the thing that opens it: a saved file the user has to go and find is a file they
     * asked for and did not get.
     */
    private fun watchDownloads() {
        if (landings != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
                // Other apps' downloads land on this broadcast too. Only ours are named.
                val name = arriving.remove(id) ?: return
                val downloads =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val file = downloads.getUriForDownloadedFile(id)
                if (file == null) {
                    notify("Internet Explorer", "$name ${whyNot(downloads, id)}")
                } else {
                    val mime = downloads.getMimeTypeForDownloadedFile(id)
                    notify("Saved", "$name is in Downloads") { open(file, mime) }
                }
            }
        }
        // A system broadcast, so the receiver has to be reachable from outside the app.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        landings = receiver
    }

    /** Hands a saved file to whichever app on the phone reads that kind of thing. */
    private fun open(file: Uri, mime: String?) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            notify("Internet Explorer", "Nothing on this phone opens that")
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $file", e)
        }
    }

    // ---------------------------------------------------------------- a press held

    /**
     * What holding a finger on the page offers, which is decided by what is under it.
     *
     * A hold on a link or on a picture is a question about that thing rather than about
     * the words around it, so it puts up the shell's own command list. A hold on anything
     * else is not answered here at all: handing the press back to the WebView leaves it
     * doing what it always did, which is to select the text under the finger.
     *
     * A picture inside a link is both, and offers both - the link's commands first,
     * because something that goes somewhere is the likelier errand. The address it goes to
     * is the one thing the hit test will not say, and has to be asked for; it arrives on
     * the handler a message later. See [linkedImageHandler].
     */
    private fun onPagePressed(tab: Tab): Boolean {
        val target = tab.webView.hitTestResult.extra
        if (target.isNullOrBlank()) return false
        when (tab.webView.hitTestResult.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                showPressMenu(hostOf(target), linkItems(target))
            WebView.HitTestResult.IMAGE_TYPE ->
                showPressMenu(hostOf(target), imageItems(target))
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ->
                tab.webView.requestFocusNodeHref(linkedImageHandler(target).obtainMessage())
            else -> return false
        }
        return true
    }

    /** Takes the address a held picture links to, and puts up both sets of commands. */
    private fun linkedImageHandler(image: String) = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val link = msg.data?.getString("url")?.takeIf { it.isNotBlank() }
            showPressMenu(
                hostOf(link ?: image),
                if (link == null) imageItems(image) else linkItems(link) + imageItems(image)
            )
        }
    }

    private fun linkItems(url: String): List<WP81ContextMenu.Item> = listOf(
        WP81ContextMenu.Item("open link") { current?.let { load(it, url) } },
        // And go to it. A page opened behind the one it was opened from is a page the user
        // has to go to the tabs and find, which is not what asking to open it meant.
        WP81ContextMenu.Item("open in new tab") { openTab(url) },
        WP81ContextMenu.Item("share") { shareLink(url, url) }
    )

    private fun imageItems(src: String): List<WP81ContextMenu.Item> = listOf(
        WP81ContextMenu.Item("save image") { saveImage(src) },
        WP81ContextMenu.Item("share image") { shareImage(src) }
    )

    /**
     * Puts [items] up over the page, at the height the press was.
     *
     * The app bar's own menu and the address field go first: the list belongs to the thing
     * under the finger, and two menus at once is not something this shell does.
     *
     * No buzz of its own. The page claims the press through the view's long click, and the
     * framework gives the shell's tick as it does; a second one by hand is what makes a
     * hold feel like two knocks. See wp81.Haptics.
     */
    private fun showPressMenu(title: String, items: List<WP81ContextMenu.Item>) {
        closeMenu()
        addressBar.clearFocus()
        hideKeyboard()
        pressMenu.show(title, items, lastPressY)
    }

    // ---------------------------------------------------------------- pictures

    /** A picture that has been brought down to the phone, ready to be handed on. */
    private data class FetchedImage(val file: File, val name: String, val mime: String)

    /**
     * The page a fetch is being made from.
     *
     * Read on the main thread and carried to whatever does the fetching - a thread, or
     * the download manager in another process - neither of which can go looking at
     * [current] itself. Both go on the request: a file asked for with neither comes back
     * from a good many sites as a refusal or a placeholder, because what they are checking
     * is that it was asked for by the page it belongs to.
     */
    private data class Caller(val referer: String?, val agent: String?)

    private fun caller() = Caller(current?.url, current?.webView?.settings?.userAgentString)

    /**
     * Saves the picture at [url] the way the browser saves everything else.
     *
     * Through the download manager and into Downloads, alongside the files a page hands
     * over: one folder rather than two, one list to look in, and the same band announcing
     * it when it lands. See [download] and [watchDownloads].
     */
    private fun saveImage(url: String) {
        if (!url.startsWith("data:")) {
            download(url, caller(), null, guessMime(url))
            return
        }
        // A picture written into the page itself is already on the phone, and there is no
        // address for the download manager to go and fetch. It is decoded and written into
        // the same folder by hand.
        val from = caller()
        Thread {
            val image = fetchImage(url, from)
            val saved = image?.let { saveToDownloads(it) }
            image?.file?.delete()
            onMain {
                if (saved == null || image == null) {
                    notify("Internet Explorer", "That picture could not be saved")
                } else {
                    notify("Saved", "${image.name} is in Downloads") {
                        open(saved, image.mime)
                    }
                }
            }
        }.start()
    }

    /**
     * Hands the picture itself to whatever the phone shares with, rather than a link to it.
     *
     * Which means fetching it first: the app on the other end may have no network, no
     * account for the site it came from, or no interest in unwrapping an address into a
     * picture. Sharing a picture should put the picture in the message.
     */
    private fun shareImage(url: String) {
        val from = caller()
        Thread {
            val image = fetchImage(url, from)
            onMain {
                if (image == null) {
                    notify("Internet Explorer", "That picture could not be shared")
                } else {
                    sendImage(image)
                }
            }
        }.start()
    }

    /**
     * Brings the picture at [url] down into the cache. Off the main thread; null if it
     * could not be had.
     */
    private fun fetchImage(url: String, from: Caller): FetchedImage? = try {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // Last time's, which has long since been handed over. These are copies of
        // something the web still has, and a folder of them that only ever grows is the
        // one thing they must not become.
        dir.listFiles()?.forEach { it.delete() }
        if (url.startsWith("data:")) decodeImage(url, dir) else downloadImage(url, dir, from)
    } catch (e: Exception) {
        Log.w(TAG, "Could not fetch the picture at $url", e)
        null
    }

    private fun downloadImage(url: String, dir: File, from: Caller): FetchedImage? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            from.agent?.let { setRequestProperty("User-Agent", it) }
            from.referer?.let { setRequestProperty("Referer", it) }
        }
        try {
            if (connection.responseCode !in 200..299) return null
            // What the server says it is, unless it will not say or says something that is
            // not a picture at all - an error page served with a 200, most often.
            val mime = connection.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: guessMime(url)
            val name = URLUtil.guessFileName(
                url, connection.getHeaderField("Content-Disposition"), mime)
            val file = File(dir, name)
            connection.inputStream.use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            return FetchedImage(file, name, mime)
        } finally {
            connection.disconnect()
        }
    }

    /** `data:image/png;base64,...` - the picture is written into the address itself. */
    private fun decodeImage(url: String, dir: File): FetchedImage? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring("data:".length, comma)
        val mime = header.substringBefore(';').takeIf { it.startsWith("image/") } ?: return null
        val body = url.substring(comma + 1)
        val bytes = if (header.contains("base64", ignoreCase = true)) {
            Base64.decode(body, Base64.DEFAULT)
        } else {
            Uri.decode(body).toByteArray()
        }
        val name = URLUtil.guessFileName(url, null, mime)
        val file = File(dir, name).apply { writeBytes(bytes) }
        return FetchedImage(file, name, mime)
    }

    /** What kind of picture this is when the server would not say. From the address's tail. */
    private fun guessMime(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
    }

    /**
     * Copies [image] into Downloads.
     *
     * Written as pending and only then published, so nothing goes looking at a file that
     * is still arriving. Off the main thread, like the fetch that produced it.
     */
    private fun saveToDownloads(image: FetchedImage): Uri? = try {
        val resolver = context.contentResolver
        val record = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, image.name)
            put(MediaStore.Downloads.MIME_TYPE, image.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, record)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { sink ->
                image.file.inputStream().use { it.copyTo(sink) }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        }
        uri
    } catch (e: Exception) {
        Log.e(TAG, "Could not save ${image.name}", e)
        null
    }

    private fun sendImage(image: FetchedImage) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", image.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = image.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                // The clip data is what carries the grant to whichever app is picked; the
                // flag on the chooser is what lets the chooser itself read the picture,
                // which is how it comes to show a thumbnail of what is about to be sent.
                clipData = ClipData.newRawUri(image.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Could not share ${image.name}", e)
            notify("Internet Explorer", "That picture could not be shared")
        }
    }

    /**
     * Back onto the main thread, if there is still a browser to come back to.
     *
     * A fetch outlives the window that started it - somebody can close the browser while a
     * picture is on its way - and what is waiting on the other side of this draws views
     * and puts up notifications.
     */
    private fun onMain(action: () -> Unit) {
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    // ---------------------------------------------------------------- the app bar

    private fun buildAppBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_COLOUR)
            // Its own taps stop here rather than reaching the page underneath it.
            isClickable = true
        }

        menuPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        menuScroller = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // The bar grows upward, and nothing stops it growing past the top of the
                // screen: a long favourites list would take its own first entries off the
                // top edge with it. Capped at the page, and scrolled beyond that.
                val cap = (root.height - dp(BAR_DP) - dp(24)).coerceAtLeast(dp(200))
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            visibility = View.GONE
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        menuScroller.addView(menuPanel, FrameLayout.LayoutParams(MATCH, WRAP))
        bar.addView(menuScroller, LinearLayout.LayoutParams(MATCH, WRAP))

        bar.addView(buildSuggestions(), LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }

        // The left button is the tabs button, as it was on the phone once IE could hold
        // more than one page: it both says how many are open and is the way to them.
        // Reload went where the phone put it when that button was spent - in the menu
        // under the dots. See buildMenu.
        tabsButton = circleButton(null) { openTabs() }
        paintTabsButton()
        row.addView(tabsButton, LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))

        row.addView(buildAddressColumn(), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })

        row.addView(buildEllipsis(), LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))

        bar.addView(row, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))
        return bar
    }

    /**
     * The address, with the loading line sitting directly on top of it.
     *
     * The line keeps its height whether or not anything is loading. Letting it collapse
     * moved the address bar up and down by three pixels on every navigation, which is the
     * sort of thing nobody can name but everybody sees.
     */
    private fun buildAddressColumn(): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val track = FrameLayout(context)
        progressFill = View(context).apply {
            setBackgroundColor(PROGRESS_COLOUR)
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(progressFill, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(track, LinearLayout.LayoutParams(MATCH, dp(PROGRESS_DP)))

        addressBar = EditText(context).apply {
            hint = "search or enter web address"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 14f
            isSingleLine = true
            // Room on the right for the button that sits over this field, so a long
            // address runs out of room before it runs under the mark rather than after.
            setPadding(dp(10), dp(8), dp(RELOAD_DP) + dp(4), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                ) {
                    go(text.toString())
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    closeMenu()
                    selectAll()
                } else {
                    // Whatever was half-typed is thrown away rather than left sitting in
                    // the bar pretending to be where the browser is.
                    setText(currentUrl)
                    hideSuggestions()
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    // Only while the user is the one doing the typing. The bar is also
                    // told its own text by every page that loads and again by the field
                    // being left, and neither of those is somebody looking for a page.
                    if (!addressBar.hasFocus()) return
                    showSuggestions(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        // Fill, ink, caret and selection from the one place the shell describes a text
        // box. This field was where that description came from - it is white under either
        // setting, because a field is a hole cut in the page rather than words on it - and
        // now the rename dialog and the two searches are the same field.
        palette.applyToField(addressBar)

        // The address and its button are one thing: the field is white, the button sits
        // on the white, and the pair reads as a single control the way the phone's did.
        // The mark is black rather than the set's own white for the same reason.
        reloadButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            // Nearly none. The box is the tap target and the mark inside it was being
            // drawn at half of it - eight device-independent pixels off each side of a
            // 34dp button leaves 18dp of glyph, which is a mark you have to look for on a
            // white field. What is left here is enough to keep the mark off the field's
            // own edge and no more. See RELOAD_DP.
            setPadding(dp(RELOAD_INSET_DP), dp(RELOAD_INSET_DP), dp(RELOAD_INSET_DP), dp(RELOAD_INSET_DP))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                val tab = current ?: return@setOnClickListener
                if (tab.loading) tab.webView.stopLoading() else tab.webView.reload()
            }
            TiltEffect.apply(this)
        }

        val field = FrameLayout(context)
        field.addView(addressBar, FrameLayout.LayoutParams(MATCH, MATCH))
        field.addView(reloadButton, FrameLayout.LayoutParams(
            dp(RELOAD_DP), dp(RELOAD_DP), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(3)
        })
        column.addView(field, LinearLayout.LayoutParams(MATCH, dp(ADDRESS_DP)))
        return column
    }

    /**
     * The three dots.
     *
     * Drawn rather than typed: an ellipsis character is a row of full stops sitting on the
     * baseline, and what the phone had was three round dots centred in the button.
     */
    private fun buildEllipsis(): View {
        val holder = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                if (menuScroller.visibility == View.VISIBLE) closeMenu() else openMenu()
            }
        }
        val dots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(3) { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }, LinearLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply {
                if (i > 0) marginStart = dp(4)
            })
        }
        holder.addView(dots, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        TiltEffect.apply(holder)
        return holder
    }

    /**
     * A white ring with a white mark in it, open in the middle.
     *
     * The shape the Start screen puts on a tile in edit mode, without its black fill: on
     * the app bar there is nothing behind the button but the bar, so the ring alone is
     * the button and the strip shows through it. See wp81_appbar_circle.
     */
    private fun circleButton(icon: String?, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            if (icon != null) setImageDrawable(SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    // ---------------------------------------------------------------- suggestions

    /**
     * The list that grows over the bar while an address is being typed.
     *
     * Same strip and same near-black as the menu, and capped the same way: it grows
     * upward from the field, and with the keyboard up there is not much screen left for
     * it to grow into. See MAX_SUGGESTIONS for why the list is short as well as the box.
     */
    private fun buildSuggestions(): View {
        suggestionPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        suggestionScroller = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val cap = (root.height - dp(BAR_DP) - dp(24)).coerceAtLeast(dp(120))
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            visibility = View.GONE
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        suggestionScroller.addView(suggestionPanel, FrameLayout.LayoutParams(MATCH, WRAP))
        return suggestionScroller
    }

    /**
     * Offers pages that have been read before and look like what is being typed.
     *
     * Nothing is fetched to do this. The phone's address bar asked a search engine what
     * you might have meant, which is a request per keystroke to somewhere else; this
     * offers the only thing it can offer without telling anyone what is being typed,
     * which is where this browser has already been. An empty field offers the most
     * recent pages, because a field somebody has just cleared is somebody looking for
     * somewhere they have been rather than somewhere new.
     *
     * The address of the page already open is never offered: it is what the bar said a
     * moment ago, and a suggestion to stay where you are is a wasted row.
     */
    private fun showSuggestions(typed: String) {
        val query = typed.trim()
        val matches = history
            .asSequence()
            .filter { it.url != currentUrl }
            .filter {
                query.isEmpty() ||
                    it.url.contains(query, ignoreCase = true) ||
                    it.title.contains(query, ignoreCase = true)
            }
            .take(MAX_SUGGESTIONS)
            .toList()
        if (matches.isEmpty()) {
            hideSuggestions()
            return
        }
        suggestionPanel.removeAllViews()
        for (entry in matches) suggestionPanel.addView(suggestionRow(entry))
        suggestionScroller.visibility = View.VISIBLE
        suggestionScroller.scrollTo(0, 0)
    }

    private fun hideSuggestions() {
        if (suggestionScroller.visibility != View.VISIBLE) return
        suggestionScroller.visibility = View.GONE
        suggestionPanel.removeAllViews()
    }

    /**
     * One page that has been read before: what it is called, and where it is.
     *
     * Both lines, because neither is enough on its own - a list of titles cannot be told
     * apart when three of them are called "Home", and a list of addresses is a list of
     * strings nobody reads. The address is the fainter of the two, the way every second
     * line in this shell is.
     */
    private fun suggestionRow(entry: HistoryEntry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                hideSuggestions()
                addressBar.clearFocus()
                hideKeyboard()
                current?.let { tab -> load(tab, entry.url) }
            }
            TiltEffect.apply(this)
        }
        row.addView(TextView(context).apply {
            text = entry.title.ifBlank { hostOf(entry.url) }
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        row.addView(TextView(context).apply {
            text = entry.url
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 12f
            setTextColor(SUGGESTION_URL_COLOUR)
            maxLines = 1
            // Cut at the end, where an address matters least: the host is at the front,
            // and that is what says which site the row is offering.
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        return row
    }

    // ---------------------------------------------------------------- history

    /**
     * Writes down a page that was reached, for the address bar to offer later.
     *
     * One row per address rather than one per visit: a list that says you were on the
     * same news site eleven times is eleven rows of the same suggestion, and the useful
     * thing about a page you keep returning to is that it comes up first. Returning to
     * one moves it back to the top instead of adding to it.
     */
    private fun recordVisit(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        history.removeAll { it.url == url }
        history.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        saveHistory()
    }

    /** A page that said what it was called after it had already been written down. */
    private fun noteTitle(url: String, title: String) {
        if (title.isBlank()) return
        val entry = history.firstOrNull { it.url == url } ?: return
        if (entry.title == title) return
        entry.title = title
        saveHistory()
    }

    private fun clearHistory() {
        if (history.isEmpty()) {
            notify("Internet Explorer", "There is no history to delete")
            return
        }
        history.clear()
        saveHistory()
        hideSuggestions()
        notify("Internet Explorer", "Browsing history deleted")
    }

    // ---------------------------------------------------------------- desktop mode

    /**
     * Tells [webView] which of itself to admit to.
     *
     * A site picks its mobile layout off the user agent, so that is the whole of the
     * setting: nothing else about the WebView changes, and a page that has been told it is
     * on a computer lays itself out as one. The address is reloaded rather than left,
     * because the version being asked for was decided when the page was fetched.
     */
    private fun applyDesktopMode(webView: WebView) {
        val settings = webView.settings
        if (mobileAgent == null) mobileAgent = settings.userAgentString
        settings.userAgentString =
            if (desktopMode) desktopAgent(mobileAgent ?: "") else mobileAgent
    }

    /**
     * The computer this browser says it is.
     *
     * Built out of the phone's own user agent rather than written down, so the Chrome
     * version in it is the version that is actually drawing the page. A made-up one goes
     * stale the first time WebView updates underneath, and a site reading it then serves a
     * workaround for a browser nobody is running. Everything that says phone comes out -
     * the Android platform, the `wv` that marks a WebView, the word Mobile - and a plain
     * Linux desktop goes in its place.
     */
    private fun desktopAgent(from: String): String =
        PLATFORM.replaceFirst(from, "(X11; Linux x86_64)")
            .replace("Version/4.0 ", "")
            .replace(" Mobile ", " ")

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DESKTOP_MODE, desktopMode).apply()
        // Every tab, not only the one in front: the setting is the browser's, and a tab
        // that kept the old answer until it was next touched would be a second setting.
        for (tab in tabs) applyDesktopMode(tab.webView)
        current?.let { tab ->
            // A tab that has never fetched anything has nothing to ask again for.
            if (tab.pending == null && tab.url.isNotBlank()) tab.webView.reload()
        }
    }

    // ---------------------------------------------------------------- the menu

    private fun openMenu() {
        // The two strips are never up together. Taking the field closes the menu, and
        // the dots put the field down - along with the keyboard and the suggestions,
        // which is also the only way to leave the bar without going anywhere.
        if (addressBar.hasFocus()) {
            addressBar.clearFocus()
            hideKeyboard()
        }
        hideSuggestions()
        buildMenu(favouritesOpen = false)
        menuScroller.visibility = View.VISIBLE
        menuCatcher.visibility = View.VISIBLE
        playMenuEntrance()
    }

    private fun closeMenu() {
        if (menuScroller.visibility != View.VISIBLE) return
        menuScroller.visibility = View.GONE
        menuCatcher.visibility = View.GONE
        menuPanel.removeAllViews()
    }

    /**
     * The commands, and behind one of them the favourites.
     *
     * Favourites replace the list in place rather than opening anything: the app bar is
     * already the full width of the screen and a menu that grows a second menu out of its
     * side is not something this shell does anywhere else.
     */
    private fun buildMenu(favouritesOpen: Boolean) {
        menuPanel.removeAllViews()
        menuScroller.scrollTo(0, 0)
        if (favouritesOpen) {
            if (favourites.isEmpty()) {
                menuPanel.addView(menuRow("no favourites yet", closes = false) {
                    buildMenu(favouritesOpen = false)
                    playMenuEntrance()
                })
                return
            }
            for (favourite in favourites) menuPanel.addView(favouriteRow(favourite))
            return
        }

        val tab = current
        // Always on the list, and dimmed where there is nothing ahead. A row that comes
        // and goes moves everything under it, so the one time the user has gone back and
        // wants forward is the one time every other command is in the wrong place.
        menuPanel.addView(
            menuRow("forward", enabled = tab?.webView?.canGoForward() == true) {
                tab?.webView?.goForward()
            }
        )
        menuPanel.addView(menuRow("home") { current?.let { load(it, homepage) } })
        menuPanel.addView(menuRow("favourites", closes = false) {
            buildMenu(favouritesOpen = true)
            playMenuEntrance()
        })
        menuPanel.addView(menuRow("add to favourites") { addCurrentToFavourites() })
        menuPanel.addView(menuRow("downloads") { openDownloads() })
        menuPanel.addView(menuRow("share page") { sharePage() })
        menuPanel.addView(menuRow("set as home page") { setHomepageToCurrent() })
        menuPanel.addView(
            menuRow("desktop mode", closes = false, ticked = desktopMode) {
                toggleDesktopMode()
                // Rebuilt in place rather than closed, so the box is seen to tick. The
                // list does not move under the finger: the row is where it was.
                buildMenu(favouritesOpen = false)
            }
        )
        menuPanel.addView(menuRow("delete history") { clearHistory() })
    }

    /**
     * One command on the list, and for a setting the box saying where it stands.
     *
     * [ticked] is a state rather than an absence of one, so a row that has it keeps it
     * either way: a box that vanished when the setting went off would leave the words
     * sliding sideways every time it was worked, and there would be nothing on the list
     * to say the setting exists.
     */
    private fun menuRow(
        label: String, closes: Boolean = true, enabled: Boolean = true,
        ticked: Boolean? = null, action: () -> Unit
    ): View {
        val row = TextView(context).apply {
            // Lowercase, like every command list in this shell.
            text = label.lowercase()
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 16f
            // Greyed rather than gone, the way the phone greyed a command it was keeping
            // in place. It does not tilt and it does not answer, so there is nothing to
            // learn from pressing it beyond what the colour already said.
            setTextColor(if (enabled) Color.WHITE else DISABLED_TEXT)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = enabled
            if (enabled) {
                setOnClickListener {
                    Haptics.tap(it)
                    if (closes) closeMenu()
                    action()
                }
                TiltEffect.apply(this)
            }
        }
        if (ticked == null) return row
        row.setCompoundDrawablesRelative(tickBox(ticked), null, null, null)
        row.compoundDrawablePadding = dp(TICK_GAP_DP)
        return row
    }

    /**
     * The box beside a setting on the menu.
     *
     * Drawn here rather than taken from the shell's own MetroMarker, which colours
     * itself out of the palette. This strip is the one surface in the phone that does not
     * follow the palette - see the note on the class - and a light theme's marker on it
     * would be a dark grey outline on near-black. White on the black strip, filled white
     * with the tick knocked out of it, is the same box in the same two states.
     */
    private fun tickBox(on: Boolean): Drawable {
        val size = dp(TICK_SIZE_DP)
        val frame = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (on) Color.WHITE else Color.TRANSPARENT)
            setStroke(dp(2), Color.WHITE)
        }
        val box = if (!on) frame else {
            val tick = ResourcesCompat
                .getDrawable(context.resources, R.drawable.ic_check_windows, null)
                ?.mutate()?.apply { setTint(Color.BLACK) }
            if (tick == null) frame else LayerDrawable(arrayOf(frame, tick)).apply {
                setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
            }
        }
        // Set here rather than left to the intrinsic size, which a layered box takes from
        // whichever layer is largest - so the ticked one would come out a hair wider than
        // the empty one and the words beside it would step sideways as it was worked.
        box.setBounds(0, 0, size, size)
        return box
    }

    /** A favourite, with the means to get rid of it on the same row. */
    private fun favouriteRow(favourite: Favourite): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = favourite.name
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(22), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                current?.let { load(it, favourite.url) }
            }
            TiltEffect.apply(this)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The one the browser ships with stays: it is the only address in the list that
        // is guaranteed to still be there tomorrow.
        //
        // A bare mark rather than a ringed disc like the app bar's. Those two sit over an
        // arbitrary web page and need the ring to be seen at all; this one is on the bar's
        // own near-black, and a row of discs down the side of a list is noise.
        if (!favourite.isDefault) {
            row.addView(ImageView(context).apply {
                setImageDrawable(SvgIcon.fromAsset(context, REMOVE_ICON))
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener {
                    Haptics.tap(it)
                    favourites.remove(favourite)
                    saveFavourites()
                    buildMenu(favouritesOpen = true)
                    playMenuEntrance()
                }
                TiltEffect.apply(this)
            }, LinearLayout.LayoutParams(dp(REMOVE_DP), dp(REMOVE_DP)).apply {
                marginEnd = dp(16)
            })
        }
        return row
    }

    /** Each row swings down about its own top edge, on a stagger. Same as the shell's. */
    private fun playMenuEntrance() {
        for (i in 0 until menuPanel.childCount) {
            val row = menuPanel.getChildAt(i)
            row.cameraDistance = 8000f * context.resources.displayMetrics.density
            row.pivotX = 0f
            row.pivotY = 0f
            row.rotationX = -90f
            row.alpha = 0f
            row.animate()
                .rotationX(0f)
                .alpha(1f)
                .setStartDelay(i * STAGGER_MS)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // ---------------------------------------------------------------- commands

    private fun sharePage() {
        val tab = current ?: return
        val url = tab.webView.url ?: return
        shareLink(url, tab.title.ifBlank { url })
    }

    /**
     * Hands an address to whatever the phone shares with.
     *
     * [subject] is what the address is called wherever the receiving app has somewhere to
     * put a name - a mail's subject line, mostly. The page's own title when there is one;
     * for a link held on a page there is nothing to go on but the address itself.
     */
    private fun shareLink(url: String, subject: String) {
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, url)
            }, null).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            Log.e(TAG, "Could not share $url", e)
        }
    }

    private fun setHomepageToCurrent() {
        val url = liveUrl() ?: return
        homepage = url
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOMEPAGE, url).apply()
        notify("Internet Explorer", "Home page set to ${hostOf(url)}")
    }

    private fun addCurrentToFavourites() {
        val url = liveUrl() ?: return
        if (favourites.any { it.url == url }) {
            notify("Internet Explorer", "${hostOf(url)} is already a favourite")
            return
        }
        favourites.add(0, Favourite(
            name = current?.title?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            isDefault = false
        ))
        saveFavourites()
        notify("Internet Explorer", "${hostOf(url)} added to favourites")
    }

    /**
     * Says something on the shell's own band.
     *
     * [onTap] is what the band does when it is tapped, which for anything that has just
     * been saved is to open the thing that was saved. Nothing, for an announcement that is
     * only an announcement.
     */
    private fun notify(title: String, text: String, onTap: (() -> Unit)? = null) {
        onShowNotification(title, text, onTap)
    }

    /** Where the browser actually is, or null if it is nowhere worth remembering. */
    private fun liveUrl(): String? =
        current?.webView?.url?.takeIf { it.isNotBlank() && !it.startsWith("about:") }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (e: Exception) {
        url
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Follows what was typed, which is an address if it can be read as one and a search
     * if it cannot.
     */
    private fun go(typed: String) {
        val text = typed.trim()
        if (text.isEmpty()) return
        val tab = current ?: return

        val looksLikeUrl = text.startsWith("http://") || text.startsWith("https://") ||
            (!text.contains(" ") && text.matches(HOSTNAME))

        val target = when {
            !looksLikeUrl ->
                "https://www.google.com/search?q=${Uri.encode(text)}"
            text.startsWith("http://") || text.startsWith("https://") -> text
            else -> "https://$text"
        }

        addressBar.clearFocus()
        hideSuggestions()
        hideKeyboard()
        load(tab, target)
    }

    /**
     * Opens an address arriving from elsewhere in the launcher.
     *
     * In its own tab, the way the phone did it: a link followed from a tile or a news
     * story is a new thing to read, and loading it over whatever was already open throws
     * away a page the user never closed.
     *
     * [fromAnotherApp] marks a link handed over by an app outside the launcher, which is
     * what back needs to know to give that app its screen back. See [Tab.external].
     */
    fun navigateToUrl(url: String, fromAnotherApp: Boolean = false) {
        closeTabs()
        openTab(url)?.external = fromAnotherApp
    }

    private fun load(tab: Tab, url: String) {
        tab.failed = false
        tab.url = url
        if (tab === current) {
            showError(null)
            showAddress(url)
        }
        tab.webView.loadUrl(url)
    }

    /** Puts [url] in the bar, unless the user is in the middle of typing over it. */
    private fun showAddress(url: String) {
        currentUrl = url
        if (!addressBar.hasFocus()) addressBar.setText(url)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    /**
     * Back is the browser's before it is the window's.
     *
     * The tabs page, the menu and the address field are all things the user opened and
     * expects to come out of; then it is the page's own history; and then, with more than
     * one page open, closing this one and returning to the last. Only with a single page
     * that has nowhere left to go does it hand back to the shell, which closes the window.
     *
     * A page another app handed over is the exception, and comes before the tab that was
     * open behind it: somebody who followed a link out of Reddit is inside Reddit's errand,
     * not inside a browsing session of their own, and backing out of the page they were
     * sent to read belongs to the app that sent them rather than to whatever the browser
     * happened to have open at the time. The page goes, and the screen goes back where it
     * came from.
     */
    fun handleBack(): Boolean {
        if (pressMenu.isShowing()) {
            pressMenu.dismiss()
            return true
        }
        if (tabsPage.visibility == View.VISIBLE) {
            closeTabs()
            return true
        }
        if (downloadsTurn.isOnScreen) {
            closeDownloads()
            return true
        }
        if (menuScroller.visibility == View.VISIBLE) {
            closeMenu()
            return true
        }
        if (addressBar.hasFocus()) {
            addressBar.clearFocus()
            hideKeyboard()
            return true
        }
        val tab = current ?: return false
        if (tab.failed) {
            tab.failed = false
            showError(null)
            if (tab.webView.canGoBack()) tab.webView.goBack()
            return true
        }
        if (tab.webView.canGoBack()) {
            tab.webView.goBack()
            return true
        }
        if (tab.external) {
            onReturnToLinkCaller()
            // With something else open behind it the browser stays, minus the page it was
            // lent out for. With nothing behind it there is no browsing session here to
            // come back to at all, so it goes with the page: unhandled, and the shell
            // closes the window as it closes any other that has run out of back.
            if (tabs.size == 1) return false
            closeTab(tab)
            return true
        }
        if (tabs.size > 1) {
            closeTab(tab)
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- loading state

    /** Shows or clears the line, and names the button, for the tab being looked at. */
    private fun paintLoading(tab: Tab) {
        reloadButton.setImageDrawable(
            SvgIcon.fromAsset(context, if (tab.loading) STOP_ICON else REFRESH_ICON))
        if (tab.loading) {
            progressFill.animate().cancel()
            progressFill.alpha = 1f
            progressFill.scaleX = 0.02f
        } else {
            // Run to the end before disappearing: a line that vanishes at two thirds reads
            // as a page that gave up rather than one that arrived.
            progressFill.animate().cancel()
            progressFill.animate().scaleX(1f).setDuration(120).withEndAction {
                progressFill.animate().alpha(0f).setDuration(180).start()
            }.start()
        }
    }

    private fun setProgress(fraction: Float) {
        if (current?.loading != true) return
        progressFill.animate().cancel()
        progressFill.animate()
            .scaleX(fraction.coerceIn(0.02f, 1f))
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ---------------------------------------------------------------- the error page

    /**
     * What the phone said when a page could not be reached.
     *
     * The desktop browser loads an HTML error page styled as a Windows dialog, which in
     * here would be a small grey window sitting inside a full-screen phone app. This is
     * the same information as a page of the shell's own: a line of large light Segoe, the
     * address underneath it, and one thing to do about it.
     */
    private fun buildErrorPage(): LinearLayout {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(palette.background)
            setPadding(dp(24), dp(40), dp(24), dp(24))
            // Nothing behind it is meant to be reachable while it is up.
            isClickable = true
        }
        page.addView(TextView(context).apply {
            text = "we're having trouble finding that page"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
            textSize = 26f
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        errorDetail = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(14), 0, 0)
        }
        page.addView(errorDetail, LinearLayout.LayoutParams(MATCH, WRAP))

        page.addView(TextView(context).apply {
            text = "try again"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
            textSize = 16f
            setTextColor(palette.accent)
            setPadding(0, dp(26), 0, dp(10))
            isClickable = true
            setOnClickListener {
                val tab = current ?: return@setOnClickListener
                tab.failedUrl?.let { load(tab, it) }
            }
            TiltEffect.apply(this)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        return page
    }

    /** Puts the error up for [url], or takes it down when handed nothing. */
    private fun showError(url: String?) {
        if (url == null) {
            errorPage.visibility = View.GONE
            return
        }
        errorDetail.text = buildString {
            append("Make sure the address is right, and that this phone is on a network.")
            if (url.isNotBlank()) append("\n\n").append(url)
        }
        errorPage.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- storage

    /**
     * Writes down what is open, so the next launch finds it.
     *
     * Addresses and titles only. A tab is a page you meant to come back to, which is an
     * address; its history and its scroll position belong to a WebView that will not
     * outlive the process, and pretending otherwise would mean promising a restored tab
     * behaves like one that never went away.
     */
    private fun saveTabs() {
        // A tab with nowhere to go yet is not written down - there is nothing to put back
        // - and the position is taken against the list that *is* written, so dropping one
        // does not leave the mark pointing at the tab beside it.
        val kept = tabs.filter { it.url.isNotBlank() && !it.url.startsWith("about:") }
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TABS, Gson().toJson(kept.map { SavedTab(it.url, it.title) }))
            .putInt(KEY_ACTIVE_TAB, kept.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    private fun loadTabs(): List<SavedTab> {
        val json = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TABS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedTab>>() {}.type
            Gson().fromJson<List<SavedTab>>(json, type)
                ?.filter { it.url.isNotBlank() }
                ?.take(MAX_TABS)
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable tabs", e)
            emptyList()
        }
    }

    private fun saveLastUrl(url: String) {
        if (url == "about:blank" || url.startsWith("file://")) return
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(InternetExplorerApp.KEY_LAST_URL, url).apply()
    }

    /** The desktop browser's own list, read in its own format. */
    private fun loadFavourites(): MutableList<Favourite> {
        val json = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(InternetExplorerApp.KEY_FAVOURITES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Favourite>>() {}.type
            Gson().fromJson<MutableList<Favourite>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable favourites", e)
            mutableListOf()
        }
    }

    private fun saveFavourites() {
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(InternetExplorerApp.KEY_FAVOURITES, Gson().toJson(favourites)).apply()
    }

    private fun loadHistory(): MutableList<HistoryEntry> {
        val json = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<HistoryEntry>>() {}.type
            Gson().fromJson<MutableList<HistoryEntry>>(json, type)
                ?.filter { it.url.isNotBlank() }
                ?.take(MAX_HISTORY)
                ?.toMutableList()
                ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable history", e)
            mutableListOf()
        }
    }

    private fun saveHistory() {
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
    }

    /**
     * How tall the strip along the bottom is.
     *
     * For anything that has to sit clear of it: the shell's toast lands above the
     * navigation keys, which is on top of this bar, and an announcement that covers the
     * address bar covers the page's own name along with it.
     */
    fun barHeight(): Int = dp(BAR_DP)

    fun cleanup() {
        root.removeCallbacks(downloadsTick)
        landings?.let {
            // Nothing left to announce a landing to. The download itself carries on, and
            // the system's own notification is what says so once the browser is gone.
            runCatching { context.unregisterReceiver(it) }
            landings = null
        }
        arriving.clear()
        for (tab in tabs) {
            tab.webView.stopLoading()
            tab.webView.destroy()
        }
        tabs.clear()
        current = null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MetroIEApp"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** A command the menu is holding open but cannot run yet. */
        private const val DISABLED_TEXT = 0x66FFFFFF.toInt()

        /** The tick box on a menu row, and the space between it and the words. */
        private const val TICK_SIZE_DP = 18
        private const val TICK_GAP_DP = 14

        /** The bracketed platform at the head of a user agent. See [desktopAgent]. */
        private val PLATFORM = Regex("""\([^)]*\)""")

        /** Whether sites are asked for their desktop version. */
        private const val KEY_DESKTOP_MODE = "ie_desktop_mode"

        private const val DEFAULT_HOMEPAGE = "https://news.google.com"
        private const val KEY_HOMEPAGE = "ie_homepage"

        /** What was open last time, and which of them was being read. */
        private const val KEY_TABS = "ie_tabs"
        private const val KEY_ACTIVE_TAB = "ie_active_tab"

        /** Everywhere the browser has been. Emptied by the menu's delete history. */
        private const val KEY_HISTORY = "ie_history"

        /**
         * How many pages are remembered.
         *
         * The list exists to finish an address somebody is typing, and an address they
         * are typing is one they have been to lately. Two hundred rows is more than a
         * phone browser gets through in a month of the kind of use this shell sees, and
         * it is all written back to one preference on every page load, so it is not a
         * number that wants to be large.
         */
        private const val MAX_HISTORY = 200

        /**
         * How many are offered at once.
         *
         * The list grows up from the address bar with the keyboard already holding the
         * bottom half of the screen. Six rows is about what is left, and a suggestion
         * that has to be scrolled to is one nobody was going to take anyway.
         */
        private const val MAX_SUGGESTIONS = 6

        /** The address under a suggestion. The bar's own grey, against its near-black. */
        private const val SUGGESTION_URL_COLOUR = 0xFF9A9A9A.toInt()

        /** The app bar's own near-black, which is not the palette's and never was. */
        private const val BAR_COLOUR = 0xFF212021.toInt()

        /** The loading line. IE's blue, from the phone. */
        private const val PROGRESS_COLOUR = 0xFF61ADDA.toInt()

        private const val BAR_DP = 62
        private const val BUTTON_DP = 44
        private const val ADDRESS_DP = 40

        /**
         * The reload button's box, inside the address field's right-hand end.
         *
         * As large as the field is tall, less the air that keeps it off the top and bottom
         * edges. With [RELOAD_INSET_DP] that puts a 36dp glyph in it - twice what the same
         * button used to draw - which is the size the mark has to be to be read at a glance
         * against a page's own white.
         */
        private const val RELOAD_DP = 38

        /** How far the mark sits inside the button. Enough to clear the field, no more. */
        private const val RELOAD_INSET_DP = 1
        private const val PROGRESS_DP = 3
        private const val DOT_DP = 5
        private const val REMOVE_DP = 32

        /** The tabs page: two cards across, at the shell's own page margin. */
        private const val TABS_PER_ROW = 2
        private const val PAGE_MARGIN_DP = 22
        private const val THUMB_HEIGHT_DP = 190
        private const val CLOSE_DP = 30

        /** How wide a thumbnail is kept. Enough for a card, far less than a screen. */
        private const val THUMB_WIDTH_DP = 200

        /** Cards in the set, and so the most pages the button could ever say. */
        private const val CARD_ICONS = 9

        /** See openTab. The button's ceiling is the browser's. */
        private const val MAX_TABS = CARD_ICONS

        /**
         * How far the mark sits inside the ring.
         *
         * These are drawn as app-bar icons, on a 76-unit square with the mark already
         * inset - but not by as much as a ringed disc needs, and without this the reload
         * arrows touch the ring at the corners.
         */
        private const val GLYPH_INSET_DP = 6

        private const val STAGGER_MS = 30L

        /** Where a picture waits between being fetched and being handed on. */
        private const val SHARE_DIR = "shared_pictures"

        /** How long a picture is given to arrive before the command gives up on it. */
        private const val FETCH_TIMEOUT_MS = 15_000

        /** How often the downloads page redraws while something is still coming in. */
        private const val DOWNLOADS_TICK_MS = 900L

        /** As far back as the downloads page goes. It is a recent list, not an archive. */
        private const val MAX_DOWNLOADS = 50

        private const val ICON_DIR = "custom_icons_8"
        private const val NEW_ICON = "$ICON_DIR/appbar.add.svg"
        private const val REFRESH_ICON = "$ICON_DIR/appbar.refresh.svg"
        private const val STOP_ICON = "$ICON_DIR/appbar.axis.x.letter.svg"
        private const val REMOVE_ICON = "$ICON_DIR/appbar.close.svg"

        /** Something with a dot in it and no spaces: `example.com`, `a.b.co.uk/path`. */
        private val HOSTNAME = Regex("^[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}(/.*)?$")
    }
}
