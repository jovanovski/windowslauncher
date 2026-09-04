package rocks.gorjan.gokixp.wp81.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the alternates row lands.
 *
 * Checked as arithmetic rather than by holding keys, because every way this goes wrong is a
 * few pixels of wrong that looks like carelessness: a row half off the screen, a row centred
 * on the key next door, a row over the thumb that opened it. The numbers below are the real
 * ones - a 1344 pixel screen at ten columns, which is the phone this was built on.
 */
class AlternatesGeometryTest {

    /**
     * The character the key advertises is the one under the finger, wherever the key is.
     *
     * The rule the whole placement exists to serve, and the one that broke on the real
     * keyboard: holding `o` near the right edge opened a seven-wide row, which was shoved
     * left to fit, which left the finger over the sixth cell - so releasing gave `ö` instead
     * of the `9` printed in the key's own corner.
     */
    @Test
    fun theAdvertisedCharacterIsUnderTheFinger() {
        for (column in 0 until 10) {
            for (count in 1..7) {
                val faceLeft = GAP / 2f + column * KEY
                val box = boxFor(faceLeft = faceLeft, count = count)
                val fingerX = faceLeft + KEY_W / 2f
                val underFinger = alternateCellAt(fingerX, box.left, count, KEY_W, GAP)
                assertEquals(
                    "column $column with $count alternates: the finger is not on the primary",
                    box.primary, underFinger
                )
                assertEquals(
                    "column $column with $count alternates: wrong character over the key",
                    'A', alternateAt("ABCDEFG".take(count), box.primary, box.primary)
                )
            }
        }
    }

    /** Sliding away from the key walks the list in order, whichever way the row turned. */
    @Test
    fun slidingAwayFromTheKeyWalksTheList() {
        // A row that has to turn around: the rightmost column with seven alternates.
        val faceLeft = GAP / 2f + 9f * KEY
        val box = boxFor(faceLeft = faceLeft, count = 7)
        assertEquals("this row should have turned around", 6, box.primary)

        val chars = "ABCDEFG"
        val fingerX = faceLeft + KEY_W / 2f
        // Stepping left, one cell at a time, should read A B C D E F G.
        val walked = (0 until 7).map { step ->
            val cell = alternateCellAt(fingerX - step * KEY, box.left, 7, KEY_W, GAP)
            alternateAt(chars, cell, box.primary)
        }
        assertEquals(chars.toList(), walked)
    }

    /** A row opened on the leftmost key cannot start off the left edge. */
    @Test
    fun theRowStaysOnScreenAtTheLeftEdge() {
        val box = boxFor(faceLeft = GAP / 2f, count = 5)
        assertTrue("row starts off screen at ${box.left}", box.left >= GAP / 2f - 0.5f)
    }

    /** Nor run off the right, which is the edge `p` and its five alternates would cross. */
    @Test
    fun theRowStaysOnScreenAtTheRightEdge() {
        val lastKeyLeft = GAP / 2f + 9f * (KEY_W + GAP)
        val box = boxFor(faceLeft = lastKeyLeft, count = 6)
        assertTrue("row ends off screen at ${box.right}", box.right <= WIDTH - GAP / 2f + 0.5f)
    }

    /**
     * A row wider than the screen starts at the left edge rather than at a negative one.
     *
     * The case a naive clamp gets wrong: pulling the right edge inside pushes the left edge
     * out past its own limit, and clamping both at once in one call is undefined when the
     * bounds cross.
     */
    @Test
    fun aRowWiderThanTheScreenStartsAtTheEdge() {
        val box = boxFor(faceLeft = 5f * KEY, count = 20)
        assertEquals(GAP / 2f, box.left, 0.5f)
    }

    /**
     * Always above the key - including the top row, where above is off the top of the
     * keyboard. That negative coordinate is the whole reason the row is its own window.
     */
    @Test
    fun theRowIsAlwaysAboveTheKey() {
        for (row in 0 until 4) {
            val faceTop = GAP / 2f + row * (KEY_H + GAP)
            val box = boxFor(faceLeft = 4f * KEY, faceTop = faceTop, count = 3)
            assertTrue(
                "row $row: box bottom ${box.bottom} is not above key top $faceTop",
                box.bottom <= faceTop
            )
        }
        // And the top row's really does leave the keyboard.
        val top = boxFor(faceLeft = 4f * KEY, faceTop = GAP / 2f, count = 3)
        assertTrue("the top row's alternates should sit above the keyboard", top.top < 0f)
    }

    /** Dragging across the row picks each cell in turn, and does not run past the ends. */
    @Test
    fun draggingAcrossTheRowPicksEachCell() {
        val box = boxFor(faceLeft = 4f * KEY, count = 4)
        for (i in 0 until 4) {
            val centre = box.left + i * (KEY_W + GAP) + KEY_W / 2f
            assertEquals("cell $i", i, alternateCellAt(centre, box.left, 4, KEY_W, GAP))
        }
        assertEquals("far left of the row", 0, alternateCellAt(box.left - 500f, box.left, 4, KEY_W, GAP))
        assertEquals("far right of the row", 3, alternateCellAt(box.right + 500f, box.left, 4, KEY_W, GAP))
    }

    /** One alternate is always index zero, however far the finger strays. */
    @Test
    fun aSingleAlternateIsAlwaysTheOneChosen() {
        assertEquals(0, alternateCellAt(-999f, 0f, 1, KEY_W, GAP))
        assertEquals(0, alternateCellAt(999f, 0f, 1, KEY_W, GAP))
    }

    private fun boxFor(faceLeft: Float, faceTop: Float = GAP / 2f + KEY_H + GAP, count: Int) =
        alternatesBox(
            faceLeft = faceLeft,
            faceTop = faceTop,
            faceRight = faceLeft + KEY_W,
            count = count,
            keyW = KEY_W,
            keyH = KEY_H,
            gap = GAP,
            viewWidth = WIDTH,
            heightFraction = 0.82f
        )

    private companion object {
        const val WIDTH = 1344f
        const val KEY_W = WIDTH / (10f * 1.107f)
        const val GAP = KEY_W * 0.107f
        const val KEY_H = KEY_W * 1.56f

        /** One column's pitch, for placing a key by its index. */
        const val KEY = KEY_W + GAP
    }
}
