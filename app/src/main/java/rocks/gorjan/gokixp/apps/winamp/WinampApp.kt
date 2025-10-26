package rocks.gorjan.gokixp.apps.winamp

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import rocks.gorjan.gokixp.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.theme.ThemeManager
import java.util.Locale

/**
 * Data model for a theme
 */
data class WinampTheme(
    val themeId: Int,
    val themeName: String,
    val themePostfix: String,
    val themeTextColor: String,
    val themeBackgroundColor: String
)

/**
 * Data model for a playlist
 */
data class Playlist(
    val name: String,
    val tracks: MutableList<String> = mutableListOf() // List of file paths
)

/**
 * Winamp music player app logic and UI controller
 */
class WinampApp(
    private val context: Context,
    private val onRequestPermissions: () -> Unit,
    private val hasAudioPermission: () -> Boolean,
    private val onShowRenameDialog: (title: String, initialText: String, hint: String, onConfirm: (String) -> Unit) -> Unit,
    private val onShowConfirmDialog: (title: String, message: String, onConfirm: () -> Unit) -> Unit,
    private val contextMenuView: rocks.gorjan.gokixp.ContextMenuView
) {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "winamp_playback"
        private const val PREFS_NAME = "winamp_prefs"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_CURRENT_PLAYLIST = "current_playlist"
        private const val KEY_CURRENT_THEME = "current_theme"
        private const val ALL_LOCAL_FILES = "Local Tracks"

        // Available themes
        private val AVAILABLE_THEMES = listOf(
            WinampTheme(
                themeId = 0,
                themeName = "Default",
                themePostfix = "",
                themeTextColor = "#00FF00",
                themeBackgroundColor = "#000000"
            ),
            WinampTheme(
                themeId = 1,
                themeName = "Modern",
                themePostfix = "_modern",
                themeTextColor = "#FFFFFF",
                themeBackgroundColor = "#243c79"
            )
        )
    }

    // Media playback state
    private var mediaPlayer: MediaPlayer? = null
    private var allTracks = mutableListOf<MusicTrack>() // All local files
    private var selectedTrackIndex = -1
    private var currentPlayingTrackPath: String? = null // Track the currently playing song by path
    private var refreshTrackList: (() -> Unit)? = null

    // Playlist state
    private var playlists = mutableListOf<Playlist>()
    private var currentPlaylistIndex = 0
    private var isShowingPlaylists = true // Start with playlist view

    // Theme state
    private var currentTheme: WinampTheme = AVAILABLE_THEMES[0] // Default theme

    // Media session for notifications
    private var mediaSession: android.media.session.MediaSession? = null

    // UI references
    private var trackNameView: TextView? = null
    private var trackSecondsView: TextView? = null
    private var tabView: ImageView? = null
    private var statusView: ImageView? = null
    private var playingAnimationView: View? = null
    private var trackListView: android.widget.TableLayout? = null
    private var playlistNameView: TextView? = null
    private var backgroundView: ImageView? = null

    // Update handlers
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        trackNameView = contentView.findViewById(R.id.track_name)
        trackListView = contentView.findViewById(R.id.track_list)
        trackSecondsView = contentView.findViewById(R.id.track_seconds)
        tabView = contentView.findViewById(R.id.winamp_tab)
        statusView = contentView.findViewById(R.id.winamp_status)
        playingAnimationView = contentView.findViewById(R.id.winamp_playing_animation)
        playlistNameView = contentView.findViewById(R.id.playlist_name)
        backgroundView = contentView.findViewById(R.id.winamp_background)
        val previousButton = contentView.findViewById<View>(R.id.previous_button)
        val playButton = contentView.findViewById<View>(R.id.play_button)
        val pauseButton = contentView.findViewById<View>(R.id.pause_button)
        val stopButton = contentView.findViewById<View>(R.id.stop_button)
        val nextButton = contentView.findViewById<View>(R.id.next_button)
        val playlistAddButton = contentView.findViewById<View>(R.id.playlist_add_button)
        val playlistRemoveButton = contentView.findViewById<View>(R.id.playlist_remove_button)
        val playlistNextButton = contentView.findViewById<View>(R.id.playlist_next_button)
        val playlistPrevButton = contentView.findViewById<View>(R.id.playlist_prev_button)
        val playlistShowAllButton = contentView.findViewById<View>(R.id.playlist_show_all)
        val themeButton = contentView.findViewById<View>(R.id.theme_button)

        // Enable marquee scrolling for track name
        trackNameView?.isSelected = true

        // Initialize media session
        setupMediaSession()

        // Set up button click handlers
        previousButton.setOnClickListener {
            if (getCurrentTracks().isNotEmpty()) {
                // Check current playback position
                val currentPosition = mediaPlayer?.currentPosition ?: 0

                if (currentPosition > 5000) {
                    // More than 5 seconds played - restart current track
                    mediaPlayer?.seekTo(0)
                    trackSecondsView?.text = "00:00"
                    // Reset tab position to left
                    (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                        params.setMargins(0, params.topMargin, params.rightMargin, params.bottomMargin)
                        tabView?.layoutParams = params
                    }
                } else {
                    // Less than 5 seconds played - go to previous track
                    selectedTrackIndex = if (selectedTrackIndex <= 0) {
                        getCurrentTracks().size - 1 // Wrap to last track
                    } else {
                        selectedTrackIndex - 1
                    }
                    val title = getCurrentTracks()[selectedTrackIndex].title.uppercase()
                    trackNameView?.text = "$title     $title     $title"
                    playTrack(getCurrentTracks()[selectedTrackIndex])
                    refreshTrackList?.invoke()
                }
            }
        }

        playButton.setOnClickListener {
            if (mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
                startUpdates()
                updateMediaSessionState(true)
                statusView?.setImageResource(getThemedDrawable("winamp_playing"))
                playingAnimationView?.visibility = View.VISIBLE
                stopTimeBlink()
            } else if (selectedTrackIndex >= 0 && selectedTrackIndex < getCurrentTracks().size) {
                playTrack(getCurrentTracks()[selectedTrackIndex])
            }
        }

        pauseButton.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                updateRunnable?.let { updateHandler.removeCallbacks(it) }
                updateMediaSessionState(false)
                statusView?.setImageResource(getThemedDrawable("winamp_paused"))
                playingAnimationView?.visibility = View.GONE
                startTimeBlink()
            }
        }

        stopButton.setOnClickListener {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            updateRunnable?.let { updateHandler.removeCallbacks(it) }
            trackSecondsView?.text = "00:00"
            statusView?.setImageResource(getThemedDrawable("winamp_stopped"))
            playingAnimationView?.visibility = View.GONE
            stopTimeBlink()
            // Reset tab position
            (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.setMargins(0, params.topMargin, params.rightMargin, params.bottomMargin)
                tabView?.layoutParams = params
            }
        }

        nextButton.setOnClickListener {
            if (getCurrentTracks().isNotEmpty()) {
                selectedTrackIndex = if (selectedTrackIndex >= getCurrentTracks().size - 1) {
                    0 // Wrap to first track
                } else {
                    selectedTrackIndex + 1
                }
                val title = getCurrentTracks()[selectedTrackIndex].title.uppercase()
                trackNameView?.text = "$title     $title     $title"
                playTrack(getCurrentTracks()[selectedTrackIndex])
                refreshTrackList?.invoke()
            }
        }

        // Set up playlist button handlers
        playlistAddButton.setOnClickListener {
            onShowRenameDialog("Create Playlist", "", "Playlist name") { playlistName ->
                if (playlistName.isNotBlank() && playlistName.uppercase() != ALL_LOCAL_FILES) {
                    createPlaylist(playlistName)
                }
            }
        }

        playlistRemoveButton.setOnClickListener {
            val currentPlaylist = getCurrentPlaylist()

            // Don't allow deleting "ALL LOCAL FILES"
            if (currentPlaylist.name == ALL_LOCAL_FILES) {
                return@setOnClickListener
            }

            // Show confirmation dialog
            onShowConfirmDialog(
                "Delete Playlist",
                "Are you sure you want to delete the playlist '${currentPlaylist.name}'?"
            ) {
                deleteCurrentPlaylist()
            }
        }

        playlistNextButton.setOnClickListener {
            switchToNextPlaylist()
        }

        playlistPrevButton.setOnClickListener {
            switchToPreviousPlaylist()
        }

        playlistShowAllButton.setOnClickListener {
            togglePlaylistView()
        }

        // Set up theme button handler
        themeButton.setOnClickListener {
            cycleTheme()
        }

        // Set up draggable thumb for seeking
        setupSeekBar()

        // Load playlists from SharedPreferences
        loadPlaylists()

        // Load theme from SharedPreferences
        loadTheme()

        // Apply the loaded theme to UI
        applyTheme()

        // Check permissions and load music
        if (hasAudioPermission()) {
            // Permission already granted, load tracks immediately
            loadMusicTracks()
        } else {
            // Request storage permissions
            onRequestPermissions()
        }

        return contentView
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
                    wasPlayingBeforeDrag = mediaPlayer?.isPlaying == true
                    if (wasPlayingBeforeDrag) {
                        mediaPlayer?.pause()
                        updateRunnable?.let { updateHandler.removeCallbacks(it) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isDraggingThumb && mediaPlayer != null) {
                        seekToPosition(view, event.rawX)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Stop dragging - resume playback if it was playing before
                    isDraggingThumb = false
                    if (wasPlayingBeforeDrag) {
                        mediaPlayer?.start()
                        startUpdates()
                        wasPlayingBeforeDrag = false
                    }
                    true
                }
                else -> false
            }
        }

        // Set up click-to-seek on the scrubber track
        val scrubberTrack = tabView?.parent as? android.view.ViewGroup
        scrubberTrack?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Check if touch is on the track (not on the thumb)
                    val thumbBounds = android.graphics.Rect()
                    tabView?.getHitRect(thumbBounds)

                    // If touch is not on the thumb, handle as a track click
                    if (!thumbBounds.contains(event.x.toInt(), event.y.toInt())) {
                        mediaPlayer?.let {
                            val touchX = event.x - view.paddingLeft
                            val maxMarginPx = 270.dpToPx()
                            val newMarginPx = touchX.toInt().coerceIn(0, maxMarginPx)

                            // Update the tab position
                            (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                                params.setMargins(newMarginPx, params.topMargin, params.rightMargin, params.bottomMargin)
                                tabView?.layoutParams = params
                            }

                            // Seek the media player to the corresponding position
                            try {
                                val duration = it.duration
                                val progress = newMarginPx.toFloat() / maxMarginPx.toFloat()
                                val newPosition = (duration * progress).toInt()
                                it.seekTo(newPosition)

                                // Update time display
                                val minutes = (newPosition / 1000 / 60).toInt()
                                val seconds = (newPosition / 1000 % 60).toInt()
                                trackSecondsView?.text = String.format("%02d:%02d", minutes, seconds)
                            } catch (e: Exception) {
                                Log.e("WinampApp", "Error seeking on track click", e)
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
            val maxMarginPx = 270.dpToPx()
            val parentLeft = IntArray(2)
            parentView.getLocationOnScreen(parentLeft)
            // Account for parent's left padding
            val touchX = rawX - parentLeft[0] - parentView.paddingLeft

            // Clamp the margin between 0 and maxMargin (in pixels)
            val newMarginPx = touchX.toInt().coerceIn(0, maxMarginPx)

            // Update the tab position
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.setMargins(newMarginPx, params.topMargin, params.rightMargin, params.bottomMargin)
                view.layoutParams = params
            }

            // Seek the media player to the corresponding position
            mediaPlayer?.let { player ->
                try {
                    val duration = player.duration
                    val progress = newMarginPx.toFloat() / maxMarginPx.toFloat()
                    val newPosition = (duration * progress).toInt()
                    player.seekTo(newPosition)

                    // Update time display
                    val minutes = (newPosition / 1000 / 60).toInt()
                    val seconds = (newPosition / 1000 % 60).toInt()
                    trackSecondsView?.text = String.format("%02d:%02d", minutes, seconds)
                } catch (e: Exception) {
                    Log.e("WinampApp", "Error seeking", e)
                }
            }
        }
    }

    /**
     * Load music tracks from device storage
     */
    fun loadMusicTracks() {
        allTracks.clear()
        selectedTrackIndex = -1

        // Add DJ Mike Llama song from raw resources as first track
        try {
            val rawResourceUri = "android.resource://${context.packageName}/${R.raw.dj_mike_llama_llama_whippin_intro}"

            // Get duration of the raw file
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(context, android.net.Uri.parse(rawResourceUri))
                prepare()
            }
            val duration = mediaPlayer.duration.toLong()
            mediaPlayer.release()

            // Add as first track
            allTracks.add(MusicTrack(rawResourceUri, "DJ Mike Llama - Llama Whippin' Intro", duration))
        } catch (e: Exception) {
            Log.e("WinampApp", "Error loading DJ Mike Llama track", e)
        }

        // Query for music files using MediaStore
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.DATA,
            android.provider.MediaStore.Audio.Media.DURATION
        )

        val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME} ASC"

        try {
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn)

                    // Only add MP3 files
                    if (path.endsWith(".mp3", ignoreCase = true)) {
                        // Remove .mp3 extension from display name
                        val displayName = name.replace(".mp3", "", ignoreCase = true)
                        allTracks.add(MusicTrack(path, displayName, duration))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WinampApp", "Error loading music tracks", e)
        }

        // Update "ALL LOCAL FILES" playlist with all tracks
        updateAllLocalFilesPlaylist()

        // Initial list refresh
        refreshTrackListUI()
    }

    /**
     * Refresh the track list UI
     */
    private fun refreshTrackListUI() {
        trackListView?.removeAllViews()

        if (isShowingPlaylists) {
            // Show playlist list
            showPlaylistList()
        } else {
            // Show track list
            showTrackList()
        }
    }

    /**
     * Show the list of playlists
     */
    private fun showPlaylistList() {
        // Update playlist name display
        playlistNameView?.text = "PLAYLISTS"

        // Get theme text color
        val themeTextColor = try {
            android.graphics.Color.parseColor(currentTheme.themeTextColor)
        } catch (e: Exception) {
            0xFF00FF00.toInt() // Fallback to green
        }

        playlists.forEachIndexed { index, playlist ->
            val tableRow = android.widget.TableRow(context).apply {
                layoutParams = android.widget.TableLayout.LayoutParams(
                    android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                    android.widget.TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Playlist name
            val playlistNameView = TextView(context).apply {
                text = "${index + 1}. ${playlist.name}"
                setTextColor(if (index == currentPlaylistIndex && !isShowingPlaylists) android.graphics.Color.WHITE else themeTextColor)
                textSize = 10f
                maxLines = 1
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.micross_block)
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = android.widget.TableRow.LayoutParams(
                    0,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    1f
                )

                if (index == currentPlaylistIndex && !isShowingPlaylists) {
                    setBackgroundColor(0xFF0000BE.toInt())
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            // Touch listener for immediate visual feedback
            val touchListener = View.OnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Immediately show selection on touch down
                        playlistNameView.setBackgroundColor(0xFF0000BE.toInt())
                        playlistNameView.setTextColor(android.graphics.Color.WHITE)
                        false // Continue processing the event
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        // Restore original colors if not the current playlist
                        if (index != currentPlaylistIndex || isShowingPlaylists) {
                            playlistNameView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            playlistNameView.setTextColor(themeTextColor)
                        }
                        false // Continue processing the event
                    }
                    else -> false
                }
            }

            // Click handler for playlist selection
            val clickListener = View.OnClickListener {
                // Switch to the selected playlist
                currentPlaylistIndex = index
                isShowingPlaylists = false

                // Update selected track index based on currently playing track
                updateSelectedTrackIndex()

                savePlaylists()
                refreshTrackListUI()
            }

            playlistNameView.setOnTouchListener(touchListener)
            tableRow.setOnTouchListener(touchListener)

            playlistNameView.setOnClickListener(clickListener)
            tableRow.setOnClickListener(clickListener)

            tableRow.addView(playlistNameView)
            trackListView?.addView(tableRow)
        }
    }

    /**
     * Show the list of tracks for the current playlist
     */
    private fun showTrackList() {
        // Update playlist name display
        playlistNameView?.text = getCurrentPlaylist().name.uppercase(Locale.getDefault())

        // Get theme text color
        val themeTextColor = try {
            android.graphics.Color.parseColor(currentTheme.themeTextColor)
        } catch (e: Exception) {
            0xFF00FF00.toInt() // Fallback to green
        }

        val currentTracks = getCurrentTracks()
        currentTracks.forEachIndexed { index, track ->
            val tableRow = android.widget.TableRow(context).apply {
                layoutParams = android.widget.TableLayout.LayoutParams(
                    android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                    android.widget.TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Format duration as MM:SS
            val minutes = (track.duration / 1000 / 60).toInt()
            val seconds = (track.duration / 1000 % 60).toInt()
            val durationStr = String.format("%02d:%02d", minutes, seconds)

            // Track number and name
            val trackInfoView = TextView(context).apply {
                text = "${index + 1}. ${track.title}"
                setTextColor(if (index == selectedTrackIndex) android.graphics.Color.WHITE else themeTextColor)
                textSize = 10f
                maxLines = 1
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.micross_block)
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = android.widget.TableRow.LayoutParams(
                    0,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    1f
                )

                if (index == selectedTrackIndex) {
                    setBackgroundColor(0xFF0000BE.toInt())
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            // Duration
            val durationView = TextView(context).apply {
                text = durationStr
                setTextColor(if (index == selectedTrackIndex) android.graphics.Color.WHITE else themeTextColor)
                textSize = 10f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.micross_block)
                gravity = android.view.Gravity.END
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                layoutParams = android.widget.TableRow.LayoutParams(
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT
                )

                if (index == selectedTrackIndex) {
                    setBackgroundColor(0xFF0000BE.toInt())
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            // Touch listener for immediate visual feedback and long-press detection
            val touchListener = View.OnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Immediately show selection on touch down
                        trackInfoView.setBackgroundColor(0xFF0000BE.toInt())
                        trackInfoView.setTextColor(android.graphics.Color.WHITE)
                        durationView.setBackgroundColor(0xFF0000BE.toInt())
                        durationView.setTextColor(android.graphics.Color.WHITE)
                        false // Continue processing the event
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        // Restore original colors if not the selected track
                        if (index != selectedTrackIndex) {
                            trackInfoView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            trackInfoView.setTextColor(themeTextColor)
                            durationView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            durationView.setTextColor(themeTextColor)
                        }
                        false // Continue processing the event
                    }
                    else -> false
                }
            }

            // Click handler for track selection and playback
            val clickListener = View.OnClickListener {
                selectedTrackIndex = index
                // Update track name display (in all caps, no extension)
                // Duplicate title to ensure scrolling even for short text
                val title = track.title.uppercase()
                trackNameView?.text = "$title     $title     $title"
                // Play the selected track
                playTrack(track)
                refreshTrackListUI()
            }

            // Long-press handler for context menu
            val longClickListener = View.OnLongClickListener { view ->
                showTrackContextMenu(track, index, view)
                true
            }

            // Apply listeners to all views
            trackInfoView.setOnTouchListener(touchListener)
            durationView.setOnTouchListener(touchListener)
            tableRow.setOnTouchListener(touchListener)

            trackInfoView.setOnClickListener(clickListener)
            durationView.setOnClickListener(clickListener)
            tableRow.setOnClickListener(clickListener)

            trackInfoView.setOnLongClickListener(longClickListener)
            durationView.setOnLongClickListener(longClickListener)
            tableRow.setOnLongClickListener(longClickListener)

            tableRow.addView(trackInfoView)
            tableRow.addView(durationView)
            trackListView?.addView(tableRow)
        }
    }

    /**
     * Play a track
     */
    private fun playTrack(track: MusicTrack) {
        try {
            // Store the currently playing track path
            currentPlayingTrackPath = track.path

            // Stop and release any existing player
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            // Stop any existing update runnable
            updateRunnable?.let { updateHandler.removeCallbacks(it) }

            // Create and start new MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                // Check if this is a resource URI or a file path
                if (track.path.startsWith("android.resource://")) {
                    setDataSource(context, android.net.Uri.parse(track.path))
                } else {
                    setDataSource(track.path)
                }
                prepare()
                start()

                // Set up completion listener to auto-play next track
                setOnCompletionListener {
                    updateRunnable?.let { updateHandler.removeCallbacks(it) }
                    trackSecondsView?.text = "00:00"
                    // Reset tab position to left (0 margin)
                    (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                        params.setMargins(0, params.topMargin, params.rightMargin, params.bottomMargin)
                        tabView?.layoutParams = params
                    }

                    // Auto-play next track
                    val currentTracks = getCurrentTracks()
                    if (currentTracks.isNotEmpty() && selectedTrackIndex >= 0) {
                        selectedTrackIndex = if (selectedTrackIndex >= currentTracks.size - 1) {
                            0 // Wrap to first track
                        } else {
                            selectedTrackIndex + 1
                        }
                        val title = currentTracks[selectedTrackIndex].title.uppercase()
                        trackNameView?.text = "$title     $title     $title"
                        playTrack(currentTracks[selectedTrackIndex])
                        refreshTrackList?.invoke()
                    }
                }
            }

            // Reset tab position to left at start of new track
            (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.setMargins(0, params.topMargin, params.rightMargin, params.bottomMargin)
                tabView?.layoutParams = params
            }

            // Start updating the time and tab position
            startUpdates()

            // Update media session state
            updateMediaSessionState(true)

            // Update background to playing state
            statusView?.setImageResource(getThemedDrawable("winamp_playing"))
            playingAnimationView?.visibility = View.VISIBLE

            // Stop blinking when playing
            stopTimeBlink()

        } catch (e: Exception) {
            Log.e("WinampApp", "Error playing track: ${track.path}", e)
        }
    }

    /**
     * Start periodic updates for playback UI
     */
    private fun startUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        try {
                            val currentPosition = player.currentPosition
                            val duration = player.duration

                            // Update time display (MM:SS)
                            val minutes = (currentPosition / 1000 / 60).toInt()
                            val seconds = (currentPosition / 1000 % 60).toInt()
                            trackSecondsView?.text = String.format("%02d:%02d", minutes, seconds)

                            // Update tab position (0 to 270dp left margin = 0% to 100%)
                            if (duration > 0) {
                                val progress = currentPosition.toFloat() / duration.toFloat()
                                val maxMarginPx = 270.dpToPx()
                                val newLeftMarginPx = (maxMarginPx * progress).toInt()

                                (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                                    params.setMargins(newLeftMarginPx, params.topMargin, params.rightMargin, params.bottomMargin)
                                    tabView?.layoutParams = params
                                }
                            }

                            // Schedule next update
                            updateHandler.postDelayed(this, 100) // Update every 100ms
                        } catch (e: Exception) {
                            Log.e("WinampApp", "Error updating playback UI", e)
                        }
                    }
                }
            }
        }
        updateRunnable?.let { updateHandler.post(it) }
    }

    /**
     * Start blinking the time display (when paused)
     */
    private fun startTimeBlink() {
        // Stop any existing blink
        stopTimeBlink()

        blinkRunnable = object : Runnable {
            override fun run() {
                trackSecondsView?.let { view ->
                    // Toggle visibility
                    view.visibility = if (view.visibility == android.view.View.VISIBLE) {
                        android.view.View.INVISIBLE
                    } else {
                        android.view.View.VISIBLE
                    }

                    // Schedule next toggle
                    blinkHandler.postDelayed(this, 1000)
                }
            }
        }
        blinkRunnable?.let { blinkHandler.post(it) }
    }

    /**
     * Stop blinking the time display
     */
    private fun stopTimeBlink() {
        // Stop the blink runnable
        blinkRunnable?.let { blinkHandler.removeCallbacks(it) }
        blinkRunnable = null

        // Ensure time display is visible
        trackSecondsView?.visibility = android.view.View.VISIBLE
    }

    /**
     * Setup media session for notifications
     */
    private fun setupMediaSession() {
        // Create notification channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Winamp Playback",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing track"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Create media session
        mediaSession = android.media.session.MediaSession(context, "WinampMediaSession")

        // Set up media session callback for notification controls
        mediaSession?.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() {
                if (mediaPlayer?.isPlaying == false) {
                    mediaPlayer?.start()
                    startUpdates()
                    updateMediaSessionState(true)
                    statusView?.setImageResource(getThemedDrawable("winamp_playing"))
                    playingAnimationView?.visibility = View.VISIBLE
                    stopTimeBlink()
                }
            }

            override fun onPause() {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                    updateRunnable?.let { updateHandler.removeCallbacks(it) }
                    updateMediaSessionState(false)
                    statusView?.setImageResource(getThemedDrawable("winamp_paused"))
                    playingAnimationView?.visibility = View.GONE
                    startTimeBlink()
                }
            }

            override fun onSkipToNext() {
                val currentTracks = getCurrentTracks()
                if (currentTracks.isNotEmpty()) {
                    selectedTrackIndex = if (selectedTrackIndex >= currentTracks.size - 1) {
                        0
                    } else {
                        selectedTrackIndex + 1
                    }
                    val title = currentTracks[selectedTrackIndex].title.uppercase()
                    trackNameView?.text = "$title     $title     $title"
                    playTrack(currentTracks[selectedTrackIndex])
                    refreshTrackList?.invoke()
                }
            }

            override fun onSkipToPrevious() {
                val currentTracks = getCurrentTracks()
                if (currentTracks.isNotEmpty()) {
                    // Check current playback position
                    val currentPosition = mediaPlayer?.currentPosition ?: 0

                    if (currentPosition > 5000) {
                        // More than 5 seconds played - restart current track
                        mediaPlayer?.seekTo(0)
                        trackSecondsView?.text = "00:00"
                        // Reset tab position to left
                        (tabView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                            params.setMargins(0, params.topMargin, params.rightMargin, params.bottomMargin)
                            tabView?.layoutParams = params
                        }
                    } else {
                        // Less than 5 seconds played - go to previous track
                        selectedTrackIndex = if (selectedTrackIndex <= 0) {
                            currentTracks.size - 1
                        } else {
                            selectedTrackIndex - 1
                        }
                        val title = currentTracks[selectedTrackIndex].title.uppercase()
                        trackNameView?.text = "$title     $title     $title"
                        playTrack(currentTracks[selectedTrackIndex])
                        refreshTrackList?.invoke()
                    }
                }
            }
        })

        // Store refresh callback
        refreshTrackList = ::refreshTrackListUI
    }

    /**
     * Update media session state and notification
     */
    private fun updateMediaSessionState(isPlaying: Boolean) {
        val stateBuilder = android.media.session.PlaybackState.Builder()
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (isPlaying) android.media.session.PlaybackState.STATE_PLAYING
                else android.media.session.PlaybackState.STATE_PAUSED,
                mediaPlayer?.currentPosition?.toLong() ?: 0,
                1.0f
            )

        mediaSession?.setPlaybackState(stateBuilder.build())

        // Update metadata
        val currentTracks = getCurrentTracks()
        if (selectedTrackIndex >= 0 && selectedTrackIndex < currentTracks.size) {
            val track = currentTracks[selectedTrackIndex]
            val metadata = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Winamp")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.duration)
                .build()

            mediaSession?.setMetadata(metadata)
        }

        // Show/update notification
        showNotification(isPlaying)
    }

    /**
     * Show media playback notification
     */
    private fun showNotification(isPlaying: Boolean) {
        val currentTracks = getCurrentTracks()
        if (selectedTrackIndex < 0 || selectedTrackIndex >= currentTracks.size) return

        val track = currentTracks[selectedTrackIndex]

        // Create notification with media style
        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
            .setContentTitle(track.title)
            .setContentText("Winamp")
            .setSmallIcon(ThemeManager(context).getWinampIcon())
            .setStyle(android.app.Notification.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken))
            .setOngoing(isPlaying)
            .build()

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Hide notification
     */
    private fun hideNotification() {
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * Show context menu for a track
     */
    private fun showTrackContextMenu(track: MusicTrack, trackIndex: Int, view: View) {
        val currentPlaylist = getCurrentPlaylist()

        // Get the view's position on screen
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0].toFloat()
        val y = location[1].toFloat() + view.height / 2

        // Create context menu items
        val menuItems = mutableListOf<rocks.gorjan.gokixp.ContextMenuItem>()

        // Add "Delete from playlist" option for custom playlists
        if (currentPlaylist.name != ALL_LOCAL_FILES) {
            menuItems.add(
                rocks.gorjan.gokixp.ContextMenuItem(
                    title = "Delete from playlist",
                    isEnabled = true,
                    action = {
                        deleteTrackFromPlaylist(track, trackIndex)
                    }
                )
            )
        }

        // Add divider if we have delete option
        if (menuItems.isNotEmpty()) {
            menuItems.add(rocks.gorjan.gokixp.ContextMenuItem("", isEnabled = false))
        }

        // Add "Add to X" options for each playlist
        playlists.forEach { playlist ->
            // Skip the current playlist and "ALL LOCAL FILES"
            if (playlist.name != currentPlaylist.name && playlist.name != ALL_LOCAL_FILES) {
                // Check if track is already in this playlist
                val isInPlaylist = playlist.tracks.contains(track.path)

                menuItems.add(
                    rocks.gorjan.gokixp.ContextMenuItem(
                        title = "Add to ${playlist.name}",
                        isEnabled = !isInPlaylist,
                        action = {
                            addTrackToPlaylist(track, playlist)
                        }
                    )
                )
            }
        }

        // Show the context menu if we have items
        if (menuItems.isNotEmpty()) {
            contextMenuView.showMenu(menuItems, x, y)
        } else {
            Log.d("WinampApp", "No context menu items to show")
        }
    }

    /**
     * Add a track to a specific playlist
     */
    private fun addTrackToPlaylist(track: MusicTrack, targetPlaylist: Playlist) {
        // Can't add to "ALL LOCAL FILES"
        if (targetPlaylist.name == ALL_LOCAL_FILES) {
            Log.w("WinampApp", "Cannot manually add to ALL LOCAL FILES playlist")
            return
        }

        // Add track if not already in the playlist
        if (!targetPlaylist.tracks.contains(track.path)) {
            targetPlaylist.tracks.add(track.path)
            savePlaylists()
            Log.d("WinampApp", "Added track '${track.title}' to playlist '${targetPlaylist.name}'")
        } else {
            Log.d("WinampApp", "Track '${track.title}' already in playlist '${targetPlaylist.name}'")
        }
    }

    /**
     * Delete a track from the current playlist
     */
    private fun deleteTrackFromPlaylist(track: MusicTrack, trackIndex: Int) {
        val playlist = getCurrentPlaylist()

        // Can't delete from "ALL LOCAL FILES"
        if (playlist.name == ALL_LOCAL_FILES) {
            Log.w("WinampApp", "Cannot delete from ALL LOCAL FILES playlist")
            return
        }

        // Remove the track from the playlist
        playlist.tracks.remove(track.path)

        // If the deleted track was playing or selected, adjust the selected index
        if (trackIndex == selectedTrackIndex) {
            // If we deleted the currently playing/selected track
            selectedTrackIndex = -1
            currentPlayingTrackPath = null // Clear the currently playing track

            // Stop playback if it was playing
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            updateRunnable?.let { updateHandler.removeCallbacks(it) }
            trackSecondsView?.text = "00:00"
            statusView?.setImageResource(getThemedDrawable("winamp_stopped"))
            playingAnimationView?.visibility = View.GONE
            stopTimeBlink()
        } else if (trackIndex < selectedTrackIndex) {
            // If we deleted a track before the selected one, adjust the index
            selectedTrackIndex--
        }

        // Save and refresh
        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Deleted track '${track.title}' from playlist '${playlist.name}'")
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Stop updates
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null

        // Stop blinking
        stopTimeBlink()

        // Stop and release media player
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Hide notification
        hideNotification()

        // Release media session
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        // Clear view references
        trackListView = null
        trackNameView = null
        trackSecondsView = null
        tabView = null
        statusView = null

        // Clear track data
        allTracks.clear()
        playlists.clear()
        selectedTrackIndex = -1
        currentPlayingTrackPath = null
        refreshTrackList = null
    }

    // ========== Playlist Management Functions ==========

    /**
     * Get the current playlist
     */
    private fun getCurrentPlaylist(): Playlist {
        return playlists.getOrNull(currentPlaylistIndex) ?: Playlist(ALL_LOCAL_FILES, mutableListOf())
    }

    /**
     * Update selectedTrackIndex based on the currently playing track
     * Call this after switching playlists to restore the highlight
     */
    private fun updateSelectedTrackIndex() {
        if (currentPlayingTrackPath != null) {
            val currentTracks = getCurrentTracks()
            selectedTrackIndex = currentTracks.indexOfFirst { it.path == currentPlayingTrackPath }
            // If track is not in current playlist, set to -1
            if (selectedTrackIndex == -1) {
                // Track not found in this playlist
            }
        } else {
            selectedTrackIndex = -1
        }
    }

    /**
     * Get the tracks for the current playlist
     */
    private fun getCurrentTracks(): List<MusicTrack> {
        val playlist = getCurrentPlaylist()
        return if (playlist.name == ALL_LOCAL_FILES) {
            allTracks
        } else {
            // Filter allTracks to only include tracks in the playlist
            allTracks.filter { track -> playlist.tracks.contains(track.path) }
        }
    }

    /**
     * Load playlists from SharedPreferences
     */
    private fun loadPlaylists() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()

        // Load playlists
        val playlistsJson = prefs.getString(KEY_PLAYLISTS, null)
        if (playlistsJson != null) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            val loadedPlaylists = gson.fromJson<List<Playlist>>(playlistsJson, type)
            playlists.clear()
            playlists.addAll(loadedPlaylists)
        }

        // Ensure "ALL LOCAL FILES" playlist exists at index 0
        if (playlists.isEmpty() || playlists[0].name != ALL_LOCAL_FILES) {
            playlists.add(0, Playlist(ALL_LOCAL_FILES, mutableListOf()))
        }

        // Load current playlist index
        currentPlaylistIndex = prefs.getInt(KEY_CURRENT_PLAYLIST, 0)

        // Validate index
        if (currentPlaylistIndex >= playlists.size) {
            currentPlaylistIndex = 0
        }

        Log.d("WinampApp", "Loaded ${playlists.size} playlists, current: ${getCurrentPlaylist().name}")
    }

    /**
     * Save playlists to SharedPreferences
     */
    private fun savePlaylists() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()

        // Save playlists (excluding ALL LOCAL FILES which is auto-generated)
        val playlistsToSave = playlists.filter { it.name != ALL_LOCAL_FILES }
        val playlistsJson = gson.toJson(playlistsToSave)

        prefs.edit().apply {
            putString(KEY_PLAYLISTS, playlistsJson)
            putInt(KEY_CURRENT_PLAYLIST, currentPlaylistIndex)
            apply()
        }

        Log.d("WinampApp", "Saved ${playlistsToSave.size} playlists")
    }

    /**
     * Update the "ALL LOCAL FILES" playlist with current tracks
     */
    private fun updateAllLocalFilesPlaylist() {
        val allFilesPlaylist = playlists.find { it.name == ALL_LOCAL_FILES }
        if (allFilesPlaylist != null) {
            allFilesPlaylist.tracks.clear()
            allFilesPlaylist.tracks.addAll(allTracks.map { it.path })
        }
    }

    /**
     * Create a new playlist
     */
    private fun createPlaylist(name: String) {
        // Check if playlist with this name already exists
        if (playlists.any { it.name.equals(name, ignoreCase = true) }) {
            Log.w("WinampApp", "Playlist '$name' already exists")
            return
        }

        val newPlaylist = Playlist(name, mutableListOf())
        playlists.add(newPlaylist)

        // Switch to the new playlist
        currentPlaylistIndex = playlists.size - 1
        selectedTrackIndex = -1

        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Created playlist: $name")
    }

    /**
     * Delete the current playlist and switch to previous
     */
    private fun deleteCurrentPlaylist() {
        val playlist = getCurrentPlaylist()

        // Can't delete "ALL LOCAL FILES"
        if (playlist.name == ALL_LOCAL_FILES) {
            Log.w("WinampApp", "Cannot delete ALL LOCAL FILES playlist")
            return
        }

        playlists.removeAt(currentPlaylistIndex)

        // Switch to previous playlist (with wrapping)
        if (playlists.isEmpty()) {
            // Shouldn't happen, but add ALL LOCAL FILES back if needed
            playlists.add(Playlist(ALL_LOCAL_FILES, mutableListOf()))
            currentPlaylistIndex = 0
        } else {
            currentPlaylistIndex = if (currentPlaylistIndex > 0) {
                currentPlaylistIndex - 1
            } else {
                playlists.size - 1
            }
        }

        selectedTrackIndex = -1
        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Deleted playlist: ${playlist.name}, switched to ${getCurrentPlaylist().name}")
    }

    /**
     * Switch to the next playlist
     */
    private fun switchToNextPlaylist() {
        if (playlists.isEmpty()) return

        currentPlaylistIndex = (currentPlaylistIndex + 1) % playlists.size

        // Update selected track index based on currently playing track
        updateSelectedTrackIndex()

        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Switched to next playlist: ${getCurrentPlaylist().name}")
    }

    /**
     * Switch to the previous playlist
     */
    private fun switchToPreviousPlaylist() {
        if (playlists.isEmpty()) return

        currentPlaylistIndex = if (currentPlaylistIndex > 0) {
            currentPlaylistIndex - 1
        } else {
            playlists.size - 1
        }

        // Update selected track index based on currently playing track
        updateSelectedTrackIndex()

        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Switched to previous playlist: ${getCurrentPlaylist().name}")
    }

    /**
     * Toggle between playlist view and track view
     */
    private fun togglePlaylistView() {
        isShowingPlaylists = !isShowingPlaylists
        refreshTrackListUI()
        Log.d("WinampApp", "Toggled playlist view: ${if (isShowingPlaylists) "showing playlists" else "showing tracks"}")
    }

    // ========== Theme Management Functions ==========

    /**
     * Cycle to the next theme
     */
    private fun cycleTheme() {
        val currentIndex = AVAILABLE_THEMES.indexOfFirst { it.themeId == currentTheme.themeId }
        val nextIndex = (currentIndex + 1) % AVAILABLE_THEMES.size
        currentTheme = AVAILABLE_THEMES[nextIndex]

        saveTheme()
        applyTheme()

        Log.d("WinampApp", "Switched to theme: ${currentTheme.themeName}")
    }

    /**
     * Apply the current theme to all UI elements
     */
    private fun applyTheme() {
        // Helper function to get drawable resource ID with postfix
        fun getDrawableResource(baseName: String): Int {
            val resourceName = baseName + currentTheme.themePostfix
            val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
            return if (resourceId != 0) resourceId else {
                // Fallback to base resource if postfixed version doesn't exist
                context.resources.getIdentifier(baseName, "drawable", context.packageName)
            }
        }

        // Parse text color
        val textColor = try {
            android.graphics.Color.parseColor(currentTheme.themeTextColor)
        } catch (e: Exception) {
            0xFF00FF00.toInt() // Fallback to green
        }

        // Parse background color
        val backgroundColor = try {
            android.graphics.Color.parseColor(currentTheme.themeBackgroundColor)
        } catch (e: Exception) {
            0xFF000000.toInt() // Fallback to black
        }

        // Update background image
        backgroundView?.setImageResource(getDrawableResource("winamp_screen"))

        // Update tab/thumb image
        tabView?.setImageResource(getDrawableResource("winamp_thumb"))

        // Update status image based on current playback state
        val isPlaying = mediaPlayer?.isPlaying == true
        val statusDrawableName = when {
            isPlaying -> "winamp_playing"
            mediaPlayer != null -> "winamp_paused"
            else -> "winamp_stopped"
        }
        statusView?.setImageResource(getDrawableResource(statusDrawableName))

        // Update playing animation (GifImageView)
        (playingAnimationView as? pl.droidsonroids.gif.GifImageView)?.setImageResource(getDrawableResource("winamp_playing_animation"))

        // Update text colors
        trackNameView?.setTextColor(textColor)
        trackSecondsView?.setTextColor(textColor)
        playlistNameView?.setTextColor(textColor)

        // Update track list background and text colors
        trackListView?.setBackgroundColor(backgroundColor)

        // Refresh track list to apply text colors to all items
        refreshTrackListUI()
    }

    /**
     * Load theme from SharedPreferences
     */
    private fun loadTheme() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeId = prefs.getInt(KEY_CURRENT_THEME, 0)

        // Find theme by ID, default to first theme if not found
        currentTheme = AVAILABLE_THEMES.find { it.themeId == themeId } ?: AVAILABLE_THEMES[0]

        Log.d("WinampApp", "Loaded theme: ${currentTheme.themeName}")
    }

    /**
     * Save theme to SharedPreferences
     */
    private fun saveTheme() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_CURRENT_THEME, currentTheme.themeId)
            apply()
        }

        Log.d("WinampApp", "Saved theme: ${currentTheme.themeName}")
    }

    /**
     * Get a themed drawable resource ID by applying the current theme postfix
     */
    private fun getThemedDrawable(baseName: String): Int {
        val resourceName = baseName + currentTheme.themePostfix
        val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
        return if (resourceId != 0) resourceId else {
            // Fallback to base resource if postfixed version doesn't exist
            context.resources.getIdentifier(baseName, "drawable", context.packageName)
        }
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
