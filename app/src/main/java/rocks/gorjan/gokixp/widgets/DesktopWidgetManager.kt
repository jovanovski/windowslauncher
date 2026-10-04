package rocks.gorjan.gokixp.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.MainActivity

/** Where a widget sits and how big it is, in desktop pixels. */
data class SavedDesktopWidget(
    val appWidgetId: Int,
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int,
)

/**
 * Android app widgets on the desktop: hosts them, adds and removes them, and remembers where
 * each one was left.
 *
 * Adding one goes the way Android asks a launcher to: allocate an id, bind it to the chosen
 * provider (asking the user the first time, through the system's own consent screen), run the
 * widget's configuration screen if it has one, and only then put it on the desktop. Backing
 * out of any step gives the id back.
 *
 * Must be created in onCreate - it registers for an activity result.
 */
class DesktopWidgetManager(
    private val activity: ComponentActivity,
    private val container: RelativeLayout,
) {
    /** A widget was long-pressed; screen coordinates, for the context menu. */
    var onWidgetLongPress: ((DesktopWidgetView, Float, Float) -> Unit)? = null

    private val appWidgetManager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity.applicationContext, HOST_ID)
    private val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val density = activity.resources.displayMetrics.density

    private val widgetViews = mutableListOf<DesktopWidgetView>()

    /** The Settings "Widget size" slider: how big widget content is drawn, 1 = as the app made it. */
    var widgetScale: Float = readScale(prefs)
        private set

    fun setWidgetScale(scale: Float) {
        widgetScale = scale
        prefs.edit { putFloat(KEY_WIDGET_SCALE, scale) }
        widgetViews.forEach { it.contentScale = scale }
    }

    /**
     * The activity, but with a plain LayoutInflater. RemoteViews clones the host context's
     * inflater, factory and all, and AppCompat's factory swaps ImageView/TextView for
     * AppCompatImageView/MaterialTextView - whose setters aren't @RemotableViewMethod, so the
     * provider's updates throw "can't use method with RemoteViews".
     */
    private val widgetContext: Context = object : ContextWrapper(activity) {
        private val inflater by lazy {
            LayoutInflater.from(activity.applicationContext).cloneInContext(this)
        }

        override fun getSystemService(name: String): Any? =
            if (name == Context.LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
    }

    /** A widget part-way through being added: bound or being configured, not on the desktop yet. */
    private data class PendingAdd(val appWidgetId: Int, val info: AppWidgetProviderInfo, val centerX: Float, val centerY: Float)
    private var pending: PendingAdd? = null

    private val bindLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val add = pending ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            configureOrPlace(add)
        } else {
            Log.d(TAG, "User declined binding widget ${add.appWidgetId}")
            abandon(add)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------

    fun startListening() {
        try {
            host.startListening()
        } catch (e: Exception) {
            // Seen on some OEM builds when the widget service is restarting; the next onStart retries
            Log.w(TAG, "AppWidgetHost.startListening failed", e)
        }
    }

    fun stopListening() {
        try {
            host.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "AppWidgetHost.stopListening failed", e)
        }
    }

    /** Puts back every widget that was on the desktop. Call once, from onCreate. */
    fun restoreWidgets() {
        val saved = loadSaved()
        val kept = mutableListOf<SavedDesktopWidget>()
        for (entry in saved) {
            val info = appWidgetManager.getAppWidgetInfo(entry.appWidgetId)
            if (info == null) {
                // Its app was uninstalled, or the id didn't survive a restore from backup
                Log.d(TAG, "Dropping widget ${entry.appWidgetId}: no longer bound")
                host.deleteAppWidgetId(entry.appWidgetId)
                continue
            }
            addWidgetView(entry.appWidgetId, info, entry.x, entry.y, entry.width, entry.height)
            kept.add(entry)
        }
        if (kept.size != saved.size) save(kept)

        // Ids we hold that nothing on the desktop uses - an add that never finished because the
        // launcher was killed half-way. Each one keeps its provider running for nothing.
        val inUse = kept.map { it.appWidgetId }.toSet()
        host.appWidgetIds.filter { it !in inUse }.forEach {
            Log.d(TAG, "Releasing orphaned widget id $it")
            host.deleteAppWidgetId(it)
        }

        // Rotation and the keyboard change the desktop's size: keep every widget on it
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) keepWidgetsOnDesktop()
        }
    }

    // ---- Picking -----------------------------------------------------------------------

    /** Every widget the user can add, including ones from a work profile, sorted by app. */
    fun availableProviders(): List<AppWidgetProviderInfo> {
        val userManager = activity.getSystemService(Context.USER_SERVICE) as UserManager
        val providers = try {
            userManager.userProfiles.flatMap { appWidgetManager.getInstalledProvidersForProfile(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list widgets per profile, falling back", e)
            appWidgetManager.installedProviders
        }
        val pm = activity.packageManager
        return providers
            // Widgets that are only meant for the lock screen or keyguard can't go on a desktop
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .sortedWith(
                compareBy<AppWidgetProviderInfo>(
                    { appLabel(it).lowercase() },
                    { it.loadLabel(pm).orEmpty().lowercase() },
                )
            )
    }

    fun appLabel(info: AppWidgetProviderInfo): String {
        return try {
            val pm = activity.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        } catch (e: Exception) {
            info.provider.packageName
        }
    }

    /**
     * Starts adding [info] to the desktop, centred on ([centerX], [centerY]) in desktop
     * coordinates. May leave the launcher for the system's consent screen and the widget's
     * own configuration screen; the widget appears once both are done.
     */
    fun addWidget(info: AppWidgetProviderInfo, centerX: Float, centerY: Float) {
        // Anything left from an add that was interrupted
        pending?.let { abandon(it) }

        val appWidgetId = host.allocateAppWidgetId()
        val add = PendingAdd(appWidgetId, info, centerX, centerY)
        pending = add

        val options = sizeOptions(info.minWidth, info.minHeight)
        val bound = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, options)
        } catch (e: Exception) {
            Log.w(TAG, "bindAppWidgetIdIfAllowed failed", e)
            false
        }
        if (bound) {
            configureOrPlace(add)
            return
        }

        // Not allowed yet: Android asks the user, once per provider (or "always" for this launcher)
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
        }
        try {
            bindLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity to grant widget binding", e)
            abandon(add)
        }
    }

    private fun configureOrPlace(add: PendingAdd) {
        if (needsConfiguration(add.info)) {
            try {
                host.startAppWidgetConfigureActivityForResult(
                    activity, add.appWidgetId, 0, REQUEST_CONFIGURE_NEW, null
                )
                return
            } catch (e: Exception) {
                // A configure screen that won't open shouldn't cost the user their widget
                Log.w(TAG, "Could not open configuration for widget ${add.appWidgetId}, placing as-is", e)
            }
        }
        place(add)
    }

    private fun needsConfiguration(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        ) {
            // The app says its defaults are fine; it can still be configured from the menu
            return false
        }
        return true
    }

    /** Forwarded from the activity's onActivityResult. Returns true if it was one of ours. */
    fun handleActivityResult(requestCode: Int, resultCode: Int): Boolean {
        return when (requestCode) {
            REQUEST_CONFIGURE_NEW -> {
                val add = pending ?: return true
                if (resultCode == Activity.RESULT_OK) place(add) else abandon(add)
                true
            }
            REQUEST_RECONFIGURE -> true // Nothing to do: the app updates its widget itself
            else -> false
        }
    }

    private fun place(add: PendingAdd) {
        pending = null
        val info = appWidgetManager.getAppWidgetInfo(add.appWidgetId) ?: add.info

        // The app's own minimum is the size it was designed around; never wider than the desktop
        val maxW = (container.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels)
        val maxH = (container.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels)
        val floor = (64 * density).toInt()
        val width = (info.minWidth * widgetScale).toInt().coerceAtLeast(floor).coerceAtMost(maxW)
        val height = (info.minHeight * widgetScale).toInt().coerceAtLeast(floor).coerceAtMost(maxH)
        val x = (add.centerX - width / 2f).coerceIn(0f, (maxW - width).toFloat().coerceAtLeast(0f))
        val y = (add.centerY - height / 2f).coerceIn(0f, (maxH - height).toFloat().coerceAtLeast(0f))

        val view = addWidgetView(add.appWidgetId, info, x, y, width, height)
        saveAll()
        // Straight into Move / Resize: it was dropped where the menu was opened, which is
        // rarely exactly where it should live
        setEditing(view)
        Log.d(TAG, "Placed widget ${add.appWidgetId} (${info.provider.flattenToShortString()}) at $x,$y ${width}x$height")
    }

    private fun abandon(add: PendingAdd) {
        if (pending == add) pending = null
        host.deleteAppWidgetId(add.appWidgetId)
    }

    // ---- Views -------------------------------------------------------------------------

    private fun addWidgetView(
        appWidgetId: Int,
        info: AppWidgetProviderInfo,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
    ): DesktopWidgetView {
        // Created with the activity, not the application, so the widget inflates with a real theme
        val hostView = host.createView(widgetContext, appWidgetId, info)
        val view = DesktopWidgetView(activity, appWidgetId, hostView, info).apply {
            onLongPress = { v, sx, sy -> onWidgetLongPress?.invoke(v, sx, sy) }
            onGeometryChanged = { saveAll() }
            contentScale = widgetScale
        }
        // Above the icons, level with Quick Glance, below windows and menus
        view.elevation = 5f * density
        view.x = x
        view.y = y
        container.addView(view, RelativeLayout.LayoutParams(width, height))
        view.commitGeometry()
        // A desktop smaller than the one it was saved on (saved in the other orientation)
        view.fitInto(container.width, container.height)
        view.reportSizeToProvider(view.layoutParams.width, view.layoutParams.height)
        widgetViews.add(view)
        return view
    }

    fun setEditing(view: DesktopWidgetView?) {
        widgetViews.forEach { it.setEditMode(it === view) }
    }

    fun exitEditMode() {
        widgetViews.forEach { it.setEditMode(false) }
    }

    fun isEditing(): Boolean = widgetViews.any { it.isEditMode }

    fun removeWidget(view: DesktopWidgetView) {
        container.removeView(view)
        widgetViews.remove(view)
        host.deleteAppWidgetId(view.appWidgetId)
        saveAll()
    }

    fun canReconfigure(view: DesktopWidgetView): Boolean {
        val info = view.providerInfo
        return info.configure != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun reconfigure(view: DesktopWidgetView) {
        try {
            host.startAppWidgetConfigureActivityForResult(activity, view.appWidgetId, 0, REQUEST_RECONFIGURE, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open configuration for widget ${view.appWidgetId}", e)
        }
    }

    private fun keepWidgetsOnDesktop() {
        // Not saved: turning the phone back puts everything where the user left it
        widgetViews.forEach { it.fitInto(container.width, container.height) }
    }

    // ---- Persistence -------------------------------------------------------------------

    private fun sizeOptions(widthPx: Int, heightPx: Int): Bundle {
        val wDp = (widthPx / density).toInt()
        val hDp = (heightPx / density).toInt()
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
    }

    private fun loadSaved(): List<SavedDesktopWidget> {
        val json = try {
            prefs.getString(KEY_DESKTOP_WIDGETS, null)
        } catch (e: ClassCastException) {
            null
        } ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedDesktopWidget>>() {}.type
            gson.fromJson<List<SavedDesktopWidget>>(json, type).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read saved widgets", e)
            emptyList()
        }
    }

    private fun saveAll() {
        save(widgetViews.map {
            SavedDesktopWidget(it.appWidgetId, it.savedX, it.savedY, it.savedWidth, it.savedHeight)
        })
    }

    private fun save(widgets: List<SavedDesktopWidget>) {
        prefs.edit { putString(KEY_DESKTOP_WIDGETS, gson.toJson(widgets)) }
    }

    companion object {
        private const val TAG = "DesktopWidgetManager"

        /** Identifies this launcher's widget host to the system; must never change. */
        private const val HOST_ID = 0x5749

        const val KEY_DESKTOP_WIDGETS = "desktop_widgets"
        const val KEY_WIDGET_SCALE = "desktop_widget_scale"

        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f

        private fun readScale(prefs: android.content.SharedPreferences): Float = try {
            prefs.getFloat(KEY_WIDGET_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        } catch (e: ClassCastException) {
            1f
        }

        private const val REQUEST_CONFIGURE_NEW = 0x5701
        private const val REQUEST_RECONFIGURE = 0x5702
    }
}
