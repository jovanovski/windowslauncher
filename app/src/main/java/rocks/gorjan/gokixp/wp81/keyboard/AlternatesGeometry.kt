package rocks.gorjan.gokixp.wp81.keyboard

/**
 * Where the row of alternates goes, worked out with no Android in it.
 *
 * Pulled out of the view on purpose. Placing this row is a handful of rules that interact, and
 * each of them is the kind of thing that comes out slightly wrong and stays wrong, because a
 * row that is very slightly off screen or highlighting the wrong cell still looks like a row.
 * As plain arithmetic it can be checked by asking it questions instead of by holding keys and
 * squinting at screenshots.
 *
 * The rule that matters most, and the one that is easy to get wrong: **the character the key
 * advertises must be the one under the finger the moment the row opens.** A key with a `9` in
 * its corner has to give a `9` when held and released, or the corner mark is a lie. That is
 * what [PopupBox.primary] is for, and why a row that cannot fit to the right of its key is
 * turned around rather than simply shoved sideways - shoving it leaves the finger somewhere
 * in the middle of the row, pointing at whichever accent happens to be there.
 */
internal data class PopupBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,

    /**
     * Which cell of the drawn row sits over the key, and therefore holds the first
     * alternate - the one printed in the key's corner.
     *
     * Zero for a row running rightwards from its key, the last cell for one running
     * leftwards, and something in between for a row too wide to do either. It is derived
     * from where the row actually ended up rather than assumed, which is what makes the
     * guarantee hold in all three cases. See [alternateAt].
     */
    val primary: Int
) {

    val width get() = right - left
    val height get() = bottom - top
}

/**
 * @param faceLeft/[faceTop]/[faceRight] the held key *as painted*, which is inset from its
 *   view's bounds by half a gutter on every side.
 * @param count how many alternates the row holds.
 */
internal fun alternatesBox(
    faceLeft: Float,
    faceTop: Float,
    faceRight: Float,
    count: Int,
    keyW: Float,
    keyH: Float,
    gap: Float,
    viewWidth: Float,
    heightFraction: Float
): PopupBox {
    val pitch = keyW + gap
    val width = count * keyW + (count - 1) * gap
    val height = keyH * heightFraction
    val edge = gap / 2f

    // Rightwards from the key, which is the ordinary case and reads in the natural order.
    val rightwards = faceLeft
    val fitsRight = rightwards + width <= viewWidth - edge

    // Otherwise turned around: the row ends where the key ends, and runs away to the left.
    val leftwards = faceRight - width
    val fitsLeft = leftwards >= edge

    val unclamped = if (fitsRight) rightwards else if (fitsLeft) leftwards else rightwards

    // A row wide enough to fit neither way - eight alternates on a middle column, which `a`
    // really has - is simply pulled inside the screen. The lower bound is applied last so
    // that such a row starts at the edge rather than at a negative coordinate, which is what
    // a single clamp between crossed bounds would give.
    val left = unclamped.coerceAtMost(viewWidth - edge - width).coerceAtLeast(edge)

    // Always above the key, including for the top row, where "above" is off the top of the
    // keyboard entirely. That is why the row is shown in a window of its own rather than
    // painted into the keyboard: a row that appeared below the key you are holding would be
    // a row under your own thumb, which is the one place it cannot be.
    val top = faceTop - gap - height

    // Which cell the key's own centre landed on, whichever of the three ways the row was
    // placed. Asked of the finished geometry rather than assumed per case, so the answer is
    // right even for the row that fitted neither way.
    val primary = (((faceLeft + faceRight) / 2f - left) / pitch).toInt().coerceIn(0, count - 1)

    return PopupBox(left, top, left + width, top + height, primary)
}

/**
 * Which cell of the drawn row a finger at [x] (in the keyboard's own coordinates) is over.
 *
 * Horizontal only. The row is horizontal, and a finger reaching sideways along it drifts
 * vertically without meaning to; requiring it to stay within the band would make the far end
 * of a wide row genuinely hard to land on.
 */
internal fun alternateCellAt(x: Float, boxLeft: Float, count: Int, keyW: Float, gap: Float): Int {
    if (count <= 1) return 0
    val pitch = keyW + gap
    return ((x - boxLeft) / pitch).toInt().coerceIn(0, count - 1)
}

/**
 * Which alternate a given cell of the drawn row holds.
 *
 * The alternates spread outward from [primary] - the cell over the key - rather than simply
 * running left to right, and this is the one place that mapping lives. Everything else
 * (placing the row, hit testing it, drawing it) counts cells from the left edge.
 *
 * Cells from [primary] rightwards take the alternates in order, so sliding away from the key
 * walks the list. Cells to its left take what is left over, furthest cell first, so that a
 * row pinned against the right-hand edge reads as the list reversed and still walks in order
 * as the finger moves left.
 *
 * Three cases, one formula. A row running rightwards has `primary` of 0 and comes out in
 * natural order; one pinned leftwards has `primary` of `n-1` and comes out reversed; one that
 * fitted neither way has `primary` somewhere in the middle and fills both ways from there.
 */
internal fun alternateAt(chars: String, cell: Int, primary: Int): Char? {
    if (chars.isEmpty()) return null
    val index = if (cell >= primary) cell - primary else chars.length - 1 - cell
    return chars.getOrNull(index)
}
