package rocks.gorjan.gokixp.wp81

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import rocks.gorjan.gokixp.NotificationListenerService

/**
 * Reads whatever is playing, so a tile can show it.
 *
 * Android exposes active media sessions to a notification listener, which this launcher
 * already runs for its notification dots - so no additional permission is involved, but it
 * does mean media on tiles only works once notification access has been granted.
 *
 * Sessions are what the media controls on a lock screen are built from: they carry the
 * track metadata and the transport controls together, which is exactly what a live tile
 * wants to show.
 */
class MediaSessions(private val context: Context) {

    /** What a tile needs to know about one app's playback. */
    data class Info(
        val packageName: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean,
        /** Position at [positionUpdatedAtMs], in milliseconds. */
        val positionMs: Long,
        val positionUpdatedAtMs: Long,
        val speed: Float,
        /** Track length, or 0 when the app does not report one. */
        val durationMs: Long,
        /** The cover the app is showing in its own notification, if it published one. */
        val art: android.graphics.Bitmap? = null
    ) {
        /**
         * Where playback has reached now.
         *
         * A session reports a position along with the moment it was measured, not a running
         * clock - so a tile that simply displayed [positionMs] would freeze at whatever the
         * last update happened to say. Extrapolating from the timestamp lets the tile tick
         * every second without the app having to report every second.
         */
        fun currentPositionMs(): Long {
            if (!isPlaying) return positionMs
            val elapsed = android.os.SystemClock.elapsedRealtime() - positionUpdatedAtMs
            val projected = positionMs + (elapsed * speed).toLong()
            return if (durationMs > 0) projected.coerceIn(0, durationMs) else projected.coerceAtLeast(0)
        }
    }

    private val manager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val listenerComponent =
        ComponentName(context, NotificationListenerService::class.java)

    private var onChanged: (() -> Unit)? = null

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { onChanged?.invoke() }

    /**
     * Starts reporting session changes.
     *
     * Registering also arranges callbacks for tracks changing within a session, not just
     * sessions appearing and disappearing - otherwise a tile would show the song that was
     * playing when it was built and never move on.
     */
    fun startUpdates(onChanged: () -> Unit) {
        this.onChanged = onChanged
        try {
            manager?.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent)
            for (controller in controllers()) controller.registerCallback(trackCallback)
        } catch (e: SecurityException) {
            // Notification access not granted yet; tiles simply show no media.
            Log.d(TAG, "No notification access, media tiles disabled: ${e.message}")
        }
    }

    fun stopUpdates() {
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
            for (controller in controllers()) controller.unregisterCallback(trackCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching media listeners", e)
        }
        onChanged = null
    }

    private val trackCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            onChanged?.invoke()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onChanged?.invoke()
        }
    }

    private fun controllers(): List<MediaController> = try {
        manager?.getActiveSessions(listenerComponent).orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Everything currently holding a media session, keyed by package.
     *
     * A session with no title is skipped: some apps hold one open without anything loaded,
     * and a tile reading "unknown" is worse than the tile it replaced.
     */
    fun active(): Map<String, Info> {
        val result = mutableMapOf<String, Info>()
        for (controller in controllers()) {
            val metadata = controller.metadata ?: continue
            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?.trim().orEmpty()
            if (title.isEmpty()) continue

            val artist = listOf(
                android.media.MediaMetadata.METADATA_KEY_ARTIST,
                android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                android.media.MediaMetadata.METADATA_KEY_ALBUM
            ).firstNotNullOfOrNull { key ->
                metadata.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()

            // What the app puts on its own notification, in the order it prefers to be
            // shown: the album cover, then whatever art it has, then the small icon some
            // players offer instead. Any of them is better than a flat tile, and a player
            // that publishes none simply does not get one.
            val art = listOf(
                android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                android.media.MediaMetadata.METADATA_KEY_ART,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON
            ).firstNotNullOfOrNull { key ->
                try {
                    metadata.getBitmap(key)
                } catch (e: Exception) {
                    null
                }
            }

            val state = controller.playbackState

            // A session outliving its playback is the normal case, not a rare one: players
            // keep one open with the last track still in its metadata long after the
            // notification has gone, and a tile reading the metadata alone went on
            // advertising music that stopped an hour ago. Only a session that is doing
            // something, or paused in the middle of doing it, has anything to say.
            val live = when (state?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
                else -> false
            }
            if (!live) continue
            val actions = state?.actions ?: 0L
            result[controller.packageName] = Info(
                packageName = controller.packageName,
                title = title,
                artist = artist,
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                positionMs = state?.position ?: 0L,
                positionUpdatedAtMs = state?.lastPositionUpdateTime
                    ?: android.os.SystemClock.elapsedRealtime(),
                // A paused session reports speed 0, which would stall the projection above -
                // but it is not consulted while paused, so 1x is the safe default.
                speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                art = art,
                durationMs = metadata
                    .getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                    .coerceAtLeast(0L)
            )
        }
        return result
    }

    // ---------------------------------------------------------------- transport

    fun togglePlayPause(packageName: String) = withTransport(packageName) { transport, playing ->
        if (playing) transport.pause() else transport.play()
    }

    fun next(packageName: String) = withTransport(packageName) { transport, _ ->
        transport.skipToNext()
    }

    fun previous(packageName: String) = withTransport(packageName) { transport, _ ->
        transport.skipToPrevious()
    }

    private inline fun withTransport(
        packageName: String,
        action: (MediaController.TransportControls, Boolean) -> Unit
    ) {
        val controller = controllers().firstOrNull { it.packageName == packageName } ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        try {
            action(controller.transportControls, playing)
        } catch (e: Exception) {
            Log.w(TAG, "Transport control failed for $packageName", e)
        }
    }

    companion object {
        private const val TAG = "WP81Media"
    }
}
