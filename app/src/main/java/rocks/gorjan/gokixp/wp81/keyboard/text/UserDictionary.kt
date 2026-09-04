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
 * learns *pairs*, which is the whole of next-word prediction here: the corpus behind the
 * shipped word lists was tokenized in a way that discarded word order, so the keyboard starts
 * out with no opinion whatsoever about what follows what and acquires one only by watching.
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
        val out = ArrayList<Pair<String, Int>>(4)
        for ((word, count) in words) {
            if (word.length > key.length && word.startsWith(key)) out.add(word to weigh(count))
        }
        return out
    }

    /** Whether this word has been learned, so that it stops being corrected away. */
    @Synchronized
    fun knows(word: String): Boolean = words.containsKey(word.lowercase())

    /** What has followed [word] before, best first. */
    @Synchronized
    fun following(word: String): List<Pair<String, Int>> {
        val followers = pairs[word.lowercase()] ?: return emptyList()
        return followers.entries
            .sortedByDescending { it.value }
            .map { it.key to weigh(it.value) }
    }

    /** Drops the least-used half when the map has grown past twice its keeping size. */
    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS)
        words.clear()
        for (entry in keep) words[entry.key] = entry.value
    }

    private fun weigh(count: Int): Int =
        (LEARNED_FLOOR + count * LEARNED_STEP).coerceAtMost(LEARNED_CEILING)

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
                }
            }
        } catch (e: Exception) {
            // A truncated or garbled file is worth nothing and is not worth crashing over;
            // starting again from an empty one costs the user their learned words and
            // nothing else.
            words.clear()
            pairs.clear()
        }
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
    }
}
