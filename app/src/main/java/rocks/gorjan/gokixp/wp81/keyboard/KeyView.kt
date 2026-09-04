package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * One key.
 *
 * Built like the calculator's, with one deliberate difference: the label, the hint and the
 * glyph are painted straight onto the canvas rather than being child views. The calculator
 * has twenty-four keys and can afford a TextView apiece; a keyboard has forty and would want
 * two apiece for the top row, and eighty views that never change and are laid out on every
 * measure pass is a cost with nothing to show for it. Everything else here - the touch
 * target half a gap larger than the painted key, the fill driven off the raw touch stream,
 * the press as one more step along the same colour line - is the calculator's and is
 * deliberately unchanged.
 *
 * The view is *bigger than the key it draws*. The gutters between keys belong to the keys
 * either side of them, split down the middle, so that a finger landing in the gap presses
 * the key it was aimed at rather than nothing at all. Nothing about where keys appear
 * changes; only how far out from each one the touch reaches.
 */
@SuppressLint("ViewConstructor")
class KeyView(
    context: Context,
    var key: Key,
    private var palette: WP81Palette
) : View(context) {

    /**
     * Who to tell. A keyboard's keys do not act on their own - what a letter means depends
     * on the shift state, what backspace means depends on what is behind the cursor, and
     * both live above this.
     */
    interface Listener {

        /**
         * The finger has gone down on a key.
         *
         * Only the keys that change which keys there are act on this rather than on the tap,
         * and they have to. Typing quickly means the next finger lands before the last one
         * lifts - that overlap *is* fast typing, not an accident - so a page that changes on
         * release is still showing the old keys when the following press arrives, and `&123`
         * followed quickly by a symbol produces the letter that was in its place.
         */
        fun onKeyPress(view: KeyView)

        /** A tap: pressed and released without the hold ever firing. */
        fun onKeyUp(view: KeyView)

        /** The hold has been recognised. Whatever it opens, opens now. */
        fun onKeyLongPress(view: KeyView)

        /**
         * The finger has moved while a hold is in progress, in this key's own coordinates.
         *
         * Which is how one picks from the row a hold opens: the gesture belongs to the key
         * that was pressed - Android delivers the whole of it there - so the key has to pass
         * the movement on rather than the popup receiving its own touches.
         */
        fun onKeyDrag(view: KeyView, x: Float, y: Float)

        /**
         * The finger has come off a key whose hold had already fired.
         *
         * Separate from [onKeyUp] because the two mean opposite things: a tap types the key,
         * where releasing a hold takes whatever the hold was offering - and must not also
         * type the key underneath it.
         */
        fun onKeyRelease(view: KeyView)

        /** Fired repeatedly while a key that repeats is held. Backspace, and nothing else. */
        fun onKeyRepeat(view: KeyView)

        /**
         * The finger is sliding along the space bar to move the caret.
         *
         * @param steps characters to move, negative for left. A delta rather than a position,
         *   because the caret is the field's and where it ends up is the field's business -
         *   a line ending, a chip, an emoji that is two code units wide. The keyboard says
         *   "one to the left" and lets the field work out what that means.
         */
        fun onCursorSlide(view: KeyView, steps: Int)
    }

    var listener: Listener? = null

    /** Set by the grid on every measure pass; everything sizes itself off these. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f

    /**
     * How long this key must be held before it offers what is behind it.
     *
     * A setting rather than a constant, because the right value is a property of the hand
     * and not of the keyboard: too short and reaching for a letter produces its symbol, too
     * long and the symbol feels withheld.
     */
    var holdMillis: Long = DEFAULT_HOLD_MS

    /** The glyph, for the keys that have one. Parsed once - see the note in the grid. */
    var glyph: Drawable? = null
        set(value) {
            field = value
            tintGlyph()
            invalidate()
        }

    /**
     * A small drawing in the corner, where a hint character would otherwise go.
     *
     * The comma's smiley. Writing it as a character - `☺` - looked right in the source and
     * came out as a full-colour emoji on the key, because that is what the system font does
     * with it: a yellow face among a keyboard of thin white marks. A corner mark has to be
     * drawn from the same icon set as every other glyph here, in the same colour, at the same
     * weight, or it is not a corner mark but a picture stuck on a key.
     */
    var hintGlyph: Drawable? = null
        set(value) {
            field = value
            value?.setTint(hintColour())
            invalidate()
        }

    /**
     * Whether shift is on, which changes what a letter key shows.
     *
     * Held here rather than asked for at paint time so that a shift press repaints
     * thirty keys with one field write each instead of thirty lookups.
     */
    private var shifted = false

    /** Painted its pressed fill. Kept rather than read back off the view - see [light]. */
    private var lit = false

    /**
     * Whether to draw a bar under the glyph. Caps lock, and nothing else.
     *
     * A keyboard whose shift is on for one letter and one whose shift is on until told
     * otherwise are two different keyboards, and the difference has to be visible or every
     * accidental double tap turns into A SENTENCE LIKE THIS. The phone marked it under the
     * arrow rather than by swapping the arrow, so the key does not appear to change shape
     * as it changes state - which is also why this is drawn by the key rather than being a
     * second glyph: one arrow, with or without a line beneath it.
     */
    var underline = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val face = Paint()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private val labelFont = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
    private val hintFont = ResourcesCompat.getFont(context, R.font.segoeui_regular)

    /** Cleared on release, so a hold that has already fired does not also commit a letter. */
    private var longPressFired = false
    private var repeatCount = 0

    private val holdCheck = Runnable { fireLongPress() }
    private val repeat = object : Runnable {
        override fun run() {
            // Marked as having fired for the same reason a hold is: a key that has already
            // acted must not act once more when the finger comes off it. Without this a
            // backspace held long enough to clear a word eats one further character on
            // release, which reads as the key overshooting rather than as a second press.
            longPressFired = true
            // Once, as the run begins. A tick under every character would be a rattle.
            if (repeatCount == 0) KeyboardHaptics.tap(this@KeyView)
            listener?.onKeyRepeat(this@KeyView)
            repeatCount++
            postDelayed(this, repeatDelay())
        }
    }

    init {
        isClickable = true
        face.color = fillFor(pressed = false)
    }

    /**
     * Points this view at a different key.
     *
     * What lets the grid change layout without being torn down and built again. A key view
     * holds nothing that belongs to one particular key except the key itself: the paints, the
     * fonts and the touch handling are the same whether the face says `q` or `#`.
     *
     * The transient touch state is cleared with it, and that is not tidiness. This runs while
     * a finger is still on the way up from `&123` - which is the very view most likely to be
     * reused as the first symbol key - and a `longPressFired` or a half-finished slide left
     * over from the press that caused the switch would be read as belonging to the new key.
     */
    fun bind(next: Key) {
        cancelHold()
        longPressFired = false
        repeatCount = 0
        sliding = false
        lit = false
        key = next
        face.color = fillFor(false)
        tintGlyph()
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        face.color = fillFor(lit)
        tintGlyph()
        invalidate()
    }

    fun setMetrics(keyW: Float, keyH: Float, gap: Float) {
        this.keyW = keyW
        this.keyH = keyH
        this.gap = gap
    }

    fun setShifted(on: Boolean) {
        if (on == shifted) return
        shifted = on
        // Only the keys whose face actually changes need repainting. A function key's does
        // not, and a symbol key's does not either because its shifted form is itself.
        if (key.action == null) invalidate()
    }

    /** What this key would commit right now, shift included. */
    fun output(): String =
        if (shifted) key.shifted ?: key.output.uppercase() else key.output

    /**
     * What a hold on this key offers, or empty if nothing.
     *
     * The hint comes first because on the top row the number is what the hold is *for* - the
     * accents behind `e` are a bonus, the `3` above it is the reason the row has hints at all.
     *
     * A key that does something rather than typing something has nothing to offer, whatever
     * is written in its corner. `&123` wears an ellipsis and means by it "there is more
     * behind this key than the page it takes you to" - which is a promise about the key, not
     * a character to type. Without this line, holding it types an ellipsis.
     */
    fun alternates(): String = when {
        key.action != null -> ""
        // A key whose hold does something has no characters behind it, whatever is drawn in
        // its corner: the comma's smiley is a picture of what the hold opens, not a character
        // to type.
        key.holdAction != null -> ""
        else -> (key.hint ?: "") + key.alternates
    }

    // ---------------------------------------------------------------- touch

    /**
     * Sliding along the space bar to move the caret.
     *
     * Putting the caret where you want it on a phone is genuinely hard - the target is a gap
     * between two letters, under a fingertip that covers eight of them - and this is the
     * answer every keyboard has settled on: the widest key on the board, which you are
     * already resting a thumb on, doubles as the control. Drag it and the caret follows.
     *
     * Two things make it feel right rather than annoying. It takes a deliberate sideways
     * movement to start ([SLIDE_SLOP_DP]), so an ordinary tap that wanders two pixels still
     * types a space. And once it has started, the space is *not* typed on release - the
     * gesture was a caret movement and ending it must not also leave a space behind, which is
     * the single most irritating way to get this wrong.
     */
    private var slideFrom = 0f

    /** How many characters the caret has already been moved this gesture. */
    private var slideSteps = 0

    private var sliding = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                light(true)
                longPressFired = false
                repeatCount = 0
                slideFrom = event.rawX
                slideSteps = 0
                sliding = false
                // The gesture is this key's for as long as the finger is down, wherever it
                // goes. A slide along the space bar leaves the key almost immediately and
                // must keep running; so must a hold that reaches up into the row of
                // alternates it opened. Neither is a scroll somebody else should be claiming.
                parent?.requestDisallowInterceptTouchEvent(true)
                KeyboardHaptics.key(this)
                listener?.onKeyPress(this)
                if (key.action == Action.BACKSPACE) {
                    postDelayed(repeat, FIRST_REPEAT_MS)
                } else if (alternates().isNotEmpty() || key.holdAction != null ||
                    key.action == Action.SHIFT
                ) {
                    postDelayed(holdCheck, holdMillis)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (key.action == Action.SPACE && slide(event)) return true
                if (longPressFired) {
                    // The hold is running, so the finger is allowed to leave the key - that
                    // is how you reach along the row it opened. The key stays lit as the
                    // thing the row belongs to, and the movement is passed on rather than
                    // being read as the finger giving up.
                    listener?.onKeyDrag(this, event.x, event.y)
                    return true
                }
                val inside = within(event)
                if (!inside) cancelHold()
                light(inside)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val inside = within(event)
                cancelHold()
                light(false)
                // A gesture, not a key. Typing a space at the end of it would undo the very
                // placing the gesture was for.
                if (sliding) {
                    sliding = false
                    return true
                }
                // A tap and the end of a hold are different events, and a hold must not also
                // type the letter that was being held.
                if (longPressFired) listener?.onKeyRelease(this)
                else if (inside) listener?.onKeyUp(this)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                light(false)
                sliding = false
                if (longPressFired) listener?.onKeyRelease(this)
                return true
            }

        }
        return super.onTouchEvent(event)
    }

    /**
     * The space bar has moved under the finger.
     *
     * Measured in raw screen coordinates rather than the view's own, because the view's are
     * relative to a key that is 3.9 units wide and the gesture routinely runs off the end of
     * it - and a finger that leaves the key should keep moving the caret, not stop dead at
     * the edge of a key nobody is looking at.
     *
     * Counted from where the finger started rather than from the last event, so the caret and
     * the finger never drift apart: the total travel decides the total movement, and rounding
     * cannot accumulate. Sliding back the way you came walks the caret back, which is what
     * makes overshooting recoverable.
     *
     * @return true when this is a caret slide and the ordinary key handling should be skipped.
     */
    private fun slide(event: MotionEvent): Boolean {
        val travel = event.rawX - slideFrom
        val density = resources.displayMetrics.density
        if (!sliding) {
            if (kotlin.math.abs(travel) < SLIDE_SLOP_DP * density) return false
            sliding = true
            cancelHold()
            // Nothing is being typed any more, so nothing should look pressed either.
            light(false)
        }
        val wanted = (travel / (SLIDE_STEP_DP * density)).toInt()
        if (wanted != slideSteps) {
            listener?.onCursorSlide(this, wanted - slideSteps)
            slideSteps = wanted
        }
        return true
    }

    /**
     * A hold has been recognised.
     *
     * Answered with the heavier of the two ticks. A hold is a different event from a tap -
     * it produces a different character, or locks shift - and it happens at a moment when
     * the finger has stopped moving and there is nothing else to say so. It is the same
     * waveform the framework fires by itself when a view claims a long press, which is what
     * makes holding a key here feel like holding anything else on the phone.
     */
    private fun fireLongPress() {
        longPressFired = true
        KeyboardHaptics.tap(this)
        listener?.onKeyLongPress(this)
    }

    private fun cancelHold() {
        removeCallbacks(holdCheck)
        removeCallbacks(repeat)
    }

    /**
     * How fast a held backspace runs.
     *
     * It accelerates, because a hold on backspace is almost always aimed at a whole word or
     * a whole line rather than at some particular number of characters, and a constant rate
     * fast enough to clear a line is too fast to stop on a word.
     */
    private fun repeatDelay(): Long =
        if (repeatCount < REPEAT_RAMP) SLOW_REPEAT_MS else FAST_REPEAT_MS

    private fun light(on: Boolean) {
        if (on == lit) return
        lit = on
        face.color = fillFor(on)
        // The glyph is a Drawable and carries its own colour, so unlike the text it has to
        // be told; [onFace] has just changed answer underneath it.
        tintGlyph()
        invalidate()
    }

    private fun within(event: MotionEvent): Boolean =
        event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelHold()
    }

    // ---------------------------------------------------------------- paint

    /**
     * What the key is painted.
     *
     * Both greys are the foreground colour at a low alpha over the background rather than
     * two fixed values, which is what lets one keyboard answer for both the Dark and the
     * Light setting: on Dark they come out the `#333333` and `#4D4D4D` the phone used, and
     * on Light they come out the matching pair the other way up. The pressed fill is one
     * more step along that same line, so it lightens on Dark and darkens on Light without
     * either being spelled out - and it works on the accent key too, which is not a grey.
     */
    @ColorInt
    private fun fillFor(pressed: Boolean): Int {
        // A pressed key sinks rather than lights up.
        //
        // It was the accent for a while, which is a defensible reading of what the phone did
        // and turned out to be wrong in the hand: at typing speed a saturated colour flashing
        // under every letter is the brightest thing on the screen, over and over, and the eye
        // goes to it instead of to the words. Going the other way - toward the page's own
        // background - reads as the key being pushed in, which is what a pressed key is meant
        // to look like, and is quiet enough to type in front of.
        //
        // Toward it, and not all the way to it. Landing on the background itself made a
        // pressed key black on the Dark setting, which is not a key that has been pushed in
        // but a hole where a key was. See [PRESSED_FILL_ALPHA], which stops short of both the
        // background and the gutter, so a key under a thumb is still plainly a key.
        if (pressed) return blend(PRESSED_FILL_ALPHA)
        return when (key.style) {
            Style.ACCENT -> palette.accent
            Style.LETTER -> blend(LETTER_FILL_ALPHA)
            Style.FUNCTION -> blend(FUNCTION_FILL_ALPHA)
        }
    }

    @ColorInt
    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    /**
     * What colour the letter, glyph or hint on this key is.
     *
     * White on an accent key, and otherwise the page's own foreground - which is the right
     * answer for a pressed key too, because a pressed key is painted the page's background
     * and the foreground is by definition what reads against it.
     */
    @ColorInt
    private fun onFace(): Int =
        if (key.style == Style.ACCENT && !lit) palette.onAccent() else palette.foreground

    private fun tintGlyph() {
        glyph?.setTint(onFace())
    }

    /**
     * How large a square this key's glyph is fitted into, as a fraction of one key's width.
     *
     * One number will not do. Every icon is fitted inside a square and centred, so what you
     * actually see is the square's size times however much of its own viewport the drawing
     * fills - and that differs a lot between them: the backspace tag runs nearly wall to
     * wall in its box where the shift arrow uses barely a third of the width of its own.
     * Fitting them all into the same square would draw the arrow half the size of the tag,
     * which is not what the phone did. These are the sizes that make the *drawn* glyphs come
     * out at the sizes measured off the phone.
     */
    private fun glyphScale(): Float = when (key.action) {
        Action.SHIFT -> SHIFT_GLYPH
        Action.BACKSPACE -> BACKSPACE_GLYPH
        Action.ENTER -> ENTER_GLYPH
        else -> ROUND_GLYPH
    }

    /**
     * What everything drawn on the key is sized against.
     *
     * A key's width, ordinarily - the whole keyboard is proportioned that way. But that only
     * holds while keys are taller than they are wide, which they are on every layout with ten
     * or twelve columns. The number pad has four, so its keys are half again wider than they
     * are tall, and sizing their digits off the width gave characters the size of a thumb.
     *
     * So a key that is wider than it is tall is measured more modestly. The trigger is the key
     * itself rather than which layout it belongs to, which means a wide key gets sensible text
     * wherever one turns up.
     */
    /**
     * What everything drawn on the key is sized against.
     *
     * A key's width, normally, because that is the unit the whole keyboard is written in. But
     * a key can be wider than it is tall - the keypads' keys are, and in landscape *every*
     * key is - and there the width says nothing about how much room there is to draw in. The
     * height is the constraint then, so the smaller of the two decides, and a fixed fraction
     * of the width is used for keys that are only slightly wide.
     */
    private fun textUnit(): Float = if (keyW > keyH) minOf(keyW * WIDE_KEY_SCALE, keyH) else keyW

    /**
     * How far below the middle a key's character sits.
     *
     * Optically rather than geometrically centred. A key with a mark in its top corner is not
     * a blank rectangle, and a letter placed at the true centre of one reads as sitting high,
     * because the eye takes the corner mark as part of the top of the key and centres what is
     * left. A few pixels down is what makes it look centred.
     *
     * Which is the whole of the reason, so a key with no corner mark does not get it. `abc` on
     * the emoji panel is the case that showed this up - a short, wide key with nothing in its
     * corner, where five pixels of a correction for something that is not there is simply five
     * pixels low. Geometry is already right when there is nothing to correct for.
     */
    private fun labelDrop(): Float =
        if (key.hint == null && hintGlyph == null) 0f
        else LABEL_DROP_DP * resources.displayMetrics.density

    /** What a corner mark is painted, whether it is a character or a drawing. */
    @ColorInt
    private fun hintColour(): Int =
        ColorUtils.setAlphaComponent(onFace(), (255 * HINT_ALPHA).toInt())

    /** How far the painted key sits inside the touchable bounds: half of the gutter. */
    private val inset get() = gap / 2f

    /**
     * Touchable area above the face that is deliberately not painted. See [Key.overhangTop].
     *
     * The space bar reaches up into the row above so that a thumb aiming at it and landing on
     * the `n` still gets a space. Everything drawn on the key measures from [faceTop] rather
     * than from the view's own top, so the key looks exactly where it always did while being
     * bigger than it looks.
     */
    var overhang = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The top of the painted key, which is not the top of the view. */
    private val faceTop get() = inset + overhang

    /** The middle of the painted key, which is what everything on it is centred on. */
    private val faceCentreY get() = (faceTop + (height - inset)) / 2f

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(inset, faceTop, width - inset, height - inset, face)
        if (keyW <= 0f) return

        glyph?.let {
            val side = (textUnit() * glyphScale()).toInt()
            val left = (width - side) / 2
            val top = (faceCentreY - side / 2f).toInt()
            it.setBounds(left, top, left + side, top + side)
            it.draw(canvas)
        }

        if (underline) {
            ink.color = onFace()
            val half = textUnit() * UNDERLINE_W / 2f
            val y = faceCentreY + textUnit() * UNDERLINE_DROP
            canvas.drawRect(
                width / 2f - half, y,
                width / 2f + half, y + textUnit() * UNDERLINE_H,
                ink
            )
        }

        val label = faceLabel()
        if (label.isNotEmpty()) {
            // The space bar says which language you are typing in, and says it the way the
            // corner marks do - the smaller face, the lighter grey - because it is the same
            // kind of statement. It is a label on the keyboard rather than a character the
            // key produces, and a key whose face reads `English` in the weight of a letter
            // looks like a key that types the word.
            val quiet = key.action == Action.SPACE
            ink.typeface = if (quiet) hintFont else labelFont
            ink.textSize = textUnit() * when {
                quiet -> HINT_TEXT
                key.action == null -> LABEL_TEXT
                else -> FUNCTION_LABEL_TEXT
            }
            ink.color = if (quiet) hintColour() else onFace()
            ink.textAlign = Paint.Align.CENTER
            // Centred on the ink the glyph actually puts on the key, not on the font's line
            // box, which carries a lot of empty space above a Segoe lowercase and would sit
            // every letter low.
            //
            // Both edges of that ink have to be used, not just its height. The bounds come
            // back relative to the baseline, so a quote - which lives entirely above the
            // baseline - has a box that is 12 tall but sits 30 above it, and centring by
            // height alone puts it near the top of the key. A comma, which hangs below,
            // comes out equally far low. Halving the sum of the two edges is what centres
            // the box itself, and it gives the same answer as the naive version for the
            // letters that do sit on the baseline.
            ink.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(
                label,
                width / 2f,
                faceCentreY - (bounds.top + bounds.bottom) / 2f + labelDrop(),
                ink
            )
        }

        hintGlyph?.let { mark ->
            val side = (textUnit() * HINT_GLYPH).toInt()
            val left = (inset + textUnit() * HINT_PAD).toInt()
            val top = (faceTop + textUnit() * HINT_PAD).toInt()
            mark.setTint(hintColour())
            mark.setBounds(left, top, left + side, top + side)
            mark.draw(canvas)
        }

        key.hint?.let { hint ->
            ink.typeface = hintFont
            ink.textSize = textUnit() * HINT_TEXT
            ink.color = hintColour()
            ink.textAlign = Paint.Align.LEFT
            ink.getTextBounds(hint, 0, hint.length, bounds)
            // Hung from the top of its ink rather than from its height.
            //
            // The same trap the label fell into, and worse here because the corner has no
            // room to spare. `bounds` is measured from the baseline, so a quote - which sits
            // thirty units above it and is only eight tall - would be placed by its height
            // and drawn thirty above that, off the top of the key entirely. Subtracting the
            // top edge instead puts the first ink exactly on the padding, whether the mark
            // is a digit, a quote, or an underscore that hangs below the line.
            canvas.drawText(
                hint,
                inset + textUnit() * HINT_PAD,
                faceTop + textUnit() * HINT_PAD - bounds.top,
                ink
            )
        }
    }

    /**
     * What is written across the key's middle.
     *
     * Empty for a key that carries a glyph instead, and empty for space - the space bar says
     * the language's name, but that is set as a label by the grid when more than one
     * language is on, and is otherwise blank the way the phone had it.
     */
    private fun faceLabel(): String = when {
        glyph != null -> ""
        key.action == Action.SPACE -> key.label.takeIf { it != " " } ?: ""
        key.action != null -> key.label
        shifted -> key.shifted ?: key.label.uppercase()
        else -> key.label
    }

    companion object {

        /** The two greys, as the alpha that produces them over black. See [fillFor]. */
        const val LETTER_FILL_ALPHA = 0.200f      // #333333 on Dark
        const val FUNCTION_FILL_ALPHA = 0.302f    // #4D4D4D on Dark

        /**
         * The third grey: a key with a thumb on it.
         *
         * Chosen to sit between the keyboard's ground and the paler of the two key fills -
         * 0.102 and 0.200 - so a pressed key is darker than any key beside it and still
         * lighter than the gutter around it. Both halves of that matter. Going past the
         * ground is how it ended up at the background and read as a hole; landing exactly on
         * the ground would be a key that merges into the board and reads as one too.
         *
         * Stated as an alpha over the page rather than as a colour, like the other two, which
         * is what makes one number answer for the Dark setting and the Light one: it is a
         * step toward the background either way, #242424 on black and #DBDBDB on white.
         */
        const val PRESSED_FILL_ALPHA = 0.14f

        /**
         * Text and glyph sizes, as fractions of one key's width.
         *
         * Most were measured off the reference rather than chosen: a lowercase `o` eighteen
         * pixels tall on a sixty-one pixel key, `&123` set across forty-four. Working back
         * from the drawn size to the font size is why they are not round numbers.
         *
         * The corner marks are the exception and are a fifth larger than the phone drew them.
         * They carry more here than they did there - the phone marked only the number row,
         * where this marks a symbol on every letter and the emoji panel on the comma - so
         * they have to be readable at a glance rather than merely present.
         */
        const val LABEL_TEXT = 0.57f
        const val FUNCTION_LABEL_TEXT = 0.36f
        const val HINT_TEXT = 0.36f

        /** See [glyphScale]. Four icons, four sizes, all landing on the measured drawing. */
        const val SHIFT_GLYPH = 0.86f
        const val BACKSPACE_GLYPH = 0.65f
        const val ENTER_GLYPH = 0.59f

        /** The emoji and globe keys, whose drawings do fill their own boxes. */
        const val ROUND_GLYPH = 0.56f

        /** The caps-lock bar: how wide, how far below the middle, how thick. */
        const val UNDERLINE_W = 0.34f
        const val UNDERLINE_DROP = 0.26f
        const val UNDERLINE_H = 0.05f

        /** Where the hint sits in from the key's top-left corner: eight pixels of sixty-one. */
        const val HINT_PAD = 0.12f

        /** See [labelDrop]. In dp, because it is a correction for the eye, not a proportion. */
        const val LABEL_DROP_DP = 5f

        /** A drawn corner mark, sized to sit where a hint character's ink would. */
        const val HINT_GLYPH = 0.31f

        /**
         * How much of its width a key wider than it is tall measures its contents against.
         *
         * A third off, which is what the number pad's digits needed. See [textUnit].
         */
        const val WIDE_KEY_SCALE = 0.67f

        /**
         * How faint the hint is.
         *
         * The phone's was `#808080` on a `#333333` key, which is not "grey" but a specific
         * amount of the key's own foreground laid over it - and stating it as the alpha that
         * produces that number is what makes it come out right on the Light theme too, where
         * `#808080` would be far too pale to read.
         */
        const val HINT_ALPHA = 0.38f

        /** What a hold is, before the user has said otherwise. See [holdMillis]. */
        const val DEFAULT_HOLD_MS = 350L


        /**
         * Backspace: the pause before it starts running, then the two rates.
         *
         * The fast rate is a ceiling as much as a speed. Every repeat is work in the
         * application being typed into as well as here, and forty milliseconds turned out to
         * be faster than some of them can keep up with.
         */
        const val FIRST_REPEAT_MS = 400L

        /**
         * How far sideways before a press on the space bar becomes a caret slide.
         *
         * Comfortably past the wobble in a tap and well short of a deliberate drag. Too small
         * and the space bar stops typing spaces reliably, which is the worst thing a keyboard
         * can do; too large and the gesture feels like it has to be argued for.
         */
        const val SLIDE_SLOP_DP = 10f

        /** And how far per character after that. About a fingertip's width of travel. */
        const val SLIDE_STEP_DP = 12f
        const val SLOW_REPEAT_MS = 95L
        const val FAST_REPEAT_MS = 55L
        const val REPEAT_RAMP = 8
    }
}
