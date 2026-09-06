package rocks.gorjan.gokixp.apps.wmp

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.VideoView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Data class for video track
 */
data class VideoTrack(
    val uri: String,
    val title: String,
    val duration: Long // Duration in milliseconds
)

/**
 * Windows Media Player app logic and UI controller
 */
class WmpApp(
    private val context: Context,
    private val onRequestPermissions: () -> Unit,
    private val hasVideoPermission: () -> Boolean,
    private val canRequestPermissions: () -> Boolean,
    private val onShowPermissionNotification: () -> Unit,
    private val fileToPlay: String? = null
) {
    companion object {
        private const val TAG = "WmpApp"
    }

    // Video playback state
    private var videoView: VideoView? = null
    private var playlistView: TableLayout? = null
    private var playlistScrollView: android.widget.ScrollView? = null
    private var playPauseButton: View? = null
    private var tabView: ImageView? = null
    private var trackView: LinearLayout? = null

    private var allVideos = mutableListOf<VideoTrack>()
    private var selectedVideoIndex = -1

    // Update handlers
    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var lastTabPosition = 0 // Track last tab position to prevent backwards movement

    // Fullscreen state
    private var isFullscreen = false
    private var originalParent: android.view.ViewGroup? = null
    private var originalLayoutParams: android.view.ViewGroup.LayoutParams? = null
    private var originalIndex: Int = -1
    private var fullscreenContainer: android.widget.FrameLayout? = null
    private var onBackPressedCallback: androidx.activity.OnBackPressedCallback? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        videoView = contentView.findViewById(R.id.wmp_video_player)
        playlistView = contentView.findViewById(R.id.wmp_playlist)
        playlistScrollView = contentView.findViewById(R.id.wmp_playlist_scroll)
        playPauseButton = contentView.findViewById(R.id.wmp_play_pause_toggle)
        tabView = contentView.findViewById(R.id.wmp_tab)
        trackView = contentView.findViewById(R.id.wmp_track)

        // Set up play/pause button
        playPauseButton?.setOnClickListener {
            togglePlayPause()
        }

        // Set up draggable tab for seeking
        setupSeekBar()

        // Set up double-tap to fullscreen on VideoView
        setupDoubleTapFullscreen()

        // Check permissions and load videos
        if (hasVideoPermission()) {
            loadVideos()

            // If a file was provided to play on launch, play it
            fileToPlay?.let { filePath ->
                playSpecificFile(filePath)
            }
        } else {
            // Check if we can request permissions
            if (canRequestPermissions()) {
                onRequestPermissions()
            } else {
                // Show notification to open settings
                onShowPermissionNotification()
            }
        }

        return contentView
    }

    /**
     * Load videos from device storage
     */
    fun loadVideos() {
        allVideos.clear()
        selectedVideoIndex = -1

        // Query for video files using MediaStore
        val projection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.DURATION
        )

        val sortOrder = "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} ASC"

        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn)

                    // Construct content URI from ID
                    val contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Remove file extension from display name
                    val displayName = name.replaceFirst(Regex("\\.[^.]+$"), "")
                    allVideos.add(VideoTrack(contentUri.toString(), displayName, duration))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos", e)
        }

        // Display the video list
        displayVideoList()
    }

    /**
     * Display the video list in the playlist view
     */
    private fun displayVideoList() {
        playlistView?.removeAllViews()

        allVideos.forEachIndexed { index, video ->
            val tableRow = TableRow(context).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Format duration as MM:SS
            val minutes = (video.duration / 1000 / 60).toInt()
            val seconds = (video.duration / 1000 % 60).toInt()
            val durationStr = String.format("%02d:%02d", minutes, seconds)

            // Video name
            val videoNameView = TextView(context).apply {
                text = "${index + 1}. ${video.title}"
                setTextColor(if (ThemeManager(context).isVistaTheme()) { if(tableRow.isSelected || index == selectedVideoIndex){ 0xFFFFFFFF.toInt() } else {0xFF000000.toInt()} } else {0xFFFFFFFF.toInt()})
                textSize = 10f
                maxLines = 1
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = TableRow.LayoutParams(
                    0,
                    TableRow.LayoutParams.WRAP_CONTENT,
                    1f
                )

                if (index == selectedVideoIndex) {
                    setBackgroundColor(0xFF0000BE.toInt())
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            // Duration
            val durationView = TextView(context).apply {
                text = durationStr
                setTextColor(if (ThemeManager(context).isVistaTheme()) { if(tableRow.isSelected || index == selectedVideoIndex){ 0xFFFFFFFF.toInt() } else {0xFF000000.toInt()} } else {0xFFFFFFFF.toInt()})
                textSize = 10f
                gravity = android.view.Gravity.END
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                )

                if (index == selectedVideoIndex) {
                    setBackgroundColor(0xFF0000BE.toInt())
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            // Touch listener for visual feedback
            val touchListener = View.OnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        videoNameView.setBackgroundColor(0xFF0000BE.toInt())
                        videoNameView.setTextColor(0xFFFFFFFF.toInt())
                        durationView.setBackgroundColor(0xFF0000BE.toInt())
                        durationView.setTextColor(0xFFFFFFFF.toInt())
                        false
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (index != selectedVideoIndex) {
                            videoNameView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            videoNameView.setTextColor(0xFF000000.toInt())
                            durationView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            durationView.setTextColor(0xFF000000.toInt())
                        }
                        false
                    }
                    else -> false
                }
            }

            // Click handler to play video
            val clickListener = View.OnClickListener {
                selectedVideoIndex = index
                playVideo(video)
                displayVideoList() // Refresh to show selection
            }

            videoNameView.setOnTouchListener(touchListener)
            durationView.setOnTouchListener(touchListener)
            tableRow.setOnTouchListener(touchListener)

            videoNameView.setOnClickListener(clickListener)
            durationView.setOnClickListener(clickListener)
            tableRow.setOnClickListener(clickListener)

            tableRow.addView(videoNameView)
            tableRow.addView(durationView)
            playlistView?.addView(tableRow)
        }
    }

    /**
     * Play a video
     */
    private fun playVideo(video: VideoTrack) {
        try {
            // Stop any existing updates immediately
            updateRunnable?.let { updateHandler.removeCallbacks(it) }
            updateRunnable = null

            // Reset tab position to left immediately
            lastTabPosition = 0
            tabView?.translationX = 0f

            videoView?.apply {
                setVideoURI(android.net.Uri.parse(video.uri))
                setOnPreparedListener { mediaPlayer ->
                    // Disable looping
                    mediaPlayer.isLooping = false

                    // Get video dimensions
                    val videoWidth = mediaPlayer.videoWidth
                    val videoHeight = mediaPlayer.videoHeight
                    val videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()

                    // Get parent container dimensions (FrameLayout)
                    val parent = this.parent as? android.view.View
                    val containerWidth = parent?.width ?: this.width
                    val containerHeight = parent?.height ?: this.height

                    // Calculate new dimensions to fit video while maintaining aspect ratio
                    val containerAspectRatio = containerWidth.toFloat() / containerHeight.toFloat()

                    val newLayoutParams = this.layoutParams
                    if (videoAspectRatio > containerAspectRatio) {
                        // Video is wider than container - fit width
                        newLayoutParams.width = containerWidth
                        newLayoutParams.height = (containerWidth / videoAspectRatio).toInt()
                    } else {
                        // Video is taller than container - fit height
                        newLayoutParams.height = containerHeight
                        newLayoutParams.width = (containerHeight * videoAspectRatio).toInt()
                    }
                    this.layoutParams = newLayoutParams
                    Log.v("GOKII", "New layout params are ${newLayoutParams.width} ${newLayoutParams.height}")

                    // Start playback
                    start()

                    // Start updating the tab position immediately
                    startUpdates()
                }
                setOnCompletionListener {
                    // Video finished playing - don't loop
                    Log.d(TAG, "Video playback completed: ${video.title}")

                    // Stop updates
                    updateRunnable?.let { updateHandler.removeCallbacks(it) }

                    // Reset tab position to left
                    lastTabPosition = 0
                    tabView?.translationX = 0f
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Error playing video: what=$what, extra=$extra")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing video: ${video.uri}", e)
        }
    }

    /**
     * Toggle play/pause
     */
    private fun togglePlayPause() {
        videoView?.apply {
            if (isPlaying) {
                pause()
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                updateRunnable = null
                Log.d(TAG, "Video paused")
            } else {
                // Stop any existing updates before starting new ones
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                updateRunnable = null
                start()
                startUpdates()
                Log.d(TAG, "Video resumed")
            }
        }
    }

    /**
     * Play a specific video file by path
     */
    fun playSpecificFile(filePath: String) {
        val videoIndex = allVideos.indexOfFirst { it.uri == filePath || android.net.Uri.parse(it.uri).path == filePath }
        if (videoIndex >= 0) {
            // Video found in library
            val video = allVideos[videoIndex]
            selectedVideoIndex = videoIndex
            playVideo(video)
            displayVideoList()
            scrollToSelectedVideo()
        } else {
            // Video not in library - add temporarily and play
            val file = java.io.File(filePath)
            if (file.exists()) {
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    0 // This won't work for external files, we'll use file URI
                )
                val fileUri = android.net.Uri.fromFile(file)
                val displayName = file.name.replaceFirst(Regex("\\.[^.]+$"), "")

                // Get duration if possible
                val retriever = android.media.MediaMetadataRetriever()
                var duration = 0L
                try {
                    retriever.setDataSource(context, fileUri)
                    val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durationStr?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting video duration", e)
                } finally {
                    retriever.release()
                }

                val tempVideo = VideoTrack(fileUri.toString(), displayName, duration)
                allVideos.add(0, tempVideo)
                selectedVideoIndex = 0
                playVideo(tempVideo)
                displayVideoList()
                scrollToSelectedVideo()
            }
        }
    }

    /**
     * Scroll to the currently selected video in the playlist
     */
    private fun scrollToSelectedVideo() {
        if (selectedVideoIndex < 0) return

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            playlistView?.let { tableLayout ->
                if (selectedVideoIndex < tableLayout.childCount) {
                    val selectedRow = tableLayout.getChildAt(selectedVideoIndex)
                    selectedRow?.let { row ->
                        val scrollY = row.top - (playlistScrollView?.height ?: 0) / 2 + row.height / 2
                        playlistScrollView?.smoothScrollTo(0, scrollY.coerceAtLeast(0))
                    }
                }
            }
        }, 100)
    }

    /**
     * Set up seek bar dragging and clicking
     */
    private fun setupSeekBar() {
        var isDraggingThumb = false
        var wasPlayingBeforeDrag = false

        tabView?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start dragging - pause playback if playing
                    isDraggingThumb = true
                    wasPlayingBeforeDrag = videoView?.isPlaying == true
                    if (wasPlayingBeforeDrag) {
                        videoView?.pause()
                        updateRunnable?.let { updateHandler.removeCallbacks(it) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isDraggingThumb && videoView != null) {
                        seekToPosition(view, event.rawX)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Stop dragging - resume playback if it was playing before
                    isDraggingThumb = false
                    if (wasPlayingBeforeDrag) {
                        videoView?.start()
                        startUpdates()
                        wasPlayingBeforeDrag = false
                    }
                    true
                }
                else -> false
            }
        }

        // Set up click-to-seek on the scrubber track
        trackView?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Check if touch is on the track (not on the thumb)
                    val thumbBounds = android.graphics.Rect()
                    tabView?.getHitRect(thumbBounds)

                    // If touch is not on the thumb, handle as a track click
                    if (!thumbBounds.contains(event.x.toInt(), event.y.toInt())) {
                        videoView?.let { vv ->
                            if (vv.duration > 0) {
                                val touchX = event.x - view.paddingLeft
                                val trackWidth = view.width - view.paddingLeft - view.paddingRight
                                val tabWidth = tabView?.width ?: 0
                                val maxMarginPx = trackWidth - tabWidth
                                val newMarginPx = touchX.toInt().coerceIn(0, maxMarginPx)

                                // Update last tab position to allow seeking in any direction
                                lastTabPosition = newMarginPx

                                // Update the tab position using translationX for better performance
                                tabView?.translationX = newMarginPx.toFloat()

                                // Seek the video player to the corresponding position
                                try {
                                    val progress = newMarginPx.toFloat() / maxMarginPx.toFloat()
                                    val newPosition = (vv.duration * progress).toInt()
                                    vv.seekTo(newPosition)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error seeking on track click", e)
                                }
                            }
                        }
                        true
                    } else {
                        // Touch is on thumb, let thumb's touch listener handle it
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * Seek to a position based on touch X coordinate
     */
    private fun seekToPosition(view: View, rawX: Float) {
        val parent = view.parent as? android.view.ViewGroup
        parent?.let { parentView ->
            // Calculate the new left margin based on touch position
            val trackWidth = parentView.width - parentView.paddingLeft - parentView.paddingRight
            val tabWidth = view.width
            val maxMarginPx = trackWidth - tabWidth

            val parentLeft = IntArray(2)
            parentView.getLocationOnScreen(parentLeft)
            // Account for parent's left padding
            val touchX = rawX - parentLeft[0] - parentView.paddingLeft

            // Clamp the margin between 0 and maxMargin (in pixels)
            val newMarginPx = touchX.toInt().coerceIn(0, maxMarginPx)

            // Update last tab position to allow seeking in any direction
            lastTabPosition = newMarginPx

            // Update the tab position using translationX for better performance
            view.translationX = newMarginPx.toFloat()

            // Seek the video player to the corresponding position
            videoView?.let { vv ->
                try {
                    if (vv.duration > 0) {
                        val progress = newMarginPx.toFloat() / maxMarginPx.toFloat()
                        val newPosition = (vv.duration * progress).toInt()
                        vv.seekTo(newPosition)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error seeking", e)
                }
            }
        }
    }

    /**
     * Start periodic updates for playback UI
     */
    private fun startUpdates() {
        // Stop any existing update runnable first
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null

        // Ensure track view is laid out before starting updates
        trackView?.let { track ->
            if (track.width <= 0) {
                // View not laid out yet, try again after a short delay
                updateHandler.postDelayed({
                    startUpdates()
                }, 50)
                return
            }
        }

        updateRunnable = object : Runnable {
            override fun run() {
                videoView?.let { vv ->
                    if (vv.isPlaying) {
                        try {
                            val currentPosition = vv.currentPosition
                            val duration = vv.duration

                            // Update tab position
                            if (duration > 0) {
                                val progress = currentPosition.toFloat() / duration.toFloat()

                                // Calculate max margin based on track width and tab width
                                trackView?.let { track ->
                                    val trackWidth = track.width - track.paddingLeft - track.paddingRight
                                    val tabWidth = tabView?.width ?: 0
                                    if (trackWidth > 0 && tabWidth > 0) {
                                        val maxMarginPx = trackWidth - tabWidth
                                        val newLeftMarginPx = (maxMarginPx * progress).toInt().coerceAtLeast(0)

                                        // Allow small backwards movement (tolerance of 3px) but prevent large jumps
                                        // This handles natural timing variations while preventing the initial stutter
                                        val backwardTolerance = 3
                                        if (newLeftMarginPx >= lastTabPosition - backwardTolerance && newLeftMarginPx != lastTabPosition) {
                                            // Only update if position has actually changed
                                            lastTabPosition = newLeftMarginPx
                                            // Use translationX for better performance (doesn't trigger layout)
                                            tabView?.translationX = newLeftMarginPx.toFloat()
                                        }
                                    }
                                }
                            }

                            // Schedule next update
                            updateHandler.postDelayed(this, 100) // Update every 100ms
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating playback UI", e)
                        }
                    }
                }
            }
        }
        updateRunnable?.let { updateHandler.post(it) }
    }

    /**
     * Set up double-tap to fullscreen on VideoView
     */
    private fun setupDoubleTapFullscreen() {
        val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                Log.d(TAG, "Double tap detected!")
                toggleFullscreen()
                return true
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                // Allow single tap to pass through (for play/pause if needed)
                return false
            }
        })

        // Get the video container (FrameLayout that contains the VideoView)
        val videoContainer = videoView?.parent as? View

        // Attach listener to the container instead of VideoView itself
        videoContainer?.setOnTouchListener { view, event ->
            val result = gestureDetector.onTouchEvent(event)
            Log.d(TAG, "Touch event on container: action=${event.action}, result=$result")
            // Return true to consume the event so double-tap works
            true
        }
    }

    /**
     * Toggle fullscreen mode for the VideoView
     */
    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    /**
     * Enter fullscreen mode
     */
    private fun enterFullscreen() {
        videoView?.let { vv ->
            // Save original parent and layout params
            originalParent = vv.parent as? android.view.ViewGroup
            originalLayoutParams = vv.layoutParams
            originalIndex = originalParent?.indexOfChild(vv) ?: -1

            // Remove from current parent
            originalParent?.removeView(vv)

            // Create gesture detector for fullscreen mode
            val fullscreenGestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    Log.d(TAG, "Double tap detected in fullscreen!")
                    exitFullscreen()
                    return true
                }
            })

            // Create fullscreen container
            val activity = context as? android.app.Activity ?: return
            fullscreenContainer = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )

                // Make it clickable to intercept all touches
                isClickable = true
                isFocusable = true

                // Set up touch listener to capture all touches and detect double-tap
                setOnTouchListener { _, event ->
                    fullscreenGestureDetector.onTouchEvent(event)
                    // Always return true to consume all touch events
                    true
                }

                // Add VideoView to fullscreen container
                addView(vv, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                ))
            }

            // Add fullscreen container to activity's content view
            val rootView = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            rootView.addView(fullscreenContainer)

            // Hide system UI using modern API
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                activity.window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            // Set up back press handler
            val componentActivity = activity as? androidx.activity.ComponentActivity
            onBackPressedCallback = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    exitFullscreen()
                }
            }
            componentActivity?.onBackPressedDispatcher?.addCallback(onBackPressedCallback!!)

            isFullscreen = true
        }
    }

    /**
     * Exit fullscreen mode
     */
    private fun exitFullscreen() {
        videoView?.let { vv ->
            // Remove from fullscreen container
            fullscreenContainer?.removeView(vv)

            // Remove fullscreen container from activity
            val activity = context as? android.app.Activity ?: return
            val rootView = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            rootView.removeView(fullscreenContainer)
            fullscreenContainer = null

            // Restore to original parent
            originalParent?.addView(vv, originalIndex, originalLayoutParams)

            // Show system UI using modern API
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                activity.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }

            // Remove back press callback
            onBackPressedCallback?.remove()
            onBackPressedCallback = null

            isFullscreen = false
        }
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Exit fullscreen if active
        if (isFullscreen) {
            exitFullscreen()
        }

        // Stop updates
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null

        videoView?.stopPlayback()
        videoView = null
        playlistView = null
        playlistScrollView = null
        playPauseButton = null
        tabView = null
        trackView = null
        allVideos.clear()
        selectedVideoIndex = -1

        // Clean up fullscreen state
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
        fullscreenContainer = null
        originalParent = null
        originalLayoutParams = null
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
