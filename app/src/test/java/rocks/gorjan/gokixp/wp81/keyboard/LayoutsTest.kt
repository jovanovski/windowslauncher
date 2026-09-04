package rocks.gorjan.gokixp.wp81.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard layouts, checked for the things that go wrong silently.
 *
 * A layout is data, and wrong data here does not crash or throw - it draws a keyboard that
 * is very slightly the wrong shape and keeps doing it. A row that adds up to 9.9 instead of
 * 10 leaves a tenth of a key of dead ground against one edge; a Cyrillic layout that has
 * dropped a letter is a keyboard you cannot write your own language on. Both are obvious in
 * a screenshot and invisible in code review, which is what these are for. Every one of them
 * caught a real mistake while the layouts were being written.
 */
class LayoutsTest {

    private val everyLayout
        get() = Layouts.ALL_LANGUAGES + listOf(
            Layouts.SYMBOLS_1, Layouts.SYMBOLS_2, Layouts.NUMBER_PAD, Layouts.PHONE_PAD
        )

    /**
     * Every row fills its layout's width exactly.
     *
     * This is the invariant the whole grid rests on: keys are measured in units of one
     * letter, the grid divides the screen by the layout's column count to find out what a
     * unit is worth, and a row whose keys do not add up to that count simply stops short.
     */
    @Test
    fun everyRowFillsItsWidth() {
        for (layout in everyLayout) {
            layout.rows.forEachIndexed { index, row ->
                val total = row.indentStart + row.keys.sumOf { it.span.toDouble() } + row.indentEnd
                assertEquals(
                    "${layout.id} row $index does not fill its width",
                    layout.columns.toDouble(),
                    total,
                    TOLERANCE
                )
            }
        }
    }

    /** All thirty-one Macedonian letters are on the page, none of them twice. */
    @Test
    fun macedonianHasItsWholeAlphabet() {
        val letters = Layouts.MK_CYRILLIC.rows
            .flatMap { it.keys }
            .filter { it.action == null && it.output.isNotBlank() && it.output != "," && it.output != "." }
            .map { it.output }
        assertEquals("a letter is on the keyboard twice", letters.size, letters.toSet().size)
        assertEquals("the Macedonian alphabet is 31 letters", 31, letters.size)
        assertTrue(
            "the letters unique to Macedonian must all be present",
            letters.containsAll(listOf("ѓ", "ќ", "ѕ", "љ", "њ", "џ", "ј"))
        )
    }

