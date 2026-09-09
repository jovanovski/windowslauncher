package rocks.gorjan.gokixp

import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private var instance: NotificationListenerService? = null
        private val activeNotificationPackages = mutableSetOf<String>()

        // Messages the mail sound has already been played for, as "key@timestamp".
        // Mail apps re-post the same notification whenever it changes, and those
        // re-posts must not ring again.
        private val ringedEmailKeys = LinkedHashSet<String>()
        private const val RINGED_EMAIL_KEYS_MAX = 64

        // One mail reaches us as several posts (a per-message notification plus a
        // group summary), so posts landing this close together share one sound.
        private const val EMAIL_SOUND_DEBOUNCE_MS = 2000L
        private var lastEmailSoundAt = 0L

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
        refreshActiveNotifications()
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        activeNotificationPackages.clear()
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName
        val isOngoing = sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        // Handle email notifications specially - always play sound and show dot
        // even if they would normally be filtered out
        if (isEmailApp(packageName)) {
            Log.d(TAG, "Email notification detected from: $packageName")
            if (shouldRingForEmail(sbn)) {
                val mainActivity = MainActivity.getInstance()
                if (mainActivity != null) {
                    Log.d(TAG, "MainActivity instance found, playing email sound")
                    mainActivity.playEmailSound()
                } else {
                    Log.w(TAG, "MainActivity instance is null, cannot play email sound")
                }
            }
            // Only add non-ongoing, non-silent email notifications
            if (!isOngoing && !isSilentNotification(sbn)) {
                activeNotificationPackages.add(packageName)
                notifyMainActivity()
            }
            return
        }

        if (!shouldShowNotification(sbn)) {
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

        // A dismissed message may legitimately ring again if the app re-posts it.
        ringedEmailKeys.removeAll { it.startsWith("${sbn.key}@") }

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
    
    /**
     * Whether this mail notification is a new message that should play the
     * "You've got mail" sound.
     *
     * A single incoming mail produces more than one post: Gmail and friends post
     * the per-message notification *and* a group summary for it, then re-post the
     * notification as it is updated (actions added, sync finished). Each of those
     * used to ring, so one mail was heard twice.
     */
    private fun shouldRingForEmail(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification

        // A foreground-service notification ("Getting your mail...") is not a mail.
        if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) {
            return false
        }

        // The timestamp keeps a new message in an existing conversation distinct
        // from a re-post of the message already rung for, which reuses the key.
        val stamp = if (notification.`when` > 0L) notification.`when` else sbn.postTime
        if (!ringedEmailKeys.add("${sbn.key}@$stamp")) {
            Log.d(TAG, "Mail sound skipped - already played for ${sbn.key}")
            return false
        }
        while (ringedEmailKeys.size > RINGED_EMAIL_KEYS_MAX) {
            ringedEmailKeys.remove(ringedEmailKeys.first())
        }

        // The group summary arrives alongside its message, and a batch of mail
        // arrives as a burst - either way that is one sound, not one per post.
        val now = SystemClock.elapsedRealtime()
        if (lastEmailSoundAt != 0L && now - lastEmailSoundAt < EMAIL_SOUND_DEBOUNCE_MS) {
            Log.d(TAG, "Mail sound skipped - within debounce of the last one")
            return false
        }
        lastEmailSoundAt = now
        return true
    }

    private fun refreshActiveNotifications() {
        try {
            activeNotificationPackages.clear()

            val notifications = getActiveNotifications()

            for (notification in notifications) {
                val isOngoing = notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
                val shouldShow = shouldShowNotification(notification)

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

        // Skip silent notifications (low/min importance channels, no sound or vibration)
        if (isSilentNotification(sbn)) {
            return false
        }

        return true
    }

    /**
     * A notification is considered silent when it is posted at an importance below
     * IMPORTANCE_DEFAULT, i.e. it never makes a sound, vibrates or peeks.
     */
    private fun isSilentNotification(sbn: StatusBarNotification): Boolean {
        try {
            val ranking = Ranking()
            if (currentRanking?.getRanking(sbn.key, ranking) == true) {
                val importance = ranking.importance
                if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
                    return importance < NotificationManager.IMPORTANCE_DEFAULT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ranking for ${sbn.packageName}", e)
        }

        // Fall back to the legacy priority for notifications posted without a channel
        @Suppress("DEPRECATION")
        return sbn.notification.priority < android.app.Notification.PRIORITY_DEFAULT
    }
    
    private fun notifyMainActivity() {
        // Notify MainActivity to update notification dots
        MainActivity.getInstance()?.updateNotificationDots()
    }
}