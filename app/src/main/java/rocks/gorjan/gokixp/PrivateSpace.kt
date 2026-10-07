package rocks.gorjan.gokixp

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Android 15's private space, as the Start menu sees it.
 *
 * The isolation is Android's: the private space is a separate profile with its own storage,
 * accounts and app data, and nothing here can widen or narrow that. What a launcher gets is
 * the right to list the profile's apps, launch them and lock or unlock the profile - and
 * only while it is the default home app holding ACCESS_HIDDEN_PROFILES. Without both,
 * [LauncherApps.getProfiles] simply leaves the private profile out, so every function here
 * degrades to "there is no private space".
 *
 * Work profiles come back from the same calls and are deliberately ignored.
 */
object PrivateSpace {

    private const val TAG = "PrivateSpace"

    /**
     * Settings > Security > Private space > "Hide private space when it's locked". Not public
     * API; Launcher3 reads the same key. Where the read is refused we show the entry, which is
     * what the setting's default is.
     */
    private const val HIDE_ENTRY_POINT_KEY = "hide_privatespace_entry_point"

    /** The private profile, or null when there is none or this app may not see it. */
    fun profile(context: Context): UserHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            launcherApps.profiles.firstOrNull { user ->
                launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up the private profile", e)
            null
        }
    }

    fun isLocked(context: Context, user: UserHandle): Boolean =
        context.getSystemService(UserManager::class.java).isQuietModeEnabled(user)

    /**
     * Unlocking asks the user for their private space credential first; the result arrives
     * later as a profile broadcast, not as this return value.
     */
    fun setLocked(context: Context, user: UserHandle, locked: Boolean) {
        try {
            context.getSystemService(UserManager::class.java).requestQuietModeEnabled(locked, user)
        } catch (e: Exception) {
            Log.w(TAG, "Could not ${if (locked) "lock" else "unlock"} the private space", e)
        }
    }

    /** True when the user asked for the private space to vanish from app lists while locked. */
    fun isHiddenWhenLocked(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, HIDE_ENTRY_POINT_KEY, 0) == 1
    } catch (e: Exception) {
        false
    }

    /** The private space's launchable apps, badged, or nothing while it is locked. */
    fun loadApps(context: Context, user: UserHandle): List<AppInfo> {
        if (isLocked(context, user)) return emptyList()
        return try {
            val density = context.resources.displayMetrics.densityDpi
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(null, user)
                .map { activity ->
                    AppInfo(
                        name = activity.label.toString(),
                        packageName = activity.componentName.packageName,
                        icon = activity.getBadgedIcon(density),
                        user = user,
                        component = activity.componentName
                    )
                }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list private space apps", e)
            emptyList()
        }
    }

    fun launch(context: Context, app: AppInfo) {
        val user = app.user ?: return
        val component = app.component ?: return
        try {
            context.getSystemService(LauncherApps::class.java).startMainActivity(component, user, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch ${app.packageName} in the private space", e)
        }
    }

    /** The app's system info page, which is also where it is uninstalled from. */
    fun openAppDetails(context: Context, app: AppInfo) {
        val user = app.user ?: return
        val component = app.component ?: return
        try {
            context.getSystemService(LauncherApps::class.java).startAppDetailsActivity(component, user, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app info for ${app.packageName}", e)
        }
    }

    fun openSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        try {
            val sender = activity.getSystemService(LauncherApps::class.java).privateSpaceSettingsIntent ?: return
            activity.startIntentSender(sender, null, 0, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open private space settings", e)
        }
    }
}

/** The "Private" row that heads the private space's apps in All Programs. */
data class PrivateSpaceHeader(val isLocked: Boolean)
