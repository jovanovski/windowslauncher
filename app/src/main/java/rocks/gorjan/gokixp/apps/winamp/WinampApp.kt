package rocks.gorjan.gokixp.apps.winamp

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import rocks.gorjan.gokixp.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.winui.WinUi
import rocks.gorjan.gokixp.winui.showMenu
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Data model for a playlist
 */
data class Playlist(
    val name: String,
    val tracks: MutableList<String> = mutableListOf() // List of file paths
)

/**
 * Winamp music player app logic and UI controller
 *
 * The window itself is a [WinampSkinView] wearing a classic Winamp 2 skin - the base skin
 * Winamp 2.91 shipped with, or any `.wsz` somebody loads from skins.webamp.org.
 */
class WinampApp(
    private val context: Context,
    private val onRequestPermissions: () -> Unit,
    private val hasAudioPermission: () -> Boolean,
    private val onShowRenameDialog: (title: String, initialText: String, hint: String, onConfirm: (String) -> Unit) -> Unit,
    private val onShowConfirmDialog: (title: String, message: String, onConfirm: () -> Unit) -> Unit,
    /** Spare since the playlist's menu moved to the kit, and droppable once MainActivity stops passing it. */
    private val contextMenuView: rocks.gorjan.gokixp.ContextMenuView,
    private val fileToPlay: String? = null, // Optional file path to play on launch
    /** Opens Android's file picker for a `.wsz`; the pick comes back through [importSkin]. */
    private val onPickSkinFile: () -> Unit = {}
) {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "winamp_playback"
        private const val PREFS_NAME = "winamp_prefs"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_CURRENT_PLAYLIST = "current_playlist"
        private const val KEY_SKIN = "current_skin"
        private const val KEY_VOLUME = "volume"
        private const val KEY_BALANCE = "balance"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
        private const val ALL_LOCAL_FILES = "Local Tracks"
        private const val IDLE_TITLE = "Winamp 2.91"

        /** A skin is a few hundred kilobytes; anything past this is not one. */
        private const val MAX_SKIN_BYTES = 20 * 1024 * 1024
    }

    /**
     * The shell's own controls. Winamp's window is skin art from edge to edge, so the only
     * things that ask the kit for anything are its menus.
     */
    private val ui = WinUi(context)

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

    // Player settings
    private var volume = 1f
    private var balance = 0f
    private var shuffle = false
    private var repeat = true // The player always looped its playlist before it had the button
    private var currentSkinFile = ""

    // Media session for notifications
    private var mediaSession: android.media.session.MediaSession? = null

    // UI references
    private var skinView: WinampSkinView? = null
    private var menuAnchor: View? = null

    // Update handlers
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        skinView = contentView.findViewById(R.id.winamp_skin)
        menuAnchor = contentView.findViewById(R.id.winamp_menu_anchor)

        // Initialize media session
        setupMediaSession()

        loadSettings()
        skinView?.apply {
            skin = loadCurrentSkin()
            volume = this@WinampApp.volume
            balance = this@WinampApp.balance
            shuffle = this@WinampApp.shuffle
            repeat = this@WinampApp.repeat
            title = IDLE_TITLE
            listener = skinListener
        }

        // Load playlists from SharedPreferences
        loadPlaylists()

        // Check permissions and load music
        if (hasAudioPermission()) {
            // Permission already granted, load tracks immediately
            loadMusicTracks()
        } else {
            refreshTrackListUI()
            // Request storage permissions
            onRequestPermissions()
        }

        return contentView
    }

    private val skinListener = object : WinampSkinView.Listener {
        override fun onPrevious() = previous()
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onStop() = stop()
        override fun onNext() = next()
        override fun onEject(x: Float, y: Float) = showSkinMenu(x, y)
        override fun onSeek(fraction: Float) = seekTo(fraction)

        override fun onVolume(volume: Float) {
            this@WinampApp.volume = volume
            applyVolume()
            saveSettings()
        }

        override fun onBalance(balance: Float) {
            this@WinampApp.balance = balance
            applyVolume()
            saveSettings()
        }

        override fun onShuffle() {
            shuffle = !shuffle
            skinView?.shuffle = shuffle
            saveSettings()
        }

        override fun onRepeat() {
            repeat = !repeat
            skinView?.repeat = repeat
            saveSettings()
        }

        override fun onOptions(x: Float, y: Float) = showSkinMenu(x, y)

        override fun onRowClick(index: Int) {
            if (isShowingPlaylists) {
                // Switch to the selected playlist
                currentPlaylistIndex = index
                isShowingPlaylists = false
                // Update selected track index based on currently playing track
                updateSelectedTrackIndex()
                savePlaylists()
                refreshTrackListUI()
                skinView?.scrollToRow(selectedTrackIndex)
            } else {
                val tracks = getCurrentTracks()
                if (index !in tracks.indices) return
                selectedTrackIndex = index
                playTrack(tracks[index])
                refreshTrackListUI()
            }
        }

        override fun onRowLongClick(index: Int, x: Float, y: Float) {
            if (isShowingPlaylists) return
            val tracks = getCurrentTracks()
            if (index !in tracks.indices) return
            showTrackContextMenu(tracks[index], index, x, y)
        }

        override fun onPlaylistAdd() {
            onShowRenameDialog("Create Playlist", "", "Playlist name") { playlistName ->
                if (playlistName.isNotBlank() && playlistName.uppercase() != ALL_LOCAL_FILES) {
                    createPlaylist(playlistName)
                }
            }
        }

        override fun onPlaylistRemove() {
            val currentPlaylist = getCurrentPlaylist()
            // Don't allow deleting "ALL LOCAL FILES"
            if (currentPlaylist.name == ALL_LOCAL_FILES) return
            onShowConfirmDialog(
                "Delete Playlist",
                "Are you sure you want to delete the playlist '${currentPlaylist.name}'?"
            ) {
                deleteCurrentPlaylist()
            }
        }

        override fun onPlaylistSelect() = switchToPreviousPlaylist()
        override fun onPlaylistMisc() = switchToNextPlaylist()
        override fun onPlaylistList() = togglePlaylistView()
    }

    // ========== Transport ==========

    private fun previous() {
        val tracks = getCurrentTracks()
        if (tracks.isEmpty()) return
        // More than 5 seconds in, Previous starts the song again
        if ((mediaPlayer?.currentPosition ?: 0) > 5000) {
            mediaPlayer?.seekTo(0)
            skinView?.elapsedMs = 0
            return
        }
        selectedTrackIndex = if (selectedTrackIndex <= 0) tracks.size - 1 else selectedTrackIndex - 1
        playTrack(tracks[selectedTrackIndex])
        refreshTrackList?.invoke()
    }

    private fun next() {
        val tracks = getCurrentTracks()
        if (tracks.isEmpty()) return
        selectedTrackIndex = nextIndex(tracks.size)
        playTrack(tracks[selectedTrackIndex])
        refreshTrackList?.invoke()
    }

    private fun nextIndex(size: Int): Int = when {
        shuffle && size > 1 -> {
            var index: Int
            do { index = Random.nextInt(size) } while (index == selectedTrackIndex)
            index
        }
        selectedTrackIndex >= size - 1 -> 0
        else -> selectedTrackIndex + 1
    }

    private fun play() {
        val player = mediaPlayer
        if (player != null && !player.isPlaying) {
            player.start()
            startUpdates()
            updateMediaSessionState(true)
            skinView?.status = WinampSkinView.Status.PLAYING
        } else if (player == null) {
            val tracks = getCurrentTracks()
            if (selectedTrackIndex in tracks.indices) {
                playTrack(tracks[selectedTrackIndex])
            } else if (!isShowingPlaylists && tracks.isNotEmpty()) {
                selectedTrackIndex = 0
                playTrack(tracks[0])
                refreshTrackListUI()
            }
        }
    }

    private fun pause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            updateRunnable?.let { updateHandler.removeCallbacks(it) }
            updateMediaSessionState(false)
            skinView?.status = WinampSkinView.Status.PAUSED
        } else {
            // Winamp's Pause button also un-pauses
            play()
        }
    }

    private fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        skinView?.apply {
            status = WinampSkinView.Status.STOPPED
            hasMedia = false
            elapsedMs = 0
            stereo = null
        }
    }

    private fun seekTo(fraction: Float) {
        val player = mediaPlayer ?: return
        try {
            val position = (player.duration * fraction).toInt()
            player.seekTo(position)
            skinView?.elapsedMs = position
        } catch (e: Exception) {
            Log.e("WinampApp", "Error seeking", e)
        }
    }

    /** Volume and balance together: balance turns one side down, never the other up. */
    private fun applyVolume() {
        val left = volume * (if (balance > 0f) 1f - balance else 1f)
        val right = volume * (if (balance < 0f) 1f + balance else 1f)
        try {
            mediaPlayer?.setVolume(left, right)
        } catch (e: IllegalStateException) {
            Log.w("WinampApp", "Player not ready for volume", e)
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
                setDataSource(context, Uri.parse(rawResourceUri))
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

        // If a file was specified to play on launch, play it
        if (fileToPlay != null) {
            playSpecificFile(fileToPlay)
        }
    }

    /**
     * Refresh the playlist editor: the list of playlists, or the current playlist's tracks
     */
    private fun refreshTrackListUI() {
        val view = skinView ?: return
        if (isShowingPlaylists) {
            view.playlistName = "Playlists"
            view.rows = playlists.mapIndexed { index, playlist ->
                WinampSkinView.Row("${index + 1}. ${playlist.name}", "")
            }
            view.highlighted = -1
        } else {
            view.playlistName = getCurrentPlaylist().name.uppercase(Locale.getDefault())
            view.rows = getCurrentTracks().mapIndexed { index, track ->
                WinampSkinView.Row("${index + 1}. ${track.title}", formatTime(track.duration))
            }
            view.highlighted = selectedTrackIndex
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
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
                    setDataSource(context, Uri.parse(track.path))
                } else {
                    setDataSource(track.path)
                }
                prepare()
                start()

                // Set up completion listener to auto-play next track
                setOnCompletionListener {
                    updateRunnable?.let { updateHandler.removeCallbacks(it) }
                    val currentTracks = getCurrentTracks()
                    val atEnd = selectedTrackIndex >= currentTracks.size - 1
                    if (currentTracks.isNotEmpty() && selectedTrackIndex >= 0 && (repeat || shuffle || !atEnd)) {
                        selectedTrackIndex = nextIndex(currentTracks.size)
                        playTrack(currentTracks[selectedTrackIndex])
                        refreshTrackList?.invoke()
                    } else {
                        // Repeat is off and the playlist has run out
                        stop()
                    }
                }
            }
            applyVolume()

            showNowPlaying(track)

            // Start updating the time and seek bar
            startUpdates()

            // Update media session state
            updateMediaSessionState(true)
        } catch (e: Exception) {
            Log.e("WinampApp", "Error playing track: ${track.path}", e)
        }
    }

    /** The song title, bitrate and channels in the main window, as Winamp put them. */
    private fun showNowPlaying(track: MusicTrack) {
        val view = skinView ?: return
        val number = selectedTrackIndex + 1
        val duration = mediaPlayer?.duration?.toLong()?.takeIf { it > 0 } ?: track.duration
        view.title = "$number. ${track.title} (${formatTime(duration)})"
        view.durationMs = duration.toInt()
        view.elapsedMs = 0
        view.hasMedia = true
        view.status = WinampSkinView.Status.PLAYING

        val info = readAudioInfo(track)
        view.kbps = info.first
        view.khz = info.second
        view.stereo = info.third
    }

    /** Bitrate in kbps, sample rate in kHz, and whether it is stereo; blanks where unknown. */
    private fun readAudioInfo(track: MusicTrack): Triple<String, String, Boolean?> {
        var kbps = ""
        var khz = ""
        var stereo: Boolean? = null
        val uri = if (track.path.startsWith("android.resource://")) Uri.parse(track.path) else null
        val retriever = MediaMetadataRetriever()
        try {
            if (uri != null) retriever.setDataSource(context, uri) else retriever.setDataSource(track.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                kbps = (it / 1000).toString()
            }
        } catch (e: Exception) {
            Log.w("WinampApp", "Could not read bitrate of ${track.path}", e)
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
        val extractor = MediaExtractor()
        try {
            if (uri != null) extractor.setDataSource(context, uri, null) else extractor.setDataSource(track.path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    khz = (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000).toString()
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    stereo = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 1
                }
                break
            }
        } catch (e: Exception) {
            Log.w("WinampApp", "Could not read the format of ${track.path}", e)
        } finally {
            extractor.release()
        }
        return Triple(kbps, khz, stereo)
    }

    /**
     * Play a specific file by path
     */
    fun playSpecificFile(filePath: String) {
        // Find the track in allTracks
        val trackIndex = allTracks.indexOfFirst { it.path == filePath }

        if (trackIndex >= 0) {
            // Track found in library
            val track = allTracks[trackIndex]
            selectedTrackIndex = trackIndex

            // Switch to "ALL LOCAL FILES" playlist
            currentPlaylistIndex = 0
            isShowingPlaylists = false

            // Play the track
            playTrack(track)
            refreshTrackListUI()
            skinView?.scrollToRow(selectedTrackIndex)

            Log.d("WinampApp", "Playing file from library: ${track.title}")
        } else {
            // Track not in library - add it temporarily and play
            try {
                val file = File(filePath)
                val displayName = file.nameWithoutExtension

                // Get duration
                val tempPlayer = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepare()
                }
                val duration = tempPlayer.duration.toLong()
                tempPlayer.release()

                // Create and play track
                val track = MusicTrack(filePath, displayName, duration)

                // Add to allTracks temporarily
                allTracks.add(0, track)
                updateAllLocalFilesPlaylist()

                selectedTrackIndex = 0
                currentPlaylistIndex = 0
                isShowingPlaylists = false

                // Play the track
                playTrack(track)
                refreshTrackListUI()
                skinView?.scrollToRow(0)

                Log.d("WinampApp", "Playing external file: ${track.title}")
            } catch (e: Exception) {
                Log.e("WinampApp", "Error playing external file: $filePath", e)
            }
        }
    }

    /**
     * Start periodic updates for playback UI
     */
    private fun startUpdates() {
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        try {
                            skinView?.elapsedMs = player.currentPosition
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
                if (mediaPlayer?.isPlaying == false) play()
            }

            override fun onPause() {
                if (mediaPlayer?.isPlaying == true) pause()
            }

            override fun onSkipToNext() = next()

            override fun onSkipToPrevious() = previous()
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

    /** Opens a menu at a point on the skin, which has no views of its own to hang one from. */
    private fun showMenuAt(x: Float, y: Float, items: List<ContextMenuItem>) {
        val anchor = menuAnchor ?: return
        anchor.translationX = x
        anchor.translationY = y
        ui.showMenu(anchor, items)
    }

    /**
     * Show context menu for a track
     *
     * Winamp skinned its window but never its menus - right-clicking a track in the playlist
     * editor put up an ordinary Windows menu - so this one comes from the control kit and
     * follows the shell rather than the skin.
     */
    private fun showTrackContextMenu(track: MusicTrack, trackIndex: Int, x: Float, y: Float) {
        val currentPlaylist = getCurrentPlaylist()

        // Create context menu items
        val menuItems = mutableListOf<ContextMenuItem>()

        // Add "Delete from playlist" option for custom playlists
        if (currentPlaylist.name != ALL_LOCAL_FILES) {
            menuItems.add(
                ContextMenuItem(
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
            menuItems.add(ContextMenuItem("", isEnabled = false))
        }

        // Add "Add to X" options for each playlist
        playlists.forEach { playlist ->
            // Skip the current playlist and "ALL LOCAL FILES"
            if (playlist.name != currentPlaylist.name && playlist.name != ALL_LOCAL_FILES) {
                // Check if track is already in this playlist
                val isInPlaylist = playlist.tracks.contains(track.path)

                menuItems.add(
                    ContextMenuItem(
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
            // The desktop's menu buzzed as it opened, and a long press that produces a menu
            // without one feels broken.
            rocks.gorjan.gokixp.Helpers.performHapticFeedback(context)
            showMenuAt(x, y, menuItems)
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
            stop()
            skinView?.title = IDLE_TITLE
        } else if (trackIndex < selectedTrackIndex) {
            // If we deleted a track before the selected one, adjust the index
            selectedTrackIndex--
        }

        // Save and refresh
        savePlaylists()
        refreshTrackListUI()

        Log.d("WinampApp", "Deleted track '${track.title}' from playlist '${playlist.name}'")
    }

    // ========== Skins ==========

    /**
     * The skin menu, off the title bar's options button, the logo, or Eject: load a `.wsz`,
     * or put on one loaded before.
     */
    private fun showSkinMenu(x: Float, y: Float) {
        val items = mutableListOf<ContextMenuItem>()
        items.add(ContextMenuItem("Load skin...", action = { onPickSkinFile() }))
        items.add(ContextMenuItem("", isEnabled = false))
        items.add(
            ContextMenuItem(
                WinampSkins.BASE_NAME,
                hasCheckbox = true,
                isChecked = currentSkinFile.isEmpty(),
                action = { applySkin("") }
            )
        )
        WinampSkins.installed(context).forEach { file ->
            items.add(
                ContextMenuItem(
                    file.nameWithoutExtension,
                    hasCheckbox = true,
                    isChecked = file.name == currentSkinFile,
                    action = { applySkin(file.name) }
                )
            )
        }
        if (currentSkinFile.isNotEmpty()) {
            items.add(ContextMenuItem("", isEnabled = false))
            items.add(ContextMenuItem("Delete current skin", action = { deleteCurrentSkin() }))
        }
        showMenuAt(x, y, items)
    }

    private fun loadCurrentSkin(): WinampSkin {
        if (currentSkinFile.isNotEmpty()) {
            WinampSkins.load(context, currentSkinFile)?.let { return it }
            currentSkinFile = ""
            saveSettings()
        }
        return WinampSkins.base(context)
    }

    private fun applySkin(fileName: String) {
        currentSkinFile = fileName
        saveSettings()
        skinView?.skin = loadCurrentSkin()
    }

    private fun deleteCurrentSkin() {
        val fileName = currentSkinFile
        if (fileName.isEmpty()) return
        onShowConfirmDialog(
            "Delete Skin",
            "Are you sure you want to delete the skin '${File(fileName).nameWithoutExtension}'?"
        ) {
            WinampSkins.delete(context, fileName)
            applySkin("")
        }
    }

    /** A skin chosen in Android's file picker. */
    fun importSkin(uri: Uri) {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: "Skin"
        importSkin(name) {
            resolver.openInputStream(uri)?.use { readSkinBytes(it) }
        }
    }

    /** A `.wsz` opened from My Computer or My Briefcase. */
    fun importSkin(file: File) {
        importSkin(file.name) {
            if (file.length() > MAX_SKIN_BYTES) null else file.readBytes()
        }
    }

    private fun readSkinBytes(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_SKIN_BYTES) return null
        }
        return out.toByteArray()
    }

    /** Reads and checks the skin off the main thread, then puts it on. */
    private fun importSkin(name: String, read: () -> ByteArray?) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                val bytes = read() ?: throw IllegalArgumentException("Could not read $name")
                WinampSkins.install(context, name, bytes)
            } catch (e: Exception) {
                Log.e("WinampApp", "Could not load skin $name", e)
                null
            }
            main.post {
                if (result == null) {
                    Toast.makeText(context, "That isn't a classic Winamp skin (.wsz)", Toast.LENGTH_LONG).show()
                    return@post
                }
                currentSkinFile = result.first
                saveSettings()
                skinView?.skin = result.second
            }
        }.start()
    }

    // ========== Settings ==========

    private fun loadSettings() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentSkinFile = prefs.getString(KEY_SKIN, "") ?: ""
        volume = prefs.getFloat(KEY_VOLUME, 1f)
        balance = prefs.getFloat(KEY_BALANCE, 0f)
        shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
        repeat = prefs.getBoolean(KEY_REPEAT, true)
    }

    private fun saveSettings() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_SKIN, currentSkinFile)
            putFloat(KEY_VOLUME, volume)
            putFloat(KEY_BALANCE, balance)
            putBoolean(KEY_SHUFFLE, shuffle)
            putBoolean(KEY_REPEAT, repeat)
            apply()
        }
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Stop updates
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null

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
        skinView?.listener = null
        skinView = null
        menuAnchor = null

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

}
