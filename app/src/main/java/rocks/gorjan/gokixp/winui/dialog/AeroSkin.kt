package rocks.gorjan.gokixp.winui.dialog

import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.winui.WinPalette

/**
 * Windows Vista and Windows 7, Aero.
 *
 * One skin for both, which is not a shortcut: rendering `aero.msstyles` 6.0.6000 and 6.1.7601
 * to the same size and diffing them gives **zero** differing pixels for the button, the edit
 * border, the combo, the tab item, the tab pane, the group box, the combo arrow and both spin
 * buttons - several of those PNGs are byte-identical between the two. "Vista is glossier, 7 is
 * flatter" is true of the window frame, the taskbar and Explorer, and not true of a single
 * common control. The only colour that actually differs is grey text, which [Aero7Skin] holds.
 *
 * Aero's controls are all the same few parts: a one-pixel transparent margin, a one-pixel ring
 * with a radius of two, a one-pixel inner highlight, and a face split by a hard stop at 47% -
 * not halfway, because the source art is 21 rows of fill and the break falls after eight.
 *
 * Two things worth stating because the popular CSS re-creation gets them wrong: the button's
 * border is #707070 and not #8E8F8F (that value is the theme's BORDERCOLORHINT, a piece of
 * metadata, not the line it draws), and the group-box caption is black. The blue everyone
 * remembers is #003399, which is the *main instruction* colour on a Control Panel page.
 */
open class AeroSkin protected constructor(pal: WinPalette) : WinSkin(pal) {

    private val border = 0xFF707070.toInt()
    private val hotBorder = 0xFF3C7FB1.toInt()
    private val pressedBorder = 0xFF2C628B.toInt()
    private val disabledBorder = 0xFFADB2B5.toInt()
    private val disabledFace = 0xFFF4F4F4.toInt()

    /** The hard stop lands at 47%, not halfway; see the class comment. */
    private val split = floatArrayOf(0f, 0.47f, 0.47f, 1f)

    private val normalFace = intArrayOf(
        0xFFF2F2F2.toInt(), 0xFFEBEBEB.toInt(), 0xFFDDDDDD.toInt(), 0xFFCFCFCF.toInt(),
    )
    private val hotFace = intArrayOf(
        0xFFEAF6FD.toInt(), 0xFFD9F0FC.toInt(), 0xFFBEE6FD.toInt(), 0xFFA7D9F5.toInt(),
    )
    private val pressedFace = intArrayOf(
        0xFFE5F4FC.toInt(), 0xFFC4E5F6.toInt(), 0xFF98D1EF.toInt(), 0xFF6DB6DD.toInt(),
    )
    private val pressedSplit = floatArrayOf(0f, 0.53f, 0.53f, 1f)

    private val highlightTop = 0xFFFCFCFC.toInt()
    private val highlightBottom = 0xFFF3F3F3.toInt()

    // The field ring is a different colour on every side - the signature of an Aero text box.
    private val fieldTop = 0xFFABADB3.toInt()
    private val fieldRight = 0xFFDBDFE6.toInt()
    private val fieldBottom = 0xFFE3E9EF.toInt()
    private val fieldLeft = 0xFFE2E3EA.toInt()
    private val fieldDisabled = 0xFFAFAFAF.toInt()

    private val tabBorder = 0xFF898C95.toInt()
    private val groupBorder = 0xFFD5DFE5.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private val spinBorder = 0xFFABADB3.toInt()
    private val spinFace = intArrayOf(
        0xFFF2F2F2.toInt(), 0xFFEBEBEB.toInt(), 0xFFDBDBDB.toInt(), 0xFFD1D1D1.toInt(),
    )
    /** Indexed by half-width: the apex is darkest, the base palest. */
    private val spinGlyph = intArrayOf(0xFF4D5678.toInt(), 0xFF5F6EA5.toInt(), 0xFF7085D1.toInt())

    override val fontRes = R.font.segoeui_regular

    /**
     * Segoe UI 9pt against MS Sans 8pt. A full design pixel bigger is the true ratio, but it
     * leaves "Background" 1 px clear of its tab border on 98's grid, so it gives back a half.
     */
    override val fontPx = 12.5f

    /** A property sheet's page is white while the dialog around it stays #F0F0F0. */
    override val pageFace = white

    /** Aero tabs grow upwards only; they are not inflated sideways. */
    override val selectedTabBleed = 0

    override fun button(e: WinEdges, w: Float, h: Float, state: WinState) =
        buttonChrome(e, 0f, 0f, w, h, state, 2f)

    /**
     * The chrome every Aero button-shaped thing is made of, inset by the one-pixel transparent
     * margin the art carries. A drop-list combo is exactly this part at full size.
     */
    private fun buttonChrome(
        e: WinEdges, l: Float, t: Float, r: Float, b: Float, state: WinState, radius: Float,
    ) {
        val m = e.lw
        val il = l + m
        val it = t + m
        val ir = r - m
        val ib = b - m
        when (state) {
            WinState.DISABLED -> e.roundRect(il, it, ir, ib, radius, disabledFace)
            WinState.HOT -> e.gradient(il, it, ir, ib, hotFace, split, radius)
            WinState.PRESSED -> e.gradient(il, it, ir, ib, pressedFace, pressedSplit, radius)
            WinState.NORMAL -> e.gradient(il, it, ir, ib, normalFace, split, radius)
        }
        // A pushed button has no highlight ring - it gets a blue-grey inset shadow instead.
        if (state != WinState.PRESSED) {
            e.roundOutline(il + m, it + m, ir - m, ib - m, radius, highlightBottom)
            e.fill(il + 2 * m, it + m, ir - 2 * m, it + 2 * m, highlightTop)
        }
        val ring = when (state) {
            WinState.HOT -> hotBorder
            WinState.PRESSED -> pressedBorder
            WinState.DISABLED -> disabledBorder
            WinState.NORMAL -> border
        }
        e.roundOutline(il, it, ir, ib, radius, ring)
    }

