package rocks.gorjan.gokixp.winui.dialog

import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.winui.WinPalette

/**
 * Windows 95 through 2000: the "Windows Standard" scheme as Windows 98 shipped it.
 *
 * Every number here was read off a 1:1 screenshot of the real Display Properties with a pixel
 * ruler. The edges are two lines of one pixel each and never a gradient, nothing is rounded,
 * and greyed-out text is embossed rather than merely grey.
 */
object ClassicSkin : WinSkin(WinPalette.CLASSIC) {

    override val fontRes = R.font.micross_block
    override val embossDisabledText = true
    override val movesCaptionWhenPressed = true

    override fun button(e: WinEdges, w: Float, h: Float, state: WinState) {
        e.fill(0f, 0f, w, h, face)
        if (state == WinState.PRESSED) {
            // The sunken edge one notch darker, which is how a 95 button looks pushed in.
            e.sunken(0f, 0f, w, h, pal.hilight, pal.light, pal.dkShadow, pal.shadow)
        } else {
            e.raised(0f, 0f, w, h, pal.hilight, pal.light, pal.shadow, pal.dkShadow)
        }
    }

    override fun field(e: WinEdges, w: Float, h: Float, enabled: Boolean) {
        e.fill(0f, 0f, w, h, if (enabled) window else face)
        e.sunken(0f, 0f, w, h, pal.hilight, pal.light, pal.shadow, pal.dkShadow)
    }

    override fun combo(e: WinEdges, w: Float, h: Float, designW: Int, designH: Int, enabled: Boolean) {
        field(e, w, h, enabled)

        // The drop-down square: 18 design px in from the right, 3 short of it, 2 down from the
        // top and 3 up from the bottom.
        val l = designW - 18
        val r = designW - 3
        e.fillDesign(l, 2, r, designH - 3, face)
        e.smallRaised(e.px(l), e.px(2), e.px(r + 1), e.px(designH - 2), pal.hilight, pal.shadow, pal.dkShadow)

        val cx = (l + r) / 2
        if (enabled) {
            e.arrow(cx, 8, 4, true, text)
        } else {
            e.arrow(cx + 1, 9, 4, true, pal.hilight)
            e.arrow(cx, 8, 4, true, pal.shadow)
        }
    }

    override fun groupBox(e: WinEdges, w: Float, h: Float, titleWidth: Float) {
        e.etched(0f, 0f, w, h, pal.shadow, pal.hilight)
        if (titleWidth <= 0f) return
        // Open a gap in both lines of the groove for the caption to sit in.
        e.fill(e.px(groupTitleX - 2), 0f, e.px(groupTitleX) + titleWidth + 4 * e.unit, 2 * e.lw, face)
    }

    override fun page(e: WinEdges, l: Int, t: Int, r: Int, b: Int) {
        e.raised(e.px(l), e.px(t), e.px(r + 1), e.px(b + 1), pal.hilight, pal.light, pal.shadow, pal.dkShadow)
    }

    override fun tab(e: WinEdges, l: Int, r: Int, selected: Boolean, pageTop: Int) {
        val left = if (selected) l - selectedTabBleed else l
        val right = if (selected) r + selectedTabBleed else r
        val top = if (selected) selectedTabTop else tabTop
        val bottom = if (selected) pageTop else tabBottom

        if (selected) {
            // Open the page's top edge so the tab and the page read as one surface.
            e.fillDesign(left + 1, pageTop, right - 2, pageTop, face)
        }
        e.fillDesign(left + 2, top, right - 2, top, pal.hilight)
        e.fillDesign(left + 1, top + 1, left + 1, top + 1, pal.hilight)
        e.fillDesign(left, top + 2, left, bottom, pal.hilight)
        e.fillDesign(right - 1, top + 1, right - 1, top + 1, pal.dkShadow)
        e.fillDesign(right - 1, top + 2, right - 1, bottom, pal.shadow)
        e.fillDesign(right, top + 2, right, bottom, pal.dkShadow)
    }

    override fun spinButton(
        e: WinEdges, l: Float, t: Float, r: Float, b: Float, up: Boolean, state: WinState,
    ) {
        val pushed = state == WinState.PRESSED
        if (pushed) e.smallPressed(l, t, r, b, pal.shadow, pal.dkShadow)
        else e.smallRaised(l, t, r, b, pal.hilight, pal.shadow, pal.dkShadow)

        // A two-row 3-1 arrow, three rows down from the top of its own half.
        val nudge = if (pushed) 1 else 0
        val cx = ((l + r) / 2f / e.unit).toInt() + nudge
        val top = (t / e.unit).toInt() + 3 + nudge
        e.arrow(cx, top, 2, !up, if (state == WinState.DISABLED) pal.shadow else text)
    }
}
