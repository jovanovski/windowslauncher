package rocks.gorjan.gokixp.wp81.keyboard.text

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The words this particular person uses, and which words they follow.
 *
 * **Touched from two threads.** Words are learned on the thread running the keyboard, and read
 * by the one working out suggestions - see the executor in `WP81KeyboardService`. Every method
 * that touches the maps is synchronised for that reason; the contents are small and the lock
 * is never held across anything slow (the file is written from a snapshot taken under it).
 *
 * Two jobs. It learns vocabulary the shipped dictionary has never heard of - names, places,
 * jargon, the way you actually spell things - so that typing them stops being a fight. And it
 * learns *pairs*, which is half of next-word prediction: [Bigrams] ships what the language
 * tends to do, and this is what *you* tend to do, merged over the top of it and winning where
 * the two disagree. See `Suggester.followers` for how that merge is arranged, and [weigh] for
 * the scale it turns on - a pair watched five times is worth as much as anything the corpus
 * has to say, and a pair watched more than that is worth more.
 *
 * ## Where it is kept, and why that matters
 *
 * In [Context.getNoBackupFilesDir], which is the one part of an app's storage that Android's
 * automatic cloud backup will not touch.
 *
 * This is not a detail. This app declares `allowBackup="true"` and both of its backup rule
 * files are empty stubs, which means the default applies and *everything* in `filesDir`,
 * `databases` and `shared_prefs` is copied to the user's cloud backup. A record of every word
 * somebody has typed on their phone is the last thing that should be going anywhere, and for
 * a keyboard whose reason to exist is not sending typing to Google it would be a flat
 * contradiction. `noBackupFilesDir` needs no manifest rule and cannot be undone by someone
 * later filling in `backup_rules.xml` without noticing this.
 *
 * A flat file rather than SQLite. The contents are a few thousand short strings with counters;
 * a database brings a schema to migrate, a helper to open and a cursor to close for something
 * that is read once at startup and rewritten in one go. It is capped and pruned rather than
 * allowed to grow, so it stays small enough for that to remain true.
 */
class UserDictionary private constructor(private val file: File) {

    private val words = HashMap<String, Int>()

    /** Keyed on the preceding word; the value is what followed it, and how often. */
    private val pairs = HashMap<String, HashMap<String, Int>>()

    /**
     * What the user has thrown away, and the reason there has to be a way to.
     *
     * A dictionary that only grows only gets worse at the thing it was added for. One
     * mistyped word is learned like any other and comes back as a suggestion for months -
     * this phone had `thank fod` in it inside a day - and until there was a bin the only
     * cure was to keep typing the right word until it outweighed the wrong one.
     *
     * Blocking rather than merely deleting, because deleting alone does not answer the
     * question. Removing a learned pair takes it out of *this* map and leaves whatever
     * [Bigrams] has to say about the same two words untouched, so a suggestion the user has
     * just thrown in the bin can come straight back from the other source. A block is the
     * only thing that means "not this one, from anywhere".
     *
     * Both are read on the suggestion path from another thread and written from the keyboard
     * thread, and both are replaced rather than mutated for that reason: a reader holds a
     * whole consistent set and never sees one half-built. They are also nearly always empty,
     * which the readers check before doing any work at all.
     */
    @Volatile
    private var blockedWords: Set<String> = emptySet()

    /** Blocked pairs, as `previous` and `next` joined by a tab. See [blockedWords]. */
    @Volatile
    private var blockedPairs: Set<String> = emptySet()

