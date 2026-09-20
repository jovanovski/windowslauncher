package rocks.gorjan.gokixp.apps.notepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note's markdown: which marks are hidden and which are showing for a given caret, and
 * the list's one-line preview with the markdown taken out.
 *
 * Spans are named the way the styler names them, `key@start:end`.
 */
class NoteMarkdownTest {

    private fun preview(content: String) = NoteMarkdown.preview(content)

    private fun has(src: String, caret: IntRange?, vararg ids: String) {
        val got = NoteMarkdown.ids(src, caret)
        for (id in ids) assertTrue("expected $id in $got", id in got)
    }

    // ----------------------------------------------------------- hidden marks

    @Test
    fun boldMarksAreHiddenWithNoCaret() {
        has("**bold** x", null, "hide@0:2", "hide@6:8", "bold@2:6")
    }

    @Test
    fun boldMarkShowsOnlyWhereTheCaretIs() {
        // Just after the closing stars: those show, the opening ones stay hidden.
        has("**bold** x", 8..8, "dim@6:8", "hide@0:2")
        // Inside the word, touching neither: both hidden.
        has("**bold** x", 4..4, "hide@0:2", "hide@6:8")
    }

    @Test
    fun caretOnAnotherLineUncoversNothing() {
        has("**a**\nzz", 7..7, "hide@0:2", "hide@3:5")
    }

    @Test
    fun selectionUncoversEveryMarkItReaches() {
        has("**bold** x", 1..7, "dim@0:2", "dim@6:8")
    }

    @Test
    fun headingMarkGoesOnceTheHeadingHasWords() {
        has("# Title", null, "hide@0:2", "h1@0:7")
        has("# Title", 2..2, "dim@0:2")
        has("# Title", 3..3, "hide@0:2")
    }

    @Test
    fun loneHashStaysInSight() {
        val got = NoteMarkdown.ids("#", null)
        assertTrue("dim@0:1" in got)
        assertFalse("hide@0:1" in got)
    }

    @Test
    fun todoIsDrawnAsABox() {
        has("- [ ] task", null, "box@0:5")
        has("- [x] task", null, "ticked@0:5", "strike@6:10")
        // Caret at the start of the words is past the box, so it stays a box.
        has("- [ ] task", 6..6, "box@0:5")
        has("- [ ] task", 5..5, "dim@0:5")
    }

    @Test
    fun linkShowsOnlyItsWords() {
        has("[link](http://a.b) z", null, "hide@0:1", "hide@5:18", "link@1:5")
    }

    @Test
    fun codeFencesAreHidden() {
        has("```\ncode\n```", null, "hide@0:3", "hide@9:12", "block@0:12")
    }

    @Test
    fun ruleRunsFromTheEdgeOnceItsDashesAreHidden() {
        has("---", null, "hide@0:3", "rule@0:3")
        has("---", 1..1, "dim@0:3", "rule-after@0:3")
    }

    @Test
    fun underlineTagsAreHidden() {
        has("<u>u</u>", null, "hide@0:3", "hide@4:8", "underline@3:4")
    }

    @Test
    fun anEmptyBoldPairIsNotARule() {
        // The stars of an empty bold pair, with the caret between them, stay stars.
        assertFalse("rule@0:4" in NoteMarkdown.ids("****", 2..2))
        has("***", null, "rule@0:3")
    }

    // ------------------------------------------------------------ tap to tick

    @Test
    fun tapFindsTheMarkBetweenTheBrackets() {
        assertEquals(3, NoteMarkdown.tickAt("- [ ] a", 0..5))
        // Indented, and ticked: the box the parser draws is the range the tap is given.
        has("  - [x] a", null, "ticked@2:7")
        assertEquals(5, NoteMarkdown.tickAt("  - [x] a", 2..7))
    }

    @Test
    fun tapOnWhatIsNoLongerABoxDoesNothing() {
        assertNull(NoteMarkdown.tickAt("- [ ] a", 0..3))
        assertNull(NoteMarkdown.tickAt("- a", 0..2))
        assertNull(NoteMarkdown.tickAt("- [ ]", 0..9))
    }

    // ---------------------------------------------------------------- preview

    @Test
    fun headingIsItsWords() {
        assertEquals("Shopping", preview("# Shopping\n- milk"))
        assertEquals("Deep", preview("###### Deep"))
    }

    @Test
    fun hashtagIsNotAHeading() {
        assertEquals("#hashtag", preview("#hashtag"))
    }

    @Test
    fun blankLinesAreSkipped() {
        assertEquals("first words", preview("\n\n   \nfirst words"))
        assertNull(preview(""))
        assertNull(preview("\n\n"))
    }

    @Test
    fun emphasisMarksAreDropped() {
        assertEquals("bold and it and code", preview("**bold** and _it_ and `code`"))
        assertEquals("both", preview("***both***"))
        assertEquals("gone here", preview("~~gone~~ here"))
        assertEquals("a star", preview("a *star*"))
    }

    @Test
    fun listMarkersAreDropped() {
        assertEquals("milk", preview("- milk"))
        assertEquals("eggs", preview("* eggs"))
        assertEquals("first", preview("1. first"))
        assertEquals("done thing", preview("- [x] done thing"))
        assertEquals("open thing", preview("- [ ] open thing"))
    }

    @Test
    fun quoteAndLinkAreTheirWords() {
        assertEquals("quoted link", preview("> quoted [link](http://x.com/a_b)"))
    }

    @Test
    fun codeFenceShowsTheCodeNotTheFence() {
        assertEquals("val x = 1", preview("```\nval x = 1\n```"))
    }

    @Test
    fun ruleIsNotSomethingTheNoteSays() {
        assertEquals("after rule", preview("---\nafter rule"))
        assertEquals("after stars", preview("* * *\nafter stars"))
    }

    @Test
    fun plainTextIsLeftAlone() {
        assertEquals("snake_case_name stays", preview("snake_case_name stays"))
        assertEquals("2 * 3 * 4", preview("2 * 3 * 4"))
        assertEquals("under_score", preview("under_score"))
    }
}
