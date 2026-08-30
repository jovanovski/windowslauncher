package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 Start screen: a vertically scrolling wall of live tiles.
 *
 * Long-pressing a tile puts the screen into **edit mode** and simultaneously grabs that
 * tile for dragging, exactly as WP8.1 did - one gesture, not two. In edit mode every
 * tile shows an unpin button and a resize chevron, and tapping empty space leaves.
 */
@SuppressLint("ViewConstructor")
class StartScreenView(
    context: Context,
    private var palette: WP81Palette
) : ScrollView(context) {

    var onLaunch: ((Tile) -> Unit)? = null
    var onTilesChanged: ((List<Tile>) -> Unit)? = null
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    /** Pulled down while already at the top of Start. */
    var onSwipeDownAtTop: (() -> Unit)? = null

    /** Pushed up while already at the bottom of Start. */
    var onSwipeUpAtBottom: (() -> Unit)? = null

    private val grid = TileGridLayout(context)

    /**
     * The wall and whatever sits under it, which the scroller actually holds.
     *
     * The grid used to be the scroller's only child. It cannot be any more: the arrow to
     * the app list belongs below the last row and has to scroll with it, and the grid is a
     * packer of tiles rather than a column that will take anything.
     */
    private val content = LinearLayout(context)

    /** The way to the app list, for anyone who has not found the swipe. */
    private val appListArrow = ImageView(context)

    var onOpenAppList: (() -> Unit)? = null

    /**
     * A folder tile was tapped and wants its contents.
     *
     * The wall does not know what is in a folder - the host does - so it asks, and opens
     * whatever comes back in place under the tile. Returning an empty list leaves the
     * folder closed.
     */
    var onFolderOpened: ((Tile) -> Unit)? = null

    /**
     * A folder's own arrangement changed - a tile inside it was resized.
     *
     * Separate from [onTilesChanged] because it is a different list kept in a different
     * place: these tiles are filed inside the folder, not pinned to Start.
     */
    var onFolderTilesChanged: ((String, List<Tile>) -> Unit)? = null

    /**
     * A tile was dropped somewhere that changes which list it belongs to.
     *
     * The folder id it landed in, or null for "out of whatever folder it was in and onto
     * Start". Filing into a closed folder is a drop on an offer that was already showing,
     * never merely a drop on a folder: a tile passing over one on its way somewhere else
     * has not been put in it, and swallowing tiles in transit is the one thing a drag onto
     * a folder must not do.
     */
    var onTileFiled: ((Tile, String?, Boolean) -> Unit)? = null

    /** The name at the head of an opened folder was tapped. */
    var onFolderRename: ((Tile) -> Unit)? = null

    /**
     * Two tiles were held together long enough to become a folder, and then let go.
     *
     * The first is the one that was picked up, the second the one it was held over. A
     * folder is made where the second was standing, with both of them in it.
     */
    var onTilesFoldered: ((Tile, Tile) -> Unit)? = null

    /**
     * What a folder holding these tiles would look like, for the preview shown while they
     * are being held together. Answered by the host, which owns the artwork.
     */
    var folderPreviewOf: ((List<Tile>) -> List<FolderPreviewView.Entry>)? = null

    /**
     * What an existing folder would show with one more tile in it - the folder first, the
     * arrival second - for the preview shown while the two are being held together.
     */
    var folderPreviewWith: ((Tile, Tile) -> List<FolderPreviewView.Entry>)? = null

    private val tiles = mutableListOf<Tile>()

    /**
     * The one tile currently showing its handles, if any.
     *
     * Editing is per-tile rather than a mode the whole screen enters: long-pressing a tile
     * selects exactly that one, and nothing else responds until it is dismissed. Putting
     * handles on every tile at once made it ambiguous which one a drag belonged to.
     */
    private var editingView: TileView? = null

    val isEditMode: Boolean
        get() = editingView != null

    /** The tile currently selected for editing, if any. */
    val editingTile: Tile?
        get() = editingView?.tile

    // Start background, shared by every tile. See TileView.setStartBackground.
    private var startBackground: Bitmap? = null
    private var backgroundFocusX = 0.5f
    private var lastSignature = 0

    // --- Drift ------------------------------------------------------------------------
    // The photo can be given a little slack in the crop and moved about inside it, which
    // turns a static wallpaper into something the tiles appear to be windows onto. The
    // slack has to be built into the crop: without it the image would come off its edges
    // and leave tiles with nothing behind them.

    /** Total travel, in pixels, on each axis. Zero when the effect is off. */
    private var driftRange = 0

    // Where in that travel the photo currently sits, in pixels from the centre.
    private var driftX = 0f
    private var driftY = 0f

    private val drift = WallpaperDrift(context) { x, y -> applyDrift(x, y) }

    /**
     * Whether the background wanders behind the tiles.
     *
     * Turning it on re-crops the photo: the travel is cut out of the image rather than
     * added around it, so a tile is never left looking past the edge of the picture.
     */
    var driftEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pushBackgroundToTiles()
            syncDrift()
        }

    /**
     * How far the photo actually travels per pixel scrolled.
     *
     * Derived from the pan range the zoom really provides rather than from
     * [PARALLAX_FACTOR] directly: once MAX_OVERSCAN caps the zoom on a long Start screen,
     * travelling at the nominal rate would slide the photo off the top and leave the
     * bottom of the screen bare.
     */
    private var effectiveParallax = 0f

    /**
     * Room kept in the crop at each end for the over-pull, in pixels.
     *
     * The resting offset sits this far into the picture, so the photo has somewhere to go
     * when the wall is pulled off either end of its travel. See [pushBackgroundToTiles].
     */
    private var overscrollSlack = 0

    // Drag state. The grab offset is where inside the tile the finger landed, so the tile
    // keeps that exact spot under the finger for the whole drag.
    private var dragView: TileView? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private val screenOrigin = IntArray(2)
    private var pendingDragView: TileView? = null
    private var pressRawX = 0f
    private var pressRawY = 0f
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f

    // --- Edge scrolling ----------------------------------------------------------------
    // A tile can be dragged past the bottom of the screen onto a row that is not on it.
    // Without this the page stayed put and there was no way to move a tile down the wall
    // except in screenfuls: drop it, scroll, pick it up again.

    /** Pixels to scroll per frame while the finger is in an edge band. Signed. */
    private var edgeScrollSpeed = 0

    private val edgeScroll = object : Runnable {
        override fun run() {
            val tile = dragView
            if (tile == null || edgeScrollSpeed == 0) return
            val before = scrollY
            scrollBy(0, edgeScrollSpeed)
            if (scrollY != before) {
                // The finger has not moved, but the wall under it has: the tile is placed
                // from absolute coordinates, so it has to be told, and where it now sits
                // may be somebody else's slot.
                followFinger(tile, lastMoveRawX, lastMoveRawY)
                reorderUnder(tile)
            }
            postOnAnimation(this)
        }
    }

    /**
     * Sets the page scrolling when a dragged tile is held near the top or bottom.
     *
     * Speed rises with how far into the band the finger is, so easing towards the edge
     * creeps and pushing right to it moves at a useful rate - the same shape of response
     * as dragging a selection past the edge of a text field.
     */
    private fun trackEdgeScroll(rawY: Float) {
        getLocationOnScreen(screenOrigin)
        val top = screenOrigin[1].toFloat()
        val y = rawY - top
        val band = EDGE_SCROLL_BAND_DP * resources.displayMetrics.density

        val depth = when {
            y < band -> -(band - y) / band
            y > height - band -> (y - (height - band)) / band
            else -> 0f
        }.coerceIn(-1f, 1f)

        val wanted =
            (depth * EDGE_SCROLL_MAX_DP * resources.displayMetrics.density).toInt()
        // Nothing to scroll towards: at the ends, leave it alone rather than fighting the
        // over-scroll.
        val blocked = (wanted < 0 && scrollY <= 0) ||
            (wanted > 0 && scrollY >= scrollRange())
        val next = if (blocked) 0 else wanted

        if (next == edgeScrollSpeed) return
        val wasIdle = edgeScrollSpeed == 0
        edgeScrollSpeed = next
        if (next != 0 && wasIdle) postOnAnimation(edgeScroll)
    }

    private fun stopEdgeScroll() {
        edgeScrollSpeed = 0
        removeCallbacks(edgeScroll)
    }
    private var lastReorderAt = 0L

    /** The slot the finger has been asking for, and since when. See [reorderUnder]. */
    private var pendingReorderAt = -1
    private var pendingReorderSince = 0L
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // Edge-swipe state: pull down at the top, push up at the bottom.
    private var edgeSwipeStartY = 0f
    private var pullDownArmed = false
    private var pushUpArmed = false
    /**
     * How far the wall has to be pushed off either end before something else takes over -
     * the shade at the top, the app list at the bottom.
     *
     * Several times the plain edge swipe, and the same at both ends. The wall moves with
     * the finger for the whole of it, so the gesture is something the user is doing rather
     * than a distance to be covered, and what arrives while a thumb is still travelling is
     * something nobody asked for. The give is what buys the longer throw: without it the
     * distance was all the gesture had, so it had to be short.
     */
    private val edgeGiveThreshold by lazy {
        EDGE_SWIPE_DP * PULL_DOWN_FACTOR * resources.displayMetrics.density
    }


    // Resize-handle drag state
    private var resizeTouchRawX = 0f
    private var resizeTouchRawY = 0f
    private var resizeStartWidth = 0f
    private var resizeStartHeight = 0f

    init {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        // An edit handle is centred on its tile's corner, so half of it lies outside the
        // cell the grid gave that tile. The grid has to stop clipping for it to be drawn.
        //
        grid.clipChildren = false

        // And the scroller has to stop clipping the *grid* to the grid, for the same
        // reason: a parent clips each child to that child's own bounds, so the handle on
        // the last row was cut off by the end of the wall it hangs below.
        //
        // Its own bounds still clip - clipToPadding is left alone and there is no padding
        // to shrink it - so nothing is drawn over the navigation bar. Only the grid's
        // edges stop being a boundary.
        clipChildren = false

        // Room under the last row to put that handle in, so scrolling to the end does not
        // leave it pressed against the foot of the window.
        grid.bottomReservePx =
            (TileView.HANDLE_OVERHANG_DP * resources.displayMetrics.density).toInt()

        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(buildAppListArrow(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setBackgroundColor(palette.background)
        setOnClickListener { if (isEditMode) exitEditMode() }
    }

    // ---------------------------------------------------------------- data

    /**
     * Rebuilds the whole Start screen.
     *
     * [liveWidget] supplies the reading for the built-in live widgets, which show their
     * content on the front face permanently rather than flipping to reveal it. Returning
     * null leaves the tile as an ordinary icon tile.
     */
    fun setTiles(
        newTiles: List<Tile>,
        liveWidget: (Tile) -> TileView.Reading? = { null },
        widgetGlyphs: (Tile) -> Pair<Int?, Int?> = { null to null },
        widgetBacks: (Tile) -> TileView.Reading? = { null },
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        // Rebuilding drops the selection, and that has to be announced: the key strip
        // switches to the edit commands off this callback, and would otherwise be left
        // showing them with nothing selected - and with "done" doing nothing, because
        // exitEditMode() finds no selection to clear.
        val wasEditing = editingView != null
        editingView = null
        if (wasEditing) onEditModeChanged?.invoke(false)
        // The band is one of the grid's children and is about to be swept away with the
        // rest of them; the wall has to be told, or it goes on believing a folder is open
        // and refuses to open it again.
        closeFolder(animated = false)

        tiles.clear()
        tiles.addAll(newTiles.sortedBy { it.index })
        grid.removeAllViews()
        for (tile in tiles) {
            val view = buildTileView(tile, glyphs)
            if (tile.kind.isLiveWidget) {
                liveWidget(tile)?.let { reading -> view.setLiveWidget(reading) }
            }
            val (frontGlyph, backGlyph) = widgetGlyphs(tile)
            view.setWidgetGlyph(frontGlyph, backGlyph)
            view.setTileColor(tileColors(tile))
            view.setWidgetBack(widgetBacks(tile))
            grid.addView(view)
        }
        grid.requestLayout()
    }

    /** Refreshes one live widget's content in place, without rebuilding the grid. */
    fun setLiveWidgetContent(tileId: String, reading: TileView.Reading) {
        forEachTileView { if (it.tile.id == tileId) it.setLiveWidget(reading) }
    }

    /** Repaints one tile, without rebuilding the wall around it. */
    fun setTileColor(tileId: String, color: Int?) {
        forEachTileView { if (it.tile.id == tileId) it.setTileColor(color) }
    }

    /** Hands one live widget the run of faces it turns through. */
    fun setLiveWidgetRotation(
        tileId: String,
        faces: List<TileView.LiveFace>,
        style: TileView.LiveStyle
    ) {
        forEachTileView { if (it.tile.id == tileId) it.setLiveWidgetRotation(faces, style) }
    }

    /** Hands every tile the loader it fetches face pictures through. */
    fun setBackdropLoader(loader: (String, (android.graphics.Bitmap?) -> Unit) -> Unit) {
        forEachTileView { it.backdropLoader = loader }
    }

    /** Which face a rotating widget is on, so the host knows what a tap should open. */
    fun rotationIndexOf(tileId: String): Int {
        var index = 0
        forEachTileView { if (it.tile.id == tileId) index = it.rotationIndex }
        return index
    }

    /** Refreshes one live widget's corner marks in place: the front's, and the reverse's. */
    fun setWidgetGlyph(tileId: String, front: Int?, back: Int?) {
        forEachTileView { if (it.tile.id == tileId) it.setWidgetGlyph(front, back) }
    }

    /** Refreshes one live widget's reverse in place. */
    fun setWidgetBack(tileId: String, back: TileView.Reading?) {
        forEachTileView { if (it.tile.id == tileId) it.setWidgetBack(back) }
    }

    private fun buildTileView(
        tile: Tile,
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ): TileView {
        val view = TileView(context, tile, palette)
        view.countsEnabled = countsEnabled
        view.applySize()
        view.setGlyph(glyphs(tile))
        startBackground?.let { bmp ->
            view.setStartBackground(bmp, cropFor(bmp, width, height, backgroundFocusX),
                Rect(0, 0, width, height))
        }
        view.setOnClickListener {
            when {
                // Nothing launches while a tile is selected: arranging and opening are
                // different jobs, and a wall of live tiles is far too easy to open by
                // accident while moving one.
                // A folder does not go anywhere: it opens where it is. Turning the wall
                // out for it would be the wall leaving for a page that never arrives.
                !isEditMode && tile.kind == Tile.Kind.FOLDER -> toggleFolder(tile)
                !isEditMode -> launchWithTurnstile(tile)
                // Tapping a different tile moves the selection to it rather than dropping
                // out and making the user press and hold all over again. Resizing three
                // tiles in a row is one gesture and two taps this way, not three
                // long-presses.
                editingView !== view -> enterEditMode(view)
                // Tapping the tile already in hand puts it down, as tapping the wall does.
                else -> exitEditMode()
            }
        }
        view.setOnLongClickListener {
            // A hold on one of the other tiles is a tap on it: the selection moves there,
            // and it is not picked up. Holding one is how arranging *starts*, and once it
            // has started there is nothing left for a hold to mean - but a hold that did
            // nothing at all still buzzed and still left the finger waiting for something.
            //
            // Declined rather than handled, which is what makes it a tap: an unhandled
            // long press is not given the system's long-press buzz, and the release still
            // lands as a click - and the click already moves the selection.
            if (isEditMode && editingView !== view) return@setOnLongClickListener false
            // One continuous gesture: the press selects the tile *and* picks it up, so it
            // can be dragged straight away rather than being put down and grabbed again.
            enterEditMode(view)
            startBodyDrag(view, pressRawX, pressRawY)
            // Nothing buzzes here: the framework gives a held view the system's own
            // long-press tick as soon as the press is claimed, and everything on this
            // shell that answers a hold is claiming one. A second buzz fired by hand was
            // what made a hold feel like two or three separate knocks.
            true
        }
        view.onResizeDrag = { v, event -> handleResizeDrag(v, event) }
        // What "off Start" means differs between a pinned app, a built-in and a tile
        // inside a folder, and only the host knows which of those it is looking at.
        view.onUnpinTap = { onTileUnpin?.invoke(tile) }
        return view
    }

    /** The top-right handle on the selected tile was tapped. See TileView.onUnpinTap. */
    var onTileUnpin: ((Tile) -> Unit)? = null

    /**
     * Whether tiles count their notifications or just mark them. See TileView.countsEnabled.
     *
     * Held here as well as pushed down, because the wall is rebuilt often and a tile made
     * after the setting changed has to be born knowing it.
     */
    /** How many cells the wall is wide. See TileGridLayout.columns. */
    /**
     * How many columns the wall is set to, as the user chose it.
     *
     * A portrait number - three or four across a phone held upright. What the packer is
     * actually given is [packedColumns], which is this scaled to the shape of the screen.
     */
    var columns: Int = TileGridLayout.COLUMNS
        set(value) {
            if (field == value) return
            field = value
            // The packer decides which tile sits where from the width it has, so a change
            // of width is a change of arrangement, and the arrangement is what is stored.
            if (applyColumns()) post { commit() }
        }

    /**
     * Hands the packer the column count the screen's shape asks for.
     *
     * Turned on its side the screen is twice as wide and no taller. Keeping the portrait
     * count there makes every cell twice the size - which is the wall of four enormous
     * tiles with a row and a half showing that landscape used to be - so the count is
     * scaled by how much wider the screen has become. A tile then stays the size it is in
     * portrait, and the width that was gained is spent on more of them.
     */
    private fun applyColumns(): Boolean {
        // The phone's shorter side, whichever way it is being held: that is the width the
        // wall was designed across, and what a tile's size is worked out from.
        val metrics = resources.displayMetrics
        val basis = kotlin.math.min(metrics.widthPixels, metrics.heightPixels)
        grid.metricBasis = basis
        bandGrid?.metricBasis = basis
        val across = TileGridLayout.columnsFor(width, basis, columns)
        if (grid.columns == across) return false
        grid.columns = across
        bandGrid?.columns = across
        return true
    }

    var countsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.countsEnabled = value }
        }

    /**
     * The arrow under the wall, on the right, where the app list is.
     *
     * Windows Phone put one here and it was the only visible way to the list - the swipe
     * is faster once you know about it, and invisible until you do. The disc is the one
     * the edit handles wear, for the same reason: it sits over the page rather than on a
     * tile, and has to read against a photograph as well as against black.
     */
    private fun buildAppListArrow(): View {
        val row = FrameLayout(context)
        appListArrow.setImageResource(R.drawable.wp81_nav_applist)
        appListArrow.scaleType = ImageView.ScaleType.FIT_CENTER
        appListArrow.setBackgroundResource(R.drawable.wp81_handle_circle)
        appListArrow.imageTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        appListArrow.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        appListArrow.clipToOutline = true
        appListArrow.isClickable = true
        appListArrow.setOnClickListener { onOpenAppList?.invoke() }
        TiltEffect.apply(appListArrow)
        val size = (APP_LIST_ARROW_DP * resources.displayMetrics.density).toInt()
        val margin = (APP_LIST_ARROW_MARGIN_DP * resources.displayMetrics.density).toInt()
        row.addView(appListArrow, FrameLayout.LayoutParams(
            size, size, android.view.Gravity.END).apply {
            marginEnd = margin
            topMargin = margin
            bottomMargin = margin
        })
        return row
    }

    /**
     * Clears the wall, then opens what was tapped.
     *
     * The order is the whole point: Windows Phone turned Start out *before* the app
     * arrived, so the launch read as leaving one place for another rather than as a screen
     * being replaced. Whatever opens - an app, a folder, a dialog - happens on the far side
     * of it.
     */
    private fun launchWithTurnstile(tile: Tile) {
        // An installed app is handed over before the wall has finished leaving: the system
        // draws its own opening animation over the top, and the tail of the turnstile is
        // meant to be happening underneath it.
        //
        // Anything this shell opens itself - News, Zune, a window of its own - has no such
        // animation to hide behind. It simply appears, so it waits for the wall to have
        // actually gone: handed over early, it arrived over a screen still visibly turning.
        val at = if (tile.kind == Tile.Kind.APP) LAUNCH_AT else 1f
        playTurnstileOut(at) { onLaunch?.invoke(tile) }
    }

    // ---------------------------------------------------------------- inline folders

    /** The folder currently opened into the wall, by tile id. */
    var openFolderId: String? = null
        private set

    private var bandAnimator: android.animation.ValueAnimator? = null

    /** The band's own grid and the column inside it, for dragging into and sliding. */
    private var bandGrid: TileGridLayout? = null
    private var bandColumn: View? = null

    /** Which side of the folder the tile in hand started on. See [fileOnDrop]. */
    private var dragStartedInBand = false

    // --- Holding one tile on another ---------------------------------------------------
    // Resting a tile in the middle of another for a moment offers to put the two together:
    // a new folder if the one underneath is a plain tile, or an arrival if it is already a
    // folder. It has to be a dwell rather than a drop: a tile crosses half the wall on its
    // way somewhere, and every tile it passes over would otherwise be an offer to file it
    // away.

    /** What resting the tile in hand on another one would do. */
    private enum class FoldKind {
        /** Two plain tiles: make a folder of the pair, where the lower one stands. */
        CREATE,

        /** A plain tile on a folder: put it in. */
        INTO
    }

    /** The tile currently being rested on, what that would do, and since when. */
    private var foldTarget: TileView? = null
    private var foldKind: FoldKind? = null
    private var foldSince = 0L

    /** True once the dwell is up: the target is showing the offer and a drop will take it. */
    private var foldArmed = false

    /**
     * What the target was showing before the offer.
     *
     * A folder is already previewing its own contents, and withdrawing the offer has to
     * give them back rather than leave it blank.
     */
    private var foldRestore: List<FolderPreviewView.Entry> = emptyList()


    private fun toggleFolder(tile: Tile) {
        if (openFolderId == tile.id) closeFolder() else onFolderOpened?.invoke(tile)
    }

    /**
     * Opens a folder into the wall: the rows below part, and its tiles appear in the gap.
     *
     * The folder's own tile is emptied while this is up. It is still there - it is where
     * the gap is anchored, and tapping it again is how the gap closes - but its contents
     * are on screen a row below, and showing them twice would only make it harder to see
     * which of the two is the real one.
     */
    fun openFolder(
        folder: Tile,
        contents: List<Tile>,
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        if (contents.isEmpty()) return
        closeFolder(animated = false)
        openFolderId = folder.id
        forEachTileView { if (it.tile.id == folder.id) it.setEmptied(true) }
        // Closed before it exists. The gap keeps its last opening's progress, so a band
        // added while that still reads as 1 is laid out at full height for the frame before
        // the animator gets to set it back to nothing - which is the folder appearing
        // whole and only the wall below it animating.
        grid.bandProgress = 0f
        grid.setBand(buildFolderBand(folder, contents, tileColors, glyphs), folder.id)
        setBandClipping(true)
        slideBand(open = true)
        // Built with a crop of their own by buildTileView, which is the wrong one: the
        // wall's photograph is zoomed for its own scroll range and the band's tiles have
        // to be windows onto that, not onto a fresh copy fitted to the screen.
        post { pushBackgroundToTiles() }
    }

    fun closeFolder(animated: Boolean = true) {
        val id = openFolderId ?: return
        openFolderId = null
        forEachTileView { if (it.tile.id == id) it.setEmptied(false) }
        if (!animated) {
            bandAnimator?.cancel()
            grid.setBand(null, null)
            grid.bandProgress = 1f
            return
        }
        slideBand(open = false) {
            grid.setBand(null, null)
            bandGrid = null
            bandColumn = null
            // The wall is a row shorter again, so the parallax has a different range.
            post { pushBackgroundToTiles() }
        }
    }

    fun isFolderOpen(): Boolean = openFolderId != null

    /**
     * Opens or closes the gap: nothing moves except its height, from nothing to the height
     * the folder needs and back.
     *
     * The contents stay exactly where they are the whole time and the window in front of
     * them grows, so a folder is revealed rather than flown in - and nothing of it is ever
     * drawn over the tiles above or below, because there is no frame in which it is
     * anywhere but inside its own gap.
     *
     * The clip is only on while that is happening. Once the gap is fully open a tile's
     * edit handles have to be able to hang past its edges like any other tile's, and the
     * clip that makes the opening look right would cut them off.
     */
    private fun slideBand(open: Boolean, after: (() -> Unit)? = null) {
        bandAnimator?.cancel()
        setBandClipping(true)
        val from = if (open) 0f else grid.bandProgress
        grid.bandProgress = from
        bandAnimator = android.animation.ValueAnimator.ofFloat(from, if (open) 1f else 0f)
            .apply {
                duration = BAND_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { grid.bandProgress = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        bandAnimator = null
                        if (open) setBandClipping(false)
                        after?.invoke()
                    }
                })
                start()
            }
    }

    /**
     * The gap's contents: the folder's name, then its tiles, closed off by a rule.
     *
     * The two bars are what make the gap read as one thing rather than as a run of tiles
     * that happens to be there. The first starts where the name ends and runs to the edge
     * of the screen; the last runs the whole width. Between them is the folder.
     */
    private fun setBandClipping(clip: Boolean) {
        // Held by the grid, not by the band: the band is not clipped by its parent, so
        // nothing it does to itself can stop it being drawn whole. See bandClipped.
        grid.bandClipped = clip
    }

    private fun buildFolderBand(
        folder: Tile,
        contents: List<Tile>,
        tileColors: (Tile) -> Int?,
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ): View {
        val density = resources.displayMetrics.density
        val bar = (BAND_BAR_DP * density).toInt()
        val margin = (width * TileGridLayout.MARGIN_FRACTION).toInt()

        val overhang = (TileView.HANDLE_OVERHANG_DP * density).toInt()
        // The rules fence off a folder, so they are the folder's own colour: a tile the
        // user has painted opens into a band that matches it rather than into the accent
        // it no longer wears.
        val rule = findTileView { it.tile.id == folder.id }?.fillColor ?: palette.accent

        // Two views, not one: the outer is the window the folder is seen through and never
        // moves, the inner is the folder itself and slides up into it. One view cannot do
        // both, because a view cannot be clipped by its own edge.
        val band = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Clipped to the window's bounds rather than to its padding, so the padding is
            // room a handle on the first row can hang into rather than a second edge for it
            // to be cut off at.
            clipChildren = false
            clipToPadding = false
            // The same air the folder ends with - see the closing rule's bottom margin -
            // so the band sits between the rows above and below it rather than nearer one.
            setPadding(0, (BAND_TOP_DP * density).toInt(), 0, 0)
        }

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // Inset both sides to the grid's own margin, so the rule beside the name stops
            // where the tiles under it stop rather than running on to the edge of the
            // screen. The air under it is the folder's inner margin, matched at the other
            // end by the closing rule's.
            setPadding(margin, 0, margin, (BAND_GAP_DP * density).toInt())
        }
        heading.addView(android.widget.TextView(context).apply {
            text = folder.label
            // The name of the thing you are looking into is the obvious place to rename it,
            // and a folder opened in the wall has no menu of its own to put it in.
            isClickable = true
            setOnClickListener { onFolderRename?.invoke(folder) }
            TiltEffect.apply(this)
            typeface = androidx.core.content.res.ResourcesCompat
                .getFont(context, R.font.segoeui_semilight)
            textSize = 22f
            setTextColor(palette.foreground)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        heading.addView(View(context).apply {
            setBackgroundColor(rule)
        }, LinearLayout.LayoutParams(0, bar, 1f).apply {
            marginStart = (BAND_GAP_DP * density).toInt()
            // Set on the foot of the name rather than in the middle of it, so what lies
            // between the rule and the tiles is the heading's own padding and nothing
            // else - a rule floating in the middle of a line of type puts half a line of
            // air under it that no measurement here can account for.
            gravity = android.view.Gravity.BOTTOM
        })
        column.addView(heading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val inner = TileGridLayout(context).apply {
            columns = grid.columns
            metricBasis = grid.metricBasis
            clipChildren = false
            // And room under the last row for its resize handle, exactly as the wall keeps.
            bottomReservePx = overhang
        }
        for (child in contents.sortedBy { it.index }) {
            inner.addView(buildTileView(child, glyphs).apply { setTileColor(tileColors(tile)) })
        }
        column.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        column.addView(View(context).apply {
            setBackgroundColor(rule)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar).apply {
            // Starting and stopping with the tiles, like the rule at the top: the two rules
            // are the folder's own edges, and edges wider than what they enclose read as a
            // second, larger thing behind it.
            marginStart = margin
            marginEnd = margin
            // The grid keeps a handle's worth of room under its last row, which is empty
            // space the rule would otherwise be pushed down by - so it is pulled back up
            // through it, and what is left between the tiles and the rule is the same
            // margin the heading leaves above them.
            topMargin = (BAND_GAP_DP * density).toInt() - overhang
            bottomMargin = (BAND_TOP_DP * density).toInt()
        })

        band.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        bandGrid = inner
        bandColumn = column
        return band
    }

    fun tiles(): List<Tile> = tiles.toList()

    // ---------------------------------------------------------------- edit mode

    /** Selects [view] for editing, replacing any previous selection. */
    fun enterEditMode(view: TileView) {
        if (editingView === view) return
        editingView?.setEditMode(false)
        editingView = view
        view.setEditMode(true)
        // The wall stands back rather than the tile shrinking: the one being arranged is
        // the one that should be whole. See TileView.setDimmed.
        forEachTileView { it.setDimmed(it !== view) }
        onEditModeChanged?.invoke(true)
    }

    fun exitEditMode() {
        val editing = editingView ?: return
        editing.setEditMode(false)
        clearSelection()
        commit()
    }

    /**
     * Drops the selection and lets the wall stand back up.
     *
     * Every way out of edit mode goes through here, because clearing [editingView] is only
     * half of leaving it: the other tiles are still shrunk, faded and drifting, and a tile
     * disappearing from under the user while the wall stays stood back is edit mode that
     * has ended everywhere except on screen.
     */
    private fun clearSelection() {
        editingView = null
        forEachTileView { it.setDimmed(false) }
        onEditModeChanged?.invoke(false)
    }

    /** True if the view consumed the back press. */
    fun handleBack(): Boolean {
        if (isEditMode) { exitEditMode(); return true }
        return false
    }

    /**
     * Every tile on the wall, including the ones inside an opened folder's band.
     *
     * The band's tiles are two levels down - band, then its own grid - and they are still
     * tiles on this wall: they carry notifications, they wear the accent, and they are
     * windows onto the same photograph. Walking only the top level left them out of all
     * of it.
     */
    /** The first tile on the wall the predicate accepts, band included. */
    private fun findTileView(match: (TileView) -> Boolean): TileView? {
        var found: TileView? = null
        forEachTileView { if (found == null && match(it)) found = it }
        return found
    }

    private fun forEachTileView(action: (TileView) -> Unit) {
        forEachTileViewIn(grid, action)
    }

    private fun forEachTileViewIn(parent: ViewGroup, action: (TileView) -> Unit) {
        for (i in 0 until parent.childCount) {
            when (val child = parent.getChildAt(i)) {
                is TileView -> action(child)
                is ViewGroup -> forEachTileViewIn(child, action)
            }
        }
    }

    /**
     * The same walk, with each tile's position in the wall's own coordinates.
     *
     * A tile inside the band knows where it is inside the band; the photograph behind the
     * wall is positioned in the wall's space, so the offsets have to be accumulated on the
     * way down or every tile in a folder shows the same wrong slice of it.
     */
    private fun forEachTileViewPlaced(action: (TileView, Int, Int) -> Unit) {
        placeTileViewsIn(grid, 0, 0, action)
    }

    private fun placeTileViewsIn(
        parent: ViewGroup,
        offsetX: Int,
        offsetY: Int,
        action: (TileView, Int, Int) -> Unit
    ) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val x = offsetX + child.left
            val y = offsetY + child.top
            when (child) {
                is TileView -> action(child, x, y)
                is ViewGroup -> placeTileViewsIn(child, x, y, action)
            }
        }
    }

    fun unpinTile(tile: Tile) {
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index < 0) return
        val view = grid.getChildAt(index)
        // Unpinning is the end of the job the tile was picked up for, so it is the end of
        // edit mode too - and the key strip has to be told, since it is showing the
        // commands for a tile that is on its way off the screen.
        if (view === editingView) clearSelection()
        tiles.removeAt(index)
        reindex()
        view.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160)
            .withEndAction {
                grid.removeView(view)
                grid.requestLayout()
                commit()
            }.start()
    }

    fun resizeTile(tile: Tile) {
        tile.size = tile.size.next()
        forEachTileView { if (it.tile.id == tile.id) it.applySize() }
        grid.requestLayout()
        commit()
    }

    private fun reindex() = tiles.forEachIndexed { i, t -> t.index = i }

    /** The grid a tile is packed by: the band's, for a tile inside an opened folder. */
    private fun gridOf(view: TileView): TileGridLayout =
        view.parent as? TileGridLayout ?: grid

    /**
     * Writes down whichever arrangement was being changed.
     *
     * The wall and an opened folder are two arrangements, kept in two places, and the same
     * gestures edit both. Committing the wall after moving something inside a folder would
     * save a list the folder's tiles are not in - which reads as unpinning all of them.
     */
    private fun commit() {
        val editing = editingView
        if (editing != null && gridOf(editing) !== grid) {
            commitFolder()
            return
        }
        reindex()
        onTilesChanged?.invoke(tiles.toList())
    }

    private fun commitFolder() {
        val id = openFolderId ?: return
        val band = bandGrid ?: return
        val ordered = mutableListOf<Tile>()
        for (i in 0 until band.childCount) {
            (band.getChildAt(i) as? TileView)?.let { ordered.add(it.tile) }
        }
        ordered.forEachIndexed { i, tile -> tile.index = i }
        onFolderTilesChanged?.invoke(id, ordered)
    }

    fun addTile(tile: Tile, glyph: MonochromeIconProvider.Glyph?) {
        if (tiles.any { it.id == tile.id }) return
        tile.index = tiles.size
        tiles.add(tile)
        grid.addView(buildTileView(tile) { glyph })
        grid.requestLayout()
        commit()
    }

    // ---------------------------------------------------------------- handle drags

    /**
     * Top-right handle: carries the tile around and reorders as it passes over others.
     *
     * Uses raw screen coordinates because the handle moves with the tile it is dragging -
     * measuring against the handle's own frame would feed the movement back into itself.
     */
    /**
     * Places the tile so the point the finger grabbed stays under the finger.
     *
     * Recomputed from absolute coordinates every move rather than accumulated as deltas:
     * a reorder changes the tile's layout position mid-drag, and an accumulated offset
     * would then be measured from the wrong base and the tile would snap away.
     */
    private fun followFinger(view: TileView, rawX: Float, rawY: Float) {
        lastMoveRawX = rawX
        lastMoveRawY = rawY
        getLocationOnScreen(screenOrigin)
        val layoutX = screenOrigin[0] + placedLeftOf(view)
        val layoutY = screenOrigin[1] + placedTopOf(view) - scrollY + content.translationY
        view.translationX = (rawX - grabOffsetX) - layoutX
        view.translationY = (rawY - grabOffsetY) - layoutY
    }

    /**
     * Bottom-right handle: resizes live as the finger moves.
     *
     * The size is read off how many grid columns the finger has reached from the tile's
     * left edge, so the tile follows the drag instead of cycling through sizes.
     */
    private fun handleResizeDrag(view: TileView, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeTouchRawX = event.rawX
                resizeTouchRawY = event.rawY
                resizeStartWidth = view.width.toFloat()
                resizeStartHeight = view.height.toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                val widthNow = resizeStartWidth + (event.rawX - resizeTouchRawX)
                val heightNow = resizeStartHeight + (event.rawY - resizeTouchRawY)
                // Against the grid the tile is actually in. A tile inside an opened
                // folder is packed by the band's own grid, and measuring its drag against
                // the wall's cells made every size it snapped to the wrong one.
                val home = gridOf(view)
                val wanted = TileSize.forSpan(
                    home.columnsForWidth(widthNow),
                    home.rowsForHeight(heightNow)
                )
                if (wanted != view.tile.size) {
                    view.tile.size = wanted
                    view.applySize()
                    home.requestLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> commit()
        }
    }

    // ---------------------------------------------------------------- drag to reorder

    private fun beginDrag(view: TileView) {
        dragView = view
        forgetPendingReorder()
        dragStartedInBand = gridOf(view) !== grid
        view.elevation = DRAG_ELEVATION
        view.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).setDuration(120).start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Tracked here as well as in onTouchEvent: ACTION_DOWN lands on whichever tile was
        // pressed, not on this ScrollView, so waiting for onTouchEvent means never seeing
        // where the gesture began. onInterceptTouchEvent always sees the DOWN first.
        trackPullDown(ev)

        val dragging = dragView
        if (dragging != null) {
            // Ended here, not in onTouchEvent. A view group is never handed the event it
            // intercepts on: the child is cancelled, and the stream only reaches
            // onTouchEvent from the *next* event. A tile picked up by a long press and let
            // go without ever being moved produces no next event, so the drag was never
            // told it had ended - the tile stayed picked up, and the following touch
            // anywhere on the wall carried it off.
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) endDrag(dragging)
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the selected tile is draggable, and only by its body.
                pendingDragView = editingView?.takeIf { contains(it, ev.x, ev.y) }
                // Recorded for every press, not just draggable ones: a long-press starts a
                // drag from where the finger already is, and by then there is no fresh
                // ACTION_DOWN left to read it from.
                pressRawX = ev.rawX
                pressRawY = ev.rawY
                lastMoveRawX = ev.rawX
                lastMoveRawY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val pending = pendingDragView
                if (pending != null && moved(ev) > touchSlop) {
                    startBodyDrag(pending, pressRawX, pressRawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pendingDragView = null
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        trackPullDown(ev)

        val dragging = dragView
        if (dragging != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    followFinger(dragging, ev.rawX, ev.rawY)
                    reorderUnder(dragging)
                    trackEdgeScroll(ev.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(dragging)
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun moved(ev: MotionEvent): Float =
        kotlin.math.hypot(ev.rawX - pressRawX, ev.rawY - pressRawY)

    /**
     * Where a tile sits in the wall's own coordinates.
     *
     * A tile inside an opened folder is two levels down, so its own left and top are
     * positions inside the band. Everything that reasons about where a tile *is* - picking
     * one up, dragging it, working out what it was dropped on - has to ask in the same
     * space, or a folder's tiles are all reported as being in the top-left corner.
     */
    private fun placedLeftOf(view: View): Int {
        var x = 0
        var v: View? = view
        while (v != null && v !== grid) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }

    private fun placedTopOf(view: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== grid) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    /** True when a point in this view's coordinates falls inside [view]'s laid-out bounds. */
    private fun contains(view: TileView, x: Float, y: Float): Boolean {
        val left = placedLeftOf(view)
        val top = placedTopOf(view) - scrollY + content.translationY
        return x >= left && x <= left + view.width && y >= top && y <= top + view.height
    }

    /**
     * Picks the selected tile up for repositioning.
     *
     * Dragging the tile itself rather than a corner handle: the whole tile is a far bigger
     * target, and a handle that moves between corners as the tile crosses the grid ends up
     * jumping around under the finger.
     */
    private fun startBodyDrag(view: TileView, rawX: Float, rawY: Float) {
        beginDrag(view)
        getLocationOnScreen(screenOrigin)
        grabOffsetX = rawX - (screenOrigin[0] + placedLeftOf(view))
        grabOffsetY = rawY - (screenOrigin[1] + placedTopOf(view) - scrollY + content.translationY)
        pendingDragView = null
    }

    /** Swaps the dragged tile with whichever one its centre is now over. */
    /**
     * Which grid the finger is currently over: the wall's, or an opened folder's.
     *
     * The wall and the folder open in it are one surface to drag on. They are two packers
     * underneath, which is an implementation detail the finger should never be able to
     * feel: crossing the folder's top edge moves the tile from one to the other, and
     * everything after that is an ordinary reorder in whichever one it landed in.
     */
    private fun gridUnderFinger(): TileGridLayout {
        val band = grid.bandView ?: return grid
        val inner = bandGrid ?: return grid
        getLocationOnScreen(screenOrigin)
        val y = lastMoveRawY - screenOrigin[1] + scrollY - content.translationY
        return if (y >= band.top && y <= band.bottom) inner else grid
    }

    /**
     * Carries a tile across the folder's edge, into it or out of it.
     *
     * The view itself moves between the two grids so the wall repacks around the hole and
     * the folder repacks around the arrival, both while the finger is still down. Filing it
     * only on release meant a drag that showed nothing until it was over, and a drop that
     * had to guess what was underneath it.
     */
    private fun moveAcross(view: TileView, target: TileGridLayout) {
        val from = gridOf(view)
        val index = from.indexOfChild(view)
        if (from === grid && index in tiles.indices) {
            // The wall keeps its own list, and the tile is leaving it.
            tiles.removeAt(index)
        }
        from.removeView(view)
        if (target === grid) {
            tiles.add(view.tile)
            // Ahead of the band, so the wall's child order still matches its tile list.
            grid.addTile(view)
            reindex()
        } else {
            target.addView(view)
        }
        lastReorderAt = android.os.SystemClock.uptimeMillis()
        // The slot the finger was asking for was a slot in the grid it has just left.
        forgetPendingReorder()
        // Its new home has not been laid out yet, so where the tile *is* on screen is not
        // known until it has been. The translation carrying it under the finger is measured
        // from that position, so without this the tile jumps to wherever the old one put it
        // and snaps back a frame later.
        view.post { if (dragView === view) followFinger(view, lastMoveRawX, lastMoveRawY) }
    }

    /** Where the finger is, in the wall's own coordinates. */
    private fun fingerInGrid(): Pair<Float, Float> {
        getLocationOnScreen(screenOrigin)
        return (lastMoveRawX - screenOrigin[0]) to
            (lastMoveRawY - screenOrigin[1] + scrollY - content.translationY)
    }

    /**
     * What resting the tile in hand on [under] would do, or null for nothing.
     *
     * A folder takes tiles but never another folder, and nothing the shell provides goes
     * either way: a built-in is rebuilt on every refresh and would come straight back out
     * of any folder it was put in. Neither happens inside an opened folder - there is no
     * nesting to offer there, only arranging.
     */
    private fun foldKindFor(view: TileView, under: TileView): FoldKind? {
        if (gridOf(view) !== grid || gridOf(under) !== grid) return null
        if (view.tile.kind == Tile.Kind.FOLDER || view.tile.kind.isBuiltIn) return null
        return when {
            under.tile.kind == Tile.Kind.FOLDER -> FoldKind.INTO
            under.tile.kind.isBuiltIn -> null
            else -> FoldKind.CREATE
        }
    }

    /**
     * Whether the finger is in the middle of [under] rather than out towards its edge.
     *
     * The middle is what two tiles are put together over; everything round it is ordinary
     * wall, where the finger is either passing across or asking for the slot.
     *
     * A circle on the tile's centre, sized from its shorter side, rather than an inset
     * rectangle. Two reasons: a wide tile then keeps its ends for the wall instead of
     * being nearly all middle, and what is left over on a small one is four corners rather
     * than a thin band all the way round - a shape a thumb can actually find.
     */
    private fun inFoldZone(under: TileView, x: Float, y: Float): Boolean {
        val radius = kotlin.math.min(under.width, under.height) * FOLD_RADIUS_FRACTION
        val dx = x - (placedLeftOf(under) + under.width / 2f)
        val dy = y - (placedTopOf(under) + under.height / 2f)
        return kotlin.math.hypot(dx, dy) < radius
    }

    /**
     * Holds the two together and shows what putting them together would give.
     *
     * The preview goes on the tile being rested on, because that is where the folder will
     * be - either the one made of the pair, or the one that is already there with the
     * arrival in it. Returning true means the wall stands still: nothing shuffles out from
     * under a tile that is being offered something, which would move the target being
     * aimed at.
     */
    private fun trackFold(view: TileView, under: TileView): Boolean {
        val kind = foldKindFor(view, under)
        if (kind == null) {
            clearFold()
            return false
        }
        if (under !== foldTarget) {
            clearFold()
            foldTarget = under
            foldKind = kind
            foldSince = android.os.SystemClock.uptimeMillis()
            return true
        }
        if (foldArmed) return true
        if (android.os.SystemClock.uptimeMillis() - foldSince < FOLD_DWELL_MS) return true

        val entries = when (kind) {
            FoldKind.CREATE -> folderPreviewOf?.invoke(listOf(under.tile, view.tile))
            FoldKind.INTO -> folderPreviewWith?.invoke(under.tile, view.tile)
        }.orEmpty()
        if (entries.isEmpty()) return true
        foldArmed = true
        foldRestore = under.folderPreviewEntries
        under.setFolderPreview(entries)
        // And the target comes forward out of the wall. Everything but the tile in hand is
        // standing back while it is being arranged, so stepping back in is what an offer
        // looks like here - and it is the same signal whether a folder is being made or
        // added to, which the preview alone is not.
        under.setDimmed(false)
        under.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun clearFold() {
        foldTarget?.takeIf { foldArmed }?.let { target ->
            // Back to whatever it was showing - its own contents, for a folder - and back
            // into the wall with the rest.
            target.setFolderPreview(foldRestore)
            target.setDimmed(true)
        }
        foldRestore = emptyList()
        foldTarget = null
        foldKind = null
        foldArmed = false
    }

    /** Forgets the slot the finger was asking for, so the dwell starts again. */
    private fun forgetPendingReorder() {
        pendingReorderAt = -1
    }


    /**
     * Decides what a drag hovering somewhere means, and does it.
     *
     * One drag has to do two jobs - move a tile about, and put two tiles together - and
     * they are told apart by *where* on the tile underneath the finger is and by *how
     * long* it has been there. Neither on its own is enough. Where alone gives a border so
     * thin it cannot be aimed at; how long alone means the tile being aimed at slides out
     * from under the finger before the hold is up.
     *
     *  - In the **middle** of a tile the two are being offered to each other. After a
     *    beat the tile underneath shows what it would hold - the pair as a new folder, or
     *    its own contents with the arrival added - and letting go takes the offer.
     *  - **Anywhere else** on it the wall is being asked for that slot, and it opens it
     *    once the finger has stayed long enough to mean it.
     *
     * That the slot has to be asked for is what makes the middle reachable: a tile crossed
     * on the way to somewhere is crossed in well under the dwell, so it stays where it is
     * and can still be aimed at. It also stops the wall shuffling under a tile being
     * dragged across it, which is what the reflow animations were forever restarting on.
     */
    private fun reorderUnder(view: TileView) {
        // One surface: crossing into the folder, or out of it, before anything else.
        val over = gridUnderFinger()
        if (over !== gridOf(view)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastReorderAt < REORDER_COOLDOWN_MS) return
            clearFold()
            moveAcross(view, over)
            return
        }

        val home = gridOf(view)
        val from = home.indexOfChild(view)
        val (fingerX, fingerY) = fingerInGrid()
        val localX = fingerX - placedLeftOf(home)
        val localY = fingerY - placedTopOf(home)

        // Past the last row means "put it last", which is otherwise unreachable. Last among
        // the *tiles*: the band is a child too, and it is not a position a tile can be
        // moved to.
        val below = localY > home.height
        val target = if (below) home.tileCount - 1 else home.indexAt(localX, localY)
        // In the gap between two tiles: over nothing in particular. The slot the finger was
        // asking for is left standing, so a hand wavering on a boundary still gets there.
        if (target < 0) {
            clearFold()
            return
        }
        if (target == from) {
            clearFold()
            forgetPendingReorder()
            return
        }

        val under = home.getChildAt(target) as? TileView
        if (under != null && !below && inFoldZone(under, fingerX, fingerY)) {
            // In the middle of a tile: either the two are being put together, or this one
            // has said it will not - and while they are, the wall stands still.
            if (trackFold(view, under)) {
                forgetPendingReorder()
                return
            }
        } else {
            clearFold()
        }

        // The slot has to be asked for rather than passed through.
        val now = android.os.SystemClock.uptimeMillis()
        if (target != pendingReorderAt) {
            pendingReorderAt = target
            pendingReorderSince = now
            return
        }
        if (now - pendingReorderSince < REORDER_DWELL_MS) return
        // One reorder at a time. Without this a drag held over a boundary re-packs on every
        // frame, and the reflow animations restart faster than they can finish - which is
        // the shuffling that shows up as rows twitching.
        if (now - lastReorderAt < REORDER_COOLDOWN_MS) return
        lastReorderAt = now
        forgetPendingReorder()

        if (home !== grid) {
            reflow(home) {
                home.removeViewAt(from)
                home.addView(view, target)
            }
            return
        }
        moveTile(from, target)
    }

    /**
     * Watches for a downward drag begun at the very top of Start.
     *
     * A ScrollView already at offset zero does nothing with a downward drag, so that
     * gesture is free to mean something else - here, the notification shade, matching the
     * pull-down every other Android surface has.
     */
    private fun trackPullDown(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwipeStartY = ev.rawY
                // Armed against whichever end of the scroll the gesture starts at. Both
                // are free to mean something else, because a ScrollView already at an end
                // does nothing with a drag that would take it further.
                pullDownArmed = scrollY == 0 && !isEditMode && dragView == null
                pushUpArmed = isScrolledToBottom() && !isEditMode && dragView == null
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView != null) {
                    pullDownArmed = false
                    pushUpArmed = false
                    return
                }
                val travelled = ev.rawY - edgeSwipeStartY

                if (pullDownArmed) {
                    // Any actual scrolling means the gesture was a scroll, not a pull.
                    if (scrollY > 0) {
                        pullDownArmed = false
                        releaseOverscroll()
                    } else if (travelled > edgeGiveThreshold) {
                        pullDownArmed = false
                        pushUpArmed = false
                        releaseOverscroll()
                        onSwipeDownAtTop?.invoke()
                        return
                    } else if (travelled > 0f) {
                        overscrollBy(travelled)
                    }
                }

                if (pushUpArmed) {
                    // The mirror of the pull above, and for the same reason: a push that
                    // did nothing until it crossed a line had no beginning, only an
                    // outcome, and one that fell short looked like a tap being ignored.
                    if (!isScrolledToBottom()) {
                        pushUpArmed = false
                        releaseOverscroll()
                    } else if (-travelled > edgeGiveThreshold) {
                        pushUpArmed = false
                        pullDownArmed = false
                        releaseOverscroll()
                        onSwipeUpAtBottom?.invoke()
                    } else if (travelled < 0f) {
                        overscrollBy(travelled)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pullDownArmed = false
                pushUpArmed = false
                releaseOverscroll()
            }
        }
    }

    /**
     * Drags the wall under the finger, short of opening anything. Signed: down at the top
     * of Start, up at the bottom of it.
     *
     * An edge swipe used to do nothing at all until it crossed the threshold, at which
     * point the shade or the app list appeared - so the gesture had no beginning, only an
     * outcome, and one that fell short looked like a tap that had been ignored. Following
     * the finger first makes what arrives something the user pulled out rather than
     * something that happened to them.
     *
     * Damped, and hard-limited: the wall gives less the further it is pushed, which is
     * what makes it feel attached to something. Real stretch - Android's overscroll
     * distortion - needs a render effect this shell cannot use below the version it
     * supports, and a tile wall bending is not what Windows Phone did anyway.
     */
    private fun overscrollBy(travelled: Float) {
        content.animate().cancel()
        val limit = OVERSCROLL_LIMIT_DP * resources.displayMetrics.density
        // Asymptotic: an infinite pull approaches the limit and never passes it. Taken on
        // the distance and the sign put back afterwards, so a push up gives exactly as
        // much as a pull down and no more.
        val given = limit * (1f - kotlin.math.exp(-kotlin.math.abs(travelled) / limit))
        content.translationY = if (travelled < 0f) -given else given
        // The pull is a scroll as far as the photo is concerned, so it drifts behind the
        // tiles at the same rate an ordinary scroll moves it at. Left out, the wall came
        // away from a picture that stayed nailed to the screen.
        updateTileOffsets()
    }

    /** Lets the wall back up, whether the pull opened anything or not. */
    private fun releaseOverscroll() {
        if (content.translationY == 0f) return
        content.animate()
            .translationY(0f)
            .setDuration(OVERSCROLL_RETURN_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(OVERSCROLL_SPRING))
            // The photo comes back with it rather than snapping back at the end: the spring
            // is the part of the gesture the user actually watches.
            .setUpdateListener { updateTileOffsets() }
            .start()
    }

    /**
     * True when there is no further to scroll.
     *
     * Includes the case where the tiles do not fill the screen at all - then Start is both
     * its own top and its own bottom, and either gesture should work.
     */
    /**
     * How far the wall can be scrolled.
     *
     * The grid carries the room kept under the last row for its handle, so this needs no
     * correction of its own - but it is worth having in one place, since the push-up
     * gesture, the drag edge-scroll and the parallax all have to agree about where the
     * end is.
     */
    private fun scrollRange(): Int = (content.height - height).coerceAtLeast(0)

    private fun isScrolledToBottom(): Boolean = scrollY >= scrollRange()


    private fun moveTile(from: Int, to: Int) {
        if (from !in tiles.indices || to !in tiles.indices) return
        reflow(grid) {
            val tile = tiles.removeAt(from)
            tiles.add(to, tile)
            val view = grid.getChildAt(from)
            grid.removeViewAt(from)
            grid.addView(view, to)
            reindex()
        }
    }

    /**
     * Runs a rearrangement and animates everything it displaced into its new slot.
     *
     * That motion is the whole point: without it a tile passing over its neighbours makes
     * them appear in new places rather than move to them. It takes the grid to work on
     * because a folder opened in the wall is a second grid doing exactly the same job, and
     * its tiles were the ones still snapping.
     */
    private fun reflow(home: TileGridLayout, rearrange: () -> Unit) {
        // Visual position, not layout position: a tile part-way through an earlier reflow
        // is drawn offset from its slot, and animating from the slot would snap it.
        val before = HashMap<View, Pair<Float, Float>>(home.childCount)
        for (i in 0 until home.childCount) {
            val child = home.getChildAt(i)
            before[child] = (child.left + child.translationX) to (child.top + child.translationY)
        }

        rearrange()
        home.requestLayout()

        home.post {
            for ((child, old) in before) {
                if (child === dragView) continue
                val dx = old.first - child.left
                val dy = old.second - child.top
                child.animate().cancel()
                if (kotlin.math.abs(dx) < 0.5f && kotlin.math.abs(dy) < 0.5f) {
                    child.translationX = 0f
                    child.translationY = 0f
                    continue
                }
                child.translationX = dx
                child.translationY = dy
                child.animate()
                    .translationX(0f).translationY(0f)
                    .setDuration(REFLOW_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * Where a drop belongs, if it belongs somewhere other than where it started.
     *
     * Only one move is left here: a tile from an opened folder let go anywhere outside that
     * folder's band comes out of it. Its mirror image - a tile from the wall going into a
     * folder that is closed - is not a drop on a folder but a drop on an offer the folder
     * was already showing, and [endDrag] takes that before anything gets this far.
     */
    private fun fileOnDrop(view: TileView): Boolean {
        val endedInBand = gridOf(view) !== grid

        // Crossed the folder's edge during the drag. The view is already where it belongs -
        // it was carried across while the finger was down - so this only writes down which
        // list that is. Nothing is rebuilt: rebuilding would take the tile the user has just
        // watched arrive and replace it with a new one, which is the pop.
        if (endedInBand != dragStartedInBand) {
            onTileFiled?.invoke(view.tile, if (endedInBand) openFolderId else null, true)
            // Both orders changed - one list lost a tile and the other gained one - so both
            // are written, rather than whichever one commit() would have guessed at.
            reindex()
            onTilesChanged?.invoke(tiles.toList())
            commitFolder()
            return true
        }

        return false
    }

    private fun endDrag(view: TileView) {
        stopEdgeScroll()
        dragView = null
        forgetPendingReorder()
        // Let go on an offer that was already showing: the pair becomes a folder where the
        // lower one stands, or the folder underneath takes the tile in.
        val folding = foldTarget?.takeIf { foldArmed }
        val kind = foldKind
        // Cleared first either way: the target is showing a preview of what it is about to
        // become, and what it actually becomes is built from the lists, not from that.
        clearFold()
        if (folding != null && kind != null) {
            when (kind) {
                FoldKind.CREATE -> onTilesFoldered?.invoke(view.tile, folding.tile)
                FoldKind.INTO -> absorbIntoFolder(view, folding, folding.tile.id)
            }
            return
        }
        // Decided before the tile is sent home, not after. Springing it back to a slot it
        // is about to leave is a beat of animation that exists only to be thrown away -
        // which is the pause, and the rebuild landing on top of it is the snap.
        if (fileOnDrop(view)) return

        view.animate()
            .translationX(0f).translationY(0f)
            .scaleX(view.restingScale())
            .scaleY(view.restingScale())
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            // Back to the edit lift rather than to nothing: the tile is still selected
            // after a drag, and its handles still hang over its neighbours.
            .withEndAction {
                view.elevation = if (view === editingView) TileView.EDIT_ELEVATION else 0f
            }
            .start()
        commit()
    }

    /**
     * Sends a tile into the folder it was dropped on, and closes the wall behind it.
     *
     * The tile is drawn towards the folder as it shrinks away, so the drop has somewhere
     * to land rather than simply ending. The wall is not rebuilt: the one tile that left
     * is taken out and the rest repack around the hole, which is the same thing unpinning
     * does and reads as one movement instead of two.
     */
    private fun absorbIntoFolder(view: TileView, folder: TileView, folderId: String) {
        val dx = (placedLeftOf(folder) + folder.width / 2f) -
            (placedLeftOf(view) + view.width / 2f)
        val dy = (placedTopOf(folder) + folder.height / 2f) -
            (placedTopOf(view) + view.height / 2f)
        view.animate()
            .translationX(dx)
            .translationY(dy)
            .scaleX(ABSORB_SCALE)
            .scaleY(ABSORB_SCALE)
            .alpha(0f)
            .setDuration(ABSORB_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                val index = tiles.indexOfFirst { it.id == view.tile.id }
                if (index >= 0) tiles.removeAt(index)
                grid.removeView(view)
                // The tile that was being arranged is inside the folder now. Leaving the
                // selection pointing at it would hold the wall stood back around a tile
                // that is no longer on it.
                if (editingView === view) clearSelection()
                reindex()
                onTilesChanged?.invoke(tiles.toList())
                onTileFiled?.invoke(view.tile, folderId, false)
                grid.requestLayout()
            }
            .start()
    }

    // ---------------------------------------------------------------- appearance

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        forEachTileView { it.applyPalette(p) }
    }

    // ---------------------------------------------------------------- start background

    /**
     * Sets the photo the tiles are windows onto.
     *
     * [focusX] picks which part of a photo wider than the screen is shown, 0 for the left
     * edge through 1 for the right.
     */
    fun setStartBackground(bitmap: Bitmap?, focusX: Float) {
        startBackground = bitmap
        backgroundFocusX = focusX.coerceIn(0f, 1f)
        pushBackgroundToTiles()
        syncDrift()
    }

    private fun pushBackgroundToTiles() {
        val bmp = startBackground
        if (bmp == null) {
            forEachTileView { it.setStartBackground(null, null, EMPTY_RECT) }
            return
        }
        if (width == 0 || height == 0) return

        // Room for the photo to travel. Zooming in is what creates it: the image is drawn
        // taller than the viewport, and the extra height is exactly the distance it pans
        // over the full scroll. No scrollable content means no pan and no zoom.
        val scrollRange = scrollRange()
        val panRange = (scrollRange * PARALLAX_FACTOR)
            .coerceAtMost(height * MAX_OVERSCAN)
            .toInt()

        effectiveParallax = if (scrollRange > 0) panRange / scrollRange.toFloat() else 0f

        // Slack for the drift, on both axes. Asking for a larger area than the screen is
        // what zooms the photo in, exactly as the parallax overscan above does.
        driftRange = if (driftEnabled) (height * DRIFT_FRACTION).toInt() else 0

        // And slack at each end for the over-pull, which travels past both ends of the
        // scroll. Without it the photo runs out at exactly the point the wall is pulled
        // off the top, and the bounce would show the tiles emptying out at the edge.
        overscrollSlack = (OVERSCROLL_LIMIT_DP * resources.displayMetrics.density *
            (1f - effectiveParallax)).toInt()

        val dest = Rect(
            0, 0,
            width + driftRange,
            height + panRange + driftRange + 2 * overscrollSlack
        )
        val src = cropFor(bmp, dest.width(), dest.height(), backgroundFocusX)
        forEachTileView { it.setStartBackground(bmp, src, dest) }
        lastSignature = signature()
        updateTileOffsets()
    }

    /** Places the photo within its slack, from the drift driver's -1..1 position. */
    private fun applyDrift(x: Float, y: Float) {
        val half = driftRange / 2f
        val nextX = Math.round(x * half).toFloat()
        val nextY = Math.round(y * half).toFloat()
        // Rounded to whole pixels and compared, so a frame that has not actually moved the
        // image does not repaint every tile on the screen.
        if (nextX == driftX && nextY == driftY) return
        driftX = nextX
        driftY = nextY
        updateTileOffsets()
    }

    /**
     * Runs the drift only while it can be seen.
     *
     * It holds a sensor and a frame callback, so anything that takes the Start screen off
     * screen - the app list sliding over, the activity going to the background - should
     * stop it rather than leave it turning over behind the user's back.
     */
    private fun syncDrift() {
        val wanted = driftEnabled && startBackground != null && isShown
        if (wanted) drift.start() else drift.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncDrift()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drift.stop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncDrift()
        // Coming back to Start is an arrival, so the wall arrives: the same turnstile it
        // opens with on a cold start. It is also the only reliable way back from
        // playTurnstileOut, which leaves every tile turned away and transparent - and the
        // activity is not always rebuilt on the way home.
        if (isVisible) playEntrance()
    }

    /** Everything the drawn background depends on, so it is only rebuilt when it changes. */
    private fun signature(): Int {
        var h = width
        h = 31 * h + height
        h = 31 * h + grid.height
        h = 31 * h + backgroundFocusX.hashCode()
        h = 31 * h + driftRange
        h = 31 * h + (startBackground?.hashCode() ?: 0)
        return h
    }

    /**
     * The region of [bmp] to show so it fills a [viewW] x [viewH] area without distortion.
     *
     * Centre-crop, except that the horizontal placement is driven by [focusX] so the user
     * can slide a wide photo to frame what they want. Because [viewH] includes the parallax
     * overscan, asking for a taller area is what zooms the photo in.
     */
    private fun cropFor(bmp: Bitmap, viewW: Int, viewH: Int, focusX: Float): Rect {
        if (viewW <= 0 || viewH <= 0) return Rect(0, 0, bmp.width, bmp.height)
        val viewAspect = viewW.toFloat() / viewH
        val bmpAspect = bmp.width.toFloat() / bmp.height
        return if (bmpAspect > viewAspect) {
            // Wider than the target: crop the sides, positioned by focusX.
            val cropW = (bmp.height * viewAspect).toInt().coerceIn(1, bmp.width)
            val left = ((bmp.width - cropW) * focusX).toInt().coerceIn(0, bmp.width - cropW)
            Rect(left, 0, left + cropW, bmp.height)
        } else {
            // Taller than the target: crop top and bottom evenly.
            val cropH = (bmp.width / viewAspect).toInt().coerceIn(1, bmp.height)
            val top = ((bmp.height - cropH) / 2).coerceIn(0, bmp.height - cropH)
            Rect(0, top, bmp.width, top + cropH)
        }
    }

    /**
     * Tells each tile where it sits relative to the photo, so the slices line up into one
     * continuous image.
     */
    private fun updateTileOffsets() {
        if (startBackground == null) return
        // Where the wall has got to, scrolled and pulled together: an over-pull moves the
        // tiles without moving the scroll position, and to the photo behind them the two
        // are the same movement.
        val travelled = scrollY - content.translationY
        // The tiles move a full pixel per pixel scrolled; the photo behind them moves only
        // effectiveParallax of that, which is what reads as depth. A factor of 0 would pin
        // the photo to the screen, 1 would glue it to the tiles.
        val backgroundShift = travelled * (1f - effectiveParallax)
        // Half the drift travel is the resting point, so the photo has the same room to
        // move in both directions before it runs out of image.
        val centre = driftRange / 2f
        forEachTileViewPlaced { tile, x, y ->
            tile.setBackgroundOffset(
                x + centre + driftX,
                y - backgroundShift + centre + overscrollSlack + driftY
            )
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateTileOffsets()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        // Turning the phone changes what the wall is packed into, and the wall is packed
        // from the shape of the screen rather than from the setting alone.
        if (applyColumns()) post { commit() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // A reorder re-lays-out the dragged tile into a new slot mid-gesture. Re-anchoring
        // here - during layout, before the frame is drawn - keeps it under the finger;
        // doing it a frame later showed as a jump.
        dragView?.let { followFinger(it, lastMoveRawX, lastMoveRawY) }
        // Resizing or adding a tile changes the scroll range, and with it how far the photo
        // has to travel - so the crop is rebuilt whenever anything it depends on moves.
        if (signature() != lastSignature) pushBackgroundToTiles() else updateTileOffsets()
    }

    /**
     * Runs the wall to its end, once whatever has just been added to it has been placed.
     *
     * For a tile that has just been pinned: it goes on the end, which on a full Start
     * screen is off the bottom of it, and an app that appears somewhere the user cannot
     * see is indistinguishable from one that was never pinned at all.
     *
     * Waited for rather than done now - the wall has not been measured with the new tile
     * yet, so where its end *is* is not known until it has been.
     */
    fun scrollToEnd(framesLeft: Int = SCROLL_TO_END_FRAMES) {
        androidx.core.view.OneShotPreDrawListener.add(this) {
            // A page that is not on screen is not laid out either, so while the app list is
            // still sliding away the wall's height is the height it had before the tile
            // arrived. Asked again on the next frame rather than acted on: the wall is put
            // back on screen by the first frame of that slide, and measured in the same
            // pass this runs at the end of.
            if (visibility == VISIBLE && content.height > 0) smoothScrollTo(0, scrollRange())
            else if (framesLeft > 0) scrollToEnd(framesLeft - 1)
        }
    }

    /** Jumps to the top without animating, for a return-to-home. */
    fun scrollToTop() {
        scrollTo(0, 0)
        updateTileOffsets()
    }

    /** Staggered entrance - tiles drop in one after another when Start appears. */
    /**
     * Turnstile-in: the wall unfolds a tile at a time, each swinging round its own left
     * edge from edge-on to flat.
     *
     * This is the animation Windows Phone was known by, and it is a rotation rather than a
     * fade: every tile is hinged on its left side, so the wall opens like a run of doors
     * rather than appearing. The stagger runs in packing order, which is left to right and
     * down, and the tiles also come in from the left - the rotation alone reads as tiles
     * turning on the spot, and the phone's did not turn, they arrived.
     */
    fun playEntrance() {
        // Whatever the wall was doing when it went away, it is not doing it now. A pull
        // that was still springing back leaves the whole column shifted - which is a wall
        // that looks scrolled and cannot be scrolled back, because the offset is a
        // translation and not a scroll position.
        content.animate().cancel()
        content.translationY = 0f
        forEachTileView { it.resetAnimationState() }

        val slide = ENTRANCE_OFFSET_DP * resources.displayMetrics.density
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.cameraDistance = CAMERA_DISTANCE * resources.displayMetrics.density
            child.pivotX = 0f
            // From whatever height is known: the entrance can run before the wall has been
            // measured, and half of nothing is a pivot on the tile's top edge that outlives
            // the animation. See TileView.hingeOnMiddle.
            child.pivotY = (if (child.height > 0) child.height else child.measuredHeight) / 2f
            child.rotationY = TURNSTILE_DEGREES
            child.translationX = -slide
            // Cleared, not left alone: the turnstile out carries every tile up as well as
            // left, and an entrance that only put the horizontal offset back left the
            // whole wall drawn a few dozen pixels high - which reads as a Start screen
            // scrolled down that cannot be scrolled back, because it is a translation and
            // not a scroll position.
            child.translationY = 0f
            child.alpha = 0f
            child.animate()
                .rotationY(0f)
                .translationX(0f)
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(i * ENTRANCE_STAGGER_MS)
                .setDuration(TURNSTILE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Turnstile-out: tiles rotate away about their left edge in sequence, the way WP8.1
     * cleared Start when launching an app. [after] runs once the last tile has gone.
     */
    fun playTurnstileOut(at: Float = LAUNCH_AT, after: () -> Unit) {
        if (grid.childCount == 0) { after(); return }
        val slide = TURNSTILE_OUT_OFFSET_DP * resources.displayMetrics.density
        var maxEnd = 0L
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.pivotX = 0f
            child.pivotY = (if (child.height > 0) child.height else child.measuredHeight) / 2f
            child.cameraDistance = CAMERA_DISTANCE * resources.displayMetrics.density
            val delay = i * TURNSTILE_STAGGER_MS
            maxEnd = maxOf(maxEnd, delay + TURNSTILE_MS)
            child.animate()
                .rotationY(-TURNSTILE_DEGREES)
                // Away from the app that is opening: up and to the left, so the wall reads
                // as being left behind rather than as closing.
                .translationX(-slide)
                .translationY(-slide)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(TURNSTILE_MS)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
        }
        // How far into the turn whatever is opening is asked for. See [launchWithTurnstile]:
        // the wall clearing and an app arriving are one movement, and where the system has
        // an animation of its own to cover the join, the two are allowed to overlap.
        postDelayed({ after() }, (maxEnd * at).toLong())
    }

    /** Restores tiles after a [playTurnstileOut], e.g. on returning to Start. */
    fun resetTurnstile() {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.rotationY = 0f
            child.translationX = 0f
            child.translationY = 0f
            child.alpha = 1f
        }
    }



    /** Hands each tile its current notifications. */
    fun setNotifications(lookup: (Tile) -> List<TileView.Line>) {
        forEachTileView { it.setNotifications(lookup(it.tile)) }
    }

    /** Hands each folder tile the contents its preview is made of. */
    fun setFolderPreviews(lookup: (Tile) -> List<FolderPreviewView.Entry>) {
        forEachTileView { it.setFolderPreview(lookup(it.tile)) }
    }

    /** Hands each tile whatever its app is playing, or null. */
    fun setMedia(lookup: (Tile) -> MediaSessions.Info?) {
        forEachTileView { it.setMedia(lookup(it.tile)) }
    }

    /** Routes a tile's transport buttons back to the host. */
    fun setMediaHandlers(
        onPlayPause: (Tile) -> Unit,
        onNext: (Tile) -> Unit,
        onPrevious: (Tile) -> Unit
    ) {
        forEachTileView { view ->
            view.onMediaPlayPause = { onPlayPause(view.tile) }
            view.onMediaNext = { onNext(view.tile) }
            view.onMediaPrevious = { onPrevious(view.tile) }
        }
    }

    companion object {
        private const val DRAG_ELEVATION = 24f
        private const val DRAG_SCALE = 1.06f
        // The arrow under the wall: its disc, and the air kept round it.
        private const val APP_LIST_ARROW_DP = 46
        private const val APP_LIST_ARROW_MARGIN_DP = 18

        // The gap an opened folder makes: its rules, the air around them, and how long
        // the wall takes to part.
        private const val BAND_BAR_DP = 10f
        private const val BAND_GAP_DP = 10f
        private const val BAND_TOP_DP = 14f
        private const val BAND_MS = 520L

        // A tile being drawn into the folder it was dropped on.
        private const val ABSORB_MS = 190L
        private const val ABSORB_SCALE = 0.5f

        /**
         * How long a tile has to be held in the middle of another before they offer to go
         * together.
         *
         * Short: the middle of a tile is not somewhere a drag passes through by accident
         * now that crossing one no longer moves it, so the dwell is only there to tell
         * resting on a tile apart from travelling over it.
         */
        private const val FOLD_DWELL_MS = 280L

        /**
         * How much of a tile is its middle: the radius two tiles are put together within,
         * as a share of the shorter side of the one being rested on. See [inFoldZone].
         *
         * A shade under half, so the middle is about half the area of a square tile and
         * the corners are the wall's.
         */
        private const val FOLD_RADIUS_FRACTION = 0.42f


        private const val ENTRANCE_STAGGER_MS = 28L
        private const val ENTRANCE_OFFSET_DP = 24f
        private const val FLIP_STAGGER_MS = 220L

        /** How long one tile takes to swing in, and how far it comes from. */
        private const val TURNSTILE_IN_MS = 300L

        /** How far a leaving tile travels up and left as it turns away. */
        private const val TURNSTILE_OUT_OFFSET_DP = 40f

        /**
         * How far into the turnstile an installed app is opened.
         *
         * Short of the end on purpose - the system's own opening animation covers the rest
         * of the turn. What the shell opens itself waits for all of it.
         */
        private const val LAUNCH_AT = 0.7f

        /**
         * Perspective for the hinge.
         *
         * Without a camera distance proportional to density the rotation skews rather than
         * turns - the same number TiltEffect uses, and for the same reason.
         */
        private const val CAMERA_DISTANCE = 8000f

        /**
         * How far the wall can be dragged down before the shade takes over.
         *
         * Shorter than the threshold that opens it, deliberately: the wall should be
         * visibly at the end of its travel by the time the gesture completes, so the shade
         * arriving reads as the next thing rather than as an interruption.
         */
        private const val OVERSCROLL_LIMIT_DP = 68f

        /** The pull down is this many times the plain edge swipe. */
        private const val PULL_DOWN_FACTOR = 3.2f

        private const val OVERSCROLL_RETURN_MS = 260L

        /** A little past flat on the way back, so the wall settles rather than stops. */
        private const val OVERSCROLL_SPRING = 1.6f

        /** How far to drag past either end of Start before the gesture fires. */
        private const val EDGE_SWIPE_DP = 48f
        private const val TURNSTILE_STAGGER_MS = 30L
        private const val TURNSTILE_MS = 220L
        private const val TURNSTILE_DEGREES = 80f

        /**
         * How long [scrollToEnd] will wait for the wall to be on screen and measured.
         *
         * A handful of frames, so a wall that never appears at all - pinned from somewhere
         * that does not go back to Start - gives up rather than asking every frame forever.
         */
        private const val SCROLL_TO_END_FRAMES = 12

        /** How long a displaced tile takes to slide into its new slot. */
        private const val REFLOW_MS = 160L

        /** Shrinks a tile's hit area while dragging, so boundaries do not flip-flop. */
        private const val REORDER_HYSTERESIS = 0.22f

        /** Minimum gap between reorders, so the grid settles before it moves again. */
        private const val REORDER_COOLDOWN_MS = 180L

        /**
         * How long the finger has to stay on a slot before the wall opens it.
         *
         * Asking rather than passing through. Short enough that arranging still feels
         * immediate, long enough that a tile carried across the wall leaves the tiles it
         * crosses where they are - which is what makes the one being aimed at still there
         * when the finger arrives.
         */
        private const val REORDER_DWELL_MS = 260L


        /**
         * Target travel of the Start background relative to the tiles, 0 to 1. Low enough
         * that the photo drifts rather than scrolls. The rate actually used is
         * [effectiveParallax], which honours the zoom ceiling.
         */
        private const val PARALLAX_FACTOR = 0.3f

        /** Ceiling on the zoom, as a fraction of viewport height. */
        /** How deep the strip at each edge is that starts the page scrolling under a drag. */
        private const val EDGE_SCROLL_BAND_DP = 96f

        /** Scroll rate at the very edge, per frame - about 400dp a second at 60fps. */
        private const val EDGE_SCROLL_MAX_DP = 7f

        private const val MAX_OVERSCAN = 0.35f

        /**
         * How far the photo drifts, as a fraction of the screen.
         *
         * The travel is also the speed: the wander crosses it in a fixed time either way,
         * so widening it moves the photo further *and* faster. At half this it was subtle
         * to the point of being arguable - which, for an effect you turn on deliberately,
         * is the wrong side of the line to be on. The cost is zoom: the travel is cut out
         * of the photo, not added around it.
         */
        private const val DRIFT_FRACTION = 0.12f

        private val EMPTY_RECT = Rect()
    }
}
