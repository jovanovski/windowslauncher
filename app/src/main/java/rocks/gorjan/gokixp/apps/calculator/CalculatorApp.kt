package rocks.gorjan.gokixp.apps.calculator

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.text.DecimalFormatSymbols

/**
 * Calculator, as Windows Phone 8.1 had it.
 *
 * A field of flat keys under a number, and nothing else: no header, no app bar, no window.
 * Which is the whole design - the calculator was the one app on the phone that was
 * entirely keypad, and the black above it exists to give the answer somewhere to be
 * rather than to hold any chrome.
 *
 * Everything is sized from the width of a single key, which is itself a quarter of the
 * screen less the gaps. The phone's own proportions - a key a fifth again as wide as it is
 * tall, a gap an eighth of a key, a number nearly a key wide - then hold at any screen
 * size, which is what makes this read as the WP8.1 calculator on hardware that is a good
 * deal taller than the phone it was drawn for.
 */
class CalculatorApp(
    private val context: Context,
    private val palette: WP81Palette
) {

    private val engine = CalculatorEngine()
    private val symbols = DecimalFormatSymbols.getInstance()

    // Not `display`: every View already has one of those, and an inner class reaching for
    // the name gets android.view.Display rather than this.
    private lateinit var readout: TextView
    private val keys = mutableListOf<KeyView>()

    /** Measured once per pass and read by everything that sizes itself. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f
    private var displayHeight = 0

    fun createView(): View {
        val root = CalcLayout(context)
        root.setBackgroundColor(palette.background)

        readout = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
            setTextColor(palette.foreground)
            gravity = Gravity.BOTTOM or Gravity.END
            // The number is placed by its own baseline against the keypad below it, which
            // the font's built-in padding would push around by a few pixels per size.
            includeFontPadding = false
            maxLines = 1
            text = engine.display
        }
        root.addView(readout)

        for (spec in specs()) {
            val key = KeyView(context, spec)
            keys.add(key)
            root.addView(key)
        }
        return root
    }

    /**
     * The keypad, in reading order.
     *
     * Memory along the top, then the editing and sign keys, then the digits with the
     * operators down the right-hand edge - the arrangement the phone shipped, which is
     * also the one a hand that has used any calculator already knows.
     */
    private fun specs(): List<KeySpec> = listOf(
        KeySpec.text("C", Style.FUNCTION, big = false) { engine.clear() },
        KeySpec.text("MC", Style.FUNCTION, big = false) { engine.memoryClear() },
        KeySpec.text("MR", Style.FUNCTION, big = false) { engine.memoryRecall() },
        KeySpec.text("M+", Style.FUNCTION, big = false) { engine.memoryAdd() },

        KeySpec.glyph(R.drawable.wp81_calc_backspace, Style.FUNCTION) { engine.backspace() },
        KeySpec.glyph(R.drawable.wp81_calc_plusminus, Style.FUNCTION) { engine.negate() },
        KeySpec.glyph(R.drawable.wp81_calc_percent, Style.FUNCTION) { engine.percent() },
        KeySpec.glyph(R.drawable.wp81_calc_divide, Style.FUNCTION) {
            engine.operator(CalculatorEngine.Op.DIVIDE)
        },

        digitKey('7'), digitKey('8'), digitKey('9'),
        KeySpec.glyph(R.drawable.wp81_calc_times, Style.FUNCTION) {
            engine.operator(CalculatorEngine.Op.MULTIPLY)
        },

        digitKey('4'), digitKey('5'), digitKey('6'),
        KeySpec.glyph(R.drawable.wp81_calc_minus, Style.FUNCTION) {
            engine.operator(CalculatorEngine.Op.SUBTRACT)
        },

        digitKey('1'), digitKey('2'), digitKey('3'),
        KeySpec.glyph(R.drawable.wp81_calc_plus, Style.FUNCTION) {
            engine.operator(CalculatorEngine.Op.ADD)
        },

        KeySpec.text("0", Style.DIGIT, big = true, span = 2) { engine.digit('0') },
        // Whichever mark this locale writes numbers with, which on most of the world's
        // phones is the comma the WP8.1 keypad showed.
        KeySpec.text(symbols.decimalSeparator.toString(), Style.DIGIT, big = true) {
            engine.decimal()
        },
        KeySpec.glyph(R.drawable.wp81_calc_equals, Style.ACCENT) { engine.equals() }
    )

    private fun refresh() {
        readout.text = engine.display
    }

    // ---------------------------------------------------------------- keys

    private enum class Style { DIGIT, FUNCTION, ACCENT }

    private class KeySpec(
        val label: String?,
        val glyph: Int,
        val style: Style,
        val big: Boolean,
        val span: Int,
        val press: () -> Unit
    ) {
        companion object {
            fun text(
                label: String,
                style: Style,
                big: Boolean,
                span: Int = 1,
                press: () -> Unit
            ) = KeySpec(label, 0, style, big, span, press)

            fun glyph(res: Int, style: Style, press: () -> Unit) =
                KeySpec(null, res, style, big = false, span = 1, press = press)
        }
    }

    /** Shorthand for the nine keys that do nothing but put a digit on the readout. */
    private fun digitKey(d: Char) = KeySpec.text(d.toString(), Style.DIGIT, big = true) {
        engine.digit(d)
    }

    /**
     * One key.
     *
     * A [TextView] when it carries a character and an [ImageView] when it carries a mark,
     * because the two are placed quite differently: a comma sits on the digits' baseline
     * where a divide sign is centred in the key, and letting the platform do each the way
     * it already knows is what keeps them where the phone put them.
     */
    @SuppressLint("ViewConstructor")
    private inner class KeyView(context: Context, val spec: KeySpec) : ViewGroup(context) {

        val label: TextView? = spec.label?.let {
            TextView(context).apply {
                text = it
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
                setTextColor(if (spec.style == Style.ACCENT) palette.onAccent() else palette.foreground)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
            }
        }

        val icon: ImageView? = if (spec.glyph != 0) {
            ImageView(context).apply {
                setImageResource(spec.glyph)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (spec.style == Style.ACCENT) palette.onAccent() else palette.foreground
                )
            }
        } else null

        init {
            setBackgroundColor(fillFor(spec.style))
            label?.let { addView(it) }
            icon?.let { addView(it) }
            isClickable = true
            TiltEffect.apply(this)
            setOnClickListener {
                spec.press()
                refresh()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            label?.let {
                val size = keyW * (if (spec.big) DIGIT_TEXT else LABEL_TEXT)
                if (kotlin.math.abs(it.textSize - size) > 0.5f) {
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                }
                it.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
                )
            }
            icon?.let {
                val side = (keyW * GLYPH).toInt()
                it.measure(
                    MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
                )
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            label?.layout(0, 0, w, h)
            icon?.let {
                val left = (w - it.measuredWidth) / 2
                val top = (h - it.measuredHeight) / 2
                it.layout(left, top, left + it.measuredWidth, top + it.measuredHeight)
            }
        }
    }

    /**
     * What a key is painted.
     *
     * Both fills are the foreground colour at a low alpha over the background rather than
     * two fixed greys, which is what lets the same keypad work on the Light theme: on Dark
     * they come out the #1F1F1F and #333333 the phone used, and on Light they come out the
     * matching pair of greys instead of staying black on white.
     */
    @ColorInt
    private fun fillFor(style: Style): Int = when (style) {
        Style.ACCENT -> palette.accent
        Style.DIGIT -> blend(DIGIT_FILL_ALPHA)
        Style.FUNCTION -> blend(FUNCTION_FILL_ALPHA)
    }

    @ColorInt
    private fun blend(alpha: Float): Int = ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    // ---------------------------------------------------------------- layout

    /**
     * The page: a number over a six-by-four keypad.
     *
     * The keypad is anchored to the bottom and sized from the width, so on a screen taller
     * than the phone's the keys stay the shape they were drawn and the extra height falls
     * to the display - which is the right place for it. Stretching the keys to fill a
     * modern 20:9 screen would leave a keypad of six tall slabs that no longer looks like
     * the thing it is copying.
     */
    private inner class CalcLayout(context: Context) : ViewGroup(context) {

        private val scratch = TextPaint()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                resources.displayMetrics.heightPixels
            } else {
                MeasureSpec.getSize(heightMeasureSpec)
            }

            // Four keys and four gaps across, counting the half-gap margin at each edge.
            keyW = w / (COLUMNS * (1f + GAP))
            gap = keyW * GAP
            keyH = keyW * KEY_ASPECT
            val keypad = ROWS * keyH + (ROWS - 1) * gap + gap / 2f
            displayHeight = (h - keypad).toInt().coerceAtLeast(0)

            measureDisplay(w)
            for (key in keys) {
                key.measure(
                    MeasureSpec.makeMeasureSpec(spanWidth(key.spec.span), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(keyH.toInt(), MeasureSpec.EXACTLY)
                )
            }
            setMeasuredDimension(w, h)
        }

        /**
         * Sizes the number, shrinking it when it no longer fits.
         *
         * A calculator has to be able to show sixteen digits, and sixteen digits at the
         * size two of them are shown at would run off both sides of the screen. Measured
         * against the actual text rather than assumed from its length, since the digits of
         * a proportional face are not all the same width and the grouping separators are
         * narrow.
         */
        private fun measureDisplay(width: Int) {
            val padEnd = (keyW * DISPLAY_PAD_END).toInt()
            val padBottom = (keyW * DISPLAY_PAD_BOTTOM).toInt()
            if (readout.paddingEnd != padEnd || readout.paddingBottom != padBottom) {
                readout.setPadding(0, 0, padEnd, padBottom)
            }

            val max = keyW * DISPLAY_TEXT
            scratch.set(readout.paint)
            scratch.textSize = max
            val wanted = scratch.measureText(readout.text.toString())
            val room = (width - padEnd).toFloat()
            val size = if (wanted <= room || wanted <= 0f) max else {
                (max * room / wanted).coerceAtLeast(max * DISPLAY_MIN_SCALE)
            }
            if (kotlin.math.abs(readout.textSize - size) > 0.5f) {
                readout.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
            readout.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(displayHeight, MeasureSpec.EXACTLY)
            )
        }

        private fun spanWidth(span: Int) = (span * keyW + (span - 1) * gap).toInt()

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            readout.layout(0, 0, w, displayHeight)

            val margin = gap / 2f
            var x = margin
            var y = displayHeight.toFloat()
            var column = 0
            for (key in keys) {
                val keyWidth = spanWidth(key.spec.span)
                key.layout(x.toInt(), y.toInt(), x.toInt() + keyWidth, y.toInt() + keyH.toInt())
                column += key.spec.span
                if (column >= COLUMNS) {
                    column = 0
                    x = margin
                    y += keyH + gap
                } else {
                    x += keyWidth + gap
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 4
        const val ROWS = 6

        /**
         * Everything below is a proportion of one key's width, taken off the phone: a key
         * is a fifth wider than it is tall, the gaps are an eighth of a key, and the number
         * is set very nearly a whole key wide.
         */
        const val GAP = 0.111f
        const val KEY_ASPECT = 0.783f

        const val DISPLAY_TEXT = 0.912f
        const val DIGIT_TEXT = 0.360f
        const val LABEL_TEXT = 0.304f
        const val GLYPH = 0.47f

        /** Where the number sits: clear of the top keys, and in from the right edge. */
        const val DISPLAY_PAD_BOTTOM = 0.715f
        const val DISPLAY_PAD_END = 0.21f

        /** How small a long number may be shrunk before it is allowed to clip. */
        const val DISPLAY_MIN_SCALE = 0.34f

        /** The two key greys, as a fraction of the way from the background to the foreground. */
        const val DIGIT_FILL_ALPHA = 0.122f
        const val FUNCTION_FILL_ALPHA = 0.2f
    }
}
