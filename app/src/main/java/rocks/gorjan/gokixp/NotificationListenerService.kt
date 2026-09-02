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

        /**
         * The missed calls the user has not dealt with, newest first.
         *
         * Read off the shade rather than off the call log, which is the difference between
         * "you have missed calls" and "you missed some calls at some point": the log keeps
         * every one of them forever, and the notification is the one that goes away when
         * the user has seen it. Which is what the People tile is asking about.
         *
         * Found by category rather than by package, because who posts these depends on
         * which app is the phone - the default dialler if it handles them, and Telecom
         * itself if it does not. The category is the one thing that is true either way.
         */
        private var missedCallLines: List<NotificationLine> = emptyList()

        fun missedCalls(): List<NotificationLine> = missedCallLines

        /**
         * Text messages waiting, for the same tile and on the same terms.
         *
         * This app's own, and only its own. Once People holds the messaging role it is the
         * one thing on the phone that announces an arriving text, so its notifications are
         * the whole of the answer - and matching by anything broader would put every chat
         * app on the phone onto a tile that is about this one. See MessageNotifier.
         */
        private var messageLines: List<NotificationLine> = emptyList()

        fun messages(): List<NotificationLine> = messageLines

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

        // A missed call is read by what it is rather than by who sent it, and who sent it
        // is a system package - which the filter below drops, taking the refresh with it.
        // So it is answered here, before the filter has a chance to say this notification
        // is of no interest. See isMissedCall.
        if (isMissedCall(sbn)) notifyMainActivity()

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
    
    /**
     * Whether a notification is a call that went unanswered.
     *
     * [android.app.Notification.CATEGORY_MISSED_CALL] is the answer the platform documents
     * and not the one it gives: Telecom's own `MissedCallNotifierImpl` - which is what
     * posts these once the default phone app does not handle them itself, and so is what
     * posts them here - sets no category at all. So the channel is asked as well, and every
     * app that has one of these names it the same way: `TelecomMissedCalls`,
     * `phone_missed_call`, `missed_calls`. Both words are required, which is what keeps
     * "Missed alarms" out.
     */
    /** One of this app's own message notifications, arriving or failing to send. */
    private fun isOurMessage(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != packageName) return false
        val channel = sbn.notification.channelId?.lowercase() ?: return false
        return channel.contains("message")
    }

    private fun isMissedCall(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == android.app.Notification.CATEGORY_MISSED_CALL) {
            return true
        }
        val channel = sbn.notification.channelId?.lowercase() ?: return false
        return channel.contains("missed") && channel.contains("call")
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
            val missed = mutableListOf<NotificationLine>()
            val texts = mutableListOf<NotificationLine>()
            for (sbn in getActiveNotifications() ?: emptyArray()) {
                // Gathered here for the same reason the missed calls are: the People tile
                // has no package to read them from, and these are wanted whole rather than
                // as part of whatever this launcher's own package happens to be showing.
                if (isOurMessage(sbn)) {
                    val bundle = sbn.notification.extras
                    val who = bundle?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.toString()?.trim().orEmpty()
                    val what = bundle?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.toString()?.trim().orEmpty()
                    if (who.isNotEmpty() || what.isNotEmpty()) {
                        texts.add(NotificationLine(who, if (what == who) "" else what))
                    }
                }
                // Gathered before the filter below rather than after it: a missed call is
                // worth surfacing whoever posted it, and Telecom's own is a system
                // notification of exactly the kind that filter is there to drop.
                if (isMissedCall(sbn)) {
                    val bundle = sbn.notification.extras
                    val who = bundle?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.toString()?.trim().orEmpty()
                    val what = bundle?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.toString()?.trim().orEmpty()
                    if (who.isNotEmpty() || what.isNotEmpty()) {
                        // Telecom's own says "Missed call" in both lines. Repeating it
                        // under itself on a tile is a tile saying one thing twice.
                        missed.add(NotificationLine(who, if (what == who) "" else what))
                    }
                }
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
            missedCallLines = missed.asReversed().distinct()
            messageLines = texts.asReversed().distinct()
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