package rocks.gorjan.gokixp.wp81

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The task switcher: what Windows Phone 8.1 showed while the back key was held.
 *
 * The real one was a row of tall cards, each a live picture of an app as it had been left,
 * flicked through sideways with a small X in the top corner of each to close it. The
 * pictures are the one part that cannot be had here: a task snapshot is taken by
 * system_server and handed only to the recents component the platform image was signed
 * with, so a third-party launcher has no way to ask for one and no way to fake one that
 * would not be a lie about what the app currently says.
 *
 * So the cards carry the app instead of a picture of it: a block of colour with the glyph
 * set large on it and the name underneath - the app's Start tile, blown up to the size the
 * screenshot would have been. That keeps the shape of the thing without pretending to a
 * fidelity it does not have, and it means a card looks like the tile the same app has on
 * Start.
 *
 * The row runs the way time does: the app you were just in is the rightmost card and the
 * one the switcher opens on, and going further back means reaching further left. The cards
 * overlap slightly and the one nearest the middle of the screen stands largest, shrinking
 * away towards both edges, so there is always exactly one card the row is *about* - which
 * is the job the phone's own switcher gave to the card that filled the screen.
 *
 * A card is thrown away by flicking it up off the row, the gesture every switcher since
 * has used, rather than by aiming at a small mark in its corner.
 *
 * There is no header. Every other page in this shell has an oversized lowercase title
 * because every other page was pushed onto a stack and can be backed out of by name; the
 * switcher was not a page at all - it was what the phone put up while a key was held, and
 * a title over it would be the one thing on screen that never appeared on the original.
 *
 * What is in the row comes from [RecentAppsStore], which is where the two halves of "where
 * has the user been" - the phone's history and this shell's own programs - are merged.
 */
