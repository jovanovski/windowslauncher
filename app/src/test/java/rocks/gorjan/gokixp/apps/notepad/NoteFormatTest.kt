package rocks.gorjan.gokixp.apps.notepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rocks.gorjan.gokixp.apps.notepad.NoteFormat.Continue
import rocks.gorjan.gokixp.apps.notepad.NoteFormat.Tool

/**
 * The formatting strip's commands, on notes written out with the selection in them: `|` is
 * a caret, and `{` `}` go round a selection.
 */
class NoteFormatTest {

    private fun parse(marked: String): Triple<String, Int, Int> {
        val caret = marked.indexOf('|')
        if (caret >= 0) return Triple(marked.removeRange(caret, caret + 1), caret, caret)
        val s = marked.indexOf('{')
        val e = marked.indexOf('}') - 1
        return Triple(marked.replace("{", "").replace("}", ""), s, e)
    }

    private fun show(src: String, s: Int, e: Int) =
        if (s == e) src.substring(0, s) + "|" + src.substring(s)
        else src.substring(0, s) + "{" + src.substring(s, e) + "}" + src.substring(e)

    private fun carryOut(src: String, r: NoteFormat.Result): String {
        val out = StringBuilder(src)
        for (c in r.changes) out.replace(c.start, c.end, c.text)
        return show(out.toString(), r.selStart, r.selEnd)
    }

    private fun press(tool: Tool, marked: String): String {
        val (src, s, e) = parse(marked)
        return carryOut(src, NoteFormat.apply(tool, src, s, e) ?: return marked)
    }

    private fun link(marked: String, typed: String): String {
        val (src, s, e) = parse(marked)
        return carryOut(src, NoteFormat.link(src, s, e, typed) ?: return marked)
    }

    private fun active(marked: String): Set<Tool> {
        val (src, s, e) = parse(marked)
        return NoteFormat.active(src, s, e)
    }

    // ------------------------------------------------------------- a selection

    @Test
    fun boldGoesRoundTheSelection() {
        assertEquals("a **{word}** b", press(Tool.BOLD, "a {word} b"))
    }

    @Test
    fun boldComesOffABoldSelection() {
        assertEquals("a {word} b", press(Tool.BOLD, "a **{word}** b"))
        assertEquals("a {word} b", press(Tool.BOLD, "a {**word**} b"))
    }

    @Test
    fun boldComesOffPartOfARunByCuttingIt() {
        assertEquals("**b**{ol}**d**", press(Tool.BOLD, "**b{ol}d**"))
        assertEquals("__b__{ol}__d__", press(Tool.BOLD, "__b{ol}d__"))
    }

    @Test
    fun marksHugTheWordsNotTheSpaces() {
        assertEquals("a **{word}** b", press(Tool.BOLD, "a {word }b"))
    }

    @Test
    fun boldAroundBoldTakesTheInnerMarksOff() {
        assertEquals("**{a b c}**", press(Tool.BOLD, "{a **b** c}"))
    }

    @Test
    fun eachLineIsWrappedOnItsOwn() {
        assertEquals("**{one**\n**two}**", press(Tool.BOLD, "{one\ntwo}"))
    }

    @Test
    fun italicAndUnderlineWrapToo() {
        assertEquals("*{it}*", press(Tool.ITALIC, "{it}"))
        assertEquals("<u>{u}</u>", press(Tool.UNDERLINE, "{u}"))
        assertEquals("{u}", press(Tool.UNDERLINE, "<u>{u}</u>"))
    }

    // --------------------------------------------------------- what comes next

    @Test
    fun nothingSelectedPutsDownAnEmptyPair() {
        assertEquals("a **|**", press(Tool.BOLD, "a |"))
        assertEquals("<u>|</u>", press(Tool.UNDERLINE, "|"))
    }

    @Test
    fun pressingAgainTakesTheEmptyPairAway() {
        assertEquals("a |", press(Tool.BOLD, "a **|**"))
        assertEquals("|", press(Tool.UNDERLINE, "<u>|</u>"))
    }

    @Test
    fun boldAndItalicShareTheirStars() {
        assertEquals("***|***", press(Tool.ITALIC, "**|**"))
        assertEquals("**|**", press(Tool.ITALIC, "***|***"))
        assertEquals("*|*", press(Tool.BOLD, "***|***"))
    }

    @Test
    fun pressingAtTheEndOfTheWordsStepsOut() {
        assertEquals("**bold**|", press(Tool.BOLD, "**bold|**"))
        assertEquals("<u>x</u>|", press(Tool.UNDERLINE, "<u>x|</u>"))
    }

    @Test
    fun pressingJustAfterARunStepsBackIn() {
        assertEquals("**bold|**", press(Tool.BOLD, "**bold**|"))
    }