    /** English keeps the ten hints that stand in for a number row. */
    @Test
    fun englishTopRowCarriesTheNumbers() {
        val hints = Layouts.EN_QWERTY.rows[0].keys.mapNotNull { it.hint }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), hints)
    }

    /**
     * Every letter has somewhere to be, for the correction engine.
     *
     * Autocorrect weighs a substitution by how far apart two keys are, so a letter missing
     * from the centre map is one the engine will never propose a correction toward. Nothing
     * anywhere would report it.
     */
    @Test
    fun everyLetterHasACentre() {
        for (layout in Layouts.ALL_LANGUAGES) {
            val typed = layout.rows.flatMap { it.keys }
                .filter { it.action == null && it.output.length == 1 }
            for (key in typed) {
                assertTrue(
                    "${layout.id} has no centre for '${key.output}'",
                    layout.keyCentres.containsKey(key.output[0])
                )
            }
        }
    }

    /** The bottom row is shared, so it has to survive being asked for at two widths. */
    @Test
    fun theSpaceBarAbsorbsTheDifference() {
        val ten = Layouts.EN_QWERTY.rows.last().keys.first { it.action == Action.SPACE }
        val twelve = Layouts.MK_CYRILLIC.rows.last().keys.first { it.action == Action.SPACE }
        assertEquals(
            "twelve columns should give the space bar exactly two more units",
            2.0,
            (twelve.span - ten.span).toDouble(),
            TOLERANCE
        )
    }

    /**
     * Every English letter holds the symbol Gboard puts behind it.
     *
     * Muscle memory is the whole value of matching another keyboard's arrangement, so this
     * pins the exact mapping rather than merely checking that something is there. The top
     * row's are its hints; the other two rows carry theirs as the first alternate, which is
     * what a hold produces.
     */
    @Test
    fun everyLetterHoldsItsGboardSymbol() {
        val expected = mapOf(
            "q" to '1', "w" to '2', "e" to '3', "r" to '4', "t" to '5',
            "y" to '6', "u" to '7', "i" to '8', "o" to '9', "p" to '0',
            "a" to '@', "s" to '#', "d" to '$', "f" to '_', "g" to '&',
            "h" to '-', "j" to '+', "k" to '(', "l" to ')',
            "z" to '*', "x" to '"', "c" to '\'', "v" to ':', "b" to ';',
            "n" to '!', "m" to '?'
        )
        val keys = Layouts.EN_QWERTY.rows.flatMap { it.keys }.associateBy { it.output }
        for ((letter, symbol) in expected) {
            val key = keys[letter] ?: error("no '$letter' key on the English layout")
            val first = ((key.hint ?: "") + key.alternates).firstOrNull()
            assertEquals("holding '$letter' should give '$symbol'", symbol, first)
        }
    }

    /**
     * A symbol sits on the symbol page where the letter that holds it sits on the letters.
     *
     * This is the whole reason the two were arranged together: a hold is the quick way to one
     * symbol and the `&123` page is the quick way to several, and a thumb that has learned
     * `?` is bottom-right from one should find it bottom-right on the other. Rows one and
     * three can be checked exactly, because both layouts put the same number of keys between
     * the same shoulders. The middle row cannot - nine letters against ten symbols - so only
     * its order is checked, which is the same compromise Gboard makes.
     */
    @Test
    fun theSymbolPageSitsWhereTheHoldsDo() {
        val letters = Layouts.EN_QWERTY
        val page = Layouts.SYMBOLS_1

        for (row in listOf(0, 2)) {
            val held = centresOfHints(letters, row)
            val printed = centresOfOutputs(page, row)
            for ((symbol, x) in held) {
                val onPage = printed[symbol]
                assertNotNull("'$symbol' is held on row $row but is not on the symbol page", onPage)
                assertEquals(
                    "'$symbol' is not above the key that holds it",
                    x, onPage!!, TOLERANCE
                )
            }
        }

        // The middle row: same symbols, same order, one extra on the end.
        val middleHeld = letters.rows[1].keys.mapNotNull { it.hint }
        val middlePrinted = page.rows[1].keys.filter { it.action == null }.map { it.output }
        assertEquals(middleHeld, middlePrinted.take(middleHeld.size))
    }

    /**
     * A subtype's language tag finds its layout, in every spelling the platform uses.
     *
     * `InputMethodSubtype` hands back a BCP 47 tag on modern Android and the older
     * underscored locale on everything else, and which one arrives depends on how the
     * subtype was declared rather than on anything this code controls. Region is dropped
     * because a layout belongs to a language: `en-US` and `en-GB` are the same keys.
     */
    @Test
    fun everySpellingOfALanguageFindsItsLayout() {
        for (tag in listOf("en", "en-US", "en_US", "en-GB", "EN")) {
            assertEquals(tag, Layouts.EN_QWERTY, Layouts.forLanguageTag(tag))
        }
        for (tag in listOf("mk", "mk-MK", "mk_MK")) {
            assertEquals(tag, Layouts.MK_CYRILLIC, Layouts.forLanguageTag(tag))
        }
        // A language nothing here can spell, and no subtype at all, both fall back to
        // letters the user can at least type with rather than to nothing.
        assertEquals(Layouts.EN_QWERTY, Layouts.forLanguageTag("ja"))
        assertEquals(Layouts.EN_QWERTY, Layouts.forLanguageTag(null))
        assertEquals(Layouts.EN_QWERTY, Layouts.forLanguageTag(""))
    }

    /** Every language layout declares the code its subtype is declared with. */
    @Test
    fun everyLanguageLayoutNamesItsLanguage() {
        for (layout in Layouts.ALL_LANGUAGES) {
            assertTrue("${layout.id} has no language code", layout.language.isNotEmpty())
        }
        // The symbol pages belong to no language, so they must not answer for one.
        assertEquals("", Layouts.SYMBOLS_1.language)
        assertEquals("", Layouts.SYMBOLS_2.language)
    }

    /** Where each hinted key's centre is, in units across the row. */
    private fun centresOfHints(layout: KeyboardLayout, row: Int): Map<String, Double> =
        walk(layout.rows[row]) { it.hint }

    private fun centresOfOutputs(layout: KeyboardLayout, row: Int): Map<String, Double> =
        walk(layout.rows[row]) { if (it.action == null) it.output else null }

    private fun walk(row: Row, name: (Key) -> String?): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        var x = row.indentStart.toDouble()
        for (key in row.keys) {
            name(key)?.let { out[it] = x + key.span / 2.0 }
            x += key.span
        }
        return out
    }

    /** A keypad that is missing a digit is a keypad you cannot type a number on. */
    @Test
    fun bothKeypadsHaveEveryDigit() {
        for (pad in listOf(Layouts.NUMBER_PAD, Layouts.PHONE_PAD)) {
            val digits = pad.rows.flatMap { it.keys }.map { it.output }.filter { it.length == 1 }
            for (digit in "0123456789") {
                assertTrue("${pad.id} has no '$digit'", digits.contains(digit.toString()))
            }
            assertTrue(
                "${pad.id} needs a backspace",
                pad.rows.flatMap { it.keys }.any { it.action == Action.BACKSPACE }
            )
            assertTrue(
                "${pad.id} needs an enter key",
                pad.rows.flatMap { it.keys }.any { it.action == Action.ENTER }
            )
        }
    }

    /**
     * The space bar's target reaches into the row above it; nothing else's does.
     *
     * The fix for hitting `n` when aiming for the space bar. A thumb reaching down flattens
     * against the glass and Android reports the centre of the contact patch, which sits above
     * where the person thinks they are pointing - so the aim is low, the report is high, and
     * the row above catches it. The space bar therefore claims part of that row without its
     * painted face moving.
     *
     * Bounded on both sides: enough to catch a near miss, and not so much that `n` becomes
     * hard to press deliberately. The row above keeps most of itself.
     */
    @Test
    fun onlyTheSpaceBarReachesIntoTheRowAbove() {
        for (layout in everyLayout) {
            for (key in layout.rows.flatMap { it.keys }) {
                if (key.action == Action.SPACE) {
                    assertTrue(
                        "${layout.id}: the space bar should reach up",
                        key.overhangTop > 0f
                    )
                    assertTrue(
                        "${layout.id}: it should not swallow the row above",
                        key.overhangTop <= 0.5f
                    )
                } else {
                    assertEquals(
                        "${layout.id}: '${key.output}${key.action ?: ""}' should not overhang",
                        0f, key.overhangTop, 0.0001f
                    )
                }
            }
        }
    }

    private companion object {
        /** Spans are floats and are added up, so exact equality is not the question. */
        const val TOLERANCE = 0.001
    }
}
