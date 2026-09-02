package rocks.gorjan.gokixp.apps.people

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * The screen off while something is close to it.
 *
 * The platform's own proximity wake lock, in one place. What it does is turn the *display*
 * off, which takes the touchscreen with it - reading the sensor by hand could only black
 * the picture out and would leave a screen still listening, and a cheek on a live
 * touchscreen is how a call ends up on hold, on speaker, or hung up.
 *
 * Held by [GokiInCallService] for the length of a call against somebody's ear, and by the
 * test command in [PeopleApp] for a minute at a time. Both go through here so the thing
 * being tested is the thing that runs during a call, rather than a second implementation
 * that happens to look like it.
 *
 * A process-wide single lock, because there is only one screen. Asking for it twice is not
 * an error and does not need counting: the second ask finds it already held.
 */
object ProximityLock {

    private var lock: PowerManager.WakeLock? = null

    /**
     * Whether this phone can do it at all.
     *
     * Not every device has the sensor, and one that does not refuses the lock level rather
     * than holding a lock that does nothing - so this is the first thing worth knowing when
     * the screen stays on and it is not obvious why.
     */
    fun supported(context: Context): Boolean = try {
        power(context)?.isWakeLockLevelSupported(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask whether the screen can be turned off by proximity", e)
        false
    }

    fun isHeld(): Boolean = lock?.isHeld == true

    /** Takes the lock, and says whether it actually got it. */
    fun hold(context: Context): Boolean {
        val app = context.applicationContext
        return try {
            val power = power(app) ?: return false
            if (!power.isWakeLockLevelSupported(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            ) return false
            val held = lock ?: power.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG
            ).also { lock = it }
            if (!held.isHeld) held.acquire()
            held.isHeld
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch for something close to the screen", e)
            false
        }
    }

    fun release() {
        val held = lock ?: return
        try {
            if (held.isHeld) {
                // Held until the sensor is clear. A call ended with the phone still at an
                // ear would otherwise light the screen against a cheek on its way down,
                // which is the moment this whole arrangement exists to avoid.
                held.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not let go of the proximity lock", e)
        }
    }

    private fun power(context: Context): PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * What the lock is called in a battery report.
     *
     * Named after what it does rather than after this app: a wake lock shows up in the
     * phone's own power screens, and "gokixp" there says nothing to anybody reading it.
     */
    private const val TAG = "WindowsLauncher:proximity"
}
