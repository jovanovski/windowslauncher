package rocks.gorjan.gokixp.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.zune.ZuneLibrary
import rocks.gorjan.gokixp.apps.zune.ZuneTrack
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * Zune as a car media source.
 *
 * The launcher already publishes a MediaSession while its own Zune screen is up, which is
 * enough for Android Auto to list it as something that is playing but not enough to open:
 * with no MediaBrowserService behind it the car has nothing to draw, and says "Windows
 * Launcher is open on the phone" instead. This is that missing half - a browse tree over
 * the same library the player shows, and a session the car can drive.
 *
 * It is a service rather than part of the player because the car has no launcher to talk
 * to: Zune is a view inside MainActivity, and the activity is not running when someone
 * gets into their car and picks the music.
 */
class ZuneCarMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private var player: MediaPlayer? = null

    /** What is queued, and where in it playback is. */
    private var queue: List<ZuneTrack> = emptyList()
    private var position = 0

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "ZuneCar").apply {
            setCallback(Callback())
            // Not active until it is actually playing. This process publishes two sessions
            // for the one package - the app's and this one - and anything picking a session
            // by package, a tile's transport buttons included, may take either. An idle one
            // answering "play" starts the library from the top under whatever the app was
            // already playing, which is how two songs ended up going at once.
            isActive = false
        }
        sessionToken = session.sessionToken
        report(PlaybackStateCompat.STATE_STOPPED)
        // The process's one-player rule. See ZuneAudio.
        rocks.gorjan.gokixp.apps.zune.ZuneAudio.register(this) { silence() }
    }

    override fun onDestroy() {
        rocks.gorjan.gokixp.apps.zune.ZuneAudio.unregister(this)
        player?.release()
        player = null
        session.release()
        super.onDestroy()
    }

    /**
     * Stops making sound, because the app is about to.
     *
     * Paused rather than released: the car's queue and its place in it are still here, so
     * a head unit that presses play again carries on rather than starting the library over.
     * The session steps aside with it - an inactive session is one nothing else will send
     * a transport command to by mistake.
     */
    private fun silence() {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "player would not pause", e)
        }
        report(PlaybackStateCompat.STATE_PAUSED)
        session.isActive = false
    }

    // ------------------------------------------------------------------ browsing

    /**
     * Anything on the phone may browse this.
     *
     * The library is the user's own music, and the only callers that can reach a service
     * on their phone are apps already on it - the car host among them.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // The library is a MediaStore query, which is not something to do on the thread
        // the host is waiting on.
        result.detach()
        Thread {
            val items = try {
                childrenOf(parentId)
            } catch (e: Exception) {
                Log.e(TAG, "could not build the browse list for $parentId", e)
                mutableListOf()
            }
            result.sendResult(items)
        }.start()
    }

    private fun childrenOf(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val tracks = ZuneLibrary.queryTracks(this)
        return when {
            parentId == ROOT -> mutableListOf(
                browsable(SONGS, "Songs"),
                browsable(ALBUMS, "Albums"),
                browsable(ARTISTS, "Artists")
            )

            parentId == SONGS -> tracks.map { playable(it) }.toMutableList()

            // Grouped by name rather than by id: what the driver is looking for is the
            // album, and two records with the same name are the same album to them.
            parentId == ALBUMS -> tracks.groupBy { it.album }
                .keys.filter { it.isNotBlank() }.sorted()
                .map { browsable("$ALBUM_PREFIX$it", it) }
                .toMutableList()

            parentId == ARTISTS -> tracks.groupBy { it.artist }
                .keys.filter { it.isNotBlank() }.sorted()
                .map { browsable("$ARTIST_PREFIX$it", it) }
                .toMutableList()

            parentId.startsWith(ALBUM_PREFIX) ->
                tracks.filter { it.album == parentId.removePrefix(ALBUM_PREFIX) }
                    .map { playable(it) }.toMutableList()

            parentId.startsWith(ARTIST_PREFIX) ->
                tracks.filter { it.artist == parentId.removePrefix(ARTIST_PREFIX) }
                    .map { playable(it) }.toMutableList()

            else -> mutableListOf()
        }
    }

    private fun browsable(id: String, title: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )

    private fun playable(track: ZuneTrack) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(track.id.toString())
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .setIconUri(artUri(track.albumId))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    private fun artUri(albumId: Long): Uri =
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    // ----------------------------------------------------------------- playing

    private inner class Callback : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            Thread {
                val all = ZuneLibrary.queryTracks(this@ZuneCarMediaService)
                val index = all.indexOfFirst { it.id.toString() == mediaId }
                if (index < 0) return@Thread
                queue = all
                position = index
                play(all[index])
            }.start()
        }

        override fun onPlay() {
            val p = player
            if (p != null) {
                rocks.gorjan.gokixp.apps.zune.ZuneAudio.claim(this@ZuneCarMediaService)
                session.isActive = true
                p.start()
                report(PlaybackStateCompat.STATE_PLAYING)
            } else {
                // Nothing chosen yet: the whole library, from the top, which is what a
                // bare "play" on a car screen is asking for.
                Thread {
                    val all = ZuneLibrary.queryTracks(this@ZuneCarMediaService)
                    if (all.isEmpty()) return@Thread
                    queue = all
                    position = 0
                    play(all[0])
                }.start()
            }
        }

        override fun onPause() {
            player?.pause()
            report(PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onStop() {
            player?.release()
            player = null
            report(PlaybackStateCompat.STATE_STOPPED)
            session.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        override fun onSkipToNext() = step(1)

        override fun onSkipToPrevious() = step(-1)

        override fun onSeekTo(pos: Long) {
            player?.seekTo(pos.toInt())
            report(
                if (player?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED
            )
        }
    }

    private fun step(by: Int) {
        if (queue.isEmpty()) return
        // Wraps, so the end of a queue is not a dead end on a screen nobody should be
        // looking at for long.
        position = ((position + by) % queue.size + queue.size) % queue.size
        play(queue[position])
    }

    private fun play(track: ZuneTrack) {
        try {
            // The app's player goes down before this one comes up, and the session only
            // holds itself out as live while it is actually playing. See ZuneAudio.
            rocks.gorjan.gokixp.apps.zune.ZuneAudio.claim(this)
            session.isActive = true
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@ZuneCarMediaService, track.uri)
                setOnCompletionListener { step(1) }
                prepare()
                start()
            }
            describe(track)
            report(PlaybackStateCompat.STATE_PLAYING)
        } catch (e: Exception) {
            Log.e(TAG, "could not play ${track.title}", e)
            report(PlaybackStateCompat.STATE_ERROR)
        }
    }

    private fun describe(track: ZuneTrack) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                    artUri(track.albumId).toString()
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
                .build()
        )
    }

    private fun report(state: Int) {
        val playing = state == PlaybackStateCompat.STATE_PLAYING
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, (player?.currentPosition ?: 0).toLong(), if (playing) 1f else 0f)
                .build()
        )
        if (playing) startForeground(NOTIFICATION_ID, notification()) else stopForeground(false)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val metadata = session.controller?.metadata
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.wp81_glyph_computer)
            .metroLook(this)
            .setContentTitle(metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Music")
            .setContentText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken))
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val TAG = "ZuneCar"
        const val ROOT = "root"
        const val SONGS = "songs"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val CHANNEL = "zune_car"
        const val NOTIFICATION_ID = 0x20E
    }
}
