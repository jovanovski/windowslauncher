package rocks.gorjan.gokixp.car

import android.app.Presentation
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import rocks.gorjan.gokixp.NotificationListenerService
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.StartScreenView
import rocks.gorjan.gokixp.wp81.Tile
import rocks.gorjan.gokixp.wp81.TileSize
import rocks.gorjan.gokixp.wp81.TileView
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.MediaSessions
import rocks.gorjan.gokixp.wp81.WP81TileHost

/**
 * Start, on the car screen.
 *
 * The host hands navigation apps a Surface to draw their map on, and this puts a Start
 * screen on it instead. Rather than painting tiles by hand, the Surface is wrapped in a
 * VirtualDisplay and a Presentation is shown on that: the point of the exercise is to run
 * the launcher's own [StartScreenView], and a real window is what gives it a ViewRootImpl,
 * so scrolling, invalidation and the tile animations all work as they do on the phone.
 *
 * Input is the part that does not come free. The host reports gestures rather than
 * touches - there is no long-press at all - so taps are turned back into MotionEvents and
 * posted into the window, and scrolling is applied to the scroller directly, which is far
 * steadier than trying to reconstitute a drag from deltas.
 */
class CarStartScreen(carContext: CarContext) : Screen(carContext) {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var startScreen: StartScreenView? = null

    /** Reads and drives whatever is playing, for the two tiles that control a player. */
    private var media: MediaSessions? = null

    /** The part of the surface the host never covers with its own chrome. */
    private var stableArea = Rect()

