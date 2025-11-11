package rocks.gorjan.gokixp.apps.iexplore

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

/**
 * Data class for a favourite website
 */
data class Favourite(
    val name: String,
    val url: String,
    val isDefault: Boolean = false
)

class InternetExplorerApp(
    private val context: Context,
    private val onSoundPlay: () -> Unit,
    private val onShowNotification: (String, String) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val onShowContextMenu: (List<ContextMenuItem>, Float, Float) -> Unit
) {
    companion object {
        private const val KEY_FAVOURITES = "ie_favourites"
        private const val DEFAULT_FAVOURITE_NAME = "Windows Launcher"
        private const val DEFAULT_FAVOURITE_URL = "https://github.com/jovanovski/windowslauncher/"
        private const val SOUND_THROTTLE_MS = 2000L // Only allow one sound per second
    }

    private var homepage: String = "https://news.google.com"
    private var webView: WebView? = null
    private val favourites = mutableListOf<Favourite>()
    private var isPageLoaded = false
    private var lastSoundPlayTime = 0L
    private var isLoadingErrorPage = false
    private var lastAttemptedUrl: String? = null

    fun setupApp(contentView: View, initialUrl: String? = null) {
        // Load homepage from shared preferences
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        homepage = prefs.getString("ie_homepage", "https://news.google.com") ?: "https://news.google.com"

        // Load favourites
        favourites.clear()
        favourites.addAll(loadFavourites())

        // Get references to views
        val urlEditText = contentView.findViewById<android.widget.EditText>(R.id.url_bar)
        val backButton = contentView.findViewById<View>(R.id.ie_back_button)
        val forwardButton = contentView.findViewById<View>(R.id.ie_forward_button)
        val goButton = contentView.findViewById<View>(R.id.ie_go_button)
        val refreshButton = contentView.findViewById<View>(R.id.ie_refresh_button)
        val homeButton = contentView.findViewById<View>(R.id.ie_home_button)
        val searchButton = contentView.findViewById<View>(R.id.ie_search_button)
        val webView = contentView.findViewById<WebView>(R.id.ie_web_view)
        this.webView = webView // Store reference for cleanup
        val statusIcon = contentView.findViewById<android.widget.ImageView>(R.id.status_icon)
        val statusText = contentView.findViewById<android.widget.TextView>(R.id.status_text)
        val tabName = contentView.findViewById<android.widget.TextView>(R.id.tab_name)


        // Configure WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrl(url, webView)
            }

            // Handle deprecated version for older Android versions
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return if (url != null) handleUrl(url, webView) else false
            }

            private fun handleUrl(url: String, webView: WebView): Boolean {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase()

                // If the scheme is not http or https, try to open with system intent
                if (scheme != "http" && scheme != "https") {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        // Check if there's an app that can handle this intent
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                            return true // Prevent WebView from loading the URL
                        } else {
                            Log.w("InternetExplorerApp", "No app found to handle URL: $url")
                            return true // Still prevent WebView from trying to load it
                        }
                    } catch (e: Exception) {
                        Log.e("InternetExplorerApp", "Error opening URL with system intent: $url", e)
                        return true // Prevent WebView from trying to load it
                    }
                }

                // Also handle intent:// scheme
                if (url.startsWith("intent://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            // Try fallback URL if available
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (fallbackUrl != null) {
                                webView.loadUrl(fallbackUrl)
                            }
                        }
                        return true
                    } catch (e: Exception) {
                        Log.e("InternetExplorerApp", "Error handling intent URL: $url", e)
                        return true
                    }
                }
                // Don't play sound here - onPageStarted will handle it with throttling
                return false // Let WebView handle http/https URLs
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                // Don't update URL bar if we're loading an error page
                if (url?.startsWith("file:///android_asset/html/ie_error.html") == true) {
                    isLoadingErrorPage = true
                    // Keep the failed URL in the address bar
                } else {
                    isLoadingErrorPage = false
                    lastAttemptedUrl = url
                    urlEditText.setText(url ?: "")
                }

                // Update status to loading
                statusIcon?.setImageResource(R.drawable.ie_status_loading)
                statusText.text = "Locating site"

                // Mark page as not loaded
                isPageLoaded = false

                // Play sound with throttling to avoid rapid-fire sounds on redirects
                playSoundThrottled()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Update status based on whether it's an error page
                if (isLoadingErrorPage) {
                    statusIcon?.setImageResource(R.drawable.ie_status_done)
                    statusText.text = "Cannot find server"
                } else {
                    statusIcon?.setImageResource(R.drawable.ie_status_done)
                    statusText.text = "Done"
                }

                // Mark page as loaded (but only for non-error pages)
                isPageLoaded = !isLoadingErrorPage

                // Update window title with page title
                val pageTitle = view?.title
                if (!pageTitle.isNullOrEmpty() && !isLoadingErrorPage) {
                    onUpdateWindowTitle("$pageTitle - Internet Explorer")
                    tabName?.text = pageTitle
                } else {
                    onUpdateWindowTitle("Internet Explorer")
                    tabName?.text = ""
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)

                // Only show error page for main frame errors (not subresources like images, scripts, etc.)
                if (request?.isForMainFrame == true) {
                    // Load custom error page from assets
                    // onPageStarted and onPageFinished will handle URL bar and status updates
                    view?.loadUrl("file:///android_asset/html/ie_error.html")
                } else {
                    // For non-main frame errors, try to handle as intent URL if it's a non-http(s) URL
                    request?.url?.toString()?.let { url ->
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") {
                            handleUrl(url, webView)
                        }
                    }
                }
            }
        }

        // Handle file downloads by opening in external browser
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("InternetExplorerApp", "Error opening download URL in external browser: $url", e)
            }
        }

        // Determine which URL to load (initialUrl takes precedence)
        val urlToLoad = initialUrl ?: homepage

        // Load the page
        webView.loadUrl(urlToLoad)
        urlEditText.setText(urlToLoad)

        // Navigation function
        val navigateToUrl = {
            val url = urlEditText.text.toString().trim()

            // Check if the input looks like a valid URL
            val isValidUrl = url.contains(".") &&
                             !url.contains(" ") &&
                             (url.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}.*")) ||
                              url.startsWith("http://") ||
                              url.startsWith("https://"))

            val finalUrl = if (isValidUrl) {
                // It's a URL, format it
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
            } else {
                // Not a valid URL, perform Google search
                // Strip http://, https://, and trailing slashes from the search term
                val searchTerm = url
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removeSuffix("/")
                    .trim()

                "https://www.google.com/search?q=${Uri.encode(searchTerm)}"
            }

            webView.loadUrl(finalUrl)

            // Hide keyboard
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
        }

        // Select all text when URL bar is focused or clicked
        urlEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                urlEditText.selectAll()
            }
        }
