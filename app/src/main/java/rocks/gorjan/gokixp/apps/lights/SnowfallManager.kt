package rocks.gorjan.gokixp.apps.lights

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import kotlin.random.Random

class SnowfallManager(
    private val context: Context,
    private val container: RelativeLayout
) {
    private val snowflakes = mutableListOf<Snowflake>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val updateInterval = 10L // Update every 50ms

    // Snowflake generation settings
    private val snowflakeGenerationInterval = 50L // Generate new snowflake every 300ms
    private val maxSnowflakes = 100 // Maximum number of active (falling) snowflakes
    private val maxStoppedSnowflakes = 150 // Maximum number of stopped snowflakes on ground
    private val stoppedSnowflakes = mutableListOf<Snowflake>() // Track stopped snowflakes in order (FIFO)

    private val prefs: SharedPreferences = context.getSharedPreferences("SnowfallState", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Taskbar position for stopping snowflakes
    private var taskbarTop: Float = 0f

    // Snowplow
    private var snowplow: ImageView? = null
    private var snowplowRunning = false
    private val snowplowInterval = 10000L // 10 seconds
    private val snowplowDuration = 8000L // 5 seconds
    private var snowplowGoingLeft = true // Alternates each pass

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

    private val snowplowRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                startSnowplow()
                handler.postDelayed(this, snowplowInterval)
            }
        }
    }

    private val snowplowCollisionRunnable = object : Runnable {
        override fun run() {
            if (snowplowRunning) {
                checkSnowplowCollisions()
                handler.postDelayed(this, 16L) // Check at ~60fps
            }
        }
    }

    fun start() {
        if (!isRunning) {
            isRunning = true

            // Calculate taskbar position
            calculateTaskbarPosition()

            // Setup snowplow
            setupSnowplow()

            // Restore previous state if exists
            restoreState()

            handler.post(updateRunnable)
            handler.post(generateRunnable)
            handler.postDelayed(snowplowRunnable, snowplowInterval) // First snowplow after 10s + 2s delay
        }
    }

    private fun setupSnowplow() {
        val activity = context as? android.app.Activity ?: return
        snowplow = activity.findViewById(rocks.gorjan.gokixp.R.id.snowplow)
        snowplow?.visibility = View.GONE
    }

    private fun startSnowplow() {
        snowplow?.let { plow ->
            // Post to ensure all views are laid out and have valid positions
            plow.post {
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels

                // Get actual layout dimensions (50dp x 26dp from XML converted to pixels)
                val density = context.resources.displayMetrics.density
                val plowWidth = (50 * density).toInt() // 50dp from XML
                val plowHeight = (26 * density).toInt() // 26dp from XML


                // Determine direction and flip accordingly
                val startX: Float
                val endX: Float

                if (snowplowGoingLeft) {
                    // Going left: start from right, flip normal (scaleX = 1)
                    startX = screenWidth.toFloat() + 1f
                    endX = -plowWidth.toFloat() - 1f
                    plow.scaleX = 1f
                } else {
                    // Going right: start from left, flip horizontally (scaleX = -1)
                    startX = -plowWidth.toFloat() - 1f
                    endX = screenWidth.toFloat() + 1f
                    plow.scaleX = -1f
                }

                // Toggle direction for next pass
                snowplowGoingLeft = !snowplowGoingLeft

                // Get taskbar position directly - must be done after layout
                val activity = context as? android.app.Activity
                val taskbar = activity?.findViewById<View>(rocks.gorjan.gokixp.R.id.taskbar_container)
                var calculatedTaskbarTop = if (taskbar != null) {
                    val location = IntArray(2)
                    taskbar.getLocationOnScreen(location)
                    location[1].toFloat()
                } else {
                    // Fallback: estimate based on typical layout
                    screenHeight - (70 * density)
                }

                // Adjust for Vista theme - taskbar is visually smaller than actual size
                val themeManager = ThemeManager(context)
                if (themeManager.getSelectedTheme() is AppTheme.WindowsVista) {
                    calculatedTaskbarTop += (5 * density)
                }

                // Update cached value
                if (calculatedTaskbarTop > 0) {
                    taskbarTop = calculatedTaskbarTop
                }

                // Position at taskbar height
                val yPosition = calculatedTaskbarTop - plowHeight


                // Set initial position
                plow.x = startX
                plow.y = yPosition
                plow.visibility = View.VISIBLE
                snowplowRunning = true

                // Start collision detection
                handler.post(snowplowCollisionRunnable)

                // Animate to left edge
                plow.animate()
                    .x(endX)
                    .setDuration(snowplowDuration)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction {
                        plow.visibility = View.GONE
                        snowplowRunning = false
                        handler.removeCallbacks(snowplowCollisionRunnable)
                    }
                    .start()
            }
        }
    }

    private fun checkSnowplowCollisions() {
        snowplow?.let { plow ->
            val plowLeft = plow.x
            val plowRight = plow.x + plow.width
            val plowTop = plow.y
            val plowBottom = plow.y + plow.height

            val iterator = snowflakes.iterator()
            while (iterator.hasNext()) {
                val snowflake = iterator.next()

                // Check if snowflake intersects with snowplow
                val flakeLeft = snowflake.x
                val flakeRight = snowflake.x + snowflake.size
                val flakeTop = snowflake.y
                val flakeBottom = snowflake.y + snowflake.size

                // AABB collision detection
                if (flakeRight > plowLeft && flakeLeft < plowRight &&
                    flakeBottom > plowTop && flakeTop < plowBottom) {
                    // Remove from stopped list if it was stopped
                    if (snowflake.isStopped) {
                        stoppedSnowflakes.remove(snowflake)
                    }
                    // Remove snowflake
                    container.removeView(snowflake.view)
                    iterator.remove()
                }
            }
        }
    }

    private fun calculateTaskbarPosition() {
        // Post to ensure layout is complete
        container.post {
            val screenHeight = context.resources.displayMetrics.heightPixels
            val density = context.resources.displayMetrics.density
            // Taskbar is typically at the bottom with 70dp margin from activity_main.xml
            // We need to find actual taskbar position
            val activity = context as? android.app.Activity
            val taskbar = activity?.findViewById<View>(rocks.gorjan.gokixp.R.id.taskbar_container)

            if (taskbar != null) {
                val location = IntArray(2)
                taskbar.getLocationOnScreen(location)
                taskbarTop = location[1].toFloat()
            } else {
                // Fallback: estimate based on typical layout
                taskbarTop = screenHeight - (70 * density)
            }

            // Adjust for Vista theme - taskbar is visually smaller than actual size
            val themeManager = ThemeManager(context)
            if (themeManager.getSelectedTheme() is AppTheme.WindowsVista) {
                taskbarTop += (5 * density)
            }
        }
    }

    fun stop() {
        isRunning = false
        snowplowRunning = false
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(generateRunnable)
        handler.removeCallbacks(snowplowRunnable)
        handler.removeCallbacks(snowplowCollisionRunnable)
        snowplow?.animate()?.cancel()
        snowplow?.visibility = View.GONE
        cleanup()
    }

    fun pause() {
        // Save state without cleaning up
        if (isRunning) {
            isRunning = false
            snowplowRunning = false
            handler.removeCallbacks(updateRunnable)
            handler.removeCallbacks(generateRunnable)
            handler.removeCallbacks(snowplowRunnable)
            handler.removeCallbacks(snowplowCollisionRunnable)
            snowplow?.animate()?.cancel()
            snowplow?.visibility = View.GONE
            saveState()
        }
    }

    fun resume() {
        if (!isRunning) {
            isRunning = true
            handler.post(updateRunnable)
            handler.post(generateRunnable)
            handler.postDelayed(snowplowRunnable, snowplowInterval)
        }
    }

    private fun generateSnowflake() {
        // Only count active (non-stopped) snowflakes toward the limit
        val activeSnowflakes = snowflakes.count { !it.isStopped }
        if (activeSnowflakes >= maxSnowflakes) return

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
            fallSpeed = Random.nextInt(1, 3), // Random fall speed in dp (2x faster)
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

        // Collect snowflakes to remove after iteration to avoid ConcurrentModificationException
        val toRemove = mutableListOf<Snowflake>()

        val iterator = snowflakes.iterator()
        while (iterator.hasNext()) {
            val snowflake = iterator.next()

            // Skip if already stopped
            if (snowflake.isStopped) {
                continue
            }

            // Update position
            snowflake.y += snowflake.fallSpeed * density

            // Add horizontal drift
            val driftPx = (snowflake.horizontalDrift * density) * 0.1f // Subtle drift
            snowflake.x += driftPx

            // Check if we hit the taskbar
            val bottomY = snowflake.y + snowflake.size
            if (bottomY >= taskbarTop) {
                // Stop at taskbar
                snowflake.y = taskbarTop - snowflake.size
                snowflake.isStopped = true

                // Add to stopped list for FIFO tracking
                stoppedSnowflakes.add(snowflake)

                // Remove oldest stopped snowflake if we exceed the limit
                if (stoppedSnowflakes.size > maxStoppedSnowflakes) {
                    val oldestStopped = stoppedSnowflakes.removeAt(0)
                    container.removeView(oldestStopped.view)
                    toRemove.add(oldestStopped)
                }
            }

            // Update view position
            snowflake.view.x = snowflake.x
            snowflake.view.y = snowflake.y

            // Remove if off screen (left or right only, not bottom since they stack)
            if (snowflake.x < -snowflake.size || snowflake.x > screenWidth) {
                container.removeView(snowflake.view)
                iterator.remove()
            }
        }

        // Remove collected snowflakes after iteration
        snowflakes.removeAll(toRemove)
    }

    private fun cleanup() {
        // Remove all snowflake views
        snowflakes.forEach { snowflake ->
            container.removeView(snowflake.view)
        }
        snowflakes.clear()
        stoppedSnowflakes.clear()

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
                horizontalDrift = snowflake.horizontalDrift,
                isStopped = snowflake.isStopped
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
                    horizontalDrift = state.horizontalDrift,
                    isStopped = state.isStopped
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
        val horizontalDrift: Int,
        val isStopped: Boolean
    )

    private data class Snowflake(
        val view: View,
        var x: Float,
        var y: Float,
        val size: Int,
        val fallSpeed: Int,
        val horizontalDrift: Int,
        var isStopped: Boolean = false
    )
}
