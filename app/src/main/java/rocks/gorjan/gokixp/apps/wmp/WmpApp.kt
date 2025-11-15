package rocks.gorjan.gokixp.apps.wmp

import android.content.Context
import android.util.Log
import android.view.View
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

    private var allVideos = mutableListOf<VideoTrack>()
    private var selectedVideoIndex = -1

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        videoView = contentView.findViewById(R.id.wmp_video_player)
        playlistView = contentView.findViewById(R.id.wmp_playlist)
        playlistScrollView = contentView.findViewById(R.id.wmp_playlist_scroll)
        playPauseButton = contentView.findViewById(R.id.wmp_play_pause_toggle)


        // Set up play/pause button
        playPauseButton?.setOnClickListener {
            togglePlayPause()
        }

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
                }
                setOnCompletionListener {
                    // Video finished playing - don't loop
                    Log.d(TAG, "Video playback completed: ${video.title}")
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
                Log.d(TAG, "Video paused")
            } else {
                start()
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
     * Cleanup when app is closed
     */
    fun cleanup() {
        videoView?.stopPlayback()
        videoView = null
        playlistView = null
        playlistScrollView = null
        playPauseButton = null
        allVideos.clear()
        selectedVideoIndex = -1
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
