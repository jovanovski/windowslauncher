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
    var x: Float,
    var y: Float,
    val id: String = "${packageName}_${System.currentTimeMillis()}",
    val type: IconType = IconType.APP,
    var parentFolderId: String? = null  // ID of parent folder, null if on desktop
)