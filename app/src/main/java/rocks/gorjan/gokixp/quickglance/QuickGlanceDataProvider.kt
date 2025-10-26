package rocks.gorjan.gokixp.quickglance

import android.content.Context

/**
 * Data class representing information to display in the Quick Glance widget
 */
data class QuickGlanceData(
    val title: String,
    val subtitle: String,
    val iconResourceId: Int,
    val priority: Int = 0, // Higher priority takes precedence
    val sourceId: String, // Identifier for the data source (e.g., "calendar", "weather", "news")
    val tapAction: TapAction? = null // Optional action to perform when tapped
)

/**
 * Sealed class representing different tap actions
 */
sealed class TapAction {
    data class OpenApp(val packageName: String, val fallbackAction: (() -> Unit)? = null) : TapAction()
    data class OpenIntent(val intent: android.content.Intent) : TapAction()
    data class CustomAction(val action: () -> Unit) : TapAction()
}

/**
 * Interface for components that can provide data to the Quick Glance widget
 */
interface QuickGlanceDataProvider {
    /**
     * Get the current data from this provider
     * @return QuickGlanceData if available, null if no data to show
     */
    suspend fun getCurrentData(): QuickGlanceData?
    
    /**
     * Start providing data updates
     */
    fun startUpdates(callback: (QuickGlanceData?) -> Unit)
    
    /**
     * Stop providing data updates
     */
    fun stopUpdates()
    
    /**
     * Get the provider's identifier
     */
    fun getProviderId(): String
}

/**
 * Manager class that coordinates multiple data providers for the Quick Glance widget
 */
class QuickGlanceDataManager(private val context: Context) {
    val providers = mutableListOf<QuickGlanceDataProvider>()
    private var updateCallback: ((QuickGlanceData?) -> Unit)? = null
    private val currentData = mutableMapOf<String, QuickGlanceData>()
    
    fun addProvider(provider: QuickGlanceDataProvider) {
        providers.add(provider)
        // Start updates if we have a callback
        updateCallback?.let { callback ->
            provider.startUpdates { data ->
                handleProviderUpdate(provider.getProviderId(), data)
            }
        }
    }
    
    fun removeProvider(providerId: String) {
        providers.removeAll { provider ->
            if (provider.getProviderId() == providerId) {
                provider.stopUpdates()
                currentData.remove(providerId)
                true
            } else false
        }
        updateWidget()
    }
    
    fun startUpdates(callback: (QuickGlanceData?) -> Unit) {
        updateCallback = callback
        providers.forEach { provider ->
            provider.startUpdates { data ->
                handleProviderUpdate(provider.getProviderId(), data)
            }
        }
    }
    
    fun stopUpdates() {
        providers.forEach { it.stopUpdates() }
        updateCallback = null
        currentData.clear()
    }
    
    private fun handleProviderUpdate(providerId: String, data: QuickGlanceData?) {
        if (data != null) {
            currentData[providerId] = data
        } else {
            currentData.remove(providerId)
        }
        updateWidget()
    }
    
    private fun updateWidget() {
        // Get highest priority data
        val bestData = currentData.values.maxByOrNull { it.priority }
        updateCallback?.invoke(bestData)
    }
    
    suspend fun forceRefresh() {
        // Force all providers to refresh their data
        providers.forEach { provider ->
            val data = provider.getCurrentData()
            handleProviderUpdate(provider.getProviderId(), data)
        }
    }
}