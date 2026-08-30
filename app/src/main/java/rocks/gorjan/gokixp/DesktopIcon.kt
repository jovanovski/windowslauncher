package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

enum class IconType {
    APP,
    FOLDER,
    RECYCLE_BIN,
    MY_COMPUTER,
    URL_SHORTCUT
}

data class DesktopIcon(
    val name: String,
    val packageName: String,
    var icon: Drawable,
    var x: Float,  // Deprecated: Used only for migration from old system
    var y: Float,  // Deprecated: Used only for migration from old system
    val id: String = "${packageName}_${System.currentTimeMillis()}",
    val type: IconType = IconType.APP,
    var parentFolderId: String? = null,  // ID of parent folder, null if on desktop
    var portraitGridIndex: Int? = null,   // Grid index for portrait orientation (0, 1, 2, 3...)
    var landscapeGridIndex: Int? = null,   // Grid index for landscape orientation (0, 1, 2, 3...)
    var targetUrl: String? = null,   // For URL_SHORTCUT icons: the web address to open on tap
    // Windows Phone 8.1 Start screen placement. Null means "not placed yet", so existing
    // saved icons load unchanged and the desktop layout is preserved when switching back
    // to XP / Vista / Classic. See rocks.gorjan.gokixp.wp81.
    var tileSize: String? = null,    // TileSize.name: SMALL | MEDIUM | WIDE
    var tileIndex: Int? = null,      // packing order on the Start screen
    // And the same two again for the phone on its side. The wall there is a different
    // shape - twice as wide and half as tall, packed into twice the columns - so it is a
    // different arrangement, kept separately and remembered separately. Null means the
    // screen has not been arranged that way yet, in which case the upright one is used
    // until it is.
    var tileSizeLandscape: String? = null,
    var tileIndexLandscape: Int? = null
)