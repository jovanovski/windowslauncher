package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slow movement for the Start background, so a still photo behind the tiles reads as
 * something the tiles are windows onto rather than as a picture stuck behind them.
 *
 * Two sources, added together and clamped:
 *
 *  - **How the phone is being held.** Not the absolute tilt: that would pin the photo to
 *    one extreme for as long as you held the phone the way people normally hold phones.
 *    What moves it is the *departure* from a slowly-adapting baseline, so a deliberate
 *    tilt swings the photo and holding the new pose lets it settle back.
 *  - **A wander that runs regardless**, on two periods close enough to look like one
 *    motion and far enough apart never to repeat, so a phone lying on a desk is not a
 *    still image.
 *
 * Reports its position as -1..1 on each axis and leaves the question of how many pixels
 * that is to the caller, which is the only side that knows how much slack the crop left.
 */
class WallpaperDrift(
    context: Context,
    private val onOffset: (Float, Float) -> Unit
) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Gravity where it exists - it is the accelerometer with the movement already filtered
    // out, which is exactly the part wanted here - and the raw accelerometer where it does
    // not. Either way a device with neither simply drifts on the wander alone.
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var rawX = 0f
    private var rawY = 0f
    private var baselineX = 0f
    private var baselineY = 0f
    private var seenSensor = false

    private var currentX = 0f
    private var currentY = 0f

    private var running = false
    private var startedAt = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        currentX = 0f
        currentY = 0f
        seenSensor = false
        // Put the photo back where it started, or it stays wherever it was when the Start
        // screen went away and jumps on the way back.
        onOffset(0f, 0f)
    }

    private fun step() {
        val seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f

        // The baseline follows the phone's resting attitude, slowly enough that a tilt
        // registers as movement first and becomes the new normal only if it is held.
        baselineX += (rawX - baselineX) * BASELINE_EASING
        baselineY += (rawY - baselineY) * BASELINE_EASING
        val tiltX = if (seenSensor) ((baselineX - rawX) / TILT_SPAN).coerceIn(-1f, 1f) else 0f
        val tiltY = if (seenSensor) ((rawY - baselineY) / TILT_SPAN).coerceIn(-1f, 1f) else 0f

        val wanderX = sin(seconds * TWO_PI / WANDER_X_SECONDS)
        val wanderY = cos(seconds * TWO_PI / WANDER_Y_SECONDS)

        val targetX = (tiltX * TILT_WEIGHT + wanderX * WANDER_WEIGHT).coerceIn(-1f, 1f)
        val targetY = (tiltY * TILT_WEIGHT + wanderY * WANDER_WEIGHT).coerceIn(-1f, 1f)

        // Eased rather than followed: the sensor is noisy at this scale, and a photo that
        // tracks a hand exactly reads as a wobble instead of as weight.
        currentX += (targetX - currentX) * EASING
        currentY += (targetY - currentY) * EASING
        onOffset(currentX, currentY)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 2) return
        if (!seenSensor) {
            // Start from wherever the phone already is, so enabling the effect does not
            // begin with the photo sliding in from an edge.
            baselineX = event.values[0]
            baselineY = event.values[1]
            seenSensor = true
        }
        rawX = event.values[0]
        rawY = event.values[1]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** How fast the resting attitude is re-learned. One frame's worth of ~20 seconds. */
        const val BASELINE_EASING = 0.0008f

        /** Departure from the baseline, in m/s^2, that reaches the end of the travel. */
        const val TILT_SPAN = 3.5f

        // The two sources share the travel; together they can ask for more than there is,
        // which is what the clamp above is for.
        const val TILT_WEIGHT = 0.65f
        const val WANDER_WEIGHT = 0.5f

        // Prime-ish and unequal, so the path does not close on itself where the eye can
        // learn it.
        const val WANDER_X_SECONDS = 37f
        const val WANDER_Y_SECONDS = 53f

        /** Per-frame approach to the target: about a second to settle. */
        const val EASING = 0.05f
    }
}
