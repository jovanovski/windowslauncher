package rocks.gorjan.gokixp.wp81.keyboard.text

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What tends to follow what, as a packed table read straight out of the assets.
 *
 * The other half of prediction, and the half that was missing. [UserDictionary] learns pairs
 * by watching, which is the better source - it learns *this* person's turns of phrase - and
 * it starts out empty, so a keyboard fresh out of the box predicted nothing at all and stayed
 * that way for a week. This is the opinion it holds until then: thirty thousand words with
 * the handful that most often come after each, counted from the corpus the word lists were
 * derived from in the first place.
 *
 * The two are merged at the point of use, in [Suggester.followers], and the merge is why the
 * weights on both sides are on the same 1-255 scale. Nothing shipped here can outrank a pair
 * the user has actually typed a few times, which is the whole policy: the corpus is the
 * starting position, not the answer.
 *
 * The format is written by `tools/dictbuild/build_bigrams.py`, which documents it in full.
 * What a reader needs to know:
 *
 *  - The **word pool** is every word the table mentions, sorted, each stored as a length byte
 *    and that many UTF-8 bytes. A word's **id** is its index in that ordering, which is what
 *    makes a lookup a binary search.
 *  - Two parallel `u32` tables, one entry per word: where its string is, and where its
 *    follower list is (zero when it has none).
 *  - A **follower list** is a count byte followed by that many three-byte entries: a `u16`
 *    word id and a `u8` weight.
 *
 * UTF-8 rather than the UTF-16 the trie uses. The trie is walked character by character tens
 * of thousands of times per keystroke, where a fixed-width code unit is worth real time; this
 * is binary-searched once per word and never walked, so the bytes are the cheaper trade. The
 * query is encoded once and compared against the buffer directly, so a lookup allocates
 * nothing until it has found something worth returning.
 */
class Bigrams private constructor(
    private val buffer: ByteBuffer,
    private val wordCount: Int,
    private val startOffset: Int
) {

    /** Where the follower table begins: straight after the string table. */
    private val listsAt = HEADER + 4 * wordCount

    /**
     * What has followed [word] before, best first, or empty if the table has no opinion.
     *
     * Lowercased on the way in because the table is: the corpus was counted that way, and a
     * sentence-initial `The` is not a different word from `the` for this purpose.
     */
    fun following(word: String): List<Pair<String, Int>> {
        if (word.isEmpty()) return emptyList()
        val id = idOf(word.lowercase()) ?: return emptyList()
        val at = buffer.getInt(listsAt + 4 * id)
        return if (at == NONE) emptyList() else entriesAt(at)
    }

    /**
     * What tends to begin a sentence, for a field with nothing in it yet.
     *
     * Counted from the first word of each line of the corpus, which for subtitles is very
     * nearly the first word of a sentence. Kept separate from the follower tables rather than
     * hung off a sentinel word, because the thing these follow is not a word.
     */
    fun starters(): List<Pair<String, Int>> =
        if (startOffset == NONE) emptyList() else entriesAt(startOffset)

    /**
     * Which word id spells [word], or null.
     *
     * The bytes are compared where they lie. Decoding each probe into a `String` to compare
     * it would allocate eighteen of them per lookup, on a path that runs on every keystroke,
     * for a question that is answered by looking at a few bytes.
     */
    private fun idOf(word: String): Int? {
        val needle = word.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = wordCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val comparison = compareAt(buffer.getInt(HEADER + 4 * mid), needle)
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return null
    }

    /** The stored word at [offset] against [needle], as `compareTo` would order them. */
    private fun compareAt(offset: Int, needle: ByteArray): Int {
        val length = buffer.get(offset).toInt() and 0xFF
        val shared = minOf(length, needle.size)
        for (i in 0 until shared) {
            // Unsigned, because the ordering the builder sorted by is the ordering of bytes,
            // and a UTF-8 continuation byte is negative as a signed one.
            val stored = buffer.get(offset + 1 + i).toInt() and 0xFF
            val wanted = needle[i].toInt() and 0xFF
            if (stored != wanted) return stored - wanted
        }
        return length - needle.size
    }

    /** The follower list at [offset], as (word, weight) pairs in the order it stores them. */
    private fun entriesAt(offset: Int): List<Pair<String, Int>> {
        val count = buffer.get(offset).toInt() and 0xFF
        if (count == 0) return emptyList()
        val out = ArrayList<Pair<String, Int>>(count)
        for (i in 0 until count) {
            val at = offset + 1 + i * ENTRY
            val id = buffer.getShort(at).toInt() and 0xFFFF
            if (id >= wordCount) continue
            val weight = buffer.get(at + 2).toInt() and 0xFF
            out.add(wordAt(id) to weight)
        }
        return out
    }

    private fun wordAt(id: Int): String {
        val offset = buffer.getInt(HEADER + 4 * id)
        val length = buffer.get(offset).toInt() and 0xFF
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.get(offset + 1 + i)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {

        /** No list. Offset zero is the file header, so it can never be one. */
        private const val NONE = 0

        private const val HEADER = 24
        private const val ENTRY = 3

        private val MAGIC = byteArrayOf(
            'W'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte()
        )
        private const val VERSION = 1

        /**
         * Reads `keyboard/<language>.bigrams` out of the assets, or null if it is not there.
         *
         * Null rather than an exception, for the same reason [Dictionary.load] does it: only
         * English ships a table, every other language has none, and a missing one costs the
         * user their head start at prediction rather than their keyboard. Everything
         * downstream falls back on what has been learned.
         */
        fun load(context: Context, language: String): Bigrams? = try {
            val bytes = context.assets.open("keyboard/$language.bigrams").use { it.readBytes() }
            parse(bytes)
        } catch (e: IOException) {
            null
        }

        /** Split out from [load] so the format can be tested without an Android context. */
        fun parse(bytes: ByteArray): Bigrams? {
            if (bytes.size < HEADER) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.get(4).toInt() != VERSION) return null
            // The entry width is in the header so a reader can refuse a file it would
            // otherwise misread silently, one field at a time, all the way down.
            if (buffer.get(6).toInt() != ENTRY) return null

            val words = buffer.getInt(8)
            val wordsOffset = buffer.getInt(12)
            val startOffset = buffer.getInt(16)
            if (words <= 0 || words > 0xFFFF) return null
            if (wordsOffset != HEADER + 8 * words || wordsOffset > bytes.size) return null
            if (startOffset < 0 || startOffset >= bytes.size) return null
            return Bigrams(buffer, words, startOffset)
        }
    }
}
