package rocks.gorjan.gokixp.apps.lights

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.edit
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.ContextMenuView
import rocks.gorjan.gokixp.R
import kotlin.random.Random

class ChristmasLightsManager(
    private val context: Context,
    private val container: LinearLayout,
    private val onShowSettings: () -> Unit,
    private val onExitLights: () -> Unit
) {
    private val lights = mutableListOf<LightView>()
    private val handlers = mutableListOf<Handler>()

    companion object {
        private const val TRAY_ICON_TAG = "christmas_lights_tray"
    }

    // Available light colors with their drawable resources
    private val lightColors = listOf(
        LightColor("blue", R.drawable.light_blue_on, R.drawable.light_blue_off),
        LightColor("red", R.drawable.light_red_on, R.drawable.light_red_off),
        LightColor("green", R.drawable.light_green_on, R.drawable.light_green_off),
        LightColor("yellow", R.drawable.light_yellow_on, R.drawable.light_yellow_off),
        LightColor("purple", R.drawable.light_purple_on, R.drawable.light_purple_off),
        LightColor("white", R.drawable.light_white_on, R.drawable.light_white_off),
        LightColor("teal", R.drawable.light_teal_on, R.drawable.light_teal_off)
    )

    fun initialize() {
        container.removeAllViews()

        // Calculate how many lights we need to fill the screen
        val screenWidth = context.resources.displayMetrics.widthPixels
        val sampleLight = ImageView(context)
        sampleLight.setImageResource(lightColors[0].onDrawable)
        sampleLight.measure(0, 0)
        val lightWidth = sampleLight.measuredWidth

        val numberOfLights = if (lightWidth > 0) {
            (screenWidth / lightWidth) + 1
        } else {
            15 // Default fallback
        }

        // Create lights and add them to the container
        for (i in 0 until numberOfLights) {
            val colorIndex = i % lightColors.size
            val lightColor = lightColors[colorIndex]

            val imageView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setImageResource(lightColor.offDrawable)
            }

            container.addView(imageView)

            val lightView = LightView(imageView, lightColor, false)
            lights.add(lightView)

            // Start animation for this light with random delay
            startLightAnimation(lightView)
        }

        // Add tray icon
        addTrayIcon()
    }

    private fun startLightAnimation(lightView: LightView) {
        val handler = Handler(Looper.getMainLooper())
        handlers.add(handler)

        // Random initial delay between 0 and 2000ms
        val initialDelay = Random.nextLong(0, 2001)

        // Runnable that toggles the light state
        val toggleRunnable = object : Runnable {
            override fun run() {
                toggleLight(lightView)
                // Schedule next toggle with random interval between 1-3 seconds
                val nextDelay = Random.nextLong(1000, 3001)
                handler.postDelayed(this, nextDelay)
            }
        }

        // Start the animation with initial random delay
        handler.postDelayed(toggleRunnable, initialDelay)
    }

    private fun toggleLight(lightView: LightView) {
        lightView.isOn = !lightView.isOn
        val drawableRes = if (lightView.isOn) {
            lightView.color.onDrawable
        } else {
            lightView.color.offDrawable
        }
        lightView.imageView.setImageResource(drawableRes)
    }

    fun cleanup() {
        // Stop all handlers to prevent memory leaks
        handlers.forEach { it.removeCallbacksAndMessages(null) }
        handlers.clear()
        lights.clear()
        container.removeAllViews()

        // Remove tray icon
        removeTrayIcon()
    }

    private fun addTrayIcon() {
        val activity = context as? Activity ?: return
        val systemTray = activity.findViewById<LinearLayout>(R.id.system_tray) ?: return

        // Check if icon already exists
        if (systemTray.findViewWithTag<View>(TRAY_ICON_TAG) != null) {
            return
        }

        val density = context.resources.displayMetrics.density

        // Create container LinearLayout
        val iconContainer = LinearLayout(context).apply {
            tag = TRAY_ICON_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        // Create ImageView
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (19 * density).toInt(),
                (19 * density).toInt()
            )
            setImageResource(R.drawable.light_red_on)
        }

        iconContainer.addView(iconView)

        // Set up touch listener for long press coordinates
        iconContainer.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                view.setTag(R.id.system_tray, Pair(event.rawX, event.rawY))
            }
            false
        }

        // Set up long press for context menu
        iconContainer.setOnLongClickListener { view ->
            val coords = view.getTag(R.id.system_tray) as? Pair<*, *>
            val x = (coords?.first as? Float) ?: 0f
            val y = (coords?.second as? Float) ?: 0f
            showTrayContextMenu(x, y)
            true
        }

        // Add at the beginning of the system tray
        systemTray.addView(iconContainer, 0)
    }

    private fun removeTrayIcon() {
        val activity = context as? Activity ?: return
        val systemTray = activity.findViewById<LinearLayout>(R.id.system_tray) ?: return
        val trayIcon = systemTray.findViewWithTag<View>(TRAY_ICON_TAG)
        if (trayIcon != null) {
            systemTray.removeView(trayIcon)
        }
    }

    private fun showTrayContextMenu(x: Float, y: Float) {
        val activity = context as? Activity ?: return
        val contextMenu = activity.findViewById<ContextMenuView>(R.id.context_menu) ?: return

        val menuItems = listOf(
            ContextMenuItem(
                title = "Settings",
                action = { onShowSettings() }
            ),
            ContextMenuItem(
                title = "Exit Lights95",
                action = { onExitLights() }
            )
        )

        contextMenu.showMenu(menuItems, x, y)
    }

    private data class LightColor(
        val name: String,
        val onDrawable: Int,
        val offDrawable: Int
    )

    private data class LightView(
        val imageView: ImageView,
        val color: LightColor,
        var isOn: Boolean
    )
}
