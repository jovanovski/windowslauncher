package rocks.gorjan.gokixp.winui.dialog

import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.winui.WinPalette

/**
 * Windows 3.1, "Windows Default": controls drawn the way USER.EXE drew them before the 3D look.
 *
 * Dialogs are white, not grey. Everything is set in the System font. Edit fields, list boxes
 * and group boxes are a single black line. Push buttons are the only raised thing: a black
 * frame with its corner pixels left out, two rows of white along the top and left and two of
 * grey along the bottom and right. Pushed in, the white goes, a grey line runs along the top
 * and left, and the caption moves a pixel. Greyed-out text is plain grey, not embossed.
 *
 * 3.1 had no tab control; its Control Panel opened a separate dialog for each thing. The tabs
 * here are drawn the way 3.1 would have drawn one: black lines on white, corners cut.
 */
object Win31Skin : WinSkin(WinPalette.WIN31) {

    override val face: Int get() = pal.window
    override val pageFace: Int get() = pal.window

    override val fontRes = R.font.w31_system
    override val movesCaptionWhenPressed = true

    // The System font is 16 Windows pixels tall, which is 16 design pixels near enough; its
    // capitals start three rows lower than MS Sans Serif's, so every baseline moves down.
    override val fontPx: Float = 16f
    override val buttonBaseline: Int = 16
    override val fieldBaseline: Int = 16
    override val labelBaseline: Int = 11
    override val groupTitleBaseline: Int = 6

    private val black get() = pal.dkShadow

    override fun button(e: WinEdges, w: Float, h: Float, state: WinState) {
        val lw = e.lw
        e.fill(lw, lw, w - lw, h - lw, pal.face)
        // The frame, its four corner pixels left out
        e.fill(lw, 0f, w - lw, lw, black)
        e.fill(lw, h - lw, w - lw, h, black)
        e.fill(0f, lw, lw, h - lw, black)
        e.fill(w - lw, lw, w, h - lw, black)
        val l = lw
        val t = lw
        val r = w - lw
        val b = h - lw
        if (state == WinState.PRESSED) {
            e.fill(l, t, r, t + lw, pal.shadow)
            e.fill(l, t, l + lw, b, pal.shadow)
        } else {
            // Two rows of white at the top and left, two of grey at the bottom and right
            e.fill(l, t, r - lw, t + 2 * lw, pal.hilight)
            e.fill(l, t, l + 2 * lw, b - lw, pal.hilight)
            e.fill(l + lw, b - 2 * lw, r, b, pal.shadow)
            e.fill(r - 2 * lw, t + lw, r, b, pal.shadow)
        }
    }

    override fun field(e: WinEdges, w: Float, h: Float, enabled: Boolean) {
        e.fill(0f, 0f, w, h, window)
        e.outline(0f, 0f, w, h, black)
    }

    override fun combo(e: WinEdges, w: Float, h: Float, designW: Int, designH: Int, enabled: Boolean) {
        field(e, w, h, enabled)

        // The drop-down button shares the box's right-hand line and is as tall as the box.
        val boxW = 17
        val l = e.px(designW - boxW)
        val r = w
        e.fill(l, 0f, r, h, pal.face)
        e.outline(l, 0f, r, h, black)
        val lw = e.lw
        e.fill(l + lw, lw, r - 2 * lw, 2 * lw, pal.hilight)
        e.fill(l + lw, lw, l + 2 * lw, h - 2 * lw, pal.hilight)
        e.fill(l + lw, h - 3 * lw, r - lw, h - lw, pal.shadow)
        e.fill(r - 3 * lw, lw, r - lw, h - lw, pal.shadow)

        // USER's combo glyph: a stem, a 7-5-3-1 arrow under it, and a bar beneath
        val cx = designW - boxW / 2 - 1
        val top = (designH - 9) / 2
        val color = if (enabled) text else disabledText
        e.fillDesign(cx - 1, top, cx + 1, top + 2, color)
        e.arrow(cx, top + 3, 4, true, color)
        e.fillDesign(cx - 3, top + 8, cx + 3, top + 8, color)
    }

    override fun groupBox(e: WinEdges, w: Float, h: Float, titleWidth: Float) {
        e.outline(0f, 0f, w, h, black)
        if (titleWidth <= 0f) return
        e.fill(e.px(groupTitleX - 2), 0f, e.px(groupTitleX) + titleWidth + 4 * e.unit, e.lw, face)
    }

    override fun page(e: WinEdges, l: Int, t: Int, r: Int, b: Int) {
        e.fill(e.px(l), e.px(t), e.px(r + 1), e.px(b + 1), pageFace)
        e.outline(e.px(l), e.px(t), e.px(r + 1), e.px(b + 1), black)
    }

    override fun tab(e: WinEdges, l: Int, r: Int, selected: Boolean, pageTop: Int) {
        val left = if (selected) l - selectedTabBleed else l
        val right = if (selected) r + selectedTabBleed else r
        val top = if (selected) selectedTabTop else tabTop
        val bottom = if (selected) pageTop else tabBottom

        if (selected) {
            // Open the page's top line so the tab and the page read as one surface
            e.fillDesign(left + 1, pageTop, right - 1, pageTop, pageFace)
        }
        e.fillDesign(left + 2, top, right - 2, top, black)
        e.fillDesign(left + 1, top + 1, left + 1, top + 1, black)
        e.fillDesign(right - 1, top + 1, right - 1, top + 1, black)
        e.fillDesign(left, top + 2, left, bottom, black)
        e.fillDesign(right, top + 2, right, bottom, black)
    }

    override fun spinButton(
        e: WinEdges, l: Float, t: Float, r: Float, b: Float, up: Boolean, state: WinState,
    ) {
        val lw = e.lw
        val pushed = state == WinState.PRESSED
        e.fill(l, t, r, b, pal.face)
        e.outline(l, t, r, b, black)
        if (pushed) {
            e.fill(l + lw, t + lw, r - lw, t + 2 * lw, pal.shadow)
            e.fill(l + lw, t + lw, l + 2 * lw, b - lw, pal.shadow)
        } else {
            e.fill(l + lw, t + lw, r - 2 * lw, t + 2 * lw, pal.hilight)
            e.fill(l + lw, t + lw, l + 2 * lw, b - 2 * lw, pal.hilight)
            e.fill(l + lw, b - 2 * lw, r - lw, b - lw, pal.shadow)
            e.fill(r - 2 * lw, t + lw, r - lw, b - lw, pal.shadow)
        }
        val nudge = if (pushed) 1 else 0
        val cx = ((l + r) / 2f / e.unit).toInt() + nudge
        val top = (t / e.unit).toInt() + 3 + nudge
        e.arrow(cx, top, 2, !up, if (state == WinState.DISABLED) pal.shadow else text)
    }
}
