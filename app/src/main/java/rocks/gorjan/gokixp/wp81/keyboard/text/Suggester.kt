package rocks.gorjan.gokixp.wp81.keyboard.text

import rocks.gorjan.gokixp.wp81.keyboard.Action
import rocks.gorjan.gokixp.wp81.keyboard.KeyboardLayout

/** One thing the keyboard is prepared to suggest. */
data class Candidate(
    val word: String,

    /** Higher is better. Only meaningful against other candidates from the same call. */
    val score: Int,

    /** True when this is not simply what was typed, so the caller can mark a correction. */
    val corrected: Boolean
)

/**
 * What word the user probably meant.
 *
 * **This interface is the seam.** Everything else in the keyboard talks to suggestions through
 * this class and nothing else, which is what makes the engine replaceable: if this
 * hand-written speller turns out to disappoint on genuinely sloppy typing, AOSP's own decoder
 * - Apache 2.0, and far better at this - can be dropped in underneath without the key grid,
 * the candidate bar or the composing logic knowing anything happened. Keep it that way.
 *
 * ## How it works
 *
 * A bounded edit-distance search, walked over the trie rather than over a word list. The
 * insight that makes it fast enough to run on every keystroke: while walking down the trie,
 * carry one row of the Levenshtein matrix per node. That row is the edit distance from the
 * typed text to the prefix spelled by the path so far, at every possible alignment. Because
 * the row can only ever increase as the path lengthens, the moment its smallest entry exceeds
 * the budget, *every word in that entire subtree* is too far away and the branch is abandoned.
 * A single `min` over twenty numbers prunes tens of thousands of words.
 *
 * Three refinements on the textbook version, each earning its keep:
 *
 *  - Substitution is charged by how far apart the keys actually are ([Proximity]), not a flat
 *    rate. This is what separates a keyboard that corrects thumbs from one that corrects
 *    spelling.
 *  - Transposition is a single edit, not two ([Damerau](https://en.wikipedia.org/wiki/Damerau-Levenshtein_distance)).
 *    `teh` for `the` is the commonest typo there is and charging it double would bury it.
 *  - The tail of a longer word is nearly free, so completions come out of the same search as
 *    corrections. Typing `hel` finds `hello` without a separate prefix walk, and the small
 *    per-character charge is what makes `help` rank above `helicopter`.
 */
