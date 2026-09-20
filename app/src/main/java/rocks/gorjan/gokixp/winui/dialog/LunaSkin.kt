package rocks.gorjan.gokixp.winui.dialog

import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.winui.WinPalette

/**
 * Windows XP, Luna Blue.
 *
 * The numbers come from Luna's own theme source - `blue.ini` and the BMPs beside it in the
 * leaked XP SP1 tree - rather than from a CSS re-creation, which matters because the popular
 * ones draw XP's button as a 9x bevel and it is nothing of the kind: a #003C74 ring with a
 * radius of three, a near-white gradient, and a three-row beige foot.
 *
 * The two tells people notice are the orange band across the top of the selected tab and the
 * blue group-box caption; the third, less obvious one is that a property sheet's page is nearly
 * white while the dialog around it stays #ECE9D8.
 */
object LunaSkin : WinSkin(WinPalette.XP) {

    private const val BUTTON_BORDER = 0xFF003C74.toInt()
    private const val BUTTON_TOP = 0xFFFFFFFF.toInt()
    private const val BUTTON_BODY = 0xFFF0F0EA.toInt()
    private val BUTTON_FOOT = intArrayOf(0xFFECEBE6.toInt(), 0xFFE2DFD6.toInt(), 0xFFD6D0C5.toInt())
    private const val PRESSED_TOP = 0xFFD1CCC1.toInt()
    private const val PRESSED_BODY = 0xFFE5E4DE.toInt()
    private const val PRESSED_FOOT = 0xFFF2F1EE.toInt()
    private const val DISABLED_BORDER = 0xFFC9C7BA.toInt()
    private const val DISABLED_FILL = 0xFFF5F4EA.toInt()

    private const val FIELD_BORDER = 0xFF7F9DB9.toInt()
    private const val FIELD_DISABLED_FILL = 0xFFEBEBE4.toInt()

    private const val TAB_BORDER = 0xFF91A7B4.toInt()
    private const val TAB_SELECTED_BORDER = 0xFF919B9C.toInt()
    private val TAB_ORANGE = intArrayOf(0xFFE68B2C.toInt(), 0xFFFFC83C.toInt(), 0xFFFFC73C.toInt())

    private const val PAGE_TOP = 0xFFFCFCFE.toInt()
    private const val PAGE_BOTTOM = 0xFFF4F3EE.toInt()
    private const val PAGE_BORDER = 0xFF919B9C.toInt()
    private const val PAGE_SHADOW_1 = 0xFFD0CEBF.toInt()
    private const val PAGE_SHADOW_2 = 0xFFE3E0D0.toInt()

    private const val GROUP_BORDER = 0xFFD0D0BF.toInt()
    private const val GROUP_CAPTION = 0xFF0046D5.toInt()

    private const val DROP_RING = 0xFFBCCEF7.toInt()
    private const val DROP_TOP = 0xFFE1EAFE.toInt()
    private const val DROP_BOTTOM = 0xFFADC9F9.toInt()
    private const val GLYPH = 0xFF4D6185.toInt()
    private const val GLYPH_DISABLED = 0xFFC9C9C2.toInt()

    override val fontRes = R.font.tahoma
    override val pageFace = PAGE_TOP
    override val groupTitleColor = GROUP_CAPTION

    override fun button(e: WinEdges, w: Float, h: Float, state: WinState) {
        val inset = e.lw
        if (state == WinState.DISABLED) {
            e.roundRect(inset, inset, w - inset, h - inset, 3f, DISABLED_FILL)
            e.roundOutline(inset, inset, w - inset, h - inset, 3f, DISABLED_BORDER)
            return
        }
        if (state == WinState.PRESSED) {
            e.gradient(
                inset, inset, w - inset, h - inset,
                intArrayOf(PRESSED_TOP, PRESSED_BODY, PRESSED_FOOT), floatArrayOf(0f, 0.2f, 1f),
                radius = 3f,
            )
        } else {
            // A near-white ramp with three fixed rows of beige under it - the foot is what
            // makes a Luna button read as sitting on the dialog rather than floating.
            val footTop = h - inset - 3 * e.lw
            e.gradient(
                inset, inset, w - inset, footTop,
                intArrayOf(BUTTON_TOP, BUTTON_BODY), null, radius = 3f,
            )
            for (i in BUTTON_FOOT.indices) {
                val y = footTop + i * e.lw
                e.fill(inset, y, w - inset, y + e.lw, BUTTON_FOOT[i])
            }
            // Re-round the bottom corners the foot just squared off.
            e.roundOutline(inset, inset, w - inset, h - inset, 3f, BUTTON_BORDER)
            if (state == WinState.HOT) hotRing(e, w, h)
            return
        }
        e.roundOutline(inset, inset, w - inset, h - inset, 3f, BUTTON_BORDER)
    }

    /** Luna's orange hover ring, two pixels inside the border. */
    private fun hotRing(e: WinEdges, w: Float, h: Float) {
        e.roundOutline(2 * e.lw, 2 * e.lw, w - 2 * e.lw, h - 2 * e.lw, 2f, 0xFFFEDF9A.toInt())
        e.roundOutline(3 * e.lw, 3 * e.lw, w - 3 * e.lw, h - 3 * e.lw, 2f, 0xFFF9B435.toInt())
    }

    override fun field(e: WinEdges, w: Float, h: Float, enabled: Boolean) {
        e.fill(0f, 0f, w, h, if (enabled) window else FIELD_DISABLED_FILL)
        e.outline(0f, 0f, w, h, if (enabled) FIELD_BORDER else DISABLED_BORDER)
    }

