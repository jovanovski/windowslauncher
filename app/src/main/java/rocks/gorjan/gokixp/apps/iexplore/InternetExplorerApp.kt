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
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

class InternetExplorerApp(
    private val context: Context,
    private val onSoundPlay: () -> Unit,
    private val onShowNotification: (String, String) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit
) {
    private var homepage: String = "https://news.google.com"
    private var webView: WebView? = null

    fun setupApp(contentView: View, initialUrl: String? = null) {
        // Load homepage from shared preferences
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        homepage = prefs.getString("ie_homepage", "https://news.google.com") ?: "https://news.google.com"

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

                return false // Let WebView handle http/https URLs
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlEditText.setText(url ?: "")

                // Update status to loading
                statusIcon?.setImageResource(R.drawable.ie_status_loading)
                statusText.text = "Locating site"

                onSoundPlay()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Update status to done
                statusIcon?.setImageResource(R.drawable.ie_status_done)
                statusText.text = "Done"

                // Update window title with page title
                val pageTitle = view?.title
                if (!pageTitle.isNullOrEmpty()) {
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
                    view?.loadUrl("file:///android_asset/html/ie_error.html")

                    // Update status to error
                    statusIcon?.setImageResource(R.drawable.ie_status_done)
                    statusText.text = "Cannot find server"
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
                webView.goBack()
            }
        }

        // Handle Forward button click
        forwardButton.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        // Handle Go button click
        goButton?.setOnClickListener {
            navigateToUrl()
        }

        // Handle Refresh button click
        refreshButton.setOnClickListener {
            webView.reload()
        }



        // Handle Home button click
        homeButton.setOnClickListener {
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
            }
            true
        }

        // Handle Search button click
        searchButton?.setOnClickListener {
            webView.loadUrl("https://www.google.com")
        }
    }

    private fun sendKeyEvent(webView: WebView, keyCode: Int, action: Int) {
        val event = KeyEvent(action, keyCode)
        webView.dispatchKeyEvent(event)
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
}
