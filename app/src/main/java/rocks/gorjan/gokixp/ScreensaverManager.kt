package rocks.gorjan.gokixp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
    private val inactivityTimeout = 30000L // 30 seconds
    private var isEnabled = true

    private val screensaverRunnable = Runnable {
        if (isEnabled) {
            showScreensaver()
        }
    }

    init {
        // Start the inactivity timer
        handler.postDelayed(screensaverRunnable, inactivityTimeout)
    }

    fun showScreensaver() {
        if (screensaverDialog?.isShowing == true) return

        val activity = context as? Activity ?: return

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
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.screensaver_pipes}")
            videoView.setVideoURI(videoUri)

            videoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true
                mediaPlayer.start()
            }

            // Start will be called in onPrepared listener

            // Tap to dismiss
            screensaverView.setOnClickListener {
                hideScreensaver()
                resetInactivityTimer()
            }

            setOnDismissListener {
                videoView.stopPlayback()
                // Show system bars when dialog is dismissed
                activity.window?.let { window ->
                    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            show()
        }
    }

    fun hideScreensaver() {
        screensaverDialog?.dismiss()
        screensaverDialog = null
    }

    fun resetInactivityTimer() {
        handler.removeCallbacks(screensaverRunnable)
        hideScreensaver()
        if (isEnabled) {
            handler.postDelayed(screensaverRunnable, inactivityTimeout)
        }
    }

    fun stopInactivityTimer() {
        handler.removeCallbacks(screensaverRunnable)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            // Restart timer when re-enabled
            resetInactivityTimer()
        } else {
            // Stop timer and hide screensaver when disabled
            stopInactivityTimer()
            hideScreensaver()
        }
    }

    fun isEnabled(): Boolean = isEnabled

    fun onDestroy() {
        stopInactivityTimer()
        hideScreensaver()
    }
}
