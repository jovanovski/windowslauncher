package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class CustomIconItem(
    val name: String,
    val drawable: Drawable,
    val isDefault: Boolean = false,
    val filePath: String? = null
)