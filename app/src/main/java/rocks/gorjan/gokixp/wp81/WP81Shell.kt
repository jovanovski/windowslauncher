package rocks.gorjan.gokixp.wp81

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import rocks.gorjan.gokixp.AppInfo

/**
 * The Windows Phone 8.1 shell: everything the launcher draws when this theme is active.
 *
 * Replaces the desktop metaphor wholesale - there is no wallpaper, no taskbar, no Start
 * menu and no desktop icons. Instead:
 *
 * ```
 *   +--------------------------------+
 *   |  (Android's own status bar)    |
 *   +--------------------------------+
 *   |                                |
 *   |  Start screen  <->  app list   |   horizontally paged, with parallax
 *   |                                |
 *   +--------------------------------+
 *   |    <-        [#]        Q      |   nav bar
 *   +--------------------------------+
 * ```
 *
 * Windowed programs still run, in Vista chrome, in the activity's existing floating
 * window container which sits above this view. [setWindowBackdropVisible] paints the
 * black ground they sit on.
 */
@SuppressLint("ViewConstructor")
class WP81Shell(
    context: Context,
    private var palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : FrameLayout(context) {

    val navBar = WP81NavBar(context, palette)

    /**
     * The app bar: the commands for whatever is being held or filled, on a strip that
     * slides up over the wall. The keys below it never change - see [WP81SecondaryBar].
     */
    val secondaryBar = WP81SecondaryBar(context)
    val startScreen = StartScreenView(context, palette)
    val appList = AppListView(context, palette, iconProvider)

    private val pages = FrameLayout(context)
    val folderPage = FolderPageView(context, palette)
    val settingsPage = WP81SettingsView(context, palette)
    val contextMenu = WP81ContextMenu(context, palette)
    val iconPicker = WP81IconPicker(context, palette)

    /** Picks a tile's colour. Opened from the key strip while a tile is selected. */
    val colorPicker = WP81ColorPicker(context, palette)
    val inputDialog = WP81InputDialog(context, palette)
    val toast = WP81Toast(context, palette)
    private val windowBackdrop = View(context)

    /** 0 = Start screen, 1 = app list. */
    private var pageProgress = 0f
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartProgress = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onSearch: (() -> Unit)? = null

    init {
        setBackgroundColor(palette.background)

        pages.addView(startScreen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pages.addView(appList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // Settings is its own Metro page, not the Vista Display Properties window.
        settingsPage.visibility = GONE
        addView(settingsPage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // Folders open as a Metro page over Start rather than in a Vista window.
        folderPage.visibility = GONE
        addView(folderPage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // The app bar sits over the pages and under the keys - it slides out from beneath
        // them, so the nav bar has to be drawn after it - and takes no space of its own:
        // its margin puts it directly on top of the keys, and the wall below is untouched.
        addView(secondaryBar, LayoutParams(
            LayoutParams.MATCH_PARENT, dp(WP81SecondaryBar.HEIGHT_DP)
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // Backdrop for windowed programs. Non-maximizable windows - Winamp, the Phone
        // Dialer - keep their fixed size and Vista chrome, so without this they would
        // float over the Start screen; WP8.1 has no such notion, so they get black.
        windowBackdrop.setBackgroundColor(Color.BLACK)
        windowBackdrop.visibility = GONE
        windowBackdrop.isClickable = true
        addView(windowBackdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(navBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(WP81NavBar.HEIGHT_DP)).apply {
            gravity = android.view.Gravity.BOTTOM
        })

        // The icon picker is a page, so it clears the navigation bar like the others.
        addView(iconPicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // Added last so they dim and cover everything, navigation bar included - WP8.1's
        // context menus and prompts take over the whole screen.
        addView(contextMenu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(colorPicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inputDialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Topmost: a toast announces something over whatever is on screen, including an
        // open context menu or prompt. Held above the navigation keys so it never covers
        // the controls the user might be reaching for.
        addView(toast, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(WP81NavBar.HEIGHT_DP)
        })

        // The Start key means Start, wherever the user is not already: a key with a
        // Windows flag on it that took you *away* from the Start screen read as broken.
        // Only once there - with nothing to return from - does it take on its second job
        // and page to the app list. To the list itself, not into a search of it: a key
        // pressed to see what is installed should not answer with a keyboard over it.
        navBar.onStart = { if (isOnStartPage()) goToAppList() else goToStart() }
        // The app bar follows what the user is doing: arranging tiles or filling a folder
        // each bring up the commands for that job, above the keys rather than instead of
        // them.
        startScreen.onEditModeChanged = { editing -> refreshNavMode() }
        navBar.onSearch = { onSearch?.invoke() }

        post { applyPageProgress(0f) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- paging

    fun goToStart(animated: Boolean = true) {
        appList.hideJumpList()
        if (appList.isSearching()) appList.endSearch()
        folderPage.hide()
        closeSettings()
        iconPicker.dismiss()
        colorPicker.dismiss()
        inputDialog.dismiss()
        appList.onPick = null
        onPickerCancelled = null
        refreshNavMode()
        animateTo(0f, animated)
    }

    /**
     * Chooses the app bar for whatever is currently on screen.
     *
     * The three keys are not part of this any more - they are the same three wherever the
     * user is - but every call site that used to mean "the strip has to change" still
     * means it, so the name stays.
     */
    fun refreshNavMode() {
        secondaryBar.setMode(
            when {
                // A folder page is on top when open, so its selection wins.
                isFolderOpen() && folderPage.contents.isEditMode -> WP81SecondaryBar.Mode.EDIT_FOLDER
                isFolderOpen() -> WP81SecondaryBar.Mode.FOLDER
                startScreen.isEditMode -> WP81SecondaryBar.Mode.EDIT_START
                else -> WP81SecondaryBar.Mode.NONE
            },
            // Both editing commands act on the selected tile, so they are offered only
            // when there is one.
            hasSelection = selectedTile() != null
        )
    }

    private var onPickerCancelled: (() -> Unit)? = null

    /**
     * Shows the app list as a picker.
     *
     * The folder page is hidden for the duration - it is drawn above the pages, so leaving
     * it up would put the list behind it. [onPick] receives the chosen app and [onCancel]
     * runs if the user backs out; both are responsible for restoring whatever was open.
     */
    fun openAppPicker(onCancel: () -> Unit, onPick: (AppInfo) -> Unit) {
        folderPage.hide()
        onPickerCancelled = onCancel
        appList.onPick = { app ->
            endPicking()
            onPick(app)
        }
        goToAppList()
        appList.scrollToTop()
    }

    fun isPicking(): Boolean = appList.onPick != null

    /** The tile currently selected, on whichever surface is on top. */
    fun selectedTile(): Tile? =
        if (isFolderOpen()) folderPage.contents.editingTile else startScreen.editingTile

    /** Ends editing on whichever surface is on top. */
    fun exitEditModeEverywhere() {
        folderPage.contents.exitEditMode()
        startScreen.exitEditMode()
    }

    /** Leaves picker mode and returns to the Start screen. */
    private fun endPicking() {
        appList.onPick = null
        onPickerCancelled = null
        if (appList.isSearching()) appList.endSearch()
        animateTo(0f, animated = true)
        refreshNavMode()
    }

    fun openFolder(
        name: String,
        tiles: List<Tile>,
        notifications: (Tile) -> List<TileView.Line> = { emptyList() },
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        folderPage.show(name, tiles, notifications, tileColors, glyphs)
        refreshNavMode()
    }

    // A page turning out is on its way somewhere else and no longer counts as where the
    // user is - see MetroPageTransition.isOnScreen.
    fun isFolderOpen(): Boolean = folderPage.isOnScreen()

    fun isSettingsOpen(): Boolean = settingsTransition.isOnScreen

    /** The turnstile for the settings page. The folder page owns its own. */
    private val settingsTransition = MetroPageTransition(settingsPage)

    fun openSettings() {
        folderPage.hide()
        // Whatever was left open last time is folded away before it is shown again.
        settingsPage.onOpened()
        settingsTransition.playIn()
        refreshNavMode()
    }

    fun closeSettings() {
        // The strip is set now rather than when the turn finishes: the keys belong to the
        // page the user is on their way to, not the one on its way out.
        settingsTransition.playOut()
        refreshNavMode()
    }

    fun closeFolder() {
        folderPage.hide()
        refreshNavMode()
    }

    /**
     * Sets the Start background photo.
     *
     * The photo is never drawn behind the whole screen: the page stays flat black or white
     * and each tile draws the slice of the photo behind it, so the tiles read as windows
     * onto the image. [focusX] slides a photo wider than the screen left or right.
     */
    /** Whether the Start background wanders behind the tiles. */
    fun setBackgroundDrift(enabled: Boolean) {
        startScreen.driftEnabled = enabled
    }

    fun setStartBackground(bitmap: android.graphics.Bitmap?, focusX: Float) {
        startScreen.setStartBackground(bitmap, focusX)
    }

    /** Pages to the app list and drops straight into search, keyboard up. */
    fun openAppSearch() {
        folderPage.hide()
        goToAppList()
        // Not with the phone on its side: a keyboard there takes two thirds of the screen
        // and leaves a list of two apps behind it, so the way to the list is the list. The
        // search is still a key away on the strip.
        if (resources.configuration.orientation !=
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            // Instant, for the same reason the sideways swipe is: this runs while the page
            // is still crossing, and the two animations on top of each other are the
            // stutter. The gesture is the movement; search is where it lands.
            appList.beginSearch(animated = false)
        }
    }

    fun goToAppList(animated: Boolean = true) {
        animateTo(1f, animated)
        refreshNavMode()
    }

    fun isOnAppList(): Boolean = pageProgress > 0.5f

    /**
     * Where the user is.
     *
     * One answer, in one place, because every time this was worked out again at a call
     * site it was worked out slightly differently - and each of those is a bug of the same
     * shape: the Start key opening a search box over a program, the first key offering
     * settings while the user was inside Zune. A program covering the shell is a place the
     * user can be, even though the shell itself has not moved.
     */
    enum class Place { START, APP_LIST, FOLDER, SETTINGS, PROGRAM }

    fun where(): Place = when {
        programOnScreen -> Place.PROGRAM
        isSettingsOpen() -> Place.SETTINGS
        isFolderOpen() -> Place.FOLDER
        isOnAppList() -> Place.APP_LIST
        else -> Place.START
    }

    /**
     * Whether a windowed program is covering the shell.
     *
     * Set by the host, which owns the windows - the shell cannot see them, they are drawn
     * above it by a container it knows nothing about.
     */
    var programOnScreen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshNavMode()
        }

    /** Whether the Start screen itself is what the user is looking at. */
    fun isOnStartPage(): Boolean = where() == Place.START

    private fun animateTo(target: Float, animated: Boolean) {
        if (!animated) { applyPageProgress(target); return }
        ValueAnimator.ofFloat(pageProgress, target).apply {
            duration = PAGE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyPageProgress(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * WP8.1 does not slide the two pages in lockstep: the outgoing page trails at a
     * fraction of the incoming page's speed, which reads as depth rather than a swap.
     */
    private fun applyPageProgress(p: Float) {
        val wasOnAppList = pageProgress > 0.5f
        pageProgress = p.coerceIn(0f, 1f)
        val w = width.toFloat().takeIf { it > 0f } ?: return
        startScreen.translationX = -pageProgress * w * PARALLAX
        startScreen.alpha = 1f - pageProgress * 0.35f
        appList.translationX = (1f - pageProgress) * w
        val listGone = pageProgress <= 0.001f
        // Put back to the top the moment it is off the screen, so every arrival at the
        // list is an arrival at the top of it. Done on the way out rather than on the way
        // in for the obvious reason: nobody can see it happen here, where the same jump
        // on arrival would be the first thing they saw.
        if (listGone && appList.visibility != GONE) appList.scrollToTop()
        appList.visibility = if (listGone) GONE else VISIBLE
        startScreen.visibility = if (pageProgress >= 0.999f) GONE else VISIBLE
        // Crossing between Start and the app list swaps settings for back.
        if (wasOnAppList != (pageProgress > 0.5f)) refreshNavMode()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Don't hijack gestures while a tile is being dragged, the jump list is up, or a
        // folder page is open - none of those are paged left-right.
        if (startScreen.isEditMode || appList.isJumpListVisible() ||
            isFolderOpen() || isSettingsOpen() ||
            contextMenu.isShowing() || iconPicker.isShowing() || inputDialog.isShowing()
        ) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Not consumed: the Start screen needs this DOWN to know where a pull-down
                // for the notification shade began.
                dragStartX = ev.x
                dragStartY = ev.y
                dragStartProgress = pageProgress
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - dragStartX
                val dy = ev.y - dragStartY
                // Only a mostly-horizontal drag pages; vertical belongs to the lists.
                // A drag towards a page that does not exist is dead - Start has nothing to
                // its left, the app list nothing to its right. Leaving those uncaught frees
                // a rightward swipe on Start to mean something to the tile underneath.
                val deadDirection =
                    (pageProgress <= 0f && dx > 0f) || (pageProgress >= 1f && dx < 0f)
                if (!dragging &&
                    !deadDirection &&
                    kotlin.math.abs(dx) > touchSlop &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    dragging = true
                    // A drag off Start towards the list is somebody going to the list to
                    // type - so the list is made into a search now, while it is still off
                    // the edge of the screen, and slides in already being one. Laid out
                    // rather than animated: the page is the animation, and a second one
                    // over it - rows rebuilding, letters folding, the field rising - is
                    // what made the arrival stutter. No keyboard yet; the finger can still
                    // turn back, and a keyboard over the Start screen would be a promise
                    // the gesture has not made.
                    if (pageProgress <= 0.5f && dx < 0f) {
                        appList.beginSearch(animated = false, showKeyboard = false)
                    }
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        val w = width.toFloat().takeIf { it > 0f } ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                applyPageProgress(dragStartProgress - (ev.x - dragStartX) / w)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                // Commit on a short travel rather than half the screen: this is a page
                // flick, not a scrub, and dragging 50% across just to change page is
                // tiring. The threshold is measured from where the drag began, so
                // going back needs no more travel than coming in did.
                val travelled = pageProgress - dragStartProgress
                val target = when {
                    travelled > COMMIT_FRACTION -> 1f
                    travelled < -COMMIT_FRACTION -> 0f
                    else -> dragStartProgress
                }
                animateTo(target, animated = true)
                // Swiping settles the page directly rather than going through
                // goToStart()/goToAppList(), so search has to be closed here too -
                // otherwise swiping back to Start left the keyboard up over it.
                //
                // The swipe is the one way in that still opens search with it: it is the
                // gesture of somebody already reaching for the list, and their hands are
                // in the right place to type. The arrow and the Start key are for looking
                // through what is there, and the button at the top of the rail is for
                // saying so on purpose.
                // Arrived: the layout is already a search - see onInterceptTouchEvent -
                // so all that is left is the keyboard. Turned back: undo it.
                if (target == 1f) appList.beginSearch(animated = false)
                else appList.endSearch(animated = false)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- state

    fun setApps(apps: List<AppInfo>) = appList.setApps(apps)

    fun setWindowBackdropVisible(visible: Boolean) {
        if (visible == (windowBackdrop.visibility == VISIBLE)) return
        if (visible) {
            windowBackdrop.alpha = 0f
            windowBackdrop.visibility = VISIBLE
            windowBackdrop.animate().alpha(1f).setDuration(150).start()
        } else {
            windowBackdrop.animate().alpha(0f).setDuration(150)
                .withEndAction { windowBackdrop.visibility = GONE }.start()
        }
    }

    /**
     * Back-press chain for the shell itself, innermost first. Returns true if handled;
     * the activity falls through to its own window handling otherwise.
     */
    fun handleBack(): Boolean {
        if (isPicking()) {
            // Backing out of picking returns to whatever asked for it.
            val cancelled = onPickerCancelled
            endPicking()
            cancelled?.invoke()
            return true
        }
        if (inputDialog.isShowing()) { inputDialog.dismiss(); return true }
        if (contextMenu.isShowing()) { contextMenu.dismiss(); return true }
        if (iconPicker.isShowing()) { iconPicker.dismiss(); return true }
        if (colorPicker.isShowing()) { colorPicker.dismiss(); return true }
        if (isSettingsOpen()) { closeSettings(); return true }
        if (isFolderOpen()) {
            // Editing inside the folder takes back first, then the page itself.
            if (folderPage.handleBack()) return true
            folderPage.hide()
            refreshNavMode()
            return true
        }
        if (appList.handleBack()) return true
        // The wall first: a tile being arranged is the innermost thing on screen, and it
        // is arranged inside the folder as often as outside it. Closing the folder around
        // a selected tile skipped a whole layer.
        if (startScreen.handleBack()) return true
        // A folder opened into the wall closes back into it, rather than back being the
        // way out of Start - which is nowhere.
        if (startScreen.isFolderOpen()) {
            startScreen.closeFolder()
            return true
        }
        // One press leaves the app list, whether or not search is open - goToStart()
        // closes search on the way out.
        if (isOnAppList()) { goToStart(); return true }
        return false
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        navBar.applyPalette(p)
        colorPicker.applyPalette(p)
        startScreen.applyPalette(p)
        appList.applyPalette(p)
        folderPage.applyPalette(p)
        settingsPage.applyPalette(p)
        contextMenu.applyPalette(p)
        iconPicker.applyPalette(p)
        inputDialog.applyPalette(p)
        toast.applyPalette(p)
    }

    companion object {
        private const val PAGE_MS = 260L

        /** How far across the screen a drag must travel to change page. */
        private const val COMMIT_FRACTION = 0.18f

        /** How much slower the outgoing page travels. */
        private const val PARALLAX = 0.35f
    }
}
