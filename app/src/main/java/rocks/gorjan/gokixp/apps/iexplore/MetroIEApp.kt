package rocks.gorjan.gokixp.apps.iexplore

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Internet Explorer, as the phone had it.
 *
 * The desktop themes give the browser a window with a toolbar, a status bar and eight
 * buttons across the top. The phone gave it none of that: the page has the whole screen,
 * and everything you can do to it lives on one dark strip along the bottom - stop or
 * reload on the left, the address in the middle, and a row of dots on the right that
 * lifts the rest of the commands into view.
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

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView

    /** The strip along the bottom: the menu, when it is open, sitting on top of the row. */
    private lateinit var appBar: LinearLayout
    private lateinit var menuPanel: LinearLayout

    /** Holds [menuPanel], and stops a long favourites list running off the top edge. */
    private lateinit var menuScroller: android.widget.ScrollView
    private lateinit var addressBar: EditText
    private lateinit var reloadButton: ImageView

    /** The blue line over the address bar. Scaled from the left rather than resized. */
    private lateinit var progressFill: View

    /** Swallows taps on the page while the menu is open, so the first one only closes it. */
    private lateinit var menuCatcher: View

    /** Shown in place of the page when a page cannot be reached at all. */
    private lateinit var errorPage: LinearLayout
    private lateinit var errorDetail: TextView

    private var homepage: String = DEFAULT_HOMEPAGE
    private val favourites = mutableListOf<Favourite>()

    private var isLoading = false

    /** What the address bar should say once the user stops editing it. */
    private var currentUrl: String = ""

    /** The address that failed, for the retry on the error page. */
    private var failedUrl: String? = null

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

        webView = WebView(context)
        buildWebView()

        // The page gets everything above the strip. The strip is laid over the top of it
        // rather than beside it so the menu can grow upward over the page instead of
        // squeezing it - which would reflow the whole document every time it opened.
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH).apply {
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

        val lastUrl = prefs.getString(InternetExplorerApp.KEY_LAST_URL, null)
        loadUrl(initialUrl ?: lastUrl ?: homepage)
        root.requestFocus()
        return root
    }

    // ---------------------------------------------------------------- the page

    private fun buildWebView() {
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleScheme(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                if (url != null) handleScheme(url) else false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorPage.visibility = View.GONE
                setLoading(true)
                if (url != null) showAddress(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setLoading(false)
                if (url != null) {
                    showAddress(url)
                    saveLastUrl(url)
                }
                onUpdateWindowTitle(view?.title?.takeIf { it.isNotBlank() } ?: "Internet Explorer")
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only the page itself. An image or a tracker that fails to load is not a
                // page that could not be reached, and covering the article over because
                // one of its ads timed out is worse than the ad being missing.
                if (request?.isForMainFrame != true) return
                setLoading(false)
                failedUrl = request.url?.toString()
                showError(failedUrl)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                setProgress(newProgress / 100f)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                onUpdateWindowTitle(title?.takeIf { it.isNotBlank() } ?: "Internet Explorer")
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
    private fun handleScheme(url: String): Boolean {
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
                    intent.getStringExtra("browser_fallback_url")?.let { webView.loadUrl(it) }
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
        menuScroller = object : android.widget.ScrollView(context) {
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

        reloadButton = circleButton(REFRESH_ICON) {
            // Whatever it does, it is about the page - so the commands go away with it.
            closeMenu()
            if (isLoading) webView.stopLoading() else webView.reload()
        }
        row.addView(reloadButton, LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))

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
            background = null
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.argb(140, 0, 0, 0))
            hint = "search or enter web address"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 14f
            isSingleLine = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
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
        // The caret and the selection band, set by hand rather than through the palette's
        // applyToField: this is the one field in the shell that is not on the shell's
        // background. It is white whichever way the light/dark setting is turned, so a
        // caret drawn in the palette's foreground would be white on white half the time.
        addressBar.setTextCursorDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            // Stretched to the line's height; only the width is read from here.
            setSize(dp(2), dp(2))
        })
        addressBar.highlightColor = Color.argb(
            90, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent))

        column.addView(addressBar, LinearLayout.LayoutParams(MATCH, dp(ADDRESS_DP)))
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
                Helpers.performHapticFeedback(context)
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
     * A black disc with a white ring and a white mark in it.
     *
     * The same button the Start screen puts on a tile in edit mode, for the same reason:
     * it sits on an app bar that has an arbitrary web page a few pixels above it, and a
     * ringed disc is legible against anything.
     */
    private fun circleButton(icon: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_handle_circle)
            setImageDrawable(SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Helpers.performHapticFeedback(context)
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

        if (webView.canGoForward()) menuPanel.addView(menuRow("forward") { webView.goForward() })
        menuPanel.addView(menuRow("home") { loadUrl(homepage) })
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
                Helpers.performHapticFeedback(context)
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
                Helpers.performHapticFeedback(context)
                closeMenu()
                loadUrl(favourite.url)
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
                    Helpers.performHapticFeedback(context)
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
        val url = webView.url ?: return
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, webView.title ?: url)
                putExtra(Intent.EXTRA_TEXT, url)
            }, null).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            Log.e(TAG, "Could not share $url", e)
        }
    }

    private fun setHomepageToCurrent() {
        val url = webView.url?.takeIf { it.isNotBlank() && !it.startsWith("about:") } ?: return
        homepage = url
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOMEPAGE, url).apply()
        onShowNotification("Internet Explorer", "Home page set to ${hostOf(url)}")
    }

    private fun addCurrentToFavourites() {
        val url = webView.url?.takeIf { it.isNotBlank() && !it.startsWith("about:") } ?: return
        if (favourites.any { it.url == url }) {
            onShowNotification("Internet Explorer", "${hostOf(url)} is already a favourite")
            return
        }
        favourites.add(0, Favourite(
            name = webView.title?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            isDefault = false
        ))
        saveFavourites()
        onShowNotification("Internet Explorer", "${hostOf(url)} added to favourites")
    }

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
        loadUrl(target)
    }

    fun navigateToUrl(url: String) = loadUrl(url)

    private fun loadUrl(url: String) {
        errorPage.visibility = View.GONE
        showAddress(url)
        webView.loadUrl(url)
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
     * Back is the page's before it is the window's.
     *
     * Returns false only when there is nothing left inside the browser to go back to,
     * which is the point at which the shell closes the window.
     */
    fun handleBack(): Boolean {
        if (menuScroller.visibility == View.VISIBLE) {
            closeMenu()
            return true
        }
        if (addressBar.hasFocus()) {
            addressBar.clearFocus()
            hideKeyboard()
            return true
        }
        if (errorPage.visibility == View.VISIBLE) {
            errorPage.visibility = View.GONE
            if (webView.canGoBack()) webView.goBack()
            return true
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- loading state

    /** Swaps the mark on the left button and shows or clears the line. */
    private fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        reloadButton.setImageDrawable(
            SvgIcon.fromAsset(context, if (loading) STOP_ICON else REFRESH_ICON))
        if (loading) {
            progressFill.animate().cancel()
            progressFill.alpha = 1f
            progressFill.scaleX = 0.02f
        } else {
            // Run to the end before disappearing: a line that vanishes at two thirds reads
            // as a page that gave up rather than one that arrived.
            progressFill.animate().scaleX(1f).setDuration(120).withEndAction {
                progressFill.animate().alpha(0f).setDuration(180).start()
            }.start()
        }
    }

    private fun setProgress(fraction: Float) {
        if (!isLoading) return
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
            setOnClickListener { failedUrl?.let { loadUrl(it) } }
            TiltEffect.apply(this)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        return page
    }

    private fun showError(url: String?) {
        errorDetail.text = buildString {
            append("Make sure the address is right, and that this phone is on a network.")
            if (!url.isNullOrBlank()) append("\n\n").append(url)
        }
        errorPage.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- storage

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
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.destroy()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MetroIEApp"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        private const val DEFAULT_HOMEPAGE = "https://news.google.com"
        private const val KEY_HOMEPAGE = "ie_homepage"

        /** The app bar's own near-black, which is not the palette's and never was. */
        private const val BAR_COLOUR = 0xFF212021.toInt()

        /** The loading line. IE's blue, from the phone. */
        private const val PROGRESS_COLOUR = 0xFF61ADDA.toInt()

        private const val BAR_DP = 62
        private const val BUTTON_DP = 44
        private const val ADDRESS_DP = 40
        private const val PROGRESS_DP = 3
        private const val DOT_DP = 5
        private const val REMOVE_DP = 32

        /**
         * How far the mark sits inside the ring.
         *
         * These are drawn as app-bar icons, on a 76-unit square with the mark already
         * inset - but not by as much as a ringed disc needs, and without this the reload
         * arrows touch the ring at the corners.
         */
        private const val GLYPH_INSET_DP = 6

        private const val STAGGER_MS = 30L

        private const val REFRESH_ICON = "custom_icons_8/appbar.refresh.svg"
        private const val STOP_ICON = "custom_icons_8/appbar.axis.x.letter.svg"
        private const val REMOVE_ICON = "custom_icons_8/appbar.close.svg"

        /** Something with a dot in it and no spaces: `example.com`, `a.b.co.uk/path`. */
        private val HOSTNAME = Regex("^[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}(/.*)?$")
    }
}
