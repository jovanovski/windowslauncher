package rocks.gorjan.gokixp.apps.lights

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import rocks.gorjan.gokixp.R
import kotlin.random.Random

class ChristmasLightsManager(
    private val context: Context,
    private val container: LinearLayout
) {
    private val lights = mutableListOf<LightView>()
    private val handlers = mutableListOf<Handler>()

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
