package rocks.gorjan.gokixp.winui.dialog

import rocks.gorjan.gokixp.winui.WinPalette

/**
 * Windows 7.
 *
 * Nothing but the palette, and even that differs from Vista's in one colour: grey text went
 * from #808080 to #6D6D6D. Every common control - button, tab, group box, edit, combo, spin -
 * is drawn from art that is byte-identical between the two, so it is all in [AeroSkin].
 *
 * Windows 7 redrew the shell, not the controls: of Vista's bitmaps, the ones that changed are
 * TaskBar, TrayNotify, StartMenu, Explorer and their neighbours, while Tab, Spin, Listbox,
 * ControlPanel and forty-odd other control classes came through untouched.
 */
object Aero7Skin : AeroSkin(WinPalette.WIN7)
