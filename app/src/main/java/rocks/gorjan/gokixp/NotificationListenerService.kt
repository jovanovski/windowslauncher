package rocks.gorjan.gokixp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {
    
    companion object {
        private const val TAG = "NotificationListener"
        private var instance: NotificationListenerService? = null
        private val activeNotificationPackages = mutableSetOf<String>()
        
        fun getInstance(): NotificationListenerService? = instance
        
        fun getActiveNotificationPackages(): Set<String> = activeNotificationPackages.toSet()
        
        fun hasNotification(packageName: String): Boolean = activeNotificationPackages.contains(packageName)
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
        
        if (!shouldShowNotification(sbn)) {
            return
        }
        
        val packageName = sbn.packageName
        Log.d(TAG, "Active notification posted for: $packageName")
        
        activeNotificationPackages.add(packageName)
        notifyMainActivity()
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        
        val packageName = sbn.packageName
        Log.d(TAG, "Notification removed for: $packageName")
        
        // Check if there are still active notifications for this package
        val stillHasNotifications = try {
            getActiveNotifications().any { it.packageName == packageName && shouldShowNotification(it) }
        } catch (e: Exception) {
            false
        }
        
        if (!stillHasNotifications) {
            activeNotificationPackages.remove(packageName)
            notifyMainActivity()
        }
    }
    
    private fun refreshActiveNotifications() {
        try {
            activeNotificationPackages.clear()
            
            val notifications = getActiveNotifications()
            for (notification in notifications) {
                if (shouldShowNotification(notification)) {
                    activeNotificationPackages.add(notification.packageName)
                }
            }
            
            Log.d(TAG, "Refreshed active notifications: ${activeNotificationPackages.size} packages")
            notifyMainActivity()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }
    
    private fun shouldShowNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        
        // Skip ongoing notifications (like music players)
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            return false
        }
        
        // Skip system notifications  
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") {
            return false
        }
        
        // Skip low priority notifications (using importance for newer APIs)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // Use importance for API 24+
                val channel = notification.channelId?.let { channelId ->
                    // We can't easily check channel importance here without NotificationManager
                    // So we'll just allow all notifications for now
                    true
                } ?: true
                if (!channel) return false
            } else {
                // Use deprecated priority for older APIs
                @Suppress("DEPRECATION")
                if (notification.priority < Notification.PRIORITY_DEFAULT) {
                    return false
                }
            }
        } catch (e: Exception) {
            // If we can't determine priority/importance, show the notification
        }
        
        return true
    }
    
    private fun notifyMainActivity() {
        // Notify MainActivity to update notification dots
        MainActivity.getInstance()?.updateNotificationDots()
    }
}