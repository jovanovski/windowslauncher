package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class WallpaperItem(
    val name: String,
    val drawable: Drawable,
    val isCurrent: Boolean = false,
    val filePath: String? = null,
    val isBuiltIn: Boolean = false
)