    override fun field(e: WinEdges, w: Float, h: Float, enabled: Boolean) {
        e.fill(0f, 0f, w, h, if (enabled) window else face)
        if (!enabled) {
            e.outline(0f, 0f, w, h, fieldDisabled)
            return
        }
        e.fill(0f, 0f, e.lw, h, fieldLeft)
        e.fill(w - e.lw, 0f, w, h, fieldRight)
        e.fill(0f, h - e.lw, w, h, fieldBottom)
        e.fill(0f, 0f, w, e.lw, fieldTop)
    }

    /**
     * A drop-list, which is what a properties dialog uses: the whole control is button chrome,
     * and at 96 dpi the arrow has no button and no divider of its own - just a glyph in a
     * scrollbar's width of well.
     */
    override fun combo(e: WinEdges, w: Float, h: Float, designW: Int, designH: Int, enabled: Boolean) {
        buttonChrome(e, 0f, 0f, w, h, if (enabled) WinState.NORMAL else WinState.DISABLED, 2f)
        val cx = designW - 9
        e.arrow(cx, designH / 2 - 2, 4, true, if (enabled) text else fieldDisabled)
    }

    override fun groupBox(e: WinEdges, w: Float, h: Float, titleWidth: Float) {
        // A white highlight inside the top and sides, and below the bottom line.
        e.roundOutline(e.lw, e.lw, w - e.lw, h, 2f, white)
        e.roundOutline(0f, 0f, w - e.lw, h - e.lw, 2f, groupBorder)
        if (titleWidth <= 0f) return
        e.fill(e.px(groupTitleX - 2), 0f, e.px(groupTitleX) + titleWidth + 3 * e.unit, 2 * e.lw, pageFace)
    }

    override fun page(e: WinEdges, l: Int, t: Int, r: Int, b: Int) {
        val left = e.px(l)
        val top = e.px(t)
        val right = e.px(r + 1)
        val bottom = e.px(b + 1)
        e.fill(left, top, right, bottom, white)
        e.outline(left, top, right, bottom, tabBorder)
    }

    override fun tab(e: WinEdges, l: Int, r: Int, selected: Boolean, pageTop: Int) {
        val top = if (selected) selectedTabTop else tabTop
        // A tab has no bottom of its own; the page's top border is its bottom.
        val bottom = if (selected) pageTop + 1 else tabBottom + 1
        val fl = e.px(l)
        val ft = e.px(top)
        val fr = e.px(r + 1)
        val fb = e.px(bottom)

        // Square corners, border on three sides only.
        if (selected) e.fill(fl, ft, fr, fb, white)
        else e.gradient(fl, ft, fr, fb, normalFace, split)
        e.fill(fl, ft, fr, ft + e.lw, tabBorder)
        e.fill(fl, ft, fl + e.lw, fb, tabBorder)
        e.fill(fr - e.lw, ft, fr, fb, tabBorder)
        if (!selected) return
        // Erase the page's own top border under the tab so the two read as one white surface.
        e.fill(fl + e.lw, e.px(pageTop), fr - e.lw, e.px(pageTop) + e.lw, white)
    }

    override fun spinButton(
        e: WinEdges, l: Float, t: Float, r: Float, b: Float, up: Boolean, state: WinState,
    ) {
        when (state) {
            WinState.DISABLED -> {
                e.fill(l, t, r, b, 0xFFEEEEEE.toInt())
                e.outline(l, t, r, b, 0xFFBFBFBF.toInt())
            }
            WinState.PRESSED -> {
                e.gradient(l, t, r, b, pressedFace, pressedSplit)
                e.outline(l, t, r, b, pressedBorder)
            }
            else -> {
                e.gradient(l, t, r, b, spinFace, split)
                e.fill(l + e.lw, t + e.lw, r - e.lw, t + 2 * e.lw, highlightTop)
                e.outline(l, t, r, b, spinBorder)
            }
        }
        // A 5-3-1 staircase with a colour per row, darkest at the apex.
        val cx = ((l + r) / 2f / e.unit).toInt()
        val top = ((t + b) / 2f / e.unit).toInt() - 1
        for (i in 0 until 3) {
            val halfWidth = if (up) i else 2 - i
            val colour = if (state == WinState.DISABLED) fieldDisabled else spinGlyph[halfWidth]
            e.fillDesign(cx - halfWidth, top + i, cx + halfWidth, top + i, colour)
        }
    }
}

/** Windows Vista. Its one quarrel with Windows 7 is a darker grey text, which the palette holds. */
object VistaSkin : AeroSkin(WinPalette.VISTA)