    private val dirty = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wp81-keyboard-dictionary").apply { isDaemon = true }
    }

    /**
     * Notes that [word] was used.
     *
     * Also the answer to a rejected correction. When the user taps the literal text they typed
     * instead of the word the keyboard offered, the honest reading is "this is a word" - so it
     * is learned, and from then on it is a word the keyboard knows rather than one it keeps
     * trying to fix. That is a better mechanism than a list of grudges, because it makes the
     * keyboard right rather than merely quiet.
     */
    @Synchronized
    fun learn(word: String) {
        if (!worthLearning(word)) return
        val key = word.lowercase()
        words[key] = (words[key] ?: 0) + 1
        dirty.set(true)
        // Pruned here as well as when writing, so that a long session cannot grow the map
        // without bound - everything that reads it walks it.
        if (words.size > MAX_WORDS * 2) prune()
    }

    /** Notes that [next] followed [previous]. */
    @Synchronized
    fun learnPair(previous: String, next: String) {
        if (!worthLearning(previous) || !worthLearning(next)) return
        val followers = pairs.getOrPut(previous.lowercase()) { HashMap() }
        val key = next.lowercase()
        followers[key] = (followers[key] ?: 0) + 1
        dirty.set(true)
    }

    /**
     * Learned words starting with [prefix], as (word, weight) pairs.
     *
     * Weights are on the same 1-255 scale the shipped dictionary uses, so the suggester can
     * rank the two together without knowing which is which. The scale is deliberately
     * compressed: a word used once should beat an obscure dictionary entry and should not beat
     * `the`, however many times it has been typed.
     */
    @Synchronized
    fun matching(prefix: String): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.lowercase()
        val blocked = blockedWords
        val out = ArrayList<Pair<String, Int>>(4)
        for ((word, count) in words) {
            if (word.length > key.length && word.startsWith(key) && word !in blocked) {
                out.add(word to weigh(count))
            }
        }
        return out
    }

    /** Whether this word has been learned, so that it stops being corrected away. */
    @Synchronized
    fun knows(word: String): Boolean = words.containsKey(word.lowercase())

    /** What has followed [word] before, best first. */
    @Synchronized
    fun following(word: String): List<Pair<String, Int>> {
        val previous = word.lowercase()
        val followers = pairs[previous] ?: return emptyList()
        return followers.entries
            .sortedByDescending { it.value }
            .filter { !isBlockedAfter(previous, it.key) && it.key !in blockedWords }
            .map { it.key to weighPair(it.value) }
    }

    /**
     * Whether [word] has been thrown away and must not be offered by anybody.
     *
     * Checked on the hot path - every candidate the trie search keeps runs through it - so
     * the empty case, which is almost every keyboard, costs one field read and one `isEmpty`
     * and allocates nothing. Not synchronised, deliberately: the set is replaced whole rather
     * than mutated, so a reader either sees the state before a block or the state after it.
     */
    fun isBlocked(word: String): Boolean {
        val blocked = blockedWords
        return blocked.isNotEmpty() && word.lowercase() in blocked
    }

    /** Whether [next] has been thrown away as a follower of [previous] specifically. */
    fun isBlockedAfter(previous: String, next: String): Boolean {
        val blocked = blockedPairs
        return blocked.isNotEmpty() &&
            "${previous.lowercase()}\t${next.lowercase()}" in blocked
    }

    /**
     * Throws away [next] as something that follows [previous].
     *
     * The narrow one, and the right default: binning a prediction says "not after this word",
     * not "never again". Somebody who does not want `see` followed by `ya` still wants to be
     * able to type `ya`.
     */
    @Synchronized
    fun forgetPair(previous: String, next: String) {
        val before = previous.lowercase()
        val after = next.lowercase()
        pairs[before]?.let {
            it.remove(after)
            if (it.isEmpty()) pairs.remove(before)
        }
        blockedPairs = cap(blockedPairs + "$before\t$after")
        dirty.set(true)
    }

    /**
     * Throws away [word] altogether: not as a word, not after anything.
     *
     * For the case the narrow one cannot fix. A word the keyboard knows *only* because it
     * watched somebody type it is a word that exists by accident when the typing was a
     * mistake, and leaving it in place means it comes back tomorrow as a completion of its
     * own first two letters. So the pairs on both sides of it go with it.
     */
    @Synchronized
    fun forgetWord(word: String) {
        val key = word.lowercase()
        words.remove(key)
        pairs.remove(key)
        for (followers in pairs.values) followers.remove(key)
        blockedWords = cap(blockedWords + key)
        dirty.set(true)
    }

    /** Whether anything was ever learned about [word], so the bin can say what it did. */
    @Synchronized
    fun knowsAnythingAbout(word: String, after: String?): Boolean {
        val key = word.lowercase()
        if (words.containsKey(key)) return true
        return after != null && pairs[after.lowercase()]?.containsKey(key) == true
    }

    /** Keeps a block list from growing without bound, dropping what was blocked longest ago. */
    private fun cap(blocked: Set<String>): Set<String> =
        if (blocked.size <= MAX_BLOCKED) blocked
        else blocked.drop(blocked.size - MAX_BLOCKED).toSet()

    /** Drops the least-used half when the map has grown past twice its keeping size. */
    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS)
        words.clear()
        for (entry in keep) words[entry.key] = entry.value
    }

    private fun weigh(count: Int): Int =
        (LEARNED_FLOOR + count * LEARNED_STEP).coerceAtMost(LEARNED_CEILING)

    /**
     * The same idea for a *pair*, on a scale of its own, and the difference is the point.
     *
     * A word you have typed once is strong evidence about you: people reuse their own
     * vocabulary, and there was nothing to contradict it - [weigh] can afford to start high.
     * A pair you have typed once is not the same claim at all. It is one adjacency, and the
     * commonest way to produce a new one is to mistype the second word, which is exactly what
     * this device had in it while the shipped table was being tested: `thank fod`, seen once,
     * outranking `thank you` from a corpus that had seen it two hundred thousand times.
     *
     * So a pair starts below the middle of what [Bigrams] can say and climbs faster. One
     * sighting is worth less than a decent shipped pair and more than a weak one; five and it
     * beats all but the handful the corpus is surest of, six and it beats those too, because
     * by then it is not an accident, it is how this person writes. Before there was a table to be wrong against, none of these numbers
     * meant anything - learned pairs were only ever ordered against each other.
     */
    private fun weighPair(count: Int): Int =
        (PAIR_FLOOR + count * PAIR_STEP).coerceAtMost(PAIR_CEILING)

    /**
     * Whether something is worth remembering at all.
     *
     * Single characters carry no information, and anything with a digit or punctuation in it
     * is usually a password fragment, a URL or a code - none of which the keyboard should be
     * offering back later, and some of which it should never have seen.
     */
    private fun worthLearning(word: String): Boolean =
        word.length in 2..MAX_WORD && word.all { it.isLetter() || it == '\'' }

    // ---------------------------------------------------------------- storage

    private fun load() {
        if (!file.exists()) return
        val loadedBlockedWords = HashSet<String>()
        val loadedBlockedPairs = HashSet<String>()
        try {
            file.forEachLine { line ->
                val parts = line.split('\t')
                when {
                    parts.size == 3 && parts[0] == "w" ->
                        parts[2].toIntOrNull()?.let { words[parts[1]] = it }
                    parts.size == 4 && parts[0] == "b" ->
                        parts[3].toIntOrNull()?.let {
                            pairs.getOrPut(parts[1]) { HashMap() }[parts[2]] = it
                        }
                    // The bin. Unknown line types are skipped rather than refused, which is
                    // what lets these two be added to a file an older build wrote - and what
                    // lets that older build go on reading a file this one wrote.
                    parts.size == 2 && parts[0] == "x" -> loadedBlockedWords.add(parts[1])
                    parts.size == 3 && parts[0] == "y" ->
                        loadedBlockedPairs.add(parts[1] + "\t" + parts[2])
                }
            }
        } catch (e: Exception) {
            // A truncated or garbled file is worth nothing and is not worth crashing over;
            // starting again from an empty one costs the user their learned words and
            // nothing else.
            words.clear()
            pairs.clear()
            loadedBlockedWords.clear()
            loadedBlockedPairs.clear()
        }
        blockedWords = loadedBlockedWords
        blockedPairs = loadedBlockedPairs
    }

    /**
     * Writes the file, on a background thread, if anything has changed.
     *
     * Called when input finishes rather than after every word: a keyboard that touched the
     * disk on each space would be doing it several times a second while someone types.
     */
    fun flush() {
        if (!dirty.getAndSet(false)) return
        val snapshot = snapshot()
        writer.execute {
            try {
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(snapshot)
                // Renamed into place so that being killed mid-write leaves the previous file
                // intact rather than half of a new one.
                if (!temporary.renameTo(file)) {
                    file.writeText(snapshot)
                    temporary.delete()
                }
            } catch (e: Exception) {
                // Losing what was learned is a disappointment, not a failure worth reporting.
            }
        }
    }

    /**
     * The file's contents, and where pruning happens.
     *
     * Taken on the calling thread so the writer never reads the maps while they are being
     * changed by a keystroke. Both are capped by keeping the highest counts, which is the
     * right thing to forget first: a word used once and never again.
     */
    @Synchronized
    private fun snapshot(): String {
        val text = StringBuilder()
        words.entries
            .sortedByDescending { it.value }
            .take(MAX_WORDS)
            .forEach { text.append("w\t").append(it.key).append('\t').append(it.value).append('\n') }

        // Written before the pairs, which are the part that gets truncated at MAX_PAIRS: a
        // block the user asked for must not be the thing that falls off the end of the file.
        for (word in blockedWords) text.append("x\t").append(word).append('\n')
        for (pair in blockedPairs) text.append("y\t").append(pair).append('\n')

        var written = 0
        for ((previous, followers) in pairs.entries.sortedByDescending { it.value.size }) {
            for ((next, count) in followers.entries.sortedByDescending { it.value }.take(MAX_FOLLOWERS)) {
                if (written++ >= MAX_PAIRS) return text.toString()
                text.append("b\t").append(previous).append('\t').append(next)
                    .append('\t').append(count).append('\n')
            }
        }
        return text.toString()
    }

    companion object {

        fun open(context: Context): UserDictionary {
            // See the class note: this directory, and not filesDir, because the default
            // backup configuration would copy filesDir to the cloud.
            val file = File(context.noBackupFilesDir, "keyboard-learned.txt")
            return UserDictionary(file).apply { load() }
        }

        /** For tests, which have no Context and want a file they chose. */
        internal fun openAt(file: File): UserDictionary = UserDictionary(file).apply { load() }

        private const val MAX_WORD = 32
        private const val MAX_WORDS = 4_000
        private const val MAX_PAIRS = 8_000
        private const val MAX_FOLLOWERS = 8

        /** How many things the bin remembers. Nobody throws away five hundred suggestions. */
        private const val MAX_BLOCKED = 500

        /**
         * How a learned word is weighed against the shipped dictionary's 1-255.
         *
         * The floor is what one use is worth, and it is set above the middle of the range on
         * purpose: if you have typed a word, you are more likely to type it again than you are
         * to type most of the dictionary. The ceiling keeps it below the handful of words that
         * make up most of the language, so learning `Gorjan` never displaces `going`.
         */
        private const val LEARNED_FLOOR = 140
        private const val LEARNED_STEP = 10
        private const val LEARNED_CEILING = 235

        /**
         * And how a learned *pair* is weighed against the shipped table. See [weighPair].
         *
         * Calibrated against real entries in that table rather than picked: `thank` -> `you`
         * is 188, `how` -> `do` is 124, `the` -> `way` is 72. One sighting lands at 85 -
         * under the good ones, over the thin ones - and five at 185, which is past all but
         * the handful the corpus is surest of.
         */
        private const val PAIR_FLOOR = 60
        private const val PAIR_STEP = 25
        private const val PAIR_CEILING = 235
    }
}
