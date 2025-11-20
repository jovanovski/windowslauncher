package rocks.gorjan.gokixp.apps.lights

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RelativeLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.random.Random

class SnowfallManager(
    private val context: Context,
    private val container: RelativeLayout
) {
    private val snowflakes = mutableListOf<Snowflake>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val updateInterval = 50L // Update every 50ms

    // Snowflake generation settings
    private val snowflakeGenerationInterval = 300L // Generate new snowflake every 300ms
    private val maxSnowflakes = 100 // Maximum number of snowflakes on screen (50% increase)

    private val prefs: SharedPreferences = context.getSharedPreferences("SnowfallState", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SNOWFLAKE_STATE = "snowflake_state"
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateSnowflakes()
                handler.postDelayed(this, updateInterval)
            }
        }
    }

    private val generateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                generateSnowflake()
                handler.postDelayed(this, snowflakeGenerationInterval)
            }
        }
    }

    fun start() {
        if (!isRunning) {
            isRunning = true

            // Restore previous state if exists
            restoreState()

            handler.post(updateRunnable)
            handler.post(generateRunnable)
        }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(generateRunnable)
        cleanup()
    }

    fun pause() {
        // Save state without cleaning up
        if (isRunning) {
            isRunning = false
            handler.removeCallbacks(updateRunnable)
            handler.removeCallbacks(generateRunnable)
            saveState()
        }
    }

    fun resume() {
        if (!isRunning) {
            isRunning = true
            handler.post(updateRunnable)
            handler.post(generateRunnable)
        }
    }

    private fun generateSnowflake() {
        if (snowflakes.size >= maxSnowflakes) return

        val screenWidth = context.resources.displayMetrics.widthPixels

        // Random size between 1dp and 4dp
        val sizeDp = Random.nextInt(1, 5)
        val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt()

        // Random starting X position
        val startX = Random.nextInt(0, screenWidth)

        // Create snowflake view (white circle)
        val snowflakeView = View(context).apply {
            layoutParams = RelativeLayout.LayoutParams(sizePx, sizePx)
            background = createCircleDrawable(sizePx)
            x = startX.toFloat()
            y = 0f
        }

        container.addView(snowflakeView)

        val snowflake = Snowflake(
            view = snowflakeView,
            x = startX.toFloat(),
            y = 0f,
            size = sizePx,
            fallSpeed = Random.nextInt(4, 10), // Random fall speed in dp (2x faster)
            horizontalDrift = Random.nextInt(-5, 6) // Random drift between -5dp and +5dp
        )

        snowflakes.add(snowflake)
    }

    private fun createCircleDrawable(size: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(size, size)
        }
    }

    private fun updateSnowflakes() {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels

        val iterator = snowflakes.iterator()
        while (iterator.hasNext()) {
            val snowflake = iterator.next()

            // Update position
            snowflake.y += snowflake.fallSpeed * density

            // Add horizontal drift
            val driftPx = (snowflake.horizontalDrift * density) * 0.1f // Subtle drift
            snowflake.x += driftPx

            // Keep within screen bounds
            if (snowflake.x < 0) snowflake.x = 0f
            if (snowflake.x > screenWidth - snowflake.size) snowflake.x = (screenWidth - snowflake.size).toFloat()

            // Update view position
            snowflake.view.x = snowflake.x
            snowflake.view.y = snowflake.y

            // Remove if off screen
            if (snowflake.y > screenHeight) {
                container.removeView(snowflake.view)
                iterator.remove()
            }
        }
    }

    private fun cleanup() {
        // Remove all snowflake views
        snowflakes.forEach { snowflake ->
            container.removeView(snowflake.view)
        }
        snowflakes.clear()

        // Clear saved state
        prefs.edit().remove(KEY_SNOWFLAKE_STATE).apply()
    }

    private fun saveState() {
        val snowflakeStates = snowflakes.map { snowflake ->
            SnowflakeState(
                x = snowflake.x,
                y = snowflake.y,
                size = snowflake.size,
                fallSpeed = snowflake.fallSpeed,
                horizontalDrift = snowflake.horizontalDrift
            )
        }

        val json = gson.toJson(snowflakeStates)
        prefs.edit().putString(KEY_SNOWFLAKE_STATE, json).apply()
    }

    private fun restoreState() {
        val json = prefs.getString(KEY_SNOWFLAKE_STATE, null) ?: return

        try {
            val type = object : TypeToken<List<SnowflakeState>>() {}.type
            val snowflakeStates: List<SnowflakeState> = gson.fromJson(json, type)

            snowflakeStates.forEach { state ->
                // Recreate snowflake view
                val snowflakeView = View(context).apply {
                    layoutParams = RelativeLayout.LayoutParams(state.size, state.size)
                    background = createCircleDrawable(state.size)
                    x = state.x
                    y = state.y
                }

                container.addView(snowflakeView)

                val snowflake = Snowflake(
                    view = snowflakeView,
                    x = state.x,
                    y = state.y,
                    size = state.size,
                    fallSpeed = state.fallSpeed,
                    horizontalDrift = state.horizontalDrift
                )

                snowflakes.add(snowflake)
            }
        } catch (e: Exception) {
            // If restoration fails, just start fresh
            e.printStackTrace()
        }
    }

    private data class SnowflakeState(
        val x: Float,
        val y: Float,
        val size: Int,
        val fallSpeed: Int,
        val horizontalDrift: Int
    )

    private data class Snowflake(
        val view: View,
        var x: Float,
        var y: Float,
        val size: Int,
        val fallSpeed: Int,
        val horizontalDrift: Int
    )
}
