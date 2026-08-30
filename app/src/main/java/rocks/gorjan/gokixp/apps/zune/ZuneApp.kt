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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.applyToField
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

    /**
     * Whole records kept, as "kind|name" - an album, an artist or a playlist.
     *
     * Kept apart from the songs rather than as the ids of everything on them, because a
     * record is a thing in its own right. Ticking off its twelve songs said the wrong
     * thing twice over: favourites filled up with a dozen rows where the user had meant
     * one, and a song they had kept on its own was taken away again when they dropped the
     * album it happened to be from.
     *
     * Held by name because that is what the shelves are grouped by - the same record
     * ripped twice has two album ids - and because a name survives the library being read
     * again, which an id from a particular read does not.
     */
    private val favouriteGroups = mutableSetOf<String>()

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
    private lateinit var npHeart: ImageView
    private lateinit var npPosition: TextView
    private lateinit var npDuration: TextView
    private lateinit var seek: ZuneProgressBar
    private lateinit var playPause: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var repeatButton: ImageView

    /** The strip along the foot of the hub, and the commands that rise out of it. */
    private lateinit var appBar: LinearLayout
    private lateinit var barMenu: LinearLayout

    /**
     * The two faces of the now playing page: the record, and the list it came from.
     *
     * Tapping the cover turns one into the other, which is what the phone's player did -
     * the art is the whole screen until you want to know what is coming, and then the
     * whole screen is the queue.
     */
    private lateinit var npDetails: LinearLayout

    /** What the two of them sit in, which is sized to whichever one is up. */
    private lateinit var npBody: FrameLayout

    /** The transport, kept so the queue can have its room. See [showQueue]. */
    private lateinit var transportRow: View
    private lateinit var queueScroll: ScrollView
    private lateinit var queueColumn: LinearLayout
    private lateinit var queueFace: LinearLayout
    private var queueShowing = false

    /** The mark that turns the queue on and off, kept so it can show which state it is in. */
    private lateinit var queueButton: ImageView

    /** One row of the queue, against the position in [order] it stands for. */
    private class QueueRowRef(
        val row: View,
        val title: TextView,
        val artist: TextView,
        val position: Int
    )


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

    /** The same, for a row standing for a whole record rather than for a song. */
    private class GroupRowRef(
        val heart: TextView,
        val key: String,
        val column: LinearLayout
    )

    private val groupRows = mutableListOf<GroupRowRef>()

    // ---------------------------------------------------------------- construction

    fun createView(): View {
        loadFavourites()
        loadHistory()
        // Read before anything is built: the transport paints itself from them.
        loadPlaybackModes()

        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        // The art fills the screen behind everything. Zune put the album where the
        // wallpaper would be rather than in a frame beside the title, which is what makes
        // a list of songs feel like it belongs to a record - so it is left nearly at full
        // strength and the type is made to work over it, rather than the picture being
        // washed out until it is safe.
        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = BACKDROP_ALPHA
            visibility = View.GONE
        }
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        // Weakest at the top, where the wordmark is and the picture is at its best, and
        // heaviest at the foot, where the lists and the small type are. A flat wash over
        // the whole thing takes the photograph away; a gradient only takes it away where
        // something has to be read. Held down hard enough for a bright sleeve - covers
        // are as often white as they are dark, and a list of songs has to be legible over
        // either one.
        scrim = View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                if (palette.isDark)
                    intArrayOf(
                        Color.argb(80, 0, 0, 0),
                        Color.argb(170, 0, 0, 0),
                        Color.argb(210, 0, 0, 0)
                    )
                else
                    intArrayOf(
                        Color.argb(100, 255, 255, 255),
                        Color.argb(180, 255, 255, 255),
                        Color.argb(220, 255, 255, 255)
                    )
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
        // The wordmark, in the size the phone used it: big enough to be the first thing on
        // the page and lowercase, because on this platform nothing shouts. It belongs to
        // the panorama rather than sitting above it, so it drifts as the sections go past.
        panorama.setTitle("music")

        // Two sections rather than eight. The library used to be a section per shelf,
        // which meant eight names to page through before the player came round again; the
        // phone's music app put every shelf behind one word - collection - and left the
        // panorama holding only the two things you swipe between while listening.
        //
        // Collection leads, where the phone put now playing, for one reason: the wordmark
        // drifts with the sections, and "music" is five letters. Opening one section along
        // would have the name of the app already half off the left edge, which is a thing
        // "xbox music" could afford and this cannot.
        //
        // The shelves are built here because they are bound as soon as the library is
        // read, and lifted into a page of their own when one is opened - see openShelf.
        favouritesColumn = shelfColumn()
        historyColumn = shelfColumn()
        albumsColumn = shelfColumn()
        songsColumn = shelfColumn()
        playlistsColumn = shelfColumn()
        artistsColumn = shelfColumn()
        newColumn = shelfColumn()
        panorama.addPage("collection", buildCollectionPage())

        panorama.addPage("now playing", buildNowPlayingPage())

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // Opens on the collection rather than on the player: an app that opens on a dead
        // transport and the words "nothing playing" has asked the user to swipe before it
        // has offered them anything.
        panorama.goTo(PAGE_COLLECTION, animated = false)

        appBar = buildAppBar()
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The panorama stops above the strip rather than running under it, so the last row
        // of a shelf is reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(BAR_DP))

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

    /**
     * A shelf's column of rows, made once and bound whenever the library is read.
     *
     * Not put anywhere yet: a shelf lives in a page that is only built when it is opened,
     * and the column moves into it. Padded on both sides because that page has no
     * panorama to give it a left margin.
     */
    private fun shelfColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PAGE_MARGIN_DP), dp(4), dp(PAGE_MARGIN_DP), dp(24))
    }

    // ---------------------------------------------------------------- collection

    /**
     * One word per shelf, and nothing else on the page.
     *
     * This is the section the phone's music app was built around, and the reason it could
     * hold a whole library without becoming a filing cabinet: the panorama stays short
     * enough to swipe, and everything you own is one tap down from a list you can read in
     * a glance. The words are set large and light because they are the page - there is no
     * artwork, no count and no chevron, and the restraint is the design.
     */
    private fun buildCollectionPage(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), dp(PAGE_MARGIN_DP), dp(24))
        }
        column.addView(shelfRow("favourites") { openShelf("favourites", favouritesColumn) }, wide())
        column.addView(shelfRow("artists") { openShelf("artists", artistsColumn) }, wide())
        column.addView(shelfRow("albums") { openShelf("albums", albumsColumn) }, wide())
        column.addView(shelfRow("songs") { openShelf("songs", songsColumn) }, wide())
        column.addView(shelfRow("playlists") { openShelf("playlists", playlistsColumn) }, wide())
        column.addView(shelfRow("history") { openShelf("history", historyColumn) }, wide())
        column.addView(shelfRow("new") { openShelf("new", newColumn) }, wide())
        return ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun shelfRow(label: String, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_light)
            textSize = 27f
            setTextColor(palette.foreground)
            includeFontPadding = false
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }

    /**
     * A shelf, on a page of its own.
     *
     * The column is moved rather than copied: it is bound once when the library is read
     * and the rows in it are the same rows the playing song is picked out of, so building
     * a second set here would leave the wrong one being repainted. Taken off its last
     * parent first, because a view has one.
     */
    private fun openShelf(title: String, column: LinearLayout) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        page.addView(
            rocks.gorjan.gokixp.wp81.MetroPageHeader(context, palette).apply {
                setTitle(title)
                onBack = { dismissOverlay(page) }
            }, wide())
        (column.parent as? android.view.ViewGroup)?.removeView(column)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        // The songs shelf is the one the jump grid scrolls, so it has to be the scroller
        // the grid knows about - which changes every time the shelf is opened afresh.
        if (column === songsColumn) songsScroll = scroll
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        pushOverlay(page)
        repaintRows()
    }

    // ---------------------------------------------------------------- the app bar

    /**
     * The strip along the foot of the hub.
     *
     * The two things you do to a library that are not "find a record and tap it" -
     * start it all at random, and go looking for one thing by name - live here rather
     * than as rows inside a shelf, which is where the phone put them and why a shelf on
     * this platform is nothing but its own contents.
     *
     * Near-black and white whatever the theme, like Internet Explorer's next door: the
     * bar is furniture, not part of the page.
     */
    private fun buildAppBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_COLOUR)
            // Its own taps stop here rather than reaching the page underneath.
            isClickable = true
        }

        barMenu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        bar.addView(barMenu, LinearLayout.LayoutParams(MATCH, WRAP))

        // The three sit together in the middle, at the size and the spacing the shell's own
        // strip uses - see WP81SecondaryBar. Spread to the corners, with the dots pushed
        // out to the right by a gap, they read as unrelated buttons that happen to share a
        // strip; together they read as what this app can be told to do.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // Only the two. Shuffling everything you own was on this strip and is a mark
        // beside the cover as well, where it belongs: it is a setting the player is in
        // rather than a command given to the library, and the same thing offered twice in
        // two senses is worse than either.
        row.addView(circleButton(R.drawable.wp81_nav_search) { showSearch() },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        // The dots wear the ring too, as they do on Start: the button that opens the rest
        // of the commands is a command like the two beside it, and a bare mark next to two
        // ringed ones read as something else - a label, or a thing that had lost its button.
        row.addView(
            circleButton(R.drawable.wp81_handle_menu, closesMenu = false) {
                if (barMenu.visibility == View.VISIBLE) closeBarMenu() else openBarMenu()
            },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                marginStart = dp(GAP_DP)
            }
        )

        bar.addView(row, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))
        return bar
    }

    /**
     * A white ring with a white mark in it, open in the middle.
     *
     * The shape the Start screen puts on a tile in edit mode, without its black fill: on
     * the app bar there is nothing behind the button but the bar, so the ring alone is the
     * button and the strip shows through it. See wp81_appbar_circle.
     */
    private fun circleButton(
        icon: Int,
        /**
         * Whether pressing it puts the bar's own command list away first.
         *
         * True of every command: what is behind the dots was opened to get at one of them,
         * and a list left standing over the page after its command has run is a list nobody
         * asked to keep. False of the dots themselves, which are the one button whose job
         * is that list - closing it before the press is handled would leave them unable to
         * do anything but reopen it.
         */
        closesMenu: Boolean = true,
        onTap: () -> Unit
    ): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                if (closesMenu) closeBarMenu()
                onTap()
            }
            TiltEffect.apply(this)
        }

    /**
     * What the dots reveal: the commands with nowhere else to be.
     *
     * Only those. Repeating the two buttons standing an inch to the left of the dots -
     * which is what the phone's bar did with its own - says nothing here: the two rings
     * are the shuffle mark and a magnifier, and neither is a glyph anybody needs named
     * for them.
     */
    private fun openBarMenu() {
        barMenu.removeAllViews()
        barMenu.addView(barMenuRow("refresh library") { refreshLibrary() }, wide())
        barMenu.visibility = View.VISIBLE
        barMenu.alpha = 0f
        barMenu.translationY = dp(12).toFloat()
        barMenu.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun closeBarMenu() {
        if (barMenu.visibility != View.VISIBLE) return
        barMenu.visibility = View.GONE
        barMenu.removeAllViews()
    }

    private fun barMenuRow(label: String, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                closeBarMenu()
                onTap()
            }
            TiltEffect.apply(this)
        }

    /** Everything you own, in no order, without having to pick a starting point. */
    private fun shuffleAll() {
        if (library.isEmpty()) return
        shuffle = true
        savePlaybackModes()
        repaintModes()
        playFrom(library, library.indices.random())
    }

    // ---------------------------------------------------------------- search

    /**
     * One field and the songs that match it, as you type.
     *
     * Matched against the song, the artist and the record all at once rather than against
     * a chosen field: nobody searching a music library knows or cares which of the three
     * the word they remember belongs to.
     */
    private fun showSearch() {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        page.addView(
            rocks.gorjan.gokixp.wp81.MetroPageHeader(context, palette).apply {
                setTitle("search")
                onBack = { dismissOverlay(page) }
            }, wide())

        val results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), dp(4), dp(PAGE_MARGIN_DP), dp(24))
        }

        val field = android.widget.EditText(context).apply {
            palette.applyToField(this)
            hint = "song, artist or album"
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            isSingleLine = true
            setPadding(dp(10), dp(9), dp(10), dp(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        page.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = dp(PAGE_MARGIN_DP)
            marginEnd = dp(PAGE_MARGIN_DP)
            bottomMargin = dp(10)
        })

        val bindResults = { query: String ->
            results.removeAllViews()
            songRows.removeAll { it.column === results }
            val needle = query.trim().lowercase(Locale.getDefault())
            when {
                needle.isEmpty() ->
                    results.addView(emptyNote("type something to look for"), wide())
                else -> {
                    val found = library.filter {
                        it.title.lowercase(Locale.getDefault()).contains(needle) ||
                            it.artist.lowercase(Locale.getDefault()).contains(needle) ||
                            it.album.lowercase(Locale.getDefault()).contains(needle)
                    }
                    if (found.isEmpty()) {
                        results.addView(emptyNote("nothing here matches that"), wide())
                    } else {
                        for ((index, track) in found.withIndex()) {
                            results.addView(songRow(track, results) {
                                dismissOverlay(page)
                                playFrom(found, index)
                            }, wide())
                        }
                        repaintRows()
                    }
                }
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                bindResults(s?.toString().orEmpty())
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        bindResults("")

        page.addView(ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(results, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        pushOverlay(page)
        field.requestFocus()
        // And the keyboard with it. Search is a page you arrive at in order to type, so
        // arriving at it with a field that has to be tapped first is one tap spent saying
        // what the last one already said.
        field.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------------------------------------------------------- now playing

    /**
     * The player, laid out the way the phone laid it out.
     *
     * The cover on the left with a column of marks beside it, then the times, then the
     * line the song is on, then the transport. Everything is ranged left off the same
     * margin as the type above it - the only round things on the page are the three
     * transport buttons, and they are rings rather than discs because on this platform a
     * filled button would be a tile.
     */
    private fun buildNowPlayingPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(PAGE_MARGIN_DP), dp(10))
        }

        // The cover and its marks stand above both faces and never go away: the art is the
        // way in and out of the queue, and the marks beside it - shuffle, repeat, the
        // heart and the queue itself - are as much use with the running order up as with
        // the song. Only what is about *this* song gives way to the list.
        page.addView(buildCoverRow(), wide())

        // What the queue takes the place of: the times, the line the song is on, and who
        // it is by.
        npDetails = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Elapsed on the left and what is left of the song on the right, said as a
        // countdown rather than as a total: "- 3:18" is how much longer this goes on for,
        // which is the thing anybody actually looks at that line to find out.
        //
        // Both of them, and the line under them, are kept to the width of the cover: the
        // marks beside it are a column of their own, and a rule running out underneath
        // them turns two things into one wide thing.
        val progress = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        npPosition = timeLabel()
        npDuration = timeLabel().apply { gravity = Gravity.END }
        times.addView(npPosition, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(npDuration, LinearLayout.LayoutParams(0, WRAP, 1f))
        progress.addView(times, wide())

        seek = ZuneProgressBar(context, palette).apply {
            onValueChanged = { v ->
                lastSeekAt = android.os.SystemClock.uptimeMillis()
                val duration = durationOrZero()
                if (duration > 0) {
                    val target = (duration * v).toInt()
                    showTimes(target.toLong(), duration.toLong())
                    try {
                        player?.seekTo(target)
                    } catch (e: IllegalStateException) {
                        Log.w("ZuneApp", "Seek before the player was ready", e)
                    }
                }
            }
        }
        progress.addView(seek, wide())
        npDetails.addView(coverWide(progress), wide())

        // The song, then who it is by - and the "by" is written out, because that line is a
        // sentence about the record rather than a field.
        //
        // No mark in front of it. There was one, saying "now playing", on a page whose
        // whole subject is the thing now playing.
        npTitle = TextView(context).apply {
            setPadding(0, dp(10), 0, 0)
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(palette.foreground)
            text = "nothing playing"
        }
        npDetails.addView(npTitle, wide())

        npArtist = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            // Smaller than the song and in the subtle colour: the two lines are a title and
            // its credit, not two titles, and setting them alike made the artist read as a
            // second song.
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            // Under the mark, not under the song: the two lines are one thing said in two
            // parts, and indenting the second to clear a glyph it has nothing to do with
            // was the only ragged left edge on the page.
            setPadding(0, dp(3), 0, 0)
        }
        npDetails.addView(npArtist, wide())

        queueColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        queueScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(queueColumn, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        // Nothing above the list any more. It used to carry a line saying how many songs
        // were behind this one and offering the way back, because the art it replaced was
        // the only way out and it had gone with it. The art stays now, so the line was
        // counting for its own sake.
        val queueFace = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(queueScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        this.queueFace = queueFace

        // The two faces share the space under the cover, and how much space that is
        // depends on which of them is up. The song wraps to its own height, so the
        // transport sits directly under the line naming it, where the thumb goes looking
        // for it; the queue is given everything from the cover down to the foot of the
        // page, and the transport steps aside for it. See showQueue.
        npBody = FrameLayout(context)
        npBody.addView(npDetails, FrameLayout.LayoutParams(MATCH, WRAP))
        npBody.addView(queueFace, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(npBody, LinearLayout.LayoutParams(MATCH, WRAP))

        transportRow = buildTransport()
        page.addView(transportRow, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(16)
        })

        repaintHeart()
        return page
    }

    /**
     * The cover, and the marks that stand beside it.
     *
     * Shuffle, repeat and the heart at the top and the queue at the foot, which is where
     * the phone put them: the two that change how the record plays and the one that says
     * you want to keep it, then - as far from them as the column is tall - the way to what
     * is coming next. The cover takes most of the width and the marks take the rest, by
     * weight rather than in dp, so the square is a square on any screen.
     */
    private fun buildCoverRow(): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // Square off its own width: the height is whatever the width came out as once the
        // weights were shared, which is the only way a proportional cover stays a cover.
        val artFrame = object : FrameLayout(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, widthSpec)
            }
        }
        artPlaceholder = TextView(context).apply {
            text = "music"
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
        row.addView(artFrame, LinearLayout.LayoutParams(0, WRAP, COVER_WEIGHT))

        val marks = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }

        shuffleButton = markButton(R.drawable.wp81_media_shuffle) { toggleShuffle() }
        marks.addView(shuffleButton, markSize())

        repeatButton = markButton(R.drawable.wp81_media_repeat) {
            repeat = !repeat
            savePlaybackModes()
            repaintModes()
        }
        marks.addView(repeatButton, markSize())

        // Under repeat, and outlined rather than solid until it is earned: the other two
        // marks are settings that are either on or off, and this one is a thing the user
        // does to the song in front of them.
        npHeart = markButton(R.drawable.wp81_media_heart) {
            currentTrack()?.let { toggleFavourite(it) }
        }
        marks.addView(npHeart, markSize())

        marks.addView(gap(), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // No gap under this one: it is meant to stand level with the bottom of the cover.
        queueButton = markButton(R.drawable.wp81_media_queue) { showQueue(!queueShowing) }
        marks.addView(queueButton, LinearLayout.LayoutParams(dp(MARK_DP), dp(MARK_DP)))

        row.addView(marks, LinearLayout.LayoutParams(0, MATCH, 1f - COVER_WEIGHT))
        return row
    }

    private fun markSize() = LinearLayout.LayoutParams(dp(MARK_DP), dp(MARK_DP)).apply {
        bottomMargin = dp(MARK_GAP_DP)
    }

    private fun markButton(icon: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }

    private fun timeLabel() = TextView(context).apply {
        text = "0:00"
        typeface = font(R.font.segoeui_regular)
        textSize = 12f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, 0, 0, dp(4))
    }

    /** Elapsed on the left, and what is left of the song counting down on the right. */
    private fun showTimes(positionMs: Long, durationMs: Long) {
        npPosition.text = formatTime(positionMs)
        npDuration.text = "- " + formatTime((durationMs - positionMs).coerceAtLeast(0))
    }

    /**
     * Back, play, forward - three rings spread across the width of the cover.
     *
     * One at each end and one in the middle, rather than three huddled at the left
     * margin: the row is as wide as the record above it, which is what ties the two
     * together, and it puts the two buttons that get pressed by accident as far from
     * each other as the page allows.
     *
     * Nothing else on it: shuffle and repeat used to sit on the ends of this row and have
     * gone up beside the cover, where the phone kept them. A transport with a setting
     * welded to each end reads as five buttons of equal standing, and four of the five
     * are not things anybody presses while a record is on.
     */
    private fun buildTransport(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(transportButton(R.drawable.wp81_media_previous) { skip(-1) })
        row.addView(gap(), LinearLayout.LayoutParams(0, WRAP, 1f))
        playPause = transportButton(R.drawable.wp81_media_play) { togglePlayPause() }
        row.addView(playPause)
        row.addView(gap(), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(transportButton(R.drawable.wp81_media_next) { skip(1) })
        repaintModes()
        return coverWide(row)
    }

    /**
     * Sets a thing to the cover's width.
     *
     * The cover takes its width by weight rather than in dp so that it stays square on
     * any screen, which means nothing else can be given that width as a number either -
     * it has to be shared out the same way, against a spacer standing in for the column
     * of marks.
     */
    private fun coverWide(child: View): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(child, LinearLayout.LayoutParams(0, WRAP, COVER_WEIGHT))
        row.addView(gap(), LinearLayout.LayoutParams(0, WRAP, 1f - COVER_WEIGHT))
        return row
    }

    /**
     * A hole in a row, standing in for the column of marks or holding two buttons apart.
     *
     * A Space rather than a View, which is not a detail: a plain View asked to wrap its
     * content answers with the whole of whatever it was offered, so a spacer built out of
     * one is as tall as the page and pushes everything under it off the bottom.
     */
    private fun gap() = android.widget.Space(context)

    /**
     * On is the accent, off is the same grey the times are set in - and the heart, which
     * is not a setting, swaps its outline for a fill instead of only changing colour.
     */
    /**
     * Shuffle on or off, from wherever it was asked for.
     *
     * The mark beside the cover and the button on the phone's media controls both come
     * through here, so the order the queue is in, what is written down, and what both of
     * those buttons show cannot drift apart.
     */
    private fun toggleShuffle() {
        shuffle = !shuffle
        savePlaybackModes()
        reorder()
        bindQueue()
        repaintModes()
        updateMediaSession()
    }

    private fun repaintModes() {
        paintToggle(shuffleButton, shuffle)
        paintToggle(repeatButton, repeat)
        if (::queueButton.isInitialized) paintToggle(queueButton, queueShowing)
    }

    /**
     * A mark beside the cover that is either on or off.
     *
     * All four of them - shuffle, repeat, the heart and the queue - are the page's own
     * colour and always were; what changes is how strongly. Off is half strength, on is
     * full. They used to go to the accent when they came on, which put four differently
     * coloured marks down the side of the cover on a page whose only other colour was the
     * art: the accent said "this one is special" where all that was meant was "this one is
     * on", and it read differently under every one of the twenty accents.
     */
    private fun paintToggle(button: ImageView, on: Boolean) {
        button.imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
        button.alpha = if (on) 1f else TOGGLE_OFF_ALPHA
    }

    /**
     * A ring with a mark in it, open in the middle so the cover behind shows through.
     *
     * Built here rather than taken from wp81_appbar_circle: that ring is white because the
     * app bar it sits on is always near-black, and these sit on the page, which under a
     * Light theme is white.
     */
    private fun transportButton(icon: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), palette.foreground)
            }
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP), dp(RING_INSET_DP))
            imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
            layoutParams = LinearLayout.LayoutParams(dp(RING_DP), dp(RING_DP))
        }

    // ---------------------------------------------------------------- the queue

    /**
     * The cover is a button and a pair of pages at once.
     *
     * Tapping it turns the page over to what is queued behind the song, which is the
     * reason the art is as large as it is - it is the control, not a picture of one.
     *
     * It used to change track on a sideways flick as well. The page it sits on is a
     * panorama, which pages sideways itself, so the art had to take that gesture away from
     * the app to have it - and the transport under the cover has done the same job all
     * along, with a button that says which way it is going.
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

    /**
     * Turns the space under the cover from the song to the running order, or back.
     *
     * In two moves rather than one, and always in the same direction: what is leaving goes
     * up and out, and what is arriving comes down into the space it left. The transport
     * goes with the song it belongs to - what is left is the cover, its marks, and the list
     * filling everything under them, so the list is the only thing on the page that
     * scrolls rather than the page scrolling as a whole.
     *
     * The height the two faces share is switched between the moves, where nothing is on
     * screen to be cut short by it: switched at the start, the list closing would be
     * clipped to the song's height on its way out, and the transport would be pushed off
     * the bottom of the page on its way back in.
     */
    private fun showQueue(show: Boolean) {
        val next = show && queue.isNotEmpty()
        queueShowing = next
        // The mark that opened it is a toggle, and says which of the two it is showing.
        paintToggle(queueButton, queueShowing)

        val slide = dp(QUEUE_SLIDE_DP).toFloat()
        if (queueShowing) {
            slideOut(npDetails, slide)
            slideOut(transportRow, slide)
            npBody.postDelayed({
                if (!queueShowing) return@postDelayed
                giveQueueTheRoom(true)
                slideIn(queueFace, slide)
                revealPlaying()
            }, QUEUE_SWAP_MS)
        } else {
            slideOut(queueFace, slide)
            npBody.postDelayed({
                if (queueShowing) return@postDelayed
                giveQueueTheRoom(false)
                slideIn(npDetails, slide)
                slideIn(transportRow, slide)
            }, QUEUE_SWAP_MS)
        }
    }

    /** The song is only as tall as it is; the queue takes the rest of the page. */
    private fun giveQueueTheRoom(give: Boolean) {
        npBody.layoutParams = (npBody.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (give) 0 else WRAP
            weight = if (give) 1f else 0f
        }
    }

    /**
     * Opened on the song that is playing rather than at the top: forty songs in, the top
     * of the list is not where the user is.
     */
    private fun revealPlaying() {
        val playing = queueRows.firstOrNull { it.position == orderPos } ?: return
        queueScroll.post {
            queueScroll.smoothScrollTo(0, (playing.row.top - dp(QUEUE_LEAD_DP)).coerceAtLeast(0))
        }
    }

    /** Up and out of the way, and gone once it is there. */
    private fun slideOut(view: View, distance: Float) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(-distance)
            .setDuration(QUEUE_SWAP_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (view.alpha == 0f) view.visibility = View.GONE }
            .start()
    }

    /** Down from above, into the space the last thing left. */
    private fun slideIn(view: View, distance: Float) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = -distance
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(QUEUE_SWAP_MS)
            .setInterpolator(DecelerateInterpolator())
            // Cleared, or the hide's "go away" fires at the end of a show - a
            // ViewPropertyAnimator keeps the end action it was last given.
            .withEndAction(null)
            .start()
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

    /** An album, an artist or a playlist: art, name, and what is underneath it. */
    private fun groupRow(
        kind: String,
        title: String,
        subtitle: String,
        albumId: Long,
        tracks: List<ZuneTrack>,
        column: LinearLayout,
        onTap: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { onTap() }
            setOnLongClickListener {
                showGroupSheet(kind, title, tracks)
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

        // The same heart a song wears, at the same end of the row, because it means the
        // same thing: this one is kept.
        val heart = TextView(context).apply {
            this.text = HEART
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(heart, LinearLayout.LayoutParams(WRAP, WRAP))

        groupRows.add(GroupRowRef(heart, groupKey(kind, title), column))
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

    private fun queryTracks(): List<ZuneTrack> = ZuneLibrary.queryTracks(context)

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
        if (!::songsScroll.isInitialized) return
        val header = songHeaders[letter.lowercaseChar()] ?: return
        val scroll = songsScroll
        scroll.post { scroll.smoothScrollTo(0, header.top) }
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
        if (::barMenu.isInitialized && barMenu.visibility == View.VISIBLE) {
            closeBarMenu()
            return true
        }
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
            // Turns shuffle on rather than shuffling once behind the user's back: the
            // toggle on the now playing screen has to agree with what is happening.
            setOnClickListener { shuffleAll() }
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

    /**
     * What has been kept: whole records first, then the songs kept one at a time.
     *
     * The records go on top because there are far fewer of them and because each one
     * stands for a dozen songs - listed underneath, three albums would sit at the bottom
     * of forty songs and keeping an album would have bought the user nothing.
     */
    private fun bindFavourites(message: String?, onTap: (() -> Unit)?) {
        favouritesColumn.removeAllViews()
        songRows.removeAll { it.column === favouritesColumn }
        groupRows.removeAll { it.column === favouritesColumn }
        if (message != null) {
            favouritesColumn.addView(emptyNote(message, onTap), wide())
            return
        }
        val records = keptGroups()
        val kept = library.filter { it.id in favourites }
        if (records.isEmpty() && kept.isEmpty()) {
            favouritesColumn.addView(
                emptyNote("nothing kept yet.  hold a song or a record to keep it here"), wide())
            return
        }
        for (record in records) {
            val albumId = record.tracks.first().albumId
            favouritesColumn.addView(
                groupRow(
                    record.kind,
                    record.name,
                    groupSubtitle(record.kind, record.tracks),
                    albumId,
                    record.tracks,
                    favouritesColumn
                ) {
                    showGroup(
                        record.kind,
                        record.name,
                        groupPageSubtitle(record.kind, record.tracks),
                        albumId,
                        record.tracks
                    )
                }, wide())
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
        groupRows.removeAll { it.column === albumsColumn }
        if (message != null) {
            albumsColumn.addView(emptyNote(message), wide())
            return
        }
        for ((name, tracks) in albumGroups()) {
            albumsColumn.addView(
                groupRow(
                    GROUP_ALBUM,
                    name,
                    groupSubtitle(GROUP_ALBUM, tracks),
                    tracks.first().albumId,
                    tracks,
                    albumsColumn
                ) {
                    showGroup(
                        GROUP_ALBUM,
                        name,
                        groupPageSubtitle(GROUP_ALBUM, tracks),
                        tracks.first().albumId,
                        tracks
                    )
                }, wide())
        }
        if (albumsColumn.childCount == 0) {
            albumsColumn.addView(emptyNote("nothing here is filed under an album"), wide())
        }
    }

    private fun bindArtists(message: String?) {
        artistsColumn.removeAllViews()
        groupRows.removeAll { it.column === artistsColumn }
        if (message != null) {
            artistsColumn.addView(emptyNote(message), wide())
            return
        }
        for ((name, tracks) in artistGroups()) {
            artistsColumn.addView(
                groupRow(
                    GROUP_ARTIST,
                    name,
                    groupSubtitle(GROUP_ARTIST, tracks),
                    tracks.first().albumId,
                    tracks,
                    artistsColumn
                ) {
                    showGroup(
                        GROUP_ARTIST,
                        name,
                        groupPageSubtitle(GROUP_ARTIST, tracks),
                        tracks.first().albumId,
                        tracks
                    )
                }, wide())
        }
        if (artistsColumn.childCount == 0) {
            artistsColumn.addView(emptyNote("nothing here is filed under an artist"), wide())
        }
    }

    private fun bindPlaylists(message: String?) {
        playlistsColumn.removeAllViews()
        groupRows.removeAll { it.column === playlistsColumn }
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
                groupRow(
                    GROUP_PLAYLIST,
                    name,
                    groupSubtitle(GROUP_PLAYLIST, tracks),
                    tracks.first().albumId,
                    tracks,
                    playlistsColumn
                ) {
                    showGroup(
                        GROUP_PLAYLIST,
                        name,
                        groupPageSubtitle(GROUP_PLAYLIST, tracks),
                        tracks.first().albumId,
                        tracks
                    )
                }, wide())
        }
    }

    // ---------------------------------------------------------------- records

    /**
     * The albums, in the order the shelf shows them.
     *
     * Grouped by name rather than by album id: the same record ripped twice lands under
     * two ids, and a user looking at their own shelf does not care. Shared with the
     * favourites page so that a kept album is the same songs there as on the shelf, and
     * so that keeping one and then buying the track that was missing from it leaves the
     * favourite holding the record as it now stands.
     */
    private fun albumGroups(): List<Pair<String, List<ZuneTrack>>> =
        library.filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .entries.sortedBy { it.key.lowercase(Locale.getDefault()) }
            .map { it.key to it.value }

    private fun artistGroups(): List<Pair<String, List<ZuneTrack>>> =
        library.filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .entries.sortedBy { it.key.lowercase(Locale.getDefault()) }
            .map { it.key to it.value }

    /** What goes under a record's name in a list: who it is by, and how much of it there is. */
    private fun groupSubtitle(kind: String, tracks: List<ZuneTrack>): String = when (kind) {
        GROUP_ALBUM -> listOf(albumArtist(tracks), "${tracks.size} songs")
            .filter { it.isNotBlank() }
            .joinToString("   ")
        GROUP_ARTIST -> {
            val albums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct().size
            listOfNotNull(
                "${tracks.size} songs",
                if (albums > 0) "$albums albums" else null
            ).joinToString("   ")
        }
        else -> "${tracks.size} songs"
    }

    /**
     * The same line on the record's own page, where the count and the length are already
     * printed underneath - so an album says only who it is by.
     */
    private fun groupPageSubtitle(kind: String, tracks: List<ZuneTrack>): String =
        if (kind == GROUP_ALBUM) albumArtist(tracks) else groupSubtitle(kind, tracks)

    /** Who a record is by, taken off the first song on it that admits to a name. */
    private fun albumArtist(tracks: List<ZuneTrack>): String =
        tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty()

    /** A record kept whole, resolved against what is on the phone now. */
    private class KeptGroup(
        val kind: String,
        val name: String,
        val tracks: List<ZuneTrack>
    )

    /**
     * The kept records that still exist, albums then artists then playlists.
     *
     * Resolved against the library on each bind rather than held as a list of songs, for
     * the same reason history is: a record can leave the phone between one launch and the
     * next, and one whose songs have all gone is not shown rather than being offered as a
     * row that plays nothing. The key stays in the store either way - a record put back on
     * the phone is a record the user still kept.
     */
    private fun keptGroups(): List<KeptGroup> {
        if (favouriteGroups.isEmpty()) return emptyList()
        val out = mutableListOf<KeptGroup>()
        for (kind in GROUP_KINDS) {
            val prefix = kind + GROUP_SEPARATOR
            if (favouriteGroups.none { it.startsWith(prefix) }) continue
            val shelf = when (kind) {
                GROUP_ALBUM -> albumGroups()
                GROUP_ARTIST -> artistGroups()
                else -> playlists
            }
            for ((name, tracks) in shelf) {
                if (prefix + name in favouriteGroups) out.add(KeptGroup(kind, name, tracks))
            }
        }
        return out
    }

    /**
     * The song that is playing is the only one in the accent, in whichever list it is in,
     * and a kept song - or a kept record - is the only one wearing a heart.
     */
    private fun repaintRows() {
        val playingId = currentTrack()?.id
        for (row in songRows) {
            row.title.setTextColor(
                if (row.id == playingId) palette.accent else palette.foreground)
            row.heart.setTextColor(
                if (row.id in favourites) palette.accent else Color.TRANSPARENT)
        }
        for (row in groupRows) {
            row.heart.setTextColor(
                if (row.key in favouriteGroups) palette.accent else Color.TRANSPARENT)
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
        kind: String,
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

        // The heart the rows wear, one level up. A hold on the row is how a record is kept
        // from a list, but once you are inside the record that row is off screen, and this
        // is the page where somebody decides they want to keep the thing.
        val pageHeart = TextView(context).apply {
            text = HEART
            textSize = 22f
            setPadding(dp(10), dp(8), 0, dp(8))
            isClickable = true
            TiltEffect.apply(this)
        }
        val paintPageHeart = {
            pageHeart.setTextColor(
                if (groupKey(kind, title) in favouriteGroups) palette.accent
                else palette.foregroundSubtle
            )
        }
        paintPageHeart()
        pageHeart.setOnClickListener {
            toggleFavouriteGroup(kind, title)
            paintPageHeart()
        }
        top.addView(pageHeart, LinearLayout.LayoutParams(WRAP, WRAP))

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
     * The same gesture a song has, answering the same question one level up. Keeping a
     * record here keeps the record: it turns up in favourites as the one row you meant,
     * which opens onto the songs, rather than as those songs spilled loose into a list.
     */
    private fun showGroupSheet(kind: String, title: String, tracks: List<ZuneTrack>) {
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

        val kept = groupKey(kind, title) in favouriteGroups
        sheet.addView(
            sheetRow(if (kept) "remove from favourites" else "add to favourites") {
                dismissOverlay(overlay)
                toggleFavouriteGroup(kind, title)
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
        // A kept playlist just grew by one, and its row says how long it is.
        bindFavourites(null, null)
        repaintRows()
    }

    // ---------------------------------------------------------------- favourites

    /**
     * How a record is written down.
     *
     * The kind goes first so that an album and an artist of the same name - which happens
     * every time somebody names a record after themselves - are two different favourites.
     */
    private fun groupKey(kind: String, name: String) = kind + GROUP_SEPARATOR + name

    private fun toggleFavouriteGroup(kind: String, name: String) {
        val key = groupKey(kind, name)
        if (!favouriteGroups.add(key)) favouriteGroups.remove(key)
        saveFavourites()
        bindFavourites(null, null)
        repaintRows()
    }

    private fun toggleFavourite(track: ZuneTrack) {
        if (!favourites.add(track.id)) favourites.remove(track.id)
        saveFavourites()
        repaintHeart()
        // The favourites list is the one that changed shape, so it is the one rebuilt.
        bindFavourites(null, null)
        repaintRows()
        // And the phone's own controls, which carry the same heart. Only for the song that
        // is playing: the heart on a row further down the library is not the one on the
        // lock screen.
        if (track.id == currentTrack()?.id) updateMediaSession()
    }

    private fun repaintHeart() {
        val track = currentTrack()
        val kept = track != null && track.id in favourites
        // Outline or fill, which is the heart's own way of saying it - and then the same
        // half-and-full the other marks use. Nothing playing is off, since there is
        // nothing there to keep.
        npHeart.setImageResource(
            if (kept) R.drawable.wp81_media_heart_filled else R.drawable.wp81_media_heart)
        paintToggle(npHeart, kept)
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        favourites.clear()
        prefs.getString(KEY_FAVOURITES, "").orEmpty()
            .split(",").mapNotNullTo(favourites) { it.trim().toLongOrNull() }
        // Kept records are separated by newlines rather than by commas: half the albums
        // ever pressed have a comma in their name, and one of those would otherwise take
        // the rest of the list down with it. A line with no kind on it is not a record.
        favouriteGroups.clear()
        prefs.getString(KEY_FAVOURITE_GROUPS, "").orEmpty()
            .split("\n").filterTo(favouriteGroups) { it.contains(GROUP_SEPARATOR) }
    }

    private fun saveFavourites() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAVOURITES, favourites.joinToString(","))
            .putString(KEY_FAVOURITE_GROUPS, favouriteGroups.joinToString("\n"))
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
        goToNowPlaying()
    }

    /**
     * Puts everything away and shows the player.
     *
     * Tapping a song is a request to hear it, not to read about it. Panning the panorama
     * on its own was not enough: the shelves and the record pages are laid over the top
     * of it, so the section changed where nobody could see it and the user was left
     * looking at the list they had just tapped out of.
     */
    private fun goToNowPlaying() {
        closeBarMenu()
        hideJumpList()
        while (overlays.isNotEmpty()) dismissOverlay(overlays.last())
        panorama.goTo(PAGE_NOW_PLAYING, animated = true)
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
            // Nothing else in this process is playing by the time this one starts. See
            // ZuneAudio - the car service has a player of its own, and a tile that sent
            // "play" to the wrong one of our two sessions could set both of them going.
            ZuneAudio.claim(this)
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
        // "by Paul Lackey", written out, because the line under the song is a sentence
        // about it rather than a field with a value in it. The record is not named: the
        // cover directly above is already the record.
        npArtist.text = if (track.artist.isBlank()) "" else "by ${track.artist}"
        showTimes(0L, track.durationMs)
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
            // Resuming is starting to make sound, so it claims the speaker exactly as a
            // fresh track does - the pause may have been the car service taking it.
            ZuneAudio.claim(this)
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
                currentTrack()?.let { showTimes(0L, it.durationMs) }
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
                        showTimes(active.currentPosition.toLong(), duration.toLong())
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
            CHANNEL_ID, "Music playback", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the song Music is playing"
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

                /**
                 * The heart, from the phone's own controls.
                 *
                 * Same code the mark beside the cover runs, so the player cannot end up in
                 * one state on screen and another on the lock screen: this writes to the
                 * list the page reads, and the page is repainted from it.
                 */
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_FAVOURITE) currentTrack()?.let { toggleFavourite(it) }
                }
            })
            isActive = true
        }
        // Registered with the process's one-player rule, so anything else that starts can
        // put this one down first.
        ZuneAudio.register(this) { silence() }
    }

    /**
     * Stops making sound, because something else in this process is about to.
     *
     * A pause rather than a stop: the song, the queue and the position are all still here,
     * and the page goes on showing them - so coming back to the app and pressing play
     * carries on where it was, which is what a player interrupted by another player should
     * do. Posted to the main thread: the car service calls this from whichever thread its
     * head unit talked to it on, and this touches the page.
     */
    private fun silence() {
        handler.post {
            if (!isPlaying) return@post
            try {
                player?.pause()
            } catch (e: IllegalStateException) {
                Log.w("ZuneApp", "Player would not pause", e)
            }
            isPlaying = false
            onPlaybackChanged()
        }
    }

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val track = currentTrack() ?: return

        val kept = track.id in favourites
        session.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                        android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                // The heart, in the slot the phone's media controls leave for an app's own
                // command. Rebuilt on every update because a custom action carries its
                // state in what it is made of - filled or outlined by which drawable it is
                // given. Shuffle was here too and is not any more: the system tints these
                // itself, so it could only say which way it was set in a label nobody
                // reads, and a button that gives no sign of what it did is worse than the
                // mark beside the cover that does.
                .addCustomAction(
                    android.media.session.PlaybackState.CustomAction.Builder(
                        ACTION_FAVOURITE,
                        if (kept) "Remove from favourites" else "Add to favourites",
                        if (kept) R.drawable.wp81_media_heart_filled
                        else R.drawable.wp81_media_heart
                    ).build()
                )
                .setState(
                    if (isPlaying) android.media.session.PlaybackState.STATE_PLAYING
                    else android.media.session.PlaybackState.STATE_PAUSED,
                    player?.currentPosition?.toLong() ?: 0L,
                    1f
                )
                .build()
        )
        // Said twice: once now, with whatever the words are, and again when the cover has
        // been read. The art is decoded off the main thread the first time an album is
        // seen and held after that, so waiting for it before saying anything would leave
        // the phone's controls blank for as long as that takes on every track change - and
        // an album with no cover would leave them blank for good.
        session.setMetadata(metadataFor(track, null))
        ZuneArt.load(context, track.albumId) { art ->
            // The song may have moved on while the cover was being decoded, and a cover
            // under the wrong title is worse than none.
            if (art != null && currentTrack()?.id == track.id) {
                session.setMetadata(metadataFor(track, art))
            }
        }

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist.ifBlank { "Music" })
            // The badge, on a canvas cut to fit it - see wp81_notification_music. The tile's
            // own glyph is drawn with air round it, and the system scales that air into the
            // badge along with the mark.
            .setSmallIcon(R.drawable.wp81_notification_music)
            .setStyle(
                android.app.Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setOngoing(isPlaying)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * What the phone's own controls know about the song: the words, and the cover.
     *
     * The cover is what the media panel and the lock screen draw behind the transport, and
     * what a watch or a car head unit shows - none of which can see this app's own page,
     * where the art has been all along.
     */
    private fun metadataFor(track: ZuneTrack, art: Bitmap?): android.media.MediaMetadata =
        android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(
                android.media.MediaMetadata.METADATA_KEY_ARTIST,
                track.artist.ifBlank { "Music" })
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
            .apply {
                if (art != null) {
                    putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                }
            }
            .build()

    /** Stops everything and takes the notification down. Called when the window closes. */
    fun cleanup() {
        ZuneAudio.unregister(this)
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
        const val PAGE_COLLECTION = 0
        const val PAGE_NOW_PLAYING = 1

        /** How strongly the album art shows through behind the page. */
        const val BACKDROP_ALPHA = 0.55f

        const val PAGE_MARGIN_DP = 22
        const val ROW_ART_DP = 48
        const val GROUP_ART_DP = 62

        /** The cover at the head of an album's own page, which is a page about the record. */
        const val GROUP_PAGE_ART_DP = 108

        /** How much of the width the cover takes; the marks beside it have the rest. */
        const val COVER_WEIGHT = 0.58f

        /**
         * A mark in the column beside the cover, and the gap under it.
         *
         * The glyphs are drawn well inside their own box - the traced set leaves a third
         * of the grid empty around the mark - so the box has to be a good deal larger
         * than the mark is meant to look. Sized against the phone's own screens rather
         * than against the box.
         */
        const val MARK_DP = 40
        const val MARK_GAP_DP = 16

        /** The mark in front of the song's name, which is a mark beside a word. */

        /** How far a mark beside the cover is dimmed when what it stands for is off. */
        const val TOGGLE_OFF_ALPHA = 0.5f

        /** How long each half of the swap between the song and the queue takes, and how
         *  far the two of them travel over it. */
        const val QUEUE_SWAP_MS = 140L
        const val QUEUE_SLIDE_DP = 18

        /** What the phone's media controls call back with. See setupMediaSession. */
        const val ACTION_FAVOURITE = "rocks.gorjan.gokixp.zune.FAVOURITE"

        /** The transport's rings, and the inset around the mark inside one. */
        const val RING_DP = 56
        const val RING_INSET_DP = 5

        // The app bar, which is the shell's own strip - see MetroIEApp, which has the same
        // one. Always this near-black with white on it, whatever the theme: that is what
        // the bar was on the phone, and it is the one part of a page that is not the page.
        const val BAR_COLOUR = 0xFF212021.toInt()
        const val BAR_DP = 62
        const val BUTTON_DP = 44
        const val GLYPH_INSET_DP = 4

        /** Between the rings, as on the shell's own strip. */
        const val GAP_DP = 28

        /** Shortest drag across the cover that counts as a skip. */

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
        /** The three kinds of record that can be kept whole, and how one is written down. */
        const val GROUP_ALBUM = "album"
        const val GROUP_ARTIST = "artist"
        const val GROUP_PLAYLIST = "playlist"
        const val GROUP_SEPARATOR = "|"
        val GROUP_KINDS = listOf(GROUP_ALBUM, GROUP_ARTIST, GROUP_PLAYLIST)

        const val KEY_FAVOURITES = "favourite_track_ids"
        const val KEY_FAVOURITE_GROUPS = "favourite_records"
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

/**
 * The line under the times on the now playing screen.
 *
 * A hair of a track with no thumb on it at all, which is what the phone's player had and
 * what the shell's own slider is not: MetroSlider carries a square handle because it is
 * the control you set brightness with, and a handle here reads as something to be aimed
 * at rather than as how far through the song you are. Dragging still seeks - the whole
 * line is the target, and it is given a finger's worth of height to be hit in even though
 * it draws two pixels of it.
 */
private class ZuneProgressBar(
    context: Context,
    palette: WP81Palette
) : View(context) {

    var onValueChanged: ((Float) -> Unit)? = null

    /** How far through, 0 to 1. */
    var value: Float = 0f
        set(v) {
            val clamped = v.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    private val goneColour = palette.foreground
    private val leftColour = palette.inactive
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthSpec),
            (HEIGHT_DP * resources.displayMetrics.density).toInt()
        )
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val track = TRACK_DP * resources.displayMetrics.density
        val top = (height - track) / 2f
        val x = width * value

        paint.color = leftColour
        canvas.drawRect(0f, top, width.toFloat(), top + track, paint)

        paint.color = goneColour
        canvas.drawRect(0f, top, x, top + track, paint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                // The panorama pages on a horizontal drag and would take this one first.
                parent?.requestDisallowInterceptTouchEvent(true)
                if (width > 0) {
                    val next = (event.x / width).coerceIn(0f, 1f)
                    if (next != value) {
                        value = next
                        onValueChanged?.invoke(next)
                    }
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** Drawn thin and touched thick: the line is two pixels, the target is a thumb. */
        const val TRACK_DP = 2f
        const val HEIGHT_DP = 26f
    }
}
