package rocks.gorjan.gokixp.wp81.keyboard.text

/**
 * The word currently being typed, and what the keyboard did to it.
 *
 * Deliberately knows nothing about Android. It holds no `InputConnection`, sends nothing to
 * anybody, and is a plain state machine over strings - which is what lets the awkward parts be
 * tested by asking questions instead of by typing on a phone and watching. The service owns
 * the connection and is responsible for making the text box agree with whatever this says.
 *
 * ## Composing text, and why it is worth the trouble
 *
 * A letter could simply be committed as it is pressed. Then the text box would contain
 * `helol` and there would be nothing to be done about it: the keyboard would have to delete
 * five characters and type five more to fix it, and anything else the app did in between -
 * moving the cursor, running its own autocomplete - would happen in the middle of that.
 *
 * Instead the word in progress is *composing* text: the app is told "this much is provisional",
 * shows it underlined, and lets it be replaced wholesale. That is what makes a correction one
 * operation rather than ten, and it is why the state has to be held somewhere rather than read
 * back out of the field.
 *
 * ## Undo
 *
 * When the keyboard changes a word, it remembers what was actually typed. Backspace immediately
 * afterwards puts the typed text back rather than deleting a character of the correction -
 * because a correction the user did not want is the one case where backspace obviously means
 * "no, I meant what I said", and a keyboard that instead nibbles the end off its own guess is
 * infuriating.
 */
class Composer {

    /** What has been typed for the word in progress. Empty when not composing a word. */
    var typed: String = ""
        private set

    /**
     * What was replaced by what, if the last thing that happened was a correction.
     *
     * Cleared by anything else at all, because it is only ever consulted immediately.
     */
    private var undoFrom: String? = null
    private var undoTo: String? = null

    val isComposing: Boolean get() = typed.isNotEmpty()

    /** True when backspace would undo a correction rather than delete a character. */
    val canUndo: Boolean get() = undoFrom != null

    /**
     * Whether [text] belongs to the word being typed, or ends it.
     *
     * Letters and the apostrophe build a word; everything else finishes one. The distinction
     * decides which of two very different things happens to the field, and getting it wrong
     * is not subtle: composing text is *replaced* rather than appended to, so a character
     * that ends a word but is committed as though it extended one deletes the word. Typing
     * `bi sakal` and holding for a `?` gave `bi ?`.
     */
    fun extendsWord(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isLetter() || it == '\'' }

    fun append(text: String) {
        typed += text
        clearUndo()
    }

    /**
     * Removes the last character of the word in progress.
     *
     * @return false when there was nothing being composed, meaning the service should delete
     *   from the text box itself instead.
     */
    fun backspace(): Boolean {
        clearUndo()
        if (typed.isEmpty()) return false
        typed = typed.dropLast(1)
        return true
    }

    /** Replaces the word in progress outright, for a tapped suggestion. */
    fun replaceWith(word: String) {
        typed = word
        clearUndo()
    }

    /**
     * Picks a finished word back up, for backspacing into one that was already committed.
     *
     * The same assignment as [replaceWith] and a different thing happening: that one swaps a
     * word being typed for a better one, this one turns text the field already holds back
     * into a word being typed. Worth its own name because the caller has to do the other half
     * - handing the field back the same characters as composing text - and a method called
     * `replaceWith` at that call site reads as though it replaced something.
     */
    fun resume(word: String) {
        typed = word
        clearUndo()
    }

    /**
     * The word is over. Returns what should end up in the text box.
     *
     * @param correction the word the keyboard would rather have, or null to leave it alone.
     */
    fun finish(correction: String?): String {
        val original = typed
        typed = ""
        return if (correction != null && correction != original) {
            undoFrom = original
            undoTo = correction
            correction
        } else {
            clearUndo()
            original
        }
    }

    /**
     * Takes the undo, if there is one: the text to put back and the text to take out.
     *
     * Consumed rather than merely read - an undo is available exactly once, immediately after
     * the correction it undoes.
     */
    fun takeUndo(): Pair<String, String>? {
        val from = undoFrom ?: return null
        val to = undoTo ?: return null
        clearUndo()
        return to to from
    }

    /** Anything that is not a correction being made or immediately undone. */
    fun reset() {
        typed = ""
        clearUndo()
    }

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
    }

    /**
     * Whether a correction is confident enough to apply without being asked.
     *
     * The bar always offers the literal text first, so nothing here is irreversible - but an
     * automatic change still has to earn itself, because a keyboard that rewrites correct
     * words is worse than one that never corrects at all.
     *
     * Three conditions, and all of them matter:
     *
     *  - The typed text is not already a word. If it is in the dictionary the user gets what
     *    they typed, full stop. This is the rule that stops `pad` becoming `sad`.
     *  - There is a suggestion, and it is a real one rather than the same word in a different
     *    case.
     *  - Nothing was typed that is not a letter. A word with a digit or a symbol in it is a
     *    password, a code or a URL, none of which anybody wants respelled.
     */
    fun shouldAutocorrect(candidate: String?, typedIsAWord: Boolean): Boolean {
        val word = candidate ?: return false
        if (typedIsAWord) return false
        if (typed.length < MIN_LENGTH) return false
        if (word.equals(typed, ignoreCase = true)) return false
        return typed.all { it.isLetter() || it == '\'' }
    }

    private companion object {
        /** Below this, a correction is a guess. See [Suggester]'s own budget for the same rule. */
        const val MIN_LENGTH = 3
    }
}