    override fun combo(e: WinEdges, w: Float, h: Float, designW: Int, designH: Int, enabled: Boolean) {
        field(e, w, h, enabled)

        // The drop-down is a scrollbar width across, sunk one pixel inside the field's frame,
        // with a white hairline of its own around it.
        val l = e.px(designW - 18)
        val t = e.lw
        val r = w - e.lw
        val b = h - e.lw
        e.fill(l, t, r, b, 0xFFFFFFFF.toInt())
        val il = l + e.lw
        val it = t + e.lw
        val ir = r - e.lw
        val ib = b - e.lw
        if (enabled) {
            e.diagonalGradient(il, it, ir, ib, DROP_TOP, DROP_BOTTOM)
            e.outline(il, it, ir, ib, DROP_RING)
        } else {
            e.fill(il, it, ir, ib, DISABLED_FILL)
            e.outline(il, it, ir, ib, 0xFFE9E9E1.toInt())
        }

        val cx = ((l + r) / 2f / e.unit).toInt()
        e.chevron(cx, designH / 2 - 3, if (enabled) GLYPH else GLYPH_DISABLED)
    }

    override fun groupBox(e: WinEdges, w: Float, h: Float, titleWidth: Float) {
        e.roundOutline(0f, 0f, w, h, 2f, GROUP_BORDER)
        if (titleWidth <= 0f) return
        e.fill(e.px(groupTitleX - 2), 0f, e.px(groupTitleX) + titleWidth + 3 * e.unit, e.lw, pageFace)
    }

    override fun page(e: WinEdges, l: Int, t: Int, r: Int, b: Int) {
        val left = e.px(l)
        val top = e.px(t)
        val right = e.px(r + 1)
        val bottom = e.px(b + 1)
        // The page texture is true-size, not stretched: it reaches its bottom colour about 300
        // rows down whatever the panel's height, and is flat below that.
        val ramp = ((bottom - top) / (300f * e.unit)).coerceAtMost(1f)
        e.gradient(
            left, top, right, bottom,
            intArrayOf(PAGE_TOP, PAGE_BOTTOM, PAGE_BOTTOM), floatArrayOf(0f, ramp, 1f),
        )
        e.outline(left, top, right, bottom, PAGE_BORDER)
        e.outline(left + e.lw, top + e.lw, right - e.lw, bottom - e.lw, PAGE_TOP)
        // A two-pixel drop shadow down the right and along the bottom.
        e.fill(right, top + e.lw, right + e.lw, bottom + e.lw, PAGE_SHADOW_1)
        e.fill(left + e.lw, bottom, right + e.lw, bottom + e.lw, PAGE_SHADOW_1)
        e.fill(right + e.lw, top + 2 * e.lw, right + 2 * e.lw, bottom + 2 * e.lw, PAGE_SHADOW_2)
        e.fill(left + 2 * e.lw, bottom + e.lw, right + 2 * e.lw, bottom + 2 * e.lw, PAGE_SHADOW_2)
    }

    override fun tab(e: WinEdges, l: Int, r: Int, selected: Boolean, pageTop: Int) {
        val left = if (selected) l - selectedTabBleed else l
        val right = if (selected) r + selectedTabBleed else r
        val top = if (selected) selectedTabTop else tabTop
        // A tab has no bottom of its own; the selected one runs on into the page.
        val bottom = if (selected) pageTop + 1 else tabBottom + 1

        val fl = e.px(left)
        val ft = e.px(top)
        val fr = e.px(right + 1)
        val fb = e.px(bottom)

        // Luna rounds a tab's top corners and leaves the bottom square and open. Drawing it
        // taller than it is and clipping to its real bounds gets that in one shape.
        e.c.save()
        e.c.clipRect(fl, ft, fr, fb)
        val tall = fb + 8 * e.lw
        if (selected) {
            e.roundRect(fl, ft, fr, tall, 3f, PAGE_TOP)
            for (i in TAB_ORANGE.indices) {
                val y = ft + i * e.lw
                e.fill(fl + e.lw, y, fr - e.lw, y + e.lw, TAB_ORANGE[i])
            }
            e.roundOutline(fl, ft, fr, tall, 3f, TAB_SELECTED_BORDER)
            // The band is the top edge, so paint over the border it would otherwise sit under.
            e.fill(fl + 2 * e.lw, ft, fr - 2 * e.lw, ft + e.lw, TAB_ORANGE[0])
        } else {
            e.gradient(fl, ft, fr, tall, intArrayOf(0xFFFFFFFF.toInt(), 0xFFECEBE6.toInt()), null, 3f)
            e.roundOutline(fl, ft, fr, tall, 3f, TAB_BORDER)
        }
        e.c.restore()
    }

    override fun spinButton(
        e: WinEdges, l: Float, t: Float, r: Float, b: Float, up: Boolean, state: WinState,
    ) {
        val enabled = state != WinState.DISABLED
        if (enabled) {
            if (state == WinState.PRESSED) {
                e.diagonalGradient(l, t, r, b, 0xFF6E8EF1.toInt(), 0xFFD2DEEB.toInt())
                e.outline(l, t, r, b, 0xFF8894DB.toInt())
            } else {
                e.diagonalGradient(l, t, r, b, DROP_TOP, DROP_BOTTOM)
                e.outline(l, t, r, b, DROP_RING)
            }
        } else {
            e.fill(l, t, r, b, DISABLED_FILL)
            e.outline(l, t, r, b, 0xFFE9E9E1.toInt())
        }
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        e.triangle(cx, cy, 1.8f * e.unit, 1.8f * e.unit, !up, if (enabled) GLYPH else GLYPH_DISABLED)
    }
}
