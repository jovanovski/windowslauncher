package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.winui.WinPalette

/**
 * How one Windows drew its controls.
 *
 * A skin owns the metrics and the painting; the colours come from the shell's [WinPalette], the
 * same one the rest of the launcher's own programs use, so a shade fixed in one place is fixed
 * everywhere. The view classes own behaviour and geometry and know nothing about any of it.
 *
 * Everything a skin is handed is in design pixels, or in device pixels already converted from
 * them, so a skin never has to think about screen density.
 */
abstract class WinSkin(val pal: WinPalette) {

    // ---- Palette, as the controls need it -------------------------------------------------

    /** The dialog's own background. */
    open val face: Int get() = pal.face

    /** Text on [face]. */
    open val text: Int get() = pal.text

    /** Text on a control the user cannot reach. */
    open val disabledText: Int get() = pal.grayText

    /** The inside of an edit field or a list box. */
    open val window: Int get() = pal.window

    /** Text on [window], which is not always the same colour as text on the dialog face. */
    open val windowText: Int get() = pal.text

    /** A property sheet's page, which from XP on is lighter than the dialog around it. */
    open val pageFace: Int get() = pal.face

    /** A group box's caption - XP draws it blue, the other three in the ordinary text colour. */
    open val groupTitleColor: Int get() = pal.text

    /**
     * Whether greyed-out text is embossed - drawn twice, white a pixel down and right with the
     * grey over it. Windows 95 through 2000 do; XP and the Aero shells use a flat grey.
     */
    open val embossDisabledText: Boolean get() = false

    /**
     * Whether a pushed button's caption moves a pixel down and right with the bevel. Only the
     * 9x shells do; from XP on the button changes colour and the text stays put.
     */
    open val movesCaptionWhenPressed: Boolean get() = false

    // ---- Text -----------------------------------------------------------------------------

    /** The shell's UI font, as a font resource id. */
    abstract val fontRes: Int

    /**
     * Em size in design pixels.
     *
     * The grid is a Windows 98 dialog's, whose 8pt MS Sans Serif measured 12 design px against
     * an 11 px em on a real screen - so the grid runs about 1.09x a Windows pixel, and the
     * later shells' bigger faces have to be scaled by the same amount to stay in proportion.
     */
    open val fontPx: Float = 12f

    /** Baseline inside a push button, in design pixels from its top. */
    open val buttonBaseline: Int = 15

    /** Baseline inside a combo box or an edit field, from its top. */
    open val fieldBaseline: Int = 15

    /** A label's baseline, from the top of the label view - which is the top of its capitals. */
    open val labelBaseline: Int = 9

    /** A group caption's baseline, from the top of the box. */
    open val groupTitleBaseline: Int = 5

    /** Where a group caption starts, in from the left of the box. */
    open val groupTitleX: Int = 9

    // ---- Tab strip metrics, in design pixels on the dialog's grid ---------------------------

    open val tabTop: Int = 9
    open val tabBottom: Int = 26
    open val selectedTabTop: Int = 7
    open val tabTextBaseline: Int = 21

    /** How far a selected tab spreads sideways past its natural bounds. */
    open val selectedTabBleed: Int = 2

    // ---- Painting ---------------------------------------------------------------------------

    /** A push button filling (0,0)-([w],[h]). */
    abstract fun button(e: WinEdges, w: Float, h: Float, state: WinState)

    /** The frame and fill of an edit field or a list well. */
    abstract fun field(e: WinEdges, w: Float, h: Float, enabled: Boolean)

    /**
     * A combo box: the field, then its drop-down button. [designW] and [designH] are the same
     * box in design pixels, for the skins whose button geometry is defined that way.
     */
    abstract fun combo(e: WinEdges, w: Float, h: Float, designW: Int, designH: Int, enabled: Boolean)

    /**
     * A group box filling (0,0)-([w],[h]), leaving a gap for a caption [titleWidth] device
     * pixels wide starting at design column [groupTitleX]. The caption is drawn by the caller.
     */
    abstract fun groupBox(e: WinEdges, w: Float, h: Float, titleWidth: Float)

    /** The tab control's page panel, given its inclusive design-pixel bounds. */
    abstract fun page(e: WinEdges, l: Int, t: Int, r: Int, b: Int)

    /**
     * One tab. [l] and [r] are its natural inclusive design columns - the bounds it has when it
     * is not selected - and [pageTop] the design row the page panel starts on.
     */
    abstract fun tab(e: WinEdges, l: Int, r: Int, selected: Boolean, pageTop: Int)

    /** One half of a spin control, arrow included. */
    abstract fun spinButton(e: WinEdges, l: Float, t: Float, r: Float, b: Float, up: Boolean, state: WinState)

    // ---- Shared -----------------------------------------------------------------------------

    private var typeface: Typeface? = null

    fun typeface(context: Context): Typeface {
        typeface?.let { return it }
        val tf = ResourcesCompat.getFont(context, fontRes) ?: Typeface.SANS_SERIF
        typeface = tf
        return tf
    }

    /** Sets [into] up to draw dialog text at [unit] device pixels per design pixel. */
    fun textPaint(context: Context, unit: Float, into: Paint): Paint = into.apply {
        typeface = typeface(context)
        textSize = fontPx * unit
        isAntiAlias = true
    }

    /**
     * Narrows a caption's type a little rather than let it spill.
     *
     * The dialog's boxes are Windows 98's, and Tahoma and Segoe UI are wider faces than the MS
     * Sans Serif they were measured for - "Picture Display:" is 72 design px in 98 and 87 in
     * Vista, in a box 75 wide. Windows would have made the dialog bigger; the grid cannot, so
     * the few captions that would run over give up a little size instead. Nothing shrinks in
     * the Classic skin, where everything already fits.
     */
    fun fitCaption(paint: Paint, text: String, available: Float, unit: Float) {
        paint.textSize = fontPx * unit
        if (available <= 0f || text.isEmpty()) return
        val w = paint.measureText(text)
        if (w <= available) return
        paint.textSize = fontPx * unit * (available / w).coerceAtLeast(0.75f)
    }

    /**
     * Draws a caption with its accelerator underlined. Greyed-out text is embossed or flat
     * depending on the shell - see [embossDisabledText].
     */
    fun caption(
        e: WinEdges,
        caption: WinCaption,
        paint: Paint,
        x: Float,
        baseline: Float,
        enabled: Boolean,
        color: Int = if (enabled) text else disabledText,
    ) {
        if (!enabled && embossDisabledText) {
            paint.color = pal.hilight
            paintCaption(e, caption, paint, x + e.lw, baseline + e.lw)
        }
        paint.color = color
        paintCaption(e, caption, paint, x, baseline)
    }

    private fun paintCaption(e: WinEdges, caption: WinCaption, paint: Paint, x: Float, baseline: Float) {
        e.c.drawText(caption.text, x, baseline, paint)
        val i = caption.accelerator
        if (i < 0 || i >= caption.text.length) return
        val before = paint.measureText(caption.text, 0, i)
        val w = paint.measureText(caption.text, i, i + 1)
        val top = baseline + e.unit
        e.c.drawRect(x + before, top, x + before + w, top + e.lw, paint)
    }

    companion object {
        /** The skin the launcher's current shell wears. */
        fun of(theme: AppTheme): WinSkin = when (theme) {
            AppTheme.WindowsClassic -> ClassicSkin
            AppTheme.WindowsXP -> LunaSkin
            AppTheme.WindowsVista -> VistaSkin
            AppTheme.Windows7 -> Aero7Skin
        }
    }
}
