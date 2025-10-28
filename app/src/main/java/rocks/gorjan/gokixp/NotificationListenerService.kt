package rocks.gorjan.gokixp

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private var instance: NotificationListenerService? = null
        private val activeNotificationPackages = mutableSetOf<String>()

        // Common email app package names
        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",           // Gmail
            "com.yahoo.mobile.client.android.mail", // Yahoo Mail
            "com.microsoft.office.outlook",    // Outlook
            "ru.yandex.mail",                  // Yandex Mail
            "com.samsung.android.email.provider", // Samsung Email
            "com.android.email",               // Stock Android Email
            "com.email",                       // Generic email
            "com.android.mail",                // Android Mail
            "com.google.android.email",        // Google Email
            "com.yahoo.mail",                  // Yahoo Mail (alternate)
            "com.microsoft.outlook",           // Outlook (alternate)
            "com.Edison.Mail",                 // Edison Mail
            "com.easilydo.mail",               // Edison Mail (alternate)
            "com.fsck.k9",                     // K-9 Mail
            "com.bluemail.mail",               // BlueMail
            "com.typemailapp.mail",            // TypeMail
            "com.mail.mobile.android.mail",    // Mail.Ru
            "com.syntomo.email",               // Email - Mail Mailbox
            "org.kman.AquaMail",               // Aqua Mail
            "com.mobisystems.office",          // OfficeSuite Mail
        )

        fun getInstance(): NotificationListenerService? = instance

        fun getActiveNotificationPackages(): Set<String> = activeNotificationPackages.toSet()

        fun hasNotification(packageName: String): Boolean = activeNotificationPackages.contains(packageName)

        fun isEmailApp(packageName: String): Boolean = EMAIL_PACKAGES.contains(packageName)
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "NotificationListenerService connected")
        refreshActiveNotifications()
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        activeNotificationPackages.clear()
        Log.d(TAG, "NotificationListenerService disconnected")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName
        val isOngoing = sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        Log.d(TAG, "Notification posted from: $packageName (ongoing=$isOngoing)")

        // Handle email notifications specially - always play sound and show dot
        // even if they would normally be filtered out
        if (isEmailApp(packageName)) {
            Log.d(TAG, "Email notification detected from: $packageName")
            val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                Log.d(TAG, "MainActivity instance found, playing email sound")
                mainActivity.playEmailSound()
            } else {
                Log.w(TAG, "MainActivity instance is null, cannot play email sound")
            }
            // Only add non-ongoing email notifications
            if (!isOngoing) {
                activeNotificationPackages.add(packageName)
                notifyMainActivity()
            } else {
                Log.d(TAG, "Skipping ongoing email notification from $packageName")
            }
            return
        }

        if (!shouldShowNotification(sbn)) {
            Log.d(TAG, "Notification from $packageName filtered out (ongoing=$isOngoing)")
            return
        }

        Log.d(TAG, "Active notification posted for: $packageName")

        activeNotificationPackages.add(packageName)

        notifyMainActivity()
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        val packageName = sbn.packageName
        Log.d(TAG, "Notification removed for: $packageName")

        // Check if there are still active notifications for this package
        // Only count non-ongoing notifications
        val stillHasNotifications = try {
            val notifications = getActiveNotifications().filter {
                it.packageName == packageName
            }

            // Log what we found for debugging
            Log.d(TAG, "Found ${notifications.size} notifications for $packageName")
            notifications.forEach { notif ->
                val isOngoing = notif.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
                Log.d(TAG, "  - Notification ongoing=$isOngoing")
            }

            // Only count notifications that pass our filter (non-ongoing, non-system)
            notifications.any { shouldShowNotification(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notifications for $packageName", e)
            false
        }

        if (!stillHasNotifications) {
            Log.d(TAG, "Removing $packageName from active notifications")
            activeNotificationPackages.remove(packageName)
            notifyMainActivity()
        } else {
            Log.d(TAG, "Keeping $packageName in active notifications")
        }
    }
    
    private fun refreshActiveNotifications() {
        try {
            activeNotificationPackages.clear()

            val notifications = getActiveNotifications()
            Log.d(TAG, "Refreshing notifications, found ${notifications.size} total notifications")

            for (notification in notifications) {
                val isOngoing = notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
                val shouldShow = shouldShowNotification(notification)
                Log.d(TAG, "  ${notification.packageName}: ongoing=$isOngoing, shouldShow=$shouldShow")

                if (shouldShow) {
                    activeNotificationPackages.add(notification.packageName)
                }
            }

            Log.d(TAG, "Refreshed active notifications: ${activeNotificationPackages.size} packages: $activeNotificationPackages")
            notifyMainActivity()

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }
    
    private fun shouldShowNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification

        // Skip ongoing notifications (like music players, timers, etc.)
        if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) {
            return false
        }

        // Skip system notifications
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") {
            return false
        }

        return true
    }
    
    private fun notifyMainActivity() {
        // Notify MainActivity to update notification dots
        MainActivity.getInstance()?.updateNotificationDots()
    }
}