package rocks.gorjan.gokixp.apps.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
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
    private var pdfScrollView: NestedScrollView? = null
    private var pdfPagesContainer: LinearLayout? = null
    private var pdfDocument: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null

    // Zoom and pan for images and PDFs
    private var matrix = Matrix()
    private var savedMatrix = Matrix()
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null
    private var pdfScaleGestureDetector: ScaleGestureDetector? = null
    private var pdfGestureDetector: GestureDetector? = null
    private var currentScale = 1f
    private var currentX = 0f
    private var currentY = 0f
    private var pdfScale = 1f

    // PDF pagination
    private val loadedPages = mutableSetOf<Int>()
    private val MAX_LOADED_PAGES = 3 // Only keep 3 pages in memory at once

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Initialize PDFBox
        PDFBoxResourceLoader.init(context)

        // Get references to views
        imageView = contentView.findViewById(R.id.photos_image)
        gifView = contentView.findViewById(R.id.photos_gif)
        pdfScrollView = contentView.findViewById(R.id.pdfScrollView)
        pdfPagesContainer = contentView.findViewById(R.id.pdfPagesContainer)

        // Set up pinch-to-zoom for images
        setupImageZoom()

        // Load the image/PDF
        loadImage()

        return contentView
    }

    /**
     * Set up pinch-to-zoom for images
     */
    private fun setupImageZoom() {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Switch to matrix mode when user starts zooming
                if (imageView?.scaleType != ImageView.ScaleType.MATRIX) {
                    imageView?.scaleType = ImageView.ScaleType.MATRIX
                    // Initialize matrix to current fitCenter position
                    matrix.reset()
                    currentScale = 1f
                    currentX = 0f
                    currentY = 0f
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(0.5f, 5.0f) // Min 0.5x, max 5x zoom

                matrix.setScale(currentScale, currentScale)
                matrix.postTranslate(currentX, currentY)
                imageView?.imageMatrix = matrix
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // Only allow panning if we're in matrix mode (zoomed)
                if (imageView?.scaleType == ImageView.ScaleType.MATRIX) {
                    currentX -= distanceX
                    currentY -= distanceY

                    matrix.setScale(currentScale, currentScale)
                    matrix.postTranslate(currentX, currentY)
                    imageView?.imageMatrix = matrix
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Reset zoom on double-tap - go back to fitCenter
                currentScale = 1f
                currentX = 0f
                currentY = 0f
                matrix.reset()
                imageView?.scaleType = ImageView.ScaleType.FIT_CENTER
                return true
            }
        })

        imageView?.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    /**
     * Set up pinch-to-zoom for PDFs
     */
    private fun setupPdfZoom() {
        pdfScaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pdfScale *= detector.scaleFactor
                pdfScale = pdfScale.coerceIn(0.5f, 5.0f) // Min 0.5x, max 5x zoom

                // Apply scale to all PDF page views
                val container = pdfPagesContainer
                if (container != null) {
                    for (i in 0 until container.childCount) {
                        val pageView = container.getChildAt(i)
                        pageView.scaleX = pdfScale
                        pageView.scaleY = pdfScale
                    }
                }
                return true
            }
        })

        pdfGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Reset zoom on double-tap
                pdfScale = 1f
                val container = pdfPagesContainer
                if (container != null) {
                    for (i in 0 until container.childCount) {
                        val pageView = container.getChildAt(i)
                        pageView.scaleX = pdfScale
                        pageView.scaleY = pdfScale
                    }
                }
                return true
            }
        })

        pdfScrollView?.setOnTouchListener { _, event ->
            val handled = pdfScaleGestureDetector?.onTouchEvent(event) ?: false
            pdfGestureDetector?.onTouchEvent(event)

            // Allow scroll when not scaling
            if (!pdfScaleGestureDetector!!.isInProgress) {
                false // Let scroll view handle scrolling
            } else {
                true // Consume touch event during scaling
            }
        }
    }

    /**
     * Load and display the image, GIF, or PDF
     */
    private fun loadImage() {
        try {
            val extension = imageFile.extension.lowercase()

            when {
                extension == "pdf" -> {
                    // Show PDF scroll view, hide others
                    imageView?.visibility = View.GONE
                    gifView?.visibility = View.GONE
                    pdfScrollView?.visibility = View.VISIBLE
                    setupPdfZoom()
                    loadPdf()
                }
                extension == "gif" -> {
                    // Show GIF, hide others
                    imageView?.visibility = View.GONE
                    pdfScrollView?.visibility = View.GONE
                    gifView?.visibility = View.VISIBLE
                    gifView?.setImageURI(android.net.Uri.fromFile(imageFile))
                }
                else -> {
                    // Show regular image, hide others
                    gifView?.visibility = View.GONE
                    pdfScrollView?.visibility = View.GONE
                    imageView?.visibility = View.VISIBLE

                    // Load bitmap
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        imageView?.setImageBitmap(bitmap)
                    } else {
                        Log.e(TAG, "Failed to decode image: ${imageFile.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading file: ${imageFile.absolutePath}", e)
        }
    }

    /**
     * Load and display a PDF file with lazy-loaded pages
     */
    private fun loadPdf() {
        try {
            // Load PDF document
            pdfDocument = PDDocument.load(imageFile)
            pdfRenderer = PDFRenderer(pdfDocument!!)

            val pageCount = pdfDocument!!.numberOfPages
            if (pageCount > 0) {
                // Clear container
                pdfPagesContainer?.removeAllViews()
                loadedPages.clear()

                // Create placeholder ImageViews for each page
                for (i in 0 until pageCount) {
                    val pageView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 16 // Small gap between pages
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        tag = i // Store page index
                    }
                    pdfPagesContainer?.addView(pageView)
                }

                // Load first 3 pages immediately
                for (i in 0 until minOf(MAX_LOADED_PAGES, pageCount)) {
                    loadPage(i)
                }

                // Set up scroll listener for lazy loading
                pdfScrollView?.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    loadVisiblePages(scrollY)
                }

                Log.d(TAG, "Loaded PDF: ${imageFile.name} ($pageCount pages)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PDF: ${imageFile.absolutePath}", e)
        }
    }

    /**
     * Load a specific PDF page
     */
    private fun loadPage(pageIndex: Int) {
        if (loadedPages.contains(pageIndex)) return

        try {
            val renderer = pdfRenderer ?: return
            val pageView = pdfPagesContainer?.getChildAt(pageIndex) as? ImageView ?: return

            // Render page at moderate resolution
            val bitmap = renderer.renderImage(pageIndex, 1.5f)
            pageView.setImageBitmap(bitmap)
            loadedPages.add(pageIndex)

            Log.d(TAG, "Loaded PDF page ${pageIndex + 1}")

            // Unload pages that are too far away to save memory
            unloadDistantPages(pageIndex)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory loading page $pageIndex, trying lower quality", e)
            try {
                val renderer = pdfRenderer ?: return
                val pageView = pdfPagesContainer?.getChildAt(pageIndex) as? ImageView ?: return
                val bitmap = renderer.renderImage(pageIndex, 1.0f)
                pageView.setImageBitmap(bitmap)
                loadedPages.add(pageIndex)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to load page $pageIndex", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading page $pageIndex", e)
        }
    }

    /**
     * Load pages that are visible or near the current scroll position
     */
    private fun loadVisiblePages(scrollY: Int) {
        val scrollView = pdfScrollView ?: return
        val container = pdfPagesContainer ?: return

        val viewHeight = scrollView.height
        val visibleTop = scrollY
        val visibleBottom = scrollY + viewHeight

        // Load pages that are visible or within 1 screen above/below
        for (i in 0 until container.childCount) {
            val pageView = container.getChildAt(i)
            val pageTop = pageView.top
            val pageBottom = pageView.bottom

            // Check if page is visible or close to visible area
            if (pageBottom > visibleTop - viewHeight && pageTop < visibleBottom + viewHeight) {
                loadPage(i)
            }
        }
    }

    /**
     * Unload pages that are far from the current page to save memory
     */
    private fun unloadDistantPages(currentPage: Int) {
        if (loadedPages.size <= MAX_LOADED_PAGES) return

        val container = pdfPagesContainer ?: return
        val pagesToUnload = loadedPages.filter {
            Math.abs(it - currentPage) > MAX_LOADED_PAGES
        }

        for (pageIndex in pagesToUnload) {
            val pageView = container.getChildAt(pageIndex) as? ImageView
            pageView?.setImageBitmap(null)
            loadedPages.remove(pageIndex)
            Log.d(TAG, "Unloaded PDF page ${pageIndex + 1} to save memory")
        }
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        imageView?.setImageBitmap(null)
        gifView?.setImageDrawable(null)

        // Close PDF document
        try {
            pdfDocument?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PDF document", e)
        }

        // Clear loaded pages and unload bitmaps
        val container = pdfPagesContainer
        if (container != null) {
            for (i in 0 until container.childCount) {
                val pageView = container.getChildAt(i) as? ImageView
                pageView?.setImageBitmap(null)
            }
        }
        loadedPages.clear()

        imageView = null
        gifView = null
        pdfScrollView = null
        pdfPagesContainer = null
        pdfDocument = null
        pdfRenderer = null
        scaleGestureDetector = null
        gestureDetector = null
        pdfScaleGestureDetector = null
        pdfGestureDetector = null
    }
}
