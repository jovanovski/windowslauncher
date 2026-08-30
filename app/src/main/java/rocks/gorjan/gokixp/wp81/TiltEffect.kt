package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The Windows Phone press-and-tilt effect.
 *
 * Pressing anything tappable in WP8.1 tilts it in 3D *towards* the touch point, as if
 * the surface were pushed in where the finger landed; pressing dead centre pushes the
 * whole thing straight back. Releasing springs it flat. It is the single most
 * recognisable interaction in the OS, and it applies to tiles and list rows alike.
 *
 * Attach with [apply]. The caller keeps its own click handling - this only animates,
 * and never consumes the event.
 */
object TiltEffect {

    /**
     * Implemented by views that are not always at scale 1.
     *
     * A selected tile sits slightly shrunk. Without this the release animation springs it
     * back to full size the moment the finger lifts, fighting the selection scale - which
     * showed up as the tile appearing to hesitate and then resize a beat later.
     */
    interface Target {
        fun restingScale(): Float
    }

    /** Maximum edge rotation, in degrees. WP tilts subtly; past ~12 it reads as a flip. */
    private const val MAX_TILT = 10f

    /** How far a centre press sinks. */
    private const val PRESS_SCALE = 0.96f

    private const val TILT_MS = 90L
    private const val RELEASE_MS = 180L

    fun apply(view: View) = apply(view) { _, _ -> false }

    /**
     * Applies the effect alongside touch handling of the caller's own.
     *
     * A view has one touch listener, so anything that wants both the tilt *and* a gesture
     * detector has to hand the detector in here - setting one afterwards silently replaces
     * the other, and whichever was applied second is the only one that ever runs.
     *
     * [also] returns whether it consumed the event, which becomes the listener's own
     * answer. The tilt itself never consumes.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun apply(view: View, also: (View, MotionEvent) -> Boolean) {
        // Without a camera distance proportional to density, the perspective is so
        // extreme the view visibly skews rather than tilts.
        view.cameraDistance = 8000f * view.resources.displayMetrics.density

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> tilt(v, event.x, event.y)
                MotionEvent.ACTION_MOVE ->
                    if (!within(v, event.x, event.y)) release(v) else tilt(v, event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(v)
            }
            // Never consumed by the tilt: the view's own click/long-press handling still
            // runs. Only what the caller handed in can claim the gesture.
            also(v, event)
        }
    }

    private fun within(v: View, x: Float, y: Float): Boolean =
        x >= 0 && y >= 0 && x <= v.width && y <= v.height

    private fun tilt(v: View, x: Float, y: Float) {
        if (v.width == 0 || v.height == 0) return

        // -1..1 from the centre of the view.
        val dx = ((x / v.width) * 2f - 1f).coerceIn(-1f, 1f)
        val dy = ((y / v.height) * 2f - 1f).coerceIn(-1f, 1f)

        // Pressing the right edge should push that edge away, which is a negative
        // rotation about Y; pressing the bottom pushes the bottom away, positive about X.
        v.animate().cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        v.animate()
            .rotationY(-dx * MAX_TILT)
            .rotationX(dy * MAX_TILT)
            // A centre press has no edge to tilt, so it sinks instead. Fade the sink out
            // towards the edges, where the rotation is already carrying the feedback.
            .scaleX(scaleFor(v, dx, dy))
            .scaleY(scaleFor(v, dx, dy))
            .setDuration(TILT_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun scaleFor(view: View, dx: Float, dy: Float): Float {
        val edgeness = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        val resting = restingScaleOf(view)
        return resting * (PRESS_SCALE + (1f - PRESS_SCALE) * edgeness)
    }

    private fun restingScaleOf(view: View): Float =
        (view as? Target)?.restingScale() ?: 1f

    private fun release(v: View) {
        val resting = restingScaleOf(v)
        v.animate().cancel()
        v.animate()
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(resting)
            .scaleY(resting)
            .setDuration(RELEASE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Clears any in-flight tilt, e.g. before a view is recycled or animated elsewhere. */
    fun reset(v: View) {
        val resting = restingScaleOf(v)
        v.animate().cancel()
        v.rotationX = 0f
        v.rotationY = 0f
        v.scaleX = resting
        v.scaleY = resting
    }
}
