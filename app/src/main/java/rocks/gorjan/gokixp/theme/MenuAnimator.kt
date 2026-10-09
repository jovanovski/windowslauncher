package rocks.gorjan.gokixp.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import rocks.gorjan.gokixp.R

/**
 * How a menu opens and closes, the way the shell it imitates did it.
 *
 * - Windows 9x ([AppTheme.WindowsClassic]) used the "scroll" effect: a menu slid out of the
 *   edge it hangs from - down from a menu bar, up out of the taskbar for Start, sideways for
 *   a submenu - and vanished the instant it was put away.
 * - XP, Vista and 7 faded menus in, and faded them back out.
 *
 * Works on the menu's own view rather than a window animation, so the same code serves the
 * Start menu and desktop context menu (views in the launcher's layout) and the menu-bar
 * drop-downs (the content of a PopupWindow).
 */
object MenuAnimator {

    /** Which way a 9x menu unrolls: the edge it grows away from. */
    enum class Direction { DOWN, UP, RIGHT }

    private const val SLIDE_MS = 120L
    private const val START_SLIDE_MS = 200L
    private const val FADE_IN_MS = 150L
    private const val FADE_OUT_MS = 100L

    /** Tag marking a view whose fade-out is under way. */
    private val CLOSING = Any()

    /** Whether [theme] puts a menu away with an animation; 9x simply drops it. */
    fun animatesClose(theme: AppTheme): Boolean = theme !is AppTheme.WindowsClassic

    /**
     * Plays [view]'s opening animation. Call after the view is made visible and positioned;
     * the view's own translation is kept as the resting place the slide ends on.
     */
    fun show(view: View, theme: AppTheme, direction: Direction = Direction.DOWN, isStartMenu: Boolean = false) {
        cancel(view)
        if (theme is AppTheme.WindowsClassic) {
            slide(view, direction, if (isStartMenu) START_SLIDE_MS else SLIDE_MS)
        } else {
            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(FADE_IN_MS)
                .setInterpolator(LinearInterpolator())
                .start()
        }
    }

    /**
     * Plays [view]'s closing animation, then runs [onHidden] (which should hide it). Under
     * 9x there is no animation and [onHidden] runs straight away.
     */
    fun hide(view: View, theme: AppTheme, onHidden: () -> Unit) {
        // Already fading out: let that fade finish rather than snapping back and starting over.
        if (view.getTag(R.id.menu_animator_tag) === CLOSING) return
        cancel(view)
        if (!animatesClose(theme) || view.visibility != View.VISIBLE) {
            onHidden()
            return
        }
        view.setTag(R.id.menu_animator_tag, CLOSING)
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                view.setTag(R.id.menu_animator_tag, null)
                onHidden()
                view.alpha = 1f
            }
            .start()
    }

    /**
     * Stops whatever animation [view] is in the middle of and puts it back at rest: fully
     * opaque, unclipped, at its own position. A pending close's [hide] callback is dropped.
     */
    fun cancel(view: View) {
        view.animate().cancel()
        (view.getTag(R.id.menu_animator_tag) as? ValueAnimator)?.cancel()
        view.setTag(R.id.menu_animator_tag, null)
        view.alpha = 1f
    }

    /**
     * The 9x scroll: the menu moves out from behind the edge it hangs from, clipped so it
     * never shows past that edge, the way the menu seemed to come out of the bar or taskbar.
     */
    private fun slide(view: View, direction: Direction, duration: Long) {
        val restX = view.translationX
        val restY = view.translationY
        // A shadow is cast from the outline, which the clip doesn't cut, so it would show the
        // whole menu's shape before the menu is out. 9x menus had none anyway; put it back after.
        val outline = view.outlineProvider
        view.outlineProvider = null
        val clip = Rect()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val w = view.width
                val h = view.height
                when (direction) {
                    Direction.DOWN -> {
                        // Shifted up by the part not yet shown; show only its bottom slice.
                        val hidden = (h * (1f - f)).toInt()
                        view.translationY = restY - hidden
                        clip.set(0, hidden, w, h)
                    }
                    Direction.UP -> {
                        // Shifted down; show only its top slice, which sits on the taskbar.
                        val hidden = (h * (1f - f)).toInt()
                        view.translationY = restY + hidden
                        clip.set(0, 0, w, h - hidden)
                    }
                    Direction.RIGHT -> {
                        val hidden = (w * (1f - f)).toInt()
                        view.translationX = restX - hidden
                        clip.set(hidden, 0, w, h)
                    }
                }
                view.clipBounds = clip
            }
            addListener(object : AnimatorListenerAdapter() {
                // Runs on cancel too, so an interrupted slide still leaves the menu at rest.
                override fun onAnimationEnd(animation: Animator) {
                    view.translationX = restX
                    view.translationY = restY
                    view.clipBounds = null
                    view.outlineProvider = outline
                    if (view.getTag(R.id.menu_animator_tag) === animation) {
                        view.setTag(R.id.menu_animator_tag, null)
                    }
                }
            })
        }
        view.setTag(R.id.menu_animator_tag, animator)
        // Start fully hidden so the frame before the first update doesn't flash the whole menu.
        view.clipBounds = Rect(0, 0, 0, 0)
        animator.start()
    }
}
