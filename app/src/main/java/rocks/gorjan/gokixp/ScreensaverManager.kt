package rocks.gorjan.gokixp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class ScreensaverManager(
    private val context: Context,
    private val rootView: ViewGroup
) {
    private var screensaverDialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var inactivityTimeout = 30000L // Default 30 seconds (configurable)
    private var selectedScreensaver = SaverCatalog.DEFAULT

    // The video behind the Custom... entry, if the user has picked one
    private var customVideoUri: Uri? = null

    /**
     * Whether the soft keyboard is up. Typing is the one kind of use the launcher cannot see:
     * the taps land on the IME's own window rather than on the activity, and letters arrive
     * through the InputConnection instead of as key events. So with the keyboard open the timer
     * waits rather than dropping a screensaver over what is being typed.
     */
    private var keyboardVisible = false

    private val screensaverRunnable: Runnable = Runnable {
        if (selectedScreensaver == SaverCatalog.NONE) return@Runnable
        if (keyboardVisible) {
            // Look again in a while instead of covering the keyboard
            handler.postDelayed(screensaverRunnable, inactivityTimeout)
            return@Runnable
        }
        showScreensaver()
    }

    init {
        // Start the inactivity timer
        handler.postDelayed(screensaverRunnable, inactivityTimeout)
    }

    fun showScreensaver() {
        if (screensaverDialog?.isShowing == true) return

        val activity = context as? Activity ?: return
        val saver = SaverCatalog.byId(selectedScreensaver)
        if (saver.kind == SaverCatalog.Kind.NONE) return
        // Custom... with nothing picked yet has nothing to play
        if (saver.kind == SaverCatalog.Kind.CUSTOM_VIDEO && customVideoUri == null) return

        // Create fullscreen dialog
        screensaverDialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            val screensaverView = LayoutInflater.from(context)
                .inflate(R.layout.screensaver_overlay, null)
            setContentView(screensaverView)

            // Make dialog fullscreen and show over everything
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawableResource(android.R.color.black)
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

                // Clear any flags that might prevent fullscreen or keep screen on
                clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Hide system bars
                WindowCompat.setDecorFitsSystemWindows(this, false)
                val windowInsetsController = WindowCompat.getInsetsController(this, decorView)
                windowInsetsController.apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            // Make sure the container is visible
            screensaverView.findViewById<View>(R.id.screensaver_container)?.visibility = View.VISIBLE

            val videoView = screensaverView.findViewById<VideoView>(R.id.screensaver_video)
            val webView = screensaverView.findViewById<WebView>(R.id.screensaver_web)

            if (saver.kind == SaverCatalog.Kind.WEB) {
                videoView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                startWebSaver(webView, saver.id, preview = false)
            } else {
                webView.visibility = View.GONE
                videoView.visibility = View.VISIBLE
                startVideoSaver(videoView, saver)
            }

            // Tap to dismiss - the catcher sits over the video and the WebView both
            screensaverView.findViewById<View>(R.id.screensaver_tap_catcher).setOnClickListener {
                hideScreensaver()
                resetInactivityTimer()
            }

            setOnDismissListener {
                videoView.stopPlayback()
                releaseWebSaver(webView)
                // Show system bars when dialog is dismissed
                activity.window?.let { window ->
                    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            show()
        }
    }

    /** Plays the bundled aquarium, or whatever the user chose for Custom..., scaled to fill. */
    private fun startVideoSaver(videoView: VideoView, saver: SaverCatalog.Saver) {
        val videoUri = when (saver.kind) {
            SaverCatalog.Kind.CUSTOM_VIDEO -> customVideoUri ?: return
            else -> Uri.parse("android.resource://${context.packageName}/${R.raw.screensaver_underwater}")
        }
        videoView.setVideoURI(videoUri)

        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true

            // Scale video to fill screen (center crop)
            val videoWidth = mediaPlayer.videoWidth
            val videoHeight = mediaPlayer.videoHeight
            if (videoWidth <= 0 || videoHeight <= 0) {
                mediaPlayer.start()
                videoView.keepScreenOn = false
                return@setOnPreparedListener
            }

            // Get actual screen dimensions from display metrics
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Calculate scale to fill screen (using max instead of min for crop behavior)
            val scaleX = screenWidth.toFloat() / videoWidth
            val scaleY = screenHeight.toFloat() / videoHeight
            val scale = maxOf(scaleX, scaleY)

            // Update layout params to fill screen
            videoView.layoutParams = videoView.layoutParams.apply {
                width = (videoWidth * scale).toInt()
                height = (videoHeight * scale).toInt()
            }

            mediaPlayer.start()

            // Allow screen to turn off during screensaver
            videoView.keepScreenOn = false
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.w("ScreensaverManager", "Screensaver video would not play ($what/$extra)")
            hideScreensaver()
            true
        }
    }

    /** Whether the screensaver is currently covering the launcher. */
    fun isShowing(): Boolean = screensaverDialog?.isShowing == true

    fun hideScreensaver() {
        screensaverDialog?.dismiss()
        screensaverDialog = null
    }

    fun resetInactivityTimer() {
        handler.removeCallbacks(screensaverRunnable)
        hideScreensaver()
        if (selectedScreensaver != SaverCatalog.NONE) {
            handler.postDelayed(screensaverRunnable, inactivityTimeout)
        }
    }

    fun stopInactivityTimer() {
        handler.removeCallbacks(screensaverRunnable)
    }

    /**
     * Follows the soft keyboard opening and closing. Both count as use in themselves, and while
     * it is open the timer holds off - see [keyboardVisible].
     */
    fun setKeyboardVisible(visible: Boolean) {
        if (keyboardVisible == visible) return
        keyboardVisible = visible
        resetInactivityTimer()
    }

    fun setSelectedScreensaver(screensaverId: String) {
        selectedScreensaver = screensaverId
        if (screensaverId != SaverCatalog.NONE) {
            // Restart timer when screensaver is enabled
            resetInactivityTimer()
        } else {
            // Stop timer and hide screensaver when disabled (None selected)
            stopInactivityTimer()
            hideScreensaver()
        }
    }

    fun getSelectedScreensaver(): String = selectedScreensaver

    /** The video behind the Custom... entry. Null means the user has not picked one. */
    fun setCustomVideoUri(uri: Uri?) {
        customVideoUri = uri
    }

    fun setInactivityTimeout(timeoutSeconds: Int) {
        inactivityTimeout = timeoutSeconds * 1000L // Convert seconds to milliseconds
        // Restart timer with new timeout if screensaver is enabled
        if (selectedScreensaver != SaverCatalog.NONE) {
            resetInactivityTimer()
        }
    }

    fun getInactivityTimeout(): Int = (inactivityTimeout / 1000L).toInt() // Return in seconds

    fun onDestroy() {
        stopInactivityTimer()
        hideScreensaver()
    }

    companion object {
        /**
         * Points a WebView at one of the savers ported from winos. Also used by the Display
         * Properties preview, which has no ScreensaverManager of its own.
         *
         * A [preview] is the whole screen drawn small, inside the little monitor. It is handed
         * the real screen's width so it knows how much to shrink everything by; a full-screen
         * saver works that out from the WebView it is in.
         */
        @SuppressLint("SetJavaScriptEnabled")
        fun startWebSaver(webView: WebView, saverId: String, preview: Boolean) {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.allowFileAccess = true
            // The savers hand their textures to WebGL and read the maze's palette back out of a
            // canvas. Both count as reading one file:// URL from another, which a WebView
            // refuses unless this is on. Nothing here loads anything but the app's own assets.
            @Suppress("DEPRECATION")
            webView.settings.allowFileAccessFromFileURLs = true
            webView.setBackgroundColor(Color.BLACK)
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false

            val metrics = webView.resources.displayMetrics
            val screenWidthDp = (metrics.widthPixels / metrics.density).toInt()
            val vw = if (preview) "&vw=$screenWidthDp" else ""
            webView.loadUrl("file:///android_asset/screensavers/index.html?kind=$saverId$vw")
        }

        /**
         * Stops a saver but leaves the WebView usable, for the preview that switches from one
         * saver to the next as the list is scrolled. A hidden WebView carries on animating.
         */
        fun stopWebSaver(webView: WebView?) {
            webView?.loadUrl("about:blank")
        }

        /**
         * Done with a WebView for good. They leak if they are only dropped, and nothing may
         * touch one after destroy(), so it comes out of the layout on the way - which is also
         * what makes calling this twice harmless.
         */
        fun releaseWebSaver(webView: WebView?) {
            val parent = webView?.parent as? ViewGroup ?: return
            webView.stopLoading()
            webView.clearHistory()
            parent.removeView(webView)
            webView.destroy()
        }
    }
}