//
//        urlEditText.setOnClickListener {
//            urlEditText.selectAll()
//        }

        // Handle URL input - navigate on Enter key
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                navigateToUrl()
                true
            } else {
                false
            }
        }

        // Handle Back button click
        backButton.setOnClickListener {
            if (webView.canGoBack()) {
                Helpers.performHapticFeedback(context)
                webView.goBack()
            }
        }

        // Handle Forward button click
        forwardButton.setOnClickListener {
            if (webView.canGoForward()) {
                Helpers.performHapticFeedback(context)
                webView.goForward()
            }
        }

        // Handle Go button click
        goButton?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            navigateToUrl()
        }

        // Handle Refresh button click
        refreshButton.setOnClickListener {
            Helpers.performHapticFeedback(context)
            webView.reload()
        }



        // Handle Home button click
        homeButton.setOnClickListener {
            Helpers.performHapticFeedback(context)
            webView.loadUrl(homepage)
        }

        // Handle Home button long press - set current URL as new homepage
        homeButton.setOnLongClickListener {
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl.isNotEmpty()) {
                // Save new homepage
                homepage = currentUrl
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString("ie_homepage", homepage).apply()
                onShowNotification("Homepage Changed", "Set to: $currentUrl")
                onSoundPlay()
                Helpers.performHapticFeedback(context)
            }
            true
        }

        // Handle Search button click
        searchButton?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            webView.loadUrl("https://www.google.com")
        }

        // Handle Favourites button click
        val favouritesButton = contentView.findViewById<View>(R.id.ie_favourites_button)
        favouritesButton?.setOnClickListener { view ->
            showFavouritesMenu(view)
        }

        // Handle Add to Favourites button click (IE7 layout)
        val addFavouritesButton = contentView.findViewById<View>(R.id.ie_add_favourites_button)
        addFavouritesButton?.setOnClickListener {
            onSoundPlay()
            saveCurrentSiteToFavourites()
        }
    }

    /**
     * Play sound with rate limiting (max once per second)
     */
    private fun playSoundThrottled() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSoundPlayTime >= SOUND_THROTTLE_MS) {
            onSoundPlay()
            lastSoundPlayTime = currentTime
        }
    }

    /**
     * Check if the browser can navigate back in history
     */
    fun canNavigateBack(): Boolean {
        return webView?.canGoBack() == true
    }

    /**
     * Navigate back in browser history
     */
    fun navigateBack() {
        webView?.goBack()
    }

    fun cleanup() {
        // Properly clean up WebView to free memory and resources
        webView?.apply {
            // Stop all loading
            stopLoading()

            // Clear the webview completely
            loadUrl("about:blank")

            // Remove all views
            clearHistory()
            clearFormData()

            // Destroy the webview
            destroy()
        }

        // Remove reference
        webView = null
    }

    /**
     * Show favourites menu
     */
    private fun showFavouritesMenu(anchorView: View) {
        val menuItems = mutableListOf<ContextMenuItem>()

        // Add "Save current site" option
        menuItems.add(ContextMenuItem(
            title = "Add to Favourites",
            isEnabled = true,
            action = {
                onSoundPlay()
                saveCurrentSiteToFavourites()
            }
        ))

        // Add separator
        menuItems.add(ContextMenuItem(title = "", isEnabled = false))

        // Add all favourites (newest first)
        favourites.forEach { favourite ->
            menuItems.add(ContextMenuItem(
                title = favourite.name,
                isEnabled = true,
                action = {
                    // Navigate to favourite URL
                    webView?.loadUrl(favourite.url)
                },
                subActionIcon = if (favourite.isDefault) null else R.drawable.delete_icon,
                subAction = if (favourite.isDefault) null else {
                    {
                        onSoundPlay()
                        deleteFavourite(favourite)
                    }
                }
            ))
        }

        // Position menu at bottom left of favourites button
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val x = location[0].toFloat()
        val y = location[1].toFloat() + anchorView.height
        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Save current site to favourites
     */
    private fun saveCurrentSiteToFavourites() {
        // Only allow adding to favourites if page is fully loaded
        if (!isPageLoaded) {
            return
        }

        val currentUrl = webView?.url
        val currentTitle = webView?.title

        if (currentUrl != null && currentUrl.isNotEmpty() &&
            currentUrl != "about:blank" && !currentUrl.startsWith("file://")) {

            val siteName = if (!currentTitle.isNullOrEmpty()) currentTitle else currentUrl

            // Check if this URL is already in favourites
            val existingFavourite = favourites.find { it.url == currentUrl }
            if (existingFavourite != null) {
                return
            }

            // Add new favourite at the beginning (newest first)
            val newFavourite = Favourite(
                name = siteName,
                url = currentUrl,
                isDefault = false
            )
            favourites.add(0, newFavourite)
            saveFavourites()

            // Extract domain from URL for notification
            val domain = try {
                Uri.parse(currentUrl).host ?: currentUrl
            } catch (e: Exception) {
                currentUrl
            }

            onShowNotification("Saved to Favourites", "$domain saved to IE favourites")
        }
    }

    /**
     * Delete a favourite
     */
    private fun deleteFavourite(favourite: Favourite) {
        if (!favourite.isDefault) {
            favourites.remove(favourite)
            saveFavourites()
        }
    }

    /**
     * Load favourites from SharedPreferences
     */
    private fun loadFavourites(): MutableList<Favourite> {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val favouritesJson = prefs.getString(KEY_FAVOURITES, null)

        val loadedFavourites = if (favouritesJson != null) {
            val type = object : TypeToken<MutableList<Favourite>>() {}.type
            Gson().fromJson<MutableList<Favourite>>(favouritesJson, type) ?: mutableListOf()
        } else {
            mutableListOf()
        }

        // Ensure default favourite exists and is at the end
        val defaultFavourite = loadedFavourites.find { it.isDefault }
        if (defaultFavourite == null) {
            // Add default favourite at the end
            loadedFavourites.add(Favourite(
                name = DEFAULT_FAVOURITE_NAME,
                url = DEFAULT_FAVOURITE_URL,
                isDefault = true
            ))
        }

        return loadedFavourites
    }

    /**
     * Save favourites to SharedPreferences
     */
    private fun saveFavourites() {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val favouritesJson = Gson().toJson(favourites)
        prefs.edit {
            putString(KEY_FAVOURITES, favouritesJson)
        }
    }
}
