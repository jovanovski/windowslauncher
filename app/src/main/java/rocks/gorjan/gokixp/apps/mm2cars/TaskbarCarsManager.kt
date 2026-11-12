package rocks.gorjan.gokixp.apps.mm2cars

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.edit
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.ContextMenuView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.ThemeManager
import kotlin.concurrent.thread

/**
 * Puts the MM2 cars on the taskbar and takes them off again: loads the models, hangs the
 * [TaskbarCarsView] strip in [container] with its road on the taskbar - or wherever up the
 * desktop the road has been dragged to - and adds a tray icon, a little render of the Bullet,
 * whose long press has the toy's controls.
 */
class TaskbarCarsManager(
    private val activity: Activity,
    private val container: FrameLayout,
    private val prefs: SharedPreferences,
    private val onShowSettings: () -> Unit,
    private val onExit: () -> Unit
) {
    private var view: TaskbarCarsView? = null
    private var sounds: CarSounds? = null
    private var released = false
    private var paused = false
    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    /** How far up the desktop the road has been dragged from the taskbar, in pixels. */
    private var lift = prefs.getFloat(KEY_LIFT_DP, 0f) * density
    private val relayout = ViewTreeObserver.OnGlobalLayoutListener { place() }

    fun initialize() {
        if (view != null) return
        released = false
        container.visibility = View.VISIBLE
        val context = activity.applicationContext
        thread(name = "mm2cars-load") {
            try {
                val models = Mm2CarModel.IDS.map { Mm2CarModel.load(context, it) }
                val gate = Mm2Prop.load(context, "gate")
                val trailer = Mm2Prop.load(context, "trailer")
                val street = Mm2Street.load(context)
                main.post { attach(models, gate, trailer, street) }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load the MM2 cars", e)
            }
        }
    }

    private fun attach(models: List<Mm2CarModel>, gate: Mm2Prop, trailer: Mm2Prop, street: Mm2Street) {
        if (released || view != null) return
        val carSounds = CarSounds(activity, prefs.getBoolean(KEY_SOUNDS, false))
        val carsView = TaskbarCarsView(activity, models, gate, trailer, street, carSounds) { icon ->
            trayIcon?.setImageDrawable(BitmapDrawable(activity.resources, icon))
        }
        sounds = carSounds
        view = carsView
        container.addView(
            carsView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, carsView.stripHeight)
        )
        container.viewTreeObserver.addOnGlobalLayoutListener(relayout)
        container.post { place() }
        addTrayIcon()
        if (!paused) carsView.start()
    }

    /** Puts the strip where its road is on the taskbar's top edge, then lifts it by however far it was dragged. */
    private fun place() {
        val carsView = view ?: return
        val ground = taskbarTop() ?: return
        val home = ground - (carsView.stripHeight - carsView.belowRoad)
        // Never below the taskbar, never off the top of the screen
        lift = lift.coerceIn(0f, home.coerceAtLeast(0f))
        carsView.translationY = home - lift
    }

    /** The top of the taskbar's glass, in the container's coordinates. */
    private fun taskbarTop(): Float? {
        val taskbar = activity.findViewById<View>(R.id.taskbar_container) ?: return null
        if (taskbar.height == 0) return null
        val bar = IntArray(2)
        val own = IntArray(2)
        taskbar.getLocationOnScreen(bar)
        container.getLocationOnScreen(own)
        val themeManager = ThemeManager(activity)
        val inset = themeManager.getTaskbarGlassInsetDp(themeManager.getSelectedTheme()) * activity.resources.displayMetrics.density
        return (bar[1] - own[1]) + inset
    }

    fun pause() {
        paused = true
        view?.stop()
    }

    fun resume() {
        paused = false
        view?.resume()
    }

    /** What a touch that is the toy's took hold of: nothing, a car (already kicked), or the road. */
    private enum class Hold { NONE, CAR, ROAD }

    // A touch that started on the road or on a car belongs to the toy until it lifts
    private var hold = Hold.NONE
    private var dragging = false
    private var downY = 0f
    private var downLift = 0f

    /**
     * Touches for the toy, before the desktop sees them: a drag that starts on the road moves
     * the whole street up or down the desktop, and a tap on a car kicks it into the air.
     * [onBareDesktop] says whether nothing (an icon, a window, a menu) is over the touch.
     * True when the touch was the toy's.
     */
    fun handleTouch(event: MotionEvent, onBareDesktop: () -> Boolean): Boolean {
        val carsView = view ?: return false
        val own = IntArray(2)
        carsView.getLocationOnScreen(own)
        val x = event.rawX - own[0]
        val y = event.rawY - own[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hold = Hold.NONE
                dragging = false
                if (!onBareDesktop() || x < 0f || x > carsView.width) return false
                val (top, bottom) = carsView.roadBand()
                hold = when {
                    y >= top - 8 * density && y <= bottom -> Hold.ROAD
                    carsView.pokeAt(x, y) -> Hold.CAR
                    else -> Hold.NONE
                }
                downY = event.rawY
                downLift = lift
                return hold != Hold.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (hold != Hold.ROAD) return hold != Hold.NONE
                if (!dragging && kotlin.math.abs(event.rawY - downY) > ViewConfiguration.get(activity).scaledTouchSlop) {
                    dragging = true
                }
                if (dragging) {
                    lift = downLift - (event.rawY - downY)
                    place()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = hold
                hold = Hold.NONE
                if (was == Hold.ROAD) {
                    // A tap on the road: a car there still gets kicked
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) carsView.pokeAt(x, y)
                    if (dragging) prefs.edit { putFloat(KEY_LIFT_DP, lift / density) }
                }
                dragging = false
                return was != Hold.NONE
            }
        }
        return hold != Hold.NONE
    }

    fun cleanup() {
        released = true
        container.viewTreeObserver.removeOnGlobalLayoutListener(relayout)
        view?.let {
            // Off the screen first, so its surface goes while its GL thread is still there to let go of it
            container.removeView(it)
            it.release()
        }
        view = null
        sounds?.release()
        sounds = null
        container.visibility = View.GONE
        removeTrayIcon()
    }

    /** The tray icon's picture; it arrives from the render thread once GL is up. */
    private var trayIcon: ImageView? = null

    private fun addTrayIcon() {
        val systemTray = activity.findViewById<LinearLayout>(R.id.system_tray) ?: return
        if (systemTray.findViewWithTag<View>(TRAY_ICON_TAG) != null) return
        val density = activity.resources.displayMetrics.density
        val size = (19 * density).toInt()

        val iconContainer = LinearLayout(activity).apply {
            tag = TRAY_ICON_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
        val iconView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        trayIcon = iconView
        iconContainer.addView(iconView)

        iconContainer.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.setTag(R.id.system_tray, Pair(event.rawX, event.rawY))
            }
            false
        }
        // A tap builds a ramp; the long press has the rest
        iconContainer.setOnClickListener { view?.buildRamp() }
        iconContainer.setOnLongClickListener { v ->
            val coords = v.getTag(R.id.system_tray) as? Pair<*, *>
            showTrayContextMenu((coords?.first as? Float) ?: 0f, (coords?.second as? Float) ?: 0f)
            true
        }
        systemTray.addView(iconContainer, 0)
    }

    private fun removeTrayIcon() {
        val systemTray = activity.findViewById<LinearLayout>(R.id.system_tray) ?: return
        systemTray.findViewWithTag<View>(TRAY_ICON_TAG)?.let { systemTray.removeView(it) }
        trayIcon = null
    }

    private fun showTrayContextMenu(x: Float, y: Float) {
        val contextMenu = activity.findViewById<ContextMenuView>(R.id.context_menu) ?: return
        val soundsOn = sounds?.enabled == true
        val menuItems = listOf(
            ContextMenuItem(title = "Build a Ramp", action = { view?.buildRamp() }),
            ContextMenuItem(title = "Add a Checkpoint", action = { view?.buildGate() }),
            ContextMenuItem(title = "Demolition Derby", action = { view?.demolitionDerby() }),
            ContextMenuItem(
                title = "Sounds",
                hasCheckbox = true,
                isChecked = soundsOn,
                action = {
                    sounds?.enabled = !soundsOn
                    prefs.edit { putBoolean(KEY_SOUNDS, !soundsOn) }
                }
            ),
            ContextMenuItem(title = "Settings", action = { onShowSettings() }),
            ContextMenuItem(title = "Exit Midtown Madness", action = { onExit() })
        )
        contextMenu.showMenu(menuItems, x, y)
    }

    companion object {
        private const val TAG = "TaskbarCarsManager"
        private const val TRAY_ICON_TAG = "mm2_cars_tray"
        private const val KEY_SOUNDS = "mm2_cars_sounds"
        private const val KEY_LIFT_DP = "mm2_cars_lift"
    }
}
