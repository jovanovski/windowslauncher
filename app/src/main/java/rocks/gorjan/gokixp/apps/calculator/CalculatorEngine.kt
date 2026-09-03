package rocks.gorjan.gokixp.apps.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import kotlin.math.abs

/**
 * The arithmetic behind the Calculator, with no view attached.
 *
 * A pocket calculator, not an expression evaluator: there is no precedence and no
 * brackets, keys are applied in the order they are pressed, and `2 + 3 x 4` is twenty
 * rather than fourteen. That is what the four-function calculator on the phone did, and
 * anything cleverer would surprise the hand that already knows this keypad.
 *
 * The number being typed is kept as *text* rather than as a double until it is used.
 * Typing is an editing operation - a trailing separator, a leading zero, a digit removed
 * by backspace - and none of those survive a round trip through a double: "3." parses to
 * 3.0 and comes back as "3", which takes the decimal point away from under the finger
 * that just pressed it.
 */
class CalculatorEngine(
    private val symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance()
) {

    enum class Op { ADD, SUBTRACT, MULTIPLY, DIVIDE }

    /** What the display should read, already grouped and in the locale's own separators. */
    val display: String
        get() = when {
            error != null -> error!!
            typed != null -> group(typed!!)
            else -> format(shown)
        }

    /** Whether anything is in memory. */
    val hasMemory: Boolean get() = memory != 0.0

    // What the display holds when the user is not typing: a result, a recalled memory, or
    // zero. Exactly one of [typed] and this is what the display reads from.
    private var shown: Double = 0.0

    /** The number as typed, with '.' throughout; null when the display holds [shown]. */
    private var typed: String? = null

    private var stored: Double? = null
    private var pending: Op? = null

    // What = did last, so pressing it again repeats it: 2 + 3 = = is 8, the way every
    // calculator with an equals key has behaved.
    private var lastOp: Op? = null
    private var lastOperand: Double? = null

    private var memory: Double = 0.0

    /**
     * Whether the last key pressed was an operator.
     *
     * Only then does a second operator *replace* the first rather than chain onto it:
     * pressing + and then realising you meant x should change your mind, not add the
     * running total to itself.
     */
    private var lastWasOperator = false

    /** Set when a result cannot be shown; every key but C and backspace is inert until then. */
    private var error: String? = null

    fun digit(d: Char) {
        if (error != null) clear()
        lastWasOperator = false
        val base = typed ?: ""
        if (base.trimStart('-').replace(".", "").length >= MAX_DIGITS) return
        typed = when {
            // A leading zero is a placeholder, not a digit: 0 then 5 is 5, not 05. It
            // stays only when a decimal point is about to be typed after it.
            base == "0" -> d.toString()
            base == "-0" -> "-$d"
            else -> base + d
        }
    }

    fun decimal() {
        if (error != null) clear()
        lastWasOperator = false
        val base = typed ?: ""
        typed = when {
            base.isEmpty() || base == "-" -> base + "0."
            base.contains('.') -> base
            else -> "$base."
        }
    }

    /**
     * Removes the last thing typed.
     *
     * A result is not something that was typed, so backspace does not edit it digit by
     * digit - it clears it. Editing a computed number would leave the display holding a
     * value that nothing arrived at.
     */
    fun backspace() {
        if (error != null) { clear(); return }
        lastWasOperator = false
        val base = typed
        if (base == null) { shown = 0.0; return }
        val shorter = base.dropLast(1)
        typed = if (shorter.isEmpty() || shorter == "-") null else shorter
        if (typed == null) shown = 0.0
    }

    fun negate() {
        if (error != null) return
        val base = typed
        if (base != null) {
            if (base.trimStart('-').all { it == '0' || it == '.' }) return
            typed = if (base.startsWith("-")) base.drop(1) else "-$base"
        } else {
            if (shown == 0.0) return
            shown = -shown
        }
    }

    /**
     * Per cent, as a calculator means it rather than as a mathematician does.
     *
     * Mid-sum it is a share of what is already there, so 200 + 10 % is 200 + 20; on its
     * own it is simply a hundredth. Both are what the keypad's user is asking for when
     * they reach for this key.
     */
    fun percent() {
        if (error != null) return
        lastWasOperator = false
        val v = current()
        val base = stored
        val result = if (pending != null && base != null) base * v / 100.0 else v / 100.0
        settle(result)
    }

    fun operator(op: Op) {
        if (error != null) return
        if (lastWasOperator) { pending = op; return }

        val v = current()
        val base = stored
        if (pending != null && base != null) {
            val r = compute(base, v, pending!!) ?: return fail()
            stored = r
            settle(r)
        } else {
            stored = v
            // Settled even though nothing was computed: what was being typed is now this
            // operation's left-hand side, and the display has to go on showing it rather
            // than dropping back to whatever it held before the typing started.
            settle(v)
        }
        pending = op
        lastOp = null
        lastOperand = null
        lastWasOperator = true
    }

    fun equals() {
        if (error != null) return
        lastWasOperator = false
        val v = current()
        val base = stored
        if (pending != null && base != null) {
            val r = compute(base, v, pending!!) ?: return fail()
            // Remembered so a second = repeats the same operation on the answer.
            lastOp = pending
            lastOperand = v
            stored = null
            pending = null
            settle(r)
        } else {
            val op = lastOp
            val operand = lastOperand
            // = with nothing pending repeats the last operation; with nothing to repeat
            // either, it settles what is on screen so the next digit starts a new number.
            if (op != null && operand != null) {
                val r = compute(v, operand, op) ?: return fail()
                settle(r)
            } else {
                settle(v)
            }
        }
    }

    /** C: everything except memory, which survives a clear on every calculator. */
    fun clear() {
        shown = 0.0
        typed = null
        stored = null
        pending = null
        lastOp = null
        lastOperand = null
        lastWasOperator = false
        error = null
    }

    fun memoryClear() { memory = 0.0 }

    fun memoryRecall() {
        if (error != null) clear()
        lastWasOperator = false
        settle(memory)
    }

    fun memoryAdd() {
        if (error != null) return
        lastWasOperator = false
        memory += current()
    }

    /** The number the next operation should use: what is being typed, or what is shown. */
    private fun current(): Double = typed?.toDoubleOrNull() ?: shown

    /** Puts [v] on the display as a result, ending whatever was being typed. */
    private fun settle(v: Double) {
        if (!v.isFinite()) { fail(); return }
        shown = v
        typed = null
    }

    private fun fail() {
        clear()
        error = CANNOT_DIVIDE
    }

    private fun compute(a: Double, b: Double, op: Op): Double? {
        val r = when (op) {
            Op.ADD -> a + b
            Op.SUBTRACT -> a - b
            Op.MULTIPLY -> a * b
            Op.DIVIDE -> if (b == 0.0) return null else a / b
        }
        return if (r.isFinite()) r else null
    }

    /**
     * A result, written out.
     *
     * Through [BigDecimal] rather than a printf format because binary floating point does
     * not hold the numbers a calculator produces: 0.1 + 0.2 is 0.30000000000000004, and
     * printing what the double actually is would be honest and useless. Rounding to
     * fifteen significant figures - one short of a double's worth - is what hides the
     * representation error, and is what desk calculators have always done.
     *
     * What survives that is then cut, not rounded, to [DECIMALS] places. A division rarely
     * comes out even, and the fifteen figures it can be trusted to are fifteen figures of
     * a number nobody reads past the sixth of: 11000 / 61.6 is 178.571428, and the digits
     * after that are noise the display would have to be shrunk to fit. Cut rather than
     * rounded because the sixth place of an answer that goes on is a place the answer is
     * being truncated at, and rounding it up would show a last digit the sum did not have.
     */
    private fun format(v: Double): String {
        if (!v.isFinite()) return CANNOT_DIVIDE
        if (v == 0.0) return "0"
        val magnitude = abs(v)
        if (magnitude >= SCIENTIFIC_ABOVE || magnitude < SCIENTIFIC_BELOW) {
            return scientific(v)
        }
        val rounded = BigDecimal(v).round(MathContext(SIGNIFICANT))
            .setScale(DECIMALS, RoundingMode.DOWN)
            .stripTrailingZeros()
        return group(rounded.toPlainString())
    }

    /** For what will not fit: 1.234567890E+21, with the locale's own decimal separator. */
    private fun scientific(v: Double): String {
        val text = String.format(java.util.Locale.US, "%.9E", v)
        val (mantissa, exponent) = text.split("E", limit = 2)
        val tidy = mantissa.trimEnd('0').trimEnd('.')
        return tidy.replace(".", symbols.decimalSeparator.toString()) + "E" + exponent
    }

    /**
     * Thousands separators, and the locale's decimal separator in place of the '.' the
     * typed string carries internally.
     *
     * Applied to the number as typed as well as to results, so a long number is readable
     * while it is being entered rather than only once something is done with it.
     */
    private fun group(plain: String): String {
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val point = body.indexOf('.')
        val whole = if (point < 0) body else body.substring(0, point)
        val rest = if (point < 0) "" else body.substring(point)

        val grouped = StringBuilder()
        for ((i, c) in whole.withIndex()) {
            // Counted from the right, so the first group can be one, two or three digits.
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(symbols.groupingSeparator)
            grouped.append(c)
        }
        if (negative) grouped.insert(0, symbols.minusSign)
        // The point is kept even with nothing after it yet: it is on screen because the
        // user just pressed it, and taking it away again would read as the key not working.
        if (rest.isNotEmpty()) {
            grouped.append(symbols.decimalSeparator).append(rest.substring(1))
        }
        return grouped.toString()
    }

    private companion object {
        /** As many digits as a double can be trusted for, less one for the rounding. */
        const val SIGNIFICANT = 15

        /** What the keypad will accept; past this the display cannot show it anyway. */
        const val MAX_DIGITS = 16

        /** How many decimal places a result is shown to, past which it is cut. */
        const val DECIMALS = 6

        const val SCIENTIFIC_ABOVE = 1e16

        /**
         * Below this a result is written in exponent form.
         *
         * Which is [DECIMALS] places: a number smaller than the last place shown would be
         * cut to nothing, and a calculator that answers a sum with 0 when the answer is
         * not zero is lying rather than rounding.
         */
        const val SCIENTIFIC_BELOW = 1e-6

        const val CANNOT_DIVIDE = "Cannot divide by zero"
    }
}
