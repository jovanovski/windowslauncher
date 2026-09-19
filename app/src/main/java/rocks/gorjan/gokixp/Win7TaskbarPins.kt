package rocks.gorjan.gokixp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Programs pinned to the Windows 7 superbar.
 *
 * A pin is what the start menu calls a program - an Android package, or one of the launcher's
 * own `system.*` programs - kept in the user's order under [KEY]. Pins sit first on the bar.
 * While a program's window is open, its button takes the pin's place instead of being added
 * after it, the way Windows 7 turns a pinned icon into the running program; closing the
 * window gives the pin back.
 *
 * Only the Windows 7 taskbar has one of these. The list is kept when the user switches to
 * another theme, and is there again when they come back.
 */
class Win7TaskbarPins(private val activity: MainActivity) {

    private val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private var strip: LinearLayout? = null

    /** Idle pin buttons by program, in the order they were laid out. */
    private val pinButtons = LinkedHashMap<String, View>()

    fun pins(): List<String> =
        (prefs.getString(KEY, null) ?: "").split(",").filter { it.isNotEmpty() }

    fun isPinned(program: String): Boolean = program in pins()

    fun pin(program: String) {
        if (!isPinned(program)) save(pins() + program)
    }

    fun unpin(program: String) = save(pins() - program)

    /** Windows 7 came with Internet Explorer and Media Player pinned; so does a first visit. */
    fun seedDefaultsOnce() {
        if (!prefs.contains(KEY)) prefs.edit { putString(KEY, DEFAULT_PINS.joinToString(",")) }
    }

    /** Lays the pins out at the start of the Windows 7 taskbar's button strip. */
    fun attach(strip: LinearLayout) {
        this.strip = strip
        rebuild()
    }

    /**
     * Called as a window registers its taskbar button. If the window is a pinned program, its
     * button goes where the pin is and the pin steps aside; returns whether it did.
     */
    fun adoptWindowButton(windowIdentifier: String?, button: View): Boolean {
        val strip = strip ?: return false
        val pinButton = windowIdentifier?.let { pinButtons[it] } ?: return false
        if (pinButton.parent != strip) return false
        strip.addView(button, strip.indexOfChild(pinButton))
        pinButton.visibility = View.GONE
        return true
    }

    /** Called once a window's taskbar button is gone; a pinned program gets its pin back. */
    fun windowButtonRemoved(windowIdentifier: String?) {
        windowIdentifier?.let { pinButtons[it] }?.visibility = View.VISIBLE
    }

    private fun save(list: List<String>) {
        prefs.edit { putString(KEY, list.joinToString(",")) }
        rebuild()
    }

    /**
     * Puts the strip in pin order: each pin, or the open window standing in for it, then the
     * buttons of every other open window in the order they were already in.
     */
    private fun rebuild() {
        val strip = strip ?: return
        pinButtons.values.forEach { strip.removeView(it) }
        pinButtons.clear()

        val windows = activity.floatingWindowManager.getAllActiveWindows()
        var index = 0
        for (program in pins()) {
            val button = createPinButton(program) ?: continue
            pinButtons[program] = button
            strip.addView(button, index++)
            val windowButton = windows.firstOrNull { it.windowIdentifier == program }?.getTaskbarButtonView()
            if (windowButton != null && windowButton.parent == strip) {
                strip.removeView(windowButton)
                strip.addView(windowButton, index - 1)
                button.visibility = View.GONE
                index++
            }
        }
    }

    private fun createPinButton(program: String): View? {
        val strip = strip ?: return null
        val icon = activity.getAppIcon(program) ?: run {
            Log.w(TAG, "Leaving out pin with no icon (uninstalled?): $program")
            return null
        }
        val button = LayoutInflater.from(activity).inflate(R.layout.taskbar_button_win7, strip, false)
        button.setBackgroundResource(R.drawable.taskbar_pin_win7_bg)
        button.findViewById<ImageView>(R.id.taskbar_button_icon).setImageDrawable(icon)
        button.contentDescription = program
        attachGestures(button, program)
        return button
    }

    /**
     * Tap launches. Press and hold offers "Unpin from taskbar"; keep holding and slide to
     * drag the pin to a new place on the bar.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(button: View, program: String) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f
        var held = false
        var dragging = false

        val showMenu = Runnable {
            held = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            button.parent?.requestDisallowInterceptTouchEvent(true)
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            activity.showWin7PinContextMenu(program, location[0].toFloat(), location[1].toFloat())
        }

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    held = false
                    dragging = false
                    view.isPressed = true
                    handler.postDelayed(showMenu, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!held && abs(dx) > slop) {
                        // A swipe before the hold: let the strip scroll instead
                        handler.removeCallbacks(showMenu)
                        view.isPressed = false
                        return@setOnTouchListener false
                    }
                    if (held && !dragging && abs(dx) > slop) {
                        dragging = true
                        activity.hideContextMenu()
                    }
                    if (dragging) {
                        view.translationX = dx
                        shiftWhileDragging(view, program)?.let { moved -> downX += moved }
                        view.translationX = event.rawX - downX
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(showMenu)
                    view.isPressed = false
                    if (dragging) {
                        view.translationX = 0f
                        saveStripOrder()
                    } else if (!held && event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                        activity.launchPinnedProgram(program)
                    }
                }
            }
            true
        }
    }

    /**
     * Swaps a dragged pin past its neighbour once it has been dragged over the neighbour's
     * middle. Returns how far the pin's resting place moved, or null if it didn't.
     */
    private fun shiftWhileDragging(view: View, program: String): Float? {
        val strip = strip ?: return null
        val index = strip.indexOfChild(view)
        val step = view.width.toFloat()
        val neighbourIndex = when {
            view.translationX > step / 2 -> index + 1
            view.translationX < -step / 2 -> index - 1
            else -> return null
        }
        val neighbour = strip.getChildAt(neighbourIndex) ?: return null
        // Pins only trade places with pins
        if (neighbour !in pinButtons.values) return null
        // Move the neighbour, not the dragged pin: detaching the view under the finger would
        // end the gesture, and the pin would never hear the finger lift.
        strip.removeView(neighbour)
        strip.addView(neighbour, index)
        Log.d(TAG, "Dragged $program to position $neighbourIndex")
        return if (neighbourIndex > index) step else -step
    }

    private fun saveStripOrder() {
        val strip = strip ?: return
        val order = (0 until strip.childCount).mapNotNull { i ->
            val child = strip.getChildAt(i)
            pinButtons.entries.firstOrNull { it.value == child }?.key
        }
        // Pins the strip couldn't show (no icon) keep their place at the end
        prefs.edit { putString(KEY, (order + (pins() - order.toSet())).joinToString(",")) }
    }

    companion object {
        private const val TAG = "Win7TaskbarPins"

        /** Ordered, comma-separated, in the same form as the start menu's pins. */
        const val KEY = "taskbar_pinned_apps"

        private val DEFAULT_PINS = listOf("system.internet_explorer", "system.wmp")
    }
}
