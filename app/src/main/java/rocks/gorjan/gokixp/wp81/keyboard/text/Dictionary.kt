package rocks.gorjan.gokixp.wp81.keyboard.text

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The word list, as a packed trie read straight out of the assets.
 *
 * A hundred and fifty thousand English words with their frequencies come to about three
 * megabytes, which is small on disk and far too large to want on the heap as objects: a
 * `Map<String, Int>` of the same content is tens of megabytes of `String` headers and boxed
 * integers, all of it live for as long as the keyboard is. So the file is the data structure.
 * It is read once into a single [ByteBuffer] and walked in place; there are no per-word
 * allocations anywhere in a lookup, which matters because a lookup happens thousands of
 * times per keystroke while the suggester explores the trie under an edit-distance bound.
 *
 * The format is written by `tools/dictbuild/build_dict.py`, which documents it in full. What
 * a reader needs to know:
 *
 *  - A **node** is a `u16` child count followed by that many edges.
 *  - An **edge** is exactly eight bytes: a UTF-16 code unit, a flags byte, a frequency byte,
 *    and a four-byte absolute offset to the child node.
 *  - Edges within a node are **sorted by character**, and are fixed width precisely so they
 *    can be binary-searched rather than scanned. That is the one decision in the format that
 *    is about speed rather than size.
 *
 * Note on the character type: the trie is keyed on UTF-16 code units, not code points. Every
 * alphabet this keyboard ships - Latin and Macedonian Cyrillic - lives entirely in the Basic
 * Multilingual Plane, where the two are the same thing, so nothing here has to think about
 * surrogate pairs. A language that needed them would need a wider edge.
 */
class Dictionary private constructor(
    private val buffer: ByteBuffer,
    private val rootOffset: Int,

    /** How many words went in. Only for sanity checks and logging. */
    val wordCount: Int
) {

    /**
     * A position part-way down the trie.
     *
     * The suggester walks many partial words at once - that is what a bounded edit-distance
     * search *is* - so it needs to hold on to "where I had got to" and carry on from there.
     * An `Int` offset is that position, and passing them around costs nothing.
     */
    fun root(): Int = rootOffset

    /** How many children the node at [node] has. */
    fun childCount(node: Int): Int = buffer.getShort(node).toInt() and 0xFFFF

    /** The character on the [index]th edge of [node]. Edges are in ascending order. */
    fun charAt(node: Int, index: Int): Char =
        buffer.getChar(node + HEADER + index * EDGE)

    /** Whether the [index]th edge of [node] completes a word. */
    fun isWord(node: Int, index: Int): Boolean =
        buffer.get(node + HEADER + index * EDGE + FLAGS).toInt() and FLAG_TERMINAL != 0

    /**
     * How common the word ending on this edge is, from 1 to 255, or 0 if it is not a word.
     *
     * Log-scaled by the builder, because the raw counts span seven orders of magnitude and a
     * linear scale would round everything but the commonest few thousand words to nothing.
     * What the suggester wants from this is the ordering and enough resolution to tell a
     * common word from a rare one, both of which survive the squash.
     */
    fun frequency(node: Int, index: Int): Int =
        buffer.get(node + HEADER + index * EDGE + FREQ).toInt() and 0xFF

    /**
     * The node the [index]th edge of [node] leads to, or [NONE] if the word ends there.
     *
     * A zero offset cannot be a real node, because offset zero is the file header.
     */
    fun childAt(node: Int, index: Int): Int =
        buffer.getInt(node + HEADER + index * EDGE + CHILD)

    /**
     * Which edge of [node] carries [ch], or -1.
     *
     * Binary search rather than a scan. A node near the root of an English trie has twenty-six
     * children and is visited on every single step of every candidate the suggester considers;
     * five comparisons instead of thirteen is the difference between a keyboard that keeps up
     * with a thumb and one that does not.
     */
    fun edgeFor(node: Int, ch: Char): Int {
        var low = 0
        var high = childCount(node) - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = charAt(node, mid)
            when {
                candidate < ch -> low = mid + 1
                candidate > ch -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The frequency of [word], or 0 if it is not in the dictionary. */
    fun frequencyOf(word: CharSequence): Int {
        if (word.isEmpty()) return 0
        var node = rootOffset
        for (i in word.indices) {
            val edge = edgeFor(node, word[i])
            if (edge < 0) return 0
            if (i == word.length - 1) {
                return if (isWord(node, edge)) frequency(node, edge) else 0
            }
            node = childAt(node, edge)
            if (node == NONE) return 0
        }
        return 0
    }

    operator fun contains(word: CharSequence): Boolean = frequencyOf(word) > 0

    companion object {

        /** No child. Offset zero is the file header, so it can never be a node. */
        const val NONE = 0

        private const val HEADER = 2       // a node's own u16 child count
        private const val EDGE = 8
        private const val FLAGS = 2        // offsets within an edge
        private const val FREQ = 3
        private const val CHILD = 4

        private const val FLAG_TERMINAL = 1

        private const val FILE_HEADER = 16
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'D'.code.toByte())
        private const val VERSION = 1

        /**
         * Reads `keyboard/<language>.trie` out of the assets, or null if it is not there.
         *
         * Null rather than an exception: a missing or unreadable dictionary should cost the
         * user their suggestions, not their keyboard. Everything downstream treats a null
         * dictionary as "no opinions about spelling" and carries on typing.
         *
         * The whole file is read into a direct buffer up front. Android's asset streams are
         * compressed and not seekable, so a lazy mapping is not on offer for anything inside
         * an APK - and three megabytes off the heap, read once when the keyboard is first
         * shown, is the cheaper end of the trade either way.
         */
        fun load(context: Context, language: String): Dictionary? = try {
            val bytes = context.assets.open("keyboard/$language.trie").use { it.readBytes() }
            parse(bytes)
        } catch (e: IOException) {
            null
        }

        /** Split out from [load] so the format can be tested without an Android context. */
        fun parse(bytes: ByteArray): Dictionary? {
            if (bytes.size < FILE_HEADER) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.get(4).toInt() != VERSION) return null
            // The edge width is in the header so that a reader can refuse a file it would
            // otherwise misread silently, one field at a time, all the way down.
            if (buffer.get(6).toInt() != EDGE) return null

            val root = buffer.getInt(8)
            val words = buffer.getInt(12)
            if (root < FILE_HEADER || root >= bytes.size) return null
            return Dictionary(buffer, root, words)
        }
    }
}
