package rocks.gorjan.gokixp.winui

import rocks.gorjan.gokixp.theme.AppTheme

/**
 * The system colours of one Windows shell.
 *
 * Every program in this launcher that draws its own chrome - rather than sitting on a
 * screenshot - needs the same dozen colours, and needs them to change together when the
 * theme does. They are the Win32 `COLOR_*` names because that is what they are: a control
 * panel's "Appearance" tab set exactly these, and every dialog in the operating system was
 * assembled out of them.
 *
 * Sampled from the shells themselves rather than invented. Classic's are the "Windows
 * Standard" scheme as Windows 98 shipped it; XP's are Luna Blue; Vista's and 7's are Aero
 * with the composited glass left to the window frame, which is [rocks.gorjan.gokixp.WindowsDialog]'s
 * business and not a program's.
 */
data class WinPalette(
    /** COLOR_3DFACE - every dialog, button, toolbar and menu bar. */
    val face: Int,
    /** COLOR_3DHILIGHT - the outer top/left edge of anything raised. */
    val hilight: Int,
    /** COLOR_3DLIGHT - the inner top/left edge. Equal to [face] in the Standard scheme. */
    val light: Int,
    /** COLOR_3DSHADOW - inner bottom/right edge, and the colour of greyed-out text. */
    val shadow: Int,
    /** COLOR_3DDKSHADOW - outer bottom/right edge. */
    val dkShadow: Int,
    /** COLOR_WINDOW - the inside of a list box, an edit field, a message pane. */
    val window: Int,
    /** COLOR_WINDOWTEXT. */
    val text: Int,
    /** COLOR_GRAYTEXT - a disabled command, a hint, a timestamp. */
    val grayText: Int,
    /** COLOR_HIGHLIGHT - the selected row's fill. */
    val select: Int,
    /**
     * The second stop of the selection, for the shells that draw it as a gradient. Equal to
     * [select] on Classic and XP, which fill it flat.
     */
    val selectEnd: Int,
    /** The line around a selected row. Transparent where the shell draws none. */
    val selectBorder: Int,
    /** COLOR_HIGHLIGHTTEXT. Black on the Aero shells, whose selection is a pale wash. */
    val selectText: Int,
    /** The pressed/hovered row, same three parts. Aero only; equals [window] elsewhere. */
    val hover: Int,
    val hoverEnd: Int,
    val hoverBorder: Int,
    /** The line around an edit field or a list, where the shell draws a line at all. */
    val fieldBorder: Int,
    /** The same line while the field has the caret in it. */
    val fieldFocusBorder: Int,
    /** The line around a push button. */
    val buttonBorder: Int,
    /** The two ends of a push button's vertical gradient. */
    val buttonTop: Int,
    val buttonBottom: Int,
    /** The two ends of a pressed push button's gradient. */
    val buttonPressedTop: Int,
    val buttonPressedBottom: Int,
    /** The strip a list's column headings sit on. */
    val headerTop: Int,
    val headerBottom: Int,
    val headerLine: Int,
    /** A toolbar's own fill, which on XP is lighter than the dialog face. */
    val toolbarTop: Int,
    val toolbarBottom: Int,
    /** The etched line a group box and a separator are drawn with. */
    val etch: Int,
    /** Anything that is a link: a number to dial, an address to open. */
    val link: Int,
    /** The wash behind a task pane or a preview, where the shell has one. */
    val paneTop: Int,
    val paneBottom: Int,
    /** How round, in dp, this shell's buttons and fields are. Zero on Classic. */
    val radiusDp: Float
) {
    companion object {
        /** Windows 98, "Windows Standard". */
        val CLASSIC = WinPalette(
            face = 0xFFD3CEC7.toInt(),
            hilight = 0xFFFFFFFF.toInt(),
            light = 0xFFD3CEC7.toInt(),
            shadow = 0xFF7B7D7B.toInt(),
            dkShadow = 0xFF3A3C39.toInt(),
            window = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            grayText = 0xFF7B7D7B.toInt(),
            select = 0xFF000080.toInt(),
            selectEnd = 0xFF000080.toInt(),
            selectBorder = 0x00000000,
            selectText = 0xFFFFFFFF.toInt(),
            hover = 0xFFFFFFFF.toInt(),
            hoverEnd = 0xFFFFFFFF.toInt(),
            hoverBorder = 0x00000000,
            fieldBorder = 0xFF7B7D7B.toInt(),
            fieldFocusBorder = 0xFF7B7D7B.toInt(),
            buttonBorder = 0xFF3A3C39.toInt(),
            buttonTop = 0xFFD3CEC7.toInt(),
            buttonBottom = 0xFFD3CEC7.toInt(),
            buttonPressedTop = 0xFFD3CEC7.toInt(),
            buttonPressedBottom = 0xFFD3CEC7.toInt(),
            headerTop = 0xFFD3CEC7.toInt(),
            headerBottom = 0xFFD3CEC7.toInt(),
            headerLine = 0xFF7B7D7B.toInt(),
            toolbarTop = 0xFFD3CEC7.toInt(),
            toolbarBottom = 0xFFD3CEC7.toInt(),
            etch = 0xFF7B7D7B.toInt(),
            link = 0xFF0000FF.toInt(),
            paneTop = 0xFFD3CEC7.toInt(),
            paneBottom = 0xFFD3CEC7.toInt(),
            radiusDp = 0f
        )

        /** Windows XP, Luna Blue. */
        val XP = WinPalette(
            face = 0xFFECE9D8.toInt(),
            hilight = 0xFFFFFFFF.toInt(),
            light = 0xFFF1EFE2.toInt(),
            shadow = 0xFFACA899.toInt(),
            dkShadow = 0xFF716F64.toInt(),
            window = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            grayText = 0xFFACA899.toInt(),
            select = 0xFF316AC5.toInt(),
            selectEnd = 0xFF316AC5.toInt(),
            selectBorder = 0x00000000,
            selectText = 0xFFFFFFFF.toInt(),
            hover = 0xFFFFFFFF.toInt(),
            hoverEnd = 0xFFFFFFFF.toInt(),
            hoverBorder = 0x00000000,
            fieldBorder = 0xFF7F9DB9.toInt(),
            fieldFocusBorder = 0xFF316AC5.toInt(),
            buttonBorder = 0xFF003C74.toInt(),
            buttonTop = 0xFFFFFFFF.toInt(),
            buttonBottom = 0xFFE4E2D5.toInt(),
            buttonPressedTop = 0xFFDFDCC9.toInt(),
            buttonPressedBottom = 0xFFF5F3E9.toInt(),
            headerTop = 0xFFFFFFFF.toInt(),
            headerBottom = 0xFFECE9D8.toInt(),
            headerLine = 0xFFACA899.toInt(),
            toolbarTop = 0xFFFFFFFF.toInt(),
            toolbarBottom = 0xFFECE9D8.toInt(),
            etch = 0xFFD5D2C6.toInt(),
            link = 0xFF0000EE.toInt(),
            // The blue task pane down the left of an XP Explorer window.
            paneTop = 0xFF7BA2E7.toInt(),
            paneBottom = 0xFF6375D6.toInt(),
            radiusDp = 3f
        )

        /** Windows Vista, Aero. */
        val VISTA = WinPalette(
            face = 0xFFF0F0F0.toInt(),
            hilight = 0xFFFFFFFF.toInt(),
            light = 0xFFE3E3E3.toInt(),
            shadow = 0xFFA0A0A0.toInt(),
            dkShadow = 0xFF696969.toInt(),
            window = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            grayText = 0xFF808080.toInt(),
            // Explorer's selection, not the dialog's: a wash of blue under black type, which
            // is what every list in Vista and 7 actually draws.
            select = 0xFFDCEBFC.toInt(),
            selectEnd = 0xFFC1DBFC.toInt(),
            selectBorder = 0xFF7DA2CE.toInt(),
            selectText = 0xFF000000.toInt(),
            hover = 0xFFFAFCFE.toInt(),
            hoverEnd = 0xFFEAF6FD.toInt(),
            hoverBorder = 0xFFB8D6FB.toInt(),
            fieldBorder = 0xFF7A7A7A.toInt(),
            fieldFocusBorder = 0xFF3D7BAD.toInt(),
            buttonBorder = 0xFF707070.toInt(),
            buttonTop = 0xFFF2F2F2.toInt(),
            buttonBottom = 0xFFE0E0E0.toInt(),
            buttonPressedTop = 0xFFC2E4F6.toInt(),
            buttonPressedBottom = 0xFF9DCBE8.toInt(),
            headerTop = 0xFFF8F8F8.toInt(),
            headerBottom = 0xFFEBEBEB.toInt(),
            headerLine = 0xFFD9D9D9.toInt(),
            toolbarTop = 0xFFF8F8F8.toInt(),
            toolbarBottom = 0xFFECECEC.toInt(),
            etch = 0xFFD9D9D9.toInt(),
            link = 0xFF0066CC.toInt(),
            paneTop = 0xFFF6F6F6.toInt(),
            paneBottom = 0xFFEDEDED.toInt(),
            radiusDp = 3f
        )

        /**
         * Windows 7.
         *
         * Vista's, and that is not laziness: rendering `aero.msstyles` 6.0.6000 and
         * 6.1.7601 at the same size and diffing them gives *zero* differing pixels for the
         * button, the edit field, the combo, the tab item, the tab pane, the group box and
         * both spin buttons - several of those bitmaps are byte-identical between the two
         * releases. Windows 7 did not redraw the common controls; it redrew the shell
         * around them.
         *
         * So what differs here is the shell and one colour. The navigation pane went white,
         * the command bar and the column headings went lighter and flatter, and grey text
         * moved from Vista's #808080 to #6D6D6D. Everything else is deliberately the same
         * because it genuinely is the same.
         *
         * (Measured by the session that owns `winui/dialog`, 2026-09-20. Two traps it also
         * turned up and that should not get "fixed" later: the #8E8F8F that circulates as
         * the Windows 7 button border is the theme's BORDERCOLORHINT metadata, not a line
         * anything draws - the drawn button border is #707070 on both shells, which is what
         * [VISTA] already says. The #898C95-ish shade is the *tab* border.)
         */
        val WIN7 = VISTA.copy(
            grayText = 0xFF6D6D6D.toInt(),
            paneTop = 0xFFFFFFFF.toInt(),
            paneBottom = 0xFFFFFFFF.toInt(),
            toolbarTop = 0xFFF0F5FB.toInt(),
            toolbarBottom = 0xFFE4EDF7.toInt(),
            headerTop = 0xFFFFFFFF.toInt(),
            headerBottom = 0xFFF3F3F3.toInt()
        )

        fun of(theme: AppTheme): WinPalette = when (theme) {
            AppTheme.WindowsClassic -> CLASSIC
            AppTheme.WindowsXP -> XP
            AppTheme.WindowsVista -> VISTA
            AppTheme.Windows7 -> WIN7
        }
    }
}
