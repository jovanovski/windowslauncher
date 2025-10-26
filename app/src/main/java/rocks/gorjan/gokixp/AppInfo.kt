package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable  // Icons loaded when start menu opens, released when it closes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AppInfo
        return packageName == other.packageName
    }

    override fun hashCode(): Int {
        return packageName.hashCode()
    }
}