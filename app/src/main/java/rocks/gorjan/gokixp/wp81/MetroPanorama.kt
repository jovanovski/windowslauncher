package rocks.gorjan.gokixp.wp81

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone panorama: a row of section titles over a set of pages, dragged
 * sideways as one long surface.
 *
 * Shell furniture rather than any one program's: Zune is laid out on it and so is News,
 * and anything else here with more sections than a screen would be too.
 *
 * Two things make it a panorama rather than a set of tabs. The titles are laid out end to
 * end and move *with* the drag, so the next section's name is already on screen, half
 * arrived, before you get to it - the app is wider than the phone and says so. And the
 * gesture is the whole screen, not a strip at the top: you push the page itself.
 *
 * There are three layers, and the whole effect is that they move at different rates. The
 * pages travel with the finger. The section titles travel with them, so each name arrives
 * at the left margin exactly as its page does - the header strip is a continuous line of
 * words rather than a set of tabs. The app's own name, at the top, travels slowest of all:
 * it drifts a fraction of the screen over the entire panorama, which is what tells the eye
 * that the app is one wide surface being moved across rather than a stack of screens being
 * swapped. Microsoft's own guidance for the control puts it exactly that way - the title's
 * rate of motion is slow relative to the content, and slower again than the background art.
 *
 * It is a loop: pushing past the last section brings the first one round again, which is
 * what the platform did and what makes a panorama a surface rather than a queue. What it
 * does *not* do is lay its names out twice to get there - see [rebuildStrip] for how the
 * strip crosses the seam with only one of each name on it.
 */
