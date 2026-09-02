package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone toast.
 *
 * A full-width accent band that slides in over whatever is on screen, holds briefly, and
 * retracts: two lines of Segoe, no icon, no border, no rounded corners. Tapping it opens
 * whatever it is about; flicking it away dismisses it early.
 *
 * Enters from the bottom, just above the navigation keys - within reach of a thumb, and
 * clear of the status bar.
 *
 * Replaces the Vista speech-bubble the desktop themes use, which is anchored to a system
 * tray this shell does not have.
 */
@SuppressLint("ViewConstructor")
class WP81Toast(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    private val band = LinearLayout(context)
    private val titleView = TextView(context)
    private val textView = TextView(context)

    private var onTap: (() -> Unit)? = null
    private var dragStartY = 0f

    /** This gesture has become a push rather than a tap. See [wireFlickToDismiss]. */
    private var pushing = false

    /** It has already gone far enough to send the band away. */
    private var pushedAway = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val hideRunnable = Runnable { dismiss() }

    init {
        visibility = GONE
        // Only the band takes touches; the rest of the screen stays live behind it.
        isClickable = false

        band.orientation = LinearLayout.VERTICAL
        band.setPadding(dp(20), dp(12), dp(20), dp(14))
        band.isClickable = true

        titleView.textSize = 15f
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)

        textView.textSize = 14f
        textView.maxLines = 2
        textView.ellipsize = android.text.TextUtils.TruncateAt.END
        textView.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)

        band.addView(titleView, wide())
        band.addView(textView, wide())
        addView(band, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        band.setOnClickListener {
            val action = onTap
            dismiss()
            action?.invoke()
        }
        wireFlickToDismiss()
        applyPalette(palette)
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** A downward flick retracts the band early - away from the screen, as it came in. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireFlickToDismiss() {
        band.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    pushing = false
                    pushedAway = false
                }
                MotionEvent.ACTION_MOVE -> if (!pushedAway) {
                    val dy = event.rawY - dragStartY
                    if (!pushing && dy > touchSlop) {
                        pushing = true
                        // Being pushed away, not held down.
                        band.isPressed = false
                    }
                    // Follows the finger downward only; dragging up does nothing.
                    if (dy > 0) band.translationY = dy
                    if (dy > dp(DISMISS_DP)) {
                        pushedAway = true
                        dismiss()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!pushedAway) band.animate().translationY(0f).setDuration(120).start()
            }
            // Consumed only once the gesture has become a push, which keeps it away from
            // the click listener: a band flicked off the screen must not also open what it
            // was announcing, which is the one thing the person flicking it declined. A
            // press that never moved is left unconsumed, so the click still hears it.
            pushing
        }
    }

    /**
     * How much room to leave under the band, on top of the navigation keys' own.
     *
     * A program with a strip of its own along the bottom - the browser's address bar - has
     * the band land on top of it, which announces something by covering the thing the
     * announcement is about. Set while such a program is open and put back when it closes.
     */
    var lift: Int = 0
        set(value) {
            field = value
            (band.layoutParams as? LayoutParams)?.let {
                it.bottomMargin = value
                band.layoutParams = it
            }
        }

    fun show(title: String, text: String, durationMs: Long, onTap: (() -> Unit)?) {
        this.onTap = onTap
        titleView.text = title
        titleView.visibility = if (title.isBlank()) GONE else VISIBLE
        textView.text = text
        textView.visibility = if (text.isBlank()) GONE else VISIBLE

        removeCallbacks(hideRunnable)
        wireFlickToDismiss()

        visibility = VISIBLE
        band.translationY = dp(SLIDE_DP).toFloat()
        band.alpha = 0f
        band.animate()
            .translationY(0f).alpha(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()

        postDelayed(hideRunnable, durationMs)
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        removeCallbacks(hideRunnable)
        band.animate()
            .translationY(dp(SLIDE_DP).toFloat()).alpha(0f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                visibility = GONE
                onTap = null
            }
            .start()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        band.setBackgroundColor(p.accent)
        titleView.setTextColor(p.onAccent())
        textView.setTextColor(p.onAccent())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** How far the band travels in and out. */
        private const val SLIDE_DP = 96

        /** Downward travel that counts as a flick-away. */
        private const val DISMISS_DP = 28
    }
}
