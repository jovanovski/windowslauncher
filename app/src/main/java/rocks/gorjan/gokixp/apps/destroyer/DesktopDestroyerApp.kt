package rocks.gorjan.gokixp.apps.destroyer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.ViewGroup
import java.util.Locale

/**
 * Desktop Destroyer (Gemtree's "Desktop Games", stress-reducer-desktop-destroyer.exe).
 *
 * The original photographed the Windows desktop and let you wreck the photo full-screen.
 * This does the same to the launcher: it copies what is on screen, then lays the game
 * over the whole window. It is not a floating window, since the original never was one.
 */
class DesktopDestroyerApp(
    private val activity: Activity,
    private val onClosed: () -> Unit,
) {
    private var view: DesktopDestroyerView? = null
    private var closed = false

    fun launch() {
        // Give a start menu that launched us the moment it needs to get off the screen.
        Handler(Looper.getMainLooper()).postDelayed({ capture() }, CAPTURE_DELAY_MS)
    }

    private fun capture() {
        if (closed) return
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            close()
            return
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) drawFallback(bitmap)
                attach(bitmap)
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "PixelCopy unavailable, drawing the desktop instead", e)
            drawFallback(bitmap)
            attach(bitmap)
        }
    }

    private fun drawFallback(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        try {
            activity.window.decorView.draw(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "Could not draw the desktop", e)
            canvas.drawColor(Color.rgb(0, 128, 128))
        }
    }

    private fun attach(snapshot: Bitmap) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val czech = Locale.getDefault().language in setOf("cs", "sk", "pl")
        val game = DesktopDestroyerView(activity, snapshot, czech, onExit = { close() })
        view = game
        (activity.window.decorView as ViewGroup).addView(
            game, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** Back press handling; true when the game took it. */
    fun onBackPressed(): Boolean {
        if (closed) return false
        view?.handleBack()
        return true
    }

    fun pause() {
        view?.pause()
    }

    fun resume() {
        view?.resume()
    }

    fun close() {
        if (closed) return
        closed = true
        view?.let { game ->
            game.release()
            (game.parent as? ViewGroup)?.removeView(game)
        }
        view = null
        onClosed()
    }

    companion object {
        private const val TAG = "DesktopDestroyer"
        private const val CAPTURE_DELAY_MS = 250L
    }
}
