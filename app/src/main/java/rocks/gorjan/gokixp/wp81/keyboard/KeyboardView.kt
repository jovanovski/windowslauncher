package rocks.gorjan.gokixp.wp81.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The field of keys.
 *
 * Everything is sized from the width of a single key, which is the layout's share of
 * whatever width the keyboard has been given. The phone's own proportions - a gutter a
 * tenth of a key, a key half again as tall as it is wide - then hold at any screen size,
 * which is what lets a layout drawn for a 480-wide phone land correctly on hardware that is
 * a good deal wider and a great deal taller. It is the calculator's grid with two things
 * generalised: the column count is the layout's rather than a constant, because Macedonian
 * needs twelve across where English needs ten, and a key's width is a fraction rather than
 * a whole number, because the phone's enter key is 1.4 keys wide and nothing else will do.
 *
 * The height is *not* a free parameter. An input method is asked how tall it wants to be and
 * is given it, so the grid asks for exactly the height its rows need at the width it has.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private var palette: WP81Palette
) : ViewGroup(context) {

    var listener: KeyView.Listener? = null
        set(value) {
            field = value
            grids.values.forEach { grid -> grid.forEach { it.listener = value } }
        }

    private var layout: KeyboardLayout = Layouts.EN_QWERTY
    /**
     * The keys of the layout on screen. Not the only ones attached - see [grids].
     */
    private var keys: List<KeyView> = emptyList()

    /**
     * One built grid per layout, kept alive and simply hidden.
     *
     * Switching to `&123` used to rebuild the grid, and that was the delay: thirty-four views
     * destroyed and thirty-four made, and then a measure and layout pass before any of them
     * had a size to be tapped. Keeping both sets means the symbols are already built, already
     * measured and already in position - the switch is two loops setting `visibility` and
     * nothing else, and the first symbol can be tapped in the same instant the page appears.
     *
     * The cost is a few dozen small views for layouts the user has actually visited, which
     * against a keyboard that is already holding two dictionaries is not worth counting.
     * Filled lazily, so a language never selected never builds one.
     */
    private val grids = mutableMapOf<String, List<KeyView>>()

    /**
     * The width each grid was last laid out at.
     *
     * A hidden grid keeps the bounds it had when it was last visible, which is exactly what
     * makes the switch free - but only while those bounds are still right. Rotate the phone,
     * or open the keyboard in a window of a different size, and they are not. This is how
     * [rebuild] knows whether it can skip the layout pass or has to ask for one.
     */
    private val laidOutAt = mutableMapOf<String, Int>()

    /** The geometry those bounds were measured against, so a change to it can void them. */
    private var lastKeyW = 0f
    private var lastKeyH = 0f

    /** Measured once per pass and read by everything that sizes itself. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f

    /**
     * How much of the bottom of this view the system is going to draw on top of.
     *
     * An input method is given the bottom of the screen, and the system then draws its own
     * strip over the bottom of that - the back chevron and the keyboard-switcher globe.
     * Nothing warns about the overlap: the keyboard simply gets a window reaching the very
     * bottom and finds its last row underneath, which on this layout is the space bar and
     * the enter key, the two that can least afford it.
     *
     * The inset the window reports is **not** enough on its own. With gesture navigation it
     * describes the gesture handle, which is the shorter thing; the strip the system puts up
     * while an input method is showing is a full navigation bar's height, and measuring it
     * on a Pixel gave 80 pixels reported against about 146 actually painted on. So the
     * larger of the two is reserved, and only when there is a bar there at all.
     *
     * The keys are laid out above whatever is reserved and the ground colour runs underneath,
     * which is also what the phone did: the strip below the keys was keyboard, not app.
     */
    private var bottomInset = 0

    private var shifted = false

    /**
     * Whether a row's own [Row.heightScale] is honoured or ignored.
     *
     * The layouts say the bottom row is three-quarters the height of a letter row, which is
     * what the phone did and what gives the app above a little more room. Turning this off
     * makes every row the same height without the layouts having to know anything about it -
     * the declaration of intent stays where it belongs and this decides whether to act on it.
     */
    var shortBottomRow: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** How long a hold takes, pushed down to every key. See [KeyView.holdMillis]. */
    var holdMillis: Long = KeyView.DEFAULT_HOLD_MS
        set(value) {
            field = value
            grids.values.forEach { grid -> grid.forEach { it.holdMillis = value } }
        }

    // ------------------------------------------------------------------ alternates

    /**
     * The row a hold opens.
     *
     * Lives in a window of its own rather than being painted in here, because it belongs
     * above the key being held and above the top row is outside this view altogether. See
     * [AlternatesPopup].
     */
    private val popup = AlternatesPopup(context, palette)

    /** Which key's row is open, so a stray drag from another key cannot move the selection. */
    private var popupKey: KeyView? = null

    /** Opens the row of alternates a hold on [key] offers, if it has any. */
    fun showAlternates(key: KeyView) {
        val chars = key.alternates()
        if (chars.isEmpty() || keyW <= 0f) return
        popupKey = key
        popup.show(this, key, chars, keyW, keyH, gap)
    }

    fun moveAlternates(key: KeyView, x: Float) {
        if (popupKey !== key) return
        popup.moveTo(key.left + x, keyW, gap)
    }

    /** What the finger was over when it came off, or null if no row was open. */
    fun takeAlternate(): Char? = if (popupKey == null) null else popup.selected

    fun hideAlternates() {
        popupKey = null
        popup.dismiss()
    }

    /**
     * A window outlives the view that opened it unless something says otherwise.
     *
     * The keyboard's view is thrown away and rebuilt often - every layout change makes a new
     * one - and a row still showing at that moment would be left on screen with nothing
     * behind it and no way to dismiss it.
     */
    override fun onDetachedFromWindow() {
        hideAlternates()
        super.onDetachedFromWindow()
    }

    /**
     * The glyphs, parsed once and shared.
     *
     * [SvgIcon.fromAsset] re-reads and re-parses the file on every call - there is no cache
     * behind it - so a keyboard that asked per key, or worse per paint, would be parsing XML
     * in the input path. A handful of them, read when the view is built, and the keys are
     * handed the results.
     */
    private val glyphs = mutableMapOf<Action, Drawable?>()

    /**
     * The same drawings again, for corner marks.
     *
     * A separate cache rather than sharing [glyphs], because a `Drawable` carries its own
     * bounds and tint: one instance on a key's face and in another key's corner would be
     * drawn at whichever size and colour was set last. `SvgIcon` has no `ConstantState` to
     * copy from either, so `newDrawable()` quietly returns null - which is why the comma's
     * smiley did not appear at all the first time.
     */
    private val hintGlyphs = mutableMapOf<Action, Drawable?>()

    init {
        setBackgroundColor(groundColour())
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val reported = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // No bar, nothing to keep clear of. A reported zero is the one case where
            // reserving the frame height anyway would leave a band of dead keyboard.
            val next = if (reported <= 0) 0 else maxOf(reported, navigationBarFrameHeight())
            if (next != bottomInset) {
                bottomInset = next
                requestLayout()
            }
            insets
        }
    }

    // ---------------------------------------------------------------- contents

    fun setLayout(next: KeyboardLayout) {
        if (next.id == layout.id && keys.isNotEmpty()) return
        layout = next
        rebuild()
    }

    fun currentLayout(): KeyboardLayout = layout

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(groundColour())
        glyphs.clear()
        hintGlyphs.clear()
        popup.applyPalette(p)
        // Every grid, not just the one on screen: a hidden one is one tap from being shown
        // and would come back in the old colours.
        grids.values.forEach { grid ->
            grid.forEach { key ->
                key.applyPalette(p)
                key.glyph = key.key.action?.let { glyphFor(it) }
                key.hintGlyph = if (key.key.hint == null) {
                    key.key.holdAction?.let { hintGlyphFor(it) }
                } else {
                    null
                }
            }
        }
        invalidate()
    }

    /**
     * Shift, in all three of its states.
     *
     * Off, on for one letter, and locked. The keys have to be told because their faces
     * change, and the shift key itself has to be told twice over: it takes the accent while
     * shift is on at all, and a bar under its arrow only once it is locked.
     */
    fun setShiftState(on: Boolean, locked: Boolean) {
        shifted = on
        shiftLocked = locked
        keys.forEach { it.setShifted(on) }
        applyShiftStyle()
    }

    /**
     * Paints the shift key for the state shift is in.
     *
     * Its own function because it has to run again whenever a grid is shown as well as
     * whenever shift changes: the accent is put on by replacing the key, so a grid hidden
     * while shift was on comes back still wearing it.
     */
    private fun applyShiftStyle() {
        val shift = keys.firstOrNull { it.key.action == Action.SHIFT } ?: return
        val style = if (shifted) Style.ACCENT else Style.FUNCTION
        if (shift.key.style != style) {
            shift.key = shift.key.copy(style = style)
            shift.applyPalette(palette)
        }
        shift.underline = shiftLocked
    }

    /** Whether shift is locked rather than one-shot, so [applyShiftStyle] can redraw it. */
    private var shiftLocked = false

    /**
     * Relabels the enter key for whatever the field being typed into has asked for.
     *
     * A field that wants a search says so, one that wants to send gets the accent and the
     * word, and a note gets the return arrow - the same key doing three jobs, which is the
     * phone's behaviour as much as it is Android's.
     */
    fun setEnterKey(label: String?, accent: Boolean) {
        val view = keys.firstOrNull { it.key.action == Action.ENTER } ?: return
        view.key = view.key.copy(
            label = label ?: "",
            style = if (accent) Style.ACCENT else Style.FUNCTION
        )
        view.glyph = if (label == null) glyphFor(Action.ENTER) else null
        view.applyPalette(palette)
    }

    /**
     * Relabels the `&123` key, which says `abc` once it has taken you to a symbol page.
     *
     * The symbol pages share the letters' bottom row rather than declaring one of their own,
     * because every other key on it - emoji, comma, space, full stop, enter - is identical on
     * all four layouts, and a second copy would be five keys of duplication kept in step by
     * hand for the sake of one label.
     */
    fun setSymbolsLabel(label: String) {
        val view = keys.firstOrNull { it.key.action == Action.SYMBOLS } ?: return
        view.key = view.key.copy(
            label = label,
            // The ellipsis belongs to `&123`. On the way back it would be a mark with
            // nothing behind it.
            hint = if (label == "&123") "\u2026" else null
        )
        view.invalidate()
    }

    /**
     * Sets what the contextual key types - the one beside the space bar.
     *
     * See [Key.contextual]. Its shifted form is set to the same character, because these are
     * punctuation rather than letters and there is nothing for shift to do to them.
     */
    fun setContextualKey(character: String) {
        val view = keys.firstOrNull { it.key.contextual } ?: return
        if (view.key.output == character) return
        val plain = character == ","
        view.key = view.key.copy(
            output = character,
            label = character,
            shifted = character,
            // The emoji panel rides on this key's hold while it is the comma. In a field that
            // wanted a slash or an at sign it is not a comma any more, and a hold offering
            // emoji on a key that now types `/` is a hold nobody would look for.
            holdAction = if (plain) Action.EMOJI else null
        )
        view.hintGlyph = if (plain) hintGlyphFor(Action.EMOJI) else null
        view.invalidate()
    }

    /**
     * What the space bar says: the name of the language being typed.
     *
     * The phone put it there and it earns its place - it is the only thing on screen that
     * says which of several keyboards you are on, and the moment somebody turns on a second
     * language it is the difference between knowing and guessing.
     *
     * Remembered rather than merely applied, because the space bar outlives the layout it is
     * currently part of: the symbol pages have one too and belong to no language, so the name
     * has to survive a trip to `&123` and back. See [applySpaceLabel], called from [rebuild].
     */
    private var spaceLabel = ""

    fun setSpaceLabel(label: String) {
        if (label == spaceLabel) return
        spaceLabel = label
        applySpaceLabel()
    }

    private fun applySpaceLabel() {
        val view = keys.firstOrNull { it.key.action == Action.SPACE } ?: return
        // A single space rather than an empty string when there is no name: the layouts spell
        // the blank key that way, and [KeyView.faceLabel] reads that exact value as "blank".
        view.key = view.key.copy(label = spaceLabel.ifEmpty { " " })
        view.invalidate()
    }

    /**
     * Points the grid at whatever [layout] now is.
     *
     * The views are **reused**, not rebuilt. Tearing the grid down and making another one is
     * the obvious way to change layout and it is the reason `&123` felt slow: thirty-four
     * views destroyed and thirty-four constructed, every one of them looking up two fonts and
     * allocating its paints, and - worse than the cost - a moment in which the keys the user
     * was about to press did not exist, because a view added this frame has no size until the
     * next layout pass. A tap that arrived in that gap hit nothing at all.
     *
     * Rebinding avoids both. The views stay attached and keep their bounds, so a symbol
     * tapped the instant the page appears lands on a key that is already there, and the only
     * work left is the measure pass that any layout change needs anyway.
     */
    /**
     * Puts the grid for [layout] on screen, building it the first time and only then.
     *
     * The whole of the switch, in the ordinary case, is hiding one list of views and showing
     * another. Nothing is created, nothing is measured, and the layout pass is skipped
     * outright when the grid coming back was last laid out at the width it is coming back to
     * - which it was, unless the keyboard has changed size since.
     */
    private fun rebuild() {
        keys.forEach { it.visibility = GONE }

        val fresh = layout.id !in grids
        keys = grids.getOrPut(layout.id) {
            layout.rows.flatMap { it.keys }.map { key ->
                KeyView(context, key, palette).also {
                    it.listener = listener
                    it.holdMillis = holdMillis
                    addView(it)
                }
            }
        }

        keys.forEach { view ->
            view.visibility = VISIBLE
            // Cleared rather than assumed. A grid that was hidden mid-press - which is what
            // happens to the letters when `&123` is released over them - would otherwise come
            // back still lit, or still believing a hold had fired on it.
            view.bind(view.key)
            view.setShifted(shifted)
            view.glyph = view.key.action?.let { glyphFor(it) }
            view.hintGlyph = if (view.key.hint == null) {
                view.key.holdAction?.let { hintGlyphFor(it) }
            } else {
                null
            }
        }

        applySpaceLabel()
        applyShiftStyle()

        // A grid coming back at the width it left at is already in the right place, and
        // asking for a layout pass would be asking for the delay this exists to remove.
        if (!fresh && laidOutAt[layout.id] == width) return

        // Otherwise it needs laying out, and **now** rather than on the next frame. A view
        // that has just been added has no bounds until it is laid out, and a key with no
        // bounds cannot be pressed - so a grid shown and tapped inside the same frame would
        // swallow that tap, which during fast typing is not a hypothetical. Waiting for the
        // frame is the very delay this whole arrangement exists to remove; it would simply
        // have moved from every switch to the first one.
        if (width <= 0 || height <= 0) {
            requestLayout()
            return
        }
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        if (measuredHeight == height) {
            layout(left, top, right, bottom)
        } else {
            // The keyboard is a different height for this layout - a number pad is shorter -
            // so the window has to resize and only a real pass can do that.
            requestLayout()
        }
    }



    /** A second instance of a glyph, for a corner mark. See [hintGlyphs]. */
    private fun hintGlyphFor(action: Action): Drawable? = hintGlyphs.getOrPut(action) {
        when (action) {
            Action.EMOJI -> SvgIcon.fromAsset(context, "$ICONS/appbar.smiley.happy.svg")
            Action.SETTINGS -> SvgIcon.fromAsset(context, "$ICONS/appbar.settings.svg")
            else -> null
        }
    }

    /**
     * The glyph a function key wears, or null for the ones that are written rather than drawn.
     *
     * All but one come out of the phone's own icon set, which is already in the assets and
     * already the right weight. Enter is the exception and is a vector of its own: the set
     * has no return arrow, having never needed one.
     */
    private fun glyphFor(action: Action): Drawable? = glyphs.getOrPut(action) {
        val drawable = when (action) {
            Action.SHIFT -> SvgIcon.fromAsset(context, "$ICONS/appbar.arrow.up.svg")
            Action.BACKSPACE -> context.getDrawable(R.drawable.wp81_calc_backspace)
            Action.ENTER -> context.getDrawable(R.drawable.wp81_kb_enter)
            Action.EMOJI -> SvgIcon.fromAsset(context, "$ICONS/appbar.smiley.happy.svg")
            Action.SETTINGS -> SvgIcon.fromAsset(context, "$ICONS/appbar.settings.svg")
            else -> null
        }
        drawable?.mutate()
    }

    // ---------------------------------------------------------------- geometry

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        // A row of twelve keys is a row of *narrower* keys, not a shorter keyboard.
        //
        // Height, and the gutters, come from a fixed reference of ten columns rather than
        // from whatever the current layout has. Deriving them from the layout's own key width
        // - which is the obvious thing, and was what this did - made the whole Macedonian
        // keyboard shrink by a fifth: shorter keys, shorter rows, a shorter keyboard, and the
        // app above it reflowing every time the language changed. Only the width of a key
        // should answer to how many of them there are.
        val reference = verticalUnit(resources, w, REFERENCE_COLUMNS)
        gap = reference * GAP
        keyH = reference * KEY_ASPECT
        // The keys still fill the width, whatever the height was capped to. That is the whole
        // shape of a landscape keyboard: as wide as the screen, no taller than it needs.
        keyW = (w - layout.columns * gap) / layout.columns

        // The cached grids are holding bounds measured against the old geometry. Rotating is
        // the case: a grid laid out in portrait and shown again in landscape would come back
        // in portrait's positions, because [rebuild] trusts [laidOutAt] to say otherwise.
        if (keyW != lastKeyW || keyH != lastKeyH) {
            laidOutAt.clear()
            lastKeyW = keyW
            lastKeyH = keyH
        }

        val h = contentHeight() + bottomInset

        var index = 0
        for (row in layout.rows) {
            val rowH = keyH * scaleOf(row)
            for (key in row.keys) {
                val view = keys.getOrNull(index++) ?: continue
                view.setMetrics(keyW, rowH, gap)
                // Taller than it is drawn, where the key asks to be. See [Key.overhangTop].
                val overhang = keyH * key.overhangTop
                view.overhang = overhang
                view.measure(
                    MeasureSpec.makeMeasureSpec(spanWidth(key.span) + gap.toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        (rowH + overhang).toInt() + gap.toInt(), MeasureSpec.EXACTLY
                    )
                )
            }
        }
        setMeasuredDimension(w, h)
    }

    /** What a key of [span] units is painted at, gutters between its units included. */
    private fun spanWidth(span: Float): Int = (span * keyW + (span - 1f) * gap).toInt()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var index = 0
        var y = gap / 2f
        for (row in layout.rows) {
            var x = gap / 2f + row.indentStart * (keyW + gap)
            val rowH = keyH * scaleOf(row)
            for (key in row.keys) {
                val view = keys.getOrNull(index++) ?: continue
                val painted = spanWidth(key.span)
                // The view is half a gutter larger than the key on every side, so it is
                // laid out shifted back by that much and given that much extra.
                // Started higher by whatever it overhangs, and ending where it always did,
                // so the face lands in the same place while the target reaches further up.
                val overhang = keyH * key.overhangTop
                view.layout(
                    (x - gap / 2f).toInt(),
                    (y - gap / 2f - overhang).toInt(),
                    (x - gap / 2f).toInt() + painted + gap.toInt(),
                    (y - gap / 2f).toInt() + rowH.toInt() + gap.toInt()
                )
                x += painted + gap
            }
            y += rowH + gap
        }
        // What lets the next switch back to this grid skip the layout pass entirely: these
        // bounds are good for as long as the keyboard is this wide. See [laidOutAt].
        laidOutAt[layout.id] = r - l
    }

    /**
     * How tall the system's own bar at the foot of the keyboard is.
     *
     * Read out of the framework's resources by name because there is no public constant for
     * it - `navigation_bar_frame_height` is the dimension the system lays that strip out to.
     * Looking it up by name is doing what the platform does internally rather than guessing,
     * and if a device does not have it, 48dp is the value it has had for as long as there
     * has been a navigation bar to measure.
     */
    private fun navigationBarFrameHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        if (id != 0) {
            val fromSystem = resources.getDimensionPixelSize(id)
            if (fromSystem > 0) return fromSystem
        }
        return (FALLBACK_BAR_DP * resources.displayMetrics.density).toInt()
    }

    /**
     * What the keyboard sits on.
     *
     * Not [WP81Palette.background]. The phone's keyboard ground was a shade off black, a
     * step darker than the keys rather than the page behind them, which is what stops a
     * field of dark keys from reading as holes cut in the screen.
     */
    private fun groundColour(): Int =
        androidx.core.graphics.ColorUtils.blendARGB(palette.background, palette.foreground, GROUND_ALPHA)

    /**
     * How tall the rows come to, gutters included and the navigation bar excluded.
     *
     * Not simply four times a row any more: the bottom row is shorter than the letters. Asked
     * for by anything that has to be exactly as tall as the keyboard - the emoji panel, which
     * replaces it, and which would otherwise resize the window every time it opened.
     */
    fun contentHeight(): Int =
        layout.rows.sumOf { (keyH * scaleOf(it) + gap).toDouble() }.toInt()

    /** How tall a row is, allowing for the setting. See [shortBottomRow]. */
    private fun scaleOf(row: Row): Float = if (shortBottomRow) row.heightScale else 1f

    /** What the keys currently measure, for anything that has to line up with them. */
    fun unitWidth(): Float = keyW
    fun keyHeight(): Float = keyH
    fun gutter(): Float = gap
    fun bottomReserved(): Int = bottomInset

    companion object {

        /** The gutter, as a fraction of one key's width. Measured off the phone: 6.5 of 61. */
        const val GAP = 0.107f

        /**
         * How much taller than wide a key is.
         *
         * The one proportion that is nothing like the calculator's, whose keys are wider
         * than they are tall. A keyboard's are the other way up because ten of them have to
         * fit across a screen that a calculator only puts four on.
         */
        const val KEY_ASPECT = 1.56f

        /** `#1A1A1A` on Dark. See [groundColour]. */
        private const val GROUND_ALPHA = 0.102f

        private const val ICONS = "custom_icons_8"

        /**
         * One key's width at a given keyboard width, shared so nothing has to copy it.
         *
         * The candidate bar sizes its text and its glyph slots in key widths like everything
         * else, and it is measured before the keys are - so it cannot ask them and must be
         * able to work it out. Two copies of this expression drifting apart would show up as
         * a bar whose text was subtly the wrong size on one layout.
         */
        fun unitWidth(width: Int, columns: Float): Float = width / (columns * (1f + GAP))

        /**
         * The unit that everything vertical is measured from, which is not always the width.
         *
         * Every proportion in this keyboard is a fraction of one key's width, and in portrait
         * that is exactly right: a wider screen should give a taller keyboard, in the same
         * proportions, and it does.
         *
         * Turn the phone sideways and it stops being right. The screen is now twice as wide
         * and half as tall, so a unit taken from the width doubles - and four rows at
         * [KEY_ASPECT] came to more than the whole height of the screen. The keyboard was not
         * merely too big; it did not fit, which is what "broken in landscape" was.
         *
         * So the unit is capped by what there is room for. The keyboard and its suggestion
         * bar together come to roughly [TOTAL_UNITS] of them, and they may have
         * [MAX_HEIGHT_SHARE] of the screen, which fixes the largest unit that fits. In
         * portrait the width is far below that cap and nothing changes at all; in landscape
         * the cap decides, and the result is keys that are wide and short - which is what a
         * landscape keyboard should be, and what every other one does.
         *
         * Taken by every part of the keyboard that has a height, including the suggestion bar
         * in [KeyboardHost], so that they agree.
         */
        fun verticalUnit(resources: android.content.res.Resources, width: Int, columns: Float): Float {
            val fromWidth = unitWidth(width, columns)
            val room = resources.displayMetrics.heightPixels * MAX_HEIGHT_SHARE / TOTAL_UNITS
            return minOf(fromWidth, room)
        }

        /**
         * The column count everything vertical is measured against.
         *
         * Ten, because that is what the phone's own layout had and what the reference the
         * proportions were measured from is. A layout with more columns gets narrower keys of
         * the same height; one with fewer would get wider ones. See [onMeasure].
         */
        const val REFERENCE_COLUMNS = 10f

        /**
         * How much of the screen the keyboard and its bar may take between them.
         *
         * Only ever reached in landscape - see [verticalUnit]. Three fifths leaves the app
         * above enough to be worth typing into, which in landscape is the scarce thing.
         */
        const val MAX_HEIGHT_SHARE = 0.6f

        /**
         * The whole keyboard's height, in units, for that cap.
         *
         * Four rows at [KEY_ASPECT], a gutter each, and the suggestion bar. Deliberately the
         * tall case - four full rows, as though the shorter bottom row were switched off -
         * because the cap has to hold for the taller of the two and a keyboard that fits when
         * shortened is not a keyboard that fits.
         */
        const val TOTAL_UNITS = 4f * KEY_ASPECT + 4f * GAP + CandidateBar.HEIGHT

        /** What a navigation bar is, and has been, when the system will not say. */
        private const val FALLBACK_BAR_DP = 48f

        /** The alternates row: how tall each cell is against a key. */
        private const val POPUP_HEIGHT = 0.82f
    }
}