class Suggester(
    private val dictionary: Dictionary?,
    layout: KeyboardLayout,
    private val user: UserDictionary? = null
) {

    private val proximity = Proximity(layout)

    /**
     * The letters that sit directly above the space bar on this layout.
     *
     * Which is to say: the letters somebody gets when they reach for a space and land short.
     * It is the oldest mis-hit on a touch keyboard - the thumb flattens as it comes down and
     * the point Android reports is above where the person believes they are pointing - and
     * `Key.overhangTop` already stretches the space bar's touch area up into this row to
     * catch some of it. This catches what still gets through, after the fact: an `n` between
     * two words is far more likely to be a space than an `n`.
     *
     * Read off the geometry rather than written down as `bnm`, because the geometry is what
     * decides it and it is different in every alphabet. The space bar spans a range of
     * columns; the letters whose centres fall inside that range are the ones above it.
     */
    private val overSpace: Set<Char> = run {
        val spaceRow = layout.rows.indexOfFirst { row -> row.keys.any { it.action == Action.SPACE } }
        if (spaceRow <= 0) return@run emptySet()

        var x = layout.rows[spaceRow].indentStart
        var from = 0f
        var to = 0f
        for (key in layout.rows[spaceRow].keys) {
            if (key.action == Action.SPACE) {
                from = x
                to = x + key.span
            }
            x += key.span
        }

        val above = layout.rows[spaceRow - 1]
        var cursor = above.indentStart
        val letters = mutableSetOf<Char>()
        for (key in above.keys) {
            val centre = cursor + key.span / 2f
            if (key.action == null && key.output.length == 1 && centre in from..to) {
                letters.add(key.output[0].lowercaseChar())
            }
            cursor += key.span
        }
        letters
    }

    /** One row of the edit-distance matrix per level of the trie, reused between searches. */
    private val rows = Array(MAX_DEPTH + 1) { IntArray(MAX_TYPED + 1) }

    /** The characters spelling the current path, so a candidate can be read off on arrival. */
    private val path = CharArray(MAX_DEPTH)

    private val found = ArrayList<Candidate>(64)

    /**
     * What is being searched for, held for the duration of one call.
     *
     * [accept] needs it for two decisions that cannot be made from the edit-distance row
     * alone - whether a candidate is a completion of the typed text, and whether it *is* the
     * typed text - and threading it through the recursion as an argument would put it on
     * every frame of a walk that visits tens of thousands of nodes.
     */
    private var searching: String = ""

    /** Nodes touched by the current completion walk, against [MAX_COMPLETION_NODES]. */
    private var visited = 0

    /** The lowest score currently kept, so a hopeless candidate costs no allocation. */
    private var worstKept = Int.MIN_VALUE

    /**
     * The best few words for what has been typed so far.
     *
     * @param typed what the user has actually pressed. Returned candidates never include it -
     *   the caller is expected to offer the literal text itself, always and first, so that an
     *   unwanted correction is one tap to reject.
     * @param previousWord the word before this one, for next-word prediction. Only the
     *   learned dictionary has an opinion; the shipped one holds no bigrams.
     */
    fun candidates(typed: CharSequence, previousWord: String? = null): List<Candidate> {
        found.clear()
        worstKept = Int.MIN_VALUE
        if (typed.isEmpty() || typed.length > MAX_TYPED) return emptyList()

        searching = typed.toString()
        val budget = budgetFor(typed.length)

        // Two searches, because completing a word and correcting one are different problems
        // and one search cannot be good at both.
        //
        // Completion follows the typed text exactly - a single path down the trie, costing
        // one binary search per character - and then reads off the best words below where it
        // lands. That path is cheap enough to follow a long way, which is what finds
        // `keyboard` eight characters deep from `keyb`.
        //
        // Correction is the edit-distance walk, and it is expensive: it branches. So it is
        // held to a short tail. Letting it run deep as well was measured at nine milliseconds
        // a keystroke on a laptop, which is most of a frame here and would be a visible lag
        // on a phone. Between them they still cover a typo *and* a completion in the same
        // word, which is the case that needs both.
        //
        // Both of them are skipped entirely when no word list shipped for this language,
        // which is the ordinary case: twenty-two layouts, two dictionaries. The learned pass
        // below still runs, so a language with no dictionary is not a language with no
        // suggestions - it is one whose suggestions are all words this person taught it.
        dictionary?.let { dict ->
            complete(dict, typed)
            apostrophes(dict, typed)
            splits(dict, typed)

            if (budget > 0) {
                visited = 0
                val first = rows[0]
                // Row zero: the cost of having typed j characters against the empty prefix,
                // which is j deletions. The boundary condition the recurrence hangs off.
                for (j in 0..typed.length) first[j] = j * DELETE
                walk(dict, dict.root(), 0, typed, budget, first[typed.length])
            }
        }

        // The learned dictionary gets the same treatment as the shipped one but is consulted
        // separately, because it is a different shape - a short list rather than a trie - and
        // is small enough that scanning it costs nothing.
        user?.let { collectLearned(it, typed, previousWord, budget) }

        // If the keyboard can finish the word, it should not also offer to shorten it.
        //
        // `key` is reachable from `keyb` by one deletion and is one of the commoner words in
        // English, so on frequency alone it beats `keyboard` - and suggesting a word shorter
        // than what has already been typed is almost always wrong. Deletions stay available
        // for when nothing completes, which is when they are genuinely the answer (`helllo`).
        val completes = found.any { it.word.length > typed.length }
        if (completes) found.removeAll { it.word.length < typed.length }

        found.sortByDescending { it.score }
        return if (found.size <= LIMIT) ArrayList(found) else ArrayList(found.subList(0, LIMIT))
    }

    /**
     * Whether [word] is spelled the way the dictionary spells it.
     *
     * What stops the keyboard rewriting correct words. `pad` and `sad` are one adjacent key
     * apart and both ordinary English, so no amount of scoring can reliably choose between
     * them - the answer is not to try: if what was typed is already a word, it stands.
     *
     * The learned dictionary counts too, which is what makes tapping a rejected correction
     * stick. Once a word has been learned, it is a word.
     */
    fun knows(word: CharSequence): Boolean {
        if (word.isEmpty()) return false
        if (dictionary?.contains(word) == true) return true
        return user?.knows(word.toString()) == true
    }

    /**
     * Words that could follow [previousWord], for the bar to show before anything is typed.
     *
     * Empty until the user has typed enough for the keyboard to have learned some pairs. The
     * shipped word lists are frequency-only - the corpus behind them was tokenized in a way
     * that discarded word order entirely - so this is the one part of prediction that starts
     * out knowing nothing and gets better with use.
     */
    fun following(previousWord: String?): List<Candidate> {
        val word = previousWord ?: return emptyList()
        val learned = user ?: return emptyList()
        return learned.following(word).take(LIMIT).map { (next, weight) ->
            Candidate(next, weight, corrected = true)
        }
    }

    /**
     * Everything the typed text is the beginning of, best first.
     *
     * Follows the typed characters exactly and then explores what is underneath. No
     * edit-distance row is involved, so there is no branching on the way down and the descent
     * costs one binary search per character typed.
     *
     * The exploration below is bounded twice over: by how much tail is worth having, and by a
     * hard cap on nodes visited. The cap is what makes a one-character prefix safe - `a` sits
     * above a sizeable fraction of the entire dictionary, and without a limit the first
     * keystroke of every word would walk all of it.
     */
    private fun complete(dict: Dictionary, typed: CharSequence) {
        var node = dict.root()
        for (i in typed.indices) {
            val edge = dict.edgeFor(node, typed[i].lowercaseChar())
            if (edge < 0) return
            typed[i].let { path[i] = it }
            if (i == typed.lastIndex) {
                node = dict.childAt(node, edge)
                if (node == Dictionary.NONE) return
            } else {
                node = dict.childAt(node, edge)
                if (node == Dictionary.NONE) return
            }
        }
        visited = 0
        gather(dict, node, typed.length, typed.length)
    }

    /** Depth-first under a matched prefix, collecting words until the budget runs out. */
    private fun gather(dict: Dictionary, node: Int, depth: Int, typedLength: Int) {
        if (depth >= MAX_DEPTH || depth - typedLength >= MAX_COMPLETION_TAIL) return
        if (visited >= MAX_COMPLETION_NODES) return

        val children = dict.childCount(node)
        for (edge in 0 until children) {
            if (visited++ >= MAX_COMPLETION_NODES) return
            path[depth] = dict.charAt(node, edge)
            if (dict.isWord(node, edge)) {
                // Everything found under a matched prefix is by definition a completion.
                offer(depth + 1, (depth + 1 - typedLength) * TAIL, dict.frequency(node, edge), true)
            }
            val child = dict.childAt(node, edge)
            if (child != Dictionary.NONE) gather(dict, child, depth + 1, typedLength)
        }
    }

    /**
     * Walks one node, carrying the edit-distance row down with it.
     *
     * Depth-first and iterative over each node's edges. Recursion depth is bounded by the
     * longest word in the dictionary, which the builder caps at 32.
     */
    /**
     * @param consumed the cheapest cost, anywhere on the path so far, at which the whole of
     *   the typed text had been accounted for. This is what makes a completion's tail free -
     *   see the pruning note at the foot of the loop.
     */
    private fun walk(
        dict: Dictionary,
        node: Int,
        depth: Int,
        typed: CharSequence,
        budget: Int,
        consumed: Int
    ) {
        if (depth >= MAX_DEPTH) return
        val previous = rows[depth]
        val current = rows[depth + 1]
        val m = typed.length

        val children = dict.childCount(node)
        for (edge in 0 until children) {
            if (visited++ >= MAX_CORRECTION_NODES) return
            val ch = dict.charAt(node, edge)
            path[depth] = ch

            // Column zero: this candidate is `depth + 1` characters long and nothing has been
            // typed to match them against yet, so each one is an insertion.
            current[0] = previous[0] + INSERT
            var best = current[0]

            for (j in 1..m) {
                val substitution = previous[j - 1] + proximity.substitute(typed[j - 1], ch)
                val insertion = previous[j] + INSERT
                val deletion = current[j - 1] + DELETE
                var cost = if (substitution < insertion) substitution else insertion
                if (deletion < cost) cost = deletion

                // Two adjacent characters the wrong way round. Charged once, not twice, and
                // read off the row two levels up because that is the last point at which
                // neither of them had been consumed.
                if (depth >= 1 && j >= 2 &&
                    typed[j - 1] == path[depth - 1] && typed[j - 2] == ch
                ) {
                    val transposed = rows[depth - 1][j - 2] + TRANSPOSE
                    if (transposed < cost) cost = transposed
                }

                current[j] = cost
                if (cost < best) best = cost
            }

            // The cheapest point on this path at which the typed text was fully accounted
            // for. A word is charged from there, not from here, which is what makes the tail
            // of a completion free: having matched `keyb` exactly, the `oard` that follows
            // costs the small per-character charge below and not four insertions.
            val reached = if (current[m] < consumed) current[m] else consumed

            if (dict.isWord(node, edge) && reached <= budget) {
                val extra = (depth + 1 - m).coerceAtLeast(0)
                offer(depth + 1, reached + extra * TAIL, dict.frequency(node, edge), false)
            }

            // Two ways to be worth continuing.
            //
            // The first is the textbook one: the row can only grow as the path lengthens, so
            // once its smallest entry is over budget every word below is out of reach. That
            // single `min` prunes tens of thousands of words per keystroke.
            //
            // The second exists because the first is wrong for completions, and getting this
            // wrong is silent. Walking `keyb` down towards `keyboard`, every character past
            // the fourth adds an insertion to the row, so by `keyboa` the whole row is over
            // budget and the branch is abandoned - the keyboard would offer completions of
            // one or two characters and simply never find longer ones. So a path that has
            // already consumed the typed text cheaply keeps going regardless of the row, up
            // to a limit on how much tail is worth exploring.
            val worthContinuing = (best <= budget ||
                (reached <= budget && depth + 1 < m + MAX_TAIL)) &&
                visited < MAX_CORRECTION_NODES
            if (worthContinuing) {
                val child = dict.childAt(node, edge)
                if (child != Dictionary.NONE) {
                    walk(dict, child, depth + 1, typed, budget, reached)
                }
            }
        }
    }

    private fun collectLearned(
        learned: UserDictionary,
        typed: CharSequence,
        previousWord: String?,
        budget: Int
    ) {
        val prefix = typed.toString()
        for ((word, weight) in learned.matching(prefix)) {
            // A learned word is only offered as an exact prefix match, never as a correction.
            // The list is small and personal, and guessing at it aggressively would turn one
            // mistyped word that happened to get learned into a permanent wrong answer.
            val cost = (word.length - prefix.length).coerceAtLeast(0) * TAIL
            keep(word, COMPLETION_BONUS + weight * FREQUENCY_WEIGHT - cost * COST_WEIGHT)
        }
        if (previousWord != null) {
            for ((word, weight) in learned.following(previousWord)) {
                if (word.startsWith(prefix, ignoreCase = true)) {
                    keep(word, COMPLETION_BONUS + (weight + BIGRAM_BONUS) * FREQUENCY_WEIGHT)
                }
            }
        }
    }

    /**
     * Scores what the path currently spells, and keeps it if it is good enough.
     *
     * **Scored before the string is built.** That ordering is the whole point of this method
     * and it is worth a note, because getting it the other way round was measured: allocating
     * a `String` for every word the walk touched, then comparing it against a list that was
     * allowed to grow without bound, cost fourteen milliseconds a keystroke on `hel` - most of
     * a frame, for thousands of words that were never going to be shown. The score needs only
     * the length, the cost and the frequency, all of which are already to hand, so almost
     * every candidate can be rejected without allocating anything at all.
     *
     * Frequency dominates and cost discounts it. Both are needed: frequency alone would offer
     * `the` for everything, and cost alone would rank an exact match on a word nobody uses
     * above a one-key slip on a word everybody does.
     */
    /**
     * The apostrophe people leave out.
     *
     * `shell` for `she'll`, `ill` for `i'll`, `dont`, `cant`, `im`, `were`, `shes`. This is
     * not a typo in the sense the edit-distance walk understands - nobody's thumb slipped -
     * it is a character deliberately skipped because reaching it means holding a key, and it
     * is one of the most common things anybody does when typing quickly on a phone.
     *
     * Given its own pass rather than left to the general search, for two reasons. It would
     * not be found there: an omitted apostrophe is an insertion in the *middle* of a word,
     * and the correction walk is deliberately held to a short tail so that it stays fast.
     * And making insertions cheap enough to find it would make every other insertion cheap
     * too, which is a great deal of noise for one narrow case.
     *
     * The cost of doing it directly is trivial: one exact lookup per position, each a walk
     * of a few nodes, on a word of typically five or six letters. What comes back is only
     * ever a real word, because that is the only thing the dictionary can answer with.
     */
    private fun apostrophes(dict: Dictionary, typed: CharSequence) {
        if (typed.length < 2 || typed.length >= MAX_DEPTH) return
        // Somebody who typed one is not somebody who left one out.
        for (c in typed) if (c == '\'') return

        val buffer = CharArray(typed.length + 1)
        for (at in 1 until typed.length) {
            var w = 0
            for (i in typed.indices) {
                if (i == at) buffer[w++] = '\''
                buffer[w++] = typed[i].lowercaseChar()
            }
            val word = String(buffer, 0, buffer.size)
            val frequency = dict.frequencyOf(word)
            if (frequency <= 0) continue
            keep(word, APOSTROPHE_BONUS + frequency * FREQUENCY_WEIGHT)
        }
    }

    /**
     * Two words run together, with or without a letter where the space should be.
     *
     * `helloworld`, because the space did not register. `hellonworld`, because the thumb
     * reached for the space bar and caught the `n` above it - see [overSpace]. Both are
     * ordinary and neither is reachable by the edit-distance walk, which corrects *a* word
     * and has no notion that there might be two.
     *
     * **Only when what was typed is not itself a word**, and that single rule is what makes
     * this usable rather than a nuisance. Half the language can be cut into two shorter
     * words - `therein`, `anytime`, `into`, `nowhere` - and offering to break up a word
     * somebody has correctly typed is worse than useless. If the dictionary recognises what
     * is there, it is what was meant.
     *
     * Scored by the *weaker* of the two halves, because a split is only as convincing as its
     * least convincing word: `hello world` is two common words, while a split that leans on
     * some obscurity to make its second half is almost certainly a coincidence.
     */
    private fun splits(dict: Dictionary, typed: CharSequence) {
        if (typed.length < MIN_PART * 2 || typed.length >= MAX_DEPTH) return

        val lower = typed.toString().lowercase()
        if (dict.contains(lower)) return

        for (at in MIN_PART..lower.length - MIN_PART) {
            // Nothing between the words: the space simply did not land.
            offerSplit(dict, lower, at, at)
        }
        for (at in MIN_PART..lower.length - MIN_PART - 1) {
            // Or a letter where the space should be, and only one that could have been it.
            if (lower[at] in overSpace) offerSplit(dict, lower, at, at + 1)
        }
    }

    /** One candidate split: `[0, leftEnd)` and `[rightStart, end)`, if both are words. */
    private fun offerSplit(dict: Dictionary, lower: String, leftEnd: Int, rightStart: Int) {
        val left = lower.substring(0, leftEnd)
        val right = lower.substring(rightStart)
        val leftFrequency = frequencyOfPart(dict, left)
        if (leftFrequency <= 0) return
        val rightFrequency = frequencyOfPart(dict, right)
        if (rightFrequency <= 0) return

        // Charged for, not rewarded. A split says a whole character is missing, which is the
        // same claim an insertion makes, so it pays the same price - and then has to win on
        // the merits of its two words like everything else does on the merits of its one.
        //
        // It was a flat bonus, and that was wrong in a way worth remembering: a bonus put
        // every split above every ordinary correction whatever the words were, so `aito`
        // offered `ai to` ahead of `auto`, which is one adjacent key away and obviously what
        // was meant. A split has to be able to lose.
        val score = minOf(leftFrequency, rightFrequency) * FREQUENCY_WEIGHT - INSERT * COST_WEIGHT
        keep("$left $right", score)
    }

    /**
     * How common a half of a split is, with two-letter halves held to a higher standard.
     *
     * The junk splits are almost all two letters: any four-letter word can be cut into two
     * pairs, and enough pairs are in a dictionary of this size to make that a real nuisance -
     * `ai`, which is what put `ai to` ahead of `auto`. But the two-letter words that matter
     * are all among the commonest words in the language - `in`, `to`, `of`, `we`, `he`, `is` -
     * so requiring a two-letter half to be genuinely common costs nothing real and removes
     * nearly all of it.
     */
    private fun frequencyOfPart(dict: Dictionary, part: String): Int {
        val frequency = dict.frequencyOf(part)
        if (part.length == MIN_PART && frequency < COMMON) return 0
        return frequency
    }

    private fun offer(length: Int, cost: Int, frequency: Int, completes: Boolean) {
        if (length < 2) return
        val score = (if (completes) COMPLETION_BONUS else 0) +
            frequency * FREQUENCY_WEIGHT - cost * COST_WEIGHT
        // The cheap rejection, and the reason this is fast.
        if (found.size >= KEEP && score <= worstKept) return
        keep(String(path, 0, length), score)
    }

    /**
     * Adds a scored candidate, holding the list to [KEEP] entries.
     *
     * Bounded because the searches between them find far more words than the bar can show,
     * and because everything here is O(the list): a list of hundreds turns the duplicate check
     * into the most expensive thing in the keyboard.
     */
    private fun keep(word: String, score: Int) {
        // Never the literal text. The caller offers that itself, first and always, so that
        // rejecting a correction is one tap; offering it twice would push a real suggestion
        // out of a bar that only holds a handful.
        if (word.equals(searching, ignoreCase = true)) return

        for (i in found.indices) {
            if (found[i].word == word) {
                // The same word can arrive from both searches, and from the learned list.
                if (score > found[i].score) {
                    found[i] = Candidate(word, score, true)
                    recomputeWorst()
                }
                return
            }
        }

        if (found.size < KEEP) {
            found.add(Candidate(word, score, corrected = true))
        } else {
            var worst = 0
            for (i in found.indices) if (found[i].score < found[worst].score) worst = i
            if (found[worst].score >= score) return
            found[worst] = Candidate(word, score, corrected = true)
        }
        recomputeWorst()
    }

    private fun recomputeWorst() {
        var worst = Int.MAX_VALUE
        for (candidate in found) if (candidate.score < worst) worst = candidate.score
        worstKept = worst
    }

    /**
     * How wrong the typed text is allowed to be, by how much of it there is.
     *
     * Nothing at all for one or two characters. At that length almost every word in the
     * language is within one edit, so a correction is not a correction but a guess, and a
     * keyboard that replaces `hi` with `he` because `he` is commoner is a keyboard people
     * turn off. The budget opens up as there is more evidence to go on.
     */
    private fun budgetFor(length: Int): Int = when {
        length <= 2 -> 0
        length <= 5 -> ONE_EDIT
        else -> TWO_EDITS
    }

    private companion object {

        /**
         * What each kind of mistake costs.
         *
         * Substitution is not here: it comes from [Proximity], and ranges from 55 for a
         * neighbouring key to 130 for one across the keyboard. These are set relative to that
         * band - an inserted or dropped character is worse than a near miss but better than
         * hitting the wrong side of the keyboard, because dropping a letter is something
         * fast typing does and reaching six keys away is not.
         */
        const val INSERT = 105
        const val DELETE = 105
        const val TRANSPOSE = 85

        /** Per character of a completion's tail. Small: completing is normal, not an error. */
        const val TAIL = 12

        /**
         * How far past the typed text the *correction* walk carries a free tail.
         *
         * Short, because that walk branches and every extra level multiplies. Four is enough
         * to catch a mistyped character and finish the word - `kwyboard` for `keyboard` - and
         * the deep cases are the completion walk's job instead.
         */
        const val MAX_TAIL = 4

        /** How far past the typed text a completion is worth chasing, and how hard to look. */
        const val MAX_COMPLETION_TAIL = 14
        const val MAX_COMPLETION_NODES = 6_000

        /**
         * A ceiling on the correction search, so that nothing can cost a frame.
         *
         * The row's own pruning is what keeps this search cheap in the ordinary case, and it
         * does that well - but how well depends entirely on what was typed, and the worst
         * inputs are several times the cost of the typical ones. A keyboard may be a little
         * less clever about an unusual word; it may not stutter. Reached in practice only by
         * long inputs with a two-edit budget.
         */
        const val MAX_CORRECTION_NODES = 60_000

        /** One near miss, and two. See [budgetFor]. */
        const val ONE_EDIT = 135
        const val TWO_EDITS = 250

        const val FREQUENCY_WEIGHT = 1000
        const val COST_WEIGHT = 380

        /**
         * What finishing the typed word is worth, against merely being near it.
         *
         * Big enough that finishing a word beats shortening it, and deliberately *not* big
         * enough to be absolute: a completion of something obscure should still lose to a
         * one-key slip on a word everybody uses. Typing `helli` ought to offer `hello` ahead
         * of `hellish`, and that only works if frequency can still outvote this. See [accept].
         */
        const val COMPLETION_BONUS = 80_000

        /**
         * What a restored apostrophe is worth, against the words that merely start the same.
         *
         * Above [COMPLETION_BONUS] on purpose. Both are longer words that begin with what was
         * typed, so they are competing for the same slots - but somebody who typed `ill` is
         * far more likely to have meant `i'll` than `illustration`, and the frequency alone
         * does not say so. Frequency still orders them among themselves.
         */
        const val APOSTROPHE_BONUS = 100_000

        /**
         * How common a two-letter half of a split has to be. See [frequencyOfPart].
         *
         * On the same log-scaled 1-255 the dictionary uses, where `the` is 251 and `to` is
         * 247. Every two-letter word anybody writes is up here; `ai`, at 121, is not.
         */
        const val COMMON = 200

        /**
         * The shortest either half of a split may be.
         *
         * Two, so that `a` and `i` cannot be split off. They are real words and splitting on
         * them is almost always wrong - `ahead` is not `a head`, `island` is not `i sland` -
         * and at one letter there is nothing to be confident about.
         */
        const val MIN_PART = 2

        /** What a learned pairing is worth on top of the word's own weight. */
        const val BIGRAM_BONUS = 120

        /** The bar shows a handful; searching for more would be work thrown away. */
        const val LIMIT = 8

        /**
         * How many candidates are held during a search.
         *
         * More than the bar shows, because the two searches and the learned list arrive in no
         * particular order and a late one may beat an early one - but small, because every
         * operation here is a scan of it. See [keep].
         */
        const val KEEP = 24

        /** The builder caps words at 32 characters. */
        const val MAX_DEPTH = 32
        const val MAX_TYPED = 32
    }
}
