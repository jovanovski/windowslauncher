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
    private var inactivityTimeout = 30000L // Default 30 seconds (configurable)
    private var selectedScreensaver = 1 // Default to 3D Pipes

    // Screensaver types
    companion object {
        const val SCREENSAVER_NONE = 0
        const val SCREENSAVER_3D_PIPES = 1
        const val SCREENSAVER_UNDERWATER = 2
    }

    private val screensaverRunnable = Runnable {
        if (selectedScreensaver != SCREENSAVER_NONE) {
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
            val videoResource = when (selectedScreensaver) {
                SCREENSAVER_3D_PIPES -> R.raw.screensaver_pipes
                SCREENSAVER_UNDERWATER -> R.raw.screensaver_underwater
                else -> R.raw.screensaver_pipes // Default fallback
            }
            val videoUri = Uri.parse("android.resource://${context.packageName}/${videoResource}")
            videoView.setVideoURI(videoUri)

            videoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true

                // Scale video to fill screen (center crop)
                val videoWidth = mediaPlayer.videoWidth
                val videoHeight = mediaPlayer.videoHeight
                val screenWidth = videoView.width
                val screenHeight = videoView.height

                // Calculate scale to fill screen (using max instead of min for crop behavior)
                val scaleX = screenWidth.toFloat() / videoWidth
                val scaleY = screenHeight.toFloat() / videoHeight
                val scale = maxOf(scaleX, scaleY)

                val scaledWidth = (videoWidth * scale).toInt()
                val scaledHeight = (videoHeight * scale).toInt()

                // Update layout params to fill screen
                videoView.layoutParams = videoView.layoutParams.apply {
                    width = scaledWidth
                    height = scaledHeight
                }

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
        if (selectedScreensaver != SCREENSAVER_NONE) {
            handler.postDelayed(screensaverRunnable, inactivityTimeout)
        }
    }

    fun stopInactivityTimer() {
        handler.removeCallbacks(screensaverRunnable)
    }

    fun setSelectedScreensaver(screensaverType: Int) {
        selectedScreensaver = screensaverType
        if (screensaverType != SCREENSAVER_NONE) {
            // Restart timer when screensaver is enabled
            resetInactivityTimer()
        } else {
            // Stop timer and hide screensaver when disabled (None selected)
            stopInactivityTimer()
            hideScreensaver()
        }
    }

    fun getSelectedScreensaver(): Int = selectedScreensaver

    fun setInactivityTimeout(timeoutSeconds: Int) {
        inactivityTimeout = timeoutSeconds * 1000L // Convert seconds to milliseconds
        // Restart timer with new timeout if screensaver is enabled
        if (selectedScreensaver != SCREENSAVER_NONE) {
            resetInactivityTimer()
        }
    }

    fun getInactivityTimeout(): Int = (inactivityTimeout / 1000L).toInt() // Return in seconds

    fun onDestroy() {
        stopInactivityTimer()
        hideScreensaver()
    }
}