    /** The surface's own size, kept because Display's getters for it are deprecated. */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            Log.i(TAG, "surface available: ${container.width}x${container.height} @${container.dpi}dpi")
            attach(container)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            Log.i(TAG, "surface destroyed")
            detach()
        }

        override fun onStableAreaChanged(area: Rect) {
            Log.i(TAG, "stable area: $area")
            stableArea = area
            applyInsets()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            Log.i(TAG, "visible area: $area")
        }

        override fun onClick(x: Float, y: Float) {
            // Deliberately dropped for now. Something about handing a tap to the wall makes
            // the host take the screen back: the tiles fold away and leave nothing behind,
            // with ON_STOP in the host's log, no error, and no way back to them. Claiming
            // the surface through NavigationManager did not change it, so the cause is not
            // yet known - and a wall that cannot be tapped is worth far more than one that
            // disappears when it is. See [tap], which is kept for when it is understood.
            if (!TAPS_ENABLED) {
                Log.i(TAG, "ignoring a tap at $x,$y while the wall folds away on every one")
                return
            }
            tap(x, y)
        }

        /**
         * Deltas, not a drag.
         *
         * Handed to the scroller rather than replayed as MotionEvents: a ScrollView driven
         * by synthetic moves needs a matching DOWN and UP around them or it never settles,
         * and the host's deltas already carry everything the scroll needs.
         */
        override fun onScroll(distanceX: Float, distanceY: Float) {
            val view = startScreen ?: return
            view.post { view.scrollBy(0, distanceY.toInt()) }
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            val view = startScreen ?: return
            view.post { view.fling(-velocityY.toInt()) }
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = Unit
    }

    init {
        // The car app shares the launcher's process, so anything thrown out of here takes
        // the home screen down with it - which is exactly how the missing ACCESS_SURFACE
        // permission first showed up: a SecurityException on this line force-finished
        // MainActivity. Nothing the car screen does is worth losing the launcher over, so
        // the surface is treated as something that may simply not arrive.
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        } catch (e: Exception) {
            Log.e(TAG, "no surface handed over; the car screen will stay blank", e)
        }

        // Claiming the navigation surface, which is not the same as being registered for
        // it. A navigation app that never says it has started navigating leaves the screen
        // unclaimed, and the host takes it back at the first opportunity - which is what
        // was happening on every tap: the wall was replaced a moment after it was drawn,
        // with ON_STOP in the host's log and no error anywhere to explain it.
        //
        // There is no route to navigate, and none is claimed. What is being held is the
        // screen: this app's whole reason for being a navigation app is that navigation
        // apps are the only ones given a surface to draw on.
        try {
            val navigation = carContext.getCarService(NavigationManager::class.java)
            navigation.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    // The host asking for the surface back - when a real navigator starts,
                    // say. Nothing to tear down: the wall is not a route.
                    Log.i(TAG, "host asked navigation to stop")
                }

                override fun onAutoDriveEnabled() {
                    Log.i(TAG, "auto drive enabled")
                }
            })
            navigation.navigationStarted()
            Log.i(TAG, "claimed the navigation surface")
        } catch (e: Exception) {
            Log.w(TAG, "could not claim the navigation surface", e)
        }
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Start")
                            .setOnClickListener { invalidate() }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun attach(container: SurfaceContainer) {
        val surface = container.surface ?: return
        detach()
        try {
            // The first surface of a session arrives with a density of 0, and a virtual
            // display cannot be made at that. A second callback follows with the real
            // figure, so the sane thing is to wait for it rather than invent one.
            if (container.dpi <= 0) {
                Log.i(TAG, "ignoring surface with no density; waiting for the real one")
                return
            }
            surfaceWidth = container.width
            surfaceHeight = container.height
            val displays = carContext.getSystemService(DisplayManager::class.java)
            val display = displays.createVirtualDisplay(
                DISPLAY_NAME,
                container.width,
                container.height,
                // Deliberately not the density the car reports. At the head unit's own
                // 160dpi an 800x400 panel reads as an 800dp-wide screen - tablet width -
                // and the wall answers by spreading into a dozen thin columns of postage
                // stamps. The panel is not a tablet held at arm's length; it is a small
                // screen read from a metre away, so it is described as a dense one, and
                // the same layout that suits a phone comes out at twice the pixels.
                CAR_DENSITY_DPI / ZOOM,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: run {
                Log.e(TAG, "no virtual display")
                return
            }
            virtualDisplay = display

            val themeManager = ThemeManager(carContext)
            val palette = WP81Palette.from(themeManager)

            // The same object the launcher builds its wall from, so a tile resized or
            // recoloured on the phone is resized and recoloured here too. Nothing is
            // passed that could write: the car screen has no long-press and so no way to
            // rearrange anything, and it must not be able to disturb the phone's wall.
            //
            // No icon list yet, which leaves the built-in live tiles - the clock, the
            // weather, the calendar, air quality - since those are placed by preference
            // rather than by an icon. The app tiles want the launcher's icon list, which
            // is still only reachable from MainActivity.
            // Read once and held: the wall asks for the list again for every tile it
            // colours, and re-parsing the whole arrangement each time would be absurd.
            media = MediaSessions(carContext)
            val icons = WP81TileHost.loadIcons(carContext)
            val tileHost = WP81TileHost(
                context = carContext,
                icons = { icons },
                // The phone's hidden set is not the car's. A clock hidden on a phone that
                // shows one in its status bar says nothing about a car screen, which has
                // no status bar; and the picture tile is not something to be reading at
                // the wheel. So the car names what it wants and ignores the rest.
                hiddenTiles = { theme ->
                    theme.getWP81HiddenTiles() - CAR_TILES + (ALL_BUILT_INS - CAR_TILES)
                }
            ).apply { refreshColors() }

            val show = Presentation(carContext, display.display)
            // A Presentation is a Dialog, and a Dialog shown from a service is the part of
            // this that may yet be refused. If it is, the catch below reports which way.
            val view = StartScreenView(show.context, palette).apply {
                setBackgroundColor(palette.background)
                // The phone's four columns assume a tall screen. The car's is short and
                // wide - 300dp of guaranteed height in the head unit - so four columns
                // would not fit a single medium tile down the page.
                // Kept, not scaled: see StartScreenView.autoColumns.
                // Nothing on this wall is meant to be pressed while taps are off, and an
                // arrow with no app list behind it least of all.
                showAppListArrow = false
                autoColumns = false
                columns = CAR_COLUMNS * ZOOM
                // Halved with the columns, so the gutters shrink along with the tiles
                // rather than staying the width they were around smaller ones.
                metricBasisOverride = container.height / ZOOM
                // Whether a tile wears the number as well as the message, as set on the phone.
                countsEnabled = themeManager.getWP81TileCounts()
                // Nothing is wired to actually launch yet; these are here so that a tap
                // that reaches the wall can be told apart from one that never lands.
                onLaunch = { tile -> open(tile) }
                onOpenAppList = { Log.i(TAG, "wall asked for the app list") }
                onFolderOpened = { tile -> Log.i(TAG, "folder ${tile.label}"); emptyList<Tile>() }
                onEditModeChanged = { on -> Log.i(TAG, "edit mode $on") }
                // Sizes are left exactly as the user arranged them.
                //
                // Giving the live tiles a second cell was the wrong way to make them
                // legible: a widget sizes its reading from one cell rather than from the
                // tile it sits on (see TileGridLayout.cellSize), so a two-cell tile on a
                // grid of small cells gets a reading built for half its width. The cell
                // is what has to be big, which is what the column count is for.
                // Maps is not pinned on the phone, and on a car screen it is the one
                // thing that has somewhere to go: the host will hand over to whatever it
                // considers the navigator, which is the only handoff it allows at all. So
                // the car adds a tile the phone's wall does not have.
                val tiles = (listOf(
                    Tile(
                        id = MAPS,
                        label = "Maps",
                        packageName = MAPS,
                        size = TileSize.MEDIUM,
                        index = -1,
                        kind = Tile.Kind.APP
                    )
                ) + tileHost.buildTiles())
                    .sortedBy { it.index }
                    .onEachIndexed { i, tile -> tile.index = i }
                Log.i(TAG, "wall has ${tiles.size} tiles: ${tiles.joinToString { it.label }}")
                setTiles(
                    tiles,
                    liveWidget = { tile ->
                        tileHost.liveContent(tile) { size -> tileHost.calendarSummary(size) }
                    },
                    tileColors = { tileHost.colorFor(it) }
                ) { tile -> tileHost.glyphFor(tile) }

                // What is waiting in each app. The listener keeps these process-wide, so
                // the car reads the same lines the phone's tiles are showing without a
                // permission or a service of its own - which is the whole point of the
                // tile from the driver's seat: whether there is a mail worth stopping for.
                setNotifications { tile ->
                    NotificationListenerService.getNotificationLines(tile.packageName)
                        .map { TileView.Line(it.title, it.text) }
                }

                // The weather turns over three readings rather than showing one, so it
                // arrives separately from the rest - see WP81TileHost.weatherFaces.
                tiles.firstOrNull { it.kind == Tile.Kind.LIVE_WEATHER }?.let { weather ->
                    val faces = tileHost.weatherFaces(weather.size)
                    if (faces.isNotEmpty()) {
                        setLiveWidgetRotation(weather.id, faces, TileView.LiveStyle.READING)
                    }
                }
            }
            show.setContentView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            show.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background))
            show.show()

            // Does the wall's own font resolve through a Presentation's context? It comes
            // back null rather than throwing when it cannot, and a null typeface is
            // silently the system default - which is exactly what a wall of Segoe turning
            // into a wall of Roboto looks like.
            val probe = androidx.core.content.res.ResourcesCompat.getFont(
                show.context, rocks.gorjan.gokixp.R.font.segoeui_semibold)
            Log.i(TAG, "segoeui_semibold in presentation context: ${probe ?: "NULL - falling back"}")

            presentation = show
            startScreen = view
            applyInsets()
            Log.i(TAG, "presentation shown on virtual display")
        } catch (e: Exception) {
            Log.e(TAG, "could not put a presentation on the car display", e)
            detach()
        }
    }

    /** Keeps the tiles out from under the chrome the host draws over the surface edges. */
    private fun applyInsets() {
        val view = startScreen ?: return
        if (stableArea.isEmpty) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        // The host shrinks this while it puts something of its own over the surface - a
        // panel on the right when a media app starts. Following it repacks the entire wall
        // into whatever strip is left, which reads as the tiles vanishing. The wall is laid
        // out for the screen, so a temporary lid over part of it is not a new screen.
        if (stableArea.width() < surfaceWidth * 0.8f) {
            Log.i(TAG, "ignoring a stable area of ${stableArea.width()}px on a ${surfaceWidth}px surface")
            return
        }
        val right = (surfaceWidth - stableArea.right).coerceAtLeast(0)
        val bottom = (surfaceHeight - stableArea.bottom).coerceAtLeast(0)
        Log.i(TAG, "padding L${stableArea.left} T${stableArea.top} R$right B$bottom")
        view.post { view.setPadding(stableArea.left, stableArea.top, right, bottom) }
    }

    /** Turns the host's click back into the down/up a tile expects. */
    private fun tap(x: Float, y: Float) {
        val window = presentation?.window ?: return
        val now = SystemClock.uptimeMillis()
        val decor = window.decorView
        decor.post {
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0)
            decor.dispatchTouchEvent(down)
            decor.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    /**
     * What a tile does when tapped, which for nearly all of them is nothing.
     *
     * Almost no phone app has anywhere to go on a car screen: Android Auto will not
     * project an activity, so a tile that opened one would either be refused or, worse,
     * open it on the phone for a passenger to find later. The three that do work are the
     * two players, which are driven in place through their media session, and the map,
     * which the host will hand over to. The rest are there to be read, not pressed.
     */
    private fun open(tile: Tile) {
        when (tile.packageName) {
            MAPS -> {
                // Naming the app, because leaving it open sends the request to whichever
                // app the car considers its navigator - and this app is registered as one,
                // so an unaddressed navigation request comes straight back to this very
                // service. Being a navigation app is what earns the drawing surface, so
                // there is no version of this that is not also the car's navigator.
                val intent = Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0"))
                    .setPackage(MAPS)
                try {
                    carContext.startCarApp(intent)
                    Log.i(TAG, "host accepted a navigation handoff addressed to Maps")
                } catch (e: Exception) {
                    Log.w(TAG, "host refused the addressed handoff: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            SPOTIFY, ZUNE -> {
                // Bring the player's own car screen up if the host will allow it. There is
                // no documented way for a navigation app to open another app's media UI,
                // so this is an attempt rather than a contract - what it does is in the log.
                if (tile.packageName == SPOTIFY) {
                    try {
                        carContext.startCarApp(
                            Intent(Intent.ACTION_VIEW).setPackage(SPOTIFY))
                        Log.i(TAG, "host accepted the request to open Spotify")
                    } catch (e: Exception) {
                        Log.w(TAG, "host refused to open Spotify: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                // Zune is a program inside this launcher rather than an installed app, so
                // its session is held by this very process and is not found under the name
                // the tile carries. See MainActivity.wp81MediaPackageFor.
                val pkg = if (tile.packageName == ZUNE) carContext.packageName else tile.packageName
                Log.i(TAG, "play/pause $pkg")
                media?.togglePlayPause(pkg)
            }

            else -> Log.i(TAG, "${tile.label} is not one of the three that do anything")
        }
    }

    private fun detach() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        startScreen = null
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "GokiCar"
        const val DISPLAY_NAME = "GokiXP car Start"

        /**
         * What the car surface is treated as, rather than what it says it is.
         *
         * 320 makes the head unit's 800x400 panel a 400x200dp screen: phone width, which
         * is what this wall was drawn for, at double the pixels.
         */
        const val CAR_DENSITY_DPI = 320

        /**
         * How much smaller than life the wall is drawn: 1 for full size, 2 for half.
         *
         * Three separate things decide how big a tile and its writing come out, and all
         * three have to move together or the parts stop matching each other. The column
         * count sets the cell, and a widget sizes its reading from that; the density sets
         * the writing that is specified in points, which is the labels and the small text
         * beside a reading; and the basis sets the margins and the gaps. Turning only one
         * of them gives smaller tiles wearing their old lettering, or tiles that have
         * shrunk away from gutters that have not.
         */
        const val ZOOM = 2

        /**
         * Whether a tap is handed to the wall at all.
         *
         * Off until the fold is understood. The tiles are worth reading on their own -
         * the time, the weather, what is waiting in which app - and none of that needs a
         * press; the three tiles that did something are not worth losing the rest for.
         */
        const val TAPS_ENABLED = false

        /**
         * Cells across the car's Start screen.
         *
         * Fewer than the phone's four would make a two-cell tile taller than the panel;
         * more, and the cell a reading is sized from shrinks until the time reads as
         * "sun 2...". Five puts a small tile at about 134px, which is close to the share
         * of the screen one takes up on the phone.
         */
        const val CAR_COLUMNS = 5

        const val MAPS = "com.google.android.apps.maps"
        const val SPOTIFY = "com.spotify.music"
        const val ZUNE = "system.zune"

        /** Every built-in the shell offers, so the car can name the ones it does not want. */
        val ALL_BUILT_INS = setOf(
            WP81TileHost.WIDGET_CLOCK, WP81TileHost.WIDGET_AQI, WP81TileHost.WIDGET_WEATHER,
            WP81TileHost.WIDGET_CALENDAR, WP81TileHost.WIDGET_NEWS, WP81TileHost.WIDGET_PHOTOS,
            WP81TileHost.WIDGET_PEOPLE, "system.welcome"
        )

        /** What belongs on a car screen: the things worth a glance from the wheel. */
        val CAR_TILES = setOf(
            WP81TileHost.WIDGET_CLOCK, WP81TileHost.WIDGET_AQI,
            WP81TileHost.WIDGET_WEATHER, WP81TileHost.WIDGET_CALENDAR
        )
    }
}