    @Test
    fun pressingInTheMiddleOfAWordTakesTheStyleOffIt() {
        assertEquals("bo|ld", press(Tool.BOLD, "**bo|ld**"))
    }

    // -------------------------------------------------------- headings and lists

    @Test
    fun headingsGoOnAndComeOff() {
        assertEquals("# |title", press(Tool.H1, "|title"))
        assertEquals("|title", press(Tool.H1, "# |title"))
        assertEquals("## ti|tle", press(Tool.H2, "# ti|tle"))
        assertEquals("### ti|tle", press(Tool.H3, "ti|tle"))
        assertEquals("# item|", press(Tool.H1, "- item|"))
    }

    @Test
    fun normalTakesAnyMarkOff() {
        assertEquals("ti|tle", press(Tool.NORMAL, "## ti|tle"))
        assertEquals("ta|sk", press(Tool.NORMAL, "- [ ] ta|sk"))
        assertEquals("plain|", press(Tool.NORMAL, "plain|"))
    }

    @Test
    fun bulletsGoOnAndComeOff() {
        assertEquals("- |", press(Tool.BULLETS, "|"))
        assertEquals("- {a\n- b}", press(Tool.BULLETS, "{a\nb}"))
        assertEquals("{a\nb}", press(Tool.BULLETS, "- {a\n- b}"))
        assertEquals("- |a", press(Tool.BULLETS, "1. |a"))
    }

    @Test
    fun blankLinesInsideASelectionAreLeftAlone() {
        assertEquals("- {a\n\n- b}", press(Tool.BULLETS, "{a\n\nb}"))
    }

    @Test
    fun numbersCountAndCarryOnFromAbove() {
        assertEquals("1. {a\n2. b}", press(Tool.NUMBERS, "{a\nb}"))
        assertEquals("1. x\n2. |y", press(Tool.NUMBERS, "1. x\n|y"))
        assertEquals("|a", press(Tool.NUMBERS, "1. |a"))
    }

    // --------------------------------------------------------------------- links

    @Test
    fun selectedWordsBecomeALink() {
        assertEquals("a [{word}](https://example.com) b", link("a {word} b", "example.com"))
    }

    @Test
    fun withNothingSelectedTheAddressIsTheWords() {
        assertEquals("a [{https://x.com}](https://x.com)", link("a |", "x.com"))
    }

    @Test
    fun insideALinkItsAddressChanges() {
        assertEquals("[w|](https://c.d)", link("[w|](https://a.b)", "c.d"))
        assertEquals("w|", link("[w|](https://a.b)", ""))
    }

    @Test
    fun anAddressKeepsItsSchemeAndLosesItsSpaces() {
        assertEquals("[{a}](mailto:x@y.z)", link("{a}", "mailto:x@y.z"))
        assertEquals("[{a}](https://x.com/a%20b)", link("{a}", " x.com/a b "))
    }

    // -------------------------------------------------------------- the strip

    @Test
    fun whatIsInForceLightsUp() {
        assertTrue(Tool.BOLD in active("**bo|ld**"))
        assertTrue(Tool.BOLD in active("**|**"))
        assertFalse(Tool.BOLD in active("**bold**|"))
        assertTrue(Tool.ITALIC in active("***b|i***"))
        assertTrue(Tool.H1 in active("# t|"))
        assertTrue(Tool.BULLETS in active("- a|"))
        assertTrue(Tool.BULLETS in active("- [ ] a|"))
        assertTrue(Tool.NUMBERS in active("1. a|"))
        assertTrue(Tool.LINK in active("[w|](https://a.b)"))
        assertEquals(emptySet<Tool>(), active("plain|"))
    }

    // ------------------------------------------------------------------ return

    @Test
    fun returnCarriesAListOn() {
        assertEquals("- ", (NoteFormat.onReturn("- item", 6, 6) as Continue.Next).mark)
        assertEquals("  4. ", (NoteFormat.onReturn("  3. x", 6, 6) as Continue.Next).mark)
        assertEquals("- [ ] ", (NoteFormat.onReturn("- [x] done", 10, 10) as Continue.Next).mark)
        // Splitting an item carries the list on with the rest of it.
        assertEquals("- ", (NoteFormat.onReturn("- ab", 3, 3) as Continue.Next).mark)
    }

    @Test
    fun returnOnAnEmptyItemEndsTheList() {
        val end = NoteFormat.onReturn("a\n- ", 4, 4) as Continue.End
        assertEquals(2, end.start)
        assertEquals("- ", end.mark)
    }

    @Test
    fun returnElsewhereIsJustANewLine() {
        assertNull(NoteFormat.onReturn("plain", 5, 5))
        assertNull(NoteFormat.onReturn("# h", 3, 3))
        assertNull(NoteFormat.onReturn("- item", 0, 0))
    }
}
