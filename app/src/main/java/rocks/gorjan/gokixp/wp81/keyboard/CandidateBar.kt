package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The strip above the keys: suggestions in the middle, a glyph at each corner.
 *
 * This is the bar in the reference screenshot, and working out what it actually was settled a
 * design question. It looks like chrome - a keyboard-switch and a microphone on the left, a
 * move handle and a close cross on the right, and nothing in between - but that empty middle
 * is where Windows 10 Mobile put its word candidates. The strip *is* the suggestion bar. So
 * suggestions fill the middle and the four glyphs stay at the edges, and the keyboard keeps
 * the height the phone had rather than growing a fifth row to hold predictions.
 *
 * The first suggestion is always the literal text the user typed. That is not a detail either:
 * it is what makes an unwanted autocorrection one tap to reject, so the correction logic can
 * afford to be confident without ever being a trap.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /** A suggestion was chosen. The index is into whatever was last given to [setWords]. */
    var onWordPicked: ((Int) -> Unit)? = null

    /**
     * A suggestion was held and dragged onto the bin: forget it.
     *
     * The way out of a wrong thing the keyboard has learned, and it needs one. Learning from
     * what somebody types is what makes the keyboard theirs, and it means a single mistyped
     * word becomes a suggestion that comes back for months - this phone had `thank fod` in it
     * within a day. A dictionary that only ever grows is a dictionary that only ever gets
     * worse at the one thing it was added for.
     */
    var onWordForgotten: ((Int) -> Unit)? = null

    /** The microphone. Voice typing is a later phase; until then the glyph is not drawn. */
    var onVoice: (() -> Unit)? = null

    /**
     * The clipboard mark was tapped: show what is on there.
     *
     * Asked for rather than taken, always. The bar's own offer to paste the newest clip
     * straight in went, because a mark that pasted something it had not shown you is a guess
     * acted on - and once the list is one tap away there is nothing the shortcut saved that
     * was worth the surprise.
     */
    var onClipboard: (() -> Unit)? = null


    /** Set by the grid on every measure pass, so the bar is sized in the same units. */
    private var keyW = 0f
    private var gap = 0f

    private var words: List<String> = emptyList()

    /**
     * Which word is emphasised - the one a space would accept.
     *
     * Not necessarily the first. When the keyboard intends to autocorrect, the word it means
     * to use is the one shown in the accent, so that what is about to happen is visible
     * *before* it happens rather than being discovered afterwards.
     */
    private var emphasis = -1

    private var pressed = -1

    /**
     * Which suggestion is being dragged to the bin, or -1.
     *
     * A mode, and the only one this view has. While it is on, the bar stops being three words
     * and two glyphs and becomes one word under the finger and somewhere to drop it - because
     * a strip this short cannot show the word travelling *and* keep everything else in place
     * without the two overlapping in the middle.
     */
    private var dragging = -1

    /** Where the finger is, so the word being dragged can ride under it. */
    private var dragX = 0f

    /** Whether the finger is over the bin, so the drop is visible before it happens. */
    private var overBin = false

    /** Where the finger went down, to tell a hold apart from a scroll of the thumb. */
    private var downX = 0f
    private var downY = 0f

    /**
     * The hold that starts a drag.
     *
     * The platform's own long-press timeout rather than the keyboard's `hold to show symbols`
     * setting, which somebody may well have taken down to 150 ms. That is the right number
     * for a key - the alternates it opens are what the hold is *for*, and they are wanted
     * fast - and the wrong one here, where a hold is a rare, deliberate thing and the common
     * gesture on the same pixels is an ordinary tap that must never turn into one.
     */
    private val startDrag = Runnable { beginDrag() }

    /**
     * Whether dictation is running.
     *
     * Drawn as the accent, because that is what the accent means everywhere else on this
     * keyboard: the thing under your thumb, or the thing that is about to happen. A
     * microphone that looks identical whether or not it is listening is a microphone nobody
     * trusts - the whole question somebody has while dictating is "is this on".
     */
    var listening = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val face = Paint()
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private val font = ResourcesCompat.getFont(context, R.font.segoeui_regular)

    /** How far a finger may wander and still be holding still. */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Where each word was drawn, for hit testing. Rebuilt on every paint. */
    private val slots = ArrayList<Slot>(6)

    private class Slot(val index: Int, val left: Float, val right: Float)

    // Held from construction: SvgIcon re-parses its file on every call and has no cache.
    private val voiceGlyph: Drawable? = SvgIcon.fromAsset(context, "$ICONS/appbar.microphone.svg")
    private val pasteGlyph: Drawable? =
        SvgIcon.fromAsset(context, "$ICONS/appbar.clipboard.paste.svg")?.mutate()
    private val binGlyph: Drawable? =
        SvgIcon.fromAsset(context, "$ICONS/appbar.delete.svg")?.mutate()

    init {
        isClickable = true
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        voiceGlyph?.setTint(p.foreground)
        invalidate()
    }

    /**
     * Set by the host before it measures. No `requestLayout` from here: this is called during
     * the host's own measure pass, and asking for another one from inside it is how a view
     * ends up measuring on every frame forever.
     */
    fun setMetrics(keyW: Float, gap: Float) {
        this.keyW = keyW
        this.gap = gap
    }

    /**
     * The words to offer, and which of them a space would take.
     *
     * @param emphasised index of the word the keyboard would choose by itself, or -1 when it
     *   would leave the typed text alone.
     */
    fun setWords(words: List<String>, emphasised: Int) {
        this.words = words
        this.emphasis = emphasised
        invalidate()
    }

    fun clear() {
        message = null
        setWords(emptyList(), -1)
    }

    /**
     * A line of text across the bar, in place of suggestions.
     *
     * For the handful of things the keyboard has to say rather than offer - dictation being
     * unavailable, or having stopped for a reason worth knowing. It goes here because the bar
     * is the only surface the keyboard owns that the user is already looking at, and a toast
     * over somebody's application to say the microphone did not start is worse than the
     * silence it replaces.
     */
    fun setMessage(text: String?) {
        if (message == text) return
        message = text
        invalidate()
    }

    private var message: String? = null

    /** The bar is a fixed share of a key's height - tall enough to touch, short enough to spare. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (keyW > 0f) (keyW * HEIGHT).toInt() else 0
        setMeasuredDimension(width, height)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = slotAt(event.x)
                downX = event.x
                downY = event.y
                if (pressed != -1) {
                    KeyboardHaptics.key(this)
                    invalidate()
                }
                // Only a word can be dragged to the bin. The two glyphs are ways in to
                // something, not things the keyboard has an opinion about.
                if (pressed >= 0) postDelayed(startDrag, LONG_PRESS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) {
                    dragX = event.x
                    val onIt = event.x > width - keyW * GLYPH_SLOT
                    if (onIt != overBin) {
                        overBin = onIt
                        // Felt at the edge of the bin rather than only on release, so the
                        // drop is known to be armed without having to look.
                        KeyboardHaptics.key(this)
                    }
                    invalidate()
                    return true
                }
                // A thumb that has travelled is a thumb going somewhere else, not a hold.
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    removeCallbacks(startDrag)
                }
                val over = slotAt(event.x)
                if (over != pressed) {
                    pressed = over
                    removeCallbacks(startDrag)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDrag)
                if (dragging >= 0) {
                    val index = dragging
                    val dropped = overBin
                    endDrag()
                    // Released short of the bin, the word is *not* taken either. Somebody who
                    // held a suggestion and thought better of it meant to cancel, and
                    // inserting the word they were trying to delete is the worst available
                    // reading of that.
                    if (dropped) onWordForgotten?.invoke(index)
                    return true
                }
                val chosen = slotAt(event.x)
                pressed = -1
                invalidate()
                when {
                    chosen == VOICE -> onVoice?.invoke()
                    chosen == PASTE -> onClipboard?.invoke()
                    chosen >= 0 -> onWordPicked?.invoke(chosen)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                endDrag()
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * The hold has fired: the word comes off the bar and the bin appears.
     *
     * The parent is asked to keep its hands off the rest of the gesture. The bar lives inside
     * the keyboard's own view group, and a drag that wandered a few pixels vertically without
     * this would be taken for a scroll by whatever is above it and the finger would be lost
     * halfway to the bin.
     */
    private fun beginDrag() {
        if (pressed < 0) return
        dragging = pressed
        pressed = -1
        dragX = downX
        overBin = false
        parent?.requestDisallowInterceptTouchEvent(true)
        KeyboardHaptics.tap(this)
        invalidate()
    }

    private fun endDrag() {
        if (dragging < 0) return
        dragging = -1
        overBin = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    /** What the word being dragged says, or null when nothing is being dragged. */
    private fun draggedWord(): String? = words.getOrNull(dragging)

    /** Which word, or which glyph, is at [x]. The glyph slot uses a negative index. */
    private fun slotAt(x: Float): Int {
        if (x > width - keyW * GLYPH_SLOT) return VOICE
        // The clipboard takes the slot at the near end - the one the bar has always held
        // empty so that three suggestions sit centred between the two ends rather than being
        // pushed off centre by the microphone alone. So it costs the suggestions nothing.
        if (x < keyW * GLYPH_SLOT) return PASTE
        for (slot in slots) if (x >= slot.left && x < slot.right) return slot.index
        return -1
    }

    // ---------------------------------------------------------------- paint

    override fun onDraw(canvas: Canvas) {
        if (keyW <= 0f) return

        // The bar sits on the same ground as the keyboard, not on the keys' own grey. It is
        // the gap the phone left above the keys, with things in it.
        face.color = ColorUtils.blendARGB(palette.background, palette.foreground, GROUND_ALPHA)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)

        val side = keyW * GLYPH_SLOT

        // A drag takes the bar over. Everything else steps aside for it - the two glyphs
        // included - because the word has to be seen travelling and there is one row to do it
        // in. What is left is the word under the finger and the bin it is going to.
        draggedWord()?.let { word ->
            drawGlyph(canvas, binGlyph, width - side / 2f, overBin)
            ink.typeface = font
            ink.textSize = keyW * TEXT
            ink.textAlign = Paint.Align.CENTER
            ink.color = if (overBin) palette.accent else palette.foreground
            // Held clear of the bin so the word never sits on top of what it is aimed at.
            val limit = width - side
            canvas.drawText(word, dragX.coerceIn(side, limit), baseline(), ink)
            return
        }

        drawGlyph(canvas, voiceGlyph, width - side / 2f, pressed == VOICE || listening)
        // The clipboard at the other end. Always drawn, whatever is on the clipboard and
        // whatever the middle of the bar is up to: it is a way in to something, like the
        // microphone, and a control that comes and goes is one nobody learns the place of.
        // An empty clipboard is something for the list to say, not a reason to hide the way
        // of asking - see the message the host puts up when there is nothing to show.
        drawGlyph(canvas, pasteGlyph, side / 2f, pressed == PASTE)

        slots.clear()

        message?.let { text ->
            ink.typeface = font
            ink.textSize = keyW * TEXT
            ink.textAlign = Paint.Align.CENTER
            ink.color = palette.foregroundSubtle
            canvas.drawText(text, (width - side) / 2f, baseline(), ink)
            return
        }

        if (words.isEmpty()) return

        ink.typeface = font
        ink.textSize = keyW * TEXT
        ink.textAlign = Paint.Align.CENTER

        // Three fixed columns, not one per word measured to its own text.
        //
        // The middle one is the keyboard's best guess, and "in the middle" has to mean the
        // middle of the bar or it means nothing - so the columns are equal and fixed rather
        // than sized to fit, and a thumb learns where the good answer lives. The left is
        // always what was actually typed, so rejecting a correction is the same movement
        // every time; the right is the runner-up.
        // The microphone sits on the right, and the same width is held empty on the left, so
        // that the three suggestions are centred on the bar rather than pushed off-centre by
        // it. The middle column is the keyboard's best guess, and "middle" has to mean the
        // middle of the screen for a thumb to learn where it is.
        val available = width - side * 2f
        val column = available / SLOTS

        for (i in 0 until SLOTS) {
            val word = wordFor(i) ?: continue
            val index = indexFor(i)
            val left = side + i * column
            slots.add(Slot(index, left, left + column))

            if (index == pressed) {
                face.color = palette.accent
                canvas.drawRect(left, 0f, left + column, height.toFloat(), face)
            }

            ink.color = when {
                index == pressed -> palette.onAccent()
                // The correction a space would apply, and only ever that. See the note in
                // the service where the emphasis is decided.
                index == emphasis -> palette.accent
                else -> palette.foreground
            }
            val text = TextUtils.ellipsize(word, ink, column - keyW * WORD_PADDING, TextUtils.TruncateAt.END)
                .toString()
            canvas.drawText(text, left + column / 2f, baseline(), ink)

            // A hairline between suggestions. The phone separated them rather than boxing
            // each one, which on a strip this short is the difference between a row of words
            // and a row of buttons.
            if (i > 0 && wordFor(i - 1) != null) {
                face.color = ColorUtils.blendARGB(palette.background, palette.foreground, DIVIDER_ALPHA)
                val top = height * DIVIDER_INSET
                canvas.drawRect(left, top, left + keyW * DIVIDER_WIDTH, height - top, face)
            }
        }
    }

    /**
     * Which word goes in column [slot], or null for an empty one.
     *
     * A single word - the text typed, with nothing to suggest - goes in the middle rather
     * than sitting alone against the left edge, which reads as the other two having failed
     * to load. Two or three fill from the left, which puts the best guess in the middle
     * either way.
     */
    /**
     * The one line every word on the bar sits on.
     *
     * From the font's own metrics rather than from the ink of the word being drawn, and that
     * is the whole point. Measuring each word centres each word - which is right for a single
     * character alone on a key, and wrong for a row of words, because the ink of `your` runs
     * below the baseline where the ink of `house` does not. Centring both on their own boxes
     * puts them at two different heights, so the row appears to bounce as the suggestions
     * change under it.
     *
     * A font's ascent and descent are the same for every word set in it, so this is one line,
     * and the words sit on it the way words on a line do.
     */
    private fun baseline(): Float {
        val metrics = ink.fontMetrics
        return height / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    private fun wordFor(slot: Int): String? =
        if (words.size == 1) words.firstOrNull().takeIf { slot == 1 } else words.getOrNull(slot)

    /** Which entry of [words] column [slot] is showing, for the tap to report. */
    private fun indexFor(slot: Int): Int = if (words.size == 1) 0 else slot

    private fun drawGlyph(canvas: Canvas, glyph: Drawable?, centreX: Float, lit: Boolean) {
        val drawable = glyph ?: return
        if (lit) {
            face.color = palette.accent
            val half = keyW * GLYPH_SLOT / 2f
            canvas.drawRect(centreX - half, 0f, centreX + half, height.toFloat(), face)
        }
        drawable.setTint(if (lit) palette.onAccent() else palette.foreground)
        val size = (keyW * GLYPH - GLYPH_TRIM_DP * resources.displayMetrics.density).toInt()
        val left = (centreX - size / 2f).toInt()
        val top = (height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    // Not private: the keyboard has to know how tall the bar is to work out whether the two
    // of them fit on the screen together. See `KeyboardView.TOTAL_UNITS`.
    internal companion object {

        /**
         * The microphone's slot, as a negative index so that words can use 0 upwards.
         *
         * There was a close cross at the other end, because the reference screenshot has one -
         * but the keyboard's own chevron in the navigation bar already does exactly that, two
         * centimetres below it, on every screen. Two ways to dismiss the keyboard is not twice
         * as useful; it is one of them taking room from the suggestions, which are what the
         * bar is for.
         */
        const val VOICE = -2

        /** The clipboard's slot, at the other end and on the same principle as [VOICE]. */
        const val PASTE = -3

        /**
         * All as fractions of one key's width, like everything else in the keyboard.
         *
         * [HEIGHT] is measured off the phone and not guessed: the reference keyboard's header
         * strip is 77 pixels against a 61 pixel key, which is 1.26. Guessing it at half that
         * made a bar of about 25dp - barely half Android's minimum touch target, and the
         * first thing anyone said about it was that they could not hit anything on it. At
         * 1.26 it comes out around 51dp, which clears the minimum with a little to spare.
         *
         * [MIN_SLOT] is the companion rule. Sizing each suggestion to its own text means a
         * short word gets a narrow target however tall the bar is, so `a` or `an` would still
         * be a poke at a sliver. Both it and [GLYPH_SLOT] are set so that the *narrower*
         * dimension of every target clears 48dp, not merely its area - a tall thin strip is
         * still a thin strip to aim at.
         */
        const val HEIGHT = 1.26f
        /**
         * The suggestions, two points larger than the hint in a key's corner.
         *
         * As a fraction of a key's width like everything else, so it scales with the
         * keyboard - which on this phone puts it at about 16sp. It is not the corner mark's
         * size any more because it is not that kind of text: a hint is a label on something
         * you can already see, and a suggestion is a word you are being asked to read and
         * decide about at typing speed.
         */
        const val TEXT = 0.409f

        /**
         * The microphone, at twice the size it started.
         *
         * It is the only thing on the bar that is not a word, so it is the only thing that
         * can be found without reading - which is worth having it drawn large enough to be
         * seen out of the corner of an eye rather than sized like a piece of punctuation.
         */
        const val GLYPH = 1.0f

        /**
         * Taken off both glyphs, in real millimetres rather than as a fraction.
         *
         * A fraction would have been the house style, and it is the wrong tool here: this is
         * not a proportion anybody chose, it is four density-independent pixels trimmed off a
         * mark that was slightly too heavy for the row of words beside it. The slot they sit
         * in ([GLYPH_SLOT]) is untouched, so what is drawn gets smaller while what can be
         * tapped stays exactly where it was.
         */
        const val GLYPH_TRIM_DP = 4f
        const val GLYPH_SLOT = 1.25f
        const val WORD_PADDING = 0.22f

        /**
         * How many suggestions the bar shows.
         *
         * Three. More than that and none of them has a place a thumb can learn, which is
         * worth more than the fourth-best guess ever is.
         */
        const val SLOTS = 3

        const val GROUND_ALPHA = 0.102f
        const val DIVIDER_ALPHA = 0.22f
        const val DIVIDER_WIDTH = 0.014f
        const val DIVIDER_INSET = 0.22f

        const val ICONS = "custom_icons_8"

        /** How long a suggestion has to be held before it comes off the bar. */
        val LONG_PRESS = ViewConfiguration.getLongPressTimeout().toLong()
    }
}
