package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

enum class IconType {
    APP,
    FOLDER,
    RECYCLE_BIN
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
    var landscapeGridIndex: Int? = null   // Grid index for landscape orientation (0, 1, 2, 3...)
)