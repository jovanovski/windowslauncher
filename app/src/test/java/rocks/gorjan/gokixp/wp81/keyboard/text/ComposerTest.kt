package rocks.gorjan.gokixp.wp81.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word in progress, and the rules about when it ends.
 *
 * Small tests over a small class, and worth having because the consequences are out of all
 * proportion to the logic: composing text is *replaced* by a commit rather than appended to,
 * so a character that should have ended the word but was treated as part of it takes the word
 * with it. That is not a subtle rendering fault, it is text disappearing as you type.
 */
class ComposerTest {

    /**
     * A symbol ends the word; a letter does not.
     *
     * The regression this pins: typing `bi sakal` and then holding a key for `?` produced
     * `bi ?`. The `?` was committed while `sakal` was still composing, and a commit replaces
     * the composing region - so the word was gone. Accents have to go the other way, because
     * `é` really is part of the word being typed.
     */
    @Test
    fun lettersExtendAWordAndSymbolsEndIt() {
        val composer = Composer()
        for (text in listOf("a", "z", "é", "ñ", "ќ", "ж", "o'")) {
            assertTrue("'$text' is part of a word", composer.extendsWord(text))
        }
        for (text in listOf("?", "!", "@", ".", ",", "/", "-", "1", " ", "")) {
            assertFalse("'$text' should end the word", composer.extendsWord(text))
        }
    }

    @Test
    fun aWordBuildsUpAndBacksOff() {
        val composer = Composer()
        assertFalse(composer.isComposing)
        composer.append("h")
        composer.append("i")
        assertEquals("hi", composer.typed)
        assertTrue(composer.isComposing)

        assertTrue("backspace should be handled while composing", composer.backspace())
        assertEquals("h", composer.typed)
        assertTrue(composer.backspace())
        assertFalse(composer.isComposing)
        assertFalse("with nothing composing the field deletes instead", composer.backspace())
    }

    /**
     * A correction can be taken back exactly once, immediately.
     *
     * Backspace straight after an automatic correction means "no, I meant what I typed" -
     * so it puts the typed word back rather than deleting a character of the keyboard's guess.
     */
    @Test
    fun aCorrectionCanBeUndoneOnce() {
        val composer = Composer()
        composer.append("t"); composer.append("e"); composer.append("h")

        assertEquals("the", composer.finish(correction = "the"))
        assertTrue(composer.canUndo)
        assertEquals("the" to "teh", composer.takeUndo())

        assertFalse("an undo is available once, not twice", composer.canUndo)
        assertNull(composer.takeUndo())
    }

    /** A word left alone leaves nothing to undo. */
    @Test
    fun anUncorrectedWordHasNothingToUndo() {
        val composer = Composer()
        composer.append("h"); composer.append("i")
        assertEquals("hi", composer.finish(correction = null))
        assertFalse(composer.canUndo)
        assertNull(composer.takeUndo())
    }

    /** Typing on after a correction clears the undo: it was only ever for the next keystroke. */
    @Test
    fun typingOnClearsTheUndo() {
        val composer = Composer()
        composer.append("t"); composer.append("e"); composer.append("h")
        composer.finish(correction = "the")
        assertTrue(composer.canUndo)
        composer.append("x")
        assertFalse(composer.canUndo)
    }

    /**
     * A word already in the dictionary is not corrected away.
     *
     * The rule that stops `pad` becoming `sad`. Both are ordinary English one adjacent key
     * apart, and no amount of scoring can choose between them - so nothing tries.
     */
    @Test
    fun aRealWordIsLeftAlone() {
        val composer = Composer()
        composer.append("p"); composer.append("a"); composer.append("d")
        assertFalse(composer.shouldAutocorrect("sad", typedIsAWord = true))
        assertTrue(composer.shouldAutocorrect("sad", typedIsAWord = false))
    }

    /** Two characters is too little evidence to correct on. */
    @Test
    fun veryShortWordsAreNotCorrected() {
        val composer = Composer()
        composer.append("h"); composer.append("i")
        assertFalse(composer.shouldAutocorrect("he", typedIsAWord = false))
    }

    /** Nothing with a digit or a symbol in it: that is a code, a password or a URL. */
    @Test
    fun mixedTextIsNeverCorrected() {
        val composer = Composer()
        for (ch in "ab1") composer.append(ch.toString())
        assertFalse(composer.shouldAutocorrect("abc", typedIsAWord = false))
    }
}
