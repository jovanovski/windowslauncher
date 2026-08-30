package rocks.gorjan.gokixp.apps.zune

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.MetroSlider
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.util.Locale

/** One song from the device's music library. */
data class ZuneTrack(
    val id: Long,
    val uri: Uri,
    /** Where the file is. Only needed because the shared playlists are keyed by path. */
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    /** When the file landed on the phone, in seconds. What "new" is sorted on. */
    val addedAt: Long
)

/**
 * Zune, the music player that shipped with Windows Phone.
 *
 * Plays the same thing Winamp here plays - whatever MediaStore knows about - and is
 * deliberately nothing like it to look at. Winamp is a skin: a fixed bitmap with controls
 * painted into it, sized to the pixel. Zune is the opposite idea, and the one Windows
 * Phone was built on: enormous light type, most of the screen given to nothing at all, no
 * chrome, and the album art bled across the background rather than boxed in a frame.
 *
 * Laid out as a panorama of six sections, which is how the phone's music app was laid out
 * and the reason it felt like a place rather than a screen: the app is wider than the
 * display, the next section's name is already visible at the right edge, and you get
 * there by pushing the page rather than by aiming at a tab.
 *
 * Built in code rather than as a layout because it is all type and space, and because the
 * sizes come from the palette and the screen rather than being fixed in dp.
 */
class ZuneApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val onRequestPermissions: () -> Unit,
    private val hasAudioPermission: () -> Boolean
) {

    // --- Library and queue --------------------------------------------------------------
    private var library: List<ZuneTrack> = emptyList()

    /**
     * What is actually playing through, and where in it.
     *
     * Not the library: starting an album or an artist replaces the queue with those songs,
     * so "next" means the next song on the record rather than the next one alphabetically
     * in everything you own.
     */
    private var queue: List<ZuneTrack> = emptyList()

    /**
     * The order the queue is played in, as positions into it.
     *
     * The queue keeps the order the record is in - which is what the list on screen shows,
     * and what "the next song" means to anyone reading it - and shuffling reorders this
     * instead. Shuffling by picking at random on each skip would mean no previous, and
     * songs coming round twice before others came round at all.
     */
    private var order: List<Int> = emptyList()

    /** Where in [order] playback is. */
    private var orderPos = -1

    private var shuffle = false
    private var repeat = false

    private val favourites = mutableSetOf<Long>()

    // --- Playback -----------------------------------------------------------------------
    private var player: MediaPlayer? = null
    private var isPlaying = false
    private var mediaSession: android.media.session.MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressTick: Runnable? = null
    private var lastSeekAt = 0L

    // --- Views --------------------------------------------------------------------------
    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var scrim: View
    private lateinit var panorama: MetroPanorama

    private lateinit var artView: ImageView
    private lateinit var artPlaceholder: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npHeart: TextView
    private lateinit var npPosition: TextView
    private lateinit var npDuration: TextView
    private lateinit var seek: MetroSlider
    private lateinit var playPause: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var repeatButton: ImageView

    /**
     * The two faces of the now playing page: the record, and the list it came from.
     *
     * Tapping the cover turns one into the other, which is what the phone's player did -
     * the art is the whole screen until you want to know what is coming, and then the
     * whole screen is the queue.
     */
    private lateinit var npDetails: LinearLayout
    private lateinit var queueScroll: ScrollView
    private lateinit var queueColumn: LinearLayout
    private lateinit var queueFace: LinearLayout
    private var queueShowing = false

    /** One row of the queue, against the position in [order] it stands for. */
    private class QueueRowRef(
        val row: View,
        val title: TextView,
        val artist: TextView,
        val position: Int
    )

    /** The line at the head of the queue, and the way back to the cover. */
    private lateinit var queueHeader: TextView

    private val queueRows = mutableListOf<QueueRowRef>()

    // Each list rebuilds itself from the library; these are the columns they fill.
    private lateinit var favouritesColumn: LinearLayout
    private lateinit var albumsColumn: LinearLayout
    private lateinit var songsColumn: LinearLayout
    private lateinit var playlistsColumn: LinearLayout
    private lateinit var artistsColumn: LinearLayout
    private lateinit var historyColumn: LinearLayout
    private lateinit var newColumn: LinearLayout

    /** The songs page's scroller, so a letter picked out of the jump list can be found. */
    private lateinit var songsScroll: ScrollView

    /** Where each letter's block starts in the songs list. */
    private val songHeaders = mutableMapOf<Char, View>()

    private lateinit var jumpList: rocks.gorjan.gokixp.wp81.JumpListView

    /**
     * What has been played, most recent first, as track ids.
     *
     * The Zune hub opened on this, and it is the one list nobody has to build: a phone
     * that knows what you played last night can offer it back without being asked.
     */
    private val history = mutableListOf<Long>()

    /**
     * The song rows on screen, so the playing one can be picked out and a heart can be
     * filled in without rebuilding the list it is in.
     *
     * Each remembers the column it belongs to: favourites rebuilds on its own whenever
     * something is kept or dropped, and would otherwise leave its old rows in here to be
     * repainted long after they had been detached.
     */
    private class SongRowRef(
        val title: TextView,
        val heart: TextView,
        val id: Long,
        val column: LinearLayout
    )

    private val songRows = mutableListOf<SongRowRef>()

    // ---------------------------------------------------------------- construction

    fun createView(): View {
        loadFavourites()
        loadHistory()
        // Read before anything is built: the transport paints itself from them.
        loadPlaybackModes()

        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        // The art fills the screen behind everything, dimmed almost to nothing. Zune put
        // the album where the wallpaper would be rather than in a frame beside the title,
        // which is what makes a list of songs feel like it belongs to a record.
        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = BACKDROP_ALPHA
            visibility = View.GONE
        }
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        scrim = View(context).apply {
            setBackgroundColor(
                if (palette.isDark) Color.argb(150, 0, 0, 0) else Color.argb(150, 255, 255, 255)
            )
            visibility = View.GONE
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The wordmark, in the size Zune used it: big enough to be the first thing on the
        // page and lowercase, because on this platform nothing shouts. It belongs to the
        // panorama rather than sitting above it, so it drifts as the sections go past.
        panorama.setTitle("music")
        panorama.addPage("live", buildLivePage())
        favouritesColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("favourites", listPage(favouritesColumn))
        // The two the Zune hub itself led with, in its order: what you have been playing,
        // then what has just arrived.
        historyColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("history", listPage(historyColumn))
        newColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("new", listPage(newColumn))
        albumsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("albums", listPage(albumsColumn))
        songsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        songsScroll = listPage(songsColumn)
        panorama.addPage("songs", songsScroll)
        playlistsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("playlists", listPage(playlistsColumn))
        artistsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panorama.addPage("artists", listPage(artistsColumn))

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // Opens on what the user chose to keep rather than on the player or on everything
        // they own: favourites is the shortest list in the app and the likeliest to be
        // what they came for.
        panorama.goTo(PAGE_FAVOURITES, animated = false)

        // Over everything, and hidden until a letter header is tapped.
        jumpList = rocks.gorjan.gokixp.wp81.JumpListView(context, palette).apply {
            visibility = View.GONE
            setBackgroundColor(palette.background)
            onLetterPicked = { letter -> jumpTo(letter) }
            // Anywhere off a letter puts the grid away, which is the way out of it: the
            // phone had no other, and this app has no key strip to put one on.
            setOnClickListener { hideJumpList() }
        }
        root.addView(jumpList, FrameLayout.LayoutParams(MATCH, MATCH))

        setupMediaSession()
        refreshLibrary()
        return root
    }

    private fun listPage(column: LinearLayout): ScrollView {
        column.setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
        return ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---------------------------------------------------------------- live page

    private fun buildLivePage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(PAGE_MARGIN_DP), dp(16))
        }

        // The record, then what is on it, then - pinned to the foot of the page - the
        // controls. Bottom centre is where a thumb is, and it is the one place in this app
        // where something is centred: the transport is furniture, not type.
        // Everything about the current song lives in here; the queue takes its place.
        npDetails = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val artFrame = FrameLayout(context)
        artPlaceholder = TextView(context).apply {
            text = "zune"
            typeface = font(R.font.segoeui_light)
            textSize = 30f
            setTextColor(palette.onAccent())
            gravity = Gravity.CENTER
            setBackgroundColor(palette.accent)
        }
        artFrame.addView(artPlaceholder, FrameLayout.LayoutParams(MATCH, MATCH))
        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        artFrame.addView(artView, FrameLayout.LayoutParams(MATCH, MATCH))
        wireArtGestures(artFrame)
        npDetails.addView(artFrame, LinearLayout.LayoutParams(dp(ART_DP), dp(ART_DP)).apply {
            bottomMargin = dp(16)
        })

        npTitle = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 28f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(palette.foreground)
            text = "nothing playing"
        }
        npDetails.addView(npTitle, wide())

        npArtist = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(4), 0, 0)
        }
        npDetails.addView(npArtist, wide())

        npHeart = TextView(context).apply {
            text = HEART
            textSize = 20f
            setPadding(0, dp(8), dp(16), dp(8))
            isClickable = true
            setOnClickListener { currentTrack()?.let { toggleFavourite(it) } }
            TiltEffect.apply(this)
        }
        npDetails.addView(npHeart, LinearLayout.LayoutParams(WRAP, WRAP))

        queueColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        queueScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(queueColumn, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        // The way back to the cover, pinned above the list rather than scrolling with it.
        // Turning the page over is a tap on the art, and once the art is gone there was
        // nothing left to tap - the queue was somewhere you could get into and not out of.
        queueHeader = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.accent)
            setPadding(0, dp(2), 0, dp(10))
            isClickable = true
            setOnClickListener { showQueue(false) }
            TiltEffect.apply(this)
        }

        val queueFace = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(queueHeader, wide())
            addView(queueScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        this.queueFace = queueFace

        // The two faces share the space above the transport, and whichever is up is what
        // pushes the transport to the foot of the page.
        val body = FrameLayout(context)
        body.addView(npDetails, FrameLayout.LayoutParams(MATCH, WRAP))
        body.addView(queueFace, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        seek = MetroSlider(context).apply {
            applyPalette(palette)
            onValueChanged = { v ->
                lastSeekAt = android.os.SystemClock.uptimeMillis()
                val duration = durationOrZero()
                if (duration > 0) {
                    val target = (duration * v).toInt()
                    npPosition.text = formatTime(target.toLong())
                    try {
                        player?.seekTo(target)
                    } catch (e: IllegalStateException) {
                        Log.w("ZuneApp", "Seek before the player was ready", e)
                    }
                }
            }
        }
        page.addView(seek, wide())

        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        npPosition = timeLabel()
        npDuration = timeLabel().apply { gravity = Gravity.END }
        times.addView(npPosition, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(npDuration, LinearLayout.LayoutParams(0, WRAP, 1f))
        page.addView(times, wide())

        page.addView(buildTransport(), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(14)
        })

        repaintHeart()
        return page
    }

    private fun timeLabel() = TextView(context).apply {
        text = "0:00"
        typeface = font(R.font.segoeui_regular)
        textSize = 12f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(4), 0, 0)
    }

    /**
     * Shuffle, back, play, forward, repeat.
     *
     * The two toggles sit on the outside and are drawn smaller than the transport they
     * flank: they are not things you press while listening, they are things you set once
     * and forget, and giving them the same weight as play made the row read as five equal
     * buttons rather than as a transport with a setting at each end.
     */
    private fun buildTransport(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        shuffleButton = toggleButton(R.drawable.wp81_media_shuffle) {
            shuffle = !shuffle
            savePlaybackModes()
            reorder()
            bindQueue()
            repaintModes()
        }
        row.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(TOGGLE_DP), 1f))

        row.addView(transportButton(R.drawable.wp81_media_previous) { skip(-1) })
        playPause = transportButton(R.drawable.wp81_media_play) { togglePlayPause() }
        row.addView(playPause, LinearLayout.LayoutParams(dp(TRANSPORT_DP), dp(TRANSPORT_DP)).apply {
            marginStart = dp(26)
            marginEnd = dp(26)
        })
        row.addView(transportButton(R.drawable.wp81_media_next) { skip(1) })

        repeatButton = toggleButton(R.drawable.wp81_media_repeat) {
            repeat = !repeat
            savePlaybackModes()
            repaintModes()
        }
        row.addView(repeatButton, LinearLayout.LayoutParams(0, dp(TOGGLE_DP), 1f))

        repaintModes()
        return row
    }

    private fun toggleButton(icon: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }

    /** On is the accent, off is the same grey the times are set in. */
    private fun repaintModes() {
        shuffleButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (shuffle) palette.accent else palette.foregroundSubtle)
        repeatButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (repeat) palette.accent else palette.foregroundSubtle)
    }

    private fun transportButton(icon: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
            layoutParams = LinearLayout.LayoutParams(dp(TRANSPORT_DP), dp(TRANSPORT_DP))
        }

    // ---------------------------------------------------------------- the queue

    /**
     * The cover is a button and a pair of pages at once.
     *
     * Tapping it turns the page over to what is queued behind the song; dragging across it
     * changes track. Both are what the phone's player did, and both are the reason the art
     * is as large as it is - it is the control, not a picture of one.
     */
    private fun wireArtGestures(art: View) {
        val detector = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true

                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    showQueue(!queueShowing)
                    return true
                }

                override fun onFling(
                    down: android.view.MotionEvent?,
                    up: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = down ?: return false
                    val dx = up.x - start.x
                    // Sideways only, and far enough to be meant: a flick that is mostly
                    // vertical belongs to the page it is on.
                    if (kotlin.math.abs(dx) < dp(SWIPE_MIN_DP)) return false
                    if (kotlin.math.abs(dx) < kotlin.math.abs(up.y - start.y)) return false
                    // Dragged left means the next song arrives from the right, which is
                    // the direction every list on this platform moves in.
                    skip(if (dx < 0) 1 else -1)
                    return true
                }
            }
        )
        art.isClickable = true
        // Handed to the tilt rather than set on its own: a view has one touch listener, and
        // applying the tilt afterwards is what was quietly throwing this one away - which
        // is why tapping the cover did nothing at all.
        TiltEffect.apply(art) { _, event ->
            // The panorama pages on a horizontal drag and would take this one first, so the
            // cover claims the gesture the moment it starts.
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                art.parent?.requestDisallowInterceptTouchEvent(true)
            }
            detector.onTouchEvent(event)
        }
    }

    private fun showQueue(show: Boolean) {
        queueShowing = show && queue.isNotEmpty()
        npDetails.visibility = if (queueShowing) View.GONE else View.VISIBLE
        queueFace.visibility = if (queueShowing) View.VISIBLE else View.GONE
        if (!queueShowing) return
        // Opened on the song that is playing rather than at the top: forty songs in, the
        // top of the list is not where the user is.
        val playing = queueRows.firstOrNull { it.position == orderPos } ?: return
        queueScroll.post {
            queueScroll.smoothScrollTo(0, (playing.row.top - dp(QUEUE_LEAD_DP)).coerceAtLeast(0))
        }
    }

    /**
     * The songs behind this one, in the order they will actually play.
     *
     * Compact rows and no covers: this is a running order, and forty covers down the side
     * of it says nothing that the album name has not already said. The one playing is in
     * the accent, and tapping any of them jumps there rather than starting a new queue.
     */
    private fun bindQueue() {
        queueColumn.removeAllViews()
        queueRows.clear()
        if (queue.isEmpty()) {
            queueColumn.addView(emptyNote("nothing queued"), wide())
            return
        }
        for (position in order.indices) {
            val track = queue.getOrNull(order[position]) ?: continue
            queueColumn.addView(queueRow(track, position), wide())
        }
        repaintQueue()
    }

    private fun queueRow(track: ZuneTrack, position: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener {
                orderPos = position
                startCurrent()
            }
            setOnLongClickListener {
                showTrackSheet(track)
                true
            }
            TiltEffect.apply(this)
        }
        val title = TextView(context).apply {
            text = track.title
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        row.addView(title, wide())
        val artist = TextView(context).apply {
            text = listOf(track.artist, formatTime(track.durationMs))
                .filter { it.isNotBlank() }
                .joinToString("   ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        row.addView(artist, wide())
        queueRows.add(QueueRowRef(row, title, artist, position))
        return row
    }

    /**
     * Marks where playback is, and greys out what has already gone by.
     *
     * A queue that looks the same above and below the playing song is a list of what is on
     * the record; dimming what is behind makes it a list of what is left.
     */
    private fun repaintQueue() {
        if (::queueHeader.isInitialized) {
            val left = (order.size - orderPos - 1).coerceAtLeast(0)
            queueHeader.text = when {
                queue.isEmpty() -> "back to the cover"
                left == 0 -> "last song   ·   back to the cover"
                left == 1 -> "1 song after this   ·   back to the cover"
                else -> "$left songs after this   ·   back to the cover"
            }
        }
        for (row in queueRows) {
            val played = row.position < orderPos
            row.title.setTextColor(
                when {
                    row.position == orderPos -> palette.accent
                    played -> palette.inactive
                    else -> palette.foreground
                }
            )
            row.artist.setTextColor(
                if (played) palette.inactive else palette.foregroundSubtle)
        }
    }

    // ---------------------------------------------------------------- rows

    /**
     * A song: art, name, and who it is by.
     *
     * The art is what makes this a Zune list rather than a file list - the same songs
     * without it read as a directory. It is left blank rather than filled with a
     * placeholder when a record has no cover: a column of identical grey squares is worse
     * than a column of nothing.
     */
    private fun songRow(track: ZuneTrack, column: LinearLayout, playFrom: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { playFrom() }
            setOnLongClickListener {
                showTrackSheet(track)
                true
            }
            TiltEffect.apply(this)
        }

        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.inactive)
        }
        ZuneArt.into(context, track.albumId, art)
        row.addView(art, LinearLayout.LayoutParams(dp(ROW_ART_DP), dp(ROW_ART_DP)).apply {
            marginEnd = dp(12)
        })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            this.text = track.title
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        text.addView(title, wide())
        text.addView(TextView(context).apply {
            this.text = listOf(track.artist, formatTime(track.durationMs))
                .filter { it.isNotBlank() }
                .joinToString("   ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(2), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))

        val heart = TextView(context).apply {
            this.text = HEART
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(heart, LinearLayout.LayoutParams(WRAP, WRAP))

        songRows.add(SongRowRef(title, heart, track.id, column))
        return row
    }

    /** An album or an artist: art, name, and what is underneath it. */
    private fun groupRow(
        title: String,
        subtitle: String,
        albumId: Long,
        tracks: List<ZuneTrack>,
        onTap: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { onTap() }
            setOnLongClickListener {
                showGroupSheet(title, tracks)
                true
            }
            TiltEffect.apply(this)
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.inactive)
        }
        ZuneArt.into(context, albumId, art)
        row.addView(art, LinearLayout.LayoutParams(dp(GROUP_ART_DP), dp(GROUP_ART_DP)).apply {
            marginEnd = dp(12)
        })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = title
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            this.text = subtitle
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun emptyNote(message: String, onTap: (() -> Unit)? = null): View =
        TextView(context).apply {
            text = message
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(18), dp(16), dp(18))
            if (onTap != null) {
                isClickable = true
                setOnClickListener { onTap() }
            }
        }

    // ---------------------------------------------------------------- library

    /**
     * Reads the music library, off the main thread.
     *
     * Called again after the permission prompt is answered, so granting access fills the
     * lists where they stand rather than asking the user to leave and come back.
     */
    fun refreshLibrary() {
        if (!hasAudioPermission()) {
            library = emptyList()
            bindAll("no access to your music.  tap to allow") { onRequestPermissions() }
            return
        }
        bindAll("reading your collection…")
        Thread {
            val found = queryTracks()
            val lists = queryPlaylists(found)
            handler.post {
                library = found
                playlists = lists
                if (found.isEmpty()) bindAll("no music on this phone") else bindAll(null)
            }
        }.start()
    }

    private var playlists: List<Pair<String, List<ZuneTrack>>> = emptyList()

    private fun queryTracks(): List<ZuneTrack> {
        val out = mutableListOf<ZuneTrack>()
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            // Deprecated, and asked for anyway: the playlists Winamp wrote are lists of
            // paths, so matching them means knowing where each file is.
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                @Suppress("DEPRECATION")
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out.add(
                        ZuneTrack(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            path = cursor.getString(pathCol).orEmpty(),
                            title = cursor.getString(titleCol).orEmpty().ifBlank { "unknown" },
                            artist = clean(cursor.getString(artistCol)),
                            album = clean(cursor.getString(albumCol)),
                            albumId = cursor.getLong(albumIdCol),
                            durationMs = cursor.getLong(durationCol),
                            addedAt = cursor.getLong(addedCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ZuneApp", "Could not read the music library", e)
        }
        return out
    }

    /**
     * The launcher's playlists - the same ones Winamp shows, from the same store.
     *
     * Not MediaStore's: its playlist tables were deprecated in Android 11 and are empty on
     * any phone that has never had a player write to them. The launcher keeps its own, and
     * a playlist made in Winamp is meant to be the same playlist here rather than a second
     * list with the same name.
     *
     * A song in a playlist whose file is no longer in the library is dropped rather than
     * shown as a dead row, and a playlist left with nothing is not listed at all.
     */
    private fun queryPlaylists(known: List<ZuneTrack>): List<Pair<String, List<ZuneTrack>>> {
        val byPath = known.filter { it.path.isNotBlank() }.associateBy { it.path }
        return rocks.gorjan.gokixp.apps.winamp.PlaylistStore.load(context)
            .map { playlist -> playlist.name to playlist.tracks.mapNotNull { byPath[it] } }
            .filter { it.second.isNotEmpty() }
    }

    // ---------------------------------------------------------------- binding

    private fun bindAll(message: String?, onTap: (() -> Unit)? = null) {
        bindSongs(message, onTap)
        bindFavourites(message, onTap)
        bindHistory(message)
        bindNew(message)
        bindAlbums(message)
        bindArtists(message)
        bindPlaylists(message)
        repaintRows()
    }

    private fun bindSongs(message: String?, onTap: (() -> Unit)?) {
        songsColumn.removeAllViews()
        songRows.removeAll { it.column === songsColumn }
        if (message != null) {
            songsColumn.addView(emptyNote(message, onTap), wide())
            return
        }
        // The first thing on the songs list, as it was on the phone: everything you own,
        // in no order, without having to pick a starting point.
        songsColumn.addView(shuffleAllRow(), wide())

        // Filed under letters, with a header for each, exactly as the app list is. A
        // thousand songs in one unbroken column can only be reached by scrolling to them.
        songHeaders.clear()
        var letter: Char? = null
        for ((index, track) in library.withIndex()) {
            val initial = initialOf(track.title)
            if (initial != letter) {
                letter = initial
                val header = letterHeader(initial)
                songHeaders[initial] = header
                songsColumn.addView(header, wide())
            }
            songsColumn.addView(
                songRow(track, songsColumn) { playFrom(library, index) }, wide())
        }
        jumpList.setAvailableLetters(songHeaders.keys)
    }

    /** The bucket a title is filed under. Anything not a letter goes under '#'. */
    private fun initialOf(title: String): Char {
        val first = title.trim().firstOrNull()?.lowercaseChar() ?: '#'
        return if (first in 'a'..'z') first else '#'
    }

    /**
     * A letter over the block of songs beneath it, and the way into the jump grid.
     *
     * Tappable for the same reason it is on the app list: the letter is not a label, it is
     * the button that gets you to any other letter without scrolling past everything in
     * between.
     */
    private fun letterHeader(letter: Char): View =
        TextView(context).apply {
            text = letter.uppercaseChar().toString()
            typeface = font(R.font.segoeui_light)
            textSize = 26f
            setTextColor(palette.accent)
            includeFontPadding = false
            setPadding(0, dp(16), 0, dp(6))
            isClickable = true
            setOnClickListener { showJumpList() }
        }

    private fun showJumpList() {
        jumpList.visibility = View.VISIBLE
        jumpList.playEntrance()
    }

    /** Scrolls the songs list to a letter's block, and closes the grid over it. */
    private fun jumpTo(letter: Char) {
        hideJumpList()
        val header = songHeaders[letter.lowercaseChar()] ?: return
        songsScroll.post { songsScroll.smoothScrollTo(0, header.top) }
    }

    private fun hideJumpList() {
        jumpList.visibility = View.GONE
    }

    /**
     * Pages and sheets stacked over the app, newest last.
     *
     * Tracked rather than counted off the root: the backdrop, the scrim, the panorama and
     * the jump grid all live there too, and "the last child" is only sometimes an overlay.
     */
    private val overlays = mutableListOf<View>()

    private fun pushOverlay(view: View) {
        overlays.add(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun dismissOverlay(view: View) {
        overlays.remove(view)
        root.removeView(view)
    }

    /**
     * Back, from the inside out.
     *
     * Backing out of an album should land on the player, not on the Start screen. The
     * window this app lives in treats back as "put Zune away", which is right at the top
     * level and wrong everywhere below it - so everything that was opened is closed first,
     * one layer per press, and only a press with nothing left to close leaves.
     */
    fun handleBack(): Boolean {
        if (jumpList.visibility == View.VISIBLE) {
            hideJumpList()
            return true
        }
        overlays.lastOrNull()?.let {
            dismissOverlay(it)
            return true
        }
        // The queue is a face of the player rather than a page over it, but it is still
        // somewhere the user went into and expects to come out of.
        if (queueShowing) {
            showQueue(false)
            return true
        }
        return false
    }

    private fun shuffleAllRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(14))
            isClickable = true
            setOnClickListener {
                // Turns shuffle on rather than shuffling once behind the user's back: the
                // toggle on the now playing screen has to agree with what is happening.
                shuffle = true
                savePlaybackModes()
                repaintModes()
                playFrom(library, (0 until library.size).random())
            }
            TiltEffect.apply(this)
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.wp81_media_shuffle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        }, LinearLayout.LayoutParams(dp(ROW_ART_DP), dp(ROW_ART_DP)).apply {
            marginEnd = dp(12)
        })
        row.addView(TextView(context).apply {
            text = "shuffle all"
            typeface = font(R.font.segoeui_light)
            textSize = 21f
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun bindFavourites(message: String?, onTap: (() -> Unit)?) {
        favouritesColumn.removeAllViews()
        songRows.removeAll { it.column === favouritesColumn }
        if (message != null) {
            favouritesColumn.addView(emptyNote(message, onTap), wide())
            return
        }
        val kept = library.filter { it.id in favourites }
        if (kept.isEmpty()) {
            favouritesColumn.addView(
                emptyNote("nothing kept yet.  hold a song to keep it here"), wide())
            return
        }
        for ((index, track) in kept.withIndex()) {
            favouritesColumn.addView(
                songRow(track, favouritesColumn) { playFrom(kept, index) }, wide())
        }
    }

    /**
     * What has been played, newest first.
     *
     * Filtered against the library each time rather than trimmed when something is
     * deleted: a song can leave the phone between one launch and the next, and a history
     * that lists what is no longer there is a list of dead ends.
     */
    private fun bindHistory(message: String?) {
        historyColumn.removeAllViews()
        songRows.removeAll { it.column === historyColumn }
        if (message != null) {
            historyColumn.addView(emptyNote(message), wide())
            return
        }
        val byId = library.associateBy { it.id }
        val played = history.mapNotNull { byId[it] }
        if (played.isEmpty()) {
            historyColumn.addView(
                emptyNote("nothing played yet.  what you hear turns up here"), wide())
            return
        }
        for ((index, track) in played.withIndex()) {
            historyColumn.addView(
                songRow(track, historyColumn) { playFrom(played, index) }, wide())
        }
    }

    /** What arrived most recently, which on a phone is what was last copied onto it. */
    private fun bindNew(message: String?) {
        newColumn.removeAllViews()
        songRows.removeAll { it.column === newColumn }
        if (message != null) {
            newColumn.addView(emptyNote(message), wide())
            return
        }
        val recent = library.sortedByDescending { it.addedAt }.take(NEW_MAX)
        if (recent.isEmpty()) {
            newColumn.addView(emptyNote("nothing here yet"), wide())
            return
        }
        for ((index, track) in recent.withIndex()) {
            newColumn.addView(songRow(track, newColumn) { playFrom(recent, index) }, wide())
        }
    }

    private fun bindAlbums(message: String?) {
        albumsColumn.removeAllViews()
        if (message != null) {
            albumsColumn.addView(emptyNote(message), wide())
            return
        }
        // Grouped by name rather than by album id: the same record ripped twice lands
        // under two ids, and a user looking at their own shelf does not care.
        val albums = library.filter { it.album.isNotBlank() }.groupBy { it.album }
        for ((name, tracks) in albums.entries.sortedBy { it.key.lowercase(Locale.getDefault()) }) {
            val artist = tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty()
            val subtitle = listOf(artist, "${tracks.size} songs")
                .filter { it.isNotBlank() }
                .joinToString("   ")
            albumsColumn.addView(
                groupRow(name, subtitle, tracks.first().albumId, tracks) {
                    showGroup(name, artist, tracks.first().albumId, tracks)
                }, wide())
        }
        if (albumsColumn.childCount == 0) {
            albumsColumn.addView(emptyNote("nothing here is filed under an album"), wide())
        }
    }

    private fun bindArtists(message: String?) {
        artistsColumn.removeAllViews()
        if (message != null) {
            artistsColumn.addView(emptyNote(message), wide())
            return
        }
        val artists = library.filter { it.artist.isNotBlank() }.groupBy { it.artist }
        for ((name, tracks) in artists.entries.sortedBy { it.key.lowercase(Locale.getDefault()) }) {
            val albums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct().size
            val subtitle = listOfNotNull(
                "${tracks.size} songs",
                if (albums > 0) "$albums albums" else null
            ).joinToString("   ")
            artistsColumn.addView(
                groupRow(name, subtitle, tracks.first().albumId, tracks) {
                    showGroup(name, subtitle, tracks.first().albumId, tracks)
                }, wide())
        }
        if (artistsColumn.childCount == 0) {
            artistsColumn.addView(emptyNote("nothing here is filed under an artist"), wide())
        }
    }

    private fun bindPlaylists(message: String?) {
        playlistsColumn.removeAllViews()
        if (message != null) {
            playlistsColumn.addView(emptyNote(message), wide())
            return
        }
        if (playlists.isEmpty()) {
            playlistsColumn.addView(
                emptyNote("no playlists yet.  make one in winamp and it shows up here"), wide())
            return
        }
        for ((name, tracks) in playlists) {
            playlistsColumn.addView(
                groupRow(name, "${tracks.size} songs", tracks.first().albumId, tracks) {
                    showGroup(name, "${tracks.size} songs", tracks.first().albumId, tracks)
                }, wide())
        }
    }

    /**
     * The song that is playing is the only one in the accent, in whichever list it is in,
     * and a kept song is the only one wearing a heart.
     */
    private fun repaintRows() {
        val playingId = currentTrack()?.id
        for (row in songRows) {
            row.title.setTextColor(
                if (row.id == playingId) palette.accent else palette.foreground)
            row.heart.setTextColor(
                if (row.id in favourites) palette.accent else Color.TRANSPARENT)
        }
    }

    // ---------------------------------------------------------------- group page

    /**
     * An album, an artist or a playlist, opened rather than started.
     *
     * Tapping a record used to play it from the top, which is the one thing a list of
     * albums is *not* for: you go to an album to find the song on it. So the cover, the
     * name, a way to start the whole thing, and then the songs - which is the page the
     * phone showed, and where its "play" and "shuffle" buttons sat.
     */
    private fun showGroup(
        title: String,
        subtitle: String,
        albumId: Long,
        tracks: List<ZuneTrack>
    ) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            // Swallows anything that falls past the rows, so the panorama behind does not
            // page while a record is open on top of it.
            isClickable = true
        }

        val header = rocks.gorjan.gokixp.wp81.MetroPageHeader(context, palette).apply {
            setTitle(title)
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }

        // The cover, big, with what is on it beside - the one place in this app where art
        // is given a line of its own rather than being the background.
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.inactive)
        }
        ZuneArt.into(context, albumId, art)
        top.addView(art, LinearLayout.LayoutParams(dp(GROUP_PAGE_ART_DP), dp(GROUP_PAGE_ART_DP))
            .apply { marginEnd = dp(14) })
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(context).apply {
            text = subtitle
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 2
            setTextColor(palette.foregroundSubtle)
        }, wide())
        heading.addView(TextView(context).apply {
            text = totalTime(tracks)
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(4), 0, 0)
        }, wide())
        top.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        column.addView(top, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(4)
            bottomMargin = dp(14)
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        actions.addView(groupAction("play") {
            dismissOverlay(page)
            if (shuffle) {
                shuffle = false
                savePlaybackModes()
                repaintModes()
            }
            playFrom(tracks, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(groupAction("shuffle") {
            dismissOverlay(page)
            shuffle = true
            savePlaybackModes()
            repaintModes()
            playFrom(tracks, tracks.indices.random())
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        column.addView(actions, wide())

        for ((index, track) in tracks.withIndex()) {
            column.addView(
                songRow(track, column) {
                    dismissOverlay(page)
                    playFrom(tracks, index)
                }, wide())
        }

        page.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        pushOverlay(page)
        repaintRows()
    }

    private fun groupAction(label: String, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent())
            setBackgroundColor(palette.accent)
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) }
        }

    /** How long the whole thing runs, said the way a record's length is said. */
    private fun totalTime(tracks: List<ZuneTrack>): String {
        val minutes = (tracks.sumOf { it.durationMs } / 60000L).toInt()
        val hours = minutes / 60
        val length =
            if (hours > 0) "$hours hr ${minutes % 60} min" else "$minutes min"
        return "${tracks.size} songs   $length"
    }

    // ---------------------------------------------------------------- track sheet

    /**
     * What a song can be filed into, on a hold.
     *
     * The playlists are the launcher's own - the ones Winamp writes - so a song put into
     * one here is in the same list when Winamp is opened, and vice versa. Making a new
     * playlist is left to Winamp: naming one needs a keyboard and a text field, which is a
     * dialog this app has no business growing when the program next door already has it.
     */
    private fun showTrackSheet(track: ZuneTrack) {
        val overlay = FrameLayout(context)

        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            isClickable = true
            setOnClickListener { dismissOverlay(overlay) }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(PAGE_MARGIN_DP), dp(16), dp(PAGE_MARGIN_DP), dp(20))
            isClickable = true
        }

        sheet.addView(TextView(context).apply {
            text = track.title
            typeface = font(R.font.segoeui_light)
            textSize = 22f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            setPadding(0, 0, 0, dp(10))
        }, wide())

        val kept = track.id in favourites
        sheet.addView(sheetRow(if (kept) "remove from favourites" else "add to favourites") {
            dismissOverlay(overlay)
            toggleFavourite(track)
        }, wide())

        val lists = playlists.map { it.first }
        if (lists.isEmpty()) {
            sheet.addView(TextView(context).apply {
                text = "no playlists yet.  winamp makes them"
                typeface = font(R.font.segoeui_regular)
                textSize = 13f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(12), 0, 0)
            }, wide())
        } else {
            sheet.addView(TextView(context).apply {
                text = "add to playlist"
                typeface = font(R.font.segoeui_semibold)
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, dp(14), 0, dp(4))
            }, wide())
            for (name in lists) {
                sheet.addView(sheetRow(name) {
                    dismissOverlay(overlay)
                    addToPlaylist(track, name)
                }, wide())
            }
        }

        overlay.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        pushOverlay(overlay)
    }

    /**
     * What a whole record can be done to, on a hold.
     *
     * The same gesture a song has, answering the same question one level up. Keeping an
     * album means keeping the songs on it - favourites is a list of songs and always was -
     * so this is a way of ticking twelve of them at once rather than a second kind of
     * favourite.
     */
    private fun showGroupSheet(title: String, tracks: List<ZuneTrack>) {
        if (tracks.isEmpty()) return
        val overlay = FrameLayout(context)

        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            isClickable = true
            setOnClickListener { dismissOverlay(overlay) }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(PAGE_MARGIN_DP), dp(16), dp(PAGE_MARGIN_DP), dp(20))
            isClickable = true
        }

        sheet.addView(TextView(context).apply {
            text = title
            typeface = font(R.font.segoeui_light)
            textSize = 22f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            setPadding(0, 0, 0, dp(10))
        }, wide())

        // Kept only when every song on it is: a record with one song ticked is not one the
        // user has kept, and offering to "remove" it would drop the one they did keep.
        val allKept = tracks.all { it.id in favourites }
        sheet.addView(
            sheetRow(if (allKept) "remove all from favourites" else "add all to favourites") {
                dismissOverlay(overlay)
                if (allKept) favourites.removeAll(tracks.map { it.id }.toSet())
                else favourites.addAll(tracks.map { it.id })
                saveFavourites()
                bindFavourites(null, null)
                repaintRows()
                repaintHeart()
            }, wide())

        sheet.addView(sheetRow("play") {
            dismissOverlay(overlay)
            playFrom(tracks, 0)
        }, wide())

        sheet.addView(sheetRow("shuffle") {
            dismissOverlay(overlay)
            shuffle = true
            savePlaybackModes()
            repaintModes()
            playFrom(tracks, tracks.indices.random())
        }, wide())

        overlay.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        pushOverlay(overlay)
    }

    private fun sheetRow(label: String, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = 18f
            setTextColor(palette.foreground)
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }

    /** Files a song into one of the launcher's playlists, where Winamp will find it too. */
    private fun addToPlaylist(track: ZuneTrack, playlistName: String) {
        if (track.path.isBlank()) return
        rocks.gorjan.gokixp.apps.winamp.PlaylistStore.addTrack(
            context, playlistName, track.path)
        // Re-read rather than patching the copy in hand: Winamp may have written to the
        // same store since this list was loaded.
        playlists = queryPlaylists(library)
        bindPlaylists(null)
    }

    // ---------------------------------------------------------------- favourites

    private fun toggleFavourite(track: ZuneTrack) {
        if (!favourites.add(track.id)) favourites.remove(track.id)
        saveFavourites()
        repaintHeart()
        // The favourites list is the one that changed shape, so it is the one rebuilt.
        bindFavourites(null, null)
        repaintRows()
    }

    private fun repaintHeart() {
        val track = currentTrack()
        npHeart.setTextColor(
            when {
                track == null -> palette.inactive
                track.id in favourites -> palette.accent
                else -> palette.foregroundSubtle
            }
        )
    }

    private fun loadPlaybackModes() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
        repeat = prefs.getBoolean(KEY_REPEAT, false)
    }

    private fun savePlaybackModes() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putBoolean(KEY_REPEAT, repeat)
            .apply()
    }

    private fun loadHistory() {
        history.clear()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "").orEmpty()
        raw.split(",").mapNotNullTo(history) { it.trim().toLongOrNull() }
    }

    private fun saveHistory() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HISTORY, history.joinToString(","))
            .apply()
    }

    /** Moves a song to the front of the history, which is the only place it can be. */
    private fun notePlayed(track: ZuneTrack) {
        history.remove(track.id)
        history.add(0, track.id)
        while (history.size > HISTORY_MAX) history.removeAt(history.lastIndex)
        saveHistory()
        bindHistory(null)
    }

    private fun loadFavourites() {
        favourites.clear()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAVOURITES, "").orEmpty()
        raw.split(",").mapNotNullTo(favourites) { it.trim().toLongOrNull() }
    }

    private fun saveFavourites() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAVOURITES, favourites.joinToString(","))
            .apply()
    }

    // ---------------------------------------------------------------- playback

    private fun currentTrack(): ZuneTrack? =
        order.getOrNull(orderPos)?.let { queue.getOrNull(it) }

    /** Starts [tracks] at [index] and makes them the queue. */
    private fun playFrom(tracks: List<ZuneTrack>, index: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        val start = index.coerceIn(0, tracks.size - 1)
        order = orderFrom(start)
        orderPos = if (shuffle) 0 else start
        bindQueue()
        // A new queue is a new record; whatever the last one was showing, this one opens
        // on its cover.
        showQueue(false)
        startCurrent()
        // Tapping a song is a request to hear it, not to read about it: the app follows
        // the user onto the section that shows what they just started.
        panorama.goTo(PAGE_LIVE, animated = true)
    }

    /**
     * The play order for a queue being started at [start].
     *
     * Shuffled, the song asked for still comes first: tapping a track and hearing a
     * different one is not shuffle, it is the app ignoring you.
     */
    private fun orderFrom(start: Int): List<Int> =
        if (shuffle) listOf(start) + (queue.indices - start).shuffled()
        else queue.indices.toList()

    /**
     * Rebuilds the order around whatever is playing, after shuffle is turned on or off.
     *
     * The song in hand does not change or restart either way. Turning shuffle on deals the
     * rest again behind it; turning it off puts the record back in its own order and finds
     * the current song's place in it.
     */
    private fun reorder() {
        val playing = order.getOrNull(orderPos) ?: return
        if (shuffle) {
            order = listOf(playing) + (queue.indices - playing).shuffled()
            orderPos = 0
        } else {
            order = queue.indices.toList()
            orderPos = playing
        }
    }

    private fun startCurrent() {
        val track = currentTrack() ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(context, track.uri)
                setOnCompletionListener { advance(1, auto = true) }
                setOnPreparedListener {
                    start()
                    this@ZuneApp.isPlaying = true
                    onPlaybackChanged()
                    startProgress()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("ZuneApp", "Could not play ${track.title}", e)
            player = null
            isPlaying = false
            onPlaybackChanged()
            return
        }
        notePlayed(track)
        bindNowPlaying(track)
        repaintRows()
        repaintQueue()
        repaintHeart()
        loadArt(track)
    }

    private fun bindNowPlaying(track: ZuneTrack) {
        npTitle.text = track.title
        npArtist.text = listOf(track.artist, track.album)
            .filter { it.isNotBlank() }
            .joinToString("   —   ")
        npDuration.text = formatTime(track.durationMs)
        npPosition.text = "0:00"
        seek.value = 0f
    }

    /** The cover, on the square and bled across the background behind everything. */
    private fun loadArt(track: ZuneTrack) {
        val wanted = track.id
        artView.visibility = View.GONE
        backdrop.visibility = View.GONE
        scrim.visibility = View.GONE
        ZuneArt.load(context, track.albumId) { art: Bitmap? ->
            if (art == null || currentTrack()?.id != wanted) return@load
            artView.setImageBitmap(art)
            artView.visibility = View.VISIBLE
            backdrop.setImageBitmap(art)
            backdrop.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
        }
    }

    private fun togglePlayPause() {
        val active = player
        if (active == null) {
            // Nothing has been chosen yet, so the button means "start what I own".
            if (library.isNotEmpty()) playFrom(library, 0)
            return
        }
        if (active.isPlaying) {
            active.pause()
            isPlaying = false
            stopProgress()
        } else {
            active.start()
            isPlaying = true
            startProgress()
        }
        onPlaybackChanged()
    }

    private fun skip(direction: Int) {
        if (queue.isEmpty()) return
        // Back within the first few seconds means the previous song; after that it means
        // the start of this one, which is what every player has done since the CD player.
        if (direction < 0 && (player?.currentPosition ?: 0) > RESTART_WINDOW_MS) {
            player?.seekTo(0)
            return
        }
        advance(direction, auto = false)
    }

    /**
     * Moves through the order, and decides what the end of it means.
     *
     * [auto] is what separates a song finishing from a button being pressed. Reaching the
     * end on its own with repeat off is the queue being over, so it stops - a player that
     * silently starts the record again is a player you cannot leave running. Pressing next
     * at the end is a request to keep going, so it wraps.
     */
    private fun advance(direction: Int, auto: Boolean) {
        if (order.isEmpty()) return
        val next = orderPos + direction
        when {
            next in order.indices -> orderPos = next
            repeat -> orderPos = if (next < 0) order.lastIndex else 0
            auto -> {
                // Left standing on the last song, wound back to the start of it, so play
                // picks the queue up again rather than having nothing to do.
                stopProgress()
                isPlaying = false
                player?.let {
                    it.pause()
                    it.seekTo(0)
                }
                seek.value = 0f
                npPosition.text = "0:00"
                onPlaybackChanged()
                return
            }
            else -> orderPos = if (next < 0) order.lastIndex else 0
        }
        startCurrent()
    }

    private fun onPlaybackChanged() {
        playPause.setImageResource(
            if (isPlaying) R.drawable.wp81_media_pause else R.drawable.wp81_media_play)
        updateMediaSession()
    }

    private fun startProgress() {
        stopProgress()
        val tick = object : Runnable {
            override fun run() {
                val active = player
                val settled =
                    android.os.SystemClock.uptimeMillis() - lastSeekAt > SEEK_SETTLE_MS
                if (active != null && settled) {
                    val duration = durationOrZero()
                    if (duration > 0) {
                        seek.value = active.currentPosition / duration.toFloat()
                        npPosition.text = formatTime(active.currentPosition.toLong())
                    }
                }
                handler.postDelayed(this, PROGRESS_MS)
            }
        }
        progressTick = tick
        handler.post(tick)
    }

    private fun stopProgress() {
        progressTick?.let { handler.removeCallbacks(it) }
        progressTick = null
    }

    // ---------------------------------------------------------------- media session

    /**
     * Publishes what is playing to the system.
     *
     * Which is what puts it in the shade with working buttons, on the lock screen, and -
     * because this shell reads media sessions for its live tiles - on the Zune tile on
     * Start, where it goes on working after the app itself has been backed out of.
     */
    private fun setupMediaSession() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(
            CHANNEL_ID, "Zune playback", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the song Zune is playing"
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)

        mediaSession = android.media.session.MediaSession(context, "ZuneMediaSession").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() {
                    if (player?.isPlaying == false) togglePlayPause()
                }

                override fun onPause() {
                    if (player?.isPlaying == true) togglePlayPause()
                }

                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            isActive = true
        }
    }

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val track = currentTrack() ?: return

        session.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (isPlaying) android.media.session.PlaybackState.STATE_PLAYING
                    else android.media.session.PlaybackState.STATE_PAUSED,
                    player?.currentPosition?.toLong() ?: 0L,
                    1f
                )
                .build()
        )
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(
                    android.media.MediaMetadata.METADATA_KEY_ARTIST,
                    track.artist.ifBlank { "Music" })
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .build()
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist.ifBlank { "Music" })
            .setSmallIcon(R.drawable.zune_icon)
            .setStyle(
                android.app.Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setOngoing(isPlaying)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    /** Stops everything and takes the notification down. Called when the window closes. */
    fun cleanup() {
        stopProgress()
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w("ZuneApp", "Player would not release", e)
        }
        player = null
        isPlaying = false
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
    }

    // ---------------------------------------------------------------- helpers

    /** The track length, or 0 while the player has none to give - it throws if asked early. */
    private fun durationOrZero(): Int = try {
        player?.duration ?: 0
    } catch (e: IllegalStateException) {
        0
    }

    /** MediaStore's way of saying a file has no tag is not a thing to show a user. */
    private fun clean(value: String?): String =
        value.orEmpty().takeUnless { it == UNKNOWN_TAG }.orEmpty()

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Section order, and where the app opens.
        const val PAGE_LIVE = 0
        const val PAGE_FAVOURITES = 1

        /** How strongly the album art shows through behind the page. */
        const val BACKDROP_ALPHA = 0.5f

        const val PAGE_MARGIN_DP = 22
        const val ART_DP = 168
        const val ROW_ART_DP = 48
        const val GROUP_ART_DP = 62

        /** The cover at the head of an album's own page, which is a page about the record. */
        const val GROUP_PAGE_ART_DP = 108
        const val TRANSPORT_DP = 44

        /** The two settings either side of it, drawn smaller than the transport itself. */
        const val TOGGLE_DP = 26

        /** Shortest drag across the cover that counts as a skip. */
        const val SWIPE_MIN_DP = 40

        /** How much of the queue is left above the playing song when it is scrolled to. */
        const val QUEUE_LEAD_DP = 72

        const val PROGRESS_MS = 500L

        /** How long after a drag the tick leaves the slider alone. */
        const val SEEK_SETTLE_MS = 700L

        /** Back inside this many milliseconds goes to the previous song, not the start. */
        const val RESTART_WINDOW_MS = 4000

        /** What MediaStore stores for a file with no artist or album tag. */
        const val UNKNOWN_TAG = "<unknown>"

        const val HEART = "♥"

        const val PREFS = "zune_prefs"
        const val KEY_FAVOURITES = "favourite_track_ids"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val KEY_HISTORY = "history_track_ids"

        /** How far back the history goes, and how much of the library "new" shows. */
        const val HISTORY_MAX = 40
        const val NEW_MAX = 40

        const val CHANNEL_ID = "zune_playback"
        const val NOTIFICATION_ID = 1042
    }
}
