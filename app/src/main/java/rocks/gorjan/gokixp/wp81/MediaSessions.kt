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
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            // A session that appears after startUpdates needs its own track callback, or
            // its tile shows whatever was playing when the shell was built and never moves
            // on. Registrations are held so they can be taken off the *same* controller
            // objects: getActiveSessions hands back new ones every call, and unregistering
            // on a fresh object takes nothing off the old one.
            attachTrackCallbacks(controllers.orEmpty())
            dirty = true
            onChanged?.invoke()
        }

    /** The controllers [trackCallback] is currently registered on. */
    private val registered = mutableListOf<MediaController>()

    private fun attachTrackCallbacks(current: List<MediaController>) {
        for (controller in registered) {
            try {
                controller.unregisterCallback(trackCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error detaching a media callback", e)
            }
        }
        registered.clear()
        for (controller in current) {
            try {
                controller.registerCallback(trackCallback)
                registered.add(controller)
            } catch (e: Exception) {
                Log.w(TAG, "Error attaching a media callback", e)
            }
        }
    }

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
            attachTrackCallbacks(controllers())
            dirty = true
        } catch (e: SecurityException) {
            // Notification access not granted yet; tiles simply show no media.
            Log.d(TAG, "No notification access, media tiles disabled: ${e.message}")
        }
    }

    fun stopUpdates() {
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
            attachTrackCallbacks(emptyList())
            cached = emptyMap()
            dirty = true
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching media listeners", e)
        }
        onChanged = null
    }

    private val trackCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            dirty = true
            onChanged?.invoke()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            dirty = true
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (!dirty && now - readAt < REREAD_MS) return cached
        cached = read()
        readAt = now
        dirty = false
        return cached
    }

    /** The last answer, reused until something says it has changed. See [active]. */
    private var cached: Map<String, Info> = emptyMap()
    private var readAt = 0L
    private var dirty = true

    /**
     * Reads every session from the system.
     *
     * Expensive in a way that is not obvious: `controller.metadata` is a binder call that
     * hands back a fresh MediaMetadata, and reading any field of it unparcels the whole
     * Bundle - including the album art. Spotify publishes 960x960, so *every* call
     * allocated 3.5MB of bitmap, whether or not anything asked for the cover.
     *
     * The Start screen asked twice a second by way of the notification refresh, which is
     * a hundred megabytes a minute of native garbage: the Java heap stays small, so no
     * collection is provoked, and it piles up in native memory until the phone is swapping.
     * A heap dump taken during that showed 64 album covers, 63 of them already unreachable.
     *
     * So it is read when something has actually changed - a track, a playback state, a
     * session appearing or going away, all of which already have callbacks - and at most
     * once every [REREAD_MS] otherwise. Positions do not need it: a tile projects those
     * from the timestamp the session gave, which is what [Info.currentPositionMs] is for.
     */
    private fun read(): Map<String, Info> {
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
            // One entry per app, and when an app holds more than one session the one that
            // is playing wins. Our own process publishes two - the music app's and the car
            // service's - and taking whichever came last off the system's list meant the
            // tile could describe a session that was not the one making the sound.
            val existing = result[controller.packageName]
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (existing != null && existing.isPlaying && !playing) continue
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

    /**
     * Runs a transport command against the session that is actually doing the playing.
     *
     * Not simply the first session the app holds. An app may hold several - this launcher
     * itself does, one in the music app and one in the car service - and the first on the
     * system's list can easily be an idle one. Sending it "play" does not resume what the
     * user is listening to: it starts that session's own idea of playback, underneath the
     * song already going, and neither half can then stop the other.
     *
     * So: the one that is playing, or failing that the one that is paused mid-song, and
     * only then whatever is left.
     */
    private inline fun withTransport(
        packageName: String,
        action: (MediaController.TransportControls, Boolean) -> Unit
    ) {
        val forApp = controllers().filter { it.packageName == packageName }
        val controller = forApp.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: forApp.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        } ?: forApp.firstOrNull() ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        try {
            action(controller.transportControls, playing)
        } catch (e: Exception) {
            Log.w(TAG, "Transport control failed for $packageName", e)
        }
    }

    companion object {
        private const val TAG = "WP81Media"

        /**
         * How stale a snapshot may get before it is read again anyway.
         *
         * Everything that matters arrives by callback, so this is only a backstop against
         * a player that changes something without saying so. Long, because reading is what
         * costs - see [read].
         */
        private const val REREAD_MS = 15_000L
    }
}
