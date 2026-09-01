package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class WallpaperItem(
    val name: String,
    /**
     * A preview of the picture, decoded small - or null where nothing is showing one.
     *
     * Null by default because the XP picker lists wallpapers by *name*: it decoded all
     * seventy-odd of them at full resolution to fill a list that never drew one, and held
     * them for as long as the dialog lived. Whoever needs a picture asks for it.
     */
    val drawable: Drawable? = null,
    val isCurrent: Boolean = false,
    val filePath: String? = null,
    val isBuiltIn: Boolean = false
)