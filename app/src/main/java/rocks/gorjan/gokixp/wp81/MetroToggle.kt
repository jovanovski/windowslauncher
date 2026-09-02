package rocks.gorjan.gokixp.wp81

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The Windows Phone 8.1 switch.
 *
 * A rectangle with a bar in it. Off, the rectangle is an outline and the bar sits at the
 * left; on, the rectangle fills with the accent and the bar slides to the right. There is
 * no rounding anywhere in it, which is most of why it does not read as an Android switch
 * wearing different colours - a lozenge with a circle in it is somebody else's control.
 *
 * Shell furniture rather than the Alarms app's, for the reason [MetroAppBar] is: a switch
 * is what this platform puts beside anything that is simply on or off, and the shell's own
 * settings page has been drawing ticked squares by hand for the want of one. The squares
 * stay where they are - a tick is right for one of a set of options being chosen - and
 * this is for the other case, where the thing itself is running or it is not.
 *
 * It draws rather than composing views because it is four rectangles and a slide, and a
 * ValueAnimator over one float is less machinery than two Views being laid out.
 */
@SuppressLint("ViewConstructor")
class MetroToggle(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /** Fired when the user works the switch. Not fired by [set], which only shows a fact. */
    var onChanged: ((Boolean) -> Unit)? = null

    private var on = false

    /** Where the bar is: 0 parked left, 1 parked right. Animated between the two. */
    private var travel = 0f
    private var slide: ValueAnimator? = null

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            set(!on, animated = true)
            onChanged?.invoke(on)
        }
        TiltEffect.apply(this)
    }

    fun isOn(): Boolean = on

    /**
     * Puts the switch where the thing it stands for actually is.
     *
     * Silent on purpose: a row seeding its switch from a saved alarm is describing the
     * world, not changing it, and a listener fired here would write back everything it was
     * just handed.
     */
    fun set(value: Boolean, animated: Boolean) {
        if (on == value && slide == null) {
            travel = if (value) 1f else 0f
            invalidate()
            return
        }
        on = value
        slide?.cancel()
        val target = if (value) 1f else 0f
        if (!animated) {
            slide = null
            travel = target
            invalidate()
            return
        }
        slide = ValueAnimator.ofFloat(travel, target).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                travel = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    slide = null
                }
            })
            start()
        }
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(TRACK_W_DP), widthSpec),
            resolveSize(dp(TRACK_H_DP), heightSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val border = dp(BORDER_DP).toFloat()
        val trackW = dp(TRACK_W_DP).toFloat().coerceAtMost(width.toFloat())
        val trackH = dp(TRACK_H_DP).toFloat().coerceAtMost(height.toFloat())
        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f

        // The track. Filled with the accent once the switch is on, which is the whole of
        // the state at a glance: an outline that had merely gained a brighter outline
        // would be a difference nobody reads across a list of six alarms.
        ink.style = Paint.Style.FILL
        ink.color = blend(palette.inactive, palette.accent, travel)
        canvas.drawRect(left, top, left + trackW, top + trackH, ink)

        // Its edge, which survives the fill: off, the edge *is* the control.
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = border
        ink.color = blend(palette.foregroundSubtle, palette.accent, travel)
        canvas.drawRect(
            left + border / 2f, top + border / 2f,
            left + trackW - border / 2f, top + trackH - border / 2f, ink
        )

        // The bar, inset from the edge so the track shows all the way round it.
        val inset = border * 2f
        val barW = dp(BAR_W_DP).toFloat()
        val runway = trackW - inset * 2f - barW
        val barLeft = left + inset + runway * travel
        ink.style = Paint.Style.FILL
        ink.color = blend(palette.foreground, Color.WHITE, travel)
        canvas.drawRect(barLeft, top + inset, barLeft + barW, top + trackH - inset, ink)
    }

    /** Straight interpolation between two colours, alpha included. */
    private fun blend(from: Int, to: Int, amount: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(from, to, amount.coerceIn(0f, 1f))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The phone's own proportions: a wide, low rectangle with a narrow bar in it. */
        const val TRACK_W_DP = 46
        const val TRACK_H_DP = 20
        private const val BAR_W_DP = 12
        private const val BORDER_DP = 2

        private const val SLIDE_MS = 140L
    }
}