@SuppressLint("ViewConstructor")
class WP81RecentsView(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /**
     * One app, ready to be drawn.
     *
     * The view is handed finished cards rather than being given a package name and left to
     * work the rest out: which glyph an app wears, what a tile of it is painted, and what
     * the user has renamed it to are all questions the host has already answered for the
     * Start screen, and answering them a second time here is how the switcher and the wall
     * end up disagreeing about the same app.
     */
    data class Card(
        /** A package name, or one of the shell's own `system.*` identifiers. */
        val id: String,
        val label: String,
        val glyph: MonochromeIconProvider.Glyph?,
        /** The app's tile colour, or null to wear the accent like an unpainted tile. */
        val color: Int?
    )

    /** Tapping a card. The host decides what opening that app means. */
    var onOpen: ((Card) -> Unit)? = null

    /**
     * Taking a card off the list, by flicking it up off the row.
     *
     * Named for what it actually does. The original's X closed the app; this cannot, and
     * the honest word for what is left is that the entry goes away. See
     * [RecentAppsStore.dismiss], which is where the same point is made about the data.
     */
    var onDismiss: ((Card) -> Unit)? = null

    /** The offer shown in place of the row when the phone will not say what has been used. */
    var onGrantAccess: (() -> Unit)? = null

    private val strip = LinearLayout(context)
    private val scroller = SnapScroller(context)

    /** What stands in for the row when there is no row: see [showNothing]. */
    private val message = TextView(context)
    private val offer = TextView(context)
    private val emptyColumn = LinearLayout(context)

    /** The page turns in and out like every other in this shell. See [MetroPageTransition]. */
    private val transition = MetroPageTransition(this)

    /**
     * What the last [show] was told about the phone's app history.
     *
     * Kept because the row can empty itself after the fact - the user can dismiss every
     * card there is - and the sentence that replaces it is not the same sentence in both
     * cases. Without this, somebody who had never granted the access and had only this
     * shell's own programs in the row would dismiss the last of them and be told there was
     * nothing to switch back to, when the truth is that the phone has not been asked.
     */
    private var usageAccessGranted = true

    /**
     * Whether the row is still waiting to be measured before it can be put where it opens.
     *
     * The switcher is built the instant the back key is released, which is before this view
     * has been laid out - so at that moment there is no width to scroll by and no card
     * positions to size the row against. Rather than guess at either, both jobs wait here
     * for the first layout that has real numbers in it.
     */
    private var awaitingLayout = false

    init {
        visibility = GONE
        // Nothing behind the switcher is reachable while it is up - it is a full-screen
        // overlay and is deliberately opaque, including over an open program window.
        isClickable = true

        scroller.isHorizontalScrollBarEnabled = false
        // Room to overscroll, so the row can be flicked past its ends and spring back the
        // way every other surface in this shell does.
        scroller.overScrollMode = OVER_SCROLL_ALWAYS
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        // The gap left by a dismissed card closes itself rather than snapping shut: what
        // the cards beside it do afterwards is a layout change, and this is what animates
        // one. Only that change, though - the card's own exit is animated by hand, see
        // [dismiss], and the transition's own fade run over the top of it made a single
        // removal look like two. Losing the fade also loses the delay the transition holds
        // the gap closed for while it plays, which is why the delay is put back to nothing.
        strip.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.DISAPPEARING)
            disableTransitionType(LayoutTransition.APPEARING)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
        }
        scroller.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Which card is nearest the middle changes on every pixel of scroll, so the sizes
        // are recomputed there rather than settled once when the row is built.
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> updateFocus() }
        // And again whenever the row itself moves: a dismissed card leaves the ones after
        // it sliding into new positions, and their size follows where they end up. It is
        // also the first moment the row has a size at all, which is when it gets put where
        // it opens - see [awaitingLayout].
        strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (awaitingLayout && strip.width > 0 && scroller.width > 0) {
                awaitingLayout = false
                scroller.scrollTo((strip.width - scroller.width).coerceAtLeast(0), 0)
            }
            updateFocus()
        }

        message.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        message.textSize = 15f
        message.gravity = Gravity.CENTER_HORIZONTAL

        // The one thing on the empty screen that is a command rather than a statement, so
        // it is set in the accent - which is what says "tappable" everywhere else here.
        offer.typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        offer.textSize = 15f
        offer.gravity = Gravity.CENTER_HORIZONTAL
        offer.setPadding(dp(12), dp(18), dp(12), dp(12))
        offer.isClickable = true
        offer.setOnClickListener {
            Haptics.tap(it)
            onGrantAccess?.invoke()
        }
        TiltEffect.apply(offer)

        emptyColumn.orientation = LinearLayout.VERTICAL
        emptyColumn.gravity = Gravity.CENTER
        emptyColumn.setPadding(dp(36), 0, dp(36), 0)
        emptyColumn.visibility = GONE
        emptyColumn.addView(message, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        emptyColumn.addView(offer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(emptyColumn, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        applyPalette(palette)
    }

    // ---------------------------------------------------------------- showing

    /**
     * Puts the switcher up.
     *
     * [usageAccess] is asked for separately from the cards because an empty row means two
     * quite different things: the phone has been asked and had nothing to say, or the
     * phone has not been asked at all. The second is worth an offer and the first is not,
     * and a caller handing over an empty list cannot tell this which it meant.
     */
    fun show(cards: List<Card>, usageAccess: Boolean) {
        usageAccessGranted = usageAccess
        strip.removeAllViews()
        if (cards.isEmpty()) {
            showNothing(usageAccess)
        } else {
            emptyColumn.visibility = GONE
            scroller.visibility = VISIBLE
            // Oldest first, so the row reads the way time does and the app just left is the
            // one on the right - where the switcher opens, and where the thumb already is.
            for (card in cards.asReversed()) strip.addView(build(card))
            // Nothing here has been measured yet; layOutRow sets the sizes and leaves the
            // scroll and the sizing to the layout that applies them.
            layOutRow()
        }
        transition.playIn()
    }

    fun hide() {
        transition.playOut()
    }

    /** Up, and not in the middle of leaving. */
    fun isShowing(): Boolean = transition.isOnScreen

    /**
     * The sentence that stands in for the row.
     *
     * A sentence rather than a picture of an empty screen: the phone said what was not
     * there in the same type as everything else and left it at that.
     */
    private fun showNothing(usageAccess: Boolean) {
        scroller.visibility = GONE
        emptyColumn.visibility = VISIBLE
        if (usageAccess) {
            message.text = "nothing to switch back to yet"
            offer.visibility = GONE
        } else {
            message.text =
                "this phone will not say which apps you have been in until app history " +
                    "is turned on for the launcher"
            offer.text = "turn on app history"
            offer.visibility = VISIBLE
        }
    }

    // ---------------------------------------------------------------- one card

    private fun build(card: Card): CardView {
        val root = CardView(context)
        // An unpainted app wears the accent, exactly as its tile would.
        root.setBackgroundColor(card.color ?: palette.accent)
        root.isClickable = true
        // The cards overlap, so which one is in front matters. A flat block of colour with
        // an outline would be given a drop shadow the moment it is lifted in Z, and there
        // are no drop shadows anywhere in this design language - so it is lifted without
        // one. See [updateFocus], which does the lifting.
        root.outlineProvider = null
        // Filled in properly by layOutRow once the row's width is known; a size here only
        // stops LinearLayout complaining before then.
        root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // The glyph is drawn by the same rule the tiles use: flat artwork - this shell's
        // own marks, an app's themed monochrome layer, its notification silhouette - goes
        // on in the tile's foreground colour, and an app with none of those keeps the icon
        // it was installed with, unboxed. It is tempting to force every card to the app's
        // full-colour launcher icon on the grounds that colour reads better at this size,
        // but that would make the switcher the one surface in the shell where an app does
        // not look like itself: Music would be a headphone glyph on Start and a picture of
        // one here. A card is a tile blown up, so it is painted like one.
        val box = glyphBox(card.glyph)
        column.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            when (val glyph = card.glyph) {
                is MonochromeIconProvider.Glyph.Monochrome -> {
                    setImageDrawable(glyph.drawable)
                    imageTintList = ColorStateList.valueOf(palette.onAccent())
                }
                is MonochromeIconProvider.Glyph.FullColor -> setImageDrawable(glyph.drawable)
                null -> setImageDrawable(null)
            }
        }, LinearLayout.LayoutParams(box, box))

        column.addView(TextView(context).apply {
            text = oneLine(card.label)
            // Semilight, which is the weight Windows Phone set the name of a thing in when
            // the thing itself was the picture beside it.
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
            textSize = 19f
            setTextColor(palette.onAccent())
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        root.addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // The tilt and the flick share the card's one touch listener - see TiltEffect.apply,
        // which exists for exactly this. Setting a second listener afterwards would silently
        // replace the first.
        TiltEffect.apply(root, flickToDismiss(root, card))
        root.setOnClickListener { onOpen?.invoke(card) }
        return root
    }

    /**
     * An app's name, as one line of words.
     *
     * Some apps put a line break in their own label - it is a string the developer typed,
     * and nothing stops them - which on Start is invisible because a tile's name is clipped
     * to one line anyway, and here is not: these cards give a name two lines, so a break
     * meant for some other layout entirely arrives as a name snapped in half. Every run of
     * whitespace becomes one space, which also takes care of the double spaces and the
     * trailing ones that tend to come with them.
     */
    private fun oneLine(label: String): String = label.replace(WHITESPACE, " ").trim()

    /**
     * The row, which comes to rest on a card rather than between two.
     *
     * A plain scroller stops wherever the finger left it, and half of one card beside half
     * of another says the switcher is about neither of them - which is the one thing the
     * difference in size is there to prevent. So a release always ends on a card: a flick
     * moves one card in the direction it was thrown, and letting go without one settles on
     * whichever card is nearest the middle already.
     *
     * One card per flick rather than a real fling. The row is ten cards at most, and a
     * proper fling crosses all of them - which turns picking the app before last into an
     * aiming problem rather than a flick and a tap.
     */
    private inner class SnapScroller(context: Context) : HorizontalScrollView(context) {

        /** Whether the release now being handled turned into a fling. See [fling]. */
        private var flung = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Super first: it is what calls fling() on the way through a release, so by the
            // time the flag is read here the answer is already in it.
            val handled = super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> flung = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!flung) settleOn(nearestCard())
            }
            return handled
        }

        override fun fling(velocityX: Int) {
            flung = true
            // Positive means the row is being sent towards its right-hand end, where the
            // newest cards are: HorizontalScrollView hands its own fling the negated finger
            // velocity, so a leftward flick arrives here positive.
            val enough = ViewConfiguration.get(context).scaledMinimumFlingVelocity
            val from = nearestCard()
            settleOn(
                when {
                    velocityX > enough -> from + 1
                    velocityX < -enough -> from - 1
                    else -> from
                }
            )
        }
    }

    /** Which card the row is currently nearest to being about. */
    private fun nearestCard(): Int {
        val count = strip.childCount
        if (count == 0) return 0
        val middle = scroller.scrollX + scroller.width / 2f
        var nearest = 0
        var best = Float.MAX_VALUE
        for (i in 0 until count) {
            val child = strip.getChildAt(i)
            val away = kotlin.math.abs(child.left + child.width / 2f - middle)
            if (away < best) {
                best = away
                nearest = i
            }
        }
        return nearest
    }

    /** Brings one card to the middle of the screen, or as near as the ends of the row allow. */
    private fun settleOn(index: Int) {
        val count = strip.childCount
        if (count == 0) return
        val child = strip.getChildAt(index.coerceIn(0, count - 1))
        val furthest = (strip.width - scroller.width).coerceAtLeast(0)
        val target = (child.left + child.width / 2 - scroller.width / 2).coerceIn(0, furthest)
        scroller.smoothScrollTo(target, 0)
    }

    /**
     * A card, which knows how large it is meant to be sitting still.
     *
     * [TiltEffect.Target] is the reason this is a class rather than a plain FrameLayout.
     * Every card in the row is scaled by how near the middle of the screen it is, so a card
     * at rest is almost never at scale 1 - and without being told, the press animation
     * would spring it back to full size the moment the finger lifted, which read as the
     * card swelling for no reason.
     */
    private inner class CardView(context: Context) : FrameLayout(context), TiltEffect.Target {
        /** What [updateFocus] last decided this card's size should be. */
        var restScale = 1f
        override fun restingScale(): Float = restScale
    }

    /**
     * Flicking a card up off the row.
     *
     * The gesture has to be taken off the scroller rather than shared with it: a horizontal
     * drag is the row being flicked across and a vertical one is this, and which of the two
     * is happening is only knowable after the finger has moved. So nothing is claimed until
     * the movement is past the slop *and* more vertical than horizontal - at which point
     * the parent is told to stop intercepting, and every event after that is consumed so
     * the card's own click never fires on the way up.
     *
     * Downward drags are ignored. There is nothing below the row to send a card to, and a
     * card that followed the finger down and then sprang back is a control answering a
     * gesture that means nothing.
     */
    private fun flickToDismiss(root: CardView, card: Card): (View, MotionEvent) -> Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var claimed = false
        return { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    claimed = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!claimed && dy < -slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        claimed = true
                        root.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (claimed) {
                        // The tilt runs first and is still answering the finger's position
                        // inside the card, which fights a card that is on its way out. Its
                        // rotation is flattened here on every step rather than once when
                        // the flick is claimed, because it would otherwise start again.
                        root.animate().cancel()
                        root.rotationX = 0f
                        root.rotationY = 0f
                        root.scaleX = root.restScale
                        root.scaleY = root.restScale
                        val lift = kotlin.math.min(0f, dy)
                        root.translationY = lift
                        val travel = (root.height * DISMISS_TRAVEL).coerceAtLeast(1f)
                        root.alpha = (1f + lift / travel).coerceIn(FADE_FLOOR, 1f)
                    }
                    claimed
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!claimed) {
                        false
                    } else {
                        // The view never sees this release, so it is left believing it is
                        // still held unless it is told otherwise.
                        root.isPressed = false
                        val far = downY - event.rawY > root.height * DISMISS_TRAVEL
                        if (event.actionMasked == MotionEvent.ACTION_UP && far) {
                            dismiss(root, card)
                        } else {
                            root.animate()
                                .translationY(0f).alpha(1f)
                                .setDuration(SPRING_MS)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                        true
                    }
                }
                else -> false
            }
        }
    }

    /**
     * The box a glyph is drawn in, inflated by how much of its own canvas it covers.
     *
     * The same correction the tiles make in their corners: source artwork pads itself
     * wildly differently - a themed layer keeps the adaptive-icon safe zone and covers
     * about two thirds of its square, this shell's own marks very nearly fill theirs - and
     * drawn in one fixed box the first reads small beside the second. Capped, because an
     * icon that is mostly empty would otherwise ask for a box wider than the card.
     */
    private fun glyphBox(glyph: MonochromeIconProvider.Glyph?): Int {
        val ratio = when (glyph) {
            is MonochromeIconProvider.Glyph.Monochrome -> glyph.contentRatio
            is MonochromeIconProvider.Glyph.FullColor -> glyph.contentRatio
            null -> 1f
        }
        return (dp(GLYPH_DP) / ratio.coerceIn(MIN_RATIO, 1f)).toInt()
    }

    /**
     * Takes a card off the row.
     *
     * The app is untouched - see [onDismiss]. The card leaves upwards rather than fading
     * where it stands, because the row is read across and a card that only fades looks
     * like it is still there and has gone wrong.
     */
    private fun dismiss(card: View, entry: Card) {
        onDismiss?.invoke(entry)
        TiltEffect.reset(card)
        card.animate()
            .alpha(0f)
            // From wherever the finger left it rather than from nought, so the card carries
            // on the way it was thrown instead of snapping back to the row to leave again.
            .translationY(card.translationY - card.height * LEAVE_FRACTION)
            .setDuration(DISMISS_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                strip.removeView(card)
                // The last one off leaves nothing behind it, and an empty black screen
                // with a key strip under it says nothing at all about why.
                if (strip.childCount == 0) showNothing(usageAccessGranted)
            }
            .start()
    }

    // ---------------------------------------------------------------- measurement

    /**
     * Sizes every card and puts the row where it opens.
     *
     * Separate from building the cards because it has to be able to run again: the row is
     * built the instant the back key is released, which on the first hold of a session is
     * before this view has ever been measured, and a card sized from a width of nothing is
     * a card that is not there. See [onSizeChanged].
     */
    private fun layOutRow() {
        val count = strip.childCount
        if (count == 0) return
        val cardWidth = cardWidth()
        // Half a screen either side of the row, so any card - the first and the last
        // included - can be brought to the middle of the screen. Without it the newest card
        // sat hard against an edge, which reads as a list that has been scrolled away from
        // rather than one that is at its start.
        val end = ((widthOrScreen() - cardWidth) / 2).coerceAtLeast(dp(GAP_DP))
        strip.setPadding(end, 0, end, 0)
        for (i in 0 until count) {
            val child = strip.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.width = cardWidth
            lp.height = cardHeight()
            // A real gap, and a wide one. The cards were overlapped at first, which is
            // what the phone's own switcher did with photographs of apps - but a photograph
            // of an app has its own edges, and these cards are flat blocks of one colour.
            // Two of them touching in the same accent read as a single wide card, so what
            // divides them has to be the background showing through.
            lp.marginEnd = if (i == count - 1) 0 else dp(GAP_DP)
            child.layoutParams = lp
        }
        // Where the right-hand end of the row is depends on every size just set, so the
        // scroll that opens on the newest card waits for the layout that applies them.
        // Posting it instead was the bug this replaced: a post can run before the layout
        // it is waiting on, and one that did found a row of width nothing.
        awaitingLayout = true
    }

    /**
     * Rotation, or a window that was not full-screen when the row was built.
     *
     * The cards hold pixel sizes worked out from the width they were built at, so a screen
     * that changes shape underneath them leaves every one of them the wrong size. Rebuilt
     * dimensions rather than rebuilt cards: what is on the row, and what has been thrown
     * off it, is not something a rotation should have an opinion about.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        layOutRow()
    }

    /**
     * Sizes each card by how near it is to the middle of the screen.
     *
     * One card is always the largest, and it is whichever one the row has been brought to
     * rest on - which is how the original said which app you were about to switch to, by
     * giving it the whole screen. Here it is a difference in size instead, because the
     * cards are small enough that several are in view at once and something has to say
     * which of them the row is currently about.
     *
     * The falloff is measured in cards rather than in screens: one card's width from the
     * middle is the whole of it, so the card either side of the focused one is already at
     * its smallest and anything further away simply stays there. A gentler curve spread the
     * difference so thinly across the row that nothing appeared to be focused at all.
     *
     * Z follows size all the same. The cards no longer overlap at rest, but a card being
     * thrown off the row travels over its neighbours, and the focused one being in front is
     * what stops that looking like it has gone behind them.
     */
    private fun updateFocus() {
        val count = strip.childCount
        if (count == 0) return
        // Before the first layout every card sits at nought wide at position nought, which
        // measures as every one of them being dead centre - so they would all be written to
        // full size, and only the next layout would put them right. That was visible as a
        // row that was correct only after the first card had been thrown off it.
        if (scroller.width == 0 || strip.getChildAt(0).width == 0) return
        val middle = scroller.scrollX + scroller.width / 2f
        val step = (cardWidth() + dp(GAP_DP)).toFloat().coerceAtLeast(1f)
        for (i in 0 until count) {
            val child = strip.getChildAt(i)
            val centre = child.left + child.width / 2f
            val away = (kotlin.math.abs(centre - middle) / step).coerceIn(0f, 1f)
            val scale = 1f - (1f - MIN_SCALE) * away
            (child as? CardView)?.restScale = scale
            // Not while a finger is on it: the press owns the card's size then, and the
            // flick owns it after that. Writing here would fight both.
            if (!child.isPressed && child.translationY == 0f) {
                child.scaleX = scale
                child.scaleY = scale
            }
            child.translationZ = scale * dp(Z_SPREAD_DP)
        }
    }

    /**
     * How wide one card is.
     *
     * A fraction of the screen rather than a fixed width, so the neighbours peek in at
     * both edges on any phone - the peek is what says the row goes on and can be flicked,
     * and it is the single thing that makes this read as the phone's switcher rather than
     * as a page of large buttons.
     */
    private fun cardWidth(): Int = (widthOrScreen() * CARD_FRACTION).toInt()

    /** How tall one card is: most of the screen, but standing clear of both ends of it. */
    private fun cardHeight(): Int = (heightOrScreen() * HEIGHT_FRACTION).toInt()

    /**
     * This view's width, or the screen's before it has ever been laid out.
     *
     * The switcher is built the moment the key is released, which on the first hold of a
     * session is before this view has been measured - and a card sized from a width of
     * zero is a card that is not there.
     */
    private fun widthOrScreen(): Int =
        width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun heightOrScreen(): Int =
        height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- palette

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        message.setTextColor(p.foregroundSubtle)
        offer.setTextColor(p.accent)
        // The cards are painted as they are built, out of colours that were read when the
        // switcher was opened. Nothing repaints them here because nothing can change them
        // while they are on screen: the settings page that changes the accent is a page,
        // and opening it takes the switcher down.
    }

    private companion object {
        /** How much of the screen one card takes. The rest is the neighbours peeking in. */
        const val CARD_FRACTION = 0.60f

        /** How much of the screen's height a card stands. The rest is air above and below. */
        const val HEIGHT_FRACTION = 0.70f

        /**
         * Between two cards, and the least at either end of the row.
         *
         * Wide, because it is the only thing separating two cards that may well be painted
         * the same colour. See [layOutRow].
         */
        const val GAP_DP = 16

        /** How small a card gets once it is a full card away from the middle. */
        const val MIN_SCALE = 0.84f

        /** How far the focused card is lifted above the ones it overlaps. See [updateFocus]. */
        const val Z_SPREAD_DP = 6

        /** The visible size of a glyph on a card, whatever it was drawn on. */
        const val GLYPH_DP = 72

        /** The least of its canvas a mark may be said to cover. See [glyphBox]. */
        const val MIN_RATIO = 0.45f

        /**
         * How far up a card has to be thrown before letting go of it means it.
         *
         * A fraction of the card rather than a number of millimetres: the card is most of
         * the screen tall, and the flick should feel the same on any of them.
         */
        const val DISMISS_TRAVEL = 0.20f

        /** Any run of blank, for [oneLine]. */
        val WHITESPACE = Regex("\\s+")

        /** How faint a card being thrown is allowed to get before it is let go of. */
        const val FADE_FLOOR = 0.2f

        /** Putting back a card that was not thrown far enough. */
        const val SPRING_MS = 160L

        const val DISMISS_MS = 170L

        /** How far further up a dismissed card carries before it is gone. */
        const val LEAVE_FRACTION = 0.35f
    }
}
