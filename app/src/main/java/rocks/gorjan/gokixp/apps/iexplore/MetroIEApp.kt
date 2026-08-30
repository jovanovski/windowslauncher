package rocks.gorjan.gokixp.apps.iexplore

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
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
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/** One open page, as it is written down between sessions. See MetroIEApp.saveTabs. */
internal data class SavedTab(val url: String, val title: String)

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
@SuppressLint("SetJavaScriptEnabled")
class MetroIEApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val onShowNotification: (String, String) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit
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
        val webView = WebView(context)
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
    }

    private lateinit var root: FrameLayout

    /** Holds every tab's WebView. Only the current one is visible. */
    private lateinit var pages: FrameLayout

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null

    /** The strip along the bottom: the menu, when it is open, sitting on top of the row. */
    private lateinit var appBar: LinearLayout
    private lateinit var menuPanel: LinearLayout

    /** Holds [menuPanel], and stops a long favourites list running off the top edge. */
    private lateinit var menuScroller: ScrollView

    private lateinit var addressBar: EditText

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

    /** The tabs page, which covers the browser entirely - app bar included. */
    private lateinit var tabsPage: FrameLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabsScroller: ScrollView

    private var homepage: String = DEFAULT_HOMEPAGE
    private val favourites = mutableListOf<Favourite>()

    /** What the address bar should say once the user stops editing it. */
    private var currentUrl: String = ""

    fun createView(initialUrl: String? = null): View {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        homepage = prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        favourites.clear()
        favourites.addAll(loadFavourites())

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

        // What was open last time. A browser on a phone is a place rather than a document
        // - the pages you left in it are still yours when you come back - and the launcher
        // is restarted often enough (a theme change, a rotation, the system reclaiming it)
        // that losing them to that would make tabs useless.
        val restored = loadTabs()
        if (restored.isEmpty()) {
            val lastUrl = prefs.getString(InternetExplorerApp.KEY_LAST_URL, null)
            openTab(initialUrl ?: lastUrl ?: homepage)
        } else {
            for (saved in restored) restoreTab(saved)
            val active = prefs.getInt(KEY_ACTIVE_TAB, 0).coerceIn(0, tabs.size - 1)
            activate(tabs[active])
            // An address that arrived with the window is a new thing to read, and goes in
            // its own tab on top of what was already there.
            if (initialUrl != null) openTab(initialUrl)
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
    private fun openTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            onShowNotification("Internet Explorer", "Nine pages is as many as it will hold")
            return
        }
        val tab = Tab()
        configure(tab)
        tabs.add(tab)
        pages.addView(tab.webView, FrameLayout.LayoutParams(MATCH, MATCH))
        activate(tab)
        load(tab, url)
        saveTabs()
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

    // ---------------------------------------------------------------- the page

    private fun configure(tab: Tab) {
        val webView = tab.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        // Pinch to zoom, without the pair of grey +/- buttons that come with it by
        // default and that no phone browser has had since about 2011.
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // White rather than the palette's background: a page that has not painted yet is
        // about to be white, and flashing black in between is worse than being early.
        webView.setBackgroundColor(Color.WHITE)
        webView.visibility = View.GONE

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
                if (tab === current) {
                    onUpdateWindowTitle(tab.title.ifBlank { "Internet Explorer" })
                }
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            download(url, contentDisposition, mimetype)
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
                onShowNotification("Internet Explorer", "Nothing on this phone opens that link")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $url", e)
        }
        return true
    }

    private fun download(url: String, contentDisposition: String?, mimetype: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading from Internet Explorer")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType(mimetype)
            val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloads.enqueue(request)
            onShowNotification("Downloading", fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Could not download $url", e)
            onShowNotification("Internet Explorer", "That file could not be downloaded")
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
                }
            }
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

    // ---------------------------------------------------------------- the menu

    private fun openMenu() {
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
        if (tab?.webView?.canGoForward() == true) {
            menuPanel.addView(menuRow("forward") { tab.webView.goForward() })
        }
        menuPanel.addView(menuRow("home") { current?.let { load(it, homepage) } })
        menuPanel.addView(menuRow("favourites", closes = false) {
            buildMenu(favouritesOpen = true)
            playMenuEntrance()
        })
        menuPanel.addView(menuRow("add to favourites") { addCurrentToFavourites() })
        menuPanel.addView(menuRow("share page") { sharePage() })
        menuPanel.addView(menuRow("set as home page") { setHomepageToCurrent() })
    }

    private fun menuRow(label: String, closes: Boolean = true, action: () -> Unit): View =
        TextView(context).apply {
            // Lowercase, like every command list in this shell.
            text = label.lowercase()
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                if (closes) closeMenu()
                action()
            }
            TiltEffect.apply(this)
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
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, tab.title.ifBlank { url })
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
        onShowNotification("Internet Explorer", "Home page set to ${hostOf(url)}")
    }

    private fun addCurrentToFavourites() {
        val url = liveUrl() ?: return
        if (favourites.any { it.url == url }) {
            onShowNotification("Internet Explorer", "${hostOf(url)} is already a favourite")
            return
        }
        favourites.add(0, Favourite(
            name = current?.title?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            isDefault = false
        ))
        saveFavourites()
        onShowNotification("Internet Explorer", "${hostOf(url)} added to favourites")
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
        hideKeyboard()
        load(tab, target)
    }

    /**
     * Opens an address arriving from elsewhere in the launcher.
     *
     * In its own tab, the way the phone did it: a link followed from a tile or a news
     * story is a new thing to read, and loading it over whatever was already open throws
     * away a page the user never closed.
     */
    fun navigateToUrl(url: String) {
        closeTabs()
        openTab(url)
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
     */
    fun handleBack(): Boolean {
        if (tabsPage.visibility == View.VISIBLE) {
            closeTabs()
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

    fun cleanup() {
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

        private const val DEFAULT_HOMEPAGE = "https://news.google.com"
        private const val KEY_HOMEPAGE = "ie_homepage"

        /** What was open last time, and which of them was being read. */
        private const val KEY_TABS = "ie_tabs"
        private const val KEY_ACTIVE_TAB = "ie_active_tab"

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

        private const val ICON_DIR = "custom_icons_8"
        private const val NEW_ICON = "$ICON_DIR/appbar.add.svg"
        private const val REFRESH_ICON = "$ICON_DIR/appbar.refresh.svg"
        private const val STOP_ICON = "$ICON_DIR/appbar.axis.x.letter.svg"
        private const val REMOVE_ICON = "$ICON_DIR/appbar.close.svg"

        /** Something with a dot in it and no spaces: `example.com`, `a.b.co.uk/path`. */
        private val HOSTNAME = Regex("^[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}(/.*)?$")
    }
}
