package rocks.gorjan.gokixp.wp81.keyboard.text

import rocks.gorjan.gokixp.wp81.keyboard.KeyboardLayout

/**
 * How near two keys are to each other, as a substitution cost.
 *
 * The single change that makes autocorrect feel like it understands a thumb rather than a
 * dictionary. Typing `s` where `a` was meant is a finger that landed a few millimetres left;
 * typing `p` where `a` was meant is a different word. A speller that charges the same for both
 * has to be told, by the frequency table alone, which of `sad` and `pad` you meant - and it
 * will get it wrong about half the time, because both are ordinary words.
 *
 * Built from the layout's own geometry, so it is correct for whichever keyboard is up: the
 * neighbours of `а` on the Macedonian layout are nothing like the neighbours of `a`, and
 * nothing here needs to know that.
 *
 * Distances are in key widths, which is what [KeyboardLayout.keyCentres] reports, so the
 * thresholds below mean what they say regardless of screen size: 1.0 is the next key along.
 */
internal class Proximity(layout: KeyboardLayout) {

    /**
     * Costs for every pair of characters on the layout, flattened.
     *
     * A table rather than a distance computed on demand. There are at most a few hundred
     * entries and each is asked for thousands of times per keystroke, once for every edge the
     * search considers against every position in the typed word.
     */
    private val index = HashMap<Char, Int>()
    private val costs: IntArray

    init {
        val centres = layout.keyCentres
        for ((i, ch) in centres.keys.withIndex()) index[ch] = i
        val n = index.size
        costs = IntArray(n * n)
        for ((a, ai) in index) {
            val (ax, ay) = centres.getValue(a)
            for ((b, bi) in index) {
                if (a == b) {
                    costs[ai * n + bi] = 0
                    continue
                }
                val (bx, by) = centres.getValue(b)
                val dx = ax - bx
                val dy = ay - by
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                costs[ai * n + bi] = when {
                    distance <= ADJACENT -> SUBSTITUTE_NEAR
                    distance <= NEARBY -> SUBSTITUTE_MID
                    else -> SUBSTITUTE_FAR
                }
            }
        }
    }

    /**
     * What it costs to have typed [typed] where [intended] belonged.
     *
     * Case is folded, because shift is not a typo: a capital `S` where a lowercase `s` was
     * wanted is the same key and must cost nothing.
     *
     * A character the layout has never heard of - punctuation, a letter from another
     * alphabet - gets the far cost rather than being refused outright, so a stray one
     * degrades a candidate instead of eliminating it.
     */
    fun substitute(typed: Char, intended: Char): Int {
        val a = typed.lowercaseChar()
        val b = intended.lowercaseChar()
        if (a == b) return 0
        val ai = index[a] ?: return SUBSTITUTE_FAR
        val bi = index[b] ?: return SUBSTITUTE_FAR
        return costs[ai * index.size + bi]
    }

    private companion object {

        /**
         * The two distance bands, in key widths.
         *
         * A key's immediate neighbours - left, right, and the two rows either side, which sit
         * a little over one width away on a staggered layout - fall inside [ADJACENT]. The
         * ring beyond that is [NEARBY]: reachable by a badly aimed thumb, but not a near miss.
         */
        const val ADJACENT = 1.25
        const val NEARBY = 2.1

        const val SUBSTITUTE_NEAR = 55
        const val SUBSTITUTE_MID = 90
        const val SUBSTITUTE_FAR = 130
    }
}
