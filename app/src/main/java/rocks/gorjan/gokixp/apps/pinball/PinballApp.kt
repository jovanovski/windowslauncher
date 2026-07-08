package rocks.gorjan.gokixp.apps.pinball

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import rocks.gorjan.gokixp.R

/**
 * 3D Pinball Space Cadet — a self-contained web game bundled in the app's assets.
 *
 * Modeled on the Internet Explorer WebView configuration and the Midtown2 app-class
 * pattern (a plain controller that takes an inflated content view via [setupApp] and
 * releases resources in [cleanup]). The game and all of its assets live under
 * app/src/main/assets/pinball/, so relative paths inside index.htm resolve against
 * the file:///android_asset/pinball/ base URL.
 */
class PinballApp(private val context: Context) {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun setupApp(contentView: View): View {
        val webView = contentView.findViewById<WebView>(R.id.pinball_web_view)
        this.webView = webView

        // Configure WebView (same settings the Internet Explorer app uses)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.setBackgroundColor(Color.BLACK)

        // Load the bundled game; index.htm references 3DPinballSpaceCadet.js by
        // relative path, which resolves against this same asset folder.
        webView.loadUrl("file:///android_asset/pinball/index.htm")

        return contentView
    }

    /**
     * Cleanup when the window is closed. WebViews leak if not destroyed, so this
     * mirrors the Internet Explorer app's cleanup routine.
     */
    fun cleanup() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearFormData()
            destroy()
        }
        webView = null
    }
}
