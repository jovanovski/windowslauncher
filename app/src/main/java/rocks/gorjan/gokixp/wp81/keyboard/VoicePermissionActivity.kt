package rocks.gorjan.gokixp.wp81.keyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Asks for the microphone on the keyboard's behalf, and gets out of the way.
 *
 * An input method cannot request a runtime permission. The request has to be made by an
 * Activity, and a keyboard has none - it is a service, drawn over whatever application happens
 * to be in front. So this exists: no layout, no window of its own worth looking at, on screen
 * for exactly as long as the system dialog it raises.
 *
 * It finishes without waiting for an answer. There is nothing here to tell about the outcome,
 * and the keyboard finds out the only way that matters - by asking whether it has the
 * permission the next time the microphone is pressed.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
        // No animation on the way out: this was never a screen, and a page sliding away that
        // the user never saw arrive reads as something having gone wrong.
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST = 1
    }
}
