package rocks.gorjan.gokixp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class LockScreenAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: LockScreenAccessibilityService? = null
        
        fun lockScreen(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
                Log.d("AccessibilityService", "Lock screen attempt: $result")
                result
            } else {
                Log.w("AccessibilityService", "Lock screen requires Android 9+")
                false
            }
        }
        
        fun isServiceEnabled(): Boolean {
            return instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AccessibilityService", "LockScreenAccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d("AccessibilityService", "LockScreenAccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events, only use global actions
    }

    override fun onInterrupt() {
        // Required override, but we don't need to handle interruptions
    }
}