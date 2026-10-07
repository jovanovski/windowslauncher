package rocks.gorjan.gokixp

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

data class AppInfo(
    val name: String,
    val packageName: String,
    val exeName: String? = null,
    val icon: Drawable,  // Icons loaded when start menu opens, released when it closes
    val minWindowWidthDp: Int = 300,  // Minimum window width in dp when resizing
    val minWindowHeightDp: Int = 250,  // Minimum window height in dp when resizing
    // Set only for apps in the private space. The same package can be installed on both
    // sides, so the profile is part of what makes an app this app.
    val user: UserHandle? = null,
    val component: ComponentName? = null
) {
    val isPrivate: Boolean get() = user != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AppInfo
        return packageName == other.packageName && user == other.user
    }

    override fun hashCode(): Int {
        return 31 * packageName.hashCode() + (user?.hashCode() ?: 0)
    }
}
