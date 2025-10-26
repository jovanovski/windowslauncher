package rocks.gorjan.gokixp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Interface to communicate with MainActivity
interface AppChangeListener {
    fun onAppInstalled(packageName: String)
    fun onAppRemoved(packageName: String)
    fun onAppReplaced(packageName: String)
}

class AppInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppInstallReceiver"
        
        private var listener: AppChangeListener? = null
        
        fun setListener(listener: AppChangeListener?) {
            this.listener = listener
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Broadcast received: ${intent.action}")
        
        val packageName = intent.data?.schemeSpecificPart
        if (packageName == null) {
            Log.e(TAG, "Package name is null in intent: $intent")
            return
        }
        
        Log.d(TAG, "Package name: $packageName")
        Log.d(TAG, "Listener is ${if (listener != null) "not null" else "null"}")
        
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                Log.d(TAG, "PACKAGE_ADDED - replacing: $replacing")
                if (!replacing) {
                    Log.d(TAG, "App installed: $packageName")
                    if (listener != null) {
                        listener?.onAppInstalled(packageName)
                        Log.d(TAG, "Called onAppInstalled for: $packageName")
                    } else {
                        Log.w(TAG, "Listener is null, cannot handle app installation")
                        // Try to start MainActivity to handle this
                        tryStartMainActivity(context, packageName, "install")
                    }
                }
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                Log.d(TAG, "PACKAGE_REMOVED - replacing: $replacing")
                if (!replacing) {
                    Log.d(TAG, "App removed: $packageName")
                    if (listener != null) {
                        listener?.onAppRemoved(packageName)
                        Log.d(TAG, "Called onAppRemoved for: $packageName")
                    } else {
                        Log.w(TAG, "Listener is null, cannot handle app removal")
                        tryStartMainActivity(context, packageName, "remove")
                    }
                }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "App replaced: $packageName")
                if (listener != null) {
                    listener?.onAppReplaced(packageName)
                    Log.d(TAG, "Called onAppReplaced for: $packageName")
                } else {
                    Log.w(TAG, "Listener is null, cannot handle app replacement")
                    tryStartMainActivity(context, packageName, "replace")
                }
            }
        }
    }
    
    private fun tryStartMainActivity(context: Context, packageName: String, action: String) {
        try {
            Log.d(TAG, "Attempting to start MainActivity to handle $action for $packageName")
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("package_action", action)
                putExtra("package_name", packageName)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity", e)
        }
    }
}