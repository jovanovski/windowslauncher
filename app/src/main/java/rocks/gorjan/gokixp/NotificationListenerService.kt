package rocks.gorjan.gokixp

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private var instance: NotificationListenerService? = null
        private val activeNotificationPackages = mutableSetOf<String>()

        /**
         * Notification small icons seen per package, for the Windows Phone 8.1 tiles.
         *
         * Android requires a small icon to be a flat monochrome silhouette, which makes
         * it a good source of tile art for apps that ship no Android 13 themed icon.
         * Cached rather than read live because a tile has to paint whether or not the
         * app currently has a notification up. Icons are small and bounded by the number
         * of installed apps, so this is not worth evicting.
         */
        private val smallIcons = mutableMapOf<String, android.graphics.drawable.Icon>()

        /**
         * Live notification text per package, for the Windows Phone 8.1 tiles.
         *
         * Rebuilt from the currently posted notifications rather than accumulated, so a
         * tile never shows something the user has already dismissed. Ordered newest first.
         */
        private val notificationText = mutableMapOf<String, List<NotificationLine>>()

        /** One notification, reduced to what a tile can show. */
        /**
         * One notification, as a tile shows it.
         *
         * [image] is whatever the app put on its own notification - the photograph in a
         * post, the avatar of whoever is messaging - already scaled down to tile size. A
         * notification with none simply has none; most do not.
         */
        /** Longest edge a notification picture is kept at. A tile is not a gallery. */
        private const val IMAGE_MAX_PX = 256

        data class NotificationLine(
            val title: String,
            val text: String,
            val image: android.graphics.Bitmap? = null
        )

        /** Notification lines for [packageName], newest first, or empty. */
        fun getNotificationLines(packageName: String): List<NotificationLine> =
            notificationText[packageName].orEmpty()

        /** Every package that currently has text worth showing on a tile. */
        fun packagesWithText(): Set<String> = notificationText.keys.toSet()

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

        /**
         * The most recent notification small icon for [packageName], loaded as a
         * drawable, or null if this app has not posted a notification since boot.
         * See MonochromeIconProvider for how the WP8.1 tiles use it.
         */
        fun getSmallIcon(
            context: android.content.Context,
            packageName: String
        ): android.graphics.drawable.Drawable? = try {
            smallIcons[packageName]?.loadDrawable(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load small icon for $packageName", e)
            null
        }

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

        // Keep the small icon for the WP8.1 Start tiles. Harvested from every posted
        // notification, including ongoing and silent ones that are filtered out below -
        // we want the artwork regardless of whether the notification itself counts.
        try {
            sbn.notification.smallIcon?.let { smallIcons[packageName] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read small icon for $packageName", e)
        }
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
    
    /**
     * Rebuilds the per-package notification text from what is currently posted.
     *
     * Reads the live set every time instead of tracking adds and removals: notifications
     * are updated in place as often as they are posted fresh, and reconciling that
     * incrementally is far easier to get wrong than simply re-reading it.
     */
    private fun refreshNotificationText() {
        try {
            val grouped = mutableMapOf<String, MutableList<NotificationLine>>()
            for (sbn in getActiveNotifications() ?: emptyArray()) {
                if (!shouldShowNotification(sbn)) continue

                // Skip the group summary. Mail and messaging apps post one summary
                // alongside each real notification, so counting both reports two
                // notifications where the user only has one.
                val flags = sbn.notification.flags
                if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue

                val extras = sbn.notification.extras ?: continue
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
                    ?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    ?.toString()?.trim().orEmpty()
                if (title.isEmpty() && text.isEmpty()) continue
                grouped.getOrPut(sbn.packageName) { mutableListOf() }
                    .add(NotificationLine(title, text, notificationImage(sbn.notification)))
            }
            notificationText.clear()
            for ((pkg, lines) in grouped) {
                // Some apps re-post the same content under several ids; identical lines
                // would otherwise show up as separate notifications to cycle through.
                notificationText[pkg] = lines.asReversed().distinct()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading notification text", e)
        }
    }

    /**
     * The picture a notification is carrying, if any.
     *
     * A big-picture style comes first - that is the post, the photo, the thing the
     * notification is *about* - and the large icon second, which on a messaging app is
     * whoever sent it. Both are scaled down here rather than at the tile: they arrive at
     * whatever size the app felt like, several of them are held at once, and a tile is
     * two hundred pixels across.
     */
    private fun notificationImage(notification: android.app.Notification): android.graphics.Bitmap? {
        try {
            val extras = notification.extras ?: return null
            val picture = extras.getParcelable(
                android.app.Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
            if (picture != null) return scaleForTile(picture)

            val large = notification.getLargeIcon() ?: return null
            val drawable = large.loadDrawable(this) ?: return null
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: IMAGE_MAX_PX
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: IMAGE_MAX_PX
            val scale = (IMAGE_MAX_PX.toFloat() / maxOf(width, height)).coerceAtMost(1f)
            val bitmap = android.graphics.Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification's picture", e)
            return null
        }
    }

    private fun scaleForTile(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= IMAGE_MAX_PX) return source
        val scale = IMAGE_MAX_PX.toFloat() / longest
        return android.graphics.Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun notifyMainActivity() {
        refreshNotificationText()
        // Notify MainActivity to update notification dots
        MainActivity.getInstance()?.updateNotificationDots()
    }
}