package rocks.gorjan.gokixp.apps.photos

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.ImageView
import pl.droidsonroids.gif.GifImageView
import rocks.gorjan.gokixp.R
import java.io.File

/**
 * Photo Viewer app for viewing images and GIFs
 */
class PhotoViewerApp(
    private val context: Context,
    private val imageFile: File
) {
    companion object {
        private const val TAG = "PhotoViewerApp"
    }

    private var imageView: ImageView? = null
    private var gifView: GifImageView? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        imageView = contentView.findViewById(R.id.photos_image)
        gifView = contentView.findViewById(R.id.photos_gif)

        // Load the image
        loadImage()

        return contentView
    }

    /**
     * Load and display the image or GIF
     */
    private fun loadImage() {
        try {
            val isGif = imageFile.extension.lowercase() == "gif"

            if (isGif) {
                // Show GIF, hide regular image
                imageView?.visibility = View.GONE
                gifView?.visibility = View.VISIBLE
                gifView?.setImageURI(android.net.Uri.fromFile(imageFile))
            } else {
                // Show regular image, hide GIF
                gifView?.visibility = View.GONE
                imageView?.visibility = View.VISIBLE

                // Load bitmap
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (bitmap != null) {
                    imageView?.setImageBitmap(bitmap)
                } else {
                    Log.e(TAG, "Failed to decode image: ${imageFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image: ${imageFile.absolutePath}", e)
        }
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        imageView?.setImageBitmap(null)
        gifView?.setImageDrawable(null)
        imageView = null
        gifView = null
    }
}
