package rocks.gorjan.gokixp.wp81

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The Windows Phone page transition: the turnstile.
 *
 * A page does not fade or slide in as a slab - it swings in about its own left edge, like
 * a door being pushed open towards the viewer, and leaves the same way. It is the single
 * most recognisable thing about moving around the OS, and the reason navigating it felt
 * like turning pages rather than switching screens.
 *
 * One of these per page. It keeps count of the transitions it has played so that a page
 * put back up while its own exit is still running cannot be hidden by that exit finishing:
 * a cancelled ViewPropertyAnimator still runs its end action, so cancelling is not enough
 * on its own.
 */
class MetroPageTransition(private val page: View) {

    private var generation = 0
    private var leaving = false

    /**
     * Whether this page is the one the user is on.
     *
     * Not the same as being visible: a page is still on screen for the length of its own
     * exit, and everything that asks "where am I" would answer with the page that is in
     * the act of leaving - which is how the key strip ended up stuck on a folder's
     * commands after the folder had gone.
     */
    val isOnScreen: Boolean
        get() = page.visibility == View.VISIBLE && !leaving

    /** Swings the page in and leaves it visible. */
    fun playIn() {
        generation++
        leaving = false
        page.animate().cancel()
        page.cameraDistance = CAMERA_DISTANCE * page.resources.displayMetrics.density
        page.pivotX = 0f
        page.pivotY = page.height / 2f
        page.rotationY = IN_DEGREES
        page.alpha = 0f
        page.visibility = View.VISIBLE
        page.animate()
            .rotationY(0f)
            .alpha(1f)
            .setDuration(IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Swings the page out, hides it, and then runs [after]. */
    fun playOut(after: () -> Unit = {}) {
        if (page.visibility != View.VISIBLE) {
            after()
            return
        }
        generation++
        leaving = true
        val turn = generation
        page.animate().cancel()
        page.cameraDistance = CAMERA_DISTANCE * page.resources.displayMetrics.density
        page.pivotX = 0f
        page.pivotY = page.height / 2f
        page.animate()
            .rotationY(OUT_DEGREES)
            .alpha(0f)
            .setDuration(OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Another transition started while this one was leaving: the page on
                // screen now is not the one this animation was hiding.
                if (turn != generation) return@withEndAction
                leaving = false
                page.visibility = View.GONE
                page.rotationY = 0f
                page.alpha = 1f
                after()
            }
            .start()
    }

    private companion object {
        /**
         * Without a camera distance proportional to density the perspective is so extreme
         * the page shears rather than turns.
         */
        const val CAMERA_DISTANCE = 8000f

        // Arriving from the right, leaving to the left, both about the left edge. Short of
        // a right angle on purpose: at 90 the page vanishes edge-on for a frame and the
        // turn reads as a flicker.
        const val IN_DEGREES = 70f
        const val OUT_DEGREES = -70f

        // Out is quicker than in. Leaving should feel like getting out of the way; arriving
        // is the part worth watching.
        const val IN_MS = 260L
        const val OUT_MS = 180L
    }
}