@SuppressLint("ViewConstructor")
class MetroPanorama(
    context: Context,
    private val palette: WP81Palette
) : LinearLayout(context) {

    /** Fired when the panorama settles on a section. */
    var onPageSettled: ((Int) -> Unit)? = null

    /**
     * The app's name, and the corner beside it.
     *
     * A layer of its own rather than something the program puts above the panorama,
     * because it has to move - and it can only move against the pages if the thing moving
     * it is the thing that knows where the pages are.
     */
    private val titleRow = FrameLayout(context).apply { clipChildren = false }

    private val titleLabel = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        textSize = APP_TITLE_SP
        includeFontPadding = false
        maxLines = 1
        visibility = View.GONE
    }

    private val headerClip = HeaderStrip(context)
    private val headerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
    }
    private val pageHost = PageHost(context)

    /** The section names, and every label drawn for them - one per name per repeat. */
    private val titleTexts = mutableListOf<String>()
    private val titleLabels = mutableListOf<TextView>()

    private val pages = mutableListOf<View>()



    /**
     * What the title is a way to, where it is a way to anything.
     *
     * Most panoramas are titled after the program and there is nothing to tap. One titled
     * after *what is being shown* is different - a weather app headed with the name of a
     * town is headed with the one thing on the page that could have been another town, so
     * it is where somebody would reach to change it. Left unset, the title is type.
     */
    var onTitle: (() -> Unit)? = null
        set(value) {
            field = value
            titleLabel.isClickable = value != null
            if (value != null) TiltEffect.apply(titleLabel)
        }

    /** Where the panorama is, measured in pages: 1.5 is halfway between the second and third. */
    private var offset = 0f

    private var settleAnimator: ValueAnimator? = null

    init {
        orientation = VERTICAL

        titleLabel.setTextColor(palette.foreground)
        titleLabel.setOnClickListener { onTitle?.invoke() }
        titleRow.addView(titleLabel, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(APP_TITLE_TOP_DP)
            bottomMargin = dp(APP_TITLE_BOTTOM_DP)
        })
        titleRow.visibility = View.GONE
        addView(titleRow, LayoutParams(MATCH, WRAP))

        headerClip.clipChildren = false
        headerClip.clipToPadding = false
        headerClip.addView(headerRow, FrameLayout.LayoutParams(WRAP, WRAP))
        addView(headerClip, LayoutParams(MATCH, WRAP))

        addView(pageHost, LayoutParams(MATCH, 0, 1f))
    }

    /**
     * The app's own name, in the size the platform wrote it in: large, light and lower
     * case.
     *
     * Programs used to draw this themselves, above the panorama, where it was a heading
     * that happened to sit over some pages. Here it belongs to the panorama, because a
     * panorama title is not a heading - it is the slowest of the moving layers, and a
     * still one is the tell that a page is a page.
     */
    fun setTitle(text: String?) {
        titleLabel.text = text.orEmpty()
        titleLabel.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        syncTitleRow()
    }

    /**
     * A command belonging to the app rather than to any one section, kept in the corner
     * the title leaves empty.
     *
     * It does not travel with the title. A panorama has nowhere else to put a command -
     * the foot of the screen is the shell's keys and the sides are the panorama's own
     * gesture - and something you have to catch as it slides past is not a button.
     */
    fun setTitleAccessory(view: View?, sizeDp: Int) {
        if (titleRow.childCount > 1) titleRow.removeViewAt(1)
        if (view == null) {
            syncTitleRow()
            return
        }
        titleRow.addView(view, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            // The panorama is padded on the left only - its pages run to the right edge of
            // the screen - so the corner is squared off by hand.
            marginEnd = paddingLeft
        })
        syncTitleRow()
    }

    private fun syncTitleRow() {
        titleRow.visibility =
            if (titleLabel.visibility == View.VISIBLE || titleRow.childCount > 1) View.VISIBLE
            else View.GONE
    }

    fun addPage(title: String, page: View) {
        titleTexts.add(title)
        rebuildStrip()

        pages.add(page)
        pageHost.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        applyOffset(offset)
    }

    /**
     * Lays the section names out end to end, once each.
     *
     * Once. The strip used to be repeated so the last name could run straight into the
     * first, which is the obvious way to carry a loop - but a panorama of two short names
     * then spent most of the screen saying "notes archive notes archive", and two sections
     * that read as four is worse than anything the repeats were buying. The loop is still
     * there; the seam is crossed by sending this one strip home instead, in the same step
     * and at the same time as the first section is pulled in. See [titleOffsetFor].
     */
    private fun rebuildStrip() {
        headerRow.removeAllViews()
        titleLabels.clear()
        if (titleTexts.isEmpty()) return
        for ((index, text) in titleTexts.withIndex()) {
            val label = TextView(context).apply {
                this.text = text
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
                textSize = TITLE_SP
                includeFontPadding = false
                maxLines = 1
                setPadding(0, dp(4), dp(TITLE_GAP_DP), dp(10))
                isClickable = true
                setOnClickListener { goTo(index, animated = true) }
            }
            titleLabels.add(label)
            headerRow.addView(label)
        }
        repaintTitles()
    }

    /** Which section is showing, rounded to the nearest while a drag is in progress. */
    fun currentPage(): Int = wrapIndex(Math.round(offset))

    /** Brings a page number back inside the panorama, however far round it has gone. */
    private fun wrapIndex(index: Int): Int {
        val count = pages.size
        if (count <= 0) return 0
        return ((index % count) + count) % count
    }

    fun goTo(index: Int, animated: Boolean) =
        settleTo(index.coerceIn(0, (pages.size - 1).coerceAtLeast(0)).toFloat(), animated)

    /**
     * Runs the panorama to [target], which may be one page past either end.
     *
     * Past the end is how the wrap is expressed: the panorama keeps going in the direction
     * it was pushed, the first section arrives from the right exactly as any other would,
     * and only once it has landed is the position quietly counted back into range.
     */
    private fun settleTo(target: Float, animated: Boolean) {
        settleAnimator?.cancel()
        if (!animated) {
            applyOffset(target)
            finishSettle()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(offset, target).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finishSettle()
                }
            })
            start()
        }
    }

    /** Counts the landing position back into range and announces where that is. */
    private fun finishSettle() {
        val count = pages.size
        if (count > 0) {
            offset = ((offset % count) + count) % count
            applyOffset(offset)
        }
        onPageSettled?.invoke(currentPage())
    }

    /**
     * Places the pages and the header for a position that may be between two sections.
     *
     * The header's travel is the width of the titles, not the width of the screen: the
     * strip is a continuous line of words, and each one has to arrive at the left margin
     * exactly as its page does.
     */
    private fun applyOffset(value: Float) {
        val count = pages.size
        // A page past either end while the finger is down, which is the wrap being asked
        // for. Nothing is allowed to sit there once the gesture is over: see [finishSettle].
        offset =
            if (count > 1) value.coerceIn(-1f, count.toFloat())
            else value.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
        val width = pageHost.width.takeIf { it > 0 } ?: return

        // Everything is drawn from the position counted round the loop rather than from
        // the raw one. The pages, the strip and the name are all periodic in it, so the
        // moment the count turns over - a lap ended, or a drag taken back past the first
        // section - nothing on screen moves. It also puts both ways of reaching the seam,
        // forwards off the last section and backwards off the first, on the same stretch
        // of the count, which is the stretch the strip and the name travel home over.
        val position = if (count > 0) ((offset % count) + count) % count else 0f

        for ((i, page) in pages.withIndex()) {
            val delta = wrappedDelta(i - position)
            page.translationX = delta * width
            // A page that is nowhere near the screen is not drawn at all, which keeps a
            // panorama of six lists to the cost of the one or two actually visible.
            page.visibility = if (kotlin.math.abs(delta) < 1.2f) VISIBLE else INVISIBLE
        }
        headerRow.translationX = -titleOffsetFor(position)
        titleLabel.translationX = titleDriftAt(position)
        repaintTitles()
    }

    /**
     * How far page [raw] is from the one on screen, by the shorter way round.
     *
     * The panorama is a loop, so the last page is one step from the first and not five
     * steps back through the middle - and which of the two a page uses decides which side
     * of the screen it slides in from.
     */
    private fun wrappedDelta(raw: Float): Float {
        val count = pages.size
        if (count < 2) return raw
        var delta = raw
        val half = count / 2f
        while (delta > half) delta -= count
        while (delta < -half) delta += count
        return delta
    }

    /**
     * Where the app's name sits at [position]. The slowest of the moving layers.
     *
     * Two parts to the lap, and the name moves throughout both of them.
     *
     * Across the sections it drifts steadily left, from the margin to the point where
     * [APP_TITLE_KEEP] of it is still showing on the last one - so wherever the user stops,
     * the name is still partly there.
     *
     * The last step of the lap is the seam, and the name spends it travelling back: it
     * slides home as the first section is pulled in, arriving exactly as that section
     * does. That is the whole reason to compute it this way rather than to animate a jump
     * afterwards - the return is part of the swipe, so it is under the finger like every
     * other movement on the panorama, and it comes out continuous at both ends of the seam.
     */
    private fun titleDriftAt(position: Float): Float {
        val count = pages.size
        val label = titleLabel.width
        if (count < 2 || label <= 0) return 0f
        val keep = label * APP_TITLE_KEEP
        val travel = (paddingLeft + label - keep)
            .coerceAtMost(width * APP_TITLE_MAX_TRAVEL)
        var lap = position % count
        if (lap < 0) lap += count
        val last = count - 1f
        // Out across the sections, then back over the one step that closes the loop.
        return if (lap <= last) -(lap / last) * travel else -(count - lap) * travel
    }

    /**
     * Where the title strip has to sit for [position], interpolated between two titles.
     *
     * Across the sections that is the plain thing: each name is carried to the left margin
     * exactly as its own page arrives, which is what makes the strip read as one line of
     * words being drawn past rather than as a set of tabs lighting up.
     *
     * The last step of the lap is the seam, and there is no further name to run into -
     * this strip holds one of each. So the strip does what the app's name does over that
     * same step and travels home: it slides back to the first name as the first section is
     * pulled in, arriving with it. The names move against the pages for that one step,
     * which is the price of never saying "notes" twice, and it is paid where the eye is on
     * the section arriving rather than on the strip.
     */
    private fun titleOffsetFor(position: Float): Float {
        if (titleLabels.isEmpty()) return 0f
        val last = titleLabels.size - 1
        val lastLeft = titleLabels[last].left.toFloat()
        if (position >= last) {
            val step = (position - last).coerceIn(0f, 1f)
            return lastLeft * (1f - step)
        }
        val low = position.toInt().coerceIn(0, last)
        val high = (low + 1).coerceAtMost(last)
        val lowLeft = titleLabels[low].left.toFloat()
        val highLeft = titleLabels[high].left.toFloat()
        return lowLeft + (highLeft - lowLeft) * (position - low)
    }

    /** The section you are on is lit; the ones either side of it stand back. */
    private fun repaintTitles() {
        if (titleLabels.isEmpty()) return
        val current = currentPage()
        for ((i, title) in titleLabels.withIndex()) {
            title.setTextColor(if (i == current) palette.foreground else palette.inactive)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) applyOffset(offset)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Holds the title strip at its true width, however far past the screen that runs.
     *
     * A row of titles inside an ordinary parent is measured against the space available,
     * so the strip stopped at the right edge of the phone and the titles past it were
     * measured to nothing - which is why the last two sections had no names. The strip is
     * longer than the display by design; that is the whole idea of a panorama.
     */
    private inner class HeaderStrip(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val child = getChildAt(0)
            if (child == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(unbounded, unbounded)
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                child.measuredHeight
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val child = getChildAt(0) ?: return
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    /**
     * The pages, and the drag that moves them.
     *
     * Intercepting rather than consuming outright: the pages are lists, so a mostly
     * vertical drag has to reach the list underneath. Only once a gesture is clearly
     * sideways does this take it, and from that moment the list stops seeing it.
     */
    private inner class PageHost(context: Context) : FrameLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var startOffset = 0f
        private var velocity: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    settleAnimator?.cancel()
                    // The shell pages left and right too. Whatever is behind this window
                    // must not read the same drag as a swipe to the app list.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging &&
                        kotlin.math.abs(dx) > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * DIRECTION_BIAS
                    ) {
                        dragging = true
                        startOffset = offset
                        beginTracking(ev)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val width = width.takeIf { it > 0 } ?: return false
            velocity?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    startOffset = offset
                    dragging = true
                    beginTracking(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    applyOffset(startOffset - (event.x - downX) / width)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    // A flick carries to the next section even when it covered barely any
                    // distance; anything slower goes wherever it is closest to.
                    val target = when {
                        vx < -minFlingVelocity -> kotlin.math.ceil(offset).toInt()
                        vx > minFlingVelocity -> kotlin.math.floor(offset).toInt()
                        else -> Math.round(offset)
                    }
                    // Not clamped to the ends: one page past either of them is the wrap,
                    // and it lands on the section at the other end of the panorama.
                    settleTo(target.toFloat(), animated = true)
                }
            }
            return true
        }

        private fun beginTracking(ev: MotionEvent) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            velocity?.addMovement(ev)
        }
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT

        /** The app's name: the largest thing on the panorama, as the platform had it. */
        const val APP_TITLE_SP = 69f

        /** Air above the name, and between it and the section titles under it. */
        const val APP_TITLE_TOP_DP = 12
        const val APP_TITLE_BOTTOM_DP = 4

        /** How much of the name is still showing once the last section is reached. */
        const val APP_TITLE_KEEP = 0.35f

        /** However long the name, it never drifts further than this share of the screen. */
        const val APP_TITLE_MAX_TRAVEL = 0.5f

        const val TITLE_SP = 30f
        const val TITLE_GAP_DP = 22
        const val SETTLE_MS = 260L

        /** How much more sideways than vertical a drag must be before it counts as paging. */
        const val DIRECTION_BIAS = 1.1f
    }
}
