package rocks.gorjan.gokixp

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.SoundPool
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.VideoView
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isNotEmpty
import kotlin.math.abs
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.SpannableString
import android.util.LruCache
import android.content.ComponentCallbacks2
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import java.io.InputStream
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import rocks.gorjan.gokixp.agent.Agent
import rocks.gorjan.gokixp.agent.AgentView
import rocks.gorjan.gokixp.agent.TTSService
import rocks.gorjan.gokixp.apps.dialer.DialerApp
import rocks.gorjan.gokixp.apps.iexplore.InternetExplorerApp
import rocks.gorjan.gokixp.apps.minesweeper.MinesweeperGame
import rocks.gorjan.gokixp.apps.msn.MsnApp
import rocks.gorjan.gokixp.apps.notepad.NotepadApp
import rocks.gorjan.gokixp.apps.regedit.RegistryEditorApp
import rocks.gorjan.gokixp.apps.regedit.GoogleDriveHelper
import rocks.gorjan.gokixp.apps.solitare.SolitareGame
import rocks.gorjan.gokixp.quickglance.QuickGlanceWidget
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rocks.gorjan.gokixp.theme.*
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.view.isEmpty
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), AppChangeListener {

    val themeManager by lazy { ThemeManager(this) }
    private val fontManager by lazy { FontManager(this) }
    val drawableManager by lazy { DrawableManager(this) }
    private val themeAwareComponents = mutableListOf<ThemeAware>()

    private lateinit var binding: ActivityMainBinding
    private lateinit var dateDay: TextView
    private lateinit var dateOrdinal: TextView
    private lateinit var clockTime: TextView
    private lateinit var handler: Handler
    private lateinit var clockRunnable: Runnable
    private lateinit var startMenu: RelativeLayout
    private lateinit var appsRecyclerView: RecyclerView
    private lateinit var searchBox: EditText
    private var isKeyboardOpen = false
    private var originalStartMenuLayoutParams: RelativeLayout.LayoutParams? = null
    private var appsAdapter: AppsAdapter? = null
    private var commandsAdapter: CommandsAdapter? = null
    private var cachedAppList: List<AppInfo>? = null
    private var isAppListLoading = false
    private var isCommandsListLoading = false
    private lateinit var contextMenu: ContextMenuView
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var desktopContainer: RelativeLayout
    private lateinit var recycleBin: RecycleBinView
    private lateinit var agentView: AgentView
    private lateinit var speechBubbleView: SpeechBubbleView
    private lateinit var quickGlanceWidget: QuickGlanceWidget
    private lateinit var cursorEffect: ImageView
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null
    private lateinit var notificationBubble: RelativeLayout
    private lateinit var notificationTitle: TextView
    private lateinit var notificationText: TextView
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationHideRunnable: Runnable? = null
    private var notificationTapCallback: (() -> Unit)? = null
    var isStartMenuVisible = false

    // Update checker
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private var updateCheckRunnable: Runnable? = null
    private val UPDATE_CHECK_INTERVAL = 3600000L // 1 hour in milliseconds
    private lateinit var updateIcon: LinearLayout
    private var updateDownloadLink: String? = null
    
    // App detection
    private var lastKnownAppCount = 0
    private var appCheckRunnable: Runnable? = null
    private val APP_CHECK_INTERVAL = 30000L // 30 seconds
    private var isContextMenuVisible = false
    private var isProgramsMenuExpanded = false
    private var isStartMenuShowingApps = false // Track Vista start menu state
    private var lastAppliedTheme: String? = null
    private var selectedIcon: DesktopIconView? = null
    private val desktopIcons = mutableListOf<DesktopIcon>()
    private val desktopIconViews = mutableListOf<DesktopIconView>()
    private lateinit var floatingWindowManager: FloatingWindowManager
    private var iconInMoveMode: DesktopIconView? = null
    private val customIconMappings = mutableMapOf<String, String>() // packageName -> customIconPath
    private val customNameMappings = mutableMapOf<String, String>() // packageName -> customName

    // Foldable device state
    private var isFoldableUnfolded = false

    // Icon bitmap cache - uses 1/8th of available memory
    private val iconBitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt() // KB
        val cacheSize = maxMemory / 8 // Use 1/8th of available memory
        Log.d("MainActivity", "Initializing icon cache with size: ${cacheSize}KB (max memory: ${maxMemory}KB)")

        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024 // Size in KB
            }

        }
    }

    // System apps configuration
    private val systemAppActions = mutableMapOf<String, (AppInfo?) -> Unit>() // packageName -> action function with optional AppInfo

    // Permission request codes
    private val CALENDAR_PERMISSION_REQUEST_CODE = 1003
    private val AUDIO_PERMISSION_REQUEST_CODE = 200
    private val VIDEO_PERMISSION_REQUEST_CODE = 201

    // Image picker launcher for wallpaper selection
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            handleSelectedImage(selectedUri)
        }
    }

    // Notepad image pickers
    private var currentNotepadApp: rocks.gorjan.gokixp.apps.notepad.NotepadApp? = null

    private val notepadGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        currentNotepadApp?.onImageSelected(uri)
    }

    private val notepadCameraPickerLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            // Camera captured successfully, URI is already set
            currentNotepadApp?.onImageSelected(pendingCameraUri)
        }
        pendingCameraUri = null
    }

    private var pendingCameraUri: Uri? = null

    // Preferences export/import launchers
    private var pendingExportJson: String? = null
    private var pendingImportCallback: (() -> Unit)? = null
    private lateinit var googleDriveHelper: GoogleDriveHelper

    private val exportPrefsLauncher = registerForActivityResult(CreateDocument("todo/todo")) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val jsonString = pendingExportJson
                if (jsonString != null) {
                    contentResolver.openOutputStream(selectedUri)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray())
                    }
                    showNotification("Registry Editor", "Settings exported successfully")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error writing export file", e)
                showNotification("Registry Editor", "Export failed: ${e.message}")
            } finally {
                pendingExportJson = null
            }
        }
    }

    private val importPrefsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val jsonString = contentResolver.openInputStream(selectedUri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }

                if (jsonString != null) {
                    val gson = Gson()
                    val importedPrefs = gson.fromJson(jsonString, Map::class.java) as Map<String, *>

                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    prefs.edit {
                        // Clear all existing preferences
                        clear()

                        // Import all preferences
                        importedPrefs.forEach { (key, value) ->
                            when (value) {
                                is String -> putString(key, value)
                                is Boolean -> putBoolean(key, value)
                                is Double -> {
                                    // Gson deserializes all numbers as Double
                                    // Check if it's a whole number to store as Int, otherwise as Float
                                    if (value == value.toLong().toDouble()) {
                                        putInt(key, value.toInt())
                                    } else {
                                        putFloat(key, value.toFloat())
                                    }
                                }
                                is Int -> putInt(key, value)
                                is Long -> putInt(key, value.toInt())
                                is Float -> putFloat(key, value)
                                else -> Log.w(
                                    "MainActivity",
                                    "Unknown preference type for key $key: ${value?.javaClass?.name}"
                                )
                            }
                        }

                    }
                    recreate()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error importing preferences", e)
                showNotification("Import Failed", "Import failed: ${e.message}")
            }
        }
    }

    // Google Sign-In launcher for Google Drive
    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d("MainActivity", "Google Sign-In result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                Log.d("MainActivity", "Google Sign-In successful: ${account.email}")
                googleDriveHelper.handleSignInResult(account)
                showNotification("Google Drive", "Signed in successfully")

                // Execute pending action
                Log.d("MainActivity", "Executing pending callback")
                pendingImportCallback?.invoke()
                pendingImportCallback = null
            } catch (e: ApiException) {
                Log.e("MainActivity", "Google Sign-In failed: code=${e.statusCode}", e)
                showNotification("Google Drive", "Sign-in failed: ${e.message}")
                pendingImportCallback = null
            } catch (e: Exception) {
                Log.e("MainActivity", "Unexpected error during sign-in", e)
                showNotification("Google Drive", "Sign-in error: ${e.message}")
                pendingImportCallback = null
            }
        } else {
            Log.w("MainActivity", "Google Sign-In cancelled or failed: resultCode=${result.resultCode}")

            // If resultCode is RESULT_CANCELED (0), it means the sign-in was cancelled
            // This often happens when OAuth credentials are not properly configured
            if (result.resultCode == RESULT_CANCELED) {
                showNotification("Google Drive", "Sign-in cancelled. Note: Google Drive API requires OAuth configuration.")
            } else {
                showNotification("Google Drive", "Sign-in failed (code: ${result.resultCode})")
            }
            pendingImportCallback = null
        }
    }

    // Sound system
    private lateinit var soundPool: SoundPool
    private val soundIds = mutableMapOf<Int, Int>() // Maps resource ID to sound ID
    private var chargingReceiver: BroadcastReceiver? = null

    // Easter egg sounds (sorted by filename)
    private val eggSounds = listOf(
        R.raw.developers1,
        R.raw.developers2,
        R.raw.ilovethiscompany
    )
    private var currentEggSoundIndex = 0
    private var profilePictureView: ImageView? = null
    private var profileNameView: TextView? = null
    private lateinit var screensaverManager: ScreensaverManager

    // Auto-sync for Google Drive
    private val autoSyncHandler = Handler(Looper.getMainLooper())
    private var autoSyncRunnable: Runnable? = null
    private val AUTO_SYNC_INTERVAL = 3600000L // 1 hour in milliseconds
    private var registryEditorAppInstance: RegistryEditorApp? = null

    // Permission error update functions for wallpaper dialog
    private var updateEmailPermissionError: (() -> Unit)? = null
    private var updateNotificationDotsPermissionError: (() -> Unit)? = null

    private fun getMediaDuration(resourceId: Int): Long {
        return try {
            val audioContext = createAttributionContext("system")
            val mediaPlayer = MediaPlayer.create(audioContext, resourceId)
            val duration = mediaPlayer?.duration?.toLong() ?: 3000L
            mediaPlayer?.release()
            // Add 500ms buffer to ensure we don't cut off early
            val bufferedDuration = duration
            Log.d("MainActivity", "Media duration: ${duration}ms, buffered: ${bufferedDuration}ms")
            bufferedDuration
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not get media duration for resource $resourceId", e)
            3000L // Default fallback
        }
    }
    
    companion object {
        const val PREFS_NAME = "taskbar_widget_prefs"  // Public constant for shared preferences name
        private const val KEY_DESKTOP_ICONS = "desktop_icons"
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_SOUND_MUTED = "sound_muted"
        private const val KEY_PLAY_EMAIL_SOUND = "play_email_sound"
        private const val KEY_SHOW_NOTIFICATION_DOTS = "show_notification_dots"
        private const val KEY_CLOCK_24_HOUR = "clock_24_hour"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_CUSTOM_ICONS_XP = "custom_icons_xp"
        private const val KEY_CUSTOM_ICONS_98 = "custom_icons_98"
        private const val KEY_CUSTOM_ICONS_VISTA = "custom_icons_vista"
        private const val KEY_ROVER_VISIBLE = "rover_visible"
        private const val KEY_RECYCLE_BIN_VISIBLE = "recycle_bin_visible"
        private const val KEY_SHORTCUT_ARROW_VISIBLE = "shortcut_arrow_visible"
        private const val KEY_WALLPAPER_XP_PATH = "wallpaper_xp_path"
        private const val KEY_WALLPAPER_XP_URI = "wallpaper_xp_uri"
        private const val KEY_WALLPAPER_CLASSIC_PATH = "wallpaper_classic_path"
        private const val KEY_WALLPAPER_CLASSIC_URI = "wallpaper_classic_uri"
        private const val KEY_WALLPAPER_VISTA_PATH = "wallpaper_vista_path"
        private const val KEY_WALLPAPER_VISTA_URI = "wallpaper_vista_uri"
        private const val KEY_CURSOR_VISIBLE = "cursor_visible"
        private const val KEY_ICON_TEXT_BACKGROUND_VISIBLE = "icon_text_background_visible"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_NAMES = "custom_names"
        private const val KEY_WEATHER_DATA = "weather_data"
        private const val KEY_WEATHER_TIMESTAMP = "weather_timestamp"
        private const val KEY_WEATHER_UNIT = "weather_unit"
        private const val KEY_QUICK_GLANCE_VISIBLE = "quick_glance_visible"
        private const val KEY_AGENT_X = "agent_x"
        private const val KEY_AGENT_Y = "agent_y"
        private const val KEY_CURRENT_AGENT = "current_agent_id"
        private const val KEY_WIDGET_X = "widget_x"
        private const val KEY_WIDGET_Y = "widget_y"
        private const val KEY_SHOW_CALENDAR_EVENTS = "show_calendar_events"
        private const val KEY_IE_HOMEPAGE = "ie_homepage"
        private const val KEY_SWIPE_RIGHT_APP = "swipe_right_app"
        private const val KEY_WEATHER_APP = "weather_app"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_START_BANNER_98 = "start_banner_98"
        private const val KEY_GESTURE_BAR_VISIBLE = "gesture_bar_visible"
        private const val KEY_TASKBAR_HEIGHT_OFFSET = "taskbar_height_offset"
        private const val KEY_SHOWN_WELCOME_FOR_VERSION = "shown_welcome_for_version"
        private const val KEY_SYSTEM_TRAY_VISIBLE = "system_tray_visible"
        private const val KEY_SELECTED_SCREENSAVER = "selected_screensaver"
        private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
        private const val KEY_LAST_GOOGLE_DRIVE_SYNC = "last_google_drive_sync"
        private const val KEY_WINDOW_STATES = "window_states"

        // Screensaver types
        private const val SCREENSAVER_NONE = 0
        private const val SCREENSAVER_3D_PIPES = 1
        private const val SCREENSAVER_UNDERWATER = 2
        private const val DEFAULT_SCREENSAVER_TIMEOUT = 30 // Default 30 seconds
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

        // System app package name prefix
        private const val SYSTEM_APP_PREFIX = "system."

        private var instance: MainActivity? = null

        fun getInstance(): MainActivity? = instance

        // Check if a package name is a system app
        fun isSystemApp(packageName: String): Boolean {
            return packageName.startsWith(SYSTEM_APP_PREFIX)
        }
        
        // Get user name from SharedPreferences (accessible to other parts of the app)
        fun getUserName(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_USER_NAME, "User") ?: "User"
        }

        // Safe getter for integer preferences that handles type mismatches from corrupted imports
        private fun android.content.SharedPreferences.safeGetInt(key: String, defaultValue: Int): Int {
            return try {
                getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                // Handle corrupted data from incorrect import
                Log.w("MainActivity", "Corrupted int preference for key: $key, resetting to default", e)
                edit().remove(key).apply()
                defaultValue
            }
        }

        // Grid constants (deprecated - will be calculated dynamically)
        @Deprecated("Use calculateGridRows() instead")
        private const val GRID_ROWS = 8
        @Deprecated("Use calculateGridColumns() instead")
        private const val GRID_COLUMNS = 5

        // Orientation enum
        enum class ScreenOrientation {
            PORTRAIT,
            LANDSCAPE
        }

        // Start banner cycling order: 98 -> me -> 2000 -> 95 -> back to 98
        private val START_BANNER_CYCLE = arrayOf(
            "start_banner_98",
            "start_banner_me",
            "start_banner_2000",
            "start_banner_95"
        )

        // Map banner names to resource IDs
        private val BANNER_RESOURCE_MAP = mapOf(
            "start_banner_98" to R.drawable.start_banner_98,
            "start_banner_me" to R.drawable.start_banner_me,
            "start_banner_2000" to R.drawable.start_banner_2000,
            "start_banner_95" to R.drawable.start_banner_95
        )
    }

    /**
     * Gets the drawable resource ID for a banner name.
     * @return Resource ID or 0 if not found
     */
    private fun getBannerResourceId(bannerName: String): Int {
        return BANNER_RESOURCE_MAP[bannerName] ?: 0
    }


    /**
     * Notifies all registered components that the theme has changed.
     */
    private fun notifyThemeChanged(theme: AppTheme) {
        themeAwareComponents.forEach { it.onThemeChanged(theme) }
    }

    // ========== Theme-Specific Resource Helper Methods ==========

    /**
     * Gets the wallpaper storage keys for the current theme.
     * Returns Pair(path_key, uri_key)
     */
    private fun getCurrentThemeWallpaperKeysTypeSafe(): Pair<String, String> {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> Pair(KEY_WALLPAPER_CLASSIC_PATH, KEY_WALLPAPER_CLASSIC_URI)
            AppTheme.WindowsXP -> Pair(KEY_WALLPAPER_XP_PATH, KEY_WALLPAPER_XP_URI)
            AppTheme.WindowsVista -> Pair(KEY_WALLPAPER_VISTA_PATH, KEY_WALLPAPER_VISTA_URI)
        }
    }

    /**
     * Gets the default wallpaper path for the current theme.
     */
    private fun getDefaultWallpaperForCurrentTheme(): String {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> "wallpapers/Windows ME (m).jpg"
            AppTheme.WindowsXP -> "wallpapers/Bliss (m).jpg"
            AppTheme.WindowsVista -> "wallpapers/Windows Vista (m).jpg" // Can be changed to Vista default later
        }
    }

    /**
     * Gets the custom icon storage key for the current theme.
     */
    private fun getCustomIconKeyForCurrentTheme(): String {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> "custom_icons_98"
            AppTheme.WindowsXP -> "custom_icons"
            AppTheme.WindowsVista -> "custom_icons_vista"
        }
    }

    /**
     * Gets the button background drawable resource for the current theme.
     */
    private fun getButtonBackgroundForCurrentTheme(): Int {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> R.drawable.win98_start_menu_border
            AppTheme.WindowsXP -> R.drawable.button_xp_background
            AppTheme.WindowsVista -> R.drawable.button_xp_background // Can be changed to Vista button later
        }
    }

    /**
     * Gets the display properties icon for the current theme.
     */
    private fun getDisplayPropertiesIconForCurrentTheme(): Int {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> R.drawable.display_98
            AppTheme.WindowsXP -> R.drawable.display_xp
            AppTheme.WindowsVista -> R.drawable.display_xp // Can be changed to Vista icon later
        }
    }

    /**
     * Gets the volume icon based on theme and mute state.
     */
    private fun getVolumeIconForCurrentTheme(isMuted: Boolean): Int {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> if (isMuted) R.drawable.mute_98 else R.drawable.sound_98
            AppTheme.WindowsXP -> if (isMuted) R.drawable.mute else R.drawable.sound
            AppTheme.WindowsVista -> if (isMuted) R.drawable.mute_vista else R.drawable.sound_vista
        }
    }

    /**
     * Returns true if Programs menu should be shown (Classic theme only).
     */
    private fun shouldShowProgramsMenu(): Boolean {
        return themeManager.getSelectedTheme() is AppTheme.WindowsClassic
    }

    /**
     * Returns true if flavour spinner should be visible (Classic theme only).
     */
    private fun shouldShowFlavourSpinner(theme: AppTheme? = null): Boolean {
        var checkTheme = theme
        if(checkTheme == null){
            checkTheme = themeManager.getSelectedTheme()
        }
        return checkTheme is AppTheme.WindowsClassic
    }

    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set theme before calling super.onCreate()
        // Phase 2: Use ThemeManager for theme initialization
        val theme = themeManager.getSelectedTheme()
        setTheme(themeManager.getThemeStyleRes(theme))

        super.onCreate(savedInstanceState)
        instance = this

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get SharedPreferences for onCreate initialization
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize screensaver manager
        screensaverManager = ScreensaverManager(this, binding.root)

        // Load screensaver selection from SharedPreferences (default to 3D Pipes for backward compatibility)
        val selectedScreensaver = prefs.safeGetInt(KEY_SELECTED_SCREENSAVER, SCREENSAVER_3D_PIPES)
        screensaverManager.setSelectedScreensaver(selectedScreensaver)

        // Load screensaver timeout from SharedPreferences (default to 30 seconds)
        val screensaverTimeout = prefs.safeGetInt(KEY_SCREENSAVER_TIMEOUT, DEFAULT_SCREENSAVER_TIMEOUT)
        screensaverManager.setInactivityTimeout(screensaverTimeout)

        // Initialize Google Drive helper
        googleDriveHelper = GoogleDriveHelper(this)

        // Check if already signed in to Google Drive
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null) {
            googleDriveHelper.handleSignInResult(lastAccount)
        }

        // Start auto-sync if enabled (should run regardless of Registry Editor being open)
        val autoSyncEnabled = prefs.getBoolean("auto_sync_google_drive", false)
        if (autoSyncEnabled) {
            startAutoSync()
        }

        // Initialize floating window manager with container
        val floatingWindowsContainer = findViewById<android.widget.FrameLayout>(R.id.floating_windows_container)
        floatingWindowManager = FloatingWindowManager(this, floatingWindowsContainer)

        // Setup foldable device detection
        setupFoldableDeviceDetection()

        // Enable edge-to-edge display after content view is set
        enableEdgeToEdge()

        // Initialize sound system
        initializeSoundPool()

        // Setup charging detection
        setupChargingDetection()

        // Initialize system apps
        initializeSystemApps()

        // Set up desktop container (separate container for icons with margin)
        desktopContainer = findViewById(R.id.desktop_icons_container)

        // Set up cursor effect
        setupCursorEffect()

        // Set up start menu first
        setupStartMenu()
        
        // Set up keyboard detection for start menu adjustment
        setupKeyboardDetection()
        
        // Set up taskbar interactions
        setupTaskbar()
        
        // Set up Clippy (after handler is initialized)
        setupClippy()
        
        // Set up Quick Glance widget
        setupQuickGlanceWidget()
        
        // Start notification monitoring (after handler is initialized)
        startNotificationMonitoring()
        
        // Set up desktop interactions
        setupDesktopInteractions()
        
        // Set up modern back press handling
        setupBackPressHandling()

        // Migrate custom mappings from old preferences file if needed
        migrateCustomMappingsIfNeeded()

        // Load custom icon mappings first so they're available when loading desktop icons
        loadCustomIconMappings()

        // Load saved desktop icons (now with custom mappings available)
        loadDesktopIcons()

        // Load custom name mappings
        loadCustomNameMappings()
        
        // Load saved wallpaper
        loadSavedWallpaper()

        // Initialize theme after wallpaper and UI setup
        initializeTheme()

        // Check if this is a theme change from SharedPreferences (survives process death)
        val isThemeChangingFromPrefs = prefs.getBoolean("theme_changing", false)

        // Only play startup sound if this is a fresh app launch or a theme change
        if (isThemeChangingFromPrefs) {
            Handler(Looper.getMainLooper()).postDelayed({
                playStartupSound()
                // Clear the theme changing flag from SharedPreferences
                prefs.edit { putBoolean("theme_changing", false) }
            }, 1000)
        }

        // Set up gesture bar toggle after theme initialization
        setupGestureBarToggle()
        
        // Request notification permission on first launch (Android 13+)
        requestNotificationPermissionIfNeeded()
        
        // Set up app install/uninstall listener
        AppInstallReceiver.setListener(this)
        
        // Handle pending app installation/removal from broadcast receiver
        handlePendingPackageAction()

        // Initialize app detection
        initializeAppDetection()

        // Start update checker (checks immediately and then every hour)
        startUpdateChecker()

        refreshDesktopIcons()

        // Show welcome screen if this is the first launch for this version
        Handler(Looper.getMainLooper()).postDelayed({
            showWelcomeScreenIfNeeded()
            refreshDesktopIcons()
        }, 1000) // Delay to ensure UI is fully loaded
    }
    
    private fun setupTaskbar() {
        dateDay = findViewById(R.id.date_day)
        dateOrdinal = findViewById(R.id.date_ordinal)
        clockTime = findViewById(R.id.clock_time)
        updateIcon = findViewById(R.id.update_icon)
        Log.d("MainActivity", "setupTaskbar: updateIcon initialized, current visibility: ${updateIcon.visibility}")
        handler = Handler(Looper.getMainLooper())

        // Set up clock updates
        setupClockUpdates()

        // Set up update icon click listener
        updateIcon.setOnClickListener {
            updateDownloadLink?.let { link ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = link.toUri()
                    startActivity(intent)
                    playClickSound()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error opening update link", e)
                }
            }
        }

        // Set up start button click
        val startButton = findViewById<ImageView>(R.id.start_button)
        startButton.setOnClickListener {
            Log.d("MainActivity", "Start button clicked!")
            playClickSound()
            toggleStartMenu()
        }
        
        // Add long press listener to start button
        startButton.setOnLongClickListener { view ->
            // Calculate position for context menu
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = (location[0] + view.width / 2).toFloat()
            val y = (location[1] + view.height / 2).toFloat()
            
            showStartMenuContextMenu(x, y)
            true
        }
        
        // Set up taskbar empty space click (between start button and system tray)
        // Note: Using post to ensure the view is laid out before accessing it
        handler.post {
            try {
                val taskbarContainer = findViewById<View>(R.id.taskbar_container)
                val taskbarEmptySpace = taskbarContainer.findViewById<View>(R.id.taskbar_empty_space)
                taskbarEmptySpace?.setOnClickListener {
                    Log.d("MainActivity", "Taskbar empty space clicked!")
                    launchWebSearch()
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to set up taskbar empty space click handler: ${e.message}")
            }
        }
        
        // Set up click listeners
        // Date container opens calendar (covers both day number and ordinal)
        val dateContainer = findViewById<LinearLayout>(R.id.date_container)
        dateContainer.setOnClickListener { openCalendarApp() }
        
        // Time display opens clock
        clockTime.setOnClickListener { openClockApp() }
        
        // Shutdown button will be set up after theme layout is loaded in setupStartMenu()

        // Set up volume icon click
        val volumeIconWrapper = findViewById<LinearLayout>(R.id.volume_icon_wrapper)
        volumeIconWrapper?.setOnClickListener {
            toggleSoundMute()
        }
        
        // Initialize volume icon state
        updateVolumeIcon()

        // Set up weather temperature display
        setupWeatherUpdates()

        // Note: setupSystemTrayToggle() is called after theme layouts are loaded
    }

    private fun setupSystemTrayToggle() {
        val systemTrayToggle = findViewById<ImageView>(R.id.system_tray_toggle)
        val systemTrayToggleArea = findViewById<LinearLayout>(R.id.system_tray_toggle_area)

        if (systemTrayToggle == null) {
            Log.e("MainActivity", "System tray toggle button not found!")
            return
        }

        // Bring to front to ensure it's not behind other views
        systemTrayToggle.bringToFront()

        // Check parent hierarchy
        Log.d("MainActivity", "System tray toggle parent: ${systemTrayToggle.parent}")
        Log.d("MainActivity", "System tray toggle parent class: ${systemTrayToggle.parent?.javaClass?.simpleName}")

        // Load saved state and apply it
        val isSystemTrayVisible = isSystemTrayVisible()
        systemTrayToggleArea?.visibility = if (isSystemTrayVisible) View.VISIBLE else View.GONE
        updateSystemTrayToggleIcon(systemTrayToggle, isSystemTrayVisible)

        // Use simple click listener only
        systemTrayToggle.setOnClickListener {
            Log.d("MainActivity", "✅ System tray toggle CLICKED!")
            performSystemTrayToggle(systemTrayToggle, systemTrayToggleArea)
        }

        // Add backup approach using different listener
        systemTrayToggle.setOnLongClickListener {
            Log.d("MainActivity", "System tray toggle LONG CLICKED - treating as click")
            performSystemTrayToggle(systemTrayToggle, systemTrayToggleArea)
            true
        }

        // Post a runnable to ensure layout is ready
        systemTrayToggle.post {
            Log.d("MainActivity", "System tray toggle layout - X: ${systemTrayToggle.x}, Y: ${systemTrayToggle.y}, Width: ${systemTrayToggle.width}, Height: ${systemTrayToggle.height}")
            Log.d("MainActivity", "System tray toggle visibility: ${systemTrayToggle.visibility}")
        }

        Log.d("MainActivity", "System tray toggle initialized. Visibility: ${if (isSystemTrayVisible) "VISIBLE" else "GONE"}")
    }

    private fun performSystemTrayToggle(systemTrayToggle: ImageView, systemTrayToggleArea: LinearLayout?) {
        Log.d("MainActivity", "Performing system tray toggle")

        val currentlyVisible = systemTrayToggleArea?.visibility == View.VISIBLE
        val newVisibility = !currentlyVisible

        // Toggle visibility
        systemTrayToggleArea?.visibility = if (newVisibility) View.VISIBLE else View.GONE

        // Update icon
        updateSystemTrayToggleIcon(systemTrayToggle, newVisibility)

        // Save state
        saveSystemTrayVisibility(newVisibility)

        Log.d("MainActivity", "System tray visibility toggled to: ${if (newVisibility) "VISIBLE" else "GONE"}")
    }

    private fun updateSystemTrayToggleIcon(toggleButton: ImageView?, isVisible: Boolean) {
        // Update icon based on visibility state and theme
        val currentTheme = themeManager.getSelectedTheme()
        val iconRes = when {
            currentTheme is AppTheme.WindowsVista && isVisible -> R.drawable.system_tray_collapse_vista
            currentTheme is AppTheme.WindowsVista && !isVisible -> R.drawable.system_tray_expand_vista
            isVisible -> R.drawable.system_tray_collapse_xp
            else -> R.drawable.system_tray_expand_xp
        }
        toggleButton?.setImageResource(iconRes)
    }

    private fun isSystemTrayVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_SYSTEM_TRAY_VISIBLE, true) // Default to visible
    }

    private fun saveSystemTrayVisibility(isVisible: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_SYSTEM_TRAY_VISIBLE, isVisible)
        }
        Log.d("MainActivity", "System tray visibility saved: $isVisible")
    }

    private fun setupClockUpdates() {
        clockRunnable = object : Runnable {
            override fun run() {
                val currentDate = Date()
                val calendar = Calendar.getInstance()
                calendar.time = currentDate

                val day = calendar.get(Calendar.DAY_OF_MONTH)

                // Get clock format preference (default to 24-hour)
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val is24Hour = prefs.getBoolean(KEY_CLOCK_24_HOUR, true)
                val timeFormatPattern = if (is24Hour) "HH:mm" else "hh:mm a"
                val timeFormat = SimpleDateFormat(timeFormatPattern, Locale.getDefault())
                val time = timeFormat.format(currentDate)

                val ordinalSuffix = getOrdinalSuffix(day)

                // Update separate date and time displays
                dateDay.text = day.toString()
                dateOrdinal.text = ordinalSuffix
                clockTime.text = time

                // Also refresh QuickGlanceWidget default panel to keep date and weather current
                if (::quickGlanceWidget.isInitialized) {
                    quickGlanceWidget.refreshDefaultPanel()
                }

                handler.postDelayed(this, 1000) // Update every second
            }
        }
        handler.post(clockRunnable)
    }
    
    private fun getOrdinalSuffix(day: Int): String {
        return when {
            day in 11..13 -> "th" // Special case for 11th, 12th, 13th
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd" 
            day % 10 == 3 -> "rd"
            else -> "th"
        }
    }
    
    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        soundPool = SoundPool.Builder()
            .setMaxStreams(5) // Maximum concurrent sounds
            .setAudioAttributes(audioAttributes)
            .build()
        
        // Add load completion listener to prevent blocking UI
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status != 0) {
                Log.w("MainActivity", "Sound failed to load with status: $status")
            }
        }
        
        // Create attributed context for audio loading
        val audioContext =
            createAttributionContext("system")

        // Preload all sounds
        soundIds[R.raw.startup] = soundPool.load(audioContext, R.raw.startup, 1)
        soundIds[R.raw.startup_98] = soundPool.load(audioContext, R.raw.startup_98, 1)
        soundIds[R.raw.startup_95] = soundPool.load(audioContext, R.raw.startup_95, 1)
        soundIds[R.raw.startup_2000] = soundPool.load(audioContext, R.raw.startup_2000, 1)
        soundIds[R.raw.startup_vista] = soundPool.load(audioContext, R.raw.startup_vista, 1)
        soundIds[R.raw.shutdown] = soundPool.load(audioContext, R.raw.shutdown, 1)
        soundIds[R.raw.shutdown_98] = soundPool.load(audioContext, R.raw.shutdown_98, 1)
        soundIds[R.raw.shutdown_2000] = soundPool.load(audioContext, R.raw.shutdown_2000, 1)
        soundIds[R.raw.shutdown_vista] = soundPool.load(audioContext, R.raw.shutdown_vista, 1)
        soundIds[R.raw.click] = soundPool.load(audioContext, R.raw.click, 1)
        soundIds[R.raw.click_vista] = soundPool.load(audioContext, R.raw.click_vista, 1)
        soundIds[R.raw.recycle] = soundPool.load(audioContext, R.raw.recycle, 1)
        soundIds[R.raw.ding] = soundPool.load(audioContext, R.raw.ding, 1)
        soundIds[R.raw.ding_vista] = soundPool.load(audioContext, R.raw.ding_vista, 1)
        soundIds[R.raw.bubble] = soundPool.load(audioContext, R.raw.bubble, 1)
        soundIds[R.raw.charge_on] = soundPool.load(audioContext, R.raw.charge_on, 1)
        soundIds[R.raw.charge_on_vista] = soundPool.load(audioContext, R.raw.charge_on_vista, 1)
        soundIds[R.raw.charge_off] = soundPool.load(audioContext, R.raw.charge_off, 1)
        soundIds[R.raw.charge_off_vista] = soundPool.load(audioContext, R.raw.charge_off_vista, 1)
        soundIds[R.raw.num_1] = soundPool.load(audioContext, R.raw.num_1, 1)
        soundIds[R.raw.num_2] = soundPool.load(audioContext, R.raw.num_2, 1)
        soundIds[R.raw.num_3] = soundPool.load(audioContext, R.raw.num_3, 1)
        soundIds[R.raw.num_4] = soundPool.load(audioContext, R.raw.num_4, 1)
        soundIds[R.raw.num_5] = soundPool.load(audioContext, R.raw.num_5, 1)
        soundIds[R.raw.num_6] = soundPool.load(audioContext, R.raw.num_6, 1)
        soundIds[R.raw.num_7] = soundPool.load(audioContext, R.raw.num_7, 1)
        soundIds[R.raw.num_8] = soundPool.load(audioContext, R.raw.num_8, 1)
        soundIds[R.raw.num_9] = soundPool.load(audioContext, R.raw.num_9, 1)
        soundIds[R.raw.num_other] = soundPool.load(audioContext, R.raw.num_other, 1)
        soundIds[R.raw.youve_got_mail] = soundPool.load(audioContext, R.raw.youve_got_mail, 1)

        // Preload egg sounds
        for (resourceId in eggSounds) {
            soundIds[resourceId] = soundPool.load(audioContext, resourceId, 1)
        }
        
        Log.d("MainActivity", "SoundPool initialized with ${soundIds.size} sounds")
    }
    
    private fun playSound(soundResourceId: Int, bypassMute: Boolean = false) {
        // Check mute state unless bypassing (for unmute confirmation)
        if (!bypassMute && isSoundMuted()) {
            Log.d("MainActivity", "Sound resource $soundResourceId not played - sound is muted")
            return
        }

        try {
            val soundId = soundIds[soundResourceId]
            if (soundId != null) {
                Log.d("MainActivity", "Playing sound resource $soundResourceId with soundId $soundId")
                // Play sound asynchronously to avoid blocking UI
                Thread {
                    try {
                        val streamId = soundPool.play(soundId, 1f, 1f, 1, 0, 1.0f)
                        if (streamId == 0) {
                            Log.w("MainActivity", "Failed to play sound resource $soundResourceId (may not be loaded yet)")
                        } else {
                            Log.d("MainActivity", "Sound playing with streamId $streamId")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error in sound playback thread for resource $soundResourceId", e)
                    }
                }.start()
            } else {
                Log.w("MainActivity", "Sound resource $soundResourceId not found in preloaded sounds playing")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing sound resource $soundResourceId", e)
        }
    }
    
    private fun playLongSound(resourceId: Int) {
        if (isSoundMuted()) return
        
        try {
            // Create MediaPlayer with attributed context for longer sounds
            val audioContext =
                createAttributionContext("system")

            Thread {
                try {
                    val mediaPlayer = MediaPlayer.create(audioContext, resourceId)
                    mediaPlayer?.apply {
                        setOnCompletionListener { mp ->
                            mp.release()
                            Log.d("MainActivity", "Long sound completed and MediaPlayer released")
                        }
                        setOnErrorListener { mp, what, extra ->
                            Log.e("MainActivity", "MediaPlayer error: what=$what, extra=$extra")
                            mp.release()
                            true
                        }
                        start()
                        Log.d("MainActivity", "Started playing long sound resource $resourceId")
                    } ?: Log.w("MainActivity", "Failed to create MediaPlayer for resource $resourceId")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error playing long sound $resourceId", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up long sound playback for resource $resourceId", e)
        }
    }

    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorContext =
                createAttributionContext("system")
            val vibratorManager =
                vibratorContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(25, 70))
        }
    }

    private fun setupChargingDetection() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                val currentTheme = themeManager.getSelectedTheme()
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        if(currentTheme == AppTheme.WindowsVista){
                            playSound(R.raw.charge_on_vista)
                        }
                        else{
                            playSound(R.raw.charge_on)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if(currentTheme == AppTheme.WindowsVista){
                            playSound(R.raw.charge_off_vista)
                        }
                        else{
                            playSound(R.raw.charge_off)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(chargingReceiver, filter)
        Log.d("MainActivity", "Charging detection setup complete")
    }

    private fun initializeSystemApps() {
        // Register Internet Explorer
        systemAppActions["system.internet_explorer"] = { appInfo ->
            showInternetExplorerDialog(appInfo = appInfo)
        }

        // Register Registry Editor
        systemAppActions["system.registry_editor"] = { appInfo ->
            showRegistryEditorDialog(appInfo = appInfo)
        }

        // Register Dialer
        systemAppActions["system.dialer"] = { appInfo ->
            showDialerDialog(appInfo = appInfo)
        }

        // Register Notepad
        systemAppActions["system.notepad"] = { appInfo ->
            showNotepadDialog(appInfo = appInfo)
        }

        // Register MSN Messenger
        systemAppActions["system.msn"] = { appInfo ->
            showMsnDialog(appInfo = appInfo)
        }

        // Register Winamp
        systemAppActions["system.winamp"] = { appInfo ->
            showWinampDialog(appInfo = appInfo)
        }

        // Register Windows Media Player
        systemAppActions["system.wmp"] = { appInfo ->
            showWmpDialog(appInfo = appInfo)
        }

        // Register Minesweeper
        systemAppActions["system.minesweeper"] = { appInfo ->
            showMinesweeperDialog(appInfo = appInfo)
        }

        // Register Solitare
        systemAppActions["system.solitare"] = { appInfo ->
            showSolitareDialog(appInfo = appInfo)
        }


        // Register Clock
        systemAppActions["system.clock"] = { appInfo ->
            createAndShowClockDialog()
        }

        Log.d("MainActivity", "System apps initialized: ${systemAppActions.size} apps")
    }

    private fun getSystemAppsList(): List<AppInfo> {
        val systemApps = mutableListOf<AppInfo>()

        // Internet Explorer - scale icon to match app icon size
        val ieDrawable = AppCompatResources.getDrawable(this, themeManager.getIEIcon())
        if (ieDrawable != null) {
            systemApps.add(AppInfo(
                name = "Internet Explorer",
                packageName = "system.internet_explorer",
                icon = createSquareDrawable(ieDrawable),
                minWindowWidthDp = 360
            ))
        }

        // Registry Editor - scale icon to match app icon size
        val regeditDrawable = AppCompatResources.getDrawable(this,themeManager.getRegeditIcon())
        if (regeditDrawable != null) {
            systemApps.add(AppInfo(
                name = "Registry Editor",
                packageName = "system.registry_editor",
                icon = createSquareDrawable(regeditDrawable)
            ))
        }

        // Dialer - scale icon to match app icon size
        val dialerDrawable = AppCompatResources.getDrawable(this,R.drawable.dialer_icon)
        if (dialerDrawable != null) {
            systemApps.add(AppInfo(
                name = "Phone Dialer",
                packageName = "system.dialer",
                icon = createSquareDrawable(dialerDrawable)
            ))
        }

        // Notepad - scale icon to match app icon size
        val notepadDrawable = AppCompatResources.getDrawable(this,themeManager.getNotepadIcon())
        if (notepadDrawable != null) {
            systemApps.add(AppInfo(
                name = "Notepad",
                packageName = "system.notepad",
                icon = createSquareDrawable(notepadDrawable)
            ))
        }

        // MSN Messenger - scale icon to match app icon size
        val msnDrawable = AppCompatResources.getDrawable(this,themeManager.getMsnIcon())
        if (msnDrawable != null) {
            systemApps.add(AppInfo(
                name = "MSN Messenger",
                packageName = "system.msn",
                icon = createSquareDrawable(msnDrawable)
            ))
        }

        // Winamp - scale icon to match app icon size
        val winampDrawable = AppCompatResources.getDrawable(this,themeManager.getWinampIcon())
        if (winampDrawable != null) {
            systemApps.add(AppInfo(
                name = "Winamp",
                packageName = "system.winamp",
                icon = createSquareDrawable(winampDrawable)
            ))
        }

        // Windows Media Player - scale icon to match app icon size
        val wmpDrawable = AppCompatResources.getDrawable(this,themeManager.getWmpIcon())
        if (wmpDrawable != null) {
            systemApps.add(AppInfo(
                name = "Windows Media Player",
                packageName = "system.wmp",
                icon = createSquareDrawable(wmpDrawable)
            ))
        }

        // Minesweeper - scale icon to match app icon size
        val minesweeperDrawable = AppCompatResources.getDrawable(this,themeManager.getMinesweeperIcon())
        if (minesweeperDrawable != null) {
            systemApps.add(AppInfo(
                name = "Minesweeper",
                packageName = "system.minesweeper",
                icon = createSquareDrawable(minesweeperDrawable)
            ))
        }

        // Solitare - scale icon to match app icon size
        val solitareDrawable = AppCompatResources.getDrawable(this,themeManager.getSolitareIcon())
        if (solitareDrawable != null) {
            systemApps.add(AppInfo(
                name = "Solitaire",
                packageName = "system.solitare",
                icon = createSquareDrawable(solitareDrawable)
            ))
        }


        // Clock - scale icon to match app icon size
        val clockDrawable = AppCompatResources.getDrawable(this,themeManager.getClockIcon())
        if (clockDrawable != null) {
            systemApps.add(AppInfo(
                name = "Clock",
                packageName = "system.clock",
                icon = createSquareDrawable(clockDrawable)
            ))
        }

        return systemApps
    }

    fun launchSystemApp(packageName: String) {
        // Find the AppInfo for this system app
        val systemApps = getSystemAppsList()
        val appInfo = systemApps.find { it.packageName == packageName }

        // Check if this app is already open and bring it to front if so
        if (floatingWindowManager.findAndFocusWindow(packageName)) {
            Log.d("MainActivity", "Brought existing window to front: $packageName")
            return
        }

        val action = systemAppActions[packageName]
        if (action != null) {
            action.invoke(appInfo)
            Log.d("MainActivity", "Launched system app: $packageName")
        } else {
            Log.w("MainActivity", "No action registered for system app: $packageName")
        }
    }

    private fun openClockApp() {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowClockDialog()
        }
    }

    private fun createAndShowClockDialog() {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.clock"  // Set identifier for tracking
        windowsDialog.setTitle("Date/Time Properties")
        windowsDialog.setTaskbarIcon(themeManager.getClockIcon())

        // Inflate the clock content
        val contentView = layoutInflater.inflate(R.layout.program_clock, null)

        // Create Clock app instance
        val clockApp = rocks.gorjan.gokixp.apps.clock.ClockApp(
            context = this,
            onSoundPlay = { playClickSound() },
            onCloseWindow = {
                windowsDialog.closeWindow()
            }
        )

        // Setup the app
        clockApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        // Use fixed size from layout: 370dp x 335dp
        windowsDialog.setWindowSize(370, 355)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            clockApp.cleanup()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }
    
    private fun openCalendarApp() {
        try {
            // Try to open the calendar app
            val calendarIntent = Intent(Intent.ACTION_MAIN)
            calendarIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // First try Google Calendar
            calendarIntent.setPackage("com.google.android.calendar")
            try {
                startActivity(calendarIntent)
                return
            } catch (e: Exception) {
                // Google Calendar not available, try system calendar
            }
            
            // Try system calendar
            calendarIntent.setPackage("com.android.calendar")
            try {
                startActivity(calendarIntent)
                return
            } catch (e: Exception) {
                // System calendar not available
            }
            
            // Fallback: try to open any calendar app
            val genericCalendarIntent = Intent(Intent.ACTION_VIEW)
            genericCalendarIntent.data = android.provider.CalendarContract.CONTENT_URI
            genericCalendarIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(genericCalendarIntent)
            
        } catch (e: Exception) {
            // All calendar opening methods failed
            Log.e("MainActivity", "Failed to open calendar app", e)
        }
    }
    
    private fun setupStartMenu(theme: String? = null) {
        try {
            Log.d("MainActivity", "Setting up start menu...")
            val startMenuView = findViewById<RelativeLayout>(R.id.start_menu)

            // Clear existing content if reloading
            if (startMenuView.isNotEmpty()) {
                startMenuView.removeAllViews()
            }

            // Phase 2: Use ThemeManager for layout selection
            val selectedTheme = if (theme != null) {
                AppTheme.fromString(theme)
            } else {
                themeManager.getSelectedTheme()
            }
            Log.d("MainActivity", "setupStartMenu: selectedTheme = '$selectedTheme'")
            val layoutResource = themeManager.getStartMenuLayoutRes(selectedTheme)
            Log.d("MainActivity", "Loading start menu layout: $layoutResource")

            val themeContent = layoutInflater.inflate(layoutResource, startMenuView, false)
            startMenuView.addView(themeContent)

            val recyclerView = findViewById<RecyclerView>(R.id.apps_recycler_view)
            val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
            val searchBoxView = findViewById<EditText>(R.id.search_box)

            Log.d("MainActivity", "startMenuView: $startMenuView")
            Log.d("MainActivity", "recyclerView: $recyclerView")
            Log.d("MainActivity", "commandsRecyclerView: $commandsRecyclerView")
            Log.d("MainActivity", "searchBoxView: $searchBoxView")

            if (startMenuView == null || recyclerView == null || commandsRecyclerView == null || searchBoxView == null) {
                Log.d("MainActivity", "Views are null, returning")
                return
            }
            
            startMenu = startMenuView
            appsRecyclerView = recyclerView
            searchBox = searchBoxView
            
            appsRecyclerView.layoutManager = LinearLayoutManager(this)

            // Setup commands RecyclerView
            commandsRecyclerView.layoutManager = LinearLayoutManager(this)
            setupCommandsList(commandsRecyclerView)

            // Load or restore apps
            if (theme != null) {
                // If reloading and adapter exists, restore it
                try {
                    appsRecyclerView.adapter = appsAdapter
                } catch (e: UninitializedPropertyAccessException) {
                    // If appsAdapter is not initialized, load apps normally
                    loadInstalledApps()
                }
            } else {
                // If initial setup, load installed apps
                loadInstalledApps()
            }
            
            // Setup search functionality
            setupSearchBox()
            
            // Setup profile picture click listener for easter egg sounds
            setupProfilePictureClickListener()

            // Setup shutdown button click (if it exists - only in XP theme)
            val shutdownButton = findViewById<ImageView>(R.id.shutdown_button)
            shutdownButton?.setOnClickListener {
                handleShutdown()
            }

            // Setup shutdown item click (if it exists - only in Windows 98 theme)
            val shutdownItem = findViewById<LinearLayout>(R.id.shutdown_item)
            shutdownItem?.setOnClickListener {
                handleShutdown()
            }
            val logoffItem = findViewById<LinearLayout>(R.id.logoff_item)
            logoffItem?.setOnClickListener {
                handleShutdown(isLogoff = true)
            }

            val settingsItem = findViewById<LinearLayout>(R.id.settings_item)
            settingsItem?.setOnClickListener {
                hideStartMenu()
                openPhoneSettings()
            }


            val welcomeItem = findViewById<LinearLayout>(R.id.welcome_item)
            welcomeItem?.setOnClickListener {
                hideStartMenu()
                showWelcomeToWindows()
            }

            val updateItem = findViewById<LinearLayout>(R.id.windows_update_item)
            updateItem?.setOnClickListener {
                hideStartMenu()
                checkForUpdates(true)
            }

            // Setup XP/Vista-specific All Programs toggle
            if (selectedTheme !is AppTheme.WindowsClassic) {
                val allProgramsWrapper = findViewById<LinearLayout>(R.id.all_programs)
                val allProgramsText = findViewById<TextView>(R.id.all_programs_text)
                val appListWrapper = findViewById<LinearLayout>(R.id.app_list_wrapper)
                val commandListWrapper = findViewById<LinearLayout>(R.id.command_list_wrapper)
                val allProgramsArrow = findViewById<ImageView>(R.id.all_programs_arrow)

                // Helper function to switch views
                fun switchToApps() {
                    if (!isStartMenuShowingApps) {
                        isStartMenuShowingApps = true
                        appListWrapper.visibility = View.VISIBLE
                        commandListWrapper?.visibility = View.GONE
                        allProgramsText?.text = "Back to Pinned"
                        allProgramsArrow?.rotation = 180f
                    }
                }

                fun switchToCommands() {
                    if (isStartMenuShowingApps) {
                        isStartMenuShowingApps = false
                        appListWrapper.visibility = View.GONE
                        commandListWrapper?.visibility = View.VISIBLE
                        allProgramsText?.text = "All Programs"
                        allProgramsArrow?.rotation = 0f
                        searchBoxView.setText("")
                    }
                }

                allProgramsWrapper?.setOnClickListener {
                    if (isStartMenuShowingApps) {
                        switchToCommands()
                    } else {
                        switchToApps()
                    }
                }

                // Detect backspace on empty search box
                searchBoxView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DEL &&
                        searchBoxView.text.isEmpty() &&
                        isStartMenuShowingApps) {
                        switchToCommands()
                        true
                    } else {
                        false
                    }
                }

                // Switch to apps when user starts typing (not just on focus)
                searchBoxView.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        // Only switch if user is actually typing (text is not empty)
                        if (!s.isNullOrEmpty()) {
                            switchToApps()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            Log.d("MainActivity", "Start menu setup complete")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up start menu", e)
        }
    }
    
    private fun showStartMenuContextMenu(x: Float, y: Float) {
        if (!::contextMenu.isInitialized) {
            Log.e("MainActivity", "Context menu not initialized")
            return
        }
        
        val menuItems = ContextMenuItems.getStartMenuMenuItems(
            onOpenSettings = {
                openPhoneSettings()
            },
            onRefreshAppList = {
                refreshAppListManually()
            }
        )
        
        contextMenu.showMenu(menuItems, x, y)
        isContextMenuVisible = true
        Log.d("MainActivity", "Start menu context menu shown at ($x, $y)")
    }
    
    private fun openPhoneSettings() {
        createAndShowWallpaperDialog("settings")
    }
    
    
    private fun loadAppIcon(packageName: String): Drawable? {
        // Handle special virtual items that don't have real packages
        if (packageName == "recycle.bin") {
            // Return the recycle bin icon from resources instead of looking in package manager
            return AppCompatResources.getDrawable(this, R.drawable.recycle)
        }

        if(packageName.startsWith("folder_")){
            // Return appropriate folder icon based on theme
            return AppCompatResources.getDrawable(this, themeManager.getFolderIconRes(themeManager.getSelectedTheme()))
        }

        // Handle system apps
        if (isSystemApp(packageName)) {
            return when (packageName) {
                "system.internet_explorer" ->AppCompatResources.getDrawable(this, themeManager.getIEIcon())
                "system.notepad" ->AppCompatResources.getDrawable(this, themeManager.getNotepadIcon())
                "system.clock" ->AppCompatResources.getDrawable(this, themeManager.getClockIcon())
                "system.solitare" ->AppCompatResources.getDrawable(this, themeManager.getSolitareIcon())
                "system.minesweeper" ->AppCompatResources.getDrawable(this, themeManager.getMinesweeperIcon())
                "system.registry_editor" ->AppCompatResources.getDrawable(this, themeManager.getRegeditIcon())
                "system.winamp" ->AppCompatResources.getDrawable(this, themeManager.getWinampIcon())
                "system.wmp" ->AppCompatResources.getDrawable(this, themeManager.getWmpIcon())
                "system.msn" ->AppCompatResources.getDrawable(this, themeManager.getMsnIcon())
                else -> null
            }
        }
        
        return try {
            try {
                val launcherContext =
                    createAttributionContext("system")
                val launcherApps = launcherContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val user = android.os.Process.myUserHandle()
                val activities = launcherApps.getActivityList(packageName, user)
                activities.firstOrNull()?.let { activityInfo ->
                    val icon = activityInfo.getBadgedIcon(resources.displayMetrics.densityDpi)
                    return icon
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "LauncherApps failed for $packageName", e)
            }

            // Final fallback to standard method
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val icon = appInfo.loadIcon(packageManager)
            Log.d("MainActivity", "Loaded icon for $packageName using standard PackageManager")
            return icon
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading icon for $packageName", e)
            null
        }
    }
    
    private fun setupCommandsList(commandsRecyclerView: RecyclerView) {
        // Load commands synchronously
        Log.d("MainActivity", "Loading commands synchronously...")
        val commandsList = loadCommandsInBackground()
        setupCommandsAdapterFromList(commandsList, commandsRecyclerView)
        Log.d("MainActivity", "Commands loaded (${commandsList.size} items)")
    }

    private fun loadCommandsInBackground(): List<CommandListItem> {
        // Get pinned apps
        val pinnedApps = getPinnedApps()
        val packageManager = packageManager

        // Build the commands list items
        val items = mutableListOf<CommandListItem>()

        // Add Programs command for Windows Classic theme (always first)
        if (shouldShowProgramsMenu()) {
            items.add(CommandListItem.ProgramsCommand(CommandItem(
                name = "Programs",
                iconResourceId = R.drawable.programs_98,
                action = {
                    toggleProgramsMenu()
                }
            )))
        }

        // Add pinned apps if any exist
        if (pinnedApps.isNotEmpty()) {
            // Add pinned apps (already sorted alphabetically when saved)
            pinnedApps.forEach { packageName ->
                try {
                    // Check if it's a system app
                    if (packageName.startsWith("system.")) {
                        // Get system app info
                        val systemApps = getSystemAppsList()
                        val systemApp = systemApps.find { it.packageName == packageName }
                        if (systemApp != null) {
                            items.add(CommandListItem.RecentApp(systemApp))
                            Log.d("MainActivity", "Added pinned system app: ${systemApp.name}")
                        } else {
                            Log.w("MainActivity", "System app not found: $packageName")
                        }
                    } else {
                        // Regular app - get from package manager
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        val appName = packageManager.getApplicationLabel(appInfo).toString()
                        val appIcon = getAppIcon(packageName) ?: packageManager.getApplicationIcon(appInfo)
                        items.add(CommandListItem.RecentApp(AppInfo(
                            name = appName,
                            packageName = packageName,
                            icon = appIcon
                        )))
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Could not load pinned app: $packageName", e)
                }
            }
        }

        return items
    }

    private fun setupCommandsAdapterFromList(commandsList: List<CommandListItem>, commandsRecyclerView: RecyclerView) {
        commandsAdapter = CommandsAdapter(this, commandsList,
            onAppLaunched = {
                // No automatic tracking - apps must be manually pinned
                // Just launch the app without changing pin status
            },
            onItemClicked = {
                // Close start menu when any command or recent app is clicked
                hideStartMenu()
            },
            onAppLongClicked = { appInfo, x, y ->
                showStartMenuAppContextMenu(appInfo, x, y)
            }
        )
        commandsRecyclerView.adapter = commandsAdapter

        // Apply current theme to the adapter
        commandsAdapter?.onThemeChanged(themeManager.getSelectedTheme())
    }

    private fun refreshCommandsList() {
        Log.d("MainActivity", "Commands list refresh requested")
        isCommandsListLoading = false // Reset loading flag

        val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
        if (commandsRecyclerView != null) {
            setupCommandsList(commandsRecyclerView)
        }
    }
    
    private fun isRoverVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_ROVER_VISIBLE, true) // Default to visible
    }
    
    private fun isRecycleBinVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_RECYCLE_BIN_VISIBLE, true) // Default to visible
    }
    
    private fun isQuickGlanceVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_QUICK_GLANCE_VISIBLE, true) // Default to visible
    }

    fun isShortcutArrowVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHORTCUT_ARROW_VISIBLE, true) // Default to visible
    }

    private fun isCursorVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_CURSOR_VISIBLE, true) // Default to visible
    }

    private fun toggleCursorVisibility() {
        val isCurrentlyVisible = isCursorVisible()
        val newVisibility = !isCurrentlyVisible

        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_CURSOR_VISIBLE, newVisibility) }

    }

    private fun getCurrentThemeWallpaperKeys(): Pair<String, String> {
        return getCurrentThemeWallpaperKeysTypeSafe()
    }

    private fun getDefaultWallpaperForTheme(): String {
        return getDefaultWallpaperForCurrentTheme()
    }
    
    private fun getUserName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_USER_NAME, "User") ?: "User"
    }
    
    private fun setUserName(userName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(KEY_USER_NAME, userName) }
        updateProfileName()
    }
    
    private fun updateProfileName() {
        val userName = getUserName()
        profileNameView?.text = userName
        Log.d("MainActivity", "Updated profile name to: $userName")
    }

    private fun updateProfilePicture() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val iconPath = prefs.getString("user_icon_path", "default") ?: "default"

        try {
            val drawable = if (iconPath == "default") {
                // Use default user icon
                ContextCompat.getDrawable(this, R.drawable.user)
            } else {
                // Load custom icon from assets
                val inputStream = assets.open(iconPath)
                val customDrawable = Drawable.createFromStream(inputStream, iconPath)
                inputStream.close()
                customDrawable
            }

            profilePictureView?.setImageDrawable(drawable)
            Log.d("MainActivity", "Updated profile picture to: $iconPath")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading profile picture: ${e.message}")
            // Fallback to default icon
            profilePictureView?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.user))
        }
    }

    private fun toggleRover() {
        val isCurrentlyVisible = isRoverVisible()
        val newVisibility = !isCurrentlyVisible
        
        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_ROVER_VISIBLE, newVisibility) }
        
        // Apply visibility
        if (::agentView.isInitialized) {
            agentView.visibility = if (newVisibility) View.VISIBLE else View.GONE
            Log.d("MainActivity", "Rover visibility changed to: ${if (newVisibility) "VISIBLE" else "GONE"}")
        }
        
        // Refresh commands list to update button text
        val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
        if (commandsRecyclerView != null) {
            setupCommandsList(commandsRecyclerView)
        }
    }
    
    private fun showAgentSelectionDialog() {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowAgentSelectionDialog()
        }
    }

    private fun createAndShowAgentSelectionDialog() {
        val agents = Agent.ALL_AGENTS
        val currentAgent = agentView.getCurrentAgent()
        val currentIndex = agents.indexOf(currentAgent)

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Select Agent")

        // Create content view
        val contentView = LinearLayout(this)
        contentView.orientation = LinearLayout.VERTICAL
        contentView.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())

        // Create radio group for agent selection
        val radioGroup = android.widget.RadioGroup(this)
        radioGroup.orientation = android.widget.RadioGroup.VERTICAL

        agents.forEachIndexed { index, agent ->
            val radioButton = android.widget.RadioButton(this)
            radioButton.id = View.generateViewId() // Generate unique ID for proper grouping
            radioButton.text = agent.name
            radioButton.isChecked = (index == currentIndex)
            radioButton.setTextColor(Color.BLACK)
            radioButton.textSize = 14f
            radioButton.setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
            radioGroup.addView(radioButton)
        }

        contentView.addView(radioGroup)

        // Add spacing
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            8.dpToPx()
        )
        contentView.addView(spacer)

        // Create buttons container
        val buttonsContainer = LinearLayout(this)
        buttonsContainer.orientation = LinearLayout.HORIZONTAL
        buttonsContainer.gravity = android.view.Gravity.END

        // Get the appropriate button background based on theme
        val buttonBackground = getButtonBackgroundForCurrentTheme()

        // Create OK button
        val okButton = TextView(this).apply {
            text = "OK"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            setBackgroundResource(buttonBackground)
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
        }

        // Create Cancel button
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            setBackgroundResource(buttonBackground)
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonsContainer.addView(okButton)
        buttonsContainer.addView(cancelButton)
        contentView.addView(buttonsContainer)

        windowsDialog.setContentView(contentView)

        // Apply theme fonts to the entire dialog content
        applyThemeFontsToDialog(contentView)

        // Create the dialog container
        // OK button click handler
        okButton.setOnClickListener {
            playClickSound()
            val selectedButtonId = radioGroup.checkedRadioButtonId
            if (selectedButtonId != -1) {
                val selectedRadioButton = radioGroup.findViewById<android.widget.RadioButton>(selectedButtonId)
                val selectedIndex = radioGroup.indexOfChild(selectedRadioButton)
                if (selectedIndex >= 0 && selectedIndex < agents.size) {
                    val selectedAgent = agents[selectedIndex]
                    if (selectedAgent != currentAgent) {
                        agentView.setCurrentAgent(selectedAgent)

                        // Refresh commands list to update agent name and icon
                        refreshCommandsList()

                        Log.d("MainActivity", "Agent changed to: ${selectedAgent.name}")
                    }
                }
            }
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Cancel button click handler
        cancelButton.setOnClickListener {
            playClickSound()
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showAgentContextMenu(agent: Agent, agentX: Float, agentY: Float) {
        Log.d("MainActivity", "Showing context menu for agent: ${agent.name} at position ($agentX, $agentY)")
        
        if (::contextMenu.isInitialized) {
            // Create agent context menu items
            val menuItems = listOf(
                ContextMenuItem("Change Agent", isEnabled = true, action = {
                    Log.d("MainActivity", "Opening agent selection dialog from context menu")
                    showAgentSelectionDialog()
                }),
                ContextMenuItem("", isEnabled = false), // Divider
                ContextMenuItem("Hide Agent", isEnabled = true, action = {
                    Log.d("MainActivity", "Hiding agent: ${agent.name}")
                    toggleRover() // This will hide the agent
                })
            )
            
            // Show the menu at agent position
            contextMenu.showMenu(menuItems, agentX, agentY)
            isContextMenuVisible = true
            
            // Hide start menu if visible
            if (isStartMenuVisible) {
                hideStartMenu()
            }
            
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }
    
    private fun showQuickGlanceContextMenu(screenX: Float, screenY: Float) {
        Log.d("MainActivity", "Showing context menu for Quick Glance widget at position ($screenX, $screenY)")
        
        if (::contextMenu.isInitialized) {
            // Create Quick Glance context menu items
            val menuItems = ContextMenuItems.getQuickGlanceMenuItems(
                onHideQuickGlance = {
                    Log.d("MainActivity", "Hiding Quick Glance widget")
                    toggleQuickGlance()
                },
                onRefreshCalendar = {
                    Log.d("MainActivity", "Refreshing calendar data")
                    refreshWidgetData()
                },
                onToggleCalendarEvents = {
                    Log.d("MainActivity", "Toggling calendar events setting")
                    val currentState = quickGlanceWidget.isShowCalendarEventsEnabled()
                    quickGlanceWidget.setShowCalendarEvents(!currentState)
                },
                isCalendarEventsEnabled = quickGlanceWidget.isShowCalendarEventsEnabled()
            )
            
            // Show the menu at the specified position
            contextMenu.showMenu(menuItems, screenX, screenY)
            isContextMenuVisible = true
            
            // Hide start menu if visible
            if (isStartMenuVisible) {
                hideStartMenu()
            }
            
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }
    
    private fun showUserNameDialog() {
        // Set cursor to busy while loading
        setCursorBusy()
        hideStartMenu()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowUserNameDialog()
        }
    }

    private fun createAndShowUserNameDialog() {
        val currentUserName = getUserName()

        showRenameDialog(
            title = "Change User Name",
            initialText = currentUserName,
            hint = "User name"
        ) { newName ->
            if (newName != currentUserName) {
                setUserName(newName)
                Log.d("MainActivity", "User name changed to: $newName")
            }
        }

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }
    
    private fun toggleRecycleBin() {
        val isCurrentlyVisible = isRecycleBinVisible()
        val newVisibility = !isCurrentlyVisible
        
        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {putBoolean(KEY_RECYCLE_BIN_VISIBLE, newVisibility) }

        if (newVisibility) {
            // Show recycle bin - add it to the first available grid space
            showRecycleBin()
        } else {
            // Hide recycle bin - remove it from desktop and free up grid space
            hideRecycleBin()
        }
        
        // Refresh commands list to update button text
        val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
        if (commandsRecyclerView != null) {
            setupCommandsList(commandsRecyclerView)
        }
        
        Log.d("MainActivity", "Recycle Bin visibility changed to: ${if (newVisibility) "VISIBLE" else "GONE"}")
    }
    
    private fun toggleQuickGlance() {
        val isCurrentlyVisible = isQuickGlanceVisible()
        val newVisibility = !isCurrentlyVisible
        
        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {putBoolean(KEY_QUICK_GLANCE_VISIBLE, newVisibility) }

        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.visibility = if (newVisibility) View.VISIBLE else View.GONE
        }
        
        // Refresh commands list to update button visibility
        val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
        if (commandsRecyclerView != null) {
            setupCommandsList(commandsRecyclerView)
        }
        
        Log.d("MainActivity", "Quick Glance visibility changed to: ${if (newVisibility) "VISIBLE" else "GONE"}")
    }

    private fun toggleShortcutArrow() {
        val isCurrentlyVisible = isShortcutArrowVisible()
        val newVisibility = !isCurrentlyVisible

        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_SHORTCUT_ARROW_VISIBLE, newVisibility) }

        // Update all desktop icons to reflect the change
        refreshDesktopIconsVisibility()

        Log.d("MainActivity", "Shortcut arrow visibility changed to: ${if (newVisibility) "VISIBLE" else "GONE"}")
    }

    private fun refreshDesktopIconsVisibility() {
        // Update visibility for all desktop icons
        for (i in 0 until desktopContainer.childCount) {
            val child = desktopContainer.getChildAt(i)
            if (child is DesktopIconView) {
                child.updateShortcutArrowVisibility(isShortcutArrowVisible())
            }
        }
    }

    private fun setSwipeRightApp(appInfo: AppInfo) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {putString(KEY_SWIPE_RIGHT_APP, appInfo.packageName) }

        Log.d("MainActivity", "Set swipe right app to: ${appInfo.name} (${appInfo.packageName})")
        showNotification("Swipe Right App changed", "Swipe right app set to ${appInfo.name}")
    }

    private fun getSwipeRightApp(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_SWIPE_RIGHT_APP, null)
    }

    private fun setWeatherApp(appInfo: AppInfo) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(KEY_WEATHER_APP, appInfo.packageName) }

        showNotification("Weather app set", "Tap the weather icon to open ${appInfo.name}")
    }

    private fun getWeatherApp(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_WEATHER_APP, null)
    }
    
    private fun showRecycleBin() {
        if (::recycleBin.isInitialized && recycleBin.parent == null) {
            // Find first available grid position
            val position = findFirstAvailableGridPosition()
            // Add to desktop at the calculated position
            val layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.leftMargin = position.first
            layoutParams.topMargin = position.second

            desktopContainer.addView(recycleBin, layoutParams)
            recycleBin.visibility = View.VISIBLE

            // Set recycle bin to the calculated grid position
            recycleBin.x = position.first.toFloat()
            recycleBin.y = position.second.toFloat()

            // Update desktop icon position and save
            val desktopIcon = recycleBin.getDesktopIcon()
            if (desktopIcon != null) {
                desktopIcon.x = position.first.toFloat()
                desktopIcon.y = position.second.toFloat()
                saveDesktopIconPosition(desktopIcon)
            } else {
                Log.d("MainActivity", "Recycle Bin added to grid at position: ${position.first}, ${position.second}")
            }
        } else if (::recycleBin.isInitialized) {
            // Just make it visible if already in layout
            recycleBin.visibility = View.VISIBLE
        }
    }
    
    private fun hideRecycleBin() {
        if (::recycleBin.isInitialized) {
            recycleBin.visibility = View.GONE
            // Optionally remove from parent to truly free up space
            val parent = recycleBin.parent as? RelativeLayout
            parent?.removeView(recycleBin)
            Log.d("MainActivity", "Recycle Bin hidden and removed from desktop")
        }
    }
    
    private fun findFirstAvailableGridPosition(): Pair<Int, Int> {
        val iconSize = (90 * resources.displayMetrics.density).toInt()
        val margin = (12 * resources.displayMetrics.density).toInt()
        val totalIconSize = iconSize + margin * 2
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val taskbarHeight = (70 * resources.displayMetrics.density).toInt()
        
        val cols = (screenWidth - margin) / totalIconSize
        val rows = (screenHeight - taskbarHeight - margin) / totalIconSize
        
        // Check each grid position to find the first available one
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = margin + col * totalIconSize
                val y = margin + row * totalIconSize
                
                // Check if this position is occupied by any desktop icon
                var occupied = false
                for (i in 0 until desktopContainer.childCount) {
                    val child = desktopContainer.getChildAt(i)
                    if (child is DesktopIconView) {
                        val childX = child.x.toInt()
                        val childY = child.y.toInt()
                        
                        // Check if positions overlap (with some tolerance)
                        if (abs(childX - x) < totalIconSize / 2 && abs(childY - y) < totalIconSize / 2) {
                            occupied = true
                            break
                        }
                    }
                }
                
                if (!occupied) {
                    return Pair(x, y)
                }
            }
        }
        
        // If no position found, return a default position
        return Pair(margin, margin)
    }
    
    private fun setupKeyboardDetection() {
        val rootView = findViewById<View>(android.R.id.content)

        // Use modern WindowInsets API for reliable keyboard detection (API 30+)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom

            val wasKeyboardOpen = isKeyboardOpen
            isKeyboardOpen = imeVisible

            // Only adjust if keyboard state changed and start menu is visible
            if (wasKeyboardOpen != isKeyboardOpen && isStartMenuVisible) {
                adjustStartMenuForKeyboard()
            }

            // Return insets to allow other listeners to handle them
            insets
        }
    }
    
    private fun adjustStartMenuForKeyboard() {
        if (!::startMenu.isInitialized) return

        // Get the ConstraintLayout container by ID
        val startMenuContainer = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.start_menu_container) ?: return
        val layoutParams = startMenuContainer.layoutParams as? RelativeLayout.LayoutParams ?: return

        // Save original layout params the first time (they have the 70dp bottom margin from XML)
        if (originalStartMenuLayoutParams == null) {
            originalStartMenuLayoutParams = RelativeLayout.LayoutParams(layoutParams)
        }

        if (isKeyboardOpen) {
            // Calculate available space above keyboard
            val rootView = findViewById<View>(android.R.id.content)
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)

            // Calculate available height above keyboard (rect.bottom is where keyboard starts)
            val availableHeight = rect.bottom

            // Adjust container to fill available space
            layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            layoutParams.height = availableHeight
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = 0

        } else {
            // Restore original layout params (including the 70dp bottom margin)
            originalStartMenuLayoutParams?.let { original ->
                layoutParams.height = original.height
                layoutParams.topMargin = original.topMargin
                layoutParams.bottomMargin = original.bottomMargin
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        startMenuContainer.layoutParams = layoutParams
    }
    
    private fun loadInstalledApps() {
        // If already loading, return early
        if (isAppListLoading) {
            Log.d("MainActivity", "App list already loading, skipping")
            return
        }

        // Use cached list if available
        cachedAppList?.let { cachedApps ->
            Log.d("MainActivity", "Using cached app list (${cachedApps.size} apps)")
            setupAppsAdapterFromList(cachedApps)
            return
        }

        // Load apps asynchronously
        isAppListLoading = true
        Thread {
            try {
                Log.d("MainActivity", "Loading apps asynchronously...")
                val appList = loadAppsInBackground()

                runOnUiThread {
                    cachedAppList = appList
                    isAppListLoading = false
                    setupAppsAdapterFromList(appList)
                    Log.d("MainActivity", "Apps loaded and cached (${appList.size} apps)")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading apps in background", e)
                runOnUiThread {
                    isAppListLoading = false
                }
            }
        }.start()
    }

    private fun loadAppsInBackground(): List<AppInfo> {
        val packageManager = packageManager
        val appInfoMap = mutableMapOf<String, AppInfo>()

        // Add system apps first (with icons loaded since they're from resources)
        getSystemAppsList().forEach { systemApp ->
            appInfoMap[systemApp.packageName] = systemApp
        }

        // Get all apps with launcher intents
        // Load icons immediately (cached for performance) - provides smooth scrolling
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfoList = packageManager.queryIntentActivities(mainIntent, 0)

            Log.d("MainActivity", "Loading ${resolveInfoList.size} apps with cached icons for smooth scrolling")

            resolveInfoList.forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (!appInfoMap.containsKey(packageName)) {
                    // Load icon immediately - uses cache so it's fast on subsequent loads
                    val icon = getAppIcon(packageName, skipCustom = true) ?: resolveInfo.loadIcon(packageManager)
                    appInfoMap[packageName] = AppInfo(
                        name = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = packageName,
                        icon = icon
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading apps", e)
        }

        return appInfoMap.values.toList().sortedBy { it.name.lowercase() }
    }

    private fun setupAppsAdapterFromList(appList: List<AppInfo>) {
        // Get pinned apps for the commands panel
        val pinnedApps = getPinnedApps()

        // Create final list with all apps
        val finalAppsList = mutableListOf<Any>()
        finalAppsList.addAll(appList)

        Log.d("MainActivity", "Setting up apps adapter with ${appList.size} apps (${pinnedApps.size} pinned)")

        appsAdapter = AppsAdapter(this, finalAppsList,
            onAppClick = { hideStartMenu() },
            onAppLongClick = { appInfo, x, y ->
                showStartMenuAppContextMenu(appInfo, x, y)
            },
            pinnedApps = pinnedApps.toSet(),
            onAppLaunched = {
                // No automatic tracking - apps must be manually pinned
            },
            recentApps = pinnedApps.toSet()
        )
        appsRecyclerView.adapter = appsAdapter

        // Apply current theme to the adapter
        appsAdapter?.onThemeChanged(themeManager.getSelectedTheme())
    }

    private fun refreshAppListManually() {
        Log.d("MainActivity", "Manual app list refresh requested")
        cachedAppList = null // Clear cache
        isAppListLoading = false // Reset loading flag
        loadInstalledApps()

        // Also refresh the commands list
        refreshCommandsList()

        // Also call the original manual refresh for desktop icons
        manualRefreshAppsAndDesktop()
    }

    private fun setupSearchBox() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                appsAdapter?.filter(query)
            }
        })

        // Handle search key press to open search intent
        searchBox.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == android.view.KeyEvent.KEYCODE_SEARCH || keyCode == android.view.KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                val query = searchBox.text.toString().trim()
                if (query.isNotEmpty()) {
                    if(query == "marti"){
                        val url = "https://gorjan.rocks/clients/marti/"
                        showInternetExplorerDialog(url)
                    }
                    else {
                        openSearchWithQuery(query)
                    }
                    hideStartMenu()
                }
                true
            } else {
                false
            }
        }
    }

    private fun openSearchWithQuery(query: String) {
        // Open Internet Explorer floating window with Google search
        val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
        showInternetExplorerDialog(searchUrl)
        Log.d("MainActivity", "Opened IE window with search query: '$query'")
    }

    private fun setupProfilePictureClickListener() {
        profilePictureView = findViewById(R.id.profile_picture)
        profileNameView = findViewById(R.id.profile_name)
        profilePictureView?.setOnClickListener {
            hideStartMenu()
            showUserIconSelectionDialog()
        }

        profilePictureView?.setOnLongClickListener {
            playNextEggSound()
            true
        }
        
        // Add click listener to profile name for changing user name
        profileNameView?.setOnClickListener {
            showUserNameDialog()
        }
        
        // Load saved user name and picture on startup
        updateProfileName()
        updateProfilePicture()
    }
    
    private fun playNextEggSound() {
        if (eggSounds.isNotEmpty()) {
            val resourceId = eggSounds[currentEggSoundIndex]

            // Switch profile picture and name to Balmer while sound plays
            profilePictureView?.setImageResource(R.drawable.balmer)
            profileNameView?.text = "Balmer"

            // Play the sound using MediaPlayer for longer sounds
            playLongSound(resourceId)
            Log.d("MainActivity", "Playing egg sound (index $currentEggSoundIndex)")

            // Get the actual duration for this sound and schedule revert
            val duration = getMediaDuration(resourceId)
            Handler(Looper.getMainLooper()).postDelayed({
                // Revert profile picture and name back to original
                updateProfilePicture() // Use saved user icon
                updateProfileName() // Use saved user name instead of hardcoded "Gorjan"
                Log.d("MainActivity", "Reverted profile picture and name back to original after $duration ms")
            }, duration)

            // Move to next sound, wrap around to 0 when reaching the end
            currentEggSoundIndex = (currentEggSoundIndex + 1) % eggSounds.size
        }
    }
    
    private fun toggleStartMenu() {
        Log.d("MainActivity", "Toggle start menu called")
        if (!::startMenu.isInitialized) {
            Log.d("MainActivity", "Start menu not initialized")
            return
        }
        
        if (isStartMenuVisible) {
            hideStartMenu()
        } else {
            showStartMenu()
        }
    }
    
    private fun showStartMenu() {
        if (::startMenu.isInitialized) {
            startMenu.visibility = View.VISIBLE
            isStartMenuVisible = true

            // For Windows Classic theme, keep app list invisible when opened via Start button
            val appList98 = findViewById<RelativeLayout>(R.id.start_menu_app_list_98)
            appList98?.visibility = View.INVISIBLE
            isProgramsMenuExpanded = false
            commandsAdapter?.setProgramsExpanded(false)

            // For Windows Vista theme, reset to show command list instead of app list
            if (themeManager.getSelectedTheme() is AppTheme.WindowsVista || themeManager.getSelectedTheme() is AppTheme.WindowsXP) {
                val appListWrapper = findViewById<LinearLayout>(R.id.app_list_wrapper)
                val commandListWrapper = findViewById<LinearLayout>(R.id.command_list_wrapper)
                val allProgramsText = findViewById<TextView>(R.id.all_programs_text)
                val allProgramsArrow = findViewById<ImageView>(R.id.all_programs_arrow)

                // Reset state and UI
                isStartMenuShowingApps = false
                appListWrapper?.visibility = View.GONE
                commandListWrapper?.visibility = View.VISIBLE
                allProgramsText?.text = "All Programs"
                allProgramsArrow.rotation = 0f
            }

            // Adjust for keyboard if needed (async to avoid blocking)
            if (isKeyboardOpen) {
                adjustStartMenuForKeyboard()
            }

            // Scroll app list to top (async to avoid blocking)
            if (::appsRecyclerView.isInitialized) {
                appsRecyclerView.post {
                    appsRecyclerView.scrollToPosition(0)
                }
            }

            // Hide context menu if visible
            if (isContextMenuVisible) {
                hideContextMenu()
            }
        }
    }
    
    fun hideStartMenu() {
        if (::startMenu.isInitialized) {
            startMenu.visibility = View.GONE
            isStartMenuVisible = false

            // Hide context menu if visible
            if (isContextMenuVisible) {
                hideContextMenu()
            }

            // Reset app list visibility to invisible for Windows Classic theme and Programs menu state
            val appList98 = findViewById<RelativeLayout>(R.id.start_menu_app_list_98)
            appList98?.visibility = View.INVISIBLE
            isProgramsMenuExpanded = false
            commandsAdapter?.setProgramsExpanded(false)

            // Close keyboard and clear search box
            if (::searchBox.isInitialized) {
                // Hide keyboard
                val inputContext =
                    createAttributionContext("system")
                val imm = inputContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchBox.windowToken, 0)

                // Clear search text
                searchBox.setText("")
            }

            // Reset keyboard state flag and restore original layout
            isKeyboardOpen = false
            originalStartMenuLayoutParams?.let { originalParams ->
                val startMenuContainer = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.start_menu_container)
                startMenuContainer?.layoutParams = originalParams
            }
            // Reset saved params so they get re-saved fresh next time
            originalStartMenuLayoutParams = null

            // MEMORY OPTIMIZATION: Clear cached app list to release icon memory when menu closes
            // Icons will be reloaded (from cache) next time menu opens
            cachedAppList = null
        }
    }
    
    fun showStartMenuWithSearch() {
        if (::startMenu.isInitialized) {
            startMenu.visibility = View.VISIBLE
            isStartMenuVisible = true

            // For Windows Classic theme, make app list visible when opened via swipe up
            // For Vista, keep showing command list until user starts typing
            if (themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
                val appList98 = findViewById<RelativeLayout>(R.id.start_menu_app_list_98)
                appList98?.visibility = View.VISIBLE
                isProgramsMenuExpanded = true
                commandsAdapter?.setProgramsExpanded(true)
            } else {
                val appListWrapper = findViewById<LinearLayout>(R.id.app_list_wrapper)
                val commandListWrapper = findViewById<LinearLayout>(R.id.command_list_wrapper)
                val allProgramsText = findViewById<TextView>(R.id.all_programs_text)
                val allProgramsArrow = findViewById<ImageView>(R.id.all_programs_arrow)

                // Reset state and UI
                isStartMenuShowingApps = false
                appListWrapper?.visibility = View.GONE
                commandListWrapper?.visibility = View.VISIBLE
                allProgramsText?.text = "All Programs"
                allProgramsArrow.rotation = 0f

            }

            // Adjust for keyboard if needed
            adjustStartMenuForKeyboard()

            // Scroll app list to top
            if (::appsRecyclerView.isInitialized) {
                appsRecyclerView.scrollToPosition(0)
            }

            // Focus search box and show keyboard
            if (::searchBox.isInitialized) {
                searchBox.requestFocus()
                val inputContext =
                    createAttributionContext("system")
                val imm = inputContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun toggleProgramsMenu() {
        val appList98 = findViewById<RelativeLayout>(R.id.start_menu_app_list_98)

        if (appList98 != null) {
            isProgramsMenuExpanded = !isProgramsMenuExpanded
            appList98.visibility = if (isProgramsMenuExpanded) View.VISIBLE else View.INVISIBLE

            // Update the adapter with the new expanded state
            commandsAdapter?.setProgramsExpanded(isProgramsMenuExpanded)
            playClickSound()
        }
    }

    private fun setupBackPressHandling() {
        // Modern back press handling for Android 13+ (API 33+)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isStartMenuVisible -> {
                        // If start menu is open, close it
                        Log.d("MainActivity", "Back pressed (modern): closing start menu")
                        hideStartMenu()
                    }
                    floatingWindowManager.getFrontWindow() != null -> {
                        // If there's a floating window, close the front-most one
                        Log.d("MainActivity", "Back pressed (modern): closing front window")
                        floatingWindowManager.closeFrontWindow()
                    }
                    else -> {
                        // If start menu is closed, do nothing
                        // This prevents the home screen from closing/restarting
                        Log.d("MainActivity", "Back pressed (modern): ignored (home screen)")
                    }
                }
            }
        })
    }

    private fun setupDesktopInteractions() {
        contextMenu = findViewById(R.id.context_menu)
        Log.d("MainActivity", "Found context menu: $contextMenu")

        // Initialize notification bubble
        notificationBubble = findViewById(R.id.notification_bubble)
        notificationTitle = findViewById(R.id.notification_title)
        notificationText = findViewById(R.id.notification_text)

        // Set up click listener to hide notification on tap
        notificationBubble.setOnClickListener {
            // Call the callback if it exists
            notificationTapCallback?.invoke()
            notificationTapCallback = null // Clear after use
            hideNotification()
        }

        // Set up close button to only close notification (without triggering callback)
        val closeNotificationButton = findViewById<View>(R.id.close_notification_button)
        closeNotificationButton.setOnClickListener {
            // Only hide the notification, don't call the callback
            notificationTapCallback = null // Clear callback to prevent it from being called
            hideNotification()
            // Click listener on child view consumes the event and prevents propagation to parent
        }

        // Set up gesture detector for long press and swipe down
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showContextMenu(e.x, e.y)
            }
            
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                Log.d("MainActivity", "Single tap detected")
                hideContextMenu()
                if (isStartMenuVisible) {
                    hideStartMenu()
                }
                return true
            }
            
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x
                    
                    Log.d("MainActivity", "Fling detected: deltaY=$deltaY, deltaX=$deltaX, startY=${e1.y}, velocityY=$velocityY")
                    
                    // Check if this is a swipe down, swipe up, or swipe right gesture from anywhere on the screen
                    val isSwipeDown = deltaY > 0 && abs(deltaY) > abs(deltaX)
                    val isSwipeUp = deltaY < 0 && abs(deltaY) > abs(deltaX)
                    val isSwipeRight = deltaX > 0 && abs(deltaX) > abs(deltaY)
                    val isMinimumDistanceY = abs(deltaY) > 80 // At least 80px movement for vertical
                    val isMinimumDistanceX = abs(deltaX) > 80 // At least 80px movement for horizontal
                    val isMinimumVelocityY = abs(velocityY) > 300 // Minimum velocity for vertical
                    val isMinimumVelocityX = abs(velocityX) > 300 // Minimum velocity for horizontal
                    
                    Log.d("MainActivity", "Swipe conditions: isSwipeDown=$isSwipeDown, isSwipeUp=$isSwipeUp, isSwipeRight=$isSwipeRight, minDistY=$isMinimumDistanceY, minDistX=$isMinimumDistanceX, minVelY=$isMinimumVelocityY, minVelX=$isMinimumVelocityX")
                    
                    if (isSwipeDown && isMinimumDistanceY && isMinimumVelocityY) {
                        Log.d("MainActivity", "✅ Swipe down detected")
                        
                        // Check if start menu is open first
                        if (isStartMenuVisible) {
                            Log.d("MainActivity", "Start menu is open, closing it first")
                            hideStartMenu()
                        } else {
                            Log.d("MainActivity", "Start menu closed, expanding notification shade")
                            expandNotificationShade()
                        }
                        return true
                    } else if (isSwipeUp && isMinimumDistanceY && isMinimumVelocityY) {
                        Log.d("MainActivity", "✅ Swipe up detected, opening start menu with search focus")
                        showStartMenuWithSearch()
                        return true
                    } else if (isSwipeRight && isMinimumDistanceX && isMinimumVelocityX) {
                        Log.d("MainActivity", "✅ Swipe right detected, launching swipe right app")
                        launchSwipeRightApp()
                        return true
                    }
                }
                return false
            }
            
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
        
        // Set touch listener on the main background
        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)
        mainBackground.setOnTouchListener { _, event ->
            // Check if speech bubble should be dismissed when touching outside
            if (event.action == MotionEvent.ACTION_DOWN && ::speechBubbleView.isInitialized) {
                if (speechBubbleView.isInInputMode()) {
                    // Check if touch is outside the speech bubble
                    val location = IntArray(2)
                    speechBubbleView.getLocationOnScreen(location)
                    val bubbleX = location[0]
                    val bubbleY = location[1]
                    val bubbleWidth = speechBubbleView.width
                    val bubbleHeight = speechBubbleView.height
                    
                    val touchX = event.rawX
                    val touchY = event.rawY
                    
                    // If touch is outside the speech bubble bounds, hide it
                    if (touchX < bubbleX || touchX > bubbleX + bubbleWidth ||
                        touchY < bubbleY || touchY > bubbleY + bubbleHeight) {
                        Log.d("MainActivity", "Touch outside speech bubble - dismissing input mode")
                        
                        // Hide the soft keyboard
                        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        inputMethodManager.hideSoftInputFromWindow(speechBubbleView.windowToken, 0)
                        
                        speechBubbleView.hideSpeech()
                    }
                }
            }
            
            gestureDetector.onTouchEvent(event)
            true // Consume the event
        }
        
    }
    
    private fun setupClippy() {
        Log.d("MainActivity", "Setting up Clippy...")
        
        // Create and configure ClippyView
        agentView = AgentView(this)
        Log.d("MainActivity", "ClippyView created")
        
        // Create speech bubble view
        speechBubbleView = SpeechBubbleView(this)
        Log.d("MainActivity", "SpeechBubbleView created")
        
        // Set up speech request listener (when user types and clicks send)
        speechBubbleView.setOnSpeechRequestListener { message ->
            Log.d("MainActivity", "Speech requested: '$message'")
            val currentAgent = agentView.getCurrentAgent()
            
            // Show loading bubble manually instead of using clippyView.triggerSpeech()
            speechBubbleView.showLoadingBubble(agentView.x, agentView.y, agentView.width, agentView.height)
            
            // Create TTS service instance and speak directly
            val ttsService = TTSService(this)
            ttsService.speakText(
                text = message,
                agent = currentAgent,
                onStart = {
                    Log.d("MainActivity", "TTS started for custom message: '$message'")
                },
                onAudioReady = { audioDurationMs ->
                    // Update bubble with text and set talking state
                    speechBubbleView.updateBubbleText(message, audioDurationMs)
                    agentView.switchToTalkingState(audioDurationMs)
                    Log.d("MainActivity", "Audio ready (${audioDurationMs}ms) for custom message")
                },
                onComplete = {
                    // Return agent to waiting state
                    agentView.switchToWaitingState()
                    Log.d("MainActivity", "TTS completed for custom message")
                },
                onError = { exception ->
                    Log.e("MainActivity", "TTS error for custom message", exception)
                    // Fallback to text-only display
                    val wordCount = message.split(" ").size
                    val speechDurationMs = ((wordCount / 140.0) * 60 * 1000).toLong()
                    val minDuration = 2000L
                    val finalDuration = maxOf(speechDurationMs, minDuration)
                    
                    speechBubbleView.updateBubbleTextWithCountdown(message)
                    agentView.switchToTalkingState(finalDuration)
                }
            )
        }
        
        // Set up agent tap callback (shows input bubble)
        agentView.onAgentTapped = { agent, agentX, agentY, agentWidth, agentHeight ->
            val defaultText = agent.getGreetingMessage(this)
            speechBubbleView.showInputBubble(defaultText, agentX, agentY, agentWidth, agentHeight)
            Log.d("MainActivity", "Agent '${agent.name}' tapped, showing input bubble with default: '$defaultText'")
        }
        
        // Set up agent speaking with audio callback (updates bubble with text)
        agentView.onAgentSpeakingWithAudio = { agent, message, _, _, _, _, audioDurationMs ->
            speechBubbleView.updateBubbleText(message, audioDurationMs)
            Log.d("MainActivity", "Agent '${agent.name}' speaking with audio (${audioDurationMs}ms): '$message'")
        }
        
        // Set up agent speaking text-only callback (fallback)
        agentView.onAgentSpeakingTextOnly = { agent, message, _, _, _, _ ->
            speechBubbleView.updateBubbleTextWithCountdown(message)
            Log.d("MainActivity", "Agent '${agent.name}' speaking text-only: '$message'")
        }
        
        // Set up agent long-press callback (shows context menu)
        agentView.onAgentLongPress = { agent, agentX, agentY ->
            showAgentContextMenu(agent, agentX, agentY)
        }
        
        // Create layout params without positioning rules  
        val size = (100 * resources.displayMetrics.density).toInt()
        val layoutParams = RelativeLayout.LayoutParams(size, size)
        
        Log.d("MainActivity", "Layout params set: size=${size}px")
        
        // Add to desktop container (this will render over icons but under menus)
        desktopContainer.addView(agentView, layoutParams)
        
        // Add speech bubble to desktop container (higher elevation than agent)
        val speechBubbleLayoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        desktopContainer.addView(speechBubbleView, speechBubbleLayoutParams)
        
        // Set elevation to render above desktop icons but below menus
        // Context menu: 100dp, Start menu: 10dp, Taskbar: 15dp
        // Setting to 5dp puts it above desktop icons (0dp) but below all menus
        agentView.elevation = 5f * resources.displayMetrics.density
        
        // Speech bubble should be above the agent
        speechBubbleView.elevation = 6f * resources.displayMetrics.density
        
        // Restore visibility state
        agentView.visibility = if (isRoverVisible()) View.VISIBLE else View.GONE
        
        Log.d("MainActivity", "ClippyView added to desktopContainer with elevation ${agentView.elevation}dp")
        
        // Restore saved position or set default position after adding to layout
        handler.post {
            // Use the same preference system as ClippyView for consistency
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedX = prefs.getFloat(KEY_AGENT_X, -1f)
            val savedY = prefs.getFloat(KEY_AGENT_Y, -1f)

            if (savedX >= 0 && savedY >= 0) {
                // Restore saved position using ClippyView's restore method
                agentView.restorePosition()
                Log.d("MainActivity", "Restored Agent to saved position: x=$savedX, y=$savedY")
            } else {
                // Set default position to avoid notifications bar (200px left, 300px top)
                val defaultX = 200f * resources.displayMetrics.density
                val defaultY = 300f * resources.displayMetrics.density
                agentView.x = defaultX
                agentView.y = defaultY
                // Save the default position so it persists
                agentView.savePosition()
                Log.d("MainActivity", "Set agent to default position and saved: x=$defaultX, y=$defaultY")
            }
        }
        
        // Verify it was added
        Log.d("MainActivity", "Desktop container child count: ${desktopContainer.childCount}")
        
        Log.d("MainActivity", "Rover setup completed")
    }
    
    private fun setupQuickGlanceWidget() {
        Log.d("MainActivity", "Setting up Quick Glance widget...")
        
        // Create and configure QuickGlanceWidget
        quickGlanceWidget = QuickGlanceWidget(this)
        Log.d("MainActivity", "QuickGlanceWidget created")

        // Set initial theme font based on current theme
        quickGlanceWidget.setThemeFont(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
        Log.d("MainActivity", "QuickGlanceWidget initial theme font set for: ${themeManager.getSelectedTheme()}")
        
        // Create layout params - widget will set its own width to 80% of screen
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        
        // Add to desktop container (this will render over icons but under menus)
        desktopContainer.addView(quickGlanceWidget, layoutParams)
        
        // Set elevation to render above desktop icons but below menus (same as rover)
        quickGlanceWidget.elevation = 5f * resources.displayMetrics.density
        
        Log.d("MainActivity", "QuickGlanceWidget added to desktopContainer with elevation ${quickGlanceWidget.elevation}dp")
        
        // Restore saved position or set default position after adding to layout
        handler.post {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedX = prefs.getFloat(KEY_WIDGET_X, -1f)
            val savedY = prefs.getFloat(KEY_WIDGET_Y, -1f)
            
            if (savedX >= 0 && savedY >= 0) {
                // Restore saved position
                quickGlanceWidget.restorePosition()
                Log.d("MainActivity", "Restored Quick Glance widget to saved position: x=$savedX, y=$savedY")
            } else {
                // Set default position (top-left area) only if no saved position
                val defaultX = 50 * resources.displayMetrics.density
                val defaultY = 100 * resources.displayMetrics.density
                quickGlanceWidget.x = defaultX
                quickGlanceWidget.y = defaultY
                Log.d("MainActivity", "Set Quick Glance widget to default position: x=$defaultX, y=$defaultY")
            }
            
            // Initialize data manager after positioning is set
            quickGlanceWidget.initializeDataManager()
            
            // Set permission request callback
            quickGlanceWidget.setPermissionRequestCallback {
                requestCalendarPermission()
            }
            
            // Set context menu callback
            quickGlanceWidget.setContextMenuCallback { screenX, screenY ->
                showQuickGlanceContextMenu(screenX, screenY)
            }
            
            // Set initial visibility based on saved preference
            quickGlanceWidget.visibility = if (isQuickGlanceVisible()) View.VISIBLE else View.GONE
        }
        
        Log.d("MainActivity", "Quick Glance widget setup completed")
    }
    
    private fun requestCalendarPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALENDAR) 
            != PackageManager.PERMISSION_GRANTED) {
            
            Log.d("MainActivity", "Requesting calendar permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_CALENDAR),
                CALENDAR_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d("MainActivity", "Calendar permission already granted")
        }
    }
    
    private fun requestNotificationPermissionIfNeeded() {
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            
            if (!alreadyRequested) {
                Log.d("MainActivity", "First launch - requesting notification permission")
                
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    
                    // Mark as requested so we don't ask again
                    prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
                    
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    Log.d("MainActivity", "Notification permission already granted")
                    prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
                }
            } else {
                Log.d("MainActivity", "Notification permission already requested previously")
            }
        } else {
            Log.d("MainActivity", "Android version < 13, notification permission not required")
        }
    }
    
    private fun showContextMenu(x: Float, y: Float) {
        Log.d("MainActivity", "showContextMenu called")
        vibrateShort()
        
        // Clear any previously selected icon
        selectedIcon?.setSelected(false)
        selectedIcon = null
        
        if (::contextMenu.isInitialized) {

            // Create desktop context menu items
            val menuItems = ContextMenuItems.getDesktopMenuItems(
                onRefresh = {
                    refreshEverything()
                },
                onChangeWallpaper = { createAndShowWallpaperDialog() },
                onOpenInternetExplorer = { showInternetExplorerDialog() },
                onNewFolder = { createNewFolder(x, y) }
            )
            
            // Show the menu
            contextMenu.showMenu(menuItems, x, y)
            isContextMenuVisible = true
            
            // Hide start menu if visible
            if (isStartMenuVisible) {
                hideStartMenu()
            }
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }

    private fun refreshEverything(){
        refreshDesktopIcons()
        refreshAppListManually()
        refreshWidgetData()
        checkForUpdates()
        Handler(Looper.getMainLooper()).postDelayed({
            playStartupSound()
        }, 1000)
    }

    private fun refreshWidgetData(){
        handleWeatherTempRefresh()

        // Also refresh the QuickGlanceWidget to update its weather display
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.refreshData()
        }

        val currentState = quickGlanceWidget.isShowCalendarEventsEnabled()
        quickGlanceWidget.setShowCalendarEvents(!currentState)
        quickGlanceWidget.setShowCalendarEvents(currentState)
    }

    private fun hideContextMenu() {
        if (::contextMenu.isInitialized) {
            contextMenu.hideMenu()
            isContextMenuVisible = false
        }
        
        // Clear any selected icon
        selectedIcon?.setSelected(false)
        selectedIcon = null
        
        // Recycle bin move mode is now handled by the same system as regular icons
    }
    
    private fun showStartMenuAppContextMenu(appInfo: AppInfo, x: Float, y: Float) {
        Log.d("MainActivity", "showStartMenuAppContextMenu called for ${appInfo.name}")
        vibrateShort()

        // Clear any previously selected icon
        selectedIcon?.setSelected(false)
        selectedIcon = null

        if (::contextMenu.isInitialized) {
            // Check if this is a system app
            val isSystemApp = isSystemApp(appInfo.packageName)

            // Create start menu app context menu items
            val isPinned = isAppPinned(appInfo.packageName)
            val menuItems = ContextMenuItems.getStartMenuAppMenuItems(
                onCreateShortcut = {
                    createDesktopShortcut(appInfo)
                    hideStartMenu()
                },
                onUninstall = {
                    uninstallApp(appInfo)
                },
                onProperties = {
                    openAppInfo(appInfo.packageName)
                    hideStartMenu()
                },
                onPinToggle = {
                    togglePinnedApp(appInfo.packageName)
                    // Immediate UI updates for unpinning or instant feedback
                    refreshCommandsList()
                    // Refresh apps list with slight delay to allow context menu to close first
                    Handler(Looper.getMainLooper()).postDelayed({
                        loadInstalledApps()
                    }, 50) // Small delay for better UX
                    // Keep start menu open for easier bulk pinning/unpinning
                },
                isPinned = isPinned,
                onSetSwipeRightApp = {
                    setSwipeRightApp(appInfo)
                    hideStartMenu()
                },
                onSetWeatherApp = {
                    setWeatherApp(appInfo)
                    hideStartMenu()
                },
                onChangeIcon = {
                    // Create a temporary desktop icon for the selection dialog
                    val tempIcon = DesktopIcon(
                        name = appInfo.name,
                        packageName = appInfo.packageName,
                        icon = appInfo.icon,
                        x = 0f,
                        y = 0f
                    )
                    val tempIconView = DesktopIconView(this).apply {
                        setDesktopIcon(tempIcon)
                    }
                    showIconSelectionDialog(tempIconView)
                    hideStartMenu()
                },
                isSystemApp = isSystemApp
            )
            
            // Show the menu
            contextMenu.showMenu(menuItems, x, y)
            isContextMenuVisible = true
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }
    
    fun showDesktopIconContextMenu(iconView: DesktopIconView, x: Float, y: Float) {
        Log.d("MainActivity", "showDesktopIconContextMenu called")
        vibrateShort()
        
        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }
        
        // Set this icon as selected
        selectedIcon = iconView
        iconView.setSelected(true)
        
        if (::contextMenu.isInitialized) {
            val icon = iconView.getDesktopIcon()

            // Check if this is a system app
            val isSystemApp = icon?.let { isSystemApp(it.packageName) } ?: false

            // Create desktop icon context menu items
            val menuItems = ContextMenuItems.getDesktopIconMenuItems(
                onOpen = {
                    icon?.let { desktopIcon ->
                        try {
                            // Check if this is a system app
                            if (isSystemApp(desktopIcon.packageName)) {
                                launchSystemApp(desktopIcon.packageName)
                            } else {
                                val launchIntent = packageManager.getLaunchIntentForPackage(desktopIcon.packageName)
                                launchIntent?.let { intent ->
                                    startActivity(intent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${desktopIcon.packageName}", e)
                        }
                    }
                },
                onMoveIcon = {
                    startIconMoveMode(iconView)
                },
                onChangeIcon = {
                    showIconSelectionDialog(iconView)
                },
                onDelete = {
                    deleteDesktopIcon(iconView)
                },
                onRename = {
                    showDesktopIconRenameDialog(iconView)
                },
                onProperties = {
                    icon?.let { desktopIcon ->
                        openAppInfo(desktopIcon.packageName)
                    }
                },
                onSetSwipeRightApp = {
                    icon?.let { desktopIcon ->
                        // Create AppInfo from DesktopIcon
                        val appInfo = AppInfo(
                            name = desktopIcon.name,
                            packageName = desktopIcon.packageName,
                            icon = desktopIcon.icon
                        )
                        setSwipeRightApp(appInfo)
                    }
                },
                onSetWeatherApp = {
                    icon?.let { desktopIcon ->
                        // Create AppInfo from DesktopIcon
                        val appInfo = AppInfo(
                            name = desktopIcon.name,
                            packageName = desktopIcon.packageName,
                            icon = desktopIcon.icon
                        )
                        setWeatherApp(appInfo)
                    }
                },
                isSystemApp = isSystemApp
            )
            
            // Show the menu
            contextMenu.showMenu(menuItems, x, y)
            isContextMenuVisible = true
            
            // Hide start menu if visible
            if (isStartMenuVisible) {
                hideStartMenu()
            }
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }
    
    fun showRecycleBinContextMenu(recycleBinView: RecycleBinView, x: Float, y: Float) {
        Log.d("MainActivity", "showRecycleBinContextMenu called")
        vibrateShort()

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        selectedIcon = null
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        if (::contextMenu.isInitialized) {
            // Create recycle bin context menu items
            val menuItems = ContextMenuItems.getRecycleBinMenuItems(
                onEmptyRecycleBin = {
                    playRecycleSound()
                },
                onMoveRecycleBin = {
                    startIconMoveMode(recycleBinView)
                },
                onHideRecycleBin = {
                    toggleRecycleBin() // Hide the recycle bin
                }
            )

            // Set up context menu click handler
            contextMenu.setOnItemClickListener {
                // Context menu will hide automatically
            }

            // Show the menu
            contextMenu.showMenu(menuItems, x, y)
            isContextMenuVisible = true
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }

    fun showFolderContextMenu(folderView: FolderView, x: Float, y: Float) {
        Log.d("MainActivity", "showFolderContextMenu called")
        vibrateShort()

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        selectedIcon = null
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        if (::contextMenu.isInitialized) {
            // Create folder context menu items
            val menuItems = ContextMenuItems.getFolderMenuItems(
                onMove = {
                    startIconMoveMode(folderView)
                },
                onRename = {
                    showFolderRenameDialog(folderView)
                },
                onDelete = {
                    deleteDesktopIcon(folderView)
                },
                onChangeIcon = {
                    showIconSelectionDialog(folderView)
                }
            )

            // Show the menu
            contextMenu.showMenu(menuItems, x, y)
            isContextMenuVisible = true
        } else {
            Log.d("MainActivity", "Context menu not initialized")
        }
    }

    private fun showFolderIconContextMenu(iconView: DesktopIconView, icon: DesktopIcon, parentDialog: WindowsDialog, touchX: Float, touchY: Float) {
        Log.d("MainActivity", "showFolderIconContextMenu called for ${icon.name}")
        vibrateShort()

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        // Set this icon as selected (shows blue highlight)
        selectedIcon = iconView
        iconView.setSelected(true)

        // Use the dialog's context menu (shared with MainActivity)
        if (::contextMenu.isInitialized) {
            // Create context menu items for icons inside folders (no "Move Icon" option)
            val menuItems = if (icon.type == IconType.FOLDER) {
                // For folders inside folders
                ContextMenuItems.getFolderMenuItems(
                    onMove = {
                        // Remove from current folder and put on desktop at first available grid position
                        icon.parentFolderId = null

                        // Find first available grid slot and set position
                        val firstAvailablePosition = findFirstAvailableGridSlot()
                        if (firstAvailablePosition != null) {
                            val (newX, newY) = getGridCoordinates(firstAvailablePosition.first, firstAvailablePosition.second)
                            icon.x = newX
                            icon.y = newY
                        } else {
                            // Fallback to default position if no grid slots available
                            icon.x = 100f
                            icon.y = 100f
                        }

                        hideContextMenu()
                        saveDesktopIcons()
                        // Close the parent folder window and reload desktop
                        floatingWindowManager.removeWindow(parentDialog)
                        refreshDesktopIcons()
                    },
                    onRename = {
                        showFolderIconRenameDialog(icon, parentDialog)
                    },
                    onDelete = {
                        hideContextMenu()
                        deleteFolderIcon(icon, parentDialog)
                    },
                    onChangeIcon = {
                        hideContextMenu()
                        // Change icon not supported for folders inside folders yet
                        showNotification("Change Icon", "Not available for items in folders")
                    }
                )
            } else {
                // For regular apps inside folders
                // Check if this is a system app
                val isSystemApp = isSystemApp(icon.packageName)

                ContextMenuItems.getFolderAppMenuItems(
                    onOpen = {
                        hideContextMenu()
                        try {
                            // Check if this is a system app
                            if (isSystemApp(icon.packageName)) {
                                launchSystemApp(icon.packageName)
                            } else {
                                val launchIntent = packageManager.getLaunchIntentForPackage(icon.packageName)
                                launchIntent?.let { intent ->
                                    startActivity(intent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${icon.packageName}", e)
                        }
                    },
                    onMoveToDesktop = {
                        // Remove from folder and place at first available grid position
                        icon.parentFolderId = null

                        // Find first available grid slot and set position
                        val firstAvailablePosition = findFirstAvailableGridSlot()
                        if (firstAvailablePosition != null) {
                            val (newX, newY) = getGridCoordinates(firstAvailablePosition.first, firstAvailablePosition.second)
                            icon.x = newX
                            icon.y = newY
                        } else {
                            // Fallback to default position if no grid slots available
                            icon.x = 100f
                            icon.y = 100f
                        }

                        hideContextMenu()
                        saveDesktopIcons()
                        // Close the parent folder window and reload desktop
                        floatingWindowManager.removeWindow(parentDialog)
                        refreshDesktopIcons()
                    },
                    onChangeIcon = {
                        showFolderIconSelectionDialog(icon, parentDialog)
                    },
                    onDelete = {
                        hideContextMenu()
                        deleteFolderIcon(icon, parentDialog)
                    },
                    onRename = {
                        showFolderIconRenameDialog(icon, parentDialog)
                    },
                    onProperties = {
                        hideContextMenu()
                        openAppInfo(icon.packageName)
                    },
                    onSetSwipeRightApp = {
                        hideContextMenu()
                        // Create AppInfo from DesktopIcon
                        val appInfo = AppInfo(
                            name = icon.name,
                            packageName = icon.packageName,
                            icon = icon.icon
                        )
                        setSwipeRightApp(appInfo)
                    },
                    onSetWeatherApp = {
                        hideContextMenu()
                        // Create AppInfo from DesktopIcon
                        val appInfo = AppInfo(
                            name = icon.name,
                            packageName = icon.packageName,
                            icon = icon.icon
                        )
                        setWeatherApp(appInfo)
                    },
                    isSystemApp = isSystemApp
                )
            }

            // Convert screen touch coordinates to dialog-relative coordinates
            val overlayLocation = IntArray(2)
            parentDialog.getLocationOnScreen(overlayLocation)

            // Calculate position relative to the dialog's overlay
            val x = touchX - overlayLocation[0]
            val y = touchY - overlayLocation[1]

            // Show the menu using the dialog's helper method
            parentDialog.showContextMenu(menuItems, x, y)
            isContextMenuVisible = true
        } else {
            Log.d("MainActivity", "Dialog context menu not initialized")
        }
    }

    private fun showFolderIconRenameDialog(icon: DesktopIcon, parentDialog: WindowsDialog) {
        val currentName = getCustomOrOriginalName(icon.packageName, icon.name)
        val hintText = if (icon.type == IconType.FOLDER) "Folder name" else "Icon name"

        showRenameDialog(
            title = "Rename",
            initialText = currentName,
            hint = hintText
        ) { newName ->
            if (icon.type == IconType.FOLDER) {
                // Update folder name directly in desktopIcon
                val iconIndex = desktopIcons.indexOfFirst { it.id == icon.id }
                if (iconIndex >= 0) {
                    val updatedIcon = icon.copy(name = newName)
                    desktopIcons[iconIndex] = updatedIcon
                }
            } else {
                // Save custom name for app
                customNameMappings[icon.packageName] = newName
                saveCustomNameMappings()
            }

            // Save and reload
            saveDesktopIcons()
            floatingWindowManager.removeWindow(parentDialog)
            // Re-open the folder to show updated name
            val folderView = desktopIconViews.find {
                (it as? FolderView)?.getDesktopIcon()?.id == icon.parentFolderId
            } as? FolderView
            if (folderView != null) {
                showFolderWindow(folderView)
            }
        }
    }

    private fun showFolderIconSelectionDialog(icon: DesktopIcon, parentDialog: WindowsDialog) {
        showIconSelectionDialog(iconView = null, folderIcon = icon, folderDialog = parentDialog)
    }

    private fun deleteFolderIcon(icon: DesktopIcon, parentDialog: WindowsDialog) {
        playRecycleSound()

        // If it's a folder, delete all icons inside it recursively
        if (icon.type == IconType.FOLDER) {
            deleteFolderAndContents(icon.id)
        } else {
            // Remove the icon
            desktopIcons.removeAll { it.id == icon.id }
        }

        saveDesktopIcons()

        // Refresh the folder GridLayout to reflect the deletion
        refreshFolderGridLayout(parentDialog)

    }

    private fun refreshFolderGridLayout(parentDialog: WindowsDialog) {
        Log.d("MainActivity", "refreshFolderGridLayout called")

        // Get the content view from the dialog
        val contentArea = parentDialog.getContentArea()
        if (contentArea.isEmpty()) {
            Log.e("MainActivity", "Content area has no children")
            return
        }

        val contentView = contentArea.getChildAt(0)

        // Find the GridView directly using R.id
        val folderIconsGrid = contentView.findViewById<android.widget.GridView>(R.id.folder_icons_grid)

        if (folderIconsGrid == null) {
            Log.e("MainActivity", "Could not find folder_icons_grid")
            return
        }

        Log.d("MainActivity", "Found GridView")

        // Get the theme
        val isWindows98 = themeManager.getSelectedTheme() is AppTheme.WindowsClassic

        // Get folder ID from the dialog's windowIdentifier (format: "folder:folderId")
        val windowId = parentDialog.windowIdentifier
        if (windowId == null || !windowId.startsWith("folder:")) {
            Log.e("MainActivity", "Invalid or missing window identifier: $windowId")
            return
        }

        val folderId = windowId.removePrefix("folder:")
        Log.d("MainActivity", "Refreshing folder with ID: $folderId")

        // Find the folder icon by ID (more reliable than name matching)
        val folderIcon = desktopIcons.firstOrNull {
            it.type == IconType.FOLDER && it.id == folderId
        }

        if (folderIcon == null) {
            Log.e("MainActivity", "Could not find folder icon for ID: $folderId")
            return
        }

        // Get all icons that belong to this folder
        val iconsInFolder = desktopIcons
            .filter { it.parentFolderId == folderIcon.id }
            .sortedBy { getCustomOrOriginalName(it.packageName, it.name).lowercase() }

        Log.d("MainActivity", "Found ${iconsInFolder.size} icons in folder")

        // Create and set new adapter
        val adapter = FolderIconAdapter(
            this,
            iconsInFolder,
            isWindows98,
            onIconClick = { icon, iconView ->
                when (icon.type) {
                    IconType.APP -> {
                        try {
                            if (isSystemApp(icon.packageName)) {
                                launchSystemApp(icon.packageName)
                            } else {
                                val launchIntent = packageManager.getLaunchIntentForPackage(icon.packageName)
                                launchIntent?.let { intent ->
                                    startActivity(intent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${icon.packageName}", e)
                        }
                    }
                    IconType.FOLDER -> {
                        showFolderWindow(iconView as FolderView)
                    }
                    else -> {}
                }
            },
            onIconLongClick = { icon, iconView, touchX, touchY ->
                showFolderIconContextMenu(iconView, icon, parentDialog, touchX, touchY)
                true
            }
        )

        // Ensure we're on the UI thread and refresh the GridView
        runOnUiThread {
            folderIconsGrid.adapter = adapter

            // Force the GridView to refresh its views
            adapter.notifyDataSetChanged()
            folderIconsGrid.invalidateViews()
            folderIconsGrid.requestLayout()

            // Update item count
            updateFolderItemCount(contentView, iconsInFolder.size)

            Log.d("MainActivity", "GridView refresh complete - ${iconsInFolder.size} icons")
        }
    }

    private fun updateFolderItemCount(contentView: View, count: Int) {
        val itemCountTextView = contentView.findViewById<TextView>(R.id.explorer_number_of_items)
        if (itemCountTextView != null) {
            val itemText = if (count == 1) "1 item" else "$count items"
            itemCountTextView.text = itemText
            Log.d("MainActivity", "Updated folder item count: $itemText")
        } else {
            Log.w("MainActivity", "Could not find explorer_number_of_items TextView")
        }
    }

    private fun deleteFolderAndContents(folderId: String) {
        Log.d("MainActivity", "Deleting folder and all contents: $folderId")

        // Find all icons inside this folder
        val iconsToDelete = desktopIcons.filter { it.parentFolderId == folderId }

        Log.d("MainActivity", "Found ${iconsToDelete.size} icons to delete")

        // Recursively delete any nested folders and their contents
        iconsToDelete.forEach { icon ->
            if (icon.type == IconType.FOLDER) {
                deleteFolderAndContents(icon.id)
            }
        }

        // Remove all icons that were in this folder
        desktopIcons.removeAll { it.parentFolderId == folderId }

        // Remove the folder itself
        desktopIcons.removeAll { it.id == folderId }

        Log.d("MainActivity", "Folder deletion complete")
    }

    private fun playRecycleSound() {
        playSound(R.raw.recycle)
    }
    
    

    private fun startIconMoveMode(iconView: DesktopIconView) {
        // Clear any previously selected icon (from context menu)
        selectedIcon?.setSelected(false)
        selectedIcon = null
        
        iconInMoveMode = iconView
        iconView.setSelected(true) // Use blue background selection effect
        iconView.setMoveMode(true)
        hideContextMenu()
    }
    
    fun exitIconMoveMode() {
        iconInMoveMode?.let { iconView ->
            iconView.setSelected(false) // Remove blue background selection effect
            iconView.setMoveMode(false)
        }
        iconInMoveMode = null
    }
    
    private fun showIconSelectionDialog(
        iconView: DesktopIconView? = null,
        folderIcon: DesktopIcon? = null,
        folderDialog: WindowsDialog? = null
    ) {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Change Icon")

        // Get current theme for content layout selection
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"

        // Create and set the content with theme-appropriate layout
        val contentLayoutResId = when (selectedTheme) {
            "Windows Classic" -> {
                R.layout.icon_selection_content
            }
            "Windows Vista" -> {
                R.layout.icon_selection_content_vista
            }
            else -> {
                R.layout.icon_selection_content_xp
            }
        }
        val contentView = layoutInflater.inflate(contentLayoutResId, null)
        windowsDialog.setContentView(contentView)

        val recyclerView = contentView.findViewById<RecyclerView>(R.id.icons_recycler_view)
        val iconTypeButtons = contentView.findViewById<LinearLayout>(R.id.icon_type_buttons)
        val btnWindows98 = contentView.findViewById<TextView>(R.id.btn_windows_98)
        val btnWindowsXP = contentView.findViewById<TextView>(R.id.btn_windows_xp)
        val btnWindowsVista = contentView.findViewById<TextView>(R.id.btn_windows_vista)
        val btnPrograms = contentView.findViewById<TextView>(R.id.btn_programs)

        // Show icon type buttons for desktop icon selection
        iconTypeButtons.visibility = View.VISIBLE

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Window is already minimized by minimize() method
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        // Set up grid layout (4 columns)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        // Create initial adapter with default icon only
        val initialIcons = mutableListOf<CustomIconItem>()

        // Determine which icon we're working with
        val desktopIcon = iconView?.getDesktopIcon() ?: folderIcon

        // Add default icon immediately
        desktopIcon?.let { icon ->
            val defaultIcon = loadAppIcon(icon.packageName)
            if (defaultIcon != null) {
                val processedIcon = createSquareDrawable(defaultIcon)
                initialIcons.add(CustomIconItem(
                    name = "Default",
                    drawable = processedIcon,
                    isDefault = true,
                    filePath = "default"
                ))
            }
        }

        // Create adapter that can be updated dynamically
        val adapter = CustomIconAdapter(initialIcons) { chosenIcon ->
            val iconPath = chosenIcon.filePath ?: "default"
            val packageName = desktopIcon?.packageName ?: ""

            // Apply the custom icon immediately
                    try {
                        val customDrawable = if (iconPath == "default") {
                            // Use default app icon
                            loadAppIcon(packageName)
                        } else {
                            // Load custom icon from assets
                            val inputStream = assets.open(iconPath)
                            val drawable = Drawable.createFromStream(inputStream, iconPath)
                            inputStream.close()
                            drawable
                        }

                        if (customDrawable != null) {
                            // Save the custom icon mapping first
                            if (iconPath == "default") {
                                customIconMappings.remove(packageName)
                            } else {
                                // Save the full path - each theme has different icons
                                customIconMappings[packageName] = iconPath
                                Log.d("MainActivity", "Saving custom icon mapping: $packageName -> $iconPath")
                            }
                            saveCustomIconMappings()

                            // Clear cached app list so it reloads with new icon
                            cachedAppList = null

                            // Now use the same loading process as refresh to get properly sized icon
                            val properlyScaledIcon = getAppIcon(packageName) ?: customDrawable

                            // Update based on which type of icon this is
                            if (iconView != null) {
                                // Update desktop icon view
                                iconView.setIconDrawable(properlyScaledIcon)
                                iconView.getDesktopIcon()?.icon = properlyScaledIcon

                                // Clear the selection state immediately
                                iconView.setSelected(false)
                                if (selectedIcon == iconView) {
                                    selectedIcon = null
                                }
                            } else if (folderIcon != null && folderDialog != null) {
                                // Update icon in desktopIcons list for folder item
                                val iconIndex = desktopIcons.indexOfFirst { it.id == folderIcon.id }
                                if (iconIndex >= 0) {
                                    desktopIcons[iconIndex] = folderIcon.copy(icon = properlyScaledIcon)
                                }

                                // Save desktop icons to persist the change
                                saveDesktopIcons()

                                // Refresh the folder view to show the new icon
                                refreshFolderGridLayout(folderDialog)

                                // Refresh app list to update start menu, but don't refresh desktop icons
                                // (that would close the folder window)
                                refreshAppListManually()
                            }

                            // Save desktop icons to persist the change (for desktop icon view case)
                            if (iconView != null) {
                                saveDesktopIcons()
                                refreshDesktopIcons()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error applying custom icon: ${e.message}")
                    }

                    // Close the window immediately
            floatingWindowManager.removeWindow(windowsDialog)
        }
        recyclerView.adapter = adapter

        // Track current icon type and loading thread
        var currentLoadingThread: Thread? = null

        // Helper function to clear all button selections
        fun clearButtonSelections() {
            btnWindows98.isSelected = false
            btnWindowsXP.isSelected = false
            btnWindowsVista.isSelected = false
            btnPrograms.isSelected = false
        }

        // Set up button click listeners
        btnWindows98.setOnClickListener {
            // Cancel any running loading thread
            currentLoadingThread?.interrupt()
            playClickSound()
            clearButtonSelections()
            btnWindows98.isSelected = true
            // Clear current icons and reload from 98 folder
            adapter.clearIcons()
            currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons_98")
        }

        btnWindowsXP.setOnClickListener {
            // Cancel any running loading thread
            currentLoadingThread?.interrupt()
            playClickSound()
            clearButtonSelections()
            btnWindowsXP.isSelected = true
            // Clear current icons and reload from XP folder
            adapter.clearIcons()
            currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons")
        }


        btnWindowsVista.setOnClickListener {
            // Cancel any running loading thread
            currentLoadingThread?.interrupt()
            playClickSound()
            clearButtonSelections()
            btnWindowsVista.isSelected = true
            // Clear current icons and reload from XP folder
            adapter.clearIcons()
            currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons_vista")
        }

        btnPrograms.setOnClickListener {
            // Cancel any running loading thread
            currentLoadingThread?.interrupt()
            playClickSound()
            clearButtonSelections()
            btnPrograms.isSelected = true
            // Clear current icons and reload from programs folder
            adapter.clearIcons()
            currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons_programs")
        }

        // Set initial button state based on current theme and load initial icons
        when (selectedTheme) {
            "Windows Classic" -> {
                btnWindows98.isSelected = true
                currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons_98")
            }
            "Windows Vista" -> {
                btnWindowsVista.isSelected = true
                currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons_vista")
            }
            else -> {
                btnWindowsXP.isSelected = true
                currentLoadingThread = loadCustomIconsLazy(adapter, "custom_icons")
            }
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    private fun showUserIconSelectionDialog() {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowUserIconSelectionDialog()
        }
    }

    private fun createAndShowUserIconSelectionDialog() {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Select User Picture")

        // Get current theme for content layout selection
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"

        // Create and set the content with theme-appropriate layout
        val contentLayoutResId = if (selectedTheme == "Windows Classic") {
            R.layout.icon_selection_content
        } else {
            R.layout.icon_selection_content_xp
        }
        val contentView = layoutInflater.inflate(contentLayoutResId, null)
        windowsDialog.setContentView(contentView)

        val recyclerView = contentView.findViewById<RecyclerView>(R.id.icons_recycler_view)
        val iconTypeButtons = contentView.findViewById<LinearLayout>(R.id.icon_type_buttons)

        // Hide icon type buttons for user profile selection (keep them hidden)
        iconTypeButtons.visibility = View.GONE

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Window is already minimized by minimize() method
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        // Set up grid layout (4 columns)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        // Create adapter for user icons
        val userIcons = mutableListOf<CustomIconItem>()

        // Add default user icon
        val defaultIcon = ContextCompat.getDrawable(this, R.drawable.user)
        if (defaultIcon != null) {
            userIcons.add(CustomIconItem(
                name = "Default",
                drawable = defaultIcon,
                isDefault = true,
                filePath = "default"
            ))
        }

        // Create adapter that handles icon selection
        val adapter = CustomIconAdapter(userIcons) { chosenIcon ->
            val iconPath = chosenIcon.filePath ?: "default"
            prefs.edit {
                putString("user_icon_path", iconPath)
            }

            // Update profile picture display
            updateProfilePicture()

            // Close the window
            floatingWindowManager.removeWindow(windowsDialog)
        }
        recyclerView.adapter = adapter

        // Load user icons from assets in background
        Thread {
            try {
                val assetFiles = assets.list("xp_user_icons") ?: arrayOf()
                for (fileName in assetFiles) {
                    if (fileName.endsWith(".png", ignoreCase = true)) {
                        try {
                            val inputStream = assets.open("xp_user_icons/$fileName")
                            val drawable = Drawable.createFromStream(inputStream, fileName)
                            inputStream.close()

                            if (drawable != null) {
                                val iconName = fileName.substringBeforeLast(".")
                                val iconItem = CustomIconItem(
                                    name = iconName,
                                    drawable = drawable,
                                    isDefault = false,
                                    filePath = "xp_user_icons/$fileName"
                                )

                                runOnUiThread {
                                    userIcons.add(iconItem)
                                    adapter.notifyItemInserted(userIcons.size - 1)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error loading user icon $fileName: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading user icons: ${e.message}")
            }
        }.start()

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showDesktopIconRenameDialog(iconView: DesktopIconView) {
        val desktopIcon = iconView.getDesktopIcon() ?: return
        val currentDisplayName = getCustomOrOriginalName(desktopIcon.packageName, desktopIcon.name)

        showRenameDialog(
            title = "Rename",
            initialText = currentDisplayName,
            hint = "Icon name"
        ) { newName ->
            if (newName.isEmpty()) {
                // Remove custom name mapping to revert to original
                customNameMappings.remove(desktopIcon.packageName)
            } else {
                // Save custom name
                customNameMappings[desktopIcon.packageName] = newName
            }

            // Save the changes
            saveCustomNameMappings()

            // Update the icon display immediately
            iconView.setDesktopIcon(desktopIcon)

            // Clear selection
            iconView.setSelected(false)
            if (selectedIcon == iconView) {
                selectedIcon = null
            }
        }
    }

    private fun showFolderRenameDialog(folderView: FolderView) {
        val desktopIcon = folderView.getDesktopIcon() ?: return
        val currentName = desktopIcon.name

        showRenameDialog(
            title = "Rename Folder",
            initialText = currentName,
            hint = "Folder name"
        ) { newName ->
            // Find the desktop icon in our list and update its name
            val iconIndex = desktopIcons.indexOfFirst { it.id == desktopIcon.id }
            if (iconIndex >= 0) {
                // Create a new DesktopIcon with the updated name
                val updatedIcon = desktopIcon.copy(name = newName)
                desktopIcons[iconIndex] = updatedIcon

                // Update the view with the new icon
                folderView.setDesktopIcon(updatedIcon)

                // Save the changes
                saveDesktopIcons()
            }

            // Clear selection
            folderView.setSelected(false)
            if (selectedIcon == folderView) {
                selectedIcon = null
            }
        }
    }

    fun showFolderWindow(folderView: FolderView) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowFolderWindow(folderView)
        }
    }

    private fun createAndShowFolderWindow(folderView: FolderView) {
        val desktopIcon = folderView.getDesktopIcon() ?: return
        val folderName = desktopIcon.name
        // Replace newlines (both literal \n and actual newlines) with spaces for display in title bar and address bar
        val folderNameDisplay = folderName.replace("\\n", " ").replace("\n", " ")

        // Check if this folder is already open and bring it to front if so
        val folderId = "folder:${desktopIcon.id}"
        if (floatingWindowManager.findAndFocusWindow(folderId)) {
            Log.d("MainActivity", "Brought existing folder window to front: $folderNameDisplay")
            setCursorNormal()
            return
        }

        // Get current theme
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = folderId  // Set identifier for tracking
        windowsDialog.setTitle(folderNameDisplay)

        // Use folder icon as taskbar icon
        val taskbarIcon = when (selectedTheme) {
            "Windows Classic" -> {
                R.drawable.folder_98
            }
            "Windows Vista" -> {
                R.drawable.folder_vista
            }
            else -> {
                R.drawable.folder_xp
            }
        }
        windowsDialog.setTaskbarIcon(taskbarIcon)

        // Inflate the windows explorer content
        val currentTheme = themeManager.getSelectedTheme()
        val explorerLayoutRes = themeManager.getWindowsExplorerLayoutRes(currentTheme)
        val contentView = layoutInflater.inflate(explorerLayoutRes, null)

        windowsDialog.setContentView(contentView)

        // Set window size to match the layout dimensions (358dp x 244dp) plus window chrome
        // Content: 358x244, Title bar + Borders: +30dp height, +4dp width (same as IE)

        windowsDialog.setWindowSizePercentage(90f, 30f)
        windowsDialog.setMaximizable(true)
        if (selectedTheme == "Windows Classic") {
            val folderNameLarge = contentView.findViewById<TextView>(R.id.folder_name_large)
            val folderIconLarge = contentView.findViewById<ImageView>(R.id.folder_icon_large)
            folderNameLarge.text = folderNameDisplay
            folderIconLarge.setImageDrawable(desktopIcon.icon)
        }


        // Get references to the folder name and icon views
        val folderNameSmall = contentView.findViewById<TextView>(R.id.folder_name_small)
        val folderIconSmall = contentView.findViewById<ImageView>(R.id.folder_icon_small)

        // Set the folder name in both text views (address bar)
        folderNameSmall?.text = folderNameDisplay
        // Set the folder icon in both image views
        folderIconSmall?.setImageDrawable(desktopIcon.icon)

        // Get the GridView for folder contents
        val folderIconsGrid = contentView.findViewById<android.widget.GridView>(R.id.folder_icons_grid)

        // Get all icons that belong to this folder, sorted by name
        val iconsInFolder = desktopIcons
            .filter { it.parentFolderId == desktopIcon.id }
            .sortedBy { getCustomOrOriginalName(it.packageName, it.name).lowercase() }


        // Pre-calculate theme-dependent values
        val isWindows98 = selectedTheme == "Windows Classic"

        // Create and set adapter
        val adapter = FolderIconAdapter(
            this,
            iconsInFolder,
            isWindows98,
            onIconClick = { icon, iconView ->
                when (icon.type) {
                    IconType.APP -> {
                        try {
                            if (isSystemApp(icon.packageName)) {
                                launchSystemApp(icon.packageName)
                            } else {
                                val launchIntent = packageManager.getLaunchIntentForPackage(icon.packageName)
                                launchIntent?.let { intent ->
                                    startActivity(intent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${icon.packageName}", e)
                        }
                    }
                    IconType.FOLDER -> {
                        showFolderWindow(iconView as FolderView)
                    }
                    else -> {}
                }
            },
            onIconLongClick = { icon, iconView, touchX, touchY ->
                showFolderIconContextMenu(iconView, icon, windowsDialog, touchX, touchY)
                true
            }
        )
        folderIconsGrid.adapter = adapter

        // Update item count
        updateFolderItemCount(contentView, iconsInFolder.size)

        // Set context menu reference and show as floating window immediately
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            hideContextMenu()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app info for package: $packageName", e)
        }
    }

    private fun loadCustomIconsLazy(adapter: CustomIconAdapter, customIconFolder: String = "custom_icons"): Thread {
        val thread = Thread {
            try {
                // Load custom icons from assets progressively
                val assetManager = assets
                val customIconFiles = assetManager.list(customIconFolder) ?: arrayOf()

                // First, get all file names, filter and sort them completely (case-insensitive)
                val sortedFiles = customIconFiles
                    .filter { it.endsWith(".webp", ignoreCase = true) && it != "README.txt" }
                    .sortedBy { it.lowercase() }

                // Then process the sorted files in smaller batches and update UI immediately
                val batchSize = 20 // Smaller batch size for faster UI updates
                
                for (i in sortedFiles.indices step batchSize) {
                    // Check if thread was interrupted
                    if (Thread.currentThread().isInterrupted) {
                        return@Thread
                    }

                    val batch = sortedFiles.subList(i, minOf(i + batchSize, sortedFiles.size))
                    val batchIcons = mutableListOf<CustomIconItem>()

                    for (fileName in batch) {
                        // Check if thread was interrupted
                        if (Thread.currentThread().isInterrupted) {
                            return@Thread
                        }
                        try {
                            
                            val inputStream = assetManager.open("$customIconFolder/$fileName")
                            val originalDrawable = Drawable.createFromStream(inputStream, fileName)
                            inputStream.close()

                            if (originalDrawable != null) {
                                val squareDrawable = createSquareDrawable(originalDrawable)
                                batchIcons.add(CustomIconItem(
                                    name = fileName.substringBeforeLast("."),
                                    drawable = squareDrawable,
                                    isDefault = false,
                                    filePath = "$customIconFolder/$fileName"
                                ))
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to load icon: $fileName", e)
                        }
                    }
                    
                    // Update UI on main thread with this batch
                    if (batchIcons.isNotEmpty()) {
                        // Check if thread was interrupted before UI update
                        if (Thread.currentThread().isInterrupted) {
                            return@Thread
                        }
                        runOnUiThread {
                            adapter.addIcons(batchIcons)
                        }
                    }

                    // Small delay to prevent overwhelming the system
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to load custom icons lazily", e)
            }
        }
        thread.start()
        return thread
    }

    private fun saveCustomIconMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Use the reliable getCustomIconsPath() method to determine current theme
        val expectedIconsPath = getCustomIconsPath()
        val (currentTheme, themeKey) = when (expectedIconsPath) {
            "custom_icons_98" -> "Windows Classic" to KEY_CUSTOM_ICONS_98
            "custom_icons" -> "Windows XP" to KEY_CUSTOM_ICONS_XP
            "custom_icons_vista" -> "Windows Vista" to KEY_CUSTOM_ICONS_VISTA
            else -> "Windows XP" to KEY_CUSTOM_ICONS_XP // fallback
        }

        val jsonString = customIconMappings.entries.joinToString(";") { "${it.key}:${it.value}" }
        Log.d("MainActivity", "Saving custom icons to $themeKey for theme $currentTheme: $jsonString")

        prefs.edit {
            putString(themeKey, jsonString)
        }
    }

    // Migrate all settings from old separate SharedPreferences files to current PREFS_NAME
    // This function can be deleted in a future version after users have migrated
    private fun migrateCustomMappingsIfNeeded() {
        val newPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // List of old SharedPreferences files to migrate from
        val oldPrefsToMigrate = listOf(
            "launcher_prefs",
            "agent_settings",
            "quick_glance_position",
            "GokiXP",  // Internet Explorer homepage
            "msn_thread_prefs"  // MSN message read status
        )

        var migrationNeeded = false

        // Check if any old prefs files have data that's not in the new prefs
        oldPrefsToMigrate.forEach { oldPrefsName ->
            val oldPrefs = getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty()) {
                // Check if any key from old prefs is missing in new prefs
                oldPrefs.all.keys.forEach { key ->
                    if (!newPrefs.contains(key)) {
                        migrationNeeded = true
                    }
                }
            }
        }

        if (migrationNeeded) {
            Log.d("MainActivity", "Migrating settings from old SharedPreferences files to $PREFS_NAME")
            newPrefs.edit {
                oldPrefsToMigrate.forEach { oldPrefsName ->
                    val oldPrefs = getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
                    val allOldPrefs = oldPrefs.all

                    if (allOldPrefs.isNotEmpty()) {
                        Log.d("MainActivity", "Migrating from $oldPrefsName (${allOldPrefs.size} keys)")

                        allOldPrefs.forEach { (key, value) ->
                            if (!newPrefs.contains(key)) {
                                when (value) {
                                    is String -> {
                                        putString(key, value)
                                        Log.d("MainActivity", "  Migrated String: $key")
                                    }
                                    is Boolean -> {
                                        putBoolean(key, value)
                                        Log.d("MainActivity", "  Migrated Boolean: $key = $value")
                                    }
                                    is Int -> {
                                        putInt(key, value)
                                        Log.d("MainActivity", "  Migrated Int: $key = $value")
                                    }
                                    is Long -> {
                                        putLong(key, value)
                                        Log.d("MainActivity", "  Migrated Long: $key = $value")
                                    }
                                    is Float -> {
                                        putFloat(key, value)
                                        Log.d("MainActivity", "  Migrated Float: $key = $value")
                                    }
                                    else -> {
                                        Log.w("MainActivity", "  Unknown type for key $key: ${value?.javaClass?.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.d("MainActivity", "Migration completed successfully")
        }
    }

    private fun loadCustomIconMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Use the reliable getCustomIconsPath() method to determine current theme
        val expectedIconsPath = getCustomIconsPath()
        val (currentTheme, themeKey) = when (expectedIconsPath) {
            "custom_icons_98" -> "Windows Classic" to KEY_CUSTOM_ICONS_98
            "custom_icons" -> "Windows XP" to KEY_CUSTOM_ICONS_XP
            "custom_icons_vista" -> "Windows Vista" to KEY_CUSTOM_ICONS_VISTA
            else -> "Windows XP" to KEY_CUSTOM_ICONS_XP // fallback
        }

        Log.d("MainActivity", "Loading custom icon mappings for theme: $currentTheme, key: $themeKey")

        // Try to load theme-specific mappings first
        val jsonString = prefs.getString(themeKey, "") ?: ""
        Log.d("MainActivity", "Theme-specific mappings found: ${jsonString.isNotEmpty()}")

        // TEMPORARILY DISABLED: If no theme-specific mapping exists, try to migrate from legacy storage
        // This migration might be causing cross-theme pollution
        /*
        if (jsonString.isEmpty()) {
            val legacyString = prefs.getString(KEY_CUSTOM_ICONS, "") ?: ""
            Log.d("MainActivity", "Legacy mappings found: ${legacyString.isNotEmpty()}")
            if (legacyString.isNotEmpty()) {
                // Migrate legacy mappings to current theme
                jsonString = legacyString

                // Save to theme-specific key
                prefs.edit {
                    putString(themeKey, legacyString)
                    // Don't remove legacy key yet in case both themes were used
                }
                Log.d("MainActivity", "Migrated legacy mappings to $themeKey")
            }
        }
        */

        customIconMappings.clear()
        if (jsonString.isNotEmpty()) {
            jsonString.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    customIconMappings[parts[0]] = parts[1]
                }
            }
        }
        Log.d("MainActivity", "Loaded ${customIconMappings.size} custom icon mappings: ${customIconMappings.keys}")
    }
    
    private fun saveCustomNameMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            // Convert map to JSON string (simple approach)
            val jsonString =
                customNameMappings.entries.joinToString(";") { "${it.key}:${it.value.replace(":", "&#58;").replace(";", "&#59;")}" }
            putString(KEY_CUSTOM_NAMES, jsonString)
        }
    }
    
    private fun loadCustomNameMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CUSTOM_NAMES, "") ?: ""
        
        customNameMappings.clear()
        if (jsonString.isNotEmpty()) {
            jsonString.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size >= 2) {
                    val packageName = parts[0]
                    val customName = parts.drop(1).joinToString(":").replace("&#58;", ":").replace("&#59;", ";")
                    customNameMappings[packageName] = customName
                }
            }
        }
    }
    
    fun getCustomOrOriginalName(packageName: String, originalName: String): String {
        return customNameMappings[packageName] ?: originalName
    }
    
    /**
     * Central function to get app icon - returns custom icon if available, otherwise default icon
     * This function handles theme awareness and should be used from all places (desktop, command list, app list)
     */
    fun getAppIcon(packageName: String, skipCustom: Boolean = false): Drawable? {

        if(!skipCustom) {
            // First check if there's a custom icon mapping for current theme
            val customIconPath = customIconMappings[packageName]
            if (customIconPath != null) {
                try {
                    val inputStream = assets.open(customIconPath)
                    val drawable = Drawable.createFromStream(inputStream, customIconPath)
                    inputStream.close()
                    if (drawable != null) {
                        // Create a square drawable with consistent sizing and cache it
                        val cacheKey = "custom_${packageName}_${customIconPath}"
                        return createSquareDrawable(drawable, cacheKey)
                    }
                } catch (e: Exception) {
                    Log.w(
                        "MainActivity",
                        "Failed to load custom icon for $packageName, falling back to default",
                        e
                    )
                    // Remove invalid mapping
                    customIconMappings.remove(packageName)
                    saveCustomIconMappings()
                }
            }
        }

        // Fall back to default app icon with caching
        return loadAppIcon(packageName)?.let {
            val cacheKey = "app_${packageName}"
            createSquareDrawable(it, cacheKey)
        }
    }
    
    private fun createSquareDrawable(originalDrawable: Drawable, cacheKey: String? = null): Drawable {
        val iconSize = 288 // Standard size for desktop icons

        // Check cache first if we have a cache key
        if (cacheKey != null) {
            val cachedBitmap = iconBitmapCache.get(cacheKey)
            if (cachedBitmap != null) {
                return cachedBitmap.toDrawable(resources)
            }
        }

        // Create a bitmap with square dimensions
        val bitmap = createBitmap(iconSize, iconSize)
        val canvas = Canvas(bitmap)

        // Calculate scaling to fit the drawable in the square while maintaining aspect ratio
        val originalWidth = originalDrawable.intrinsicWidth
        val originalHeight = originalDrawable.intrinsicHeight

        val scale = if (originalWidth > 0 && originalHeight > 0) {
            minOf(iconSize.toFloat() / originalWidth, iconSize.toFloat() / originalHeight)
        } else {
            1f
        }

        val scaledWidth = (originalWidth * scale).toInt()
        val scaledHeight = (originalHeight * scale).toInt()

        // Center the drawable in the square
        val left = (iconSize - scaledWidth) / 2
        val top = (iconSize - scaledHeight) / 2
        val right = left + scaledWidth
        val bottom = top + scaledHeight

        // Set bounds and draw
        originalDrawable.setBounds(left, top, right, bottom)
        originalDrawable.draw(canvas)

        // Cache the bitmap if we have a cache key
        if (cacheKey != null) {
            iconBitmapCache.put(cacheKey, bitmap)
        }

        // Create drawable from bitmap
        return bitmap.toDrawable(resources)
    }
    
    private fun createDesktopShortcut(appInfo: AppInfo) {
        // Find the first available grid slot (ignoring tap location)
        val firstAvailablePosition = findFirstAvailableGridSlot()
        if (firstAvailablePosition != null) {
            val (newX, newY) = getGridCoordinates(firstAvailablePosition.first, firstAvailablePosition.second)
            addDesktopIcon(appInfo, newX, newY)
        } else {
            // Fallback to default position if no grid slots available
            addDesktopIcon(appInfo, 100f, 100f)
        }
    }
    
    private fun findFirstAvailableGridSlot(): Pair<Int, Int>? {
        // Get all currently occupied positions
        val occupiedPositions = mutableSetOf<Pair<Int, Int>>()
        
        // Add positions for all existing desktop icons (including recycle bin)
        desktopIconViews.forEach { iconView ->
            val centerX = iconView.x + iconView.width / 2
            val centerY = iconView.y + iconView.height / 2
            val (cellWidth, cellHeight) = getGridDimensions()
            
            // Account for top margin (status bar + padding)
            val topMarginPx = 80f * resources.displayMetrics.density
            val adjustedCenterY = centerY - topMarginPx
            
            val col = (centerX / cellWidth).coerceIn(0f, (GRID_COLUMNS - 1).toFloat()).toInt()
            val row = (adjustedCenterY / cellHeight).coerceIn(0f, (GRID_ROWS - 1).toFloat()).toInt()
            
            occupiedPositions.add(Pair(row, col))
        }
        
        // Search for first available slot from top-left to bottom-right
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLUMNS) {
                val position = Pair(row, col)
                if (!occupiedPositions.contains(position)) {
                    return position
                }
            }
        }
        
        // No available slots found
        return null
    }

    private fun createNewFolder(menuX: Float, menuY: Float) {
        Log.d("MainActivity", "createNewFolder called at ($menuX, $menuY)")

        // Get theme to determine which folder icon to use
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
        val isWindows98 = selectedTheme == "Windows Classic"

        // Get the appropriate folder icon
        val folderIconResource = if (isWindows98) {
            R.drawable.folder_98
        } else if (selectedTheme == "Windows Vista") {
            R.drawable.folder_vista
        }
        else {
            R.drawable.folder_xp
        }

        val folderIcon =AppCompatResources.getDrawable(this, folderIconResource)!!

        // Find the nearest available grid position to where the context menu was opened
        val occupiedPositions = mutableSetOf<Pair<Int, Int>>()

        // Add positions for all existing desktop icons
        desktopIconViews.forEach { iconView ->
            val centerX = iconView.x + iconView.width / 2
            val centerY = iconView.y + iconView.height / 2
            val (cellWidth, cellHeight) = getGridDimensions()

            // Account for top margin (status bar + padding)
            val topMarginPx = 60f * resources.displayMetrics.density
            val adjustedCenterY = centerY - topMarginPx

            val col = (centerX / cellWidth).coerceIn(0f, (GRID_COLUMNS - 1).toFloat()).toInt()
            val row = (adjustedCenterY / cellHeight).coerceIn(0f, (GRID_ROWS - 1).toFloat()).toInt()

            occupiedPositions.add(Pair(row, col))
        }

        // Find the nearest available position to the menu location
        val nearestPosition = findNearestAvailableGridPosition(menuX, menuY, occupiedPositions)

        val (newX, newY) = if (nearestPosition != null) {
            getGridCoordinates(nearestPosition.first, nearestPosition.second)
        } else {
            // Fallback to menu position if no grid position available
            Pair(menuX, menuY)
        }

        // Generate unique ID for the folder
        val folderId = "folder_${System.currentTimeMillis()}"

        // Create desktop icon for the folder
        val desktopIcon = DesktopIcon(
            name = "New Folder",
            packageName = folderId,
            icon = folderIcon,
            x = newX,
            y = newY,
            id = folderId,
            type = IconType.FOLDER
        )

        desktopIcons.add(desktopIcon)

        // Create folder view
        val folderView = FolderView(this).apply {
            setDesktopIcon(desktopIcon)
            setThemeFont(isWindows98)
            setThemeIcon(isWindows98)
        }

        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )

        desktopContainer.addView(folderView, layoutParams)
        desktopIconViews.add(folderView)

        // Set position after adding to container
        folderView.post {
            folderView.x = newX
            folderView.y = newY
        }

        // Save the desktop icons
        saveDesktopIcons()

        Log.d("MainActivity", "New folder created at ($newX, $newY)")
    }

    private fun uninstallApp(appInfo: AppInfo) {
        try {
            // Check if this is a system app (cannot be uninstalled by regular users)
            val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
            val isSystemApp = packageInfo.applicationInfo?.let { applicationInfo ->
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } ?: false
            
            if (isSystemApp) {
                showNotification("Error", "Cannot uninstall system app: ${appInfo.name}")
                return
            }
            
            // Launch the system uninstall dialog
            val uninstallIntent = Intent(Intent.ACTION_DELETE)
            uninstallIntent.data = "package:${appInfo.packageName}".toUri()
            uninstallIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(uninstallIntent)
            
            // Hide the start menu
            hideStartMenu()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error uninstalling app: ${appInfo.packageName}", e)
            showNotification("Error", "Cannot uninstall ${appInfo.name}")
        }
    }

    private fun createAndShowWallpaperDialog(initScreen: String? = null) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Get current theme
        val currentTheme = themeManager.getSelectedTheme()
        val themeResId = themeManager.getThemeStyleRes(currentTheme)
        val themedContext = ContextThemeWrapper(this, themeResId)

        // Create Windows-style dialog with themed context and correct theme from start
        val windowsDialog = WindowsDialog(themedContext, initialTheme = currentTheme)
        windowsDialog.setTitle("Display Properties")
        windowsDialog.setWindowSize(360, 402)

        // Set theme-appropriate taskbar icon
        val taskbarIcon = getDisplayPropertiesIconForCurrentTheme()
        windowsDialog.setTaskbarIcon(taskbarIcon)

        // Create and set the unified content view using themed inflater
        val themedInflater = LayoutInflater.from(themedContext)
        val contentView = themedInflater.inflate(R.layout.wallpaper_selection_content, null)
        windowsDialog.setContentView(contentView)

        // Inflate the theme-appropriate RecyclerView into the container
        val recyclerView = contentView.findViewById<RecyclerView>(R.id.wallpapers_recycler_view)

        // Get references to tab buttons
        val wallpaperSelectButton = contentView.findViewById<View>(R.id.wallpaper_select_screen_button)
        val screensaverButton = contentView.findViewById<View>(R.id.wallpaper_screensaver_screen_button)
        val appearanceButton = contentView.findViewById<View>(R.id.wallpaper_appearance_screen_button)
        val settingsButton = contentView.findViewById<View>(R.id.wallpaper_settings_screen_button)

        // Get references to screen containers
        val wallpaperSelectScreen = contentView.findViewById<RelativeLayout>(R.id.wallpaper_select_screen)
        val screensaverScreen = contentView.findViewById<RelativeLayout>(R.id.wallpaper_screensaver_screen)
        val appearanceScreen = contentView.findViewById<RelativeLayout>(R.id.wallpaper_appearance_screen)
        val settingsScreen = contentView.findViewById<RelativeLayout>(R.id.wallpaper_settings_screen)

        // Function to switch screens
        fun showScreen(screenToShow: RelativeLayout) {
            wallpaperSelectScreen.visibility = View.GONE
            screensaverScreen.visibility = View.GONE
            appearanceScreen.visibility = View.GONE
            settingsScreen.visibility = View.GONE
            screenToShow.visibility = View.VISIBLE
        }

        // Set up tab button click listeners
        wallpaperSelectButton.setOnClickListener {
            showScreen(wallpaperSelectScreen)
            playClickSound()
        }

        screensaverButton.setOnClickListener {
            showScreen(screensaverScreen)
            playClickSound()
        }

        appearanceButton.setOnClickListener {
            showScreen(appearanceScreen)
            playClickSound()
        }

        settingsButton.setOnClickListener {
            showScreen(settingsScreen)
            playClickSound()
        }


        // Get references to UI elements
        val themeSpinner = contentView.findViewById<android.widget.Spinner>(R.id.theme_spinner)
        val flavourLabel = contentView.findViewById<TextView>(R.id.flavour_label)
        val flavourSpinner = contentView.findViewById<android.widget.Spinner>(R.id.flavour_spinner)
        val gestureBarCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_under_taskbar_checkbox)
        val showAgentCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_agent_checkbox)
        val showQuickGlanceCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_quick_glance_checkbox)
        val showRecycleBinCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_recycle_bin_checkbox)
        val showShortcutArrowOnIcons = contentView.findViewById<android.widget.CheckBox>(R.id.show_shortcut_arrow)
        val showCursorCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_cursor_checkbox)
        val playEmailSoundCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.play_email_sound_checkbox)
        val showNotificationDotsCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_notification_dots_checkbox)
        val screensaverSelector = contentView.findViewById<android.widget.Spinner>(R.id.screensaver_selector)
        val previewScreensaverVideo = contentView.findViewById<VideoView>(R.id.preview_screensaver_video)
        val previewScreensaverButton = contentView.findViewById<TextView>(R.id.preview_screensaver_button)
        val customWallpaperButton = contentView.findViewById<View>(R.id.custom_wallpaper_button)

        // Set up theme spinner with appropriate layouts based on current theme
        val themes = arrayOf("Windows Vista", "Windows XP", "Windows Classic")
        val spinnerLayoutId = themeManager.getSpinnerItemLayoutRes(currentTheme)
        val dropdownLayoutId = themeManager.getSpinnerDropdownLayoutRes(currentTheme)

        val spinnerAdapter = android.widget.ArrayAdapter(this, spinnerLayoutId, themes)
        spinnerAdapter.setDropDownViewResource(dropdownLayoutId)
        themeSpinner.adapter = spinnerAdapter

        // Set current theme
        val currentThemeString = currentTheme.toString()
        val themeIndex = themes.indexOf(currentThemeString)
        if (themeIndex != -1) {
            themeSpinner.setSelection(themeIndex)
        }

        // Set up flavour spinner
        val flavours = arrayOf("Windows 95", "Windows 98", "Windows ME", "Windows 2000")
        val flavourValues = mapOf(
            "Windows 95" to "start_banner_95",
            "Windows 98" to "start_banner_98",
            "Windows ME" to "start_banner_me",
            "Windows 2000" to "start_banner_2000"
        )

        val flavourSpinnerAdapter = android.widget.ArrayAdapter(this, spinnerLayoutId, flavours)
        flavourSpinnerAdapter.setDropDownViewResource(dropdownLayoutId)
        flavourSpinner.adapter = flavourSpinnerAdapter

        // Set current flavour from SharedPreferences
        val currentFlavourValue = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
        val currentFlavourName = flavourValues.entries.find { it.value == currentFlavourValue }?.key ?: "Windows 98"
        val flavourIndex = flavours.indexOf(currentFlavourName)
        if (flavourIndex != -1) {
            flavourSpinner.setSelection(flavourIndex)
        }

        // Show/hide flavour spinner based on current theme
        var flavourVisibility = if (shouldShowFlavourSpinner()) View.VISIBLE else View.GONE
        flavourLabel.visibility = flavourVisibility
        flavourSpinner.visibility = flavourVisibility

        // Track pending theme and flavour selections (don't apply immediately)
        var pendingTheme: String? = null
        var pendingFlavour: String? = null

        // Handle flavour selection - just track it, don't apply
        flavourSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedFlavour = flavours[position]
                pendingFlavour = flavourValues[selectedFlavour]
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
                // Do nothing
            }
        }

        // Set up gesture bar checkbox
        val gestureBarVisible = prefs.getBoolean(KEY_GESTURE_BAR_VISIBLE, true)
        gestureBarCheckbox.isChecked = gestureBarVisible

        gestureBarCheckbox.setOnCheckedChangeListener { _, isChecked ->
            // Save new state to SharedPreferences
            prefs.edit { putBoolean(KEY_GESTURE_BAR_VISIBLE, isChecked) }

            // Apply the change immediately
            val gestureBarBackground = findViewById<View>(R.id.gesture_bar_background)
            gestureBarBackground.visibility = if (isChecked) View.VISIBLE else View.INVISIBLE

            Log.d("MainActivity", "Gesture bar visibility changed to: ${if (isChecked) "VISIBLE" else "INVISIBLE"}")
        }

        // Set up Show Agent checkbox
        showAgentCheckbox.isChecked = isRoverVisible()
        showAgentCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isRoverVisible()) {
                toggleRover()
            }
        }

        // Set up Show Quick Glance checkbox
        showQuickGlanceCheckbox.isChecked = isQuickGlanceVisible()
        showQuickGlanceCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isQuickGlanceVisible()) {
                toggleQuickGlance()
            }
        }

        // Set up Show Recycle Bin checkbox
        showRecycleBinCheckbox.isChecked = isRecycleBinVisible()
        showRecycleBinCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isRecycleBinVisible()) {
                toggleRecycleBin()
            }
        }

        // Set up Show Shortcut Arrow checkbox
        showShortcutArrowOnIcons.isChecked = isShortcutArrowVisible()
        showShortcutArrowOnIcons.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isShortcutArrowVisible()) {
                toggleShortcutArrow()
            }
        }

        // Set up Show Cursor checkbox
        showCursorCheckbox.isChecked = isCursorVisible()
        showCursorCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isCursorVisible()) {
                toggleCursorVisibility()
            }
        }

        // Set up Play Email Sound checkbox
        val playEmailSoundEnabled = prefs.getBoolean(KEY_PLAY_EMAIL_SOUND, true)
        playEmailSoundCheckbox.isChecked = playEmailSoundEnabled

        // Set up email permission error text
        val emailPermissionError = contentView.findViewById<TextView>(R.id.email_permission_error)
        emailPermissionError.paintFlags = emailPermissionError.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        emailPermissionError.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        }

        // Update email error visibility
        val updateEmailPermissionErrorFunc = {
            emailPermissionError.visibility = if (playEmailSoundCheckbox.isChecked && !isNotificationListenerEnabled()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        // Store reference for use in onResume
        updateEmailPermissionError = updateEmailPermissionErrorFunc
        updateEmailPermissionErrorFunc()

        playEmailSoundCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_PLAY_EMAIL_SOUND, isChecked) }
            Log.d("MainActivity", "Play email sound changed to: $isChecked")
            updateEmailPermissionErrorFunc()
        }

        // Set up Show Notification Dots checkbox
        val showNotificationDotsEnabled = prefs.getBoolean(KEY_SHOW_NOTIFICATION_DOTS, true)
        showNotificationDotsCheckbox.isChecked = showNotificationDotsEnabled

        // Set up notification dots permission error text
        val notificationDotsPermissionError = contentView.findViewById<TextView>(R.id.notification_dots_permission_error)
        notificationDotsPermissionError.paintFlags = notificationDotsPermissionError.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        notificationDotsPermissionError.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        }

        // Update notification dots error visibility
        val updateNotificationDotsPermissionErrorFunc = {
            notificationDotsPermissionError.visibility = if (showNotificationDotsCheckbox.isChecked && !isNotificationListenerEnabled()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        // Store reference for use in onResume
        updateNotificationDotsPermissionError = updateNotificationDotsPermissionErrorFunc
        updateNotificationDotsPermissionErrorFunc()

        showNotificationDotsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_SHOW_NOTIFICATION_DOTS, isChecked) }
            Log.d("MainActivity", "Show notification dots changed to: $isChecked")
            updateNotificationDotsPermissionErrorFunc()
            // Update notification dots immediately
            updateNotificationDots()
        }

        // Set up Taskbar Height Input
        val taskbarHeightInput = contentView.findViewById<EditText>(R.id.taskbar_height_input)

        // Load current offset from SharedPreferences
        val currentOffset = prefs.safeGetInt(KEY_TASKBAR_HEIGHT_OFFSET, 0)
        taskbarHeightInput.setText(currentOffset.toString())

        // Track pending offset value (don't apply immediately)
        var pendingTaskbarOffset: Int? = null

        taskbarHeightInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString().toIntOrNull()
                if (value != null && value in -30..30) {
                    pendingTaskbarOffset = value
                } else if (s.toString().isEmpty()) {
                    pendingTaskbarOffset = 0
                }
            }
        })

        // Set up Screensaver Selector
        val screensaverOptions = resources.getStringArray(R.array.screensaver_options)
        val screensaverAdapter = android.widget.ArrayAdapter(this, R.layout.spinner_item_screensaver, screensaverOptions)
        screensaverAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_screensaver)
        screensaverSelector.adapter = screensaverAdapter

        // Load saved screensaver selection (default to 3D Pipes for backward compatibility)
        val selectedScreensaver = prefs.safeGetInt(KEY_SELECTED_SCREENSAVER, SCREENSAVER_3D_PIPES)
        screensaverSelector.setSelection(selectedScreensaver)

        // Track pending screensaver selection (don't save immediately)
        var pendingScreensaverSelection: Int = selectedScreensaver

        // Helper function to get video resource for screensaver type
        fun getScreensaverVideoResource(screensaverType: Int): Int? {
            return when (screensaverType) {
                SCREENSAVER_3D_PIPES -> R.raw.screensaver_pipes
                SCREENSAVER_UNDERWATER -> R.raw.screensaver_underwater
                else -> null
            }
        }

        // Helper function to play preview video
        fun playPreviewVideo(screensaverType: Int) {
            val videoResource = getScreensaverVideoResource(screensaverType)
            if (videoResource != null) {
                val videoUri = Uri.parse("android.resource://${packageName}/${videoResource}")
                previewScreensaverVideo.setVideoURI(videoUri)
                previewScreensaverVideo.visibility = View.VISIBLE
                previewScreensaverVideo.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true
                    mediaPlayer.start()
                }
                // Start the VideoView to begin preparing and playing the video
                previewScreensaverVideo.start()
            } else {
                // No video for "None" option
                previewScreensaverVideo.stopPlayback()
                previewScreensaverVideo.visibility = View.INVISIBLE
            }
        }

        // Play initial preview
        playPreviewVideo(selectedScreensaver)

        // Handle screensaver selection changes
        screensaverSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingScreensaverSelection = position
                playPreviewVideo(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Do nothing
            }
        }

        // Set up Preview Screensaver button
        previewScreensaverButton.setOnClickListener {
            if (::screensaverManager.isInitialized && pendingScreensaverSelection != SCREENSAVER_NONE) {
                // Temporarily set the selected screensaver to the pending selection for preview
                val previousSelection = screensaverManager.getSelectedScreensaver()
                screensaverManager.setSelectedScreensaver(pendingScreensaverSelection)
                screensaverManager.showScreensaver()

                // Note: The previous selection will be restored when Apply/OK is pressed or Cancel closes the dialog
                // For now, we leave it at the pending selection so the preview shows correctly
            }
        }

        // Set up Screensaver Timeout EditText
        val screensaverTimeoutInput = contentView.findViewById<EditText>(R.id.preview_screensaver_timeout_time)

        // Load saved timeout (default to 30 seconds)
        val savedTimeout = prefs.safeGetInt(KEY_SCREENSAVER_TIMEOUT, DEFAULT_SCREENSAVER_TIMEOUT)
        screensaverTimeoutInput.setText(savedTimeout.toString())

        // Track pending timeout value (don't save immediately)
        var pendingScreensaverTimeout: Int = savedTimeout

        // Handle text changes
        screensaverTimeoutInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().toIntOrNull()
                if (value != null && value in 10..60) {
                    pendingScreensaverTimeout = value
                } else if (s.toString().isEmpty()) {
                    pendingScreensaverTimeout = DEFAULT_SCREENSAVER_TIMEOUT
                }
            }
        })

        customWallpaperButton.setOnClickListener {
                imagePickerLauncher.launch("image/*")
        }

        // Apply fonts based on selected theme
        applyThemeFontsToDialog(contentView)

        // Create the dialog container without interfering with system UI
        // Handle theme selection - just track it and update UI, don't apply
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedTheme = themes[position]
                pendingTheme = selectedTheme

                flavourVisibility = if (shouldShowFlavourSpinner(AppTheme.fromString(pendingTheme))) View.VISIBLE else View.GONE
                // Update flavour spinner visibility based on selected theme
                flavourLabel.visibility = flavourVisibility
                flavourSpinner.visibility = flavourVisibility
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {
                // Do nothing
            }
        }

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
        }

        windowsDialog.setOnMaximizeListener {
            // For now, do nothing (could implement maximize later)
        }

        // Set up list layout for wallpapers
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load wallpapers
        val wallpapers = loadWallpapers()

        // Track selected wallpaper for preview
        var selectedWallpaper: WallpaperItem? = null

        // Get wallpaper preview ImageView
        val wallpaperPreview = contentView.findViewById<ImageView>(R.id.wallpaper_preview)

        // Load and display current wallpaper in preview
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()
        var currentWallpaperPath = prefs.getString(pathKey, null) ?: getDefaultWallpaperForTheme()

        // Check if there's a custom wallpaper URI first
        val customWallpaperUri = prefs.getString(uriKey, null)
        if (customWallpaperUri != null) {
            // Load custom wallpaper from URI
            try {
                val uri = customWallpaperUri.toUri()
                val inputStream = contentResolver.openInputStream(uri)
                val customDrawable = Drawable.createFromStream(inputStream, customWallpaperUri)
                inputStream?.close()
                if (customDrawable != null) {
                    wallpaperPreview.scaleType = ImageView.ScaleType.CENTER_CROP
                    wallpaperPreview.setImageDrawable(customDrawable)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load custom wallpaper for preview: $customWallpaperUri", e)
            }
        } else {
            // Load built-in wallpaper from assets
            // Set scaleType based on whether path contains "(m)"
            if (currentWallpaperPath.contains("(m)")) {
                wallpaperPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                wallpaperPreview.scaleType = ImageView.ScaleType.CENTER_CROP
            }

            try {
                val inputStream = assets.open(currentWallpaperPath)
                val currentDrawable = Drawable.createFromStream(inputStream, currentWallpaperPath)
                inputStream.close()
                if (currentDrawable != null) {
                    wallpaperPreview.setImageDrawable(currentDrawable)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load current wallpaper for preview: $currentWallpaperPath", e)
            }
        }

        // Get button references
        val okButton = contentView.findViewById<View>(R.id.wallpaper_ok_button)
        val cancelButton = contentView.findViewById<View>(R.id.wallpaper_cancel_button)
        val applyButton = contentView.findViewById<View>(R.id.wallpaper_apply_button)

        val adapter = WallpaperAdapter(wallpapers) { wallpaper ->
            // Preview the wallpaper instead of showing target dialog immediately
            selectedWallpaper = wallpaper
            if (wallpaper.name.contains("(m)")) {
                wallpaperPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                wallpaperPreview.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            wallpaperPreview.setImageDrawable(wallpaper.drawable)
            playClickSound()
        }
        recyclerView.adapter = adapter

        // Set up OK button - apply theme/flavour changes, show target dialog if wallpaper changed, then close
        okButton.setOnClickListener {
            playClickSound()

            // Apply pending theme changes
            if (pendingTheme != null && pendingTheme != currentTheme.toString()) {
                prefs.edit {
                    putString("selected_theme", pendingTheme)
                }
                applyTheme(pendingTheme!!)
            }

            // Apply pending flavour changes
            if (pendingFlavour != null && pendingFlavour != currentFlavourValue) {
                prefs.edit { putString(KEY_START_BANNER_98, pendingFlavour) }
                val startMenuContent = findViewById<View>(R.id.start_menu_content)
                val bannerFrame = startMenuContent?.findViewById<android.widget.FrameLayout>(R.id.start_banner_frame)
                bannerFrame?.let { frame ->
                    loadCurrentStartBanner(frame)
                }
            }

            // Apply pending taskbar height offset
            if (pendingTaskbarOffset != null) {
                prefs.edit {putInt(KEY_TASKBAR_HEIGHT_OFFSET, pendingTaskbarOffset!!) }
                applyTaskbarHeightOffset(pendingTaskbarOffset!!)
            }

            // Apply pending screensaver selection
            prefs.edit { putInt(KEY_SELECTED_SCREENSAVER, pendingScreensaverSelection) }
            if (::screensaverManager.isInitialized) {
                screensaverManager.setSelectedScreensaver(pendingScreensaverSelection)
            }

            // Apply pending screensaver timeout
            prefs.edit { putInt(KEY_SCREENSAVER_TIMEOUT, pendingScreensaverTimeout) }
            if (::screensaverManager.isInitialized) {
                screensaverManager.setInactivityTimeout(pendingScreensaverTimeout)
            }

            currentWallpaperPath = prefs.getString(pathKey, null) ?: getDefaultWallpaperForTheme()

            // Apply wallpaper if changed
            if (selectedWallpaper != null && selectedWallpaper!!.filePath != currentWallpaperPath) {
                showWallpaperTargetDialog(selectedWallpaper!!)
            }

            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set up Cancel button - close without applying
        cancelButton.setOnClickListener {
            playClickSound()
            // Restore the saved screensaver selection if it was changed during preview
            if (::screensaverManager.isInitialized) {
                val savedScreensaver = prefs.safeGetInt(KEY_SELECTED_SCREENSAVER, SCREENSAVER_3D_PIPES)
                screensaverManager.setSelectedScreensaver(savedScreensaver)
            }
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set up Apply button - apply theme/flavour and wallpaper, but don't close
        applyButton.setOnClickListener {
            playClickSound()

            // Apply pending theme changes
            if (pendingTheme != null && pendingTheme != currentTheme.toString()) {
                prefs.edit {
                    putString("selected_theme", pendingTheme)
                }
                applyTheme(pendingTheme!!)
                pendingTheme = null // Clear after applying
            }

            // Apply pending flavour changes
            if (pendingFlavour != null && pendingFlavour != currentFlavourValue) {
                prefs.edit { putString(KEY_START_BANNER_98, pendingFlavour) }
                val startMenuContent = findViewById<View>(R.id.start_menu_content)
                val bannerFrame = startMenuContent?.findViewById<android.widget.FrameLayout>(R.id.start_banner_frame)
                bannerFrame?.let { frame ->
                    loadCurrentStartBanner(frame)
                }
                pendingFlavour = null // Clear after applying
            }

            // Apply pending taskbar height offset
            if (pendingTaskbarOffset != null) {
                prefs.edit {putInt(KEY_TASKBAR_HEIGHT_OFFSET, pendingTaskbarOffset!!) }
                applyTaskbarHeightOffset(pendingTaskbarOffset!!)
                pendingTaskbarOffset = null // Clear after applying
            }

            // Apply pending screensaver selection
            prefs.edit { putInt(KEY_SELECTED_SCREENSAVER, pendingScreensaverSelection) }
            if (::screensaverManager.isInitialized) {
                screensaverManager.setSelectedScreensaver(pendingScreensaverSelection)
            }

            // Apply pending screensaver timeout
            prefs.edit { putInt(KEY_SCREENSAVER_TIMEOUT, pendingScreensaverTimeout) }
            if (::screensaverManager.isInitialized) {
                screensaverManager.setInactivityTimeout(pendingScreensaverTimeout)
            }

            // Apply wallpaper if changed
            if (selectedWallpaper != null && selectedWallpaper!!.filePath != currentWallpaperPath) {
                showWallpaperTargetDialog(selectedWallpaper!!)
                // Update current wallpaper path after applying
                currentWallpaperPath = prefs.getString(pathKey, null) ?: getDefaultWallpaperForTheme()
            }
        }

        // Show as floating window
        Log.d("MainActivity", "Showing wallpaper dialog as floating window")
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
            when(initScreen){
                "screensaver" -> showScreen(screensaverScreen)
                "appearance" -> showScreen(appearanceScreen)
                "settings" -> showScreen(settingsScreen)
            }
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showWallpaperTargetDialog(
        wallpaperItem: WallpaperItem? = null,
        uri: Uri? = null,
        drawable: Drawable? = null
    ) {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Apply Wallpaper To")

        // Get current theme for button styling
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"

        // Create content view from XML layout
        val contentView = layoutInflater.inflate(R.layout.wallpaper_target_dialog_content, null)
        windowsDialog.setContentView(contentView)

        // Get references to UI elements
        val launcherCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.launcher_checkbox)
        val homeScreenCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.home_screen_checkbox)
        val lockScreenCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.lock_screen_checkbox)
        val applyButton = contentView.findViewById<TextView>(R.id.apply_button)

        // Set button background based on theme
        val buttonBackground = if (selectedTheme == "Windows Classic") {
            R.drawable.win98_start_menu_border
        } else {
            R.drawable.button_xp_background
        }
        applyButton.setBackgroundResource(buttonBackground)

        // Apply theme fonts to the entire dialog content
        applyThemeFontsToDialog(contentView)

        // Apply button click handler
        applyButton.setOnClickListener {
            playClickSound()
            setCursorBusy()

            if (launcherCheckbox.isChecked) {
                if (wallpaperItem != null) {
                    applyCustomWallpaper(wallpaperItem)
                } else if (drawable != null) {
                    applyWallpaperDrawable(drawable, uri)
                }
            }

            if (homeScreenCheckbox.isChecked || lockScreenCheckbox.isChecked) {
                if (wallpaperItem != null) {
                    applyWallpaperToDevice(wallpaperItem, homeScreenCheckbox.isChecked, lockScreenCheckbox.isChecked)
                } else if (drawable != null) {
                    applyWallpaperToDeviceFromDrawable(drawable, homeScreenCheckbox.isChecked, lockScreenCheckbox.isChecked)
                }
            }

            floatingWindowManager.removeWindow(windowsDialog)
            setCursorNormal()
        }

        // Set close listener to restore cursor if dialog is closed without applying
        windowsDialog.setOnCloseListener {
            setCursorNormal()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    private fun showInternetExplorerDialog(initialUrl: String? = null, appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()
        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowInternetExplorerDialog(initialUrl, appInfo)
        }
    }

    private fun createAndShowInternetExplorerDialog(initialUrl: String? = null, appInfo: AppInfo? = null) {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.internet_explorer"  // Set identifier for tracking
        windowsDialog.setTitle("Internet Explorer")
        windowsDialog.setTaskbarIcon(themeManager.getIEIcon())

        // Set minimum window size from AppInfo if available
        if (appInfo != null) {
            windowsDialog.setMinimumWindowSize(appInfo)
        }

        // Inflate the internet explorer content
        val contentView = layoutInflater.inflate(themeManager.getIELayout(), null)
        windowsDialog.setContentView(contentView)

        // Set window size: 358dp width + borders/padding, 424dp height + title bar + borders/padding
        // Content: 300x424, Title bar: 36dp, Margins: 2dp sides+bottom
        windowsDialog.setWindowSizePercentage(  90f, 60f)
        windowsDialog.setMaximizable(true)


        // Create Internet Explorer app instance
        val ieApp = InternetExplorerApp(
            context = this,
            onSoundPlay = { playClickSound() },
            onShowNotification = { title, message -> showNotification(title, message) },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) }
        )

        ieApp.setupApp(contentView, initialUrl)

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Window is already minimized by minimize() method
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        windowsDialog.setOnCloseListener {
            ieApp.cleanup()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showAddKeyDialog(prefs: android.content.SharedPreferences, refreshCallback: () -> Unit) {


        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val keyInput = EditText(this).apply {
            hint = "Key name"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val valueInput = EditText(this).apply {
            hint = "Value"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val typeSpinner = android.widget.Spinner(this)
        val typeOptions = arrayOf("String", "Boolean", "Integer", "Float", "Long")
        val spinnerAdapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, typeOptions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
        }
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = spinnerAdapter

        container.addView(TextView(this).apply {
            text = "Key:"
            setTextColor(Color.BLACK)
            setPadding(0, 8, 0, 4)
        })
        container.addView(keyInput)
        container.addView(TextView(this).apply {
            text = "Value:"
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 4)
        })
        container.addView(valueInput)
        container.addView(TextView(this).apply {
            text = "Type:"
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 4)
        })
        container.addView(typeSpinner)

        android.app.AlertDialog.Builder(this, R.style.LightAlertDialog)
            .setTitle("Add Preference Key")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val key = keyInput.text.toString().trim()
                val value = valueInput.text.toString().trim()
                val type = typeSpinner.selectedItem.toString()

                if (key.isEmpty()) {
                    showNotification("Error", "Key cannot be empty")
                    return@setPositiveButton
                }

                try {
                    prefs.edit().apply {
                        when (type) {
                            "String" -> putString(key, value)
                            "Boolean" -> putBoolean(key, value.toBoolean())
                            "Integer" -> putInt(key, value.toInt())
                            "Float" -> putFloat(key, value.toFloat())
                            "Long" -> putLong(key, value.toLong())
                        }
                        apply()
                    }
                    showNotification("Registry Editor", "Key added successfully")
                    refreshCallback()
                } catch (e: Exception) {
                    showNotification("Registry Editor", "Error adding key: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRegistryEditorDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowRegistryEditor()
        }
    }

    private fun createAndShowRegistryEditor() {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.registry_editor"  // Set identifier for tracking
        windowsDialog.setTitle("Registry Editor")
        windowsDialog.setTaskbarIcon(themeManager.getRegeditIcon())

        // Inflate the Registry Editor content
        val contentView = layoutInflater.inflate(R.layout.program_registry_editor, null)
        windowsDialog.setContentView(contentView)

        // Set window size to match the layout: 358dp width + borders/padding, 610dp height + title bar + borders/padding
        windowsDialog.setWindowSizePercentage(90f, 60f)
        windowsDialog.setMaximizable(true)

        // Load SharedPreferences
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Create Registry Editor app instance
        val regeditApp = RegistryEditorApp(
            context = this,
            onSoundPlay = { playClickSound() },
            onShowNotification = { title, message -> showNotification(title, message) },
            onShowAddKeyDialog = { prefs, refreshCallback -> showAddKeyDialog(prefs, refreshCallback) },
            onExportToLocalFile = { prefsToExport -> exportToLocalFile(prefsToExport) },
            onExportToGoogleDrive = { prefsToExport -> exportToGoogleDrive(prefsToExport) },
            onImportFromLocalFile = { importFromLocalFile() },
            onImportFromGoogleDrive = { importFromGoogleDrive() },
            onAutoSyncChanged = { enabled -> handleAutoSyncChanged(enabled) },
            getLastSyncTime = { preferences.getSafeLong(KEY_LAST_GOOGLE_DRIVE_SYNC, 0L) }
        )

        // Store instance for auto-sync updates
        registryEditorAppInstance = regeditApp

        regeditApp.setupApp(contentView, preferences)

        // Auto-sync is already started in onCreate if enabled - no need to start it again here

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Window is already minimized by minimize() method
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        windowsDialog.setOnCloseListener {
            regeditApp.cleanup()
            // Don't stop auto-sync when closing Registry Editor - it should continue running
            registryEditorAppInstance = null
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun exportToLocalFile(prefs: android.content.SharedPreferences) {
        try {
            val allPrefs = prefs.all
            val prefsMap = mutableMapOf<String, Any?>()
            allPrefs.forEach { (key, value) -> prefsMap[key] = value }

            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(prefsMap)

            // Store the JSON temporarily for the launcher callback
            pendingExportJson = jsonString

            // Launch file picker with suggested filename
            exportPrefsLauncher.launch("windows_launcher_settings_export.json")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error exporting preferences", e)
            showNotification("Export Failed", "Export failed: ${e.message}")
        }
    }

    private fun importFromLocalFile() {
        try {
            // Launch file picker for JSON files
            importPrefsLauncher.launch(arrayOf("application/json", "*/*"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting import", e)
            showNotification("Import Failed", "Import failed: ${e.message}")
        }
    }

    private fun exportToGoogleDrive(prefs: android.content.SharedPreferences) {
        Log.d("MainActivity", "exportToGoogleDrive called, isSignedIn=${googleDriveHelper.isSignedIn()}")

        // Check if signed in
        if (!googleDriveHelper.isSignedIn()) {
            Log.d("MainActivity", "Not signed in, launching sign-in flow")
            // Save the action to perform after sign-in
            pendingImportCallback = {
                Log.d("MainActivity", "Callback executing after sign-in")
                exportToGoogleDrive(prefs)
            }
            // Start sign-in flow
            googleSignInLauncher.launch(googleDriveHelper.getSignInIntent())
            return
        }

        // Export to Google Drive
        try {
            Log.d("MainActivity", "Starting export to Google Drive")
            val allPrefs = prefs.all
            val prefsMap = mutableMapOf<String, Any?>()
            allPrefs.forEach { (key, value) -> prefsMap[key] = value }

            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(prefsMap)

            Log.d("MainActivity", "JSON prepared, size=${jsonString.length} bytes")

            lifecycleScope.launch {
                try {
                    val result = googleDriveHelper.exportToGoogleDrive(jsonString)
                    result.onSuccess {
                        // Record last sync time
                        val currentTime = System.currentTimeMillis()
                        prefs.edit { putLong(KEY_LAST_GOOGLE_DRIVE_SYNC, currentTime) }

                        // Update UI in Registry Editor if it's open
                        registryEditorAppInstance?.onSyncCompleted()
                    }.onFailure { error ->
                        Log.e("MainActivity", "Google Drive export failed", error)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Exception in coroutine", e)
                    showNotification("Export Failed", "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error exporting to Google Drive", e)
            showNotification("Export Failed", "Export failed: ${e.message}")
        }
    }

    private fun importFromGoogleDrive() {
        Log.d("MainActivity", "importFromGoogleDrive called, isSignedIn=${googleDriveHelper.isSignedIn()}")

        // Check if signed in
        if (!googleDriveHelper.isSignedIn()) {
            Log.d("MainActivity", "Not signed in, launching sign-in flow")
            // Save the action to perform after sign-in
            pendingImportCallback = {
                Log.d("MainActivity", "Callback executing after sign-in")
                importFromGoogleDrive()
            }
            // Start sign-in flow
            googleSignInLauncher.launch(googleDriveHelper.getSignInIntent())
            return
        }

        // Import from Google Drive
        Log.d("MainActivity", "Starting import from Google Drive")
        showNotification("Google Drive", "Downloading backup...")

        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Calling importFromGoogleDrive on helper")
                val result = googleDriveHelper.importFromGoogleDrive()
                result.onSuccess { jsonString ->
                    try {
                        Log.d("MainActivity", "Import successful, parsing JSON (${jsonString.length} bytes)")
                        val gson = Gson()
                        val importedPrefs = gson.fromJson(jsonString, Map::class.java) as Map<String, Any>

                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit {

                            // Clear all existing preferences
                            clear()

                            // Import all preferences
                            importedPrefs.forEach { (key, value) ->
                                when (value) {
                                    is String -> putString(key, value)
                                    is Boolean -> putBoolean(key, value)
                                    is Int -> putInt(key, value.toInt())
                                    is Long -> putLong(key, value.toLong())
                                    is Float -> putFloat(key, value.toFloat())
                                    is Double -> putFloat(key, value.toFloat())
                                    else -> Log.w(
                                        "MainActivity",
                                        "Unknown preference type for key $key"
                                    )
                                }
                            }

                        }
                        Log.d("MainActivity", "Preferences imported successfully")
                        showNotification("Registry Editor", "Settings imported successfully from Google Drive")
                        recreate()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error parsing imported data", e)
                        showNotification("Import Failed", "Failed to parse backup data: ${e.message}")
                    }
                }.onFailure { error ->
                    Log.e("MainActivity", "Google Drive import failed", error)
                    showNotification("Import Failed", "Failed to download from Google Drive: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Exception in coroutine", e)
                showNotification("Import Failed", "Error: ${e.message}")
            }
        }
    }

    private fun handleAutoSyncChanged(enabled: Boolean) {
        Log.d("MainActivity", "Auto-sync changed: $enabled")
        if (enabled) {
            startAutoSync()
        } else {
            stopAutoSync()
        }
    }

    private fun startAutoSync() {
        // Stop any existing timer first
        stopAutoSync()

        Log.d("MainActivity", "Starting auto-sync timer (interval: ${AUTO_SYNC_INTERVAL}ms)")

        // Perform immediate sync when auto-sync is enabled
        if (googleDriveHelper.isSignedIn()) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            exportToGoogleDrive(prefs)
        } else {
            Log.d("MainActivity", "Skipping initial auto-sync: not signed in to Google Drive")
        }

        autoSyncRunnable = object : Runnable {
            override fun run() {
                Log.d("MainActivity", "Auto-sync timer triggered")

                // Only sync if user is signed in to Google Drive
                if (googleDriveHelper.isSignedIn()) {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    exportToGoogleDrive(prefs)
                } else {
                    Log.d("MainActivity", "Skipping auto-sync: not signed in to Google Drive")
                }

                // Schedule next sync
                autoSyncHandler.postDelayed(this, AUTO_SYNC_INTERVAL)
            }
        }

        // Start the timer
        autoSyncHandler.postDelayed(autoSyncRunnable!!, AUTO_SYNC_INTERVAL)
    }

    private fun stopAutoSync() {
        autoSyncRunnable?.let {
            Log.d("MainActivity", "Stopping auto-sync timer")
            autoSyncHandler.removeCallbacks(it)
            autoSyncRunnable = null
        }
    }

    private fun showDialerDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowDialerDialog()
        }
    }

    private fun createAndShowDialerDialog() {
        // Request permissions when opening dialer
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.READ_CONTACTS
                ),
                100
            )
        }

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.dialer"  // Set identifier for tracking
        windowsDialog.setTitle("Phone Dialer")
        windowsDialog.setTaskbarIcon(R.drawable.dialer_icon)

        // Inflate the dialer content
        val contentView = layoutInflater.inflate(R.layout.program_dialer, null)

        // Create Dialer app instance
        val dialerApp = DialerApp(
            context = this,
            onSoundPlay = { soundResource ->
                playSound(soundResource)
            },
            onShowContextMenu = { menuItems, x, y ->
                if (::contextMenu.isInitialized) {
                    contextMenu.showMenu(menuItems, x, y)
                }
            }
        )

        // Setup the app
        dialerApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(364, 382)

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Window is already minimized by minimize() method
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            dialerApp.cleanup()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showNotepadDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowNotepadDialog()
        }
    }

    private fun createAndShowNotepadDialog() {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.notepad"  // Set identifier for tracking
        windowsDialog.setTitle("Notepad")
        windowsDialog.setTaskbarIcon(themeManager.getNotepadIcon())

        // Inflate the notepad content
        val contentView = layoutInflater.inflate(R.layout.program_notepad, null)

        // Create Notepad app instance
        val notepadApp = NotepadApp(
            context = this,
            onSoundPlay = { soundType ->
                when (soundType) {
                    "click" -> playClickSound()
                    else -> playClickSound()
                }
            },
            onShowContextMenu = { menuItems, x, y ->
                if (::contextMenu.isInitialized) {
                    contextMenu.showMenu(menuItems, x, y)
                }
            },
            onShowRenameDialog = { title, initialText, hint, onOk ->
                showRenameDialog(title, initialText, hint, onOk)
            },
            onUpdateWindowTitle = { title ->
                windowsDialog.setTitle(title)
            },
            galleryPickerLauncher = notepadGalleryPickerLauncher,
            onCameraCapture = { uri ->
                pendingCameraUri = uri
                notepadCameraPickerLauncher.launch(uri)
            },
            onShowFullscreenImage = { uri ->
                showFullscreenImage(uri)
            },
            getCursorPosition = {
                Pair(cursorEffect.x, cursorEffect.y)
            }
        )

        // Store reference for launchers to call back
        currentNotepadApp = notepadApp

        // Setup the app
        notepadApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setMaximizable(true)

//        windowsDialog.setWindowSize(360, 382)
        windowsDialog.setWindowSizePercentage(90f, 50f)

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            notepadApp.onMinimize()
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            notepadApp.cleanup()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showFullscreenImage(uri: Uri) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = android.widget.ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        try {
            // First, decode the bitmap
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                dialog.dismiss()
                return
            }

            // Read EXIF orientation and rotate if needed
            try {
                val exifInputStream = contentResolver.openInputStream(uri)
                val exif = exifInputStream?.use {
                    androidx.exifinterface.media.ExifInterface(it)
                }

                val orientation = exif?.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                ) ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

                val rotationAngle = when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                if (rotationAngle != 0f) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationAngle)
                    val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    bitmap = rotatedBitmap
                }
            } catch (exifException: Exception) {
                // Continue with unrotated bitmap if EXIF reading fails
                Log.e("MainActivity", "Error reading EXIF data", exifException)
            }

            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            dialog.dismiss()
            return
        }

        imageView.setOnClickListener {
            playClickSound()
            dialog.dismiss()
        }

        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun showMsnDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowMsnDialog()
        }
    }

    private fun createAndShowMsnDialog() {
        // Check if permissions are granted
        val hasReadSms = checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasSendSms = checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReceiveSms = checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReadContacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        if (!hasReadSms || !hasSendSms || !hasReceiveSms || !hasReadContacts) {
            // Request permissions
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_CONTACTS
                ),
                101
            )
            setCursorNormal()
            return
        }

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.msn"  // Set identifier for tracking
        windowsDialog.setTitle("MSN Messenger")
        windowsDialog.setTaskbarIcon(themeManager.getMsnIcon())

        // Inflate the MSN content
        val contentView = layoutInflater.inflate(R.layout.program_msn, null)

        // Create MSN app instance
        val msnApp = MsnApp(
            context = this,
            onSoundPlay = {
                playClickSound()
            },
            onCloseWindow = {
                windowsDialog.closeWindow()
            },
            onMoveWindow = { offsetY ->
                windowsDialog.moveWindowVertical(offsetY)
            },
            onShakeWindow = {
                windowsDialog.shakeWindow()
            }
        )

        // Setup the app
        msnApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(400, 522)

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            msnApp.onMinimize()
        }

        windowsDialog.setOnMaximizeListener {
            // Do nothing for now
        }

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            msnApp.cleanup()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }


    /**
     * Gets the button background drawable resource for the current theme
     */
    private fun getThemedButtonBackground(): Int {
        return when (themeManager.getSelectedTheme()) {
            AppTheme.WindowsClassic -> R.drawable.win98_start_menu_border
            AppTheme.WindowsXP -> R.drawable.button_xp_background
            AppTheme.WindowsVista -> R.drawable.button_xp_background
        }
    }

    /**
     * Generic dialog for renaming items with Windows XP/98 styling
     * @param title Dialog title
     * @param initialText Initial text to show in the input field
     * @param hint Placeholder hint text (optional)
     * @param onOk Callback when OK is clicked, receives the new text
     */
    private fun showRenameDialog(
        title: String,
        initialText: String,
        hint: String = "",
        onOk: (String) -> Unit
    ) {
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle(title)

        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        }

        // Create EditText
        val editText = EditText(this).apply {
            setText(initialText)
            selectAll()
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            highlightColor = "#7a94f4".toColorInt()
            textSize = 12f
            setBackgroundResource(R.drawable.win98_edit_text_border)
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            isSingleLine = true
            if (hint.isNotEmpty()) {
                setHint(hint)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        }

        contentView.addView(editText)

        // Create buttons container
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        // Create OK button
        val okButton = TextView(this).apply {
            text = "OK"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
        }

        // Create Cancel button
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonsContainer.addView(okButton)
        buttonsContainer.addView(cancelButton)
        contentView.addView(buttonsContainer)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(250, null)

        // OK button handler
        okButton.setOnClickListener {
            playClickSound()
            val newText = editText.text.toString().trim()
            if (newText.isNotEmpty()) {
                onOk(newText)
            }
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Cancel button handler
        cancelButton.setOnClickListener {
            playClickSound()
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set context menu reference
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Show keyboard
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Generic confirmation dialog with Windows XP/98 styling
     * @param title Dialog title
     * @param message Confirmation message
     * @param onConfirm Callback when OK is clicked
     */
    private fun showConfirmDialog(
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle(title)

        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        }

        // Create message TextView
        val messageText = TextView(this).apply {
            text = message
            setTextColor(Color.BLACK)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        }

        contentView.addView(messageText)

        // Create buttons container
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        // Create OK button
        val okButton = TextView(this).apply {
            text = "OK"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
        }

        // Create Cancel button
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonsContainer.addView(okButton)
        buttonsContainer.addView(cancelButton)
        contentView.addView(buttonsContainer)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(300, null)

        // OK button handler
        okButton.setOnClickListener {
            playClickSound()
            onConfirm()
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Cancel button handler
        cancelButton.setOnClickListener {
            playClickSound()
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set context menu reference
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }


    // Winamp state for permission handling
    private var winampAppInstance: rocks.gorjan.gokixp.apps.winamp.WinampApp? = null

    // WMP state for permission handling
    private var wmpAppInstance: rocks.gorjan.gokixp.apps.wmp.WmpApp? = null

    private fun showMinesweeperDialog(appInfo: AppInfo? = null) {
        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.minesweeper"  // Set identifier for tracking
        windowsDialog.setTitle("Minesweeper")
        windowsDialog.setTaskbarIcon(themeManager.getMinesweeperIcon())

        // Inflate the minesweeper layout
        val contentView = layoutInflater.inflate(R.layout.program_minesweeper, null)

        // Create Minesweeper game instance
        val minesweeperGame = MinesweeperGame(this) { soundType ->
            when (soundType) {
                "click" -> playClickSound()
                else -> playClickSound()
            }
        }

        // Setup the game
        minesweeperGame.setupGame(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(280, 372)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            minesweeperGame.cleanup()
        }

        // Cleanup on minimize
        windowsDialog.setOnMinimizeListener {
            // Game continues running when minimized
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    private fun showSolitareDialog(appInfo: AppInfo? = null) {
        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.solitare"  // Set identifier for tracking
        windowsDialog.setTitle("Solitaire")
        windowsDialog.setTaskbarIcon(themeManager.getSolitareIcon())

        // Inflate the solitare layout
        val contentView = layoutInflater.inflate(R.layout.program_solitare, null)

        // Create Solitare game instance
        val solitareGame = SolitareGame(this)

        // Setup the game
        solitareGame.setupGame(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setMaximizable(true)
        windowsDialog.setWindowSizePercentage(90f, 60f)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            solitareGame.cleanup()
        }

        // Cleanup on minimize
        windowsDialog.setOnMinimizeListener {
            // Game continues running when minimized
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    private fun showWinampDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowWinampDialog()
        }
    }

    private fun createAndShowWinampDialog() {
        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.winamp"  // Set identifier for tracking

        // Inflate the winamp content
        val contentView = layoutInflater.inflate(R.layout.program_winamp, null)

        // Create Winamp app instance
        val winampApp = rocks.gorjan.gokixp.apps.winamp.WinampApp(
            context = this,
            onRequestPermissions = {
                // Request storage permissions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // For Android 13+, request READ_MEDIA_AUDIO
                    requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO), AUDIO_PERMISSION_REQUEST_CODE)
                } else {
                    // For older Android versions, request READ_EXTERNAL_STORAGE
                    requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), AUDIO_PERMISSION_REQUEST_CODE)
                }
            },
            hasAudioPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                } else {
                    checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            },
            onShowRenameDialog = { title, initialText, hint, onConfirm ->
                showRenameDialog(title, initialText, hint, onConfirm)
            },
            onShowConfirmDialog = { title, message, onConfirm ->
                showConfirmDialog(title, message, onConfirm)
            },
            contextMenuView = contextMenu
        )

        // Store reference for permission callback
        winampAppInstance = winampApp

        // Setup the app
        winampApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(358, 420)

        // Get the custom drag view from the content
        val customDragView = contentView.findViewById<View>(R.id.dialog_title_bar)

        // Make the dialog borderless and set up dragging with the custom view
        windowsDialog.setBorderless(customDragView)

        // Set the Winamp icon for the taskbar
        windowsDialog.setTaskbarIcon(themeManager.getWinampIcon())

        // Set window title (won't be visible due to borderless, but needed for taskbar)
        windowsDialog.setTitle("Winamp")

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Keep playing when minimized
        }

        windowsDialog.setOnCloseListener {
            // Stop playback and cleanup
            winampApp.cleanup()
            winampAppInstance = null
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    private fun showWmpDialog(appInfo: AppInfo? = null) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowWmpDialog()
        }
    }

    private fun createAndShowWmpDialog() {
        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.wmp"  // Set identifier for tracking
        windowsDialog.setTitle("Windows Media Player")
        windowsDialog.setTaskbarIcon(themeManager.getWmpIcon())

        // Inflate the wmp content based on theme
        val contentView = layoutInflater.inflate(themeManager.getWmpLayout(), null)

        // Create WMP app instance
        val wmpApp = rocks.gorjan.gokixp.apps.wmp.WmpApp(
            context = this,
            onRequestPermissions = {
                // Request video permissions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // For Android 13+, request READ_MEDIA_VIDEO
                    requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO), VIDEO_PERMISSION_REQUEST_CODE)
                } else {
                    // For older Android versions, request READ_EXTERNAL_STORAGE
                    requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), VIDEO_PERMISSION_REQUEST_CODE)
                }
            },
            hasVideoPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                } else {
                    checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            },
            canRequestPermissions = {
                // Check if we should show rationale (if user denied before) or can request
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    shouldShowRequestPermissionRationale(android.Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    shouldShowRequestPermissionRationale(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
            onShowPermissionNotification = {
                // Show notification that opens app settings when tapped
                showNotification(
                    title = "Permission Missing",
                    description = "Windows Media Player needs the storage permission, tap here to grant it.",
                    onTap = {
                        // Open app settings
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                )
            }
        )

        // Store reference for permission callback
        wmpAppInstance = wmpApp

        // Setup the app
        wmpApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        if(themeManager.isClassicTheme()) {
            windowsDialog.setWindowSize(286, 412)
        }
        else if(themeManager.isXPTheme()) {
            windowsDialog.setWindowSize(384, 262)
        }else {
            windowsDialog.setWindowSize(384, 284)
        }

        // Set up window control handlers
        windowsDialog.setOnMinimizeListener {
            // Keep playing when minimized
        }

        windowsDialog.setOnCloseListener {
            // Stop playback and cleanup
            wmpApp.cleanup()
            wmpAppInstance = null
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
        }, 100) // Small delay to ensure window is fully rendered
    }

    /**
     * Shows the welcome screen once per app version
     */
    private fun showWelcomeScreenIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Get current app version
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }

        // Check if welcome was already shown for this version
        val shownForVersion = prefs.getString(KEY_SHOWN_WELCOME_FOR_VERSION, null)

        if (shownForVersion != currentVersion) {
            // Welcome not shown for this version yet, show it
            showWelcomeToWindows(showChangeLog = true)

            // Save that we've shown it for this version
            prefs.edit { putString(KEY_SHOWN_WELCOME_FOR_VERSION, currentVersion) }
        }
    }

    private fun showWelcomeToWindows(showChangeLog: Boolean = false) {
        // Get theme preferences
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"

        // Determine the layout based on theme and flavor
        val layoutRes = when (selectedTheme) {
            "Windows Classic" -> {
                val flavor = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
                when (flavor) {
                    "start_banner_95" -> R.layout.program_welcome_95
                    "start_banner_98" -> R.layout.program_welcome_98
                    "start_banner_2000" -> R.layout.program_welcome_2000
                    "start_banner_me" -> R.layout.program_welcome_me
                    else -> R.layout.program_welcome_98
                }
            }
            "Windows Vista" -> R.layout.program_welcome_vista
            else -> R.layout.program_welcome_xp // Windows XP theme
        }

        // Create MediaPlayer for welcome sound - choose based on theme
        val soundRes = when (selectedTheme) {
            "Windows Classic" -> {
                val flavor = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
                when (flavor) {
                    "start_banner_95", "start_banner_98" -> R.raw.welcome_98
                    else -> R.raw.welcome
                }
            }
            "Windows Vista" -> R.raw.welcome_vista
            else -> R.raw.welcome
        }
        val welcomeMediaPlayer = MediaPlayer.create(this, soundRes)
        welcomeMediaPlayer.isLooping = true
        welcomeMediaPlayer.start()

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Welcome to Windows")
        windowsDialog.setTaskbarIcon(themeManager.getWindowsIcon())

        // Inflate the welcome content
        val contentView = layoutInflater.inflate(layoutRes, null)
        windowsDialog.setContentView(contentView)

        // Set window size based on layout
        val (width, height) = when (layoutRes) {
            R.layout.program_welcome_xp -> Pair(354, 286)
            R.layout.program_welcome_vista -> Pair(354, 286)
            else -> Pair(354, 258)
        }
        windowsDialog.setWindowSize(width, height)

        // Get reference to the welcome text TextView
        val welcomeTextView = contentView.findViewById<TextView>(R.id.welcome_text)
        val closeButton = contentView.findViewById<View>(R.id.close_button)
        val backgroundImageView = contentView.findViewById<ImageView>(R.id.background)
        val welcomeButton = contentView.findViewById<View>(R.id.welcome_button)
        val changeLogButton = contentView.findViewById<View>(R.id.change_log_button)

        // Determine which drawables to use based on theme and flavor
        val (welcomeDrawable, changeLogDrawable) = when (selectedTheme) {
            "Windows Classic" -> {
                val flavor = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
                when (flavor) {
                    "start_banner_95" -> Pair(R.drawable.welcome_95_welcome, R.drawable.welcome_95_change_log)
                    "start_banner_98" -> Pair(R.drawable.welcome_98_welcome, R.drawable.welcome_98_change_log)
                    "start_banner_2000" -> Pair(R.drawable.welcome_2000_welcome, R.drawable.welcome_2000_change_log)
                    "start_banner_me" -> Pair(R.drawable.welcome_me_welcome, R.drawable.welcome_me_change_log)
                    else -> Pair(R.drawable.welcome_98_welcome, R.drawable.welcome_98_change_log)
                }
            }
            "Windows Vista" -> Pair(R.drawable.welcome_vista_welcome, R.drawable.welcome_vista_change_log)
            else -> Pair(R.drawable.welcome_xp_welcome, R.drawable.welcome_xp_change_log)
        }

        // Get version name
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }

        // Set welcome message based on theme
        val welcomeMessage = "Windows has updated to version $versionName, tap 'Change Log' to see what's new!.\n\nIf you like what I'm building, buy me a coffee here: https://buymeacoffee.com/jovanovski.\n\nThis is a passion project from Gorjan Jovanovski, a developer who grew up with these aesthetics and prefers them over new design any day.\n\nIf you're a 80s or 90s kid, you remember these days fondly, and this is a change to relive them on a modern daily driver, in your pocket!\n\nA few tips:\n1) Tap on things that look tappable, chances are they are.\n2) Swipe back to close the active open window.\n3) Swipe up, down and right on the desktop for different actions.\n4) Long press on the desktop to change wallpapers and themes.\n5) There are multiple Windows apps in the start menu, all with their own purpose.\n\nAll the copyrighted information belongs to their respective authors, the aim here is to just recreate nostalgia for fun.\n\nThe music you're listening to from the legendary Stan LePard, rest in peace!\n\nFor any feature requests, drop me an email at hey@gorjan.rocks\n\nThanks for using Windows!"

        // Function to format changelog text
        fun fetchChangeLogFromGitHub(callback: (String) -> Unit) {
            Thread {
                try {
                    val url = URL("https://api.github.com/repos/jovanovski/windowslauncher/releases")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val gson = Gson()
                        val releases = gson.fromJson(response, com.google.gson.JsonArray::class.java)

                        if (releases == null || releases.size() == 0) {
                            callback("No changelog available")
                            return@Thread
                        }

                        val builder = StringBuilder()
                        builder.append("Change Log\n\n")

                        // Releases are already sorted from most recent to oldest by GitHub API
                        for (i in 0 until releases.size()) {
                            val release = releases[i].asJsonObject
                            val name = release.get("name")?.asString ?: release.get("tag_name")?.asString ?: "Unknown Version"
                            val body = release.get("body")?.asString ?: ""

                            builder.append("$name\n")
                            if (body.isNotEmpty()) {
                                builder.append("$body\n")
                            }

                            if (i < releases.size() - 1) {
                                builder.append("\n")
                            }
                        }

                        callback(builder.toString())
                    } else {
                        Log.e("MainActivity", "Failed to fetch changelog: HTTP $responseCode")
                        callback("Failed to load changelog from GitHub")
                    }

                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error fetching changelog from GitHub", e)
                    callback("Error loading changelog: ${e.message}")
                }
            }.start()
        }

        // Make the text clickable with blue underlined links
        val spannableString = SpannableString(welcomeMessage)

        // Buy me a coffee link
        val linkStart = welcomeMessage.indexOf("https://buymeacoffee.com/jovanovski")
        val linkEnd = linkStart + "https://buymeacoffee.com/jovanovski".length

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW,
                    "https://buymeacoffee.com/jovanovski".toUri())
                startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color =  Color.BLUE
            }
        }

        // Email link
        val emailStart = welcomeMessage.indexOf("hey@gorjan.rocks")
        val emailEnd = emailStart + "hey@gorjan.rocks".length

        val emailClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:hey@gorjan.rocks?subject=Windows%20Launcher".toUri()
                }
                startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = Color.BLUE
            }
        }

        // Gorjan link
        val gorjanStart = welcomeMessage.indexOf("Gorjan Jovanovski")
        val gorjanEnd = gorjanStart + "Gorjan Jovanovski".length

        val gorjanClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, "https://gorjan.rocks".toUri())
                startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = Color.BLUE
            }
        }

        val versionTextView = contentView.findViewById<TextView>(R.id.version)

        // Set version text (using the versionName we already retrieved)
        versionTextView?.text = "Version: $versionName"

        spannableString.setSpan(clickableSpan, linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(emailClickableSpan, emailStart, emailEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(gorjanClickableSpan, gorjanStart, gorjanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        welcomeTextView.text = spannableString
        welcomeTextView.movementMethod = LinkMovementMethod.getInstance()

        // Set up button click listeners to switch between welcome and changelog
        welcomeButton?.setOnClickListener {
            // Switch to welcome image
            backgroundImageView.setImageResource(welcomeDrawable)

            // Restore welcome text with clickable links
            welcomeTextView.text = spannableString
            welcomeTextView.movementMethod = LinkMovementMethod.getInstance()
        }

        changeLogButton?.setOnClickListener {
            // Switch to changelog image
            backgroundImageView.setImageResource(changeLogDrawable)

            // Show loading message
            welcomeTextView.text = "Loading changelog..."
            welcomeTextView.movementMethod = null

            // Fetch and display changelog from GitHub
            fetchChangeLogFromGitHub { changeLogText ->
                runOnUiThread {
                    welcomeTextView.text = changeLogText
                }
            }
        }

        // Set up close button click handler
        closeButton?.setOnClickListener {
            welcomeMediaPlayer.stop()
            welcomeMediaPlayer.release()
            floatingWindowManager.removeWindow(windowsDialog)
        }

        windowsDialog.setOnCloseListener {
            welcomeMediaPlayer.stop()
            welcomeMediaPlayer.release()
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Trigger change log if requested
        if (showChangeLog) {
            changeLogButton?.performClick()
        }
    }

    private fun loadWallpapers(): List<WallpaperItem> {
        val wallpapers = mutableListOf<WallpaperItem>()

        // Load all wallpapers from assets in alphabetical order
        try {
            val assetManager = assets
            val wallpaperFiles = (assetManager.list("wallpapers") ?: arrayOf()).sorted()
            
            for (fileName in wallpaperFiles) {
                if (fileName.matches(".*\\.(png|jpg|jpeg|webp)$".toRegex(RegexOption.IGNORE_CASE)) && fileName != "README.txt") {
                    try {
                        val inputStream = assetManager.open("wallpapers/$fileName")
                        val drawable = Drawable.createFromStream(inputStream, fileName)
                        inputStream.close()
                        
                        if (drawable != null) {
                            val filePath = "wallpapers/$fileName"
                            wallpapers.add(WallpaperItem(
                                name = fileName.substringBeforeLast("."),
                                drawable = drawable,
                                isCurrent = false,
                                filePath = filePath,
                                isBuiltIn = false
                            ))
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Failed to load wallpaper: $fileName", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to load wallpapers", e)
        }
        
        return wallpapers
    }

    private fun applyCustomWallpaper(wallpaperItem: WallpaperItem) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

        // All wallpapers are now from assets
        wallpaperItem.filePath?.let { filePath ->
            prefs.edit {
                putString(pathKey, filePath)
                // Clear URI when setting asset wallpaper
                remove(uriKey)
            }
            applyCustomWallpaperFromAssets(filePath)
        }
    }
    
    private fun applyCustomWallpaperFromAssets(filePath: String) {
        try {
            val inputStream = assets.open(filePath)
            val drawable = Drawable.createFromStream(inputStream, filePath)
            inputStream.close()

            if (drawable != null) {
                applyWallpaperDrawable(drawable)
                Log.d("MainActivity", "Applied custom wallpaper: $filePath")
            } else {
                throw Exception("Failed to create drawable from asset")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to apply custom wallpaper: $filePath", e)
        }
    }

    private fun applyWallpaperDrawable(drawable: Drawable, uri: Uri? = null) {
        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)

        // Create or find existing wallpaper ImageView
        var wallpaperImageView = mainBackground.findViewWithTag<ImageView>("wallpaper")

        if (wallpaperImageView == null) {
            // Create new ImageView for wallpaper
            wallpaperImageView = ImageView(this)
            wallpaperImageView.tag = "wallpaper"
            wallpaperImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            wallpaperImageView.adjustViewBounds = false

            // Add as first child (behind everything else)
            val layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            mainBackground.addView(wallpaperImageView, 0, layoutParams)
        }

        // Set the wallpaper image
        wallpaperImageView.setImageDrawable(drawable)

        // Remove any background from the RelativeLayout
        mainBackground.background = null

        // If URI is provided, save it to SharedPreferences
        if (uri != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

            // Release any existing persistent URI permission for this theme
            val oldUri = prefs.getString(uriKey, null)
            if (oldUri != null) {
                try {
                    val oldUriParsed = oldUri.toUri()
                    contentResolver.releasePersistableUriPermission(
                        oldUriParsed,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d("MainActivity", "Released old persistent URI permission for: $oldUri")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Could not release old URI permission for: $oldUri", e)
                }
            }

            // Save the URI as current wallpaper for the current theme
            prefs.edit {
                putString(uriKey, uri.toString())
                // Clear path when setting custom URI
                remove(pathKey)
            }
            Log.d("MainActivity", "Saved custom wallpaper URI: $uri")
        }
    }

    /**
     * Release wallpaper bitmap to save memory when app goes to background
     */
    private fun releaseWallpaperBitmap() {
        try {
            val mainBackground = findViewById<RelativeLayout>(R.id.main_background)
            val wallpaperImageView = mainBackground.findViewWithTag<ImageView>("wallpaper")

            if (wallpaperImageView != null) {
                wallpaperImageView.setImageDrawable(null)
                Log.d("MainActivity", "Released wallpaper bitmap to save memory")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Error releasing wallpaper bitmap", e)
        }
    }

    /**
     * Reload wallpaper bitmap when app returns to foreground
     */
    private fun reloadWallpaperBitmap() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

            // Check if we have a custom wallpaper URI
            val uriString = prefs.getString(uriKey, null)
            if (uriString != null) {
                val uri = uriString.toUri()

                // Reload with downsampling
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                val displayMetrics = resources.displayMetrics
                val targetWidth = minOf(displayMetrics.widthPixels, 1080)
                val targetHeight = minOf(displayMetrics.heightPixels, 1920)

                options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565

                val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (bitmap != null) {
                    val drawable = bitmap.toDrawable(resources)
                    applyWallpaperDrawable(drawable)
                    Log.d("MainActivity", "Reloaded wallpaper bitmap: ${bitmap.width}x${bitmap.height}, ${bitmap.byteCount / 1024}KB")
                }
            } else {
                // Check for built-in wallpaper path
                val path = prefs.getString(pathKey, null)
                if (path != null) {
                    try {
                        val drawable = Drawable.createFromStream(assets.open(path), path)
                        if (drawable != null) {
                            applyWallpaperDrawable(drawable)
                            Log.d("MainActivity", "Reloaded built-in wallpaper: $path")
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Could not reload built-in wallpaper: $path", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Error reloading wallpaper bitmap", e)
        }
    }

    /**
     * Calculate sample size for bitmap downsampling to reduce memory usage
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        Log.d("MainActivity", "Image downsampling: ${width}x${height} -> target ${reqWidth}x${reqHeight}, sample size: $inSampleSize")
        return inSampleSize
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            // First, decode with inJustDecodeBounds=true to check dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            Log.d("MainActivity", "Original wallpaper size: ${options.outWidth}x${options.outHeight}")

            // Calculate target size based on screen dimensions (limit to 1080p for memory efficiency)
            val displayMetrics = resources.displayMetrics
            val targetWidth = minOf(displayMetrics.widthPixels, 1080)
            val targetHeight = minOf(displayMetrics.heightPixels, 1920)

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)

            // Decode bitmap with inSampleSize set and use RGB_565 for non-transparent images (50% memory savings)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 50% memory vs ARGB_8888

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (bitmap != null) {
                Log.d("MainActivity", "Downsampled wallpaper size: ${bitmap.width}x${bitmap.height}, memory: ${bitmap.byteCount / 1024}KB")

                val drawable = bitmap.toDrawable(resources)

                // Take persistent URI permission to survive app updates
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d("MainActivity", "Took persistent URI permission for: $uri")
                } catch (e: SecurityException) {
                    Log.w("MainActivity", "Could not take persistent URI permission for: $uri", e)
                    // Continue anyway, the URI might still work temporarily
                }

                // Show wallpaper target selection dialog FIRST, before applying anything
                // The dialog will handle applying the wallpaper based on user selection
                showWallpaperTargetDialog(null, uri, drawable)
                Log.d("MainActivity", "Showing wallpaper target dialog for custom wallpaper: $uri")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load custom wallpaper from device", e)
        }
    }

    private fun applyThemeFontsToDialog(contentView: View) {
        val fontResId = fontManager.getFontFamilyRes(themeManager.getSelectedTheme())

        val typeface = try {
            androidx.core.content.res.ResourcesCompat.getFont(this, fontResId)
        } catch (e: Exception) {
            null
        }

        // Find TextViews by traversing the view hierarchy
        applyFontToAllTextViews(contentView, typeface)
    }

    private fun applyFontToAllTextViews(parent: View, typeface: android.graphics.Typeface?) {
        if (parent is TextView) {
            parent.typeface = typeface
        } else if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                applyFontToAllTextViews(parent.getChildAt(i), typeface)
            }
        }
    }

    // Helper methods for theme-based font management
    fun getThemePrimaryFont(): android.graphics.Typeface? {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(R.attr.primaryFontFamily, typedValue, true)) {
            androidx.core.content.res.ResourcesCompat.getFont(this, typedValue.resourceId)
        } else {
            null
        }
    }

    private fun getThemeSecondaryFont(): android.graphics.Typeface? {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(R.attr.secondaryFontFamily, typedValue, true)) {
            androidx.core.content.res.ResourcesCompat.getFont(this, typedValue.resourceId)
        } else {
            null
        }
    }

    fun applyThemeFontToTextView(textView: TextView, usePrimary: Boolean = true) {
        val font = if (usePrimary) getThemePrimaryFont() else getThemeSecondaryFont()
        textView.typeface = font
    }

    private fun swapTaskbarLayout(layoutResId: Int) {
        val oldTaskbar = findViewById<View>(R.id.taskbar_container)

        if (oldTaskbar != null) {
            // Store the layout parameters from the old taskbar
            val layoutParams = oldTaskbar.layoutParams
            val oldParent = oldTaskbar.parent as ViewGroup
            val indexInParent = oldParent.indexOfChild(oldTaskbar)

            // Remove the old taskbar
            oldParent.removeView(oldTaskbar)

            // Inflate the new taskbar layout
            val newTaskbar = layoutInflater.inflate(layoutResId, null)
            newTaskbar.id = R.id.taskbar_container

            // Set height based on theme - Vista taskbar is taller
            val taskbarHeight = if (layoutResId == R.layout.taskbar_vista) {
                (45 * resources.displayMetrics.density).toInt()
            } else {
                (40 * resources.displayMetrics.density).toInt()
            }

            // Update layout params with new height
            if (layoutParams is RelativeLayout.LayoutParams) {
                layoutParams.height = taskbarHeight
            }
            newTaskbar.layoutParams = layoutParams

            // Add the new taskbar at the same position
            oldParent.addView(newTaskbar, indexInParent)

            // Re-initialize taskbar elements and event handlers
            initializeTaskbarElements()
        }
    }

    private fun initializeTaskbarElements() {
        dateDay = findViewById(R.id.date_day)
        dateOrdinal = findViewById(R.id.date_ordinal)
        clockTime = findViewById(R.id.clock_time)

        // Reinitialize update icon and restore its state
        updateIcon = findViewById(R.id.update_icon)
        Log.d("MainActivity", "initializeTaskbarElements: updateIcon reinitialized")

        // Restore update icon visibility if an update was previously detected
        if (updateDownloadLink != null) {
            updateIcon.visibility = View.VISIBLE
            Log.d("MainActivity", "initializeTaskbarElements: Restored update icon visibility to VISIBLE")
        }

        // Set up update icon click listener
        updateIcon.setOnClickListener {
            updateDownloadLink?.let { link ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = link.toUri()
                    startActivity(intent)
                    playClickSound()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error opening update link", e)
                }
            }
        }

        // Set up start button click
        val startButton = findViewById<ImageView>(R.id.start_button)
        startButton.setOnClickListener {
            Log.d("MainActivity", "Start button clicked!")
            playClickSound()
            toggleStartMenu()
        }

        // Add long press listener to start button
        startButton.setOnLongClickListener { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = (location[0] + view.width / 2).toFloat()
            val y = (location[1] + view.height / 2).toFloat()
            showStartMenuContextMenu(x, y)
            true
        }

        // Set up taskbar empty space click
        handler.post {
            try {
                val taskbarContainer = findViewById<View>(R.id.taskbar_container)
                val taskbarEmptySpace = taskbarContainer.findViewById<View>(R.id.taskbar_empty_space)
                taskbarEmptySpace?.setOnClickListener {
                    Log.d("MainActivity", "Taskbar empty space clicked!")
                    launchWebSearch()
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to set up taskbar empty space click handler: ${e.message}")
            }
        }

        // Set up click listeners
        val dateContainer = findViewById<LinearLayout>(R.id.date_container)
        dateContainer.setOnClickListener { openCalendarApp() }

        clockTime.setOnClickListener { openClockApp() }

        // Long press to toggle clock format (24-hour <-> 12-hour)
        clockTime.setOnLongClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val is24Hour = prefs.getBoolean(KEY_CLOCK_24_HOUR, true)
            val newFormat = !is24Hour

            // Save new format preference
            prefs.edit { putBoolean(KEY_CLOCK_24_HOUR, newFormat) }

            // Show feedback to user
            val formatName = if (newFormat) "24-hour" else "12-hour"
            playClickSound()

            true // Consume the long press event
        }

        // Set up volume icon click
        val volumeIcon = findViewById<ImageView>(R.id.volume_icon)
        volumeIcon?.setOnClickListener {
            toggleSoundMute()
        }

        // Set up weather temperature click
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        weatherTemp?.setOnClickListener {
            handleWeatherTempTap()
        }

        // Long press to toggle temperature unit
        weatherTemp?.setOnLongClickListener {
            toggleWeatherUnit()
            updateWeatherTemperature()
            true
        }

        // Initialize volume icon state
        updateVolumeIcon()

        // Set up gesture bar toggle functionality
        setupGestureBarToggle()
    }

    private fun playStartupSound() {

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if(themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
            val currentBanner = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
            when (currentBanner) {
                "start_banner_me", "start_banner_2000" -> {
                    playSound(R.raw.startup_2000)
                }
                "start_banner_95" -> {
                    playSound(R.raw.startup_95)
                }
                else -> {
                    playSound(R.raw.startup_98)
                }
            }
        }
        else if(themeManager.getSelectedTheme() is AppTheme.WindowsVista) {
            playSound(R.raw.startup_vista)
        }
        else{
            playSound(R.raw.startup)
        }
    }
    
    private fun handleShutdown(isLogoff: Boolean = false) {
        // Close start menu if it's open
        if (isStartMenuVisible) {
            hideStartMenu()
        }
        
        playShutdownSound()
        // Delay the screen lock to allow sound to play
        Handler(Looper.getMainLooper()).postDelayed({
            if(themeManager.isClassicTheme()) {
                if(isLogoff){
                    lockScreen()
                }
                else {
                    showSafeToTurnOffScreen()
                    Handler(Looper.getMainLooper()).postDelayed({
                        lockScreen()
                    }, 1500)
                }
            }
            else{
                lockScreen()
            }
        }, 1500) // 1 second delay
    }
    
    private fun playShutdownSound() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if(themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
            val currentBanner = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"
            when (currentBanner) {
                "start_banner_me", "start_banner_2000" -> {
                    playSound(R.raw.shutdown_2000)
                }
                "start_banner_95" -> {
                    playSound(R.raw.shutdown_98)
                }
                else -> {
                    playSound(R.raw.shutdown_98)
                }
            }
        }
        else if(themeManager.getSelectedTheme() is AppTheme.WindowsVista) {
            playSound(R.raw.shutdown_vista)
        }
        else{
            playSound(R.raw.shutdown)
        }
    }

    private fun showSafeToTurnOffScreen() {
        val safeToTurnOffSplash = findViewById<ImageView>(R.id.safe_to_turn_off_splash)
        safeToTurnOffSplash?.visibility = View.VISIBLE
    }

    private fun lockScreen() {
        // Check Android version compatibility

        if (LockScreenAccessibilityService.isServiceEnabled()) {
            // Accessibility service is enabled, use it to lock screen
            if (LockScreenAccessibilityService.lockScreen()) {
                Log.d("MainActivity", "Screen locked using accessibility service")
                return
            } else {
                Log.w("MainActivity", "Failed to lock screen with accessibility service")
            }
        } else {
            // Accessibility service not enabled, request it
            Toast.makeText(this,"Enable the 'Windows Launcher' accessibility service to use screen lock", Toast.LENGTH_LONG).show()
            requestAccessibilityPermission()
            return
        }
        
        // Fallback to home screen
        goToHomeScreen()
    }
    
    private fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(homeIntent)
            moveTaskToBack(true)
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to go to home screen", e)
        }
    }
    
    private fun requestAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            
            // Show additional guidance
            Handler(Looper.getMainLooper()).postDelayed({
                showNotification("Enable Service", "Find 'Windows Launcher' in the list and turn it ON")
            }, 1500)
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to open accessibility settings", e)
            showNotification("Permissions needed", "Please go to Settings > Accessibility and enable Windows Launcher")
        }
    }
    
    private fun loadSavedWallpaper() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

        // Check for custom URI first (from image picker)
        val customWallpaperUri = prefs.getString(uriKey, null)
        if (customWallpaperUri != null) {
            try {
                val uri = customWallpaperUri.toUri()
                applyCustomWallpaperFromUri(uri)
                return
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load custom wallpaper URI: $customWallpaperUri", e)
                // Remove invalid URI and fall back to default
                prefs.edit { remove(uriKey) }
            }
        }

        // Check for asset path
        val customWallpaperPath = prefs.getString(pathKey, null)
        if (customWallpaperPath != null) {
            applyCustomWallpaperFromAssets(customWallpaperPath)
        } else {
            // Set and apply default wallpaper for theme
            val defaultWallpaper = getDefaultWallpaperForTheme()
            prefs.edit { putString(pathKey, defaultWallpaper) }
            applyCustomWallpaperFromAssets(defaultWallpaper)
        }
    }

    private fun applyCustomWallpaperFromUri(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.let { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                stream.close()
                if (bitmap != null) {
                    val drawable = bitmap.toDrawable(resources)
                    applyWallpaperDrawable(drawable)
                    Log.d("MainActivity", "Applied custom wallpaper from URI: $uri")
                } else {
                    throw Exception("Failed to decode bitmap from URI")
                }
            } ?: throw Exception("Failed to open input stream")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to apply custom wallpaper from URI: $uri", e)
            // Remove invalid URI
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val (_, uriKey) = getCurrentThemeWallpaperKeys()
            prefs.edit { remove(uriKey) }
            // Fall back to default wallpaper
            val defaultWallpaper = getDefaultWallpaperForTheme()
            applyCustomWallpaperFromAssets(defaultWallpaper)
        }
    }
    
    private fun enableEdgeToEdge() {
        try {
            // Android 11 and above - extend behind system bars but keep them visible
            window.setDecorFitsSystemWindows(false)
            // Don't hide the system bars, just allow content to draw behind them
        } catch (e: Exception) {
            Log.e("MainActivity", "Error enabling edge-to-edge", e)
        }
    }

    fun playClickSound() {
        playSound(R.raw.click)
    }

    fun playEmailSound() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val playEmailSound = prefs.getBoolean(KEY_PLAY_EMAIL_SOUND, true)
        if (playEmailSound) {
            playSound(R.raw.youve_got_mail)
        }
    }

    private fun playDingSound() {
        // Bypass mute check since this is specifically for unmute confirmation
        if(themeManager.getSelectedTheme() == AppTheme.WindowsVista){
            playSound(R.raw.ding_vista, bypassMute = true)
        }
        else{
            playSound(R.raw.ding, bypassMute = true)
        }

    }

    private fun addDesktopIcon(appInfo: AppInfo, x: Float = 100f, y: Float = 100f) {

        // Use custom icon if available, otherwise use app icon
        val iconToUse = getAppIcon(appInfo.packageName) ?: appInfo.icon

        // Convert x/y to grid index for current orientation
        val currentOrientation = getCurrentOrientation()
        val gridIndex = if (desktopContainer.width > 0) {
            convertXYToGridIndex(x, y, currentOrientation)
        } else {
            null // Will be assigned during migration/reflow
        }

        val desktopIcon = DesktopIcon(
            name = appInfo.name,
            packageName = appInfo.packageName,
            icon = iconToUse,
            x = x,
            y = y,
            portraitGridIndex = if (currentOrientation == ScreenOrientation.PORTRAIT) gridIndex else null,
            landscapeGridIndex = if (currentOrientation == ScreenOrientation.LANDSCAPE) gridIndex else null
        )

        desktopIcons.add(desktopIcon)
        Log.d("MainActivity", "Added desktop icon ${appInfo.name} with grid index $gridIndex for $currentOrientation")
        
        // Create appropriate icon view (RecycleBinView for recycle bin, DesktopIconView for others)
        val iconView = if (appInfo.packageName == "recycle.bin") {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
            Log.d("MainActivity", "OPA: selectedTheme = $selectedTheme")

            RecycleBinView(this).apply {
                setDesktopIcon(desktopIcon)
                // Apply current theme for recycle bin
                Log.d("MainActivity", "OPA: selectedTheme = $selectedTheme")
                val isClassic = themeManager.getSelectedTheme() is AppTheme.WindowsClassic
                setThemeFont(isClassic)
                setThemeIcon(isClassic)
            }
        } else {
            DesktopIconView(this).apply {
                setDesktopIcon(desktopIcon)
                setThemeFont(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
            }
        }
        
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        
        desktopContainer.addView(iconView, layoutParams)
        desktopIconViews.add(iconView)
        
        // Set position after adding to container
        iconView.post {
            iconView.x = x
            iconView.y = y
        }
        
        saveDesktopIcons()
    }
    
    
    fun saveDesktopIconPosition(desktopIcon: DesktopIcon?) {
        desktopIcon?.let {

            // Verify this icon is in the desktopIcons list
            val foundIcon = desktopIcons.find { icon -> icon.id == it.id }
            if (foundIcon == null) {
                Log.e("MainActivity", "ERROR: Icon ${it.name} with ID ${it.id} NOT FOUND in desktopIcons list!")
            } else if (foundIcon !== it) {
                Log.e("MainActivity", "ERROR: Icon ${it.name} is a DIFFERENT OBJECT than the one in desktopIcons list!")
                Log.e("MainActivity", "  View icon position: x=${it.x}, y=${it.y}")
                Log.e("MainActivity", "  List icon position: x=${foundIcon.x}, y=${foundIcon.y}")
            }

            saveDesktopIcons()
        }
    }
    
    private fun saveDesktopIcons() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()

        // Convert to serializable data
        val serializedIcons = desktopIcons.map { icon ->
            mapOf(
                "name" to icon.name,
                "packageName" to icon.packageName,
                "x" to icon.x,  // Keep for backwards compatibility
                "y" to icon.y,  // Keep for backwards compatibility
                "id" to icon.id,
                "type" to icon.type.name,
                "parentFolderId" to icon.parentFolderId,
                "portraitGridIndex" to icon.portraitGridIndex,
                "landscapeGridIndex" to icon.landscapeGridIndex
            )
        }

        val json = gson.toJson(serializedIcons)
        prefs.edit { putString(KEY_DESKTOP_ICONS, json) }
        Log.d("MainActivity", "Saved ${desktopIcons.size} desktop icons with grid indices")
    }
    
    private fun loadDesktopIcons() {

        // Clear all existing desktop icons and views
        desktopIconViews.forEach { view ->
            desktopContainer.removeView(view)
        }
        desktopIconViews.clear()
        desktopIcons.clear()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_DESKTOP_ICONS, null) ?: return


        try {
            val gson = Gson()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val serializedIcons: List<Map<String, Any>> = gson.fromJson(json, type)


            val packageManager = packageManager

            serializedIcons.forEach { iconData ->
                val packageName = iconData["packageName"] as String
                val name = iconData["name"] as String
                val x = (iconData["x"] as Double).toFloat()
                val y = (iconData["y"] as Double).toFloat()
                val id = iconData["id"] as String
                val parentFolderId = iconData["parentFolderId"] as? String
                val typeStr = iconData["type"] as? String

                // Read grid indices (may be null for old data)
                val portraitGridIndex = (iconData["portraitGridIndex"] as? Double)?.toInt()
                val landscapeGridIndex = (iconData["landscapeGridIndex"] as? Double)?.toInt()


                val iconType = if (typeStr != null) {
                    try {
                        IconType.valueOf(typeStr)
                    } catch (e: Exception) {
                        if (packageName == "recycle.bin") IconType.RECYCLE_BIN else IconType.APP
                    }
                } else {
                    if (packageName == "recycle.bin") IconType.RECYCLE_BIN else IconType.APP
                }

                try {
                    val icon = when (iconType) {
                        IconType.RECYCLE_BIN -> {
                            // Special case for recycle bin - use recycle drawable
                            AppCompatResources.getDrawable(this, R.drawable.recycle)!!
                        }
                        IconType.FOLDER -> {
                            // Use custom icon if available, otherwise use theme-appropriate folder icon
                            getAppIcon(packageName) ?: run {
                                val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
                                AppCompatResources.getDrawable(this, if (selectedTheme == "Windows Classic") R.drawable.folder_98 else if (selectedTheme == "Windows Vista") R.drawable.folder_vista else R.drawable.folder_xp)!!
                            }
                        }
                        IconType.APP -> {
                            // Check if this is a system app first
                            if (isSystemApp(packageName)) {
                                // Use custom icon if available, otherwise load from system app list
                                getAppIcon(packageName) ?: run {
                                    getSystemAppsList().find { it.packageName == packageName }?.icon
                                        ?: AppCompatResources.getDrawable(this, themeManager.getIEIcon())!! // Fallback to IE icon
                                }
                            } else {
                                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                                // Use custom icon if available, otherwise fallback to default app icon
                                getAppIcon(packageName) ?: appInfo.loadIcon(packageManager)
                            }
                        }
                    }

                    val desktopIcon = DesktopIcon(name, packageName, icon, x, y, id, iconType, parentFolderId, portraitGridIndex, landscapeGridIndex)
                    desktopIcons.add(desktopIcon)

                    // Skip icons that are inside folders - they shouldn't be shown on desktop
                    if (parentFolderId != null) {
                        return@forEach
                    }

                    // Create appropriate icon view
                    val iconView = when (iconType) {
                        IconType.RECYCLE_BIN -> {
                            RecycleBinView(this).apply {
                                setDesktopIcon(desktopIcon)
                                // Apply current theme for recycle bin
                                val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
                                Log.d("MainActivity", "LoadDesktopIcons: selectedTheme = $selectedTheme")
                                setThemeFont(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
                                setThemeIcon(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
                            }
                        }
                        IconType.FOLDER -> {
                            FolderView(this).apply {
                                setDesktopIcon(desktopIcon)
                                // Apply current theme for folder
                                Log.d("MainActivity", "LoadDesktopIcons: loading folder = $name")
                                setThemeFont(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
                                // Only set theme icon if there's no custom icon mapping
                                if (!customIconMappings.containsKey(packageName)) {
                                    setThemeIcon(themeManager.getSelectedTheme())
                                }
                            }
                        }
                        IconType.APP -> {
                            DesktopIconView(this).apply { setDesktopIcon(desktopIcon) }
                        }
                    }

                    val layoutParams = RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT
                    )

                    desktopContainer.addView(iconView, layoutParams)
                    desktopIconViews.add(iconView)
                    // Apply current theme font
                    val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
                    iconView.setThemeFont(selectedTheme == "Windows Classic")

                    // Set position after adding to container
                    // NOTE: Position will be set by positionIconsFromGridIndices() after all icons are loaded
                    iconView.post {
                        iconView.x = x
                        iconView.y = y
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading desktop icon: $packageName", e)
                }
            }

            Log.d("MainActivity", "=== LOAD COMPLETE ===")
            Log.d("MainActivity", "Total icons in desktopIcons list: ${desktopIcons.size}")
            Log.d("MainActivity", "Icons in folders: ${desktopIcons.count { it.parentFolderId != null }}")
            Log.d("MainActivity", "Icons on desktop: ${desktopIcons.count { it.parentFolderId == null }}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading desktop icons", e)
        }

        // Ensure recycle bin exists as desktop icon (after loading existing icons)
        ensureRecycleBinExists()

        // Post to ensure container has dimensions
        desktopContainer.post {
            // Migrate old x/y positions to grid indices if needed
            migrateIconsToGridSystem()

            // Position icons based on grid indices for current orientation
            positionIconsFromGridIndices()

            // Reflow any icons without position in current orientation
            reflowIconsWithoutPosition()

            // Save after migration and reflow
            saveDesktopIcons()
        }
    }
    
    private fun ensureRecycleBinExists() {
        // Check if recycle bin already exists in desktop icons
        val recycleBinExists = desktopIcons.any { it.packageName == "recycle.bin" }
        
        if (!recycleBinExists) {
            // Create recycle bin as a regular desktop icon with theme-appropriate icon
            // Use reliable theme detection method
            val expectedIconsPath = getCustomIconsPath()
            val currentTheme = if (expectedIconsPath == "custom_icons_98") "Windows Classic" else "Windows XP"
            val iconResource = if (currentTheme == "Windows Classic") {
                R.drawable.recycle_98
            } else {
                R.drawable.recycle
            }
            val recycleDrawable = AppCompatResources.getDrawable(this, iconResource)!!
            val recycleBinAppInfo = AppInfo(
                name = "Recycle Bin",
                packageName = "recycle.bin",
                icon = recycleDrawable
            )
            
            // Set default position (bottom-right)
            val defaultX = resources.displayMetrics.widthPixels - 150f
            val defaultY = resources.displayMetrics.heightPixels - 350f
            
            addDesktopIcon(recycleBinAppInfo, defaultX, defaultY)
            saveDesktopIcons() // Save immediately
        }
        
        // Update recycleBin reference to point to the RecycleBinView (after all icons are loaded)
        Handler(Looper.getMainLooper()).post { updateRecycleBinReference() }
        
        Log.d("MainActivity", "Recycle bin ensured in desktop icons")
    }
    
    private fun updateRecycleBinReference() {
        // Find the RecycleBinView in desktopIconViews
        recycleBin = desktopIconViews.find {
            it is RecycleBinView
        } as? RecycleBinView ?: throw IllegalStateException("RecycleBinView not found in desktop icons")
        // Restore visibility state - if hidden, remove from desktop
        if (!isRecycleBinVisible()) {
            hideRecycleBin()
        }
    }
    
    fun deleteDesktopIcon(iconView: DesktopIconView) {
        // Play recycle sound
        playRecycleSound()

        // Remove from views list
        desktopIconViews.remove(iconView)

        // Remove from desktop container
        desktopContainer.removeView(iconView)

        // Find and remove from desktopIcons list
        val iconToRemove = iconView.getDesktopIcon()
        iconToRemove?.let { icon ->
            // If it's a folder, delete all contents recursively
            if (icon.type == IconType.FOLDER) {
                deleteFolderAndContents(icon.id)
            } else {
                // Just remove this icon
                desktopIcons.removeAll { it.id == icon.id }
            }
        }

        // Save updated icons
        saveDesktopIcons()

        // Refresh all open folder windows
        refreshAllOpenFolders()
    }

    private fun refreshAllOpenFolders() {
        Log.d("MainActivity", "Refreshing all open folder windows")

        // Get all active windows from the floating window manager
        val activeWindows = floatingWindowManager.getAllActiveWindows()

        Log.d("MainActivity", "Found ${activeWindows.size} active windows")

        // Refresh each window (they might be folder windows)
        activeWindows.forEach { dialog ->
            try {
                refreshFolderGridLayout(dialog)
            } catch (e: Exception) {
                // Window might not be a folder window, ignore
                Log.d("MainActivity", "Could not refresh window (might not be a folder): ${e.message}")
            }
        }
    }

    fun addIconToFolder(iconView: DesktopIconView, folderView: FolderView) {
        val icon = iconView.getDesktopIcon() ?: return
        val folder = folderView.getDesktopIcon() ?: return

        Log.d("MainActivity", "Adding icon ${icon.name} to folder ${folder.name}")

        // Set the parent folder ID
        icon.parentFolderId = folder.id

        // Remove the icon view from desktop
        desktopIconViews.remove(iconView)
        desktopContainer.removeView(iconView)

        // Save updated icons (icon is still in desktopIcons list, just has parentFolderId set)
        saveDesktopIcons()

        // Refresh all open folder windows to show the new icon
        refreshAllOpenFolders()

        Log.d("MainActivity", "Icon successfully moved to folder")
    }

    fun isOverRecycleBin(x: Float, y: Float): Boolean {
        // Return false if recycle bin is not visible
        if (!isRecycleBinVisible() || !::recycleBin.isInitialized || recycleBin.parent == null) {
            return false
        }

        // Get recycle bin bounds
        val recycleBinX = recycleBin.x
        val recycleBinY = recycleBin.y
        val recycleBinWidth = recycleBin.width
        val recycleBinHeight = recycleBin.height

        // Check if coordinates are within recycle bin bounds with some tolerance
        val tolerance = 20 // pixels
        return x >= recycleBinX - tolerance &&
               x <= recycleBinX + recycleBinWidth + tolerance &&
               y >= recycleBinY - tolerance &&
               y <= recycleBinY + recycleBinHeight + tolerance
    }

    fun isOverFolder(x: Float, y: Float): FolderView? {
        // Check all desktop icon views to see if any folders are under the coordinates
        desktopIconViews.forEach { iconView ->
            if (iconView is FolderView && iconView.parent != null && iconView.isVisible) {
                val folderX = iconView.x
                val folderY = iconView.y
                val folderWidth = iconView.width
                val folderHeight = iconView.height

                // Check if coordinates are within folder bounds with some tolerance
                val tolerance = 20 // pixels
                if (x >= folderX - tolerance &&
                    x <= folderX + folderWidth + tolerance &&
                    y >= folderY - tolerance &&
                    y <= folderY + folderHeight + tolerance) {
                    return iconView
                }
            }
        }
        return null
    }

    private fun getPinnedApps(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // First try to get as string (new format)
        try {
            val pinnedAppsString = prefs.getString(KEY_PINNED_APPS, "") ?: ""
            if (pinnedAppsString.isNotEmpty()) {
                return pinnedAppsString.split(",")
            }
        } catch (e: ClassCastException) {
            Log.w("MainActivity", "Found old format pinned apps data, migrating...")
        }

        return emptyList()
    }
    
    private fun togglePinnedApp(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentPinned = getPinnedApps().toMutableList()

        if (currentPinned.contains(packageName)) {
            // Unpin the app - no sorting needed
            currentPinned.remove(packageName)
            val pinnedAppsString = currentPinned.joinToString(",")
            prefs.edit { putString(KEY_PINNED_APPS, pinnedAppsString) }
            Log.d("MainActivity", "Unpinned app: $packageName")
        } else {
            // Pin the app - add immediately without sorting for instant UI response
            currentPinned.add(packageName)
            val pinnedAppsString = currentPinned.joinToString(",")
            prefs.edit { putString(KEY_PINNED_APPS, pinnedAppsString) }
            Log.d("MainActivity", "Pinned app: $packageName (will sort in background)")

            // Sort in background thread to avoid UI freeze
            Thread {
                try {
                    val packageManager = packageManager
                    val sortedPinned = currentPinned.sortedBy { pkg ->
                        try {
                            // Check if it's a system app
                            if (pkg.startsWith("system.")) {
                                // Get name from system apps list
                                val systemApps = getSystemAppsList()
                                val systemApp = systemApps.find { it.packageName == pkg }
                                systemApp?.name?.lowercase() ?: pkg.lowercase()
                            } else {
                                // Regular app - get from package manager
                                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                                packageManager.getApplicationLabel(appInfo).toString().lowercase()
                            }
                        } catch (e: Exception) {
                            pkg.lowercase()
                        }
                    }

                    // Update with sorted list
                    runOnUiThread {
                        val sortedAppsString = sortedPinned.joinToString(",")
                        prefs.edit { putString(KEY_PINNED_APPS, sortedAppsString) }

                        // Refresh UI with sorted list
                        val commandsRecyclerView = findViewById<RecyclerView>(R.id.commands_recycler_view)
                        if (commandsRecyclerView != null) {
                            setupCommandsList(commandsRecyclerView)
                        }
                        Log.d("MainActivity", "Sorted pinned apps in background: $sortedPinned")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error sorting pinned apps in background", e)
                }
            }.start()
        }
    }
    
    private fun isAppPinned(packageName: String): Boolean {
        return getPinnedApps().contains(packageName)
    }

    private fun refreshDesktopIcons() {
        Log.d("MainActivity", "Refreshing desktop icons to get updated dynamic icons")
        
        // Make all icons and recycle bin disappear first
        desktopIconViews.forEach { iconView ->
            iconView.alpha = 0f
        }

        loadDesktopIcons()
        
        // Wait 200ms, then refresh and make them reappear
        Handler(Looper.getMainLooper()).postDelayed({
            desktopIcons.forEachIndexed { index, icon ->
                val iconView = desktopIconViews.getOrNull(index)
                if (iconView != null) {
                    // Skip recycle bin - it has its own theme handling via recycleBin.setThemeIcon()
                    if (icon.packageName == "recycle.bin") {
                        Log.d("MainActivity", "Skipping recycle bin refresh - handled separately by setThemeIcon()")
                        iconView.alpha = 1f // Make it visible again
                        return@forEachIndexed
                    }

                    // Make the icon reappear
                    iconView.alpha = 1f
                }
            }
            
            // Also refresh start menu apps
            loadInstalledApps()
            Log.d("MainActivity", "Desktop icon refresh complete")
        }, 200)
    }

    // ========== NEW RESPONSIVE GRID SYSTEM ==========

    /**
     * Setup foldable device detection using WindowManager library
     */
    private fun setupFoldableDeviceDetection() {
        lifecycleScope.launch {
            val windowInfoTracker = WindowInfoTracker.getOrCreate(this@MainActivity)
            windowInfoTracker.windowLayoutInfo(this@MainActivity)
                .collectLatest { info ->
                    val foldingFeature = info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()

                    val previousState = isFoldableUnfolded

                    if (foldingFeature != null) {
                        // Device has a folding feature (hinge)
                        isFoldableUnfolded = when (foldingFeature.state) {
                            FoldingFeature.State.FLAT -> {
                                // Device is fully unfolded → using internal (main) screen
                                Log.d("MainActivity", "Foldable device detected: FLAT (unfolded)")
                                true
                            }
                            FoldingFeature.State.HALF_OPENED -> {
                                // Device is partially folded (like laptop mode)
                                Log.d("MainActivity", "Foldable device detected: HALF_OPENED")
                                true
                            }
                            else -> {
                                Log.d("MainActivity", "Foldable device detected: ${foldingFeature.state}")
                                false
                            }
                        }
                    } else {
                        // No folding feature detected - this is a regular phone or tablet
                        // Don't override orientation for tablets/large phones
                        // Only actual foldables with a hinge should trigger landscape mode in portrait
                        isFoldableUnfolded = false
                        Log.d("MainActivity", "No folding feature detected - using device orientation")
                    }

                    // If state changed, refresh desktop layout
                    if (previousState != isFoldableUnfolded) {
                        Log.d("MainActivity", "Foldable state changed from $previousState to $isFoldableUnfolded - refreshing desktop")
                        runOnUiThread {
                            refreshDesktopIcons()
                        }
                    }
                }
        }
    }

    /**
     * Get current screen orientation
     * Returns LANDSCAPE if:
     * - Device is in landscape orientation, OR
     * - Device is a foldable/tablet with large screen active
     */
    private fun getCurrentOrientation(): ScreenOrientation {
        // Check if in landscape orientation
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return ScreenOrientation.LANDSCAPE
        }

        // Check if foldable is unfolded or if it's a large screen device
        return if (isFoldableUnfolded) {
            ScreenOrientation.LANDSCAPE
        } else {
            ScreenOrientation.PORTRAIT
        }
    }

    /**
     * Calculate dynamic number of columns based on screen width and orientation
     */
    private fun calculateGridColumns(orientation: ScreenOrientation? = null): Int {
        val targetOrientation = orientation ?: getCurrentOrientation()
        val containerWidth = desktopContainer.width.toFloat()
        val iconWidthDp = 80f // Icon width in dp
        val iconWidthPx = iconWidthDp * resources.displayMetrics.density

        // Calculate how many icons fit horizontally
        val columns = (containerWidth / iconWidthPx).toInt()

        // Ensure at least 1 column
        val result = maxOf(1, columns)

        Log.d("MainActivity", "calculateGridColumns($targetOrientation): containerWidth=$containerWidth, iconWidthPx=$iconWidthPx, columns=$result")
        return result
    }

    /**
     * Calculate dynamic number of rows based on screen height and orientation
     */
    private fun calculateGridRows(orientation: ScreenOrientation? = null): Int {
        val targetOrientation = orientation ?: getCurrentOrientation()
        val containerHeight = desktopContainer.height.toFloat()
        val iconHeightDp = 90f // Icon height in dp
        val iconHeightPx = iconHeightDp * resources.displayMetrics.density

        // Account for taskbar and top margin
        val taskbarHeightPx = 70 * resources.displayMetrics.density
        val topMarginPx = 60 * resources.displayMetrics.density
        val usableHeight = containerHeight - taskbarHeightPx - topMarginPx

        // Calculate how many icons fit vertically
        val rows = (usableHeight / iconHeightPx).toInt()

        // Ensure at least 1 row
        val result = maxOf(1, rows)

        Log.d("MainActivity", "calculateGridRows($targetOrientation): usableHeight=$usableHeight, iconHeightPx=$iconHeightPx, rows=$result")
        return result
    }

    /**
     * Convert grid index to (row, col) position
     * @param index Linear index (0, 1, 2, 3...)
     * @param orientation Target orientation
     * @return Pair of (row, col)
     */
    private fun convertIndexToPosition(index: Int, orientation: ScreenOrientation? = null): Pair<Int, Int> {
        val columns = calculateGridColumns(orientation)
        val row = index / columns
        val col = index % columns
        Log.d("MainActivity", "convertIndexToPosition: index=$index, columns=$columns -> row=$row, col=$col")
        return Pair(row, col)
    }

    /**
     * Convert (row, col) position to grid index
     * @param row Row number
     * @param col Column number
     * @param orientation Target orientation
     * @return Linear index
     */
    private fun convertPositionToIndex(row: Int, col: Int, orientation: ScreenOrientation? = null): Int {
        val columns = calculateGridColumns(orientation)
        val index = row * columns + col
        Log.d("MainActivity", "convertPositionToIndex: row=$row, col=$col, columns=$columns -> index=$index")
        return index
    }

    /**
     * Convert old x/y coordinates to grid index
     * Used for migration from old system
     */
    private fun convertXYToGridIndex(x: Float, y: Float, orientation: ScreenOrientation): Int {
        val columns = calculateGridColumns(orientation)
        val containerWidth = desktopContainer.width.toFloat()
        val containerHeight = desktopContainer.height.toFloat()

        val taskbarHeightPx = 70 * resources.displayMetrics.density
        val topMarginPx = 60 * resources.displayMetrics.density
        val usableHeight = containerHeight - taskbarHeightPx - topMarginPx

        val cellWidth = containerWidth / columns
        val cellHeight = usableHeight / calculateGridRows(orientation)

        // Adjust y for top margin
        val adjustedY = y - topMarginPx

        // Calculate which cell the icon center is in
        val iconWidthPx = 90f * resources.displayMetrics.density
        val iconHeightPx = 100f * resources.displayMetrics.density
        val centerX = x + iconWidthPx / 2
        val centerY = adjustedY + iconHeightPx / 2

        val col = (centerX / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = (centerY / cellHeight).toInt().coerceIn(0, calculateGridRows(orientation) - 1)

        val index = row * columns + col
        Log.d("MainActivity", "convertXYToGridIndex: x=$x, y=$y -> row=$row, col=$col -> index=$index")
        return index
    }

    /**
     * Migrate icons from old x/y system to new grid index system
     */
    private fun migrateIconsToGridSystem() {
        val currentOrientation = getCurrentOrientation()
        var migrationCount = 0

        desktopIcons.forEach { icon ->
            // Skip icons in folders - they don't need grid positions
            if (icon.parentFolderId != null) return@forEach

            // Check if icon needs migration (has no grid indices)
            if (icon.portraitGridIndex == null && icon.landscapeGridIndex == null) {
                // Convert x/y to grid index for current orientation
                val gridIndex = convertXYToGridIndex(icon.x, icon.y, currentOrientation)

                when (currentOrientation) {
                    ScreenOrientation.PORTRAIT -> {
                        icon.portraitGridIndex = gridIndex
                        Log.d("MainActivity", "Migrated icon ${icon.name} from x/y to portrait grid index $gridIndex")
                    }
                    ScreenOrientation.LANDSCAPE -> {
                        icon.landscapeGridIndex = gridIndex
                        Log.d("MainActivity", "Migrated icon ${icon.name} from x/y to landscape grid index $gridIndex")
                    }
                }
                migrationCount++
            }
        }

        if (migrationCount > 0) {
            Log.d("MainActivity", "Migrated $migrationCount icons from x/y to grid system")
        }
    }

    /**
     * Position all desktop icons based on their grid indices for current orientation
     */
    private fun positionIconsFromGridIndices() {
        val currentOrientation = getCurrentOrientation()

        desktopIcons.forEach { icon ->
            // Skip icons in folders
            if (icon.parentFolderId != null) return@forEach

            // Find the corresponding view by matching the icon
            val iconView = desktopIconViews.find { it.getDesktopIcon() == icon }
            if (iconView == null) {
                Log.w("MainActivity", "No view found for icon ${icon.name}")
                return@forEach
            }

            // Get grid index for current orientation
            val gridIndex = when (currentOrientation) {
                ScreenOrientation.PORTRAIT -> icon.portraitGridIndex
                ScreenOrientation.LANDSCAPE -> icon.landscapeGridIndex
            }

            if (gridIndex != null) {
                // Convert grid index to screen position
                val (row, col) = convertIndexToPosition(gridIndex, currentOrientation)
                val (x, y) = getGridCoordinatesFromIndex(row, col)

                // Update icon position
                icon.x = x
                icon.y = y
                iconView.x = x
                iconView.y = y

                Log.d("MainActivity", "Positioned ${icon.name} at grid index $gridIndex (row=$row, col=$col) -> x=$x, y=$y")
            }
        }
    }

    /**
     * Auto-assign grid indices to icons that don't have position in current orientation
     */
    private fun reflowIconsWithoutPosition() {
        val currentOrientation = getCurrentOrientation()

        // Get icons that need reflow (no grid index for current orientation)
        val iconsToReflow = desktopIcons.filter { icon ->
            // Skip icons in folders
            if (icon.parentFolderId != null) return@filter false

            when (currentOrientation) {
                ScreenOrientation.PORTRAIT -> icon.portraitGridIndex == null
                ScreenOrientation.LANDSCAPE -> icon.landscapeGridIndex == null
            }
        }

        if (iconsToReflow.isEmpty()) {
            Log.d("MainActivity", "No icons need reflow for $currentOrientation orientation")
            return
        }

        // Build set of occupied grid indices
        val occupiedIndices = desktopIcons.mapNotNull { icon ->
            if (icon.parentFolderId != null) return@mapNotNull null

            when (currentOrientation) {
                ScreenOrientation.PORTRAIT -> icon.portraitGridIndex
                ScreenOrientation.LANDSCAPE -> icon.landscapeGridIndex
            }
        }.toMutableSet()

        // Assign sequential available indices
        var nextIndex = 0
        iconsToReflow.forEach { icon ->
            // Find next available index
            while (occupiedIndices.contains(nextIndex)) {
                nextIndex++
            }

            // Assign this index
            when (currentOrientation) {
                ScreenOrientation.PORTRAIT -> {
                    icon.portraitGridIndex = nextIndex
                    Log.d("MainActivity", "Auto-assigned portrait grid index $nextIndex to ${icon.name}")
                }
                ScreenOrientation.LANDSCAPE -> {
                    icon.landscapeGridIndex = nextIndex
                    Log.d("MainActivity", "Auto-assigned landscape grid index $nextIndex to ${icon.name}")
                }
            }

            // Convert to screen position
            val (row, col) = convertIndexToPosition(nextIndex, currentOrientation)
            val (x, y) = getGridCoordinatesFromIndex(row, col)

            icon.x = x
            icon.y = y

            // Update view position if it exists (find by matching icon, not by index)
            val iconView = desktopIconViews.find { it.getDesktopIcon() == icon }
            if (iconView != null) {
                iconView.x = x
                iconView.y = y
            } else {
                Log.w("MainActivity", "No view found for reflowed icon ${icon.name}")
            }

            occupiedIndices.add(nextIndex)
            nextIndex++
        }

        Log.d("MainActivity", "Reflowed ${iconsToReflow.size} icons for $currentOrientation orientation")
    }

    /**
     * Get screen coordinates from grid row/col
     * Helper function that uses dynamic grid calculation
     */
    private fun getGridCoordinatesFromIndex(row: Int, col: Int): Pair<Float, Float> {
        val columns = calculateGridColumns()
        val rows = calculateGridRows()

        val containerWidth = desktopContainer.width.toFloat()
        val containerHeight = desktopContainer.height.toFloat()

        val taskbarHeightPx = 70 * resources.displayMetrics.density
        val topMarginPx = 60 * resources.displayMetrics.density
        val usableHeight = containerHeight - taskbarHeightPx - topMarginPx

        val cellWidth = containerWidth / columns
        val cellHeight = usableHeight / rows

        val iconWidthDp = 90f
        val iconHeightDp = 100f
        val iconWidthPx = iconWidthDp * resources.displayMetrics.density
        val iconHeightPx = iconHeightDp * resources.displayMetrics.density

        // Calculate center of the grid cell
        val cellCenterX = (col * cellWidth) + (cellWidth / 2)
        val cellCenterY = topMarginPx + (row * cellHeight) + (cellHeight / 2)

        // Position icon so its center aligns with cell center
        val x = cellCenterX - (iconWidthPx / 2)
        val y = cellCenterY - (iconHeightPx / 2)

        return Pair(x, y)
    }

    // ========== END NEW RESPONSIVE GRID SYSTEM ==========

    private fun getGridDimensions(): Pair<Float, Float> {
        // Use the desktop container dimensions (where icons are placed)
        val containerWidth = desktopContainer.width.toFloat()
        val containerHeight = desktopContainer.height.toFloat()
        
        // Account for taskbar (40dp + 30dp margin = 70dp) and top margin (60dp)
        val taskbarHeightPx = 70 * resources.displayMetrics.density
        val topMarginPx = 60 * resources.displayMetrics.density
        val usableHeight = containerHeight - taskbarHeightPx - topMarginPx
        
        val cellWidth = containerWidth / GRID_COLUMNS
        val cellHeight = usableHeight / GRID_ROWS
        
        Log.d("MainActivity", "Grid dimensions: cellWidth=$cellWidth, cellHeight=$cellHeight, container=${containerWidth}x${containerHeight}, usableHeight=$usableHeight")
        
        return Pair(cellWidth, cellHeight)
    }
    
    private fun getGridCoordinates(row: Int, col: Int): Pair<Float, Float> {
        val (cellWidth, cellHeight) = getGridDimensions()
        
        // Get actual icon dimensions in pixels
        val iconWidthDp = 90f // Icon width in dp
        val iconHeightDp = 100f // Icon height in dp
        val iconWidthPx = iconWidthDp * resources.displayMetrics.density
        val iconHeightPx = iconHeightDp * resources.displayMetrics.density
        
        // Account for top margin
        val topMarginPx = 60 * resources.displayMetrics.density
        
        // Calculate center of the grid cell (with top margin offset)
        val cellCenterX = (col * cellWidth) + (cellWidth / 2)
        val cellCenterY = topMarginPx + (row * cellHeight) + (cellHeight / 2)
        
        // Position icon so its center aligns with cell center
        val x = cellCenterX - (iconWidthPx / 2)
        val y = cellCenterY - (iconHeightPx / 2)
        
        Log.d("MainActivity", "Grid coordinates for ($row, $col): icon position ($x, $y)")
        
        return Pair(x, y)
    }
    
    fun snapSingleIconToGrid(iconView: DesktopIconView) {
        val currentOrientation = getCurrentOrientation()
        val columns = calculateGridColumns()
        val rows = calculateGridRows()

        val occupiedIndices = mutableSetOf<Int>()

        // Get all current occupied grid indices (excluding the icon being snapped)
        desktopIcons.forEach { icon ->
            // Skip icons in folders
            if (icon.parentFolderId != null) return@forEach

            // Find the view by matching the icon
            val view = desktopIconViews.find { it.getDesktopIcon() == icon }
            if (view != null && view != iconView && view.parent != null && view.isVisible) {
                // Get grid index for current orientation
                val gridIndex = when (currentOrientation) {
                    ScreenOrientation.PORTRAIT -> icon.portraitGridIndex
                    ScreenOrientation.LANDSCAPE -> icon.landscapeGridIndex
                }

                if (gridIndex != null) {
                    occupiedIndices.add(gridIndex)
                }
            }
        }

        // Convert current position to grid index
        val currentGridIndex = convertXYToGridIndex(iconView.x, iconView.y, currentOrientation)

        // Find nearest available index
        var nearestIndex = currentGridIndex
        if (occupiedIndices.contains(nearestIndex)) {
            // Current position is occupied, find nearest available
            nearestIndex = findNearestAvailableIndex(currentGridIndex, occupiedIndices, columns, rows)
        }

        // Convert grid index to position
        val (row, col) = convertIndexToPosition(nearestIndex, currentOrientation)
        val (newX, newY) = getGridCoordinatesFromIndex(row, col)

        // Update view position
        iconView.x = newX
        iconView.y = newY

        // Update the desktop icon's position and grid index
        iconView.getDesktopIcon()?.let { icon ->
            icon.x = newX
            icon.y = newY

            // Save grid index for current orientation
            when (currentOrientation) {
                ScreenOrientation.PORTRAIT -> {
                    icon.portraitGridIndex = nearestIndex
                    Log.d("MainActivity", "Snapped ${icon.name} to portrait grid index $nearestIndex (row=$row, col=$col)")
                }
                ScreenOrientation.LANDSCAPE -> {
                    icon.landscapeGridIndex = nearestIndex
                    Log.d("MainActivity", "Snapped ${icon.name} to landscape grid index $nearestIndex (row=$row, col=$col)")
                }
            }
        }
    }

    /**
     * Find nearest available grid index (spiral search from target index)
     */
    private fun findNearestAvailableIndex(targetIndex: Int, occupiedIndices: Set<Int>, columns: Int, rows: Int): Int {
        // If target is available, use it
        if (!occupiedIndices.contains(targetIndex)) {
            return targetIndex
        }

        val maxGridSize = columns * rows
        val targetRow = targetIndex / columns
        val targetCol = targetIndex % columns

        // Spiral search outward from target position
        for (radius in 1..maxOf(columns, rows)) {
            for (dr in -radius..radius) {
                for (dc in -radius..radius) {
                    // Only check cells at current radius (not interior)
                    if (Math.abs(dr) != radius && Math.abs(dc) != radius) continue

                    val row = targetRow + dr
                    val col = targetCol + dc

                    // Check bounds
                    if (row < 0 || row >= rows || col < 0 || col >= columns) continue

                    val index = row * columns + col
                    if (index >= 0 && index < maxGridSize && !occupiedIndices.contains(index)) {
                        return index
                    }
                }
            }
        }

        // Fallback: find first available index
        for (i in 0 until maxGridSize) {
            if (!occupiedIndices.contains(i)) {
                return i
            }
        }

        // No available positions (should never happen)
        return targetIndex
    }
    
    private fun snapExistingIconsToGrid() {
        val occupiedPositions = mutableSetOf<Pair<Int, Int>>()

        // Then snap all desktop icons
        desktopIcons.forEachIndexed { index, icon ->
            val iconView = desktopIconViews.getOrNull(index)
            if (iconView != null && iconView.parent != null && iconView.isVisible) {
                // Find the nearest available grid position to the icon's current location
                val nearestPosition = findNearestAvailableGridPosition(icon.x, icon.y, occupiedPositions)
                
                if (nearestPosition != null) {
                    val (row, col) = nearestPosition
                    val (newX, newY) = getGridCoordinates(row, col)
                    
                    icon.x = newX
                    icon.y = newY
                    iconView.x = newX
                    iconView.y = newY
                    
                    // Mark this position as occupied
                    occupiedPositions.add(Pair(row, col))
                    
                    Log.d("MainActivity", "Snapped icon ${icon.name} to nearest grid position ($row, $col)")
                } else {
                    Log.w("MainActivity", "No available grid position found for icon ${icon.name}")
                }
            }
        }
        saveDesktopIcons()
        refreshDesktopIcons()
        Log.d("MainActivity", "Snapped recycle bin and ${desktopIcons.size} icons to nearest available grid positions")
    }
    
    private fun findNearestAvailableGridPosition(iconX: Float, iconY: Float, occupiedPositions: Set<Pair<Int, Int>>): Pair<Int, Int>? {
        val (cellWidth, cellHeight) = getGridDimensions()
        
        // Calculate which grid cell the icon's center is currently in
        val iconWidthPx = 90f * resources.displayMetrics.density
        val iconHeightPx = 100f * resources.displayMetrics.density
        val centerX = iconX + (iconWidthPx / 2)
        val centerY = iconY + (iconHeightPx / 2)
        
        // Account for top margin when determining grid position
        val topMarginPx = 60 * resources.displayMetrics.density
        val adjustedCenterY = centerY - topMarginPx
        
        val currentCol = (centerX / cellWidth).coerceIn(0f, (GRID_COLUMNS - 1).toFloat()).toInt()
        val currentRow = (adjustedCenterY / cellHeight).coerceIn(0f, (GRID_ROWS - 1).toFloat()).toInt()
        
        // Check if the current position is available
        if (!occupiedPositions.contains(Pair(currentRow, currentCol))) {
            return Pair(currentRow, currentCol)
        }
        
        // Search in expanding circles for the nearest available position
        for (radius in 1 until maxOf(GRID_ROWS, GRID_COLUMNS)) {
            for (dRow in -radius..radius) {
                for (dCol in -radius..radius) {
                    // Only check positions at the current radius (edge of the circle)
                    if (maxOf(abs(dRow), abs(dCol)) == radius) {
                        val testRow = currentRow + dRow
                        val testCol = currentCol + dCol
                        
                        // Check if position is within grid bounds
                        if (testRow in 0 until GRID_ROWS && testCol in 0 until GRID_COLUMNS) {
                            if (!occupiedPositions.contains(Pair(testRow, testCol))) {
                                return Pair(testRow, testCol)
                            }
                        }
                    }
                }
            }
        }
        
        return null // No available positions found
    }
    
    
    
    private fun isSoundMuted(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_SOUND_MUTED, false)
    }
    
    // Grid system is now always enabled - no toggle needed


    private fun toggleSoundMute() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentlyMuted = isSoundMuted()
        prefs.edit {putBoolean(KEY_SOUND_MUTED, !currentlyMuted) }
        updateVolumeIcon()

        // Play a brief sound to indicate the toggle (only if unmuting)
        if (currentlyMuted) {
            // Only play ding sound when unmuting
            playDingSound()
        }
        
        Log.d("MainActivity", "Sound ${if (!currentlyMuted) "muted" else "unmuted"}")
    }
    
    private fun updateVolumeIcon() {
        val volumeIcon = findViewById<ImageView>(R.id.volume_icon)
        val isMuted = isSoundMuted()
        val iconResource = getVolumeIconForCurrentTheme(isMuted)

        volumeIcon?.setImageResource(iconResource)
    }
    
    fun expandNotificationShade() {
        Log.d("MainActivity", "🔥 expandNotificationShade() called")
        
        try {
            // Method 1: Try the standard StatusBarManager approach
            Log.d("MainActivity", "Trying StatusBarManager approach...")
            val statusContext =
                createAttributionContext("system")
            val statusBarManager = statusContext.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarManager?.javaClass?.getMethod("expandNotificationsPanel")
            expandMethod?.invoke(statusBarManager)
            Log.d("MainActivity", "✅ StatusBarManager method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "StatusBarManager method failed: ${e.message}")
        }
        
        try {
            // Method 2: Try legacy approach with different service name
            Log.d("MainActivity", "Trying legacy statusbar service approach...")
            val statusContext2 =
                createAttributionContext("system")
            val statusBarService = statusContext2.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarService?.javaClass?.getMethod("expandNotificationsPanel")
            expandMethod?.invoke(statusBarService)
            Log.d("MainActivity", "✅ Legacy statusbar method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Legacy statusbar method failed: ${e.message}")
        }
        
        try {
            // Method 3: Try expanding settings panel instead
            Log.d("MainActivity", "Trying settings panel approach...")
            val statusContext3 =
                createAttributionContext("system")
            val statusBarManager = statusContext3.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarManager?.javaClass?.getMethod("expandSettingsPanel")
            expandMethod?.invoke(statusBarManager)
            Log.d("MainActivity", "✅ Settings panel method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Settings panel method failed: ${e.message}")
        }
        
        try {
            // Method 4: Try to trigger via broadcast
            Log.d("MainActivity", "Trying broadcast approach...")
            val intent = Intent("android.intent.action.EXPAND_NOTIFICATIONS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            sendBroadcast(intent)
            Log.d("MainActivity", "✅ Broadcast sent")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Broadcast approach failed: ${e.message}")
        }
        
        // Final fallback: Show a message
        Log.e("MainActivity", "❌ All notification shade expansion methods failed")
    }
    
    private fun launchWebSearch() {
        Log.d("MainActivity", "🔍 launchWebSearch() called")
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, "") // leave empty so the box is focused
                // Launch as separate task that can be dismissed with home gesture
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.d("MainActivity", "✅ Launched web search with focused search box")
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch focused web search: ${e.message}")
        }
        
        // Fallback: try the existing Google Search method
        launchGoogleSearch()
    }

    fun launchSwipeRightApp() {
        val swipeRightPackage = getSwipeRightApp()
        
        if (swipeRightPackage != null) {
            Log.d("MainActivity", "📱 Launching swipe right app: $swipeRightPackage")
            try {
                if (isSystemApp(swipeRightPackage)) {
                    launchSystemApp(swipeRightPackage)
                } else {
                    val intent = packageManager.getLaunchIntentForPackage(swipeRightPackage)
                    if (intent != null) {
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        startActivity(intent)
                        Log.d("MainActivity", "✅ Successfully launched swipe right app")
                    } else {
                        Log.w(
                            "MainActivity",
                            "Swipe right app not found, falling back to Google magazines"
                        )
                        launchGoogleMagazines()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to launch swipe right app, falling back to Google magazines", e)
                launchGoogleMagazines()
            }
        } else {
            Log.d("MainActivity", "No swipe right app set, showing instruction toast")
            showNotification("Tip", "Long press an app in the Start menu and select 'Set as Swipe Right App'")
        }
    }

    private fun launchGoogleMagazines() {
        Log.d("MainActivity", "📰 launchGoogleMagazines() called")
        try {
            // Try to launch Google magazines app directly
            val magazinesIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.magazines")
            if (magazinesIntent != null) {
                Log.d("MainActivity", "✅ Launching Google magazines app")
                magazinesIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                startActivity(magazinesIntent)
                return
            } else {
                Log.w("MainActivity", "Google magazines app not found")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google magazines: ${e.message}")
        }

        // Fallback: try to open in Play Store if app not installed
        try {
            val playStoreIntent = Intent(Intent.ACTION_VIEW)
            playStoreIntent.data = "market://details?id=com.google.android.apps.magazines".toUri()
            playStoreIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(playStoreIntent)
            Log.d("MainActivity", "✅ Opened Google magazines in Play Store")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open Google magazines in Play Store: ${e.message}")
        }
    }

    private fun launchGoogleSearch() {
        Log.d("MainActivity", "🔍 launchGoogleSearch() called")
        try {
            // Method 1: Try to launch Google Search with focused search box
            val searchIntent = Intent(Intent.ACTION_SEARCH)
            searchIntent.setPackage("com.google.android.googlequicksearchbox")
            // Launch as separate task that can be properly dismissed
            searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            startActivity(searchIntent)
            Log.d("MainActivity", "✅ Launched Google Search with focused search box")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch focused Google Search: ${e.message}")
        }

        try {
            // Method 1b: Try to launch Google Search app directly as fallback
            val googleSearchIntent = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
            if (googleSearchIntent != null) {
                Log.d("MainActivity", "✅ Launching Google Search app")
                startActivity(googleSearchIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google Search app: ${e.message}")
        }

        try {
            // Method 2: Try to launch search via intent
            val searchIntent = Intent(Intent.ACTION_SEARCH)
            searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(searchIntent)
            Log.d("MainActivity", "✅ Launched search via ACTION_SEARCH")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch search via ACTION_SEARCH: ${e.message}")
        }
        
        try {
            // Method 3: Try to launch Google app (fallback)
            val googleAppIntent = packageManager.getLaunchIntentForPackage("com.google.android.gms")
            if (googleAppIntent != null) {
                Log.d("MainActivity", "✅ Launching Google app as fallback")
                startActivity(googleAppIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google app: ${e.message}")
        }
        
        try {
            // Method 4: Launch web search as final fallback
            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH)
            webSearchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(webSearchIntent)
            Log.d("MainActivity", "✅ Launched web search")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch web search: ${e.message}")
        }
        
        // Final fallback: Show a message
        Log.e("MainActivity", "❌ All Google Search launch methods failed")
        showNotification("Error", "Google Search app not available")
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall", "GestureBackNavigation")
    override fun onBackPressed() {
        // Custom back button behavior for home screen launcher
        when {

            isStartMenuVisible -> {
                // If start menu is open, close it
                Log.d("MainActivity", "Back pressed: closing start menu")
                hideStartMenu()
            }
            floatingWindowManager.getFrontWindow() != null -> {
                // If there's a floating window, close the front-most one
                Log.d("MainActivity", "Back pressed: closing front window")
                floatingWindowManager.closeFrontWindow()
            }
            else -> {
                // If start menu is closed, do nothing (don't call super.onBackPressed())
                // This prevents the home screen from closing/restarting
                Log.d("MainActivity", "Back pressed: ignored (home screen)")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop periodic app checking when paused
        stopPeriodicAppChecking()

        // Stop screensaver timer when app loses focus
        if (::screensaverManager.isInitialized) {
            screensaverManager.stopInactivityTimer()
        }

        // Hide safe to turn off splash if visible
        val safeToTurnOffSplash = findViewById<ImageView>(R.id.safe_to_turn_off_splash)
        safeToTurnOffSplash?.visibility = View.GONE

        // Clear non-essential caches to free memory when app goes to background
        clearNonEssentialCaches()
    }

    override fun onResume() {
        super.onResume()
        refreshWeatherIfNeeded()

        // Check for new apps when resuming and start periodic checking
        checkForNewApps()
        startPeriodicAppChecking()

        // Reset screensaver timer when app regains focus
        if (::screensaverManager.isInitialized) {
            screensaverManager.resetInactivityTimer()
        }

        // Update permission error visibility when returning from settings
        updateEmailPermissionError?.invoke()
        updateNotificationDotsPermissionError?.invoke()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        val newOrientation = getCurrentOrientation()
        Log.d("MainActivity", "Configuration changed to: $newOrientation")

        // Post to ensure container has updated dimensions
        desktopContainer.post {
            // Position icons based on grid indices for new orientation
            positionIconsFromGridIndices()

            // Reflow any icons without position in new orientation
            reflowIconsWithoutPosition()

            // Save after reflow
            saveDesktopIcons()

            // Force refresh to show new positions
            desktopContainer.postDelayed({
                refreshDesktopIcons()
            }, 100)
        }
    }

    override fun onStop() {
        super.onStop()
        // With singleTask launch mode and proper manifest settings,
        // the system should handle home screen behavior correctly

        // Stop screensaver timer when app is fully stopped
        if (::screensaverManager.isInitialized) {
            screensaverManager.stopInactivityTimer()
        }

        // Release wallpaper bitmap to save memory when fully backgrounded
        releaseWallpaperBitmap()
    }

    override fun onRestart() {
        super.onRestart()
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")

        // Reload wallpaper when app comes back to foreground
        reloadWallpaperBitmap()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent called with action: ${intent?.action}, categories: ${intent?.categories}")
        
        // Handle home intent when we're already the active launcher
        if (intent?.action == Intent.ACTION_MAIN && 
            intent.hasCategory(Intent.CATEGORY_HOME)) {
            Log.d("MainActivity", "Home intent received - ensuring we stay visible")
            // We're already the home screen, just ensure we're in the right state
            if (isStartMenuVisible) {
                hideStartMenu()
            }
        }
        
        // Update intent for activity
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)

        // Stop update checker
        stopUpdateChecker()

        // Unregister charging receiver
        chargingReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                Log.d("MainActivity", "Charging receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "Charging receiver was not registered")
            }
        }

        // Clean up floating windows
        if (::floatingWindowManager.isInitialized) {
            floatingWindowManager.removeAllWindows()
        }

        // Clean up weather updates
        weatherUpdateRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        // Clean up screensaver
        if (::screensaverManager.isInitialized) {
            screensaverManager.onDestroy()
            Log.d("MainActivity", "Screensaver cleaned up")
        }

        // Clean up Clippy and speech bubble
        if (::agentView.isInitialized) {
            agentView.destroy()
            Log.d("MainActivity", "Clippy cleaned up")
        }

        if (::speechBubbleView.isInitialized) {
            speechBubbleView.destroy()
            Log.d("MainActivity", "Speech bubble cleaned up")
        }

        // Clean up Quick Glance widget
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.destroy()
            Log.d("MainActivity", "Quick Glance widget cleaned up")
        }

        // Clean up SoundPool
        if (::soundPool.isInitialized) {
            soundPool.release()
            Log.d("MainActivity", "SoundPool released")
        }

        // Clean up app install receiver listener
        AppInstallReceiver.setListener(null)

        // Stop app checking
        stopPeriodicAppChecking()

        // Clear instance reference
        instance = null

        // Clear bitmap caches
        clearAllBitmapCaches()
    }

    /**
     * Called when the system is running low on memory
     */
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("MainActivity", "onLowMemory called - clearing caches aggressively")
        clearAllBitmapCaches()
    }

    /**
     * Called when the system wants the application to trim memory
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MainActivity", "onTrimMemory called with level: $level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // App is running but system is critically low on memory
                clearAllBitmapCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // App is running but system is low on memory
                clearNonEssentialCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // App is running but system wants to reclaim memory
                iconBitmapCache.trimToSize(iconBitmapCache.maxSize() / 2)
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // App is in background - can be more aggressive
                clearAllBitmapCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI is hidden - good time to release memory
                clearNonEssentialCaches()
            }
        }
    }

    /**
     * Clear all bitmap caches aggressively
     */
    private fun clearAllBitmapCaches() {
        val initialSize = iconBitmapCache.size()
        iconBitmapCache.evictAll()
        Log.d("MainActivity", "Cleared icon bitmap cache (was $initialSize items)")

        // Clear cached app list
        cachedAppList = null
        Log.d("MainActivity", "Cleared cached app list")
    }

    /**
     * Clear non-essential caches while keeping visible items
     */
    private fun clearNonEssentialCaches() {
        // Trim icon cache to 25% of max size
        val targetSize = iconBitmapCache.maxSize() / 4
        iconBitmapCache.trimToSize(targetSize)

        // Clear cached app list (will reload when needed)
        cachedAppList = null
    }

    // Weather-related functions
    private var weatherUpdateRunnable: Runnable? = null
    
    private fun setupWeatherUpdates() {
        // Set up weather temperature text interactions
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)

        // Tap to handle weather permissions or open weather app
        weatherTemp?.setOnClickListener {
            handleWeatherTempTap()
        }

        // Long press to toggle temperature unit
        weatherTemp?.setOnLongClickListener {
            toggleWeatherUnit()
            updateWeatherTemperature()
            true
        }
        
        // Initialize weather updates
        updateWeatherTemperature()
        
        // Schedule hourly weather updates
        scheduleWeatherUpdates()
    }
    
    private fun scheduleWeatherUpdates() {
        weatherUpdateRunnable = object : Runnable {
            override fun run() {
                // Update weather every hour (3600000 ms)
                updateWeatherTemperature()
                handler.postDelayed(this, 3600000) // 1 hour
            }
        }
        weatherUpdateRunnable?.let { runnable ->
            handler.postDelayed(runnable, 3600000) // First update after 1 hour
        }
    }
    
    private fun handleWeatherTempTap() {
        Log.d("MainActivity", "🌤️ Weather temp tapped - checking for saved weather app")

        // Check if a custom weather app is set
        val weatherAppPackage = getWeatherApp()

        if (weatherAppPackage != null) {
            // Launch the saved weather app
            Log.d("MainActivity", "📱 Launching saved weather app: $weatherAppPackage")
            try {
                if (isSystemApp(weatherAppPackage)) {
                    launchSystemApp(weatherAppPackage)
                } else {
                    val intent = packageManager.getLaunchIntentForPackage(weatherAppPackage)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        startActivity(intent)
                        Log.d("MainActivity", "✅ Successfully launched saved weather app")
                    } else {
                        Log.w("MainActivity", "Saved weather app not found, falling back to default")
                        launchDefaultWeatherApp()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error launching saved weather app, falling back to default", e)
                launchDefaultWeatherApp()
            }
        } else {
            // No custom app set, use default behavior
            launchDefaultWeatherApp()
        }
    }

    private fun launchDefaultWeatherApp() {
        Log.d("MainActivity", "🌤️ Launching default weather app - checking permissions")

        // Check if location permissions are granted
        val hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation || hasCoarseLocation) {
            // Permission granted - open weather app
            Log.d("MainActivity", "✅ Location permission granted - launching Google weather app")
            launchGoogleWeatherApp()
        } else {
            // No permission - check if we should show rationale or request permission
            val shouldShowRationaleFine = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)
            val shouldShowRationaleCoarse = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            
            if (shouldShowRationaleFine || shouldShowRationaleCoarse) {
                // User previously denied permission - open app settings
                Log.d("MainActivity", "❌ Permission previously denied - opening app settings")
                openAppSettings()
            } else {
                // First time asking for permission - request it
                Log.d("MainActivity", "❓ First time requesting location permission")
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }
    
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:$packageName".toUri()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Log.d("MainActivity", "✅ Opened app settings")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Failed to open app settings: ${e.message}")
            // Fallback: show a toast message
            showNotification("Permission Missing", "Please enable location permission in Settings")
        }
    }
    
    private fun launchGoogleWeatherApp() {
        Log.d("MainActivity", "🌤️ launchGoogleWeatherApp() called")
        try {
            // Try to launch Google weather app directly
            val weatherIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.weather")
            if (weatherIntent != null) {
                Log.d("MainActivity", "✅ Launching Google weather app")
                weatherIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                startActivity(weatherIntent)
                return
            } else {
                Log.w("MainActivity", "Google weather app not found")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google weather app: ${e.message}")
        }

        // Fallback: try to open in Play Store if app not installed
        try {
            val playStoreIntent = Intent(Intent.ACTION_VIEW)
            playStoreIntent.data = "market://details?id=com.google.android.apps.weather".toUri()
            playStoreIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(playStoreIntent)
            Log.d("MainActivity", "✅ Opened Google weather app in Play Store")
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open Google weather app in Play Store: ${e.message}")
        }
    }

    private fun handleWeatherTempRefresh() {
        Log.d("MainActivity", "🔄 Weather refresh requested via long press")
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request permission
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 
                LOCATION_PERMISSION_REQUEST_CODE)
        } else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request coarse location as backup
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 
                LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            // Permission already granted, fetch weather
            fetchLocationAndWeather()
        }
    }
    
    private fun updateWeatherTemperature() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        
        // Check for location permissions
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "No location permission - showing '?'")
            weatherTemp?.text = "?"
            return
        }
        
        // Check network availability
        if (!isNetworkAvailable()) {
            Log.d("MainActivity", "No network connection - trying to use cached data")
            // Try to use cached data
            val cachedData = getCachedWeatherJson()
            if (cachedData != null) {
                try {
                    val currentWeather = cachedData.getJSONObject("current")
                    val temperature = currentWeather.getDouble("temperature_2m")
                    val roundedTemp = kotlin.math.round(temperature).toInt()
                    weatherTemp?.text = "$roundedTemp°"
                    Log.d("MainActivity", "Using cached weather data: $roundedTemp°")
                    return
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing cached weather data", e)
                }
            }
            weatherTemp?.text = "?"
            return
        }
        
        // Permission granted and network available, fetch weather
        fetchLocationAndWeather()
    }
    
    private fun fetchLocationAndWeather() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        weatherTemp?.text = "..."
        
        val locationContext =
            createAttributionContext("weather")
        val locationManager = locationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED) {
                
                // First try to get last known location (fast, no GPS ping)
                var lastKnownLocation: android.location.Location? = null
                
                // Check GPS provider first
                if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                }
                
                // Fallback to network provider
                if (lastKnownLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                }
                
                // If we have a cached location, use it immediately
                if (lastKnownLocation != null) {
                    fetchWeatherData(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    return
                }
                
                // Only if no cached location is available, request fresh location
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        fetchWeatherData(location.latitude, location.longitude)
                        locationManager.removeUpdates(this)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                
                // Request fresh location as fallback
                if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    // Prefer network provider for speed
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER, 
                        0, 0f, locationListener)
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER, 
                        0, 0f, locationListener)
                }
                
                // Shorter timeout since we're only using this as fallback
                handler.postDelayed({
                    locationManager.removeUpdates(locationListener)
                    weatherTemp?.text = "?"
                }, 10000) // Reduced to 10 seconds
            }
        } catch (e: SecurityException) {
            weatherTemp?.text = "?"
        }
    }
    
    private fun fetchWeatherData(latitude: Double, longitude: Double) {
        Thread {
            val maxRetries = 3
            var lastError: Exception? = null
            
            for (attempt in 0 until maxRetries) {
                try {
                    Log.d("MainActivity", "Weather fetch attempt ${attempt + 1}/$maxRetries")
                    
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&timezone=auto"
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000 + (attempt * 2000) // Increase timeout with retries
                    connection.readTimeout = 10000 + (attempt * 2000)
                    
                    val responseCode = connection.responseCode
                    Log.d("MainActivity", "Weather API response code: $responseCode")
                    
                    if (responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        
                        // Parse JSON response properly
                        try {
                            val jsonObject = org.json.JSONObject(response)
                            val currentWeather = jsonObject.getJSONObject("current")
                            val temperature = currentWeather.getDouble("temperature_2m")

                            // Save weather data to SharedPreferences for other components
                            saveWeatherData(response)

                            runOnUiThread {
                                val weatherTemp = findViewById<TextView>(R.id.weather_temp)
                                val formattedTemp = formatTemperature(temperature)
                                weatherTemp?.text = formattedTemp
                                Log.d("MainActivity", "Weather updated successfully: $formattedTemp")

                                // Notify QuickGlanceWidget to refresh its weather display
                                if (::quickGlanceWidget.isInitialized) {
                                    quickGlanceWidget.refreshData()
                                }
                            }
                            return@Thread // Success - exit retry loop
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error parsing weather JSON on attempt ${attempt + 1}", e)
                            lastError = e
                        }
                    } else {
                        val errorMessage = "HTTP error $responseCode on attempt ${attempt + 1}"
                        Log.e("MainActivity", errorMessage)
                        lastError = Exception(errorMessage)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Network error on attempt ${attempt + 1}: ${e.message}", e)
                    lastError = e
                }
                
                // Wait before retrying (exponential backoff)
                if (attempt < maxRetries - 1) {
                    val delayMs = (1000 * (attempt + 1) * (attempt + 1)).toLong() // 1s, 4s, 9s
                    Log.d("MainActivity", "Waiting ${delayMs}ms before retry...")
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        Log.d("MainActivity", "Retry sleep interrupted")
                        break
                    }
                }
            }
            
            // All retries failed - try to use cached data or show error
            Log.e("MainActivity", "All weather fetch attempts failed. Last error: ${lastError?.message}")
            runOnUiThread {
                handleWeatherFetchFailure()
            }
        }.start()
    }
    
    private fun handleWeatherFetchFailure() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        
        // Try to use cached data as fallback
        val cachedData = getCachedWeatherJson()
        if (cachedData != null) {
            try {
                val currentWeather = cachedData.getJSONObject("current")
                val temperature = currentWeather.getDouble("temperature_2m")
                val formattedTemp = formatTemperature(temperature)
                weatherTemp?.text = formattedTemp
                Log.d("MainActivity", "Using cached weather as fallback: $formattedTemp")
                return
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parsing cached weather fallback", e)
            }
        }
        
        // No cached data available - show error
        weatherTemp?.text = "?"
        Log.d("MainActivity", "No cached weather available - showing '?'")
    }
    
    // Weather data caching methods
    private fun saveWeatherData(weatherResponse: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_WEATHER_DATA, weatherResponse)
                putLong(KEY_WEATHER_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            Log.d("MainActivity", "Weather data saved to SharedPreferences")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving weather data", e)
        }
    }
    
    private fun getCachedWeatherData(): String? {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.getString(KEY_WEATHER_DATA, null)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error retrieving cached weather data", e)
            null
        }
    }
    
    private fun getCachedWeatherTimestamp(): Long {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.getSafeLong(KEY_WEATHER_TIMESTAMP, 0L)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error retrieving weather timestamp", e)
            0L
        }
    }
    
    fun isWeatherDataFresh(maxAgeMinutes: Int = 30): Boolean {
        val timestamp = getCachedWeatherTimestamp()
        if (timestamp == 0L) return false

        val ageMinutes = (System.currentTimeMillis() - timestamp) / (1000 * 60)
        return ageMinutes < maxAgeMinutes
    }

    // Temperature unit preference methods
    private fun getWeatherUnit(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_WEATHER_UNIT, "C") ?: "C"
    }

    private fun setWeatherUnit(unit: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(KEY_WEATHER_UNIT, unit) }
        Log.d("MainActivity", "Weather unit changed to: $unit")
    }

    private fun toggleWeatherUnit() {
        val currentUnit = getWeatherUnit()
        val newUnit = if (currentUnit == "C") "F" else "C"
        setWeatherUnit(newUnit)
    }

    private fun convertTemperature(tempCelsius: Double): Int {
        return when (getWeatherUnit()) {
            "F" -> kotlin.math.round((tempCelsius * 9.0 / 5.0) + 32.0).toInt()
            else -> kotlin.math.round(tempCelsius).toInt()
        }
    }

    private fun formatTemperature(tempCelsius: Double): String {
        val temp = convertTemperature(tempCelsius)
        return "$temp°"
    }

    // Public method for QuickGlance widget to format temperature with unit
    fun formatTemperatureForWidget(tempCelsius: Double): String {
        return formatTemperature(tempCelsius)
    }

    private fun refreshWeatherIfNeeded() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        val currentText = weatherTemp?.text?.toString() ?: "?"
        
        // Always try to refresh if showing "?" or cached data is old
        if (currentText == "?" || currentText == "..." || isCachedWeatherDataOld()) {
            Log.d("MainActivity", "Weather refresh needed - current: $currentText")
            updateWeatherTemperature()
        } else {
            Log.d("MainActivity", "Weather refresh not needed - current: $currentText")
        }
    }
    
    // Helper function to safely get Long values from SharedPreferences
    // Handles migration from Integer to Long by removing old values
    private fun android.content.SharedPreferences.getSafeLong(key: String, defaultValue: Long): Long {
        return try {
            getLong(key, defaultValue)
        } catch (e: ClassCastException) {
            // Handle migration from Integer to Long - remove old value
            edit { remove(key) }
            Log.w("MainActivity", "Migrated $key from Integer to Long")
            defaultValue
        }
    }

    private fun isCachedWeatherDataOld(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timestamp = prefs.getSafeLong(KEY_WEATHER_TIMESTAMP, 0L)
        val currentTime = System.currentTimeMillis()
        val thirtyMinutesAgo = currentTime - (30 * 60 * 1000) // 30 minutes
        return timestamp < thirtyMinutesAgo
    }
    
    // Helper method to get parsed weather data
    fun getCachedWeatherJson(): org.json.JSONObject? {
        return try {
            val weatherData = getCachedWeatherData()
            if (weatherData != null) {
                org.json.JSONObject(weatherData)
            } else null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing cached weather JSON", e)
            null
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            
            // Check if we have the required permission
            // For API 23+, use the modern approach
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: SecurityException) {
            // Handle case where ACCESS_NETWORK_STATE permission is missing
            Log.w("MainActivity", "ACCESS_NETWORK_STATE permission not granted, assuming network is available", e)
            true // Default to assuming network is available
        } catch (e: Exception) {
            // Handle any other unexpected exceptions
            Log.e("MainActivity", "Unexpected error checking network availability, assuming network is available", e)
            true // Default to assuming network is available
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, fetch weather
                fetchLocationAndWeather()
            } else {
                // Permission denied, show settings or keep question mark
                val weatherTemp = findViewById<TextView>(R.id.weather_temp)
                weatherTemp?.text = "?"

                // Handle location permission rationale
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    // User denied but didn't check "don't ask again"
                } else {
                    // User denied and checked "don't ask again", open settings
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Couldn't open settings
                    }
                }
            }
            
            CALENDAR_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Calendar permission granted")

                // Notify Quick Glance widget about permission grant
                if (::quickGlanceWidget.isInitialized) {
                    handler.postDelayed({
                        quickGlanceWidget.handleCalendarPermissionGranted()
                        Log.d("MainActivity", "Quick Glance widget notified of permission grant")
                    }, 500) // Small delay to ensure permission is fully processed
                }
            } else {
                Log.d("MainActivity", "Calendar permission denied")
                showNotification("Permission Needed", "Calendar permission required for event display")
            }

            AUDIO_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Winamp", "Audio permission granted")

                // Load music tracks if Winamp is open
                winampAppInstance?.loadMusicTracks()
            } else {
                Log.d("Winamp", "Audio permission denied")
                showNotification("Permission Needed", "Storage permission required to access music files")
            }

            VIDEO_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("WMP", "Video permission granted")

                // Load videos if WMP is open
                wmpAppInstance?.loadVideos()
            } else {
                Log.d("WMP", "Video permission denied")
                showNotification("Permission Needed", "Storage permission required to access video files")
            }

            NOTIFICATION_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Notification permission granted")
            } else {
                Log.d("MainActivity", "Notification permission denied")
                // Don't show a toast for notification denial as it's optional
            }

            101 -> {
                // MSN Messenger SMS permissions
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

                if (allGranted) {
                    // Permissions granted, open MSN Messenger
                    Log.d("MainActivity", "MSN permissions granted, opening app")
                    Handler(Looper.getMainLooper()).post {
                        showMsnDialog()
                    }
                } else {
                    // Check if any permission was permanently denied
                    val hasReadSms = checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                    val hasSendSms = checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                    val hasReadContacts = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

                    val canRequestReadSms = shouldShowRequestPermissionRationale(android.Manifest.permission.READ_SMS)
                    val canRequestSendSms = shouldShowRequestPermissionRationale(android.Manifest.permission.SEND_SMS)
                    val canRequestContacts = shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CONTACTS)

                    // If any permission is denied and we can't request it again (permanently denied)
                    if ((!hasReadSms && !canRequestReadSms) ||
                        (!hasSendSms && !canRequestSendSms) ||
                        (!hasReadContacts && !canRequestContacts)) {

                        // Show notification to open settings
                        showNotification(
                            "Permissions Needed",
                            "Tap here to open settings and grant Contact and SMS permissions"
                        ) {
                            openAppSettings()
                        }
                    }
                }
            }
        }
    }
    
    // Notification monitoring functionality
    fun updateNotificationDots() {
        handler.post {
            try {
                // Check if notification dots are enabled in settings
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val showNotificationDots = prefs.getBoolean(KEY_SHOW_NOTIFICATION_DOTS, true)

                desktopIconViews.forEach { iconView ->
                    val packageName = iconView.getDesktopIcon()?.packageName
                    if (packageName != null && packageName != "recycle.bin") {
                        // Only show dot if setting is enabled AND app has a notification
                        val hasNotification = showNotificationDots && NotificationListenerService.hasNotification(packageName)
                        iconView.updateNotificationDot(hasNotification)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error updating notification dots", e)
            }
        }
    }
    
    private fun startNotificationMonitoring() {
        // Start periodic refresh of notification dots every 2 seconds
        val updateRunnable = object : Runnable {
            override fun run() {
                updateNotificationDots()
                handler.postDelayed(this, 2000) // 2 seconds
            }
        }
        handler.post(updateRunnable)
        
        // Check if notification listener service is enabled
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        if (intent.resolveActivity(packageManager) != null && 
            !isNotificationListenerEnabled()) {
            
            // Optionally show a dialog to enable notification listener
            Log.d("MainActivity", "Notification listener not enabled, notifications dots may not work")
        }
    }
    
    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            val names = flat.split(":")
            for (name in names) {
                val componentName = android.content.ComponentName.unflattenFromString(name)
                if (componentName != null && packageName == componentName.packageName) {
                    return true
                }
            }
        }
        return false
    }
    
    // AppChangeListener implementation
    override fun onAppInstalled(packageName: String) {
        Log.d("MainActivity", "App installed notification: $packageName")
        runOnUiThread {
            // First refresh the app list
            loadInstalledApps()
            
            // Then create a desktop icon for the new app (only if it's launchable)
            try {
                // Check if the app has a launcher intent (is launchable by user)
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    val appIcon = getAppIcon(packageName) ?: packageManager.getApplicationIcon(appInfo)
                    
                    val newAppInfo = AppInfo(
                        name = appName,
                        packageName = packageName,
                        icon = appIcon
                    )
                    
                    // Create desktop shortcut using existing function
                    createDesktopShortcut(newAppInfo)
                    Log.d("MainActivity", "Created desktop shortcut for: $appName")
                } else {
                    Log.d("MainActivity", "Skipping desktop shortcut for non-launchable app: $packageName")
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error creating desktop shortcut for: $packageName", e)
            }
        }
    }
    
    override fun onAppRemoved(packageName: String) {
        Log.d("MainActivity", "App removed notification: $packageName")
        runOnUiThread {
            // First refresh the app list
            loadInstalledApps()
            
            // Then remove any desktop icons for the uninstalled app
            try {
                val iconsToRemove = mutableListOf<DesktopIconView>()
                
                // Find all desktop icons with the matching package name
                desktopIconViews.forEach { iconView ->
                    val desktopIcon = iconView.getDesktopIcon()
                    if (desktopIcon?.packageName == packageName) {
                        iconsToRemove.add(iconView)
                    }
                }
                
                // Remove the found icons
                iconsToRemove.forEach { iconView ->
                    desktopIconViews.remove(iconView)
                    desktopContainer.removeView(iconView)
                    
                    // Remove from desktopIcons list
                    val iconToRemove = iconView.getDesktopIcon()
                    iconToRemove?.let { icon ->
                        desktopIcons.removeAll { it.id == icon.id }
                    }
                }
                
                if (iconsToRemove.isNotEmpty()) {
                    saveDesktopIcons()
                    Log.d("MainActivity", "Removed ${iconsToRemove.size} desktop icons for uninstalled app: $packageName")
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error removing desktop icons for: $packageName", e)
            }
        }
    }
    
    override fun onAppReplaced(packageName: String) {
        Log.d("MainActivity", "App replaced notification: $packageName")
        runOnUiThread {
            loadInstalledApps()
        }
    }
    
    private fun handlePendingPackageAction() {
        val intent = intent
        val packageAction = intent.getStringExtra("package_action")
        val packageName = intent.getStringExtra("package_name")
        
        if (packageAction != null && packageName != null) {
            Log.d("MainActivity", "Handling pending package action: $packageAction for $packageName")
            
            // Handle the action after a short delay to ensure UI is ready
            Handler(Looper.getMainLooper()).postDelayed({
                when (packageAction) {
                    "install" -> onAppInstalled(packageName)
                    "remove" -> onAppRemoved(packageName)
                    "replace" -> onAppReplaced(packageName)
                }
                
                // Clear the intent extras so they don't get handled again
                intent.removeExtra("package_action")
                intent.removeExtra("package_name")
            }, 1000) // 1 second delay
        }
    }
    
    // Manual refresh function (can be called via developer options or debug)
    private fun manualRefreshAppsAndDesktop() {
        Log.d("MainActivity", "Manual refresh of apps and desktop icons")
        runOnUiThread {
            loadInstalledApps()
            
            // Force check for new apps using the same logic as automatic detection
            checkForNewApps()
        }
    }

    // Alternative app detection system (since broadcasts may not work on modern Android)
    private fun initializeAppDetection() {
        // Get initial app count
        lastKnownAppCount = getCurrentLaunchableAppCount()
        Log.d("MainActivity", "Initial app count: $lastKnownAppCount")

        // Initialize known apps list if it doesn't exist
        initializeKnownAppsList()
    }

    private fun initializeKnownAppsList() {
        val knownApps = getKnownApps()
        if (knownApps.isEmpty()) {
            // First time - populate with current apps
            val currentApps = getCurrentInstalledApps()
            saveKnownApps(currentApps)
            Log.d("MainActivity", "Initialized known apps list with ${currentApps.size} apps")
        } else {
            Log.d("MainActivity", "Loaded ${knownApps.size} known apps from storage")
        }
    }

    private fun getCurrentInstalledApps(): Set<String> {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(mainIntent, 0).map {
                it.activityInfo.packageName
            }.toSet()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting current installed apps", e)
            emptySet()
        }
    }

    private fun getKnownApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val knownAppsJson = prefs.getString(KEY_KNOWN_APPS, "[]")
        return try {
            val gson = Gson()
            val type = object : TypeToken<List<String>>() {}.type
            val knownAppsList: List<String> = gson.fromJson(knownAppsJson, type) ?: emptyList()
            knownAppsList.toSet()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading known apps", e)
            emptySet()
        }
    }

    private fun saveKnownApps(apps: Set<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val knownAppsJson = gson.toJson(apps.toList())
        prefs.edit {
            putString(KEY_KNOWN_APPS, knownAppsJson)
        }
        Log.d("MainActivity", "Saved ${apps.size} known apps to storage")
    }

    private fun getCurrentLaunchableAppCount(): Int {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(mainIntent, 0).size
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting app count", e)
            0
        }
    }

    private fun checkForNewApps() {
        try {
            val currentAppCount = getCurrentLaunchableAppCount()

            if (currentAppCount != lastKnownAppCount) {
                detectAppChanges()
                lastKnownAppCount = currentAppCount
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking for new apps", e)
        }
    }

    private fun detectAppChanges() {
        try {
            // Get current installed apps
            val currentApps = getCurrentInstalledApps()

            // Get previously known apps from storage
            val knownApps = getKnownApps()

            // Find new apps (in current but not in known)
            val newApps = currentApps - knownApps

            // Find removed apps (in known but not in current)
            val removedApps = knownApps - currentApps

            Log.d("MainActivity", "New apps: $newApps")
            Log.d("MainActivity", "Removed apps: $removedApps")

            // Handle removed apps
            removedApps.forEach { packageName ->
                val iconsToRemove = mutableListOf<DesktopIconView>()

                desktopIconViews.forEach { iconView ->
                    val desktopIcon = iconView.getDesktopIcon()
                    if (desktopIcon?.packageName == packageName) {
                        iconsToRemove.add(iconView)
                    }
                }

                iconsToRemove.forEach { iconView ->
                    desktopIconViews.remove(iconView)
                    desktopContainer.removeView(iconView)

                    val iconToRemove = iconView.getDesktopIcon()
                    iconToRemove?.let { icon ->
                        desktopIcons.removeAll { it.id == icon.id }
                    }
                }

                if (iconsToRemove.isNotEmpty()) {
                    saveDesktopIcons()
                }
            }

            // Update the known apps list with current apps
            if (newApps.isNotEmpty() || removedApps.isNotEmpty()) {
                saveKnownApps(currentApps)
            }

            // Refresh the app list
            loadInstalledApps()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error detecting app changes", e)
        }
    }

    private fun startPeriodicAppChecking() {
        stopPeriodicAppChecking() // Stop any existing checker

        appCheckRunnable = Runnable {
            checkForNewApps()

            // Schedule next check
            appCheckRunnable?.let { runnable ->
                handler.postDelayed(runnable, APP_CHECK_INTERVAL)
            }
        }

        // Start first check after a short delay
        appCheckRunnable?.let { runnable ->
            handler.postDelayed(runnable, 5000) // 5 seconds initial delay
        }

    }

    private fun stopPeriodicAppChecking() {
        appCheckRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        appCheckRunnable = null
    }


    // Phase 2: New type-safe version using AppTheme
    private fun applyTheme(theme: AppTheme) {

        val themeString = theme.toString()

        // Check if this theme is already applied to prevent infinite loop
        if (lastAppliedTheme == themeString) {
            return
        }

        // Save the selected theme
        themeManager.setSelectedTheme(theme)

        // Persist the flag in SharedPreferences to survive process death
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {putBoolean("theme_changing", true)}

        // Notify theme-aware components before recreation
        notifyThemeChanged(theme)

        // Turn screen grayscale with animation, then continue with theme application
        val pleaseWaitView = findViewById<ImageView>(R.id.please_wait)
        pleaseWaitView?.visibility = View.VISIBLE

        setGrayscale(true) {
            // Animation completed, now continue with theme application
            setGrayscale(false)
            pleaseWaitView?.visibility = View.GONE

            // Mark this theme as the one to be applied after recreate
            lastAppliedTheme = null // Reset so initializeTheme will apply the theme

            // Recreate the activity - initializeTheme() will apply the theme after recreation
            recreate()

        }
    }

    // Backward compatible overload - converts String to AppTheme
    private fun applyTheme(theme: String) {
        applyTheme(AppTheme.fromString(theme))
    }


    private fun applyWindows98Theme() {
        Log.d("MainActivity", "Applying Windows 98 theme")

        // Swap to Windows 98 taskbar layout
        swapTaskbarLayout(R.layout.taskbar_98)

        // For Windows Classic, always show the system tray toggle area
        val systemTrayToggleArea = findViewById<LinearLayout>(R.id.system_tray_toggle_area)
        systemTrayToggleArea?.visibility = View.VISIBLE
        saveSystemTrayVisibility(true) // Save as visible for Windows Classic

        // Update toggle icon to reflect visible state
        val systemTrayToggle = findViewById<ImageView>(R.id.system_tray_toggle)
        updateSystemTrayToggleIcon(systemTrayToggle, true)

        // Reload start menu with Windows 98 layout
        setupStartMenu("Windows Classic")

        // Set context menu background to Windows 98 style
        contextMenu.setThemeBackground(true)


        // Update desktop icons font to Microsoft Sans Serif
        desktopIconViews.forEach { iconView ->
            iconView.setThemeFont(true)
        }

        // Reload custom icon mappings for Windows Classic theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Update quick glance widget font to Microsoft Sans Serif
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(true)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // Update start menu adapters font to Microsoft Sans Serif
        appsAdapter?.setTheme(true)
        commandsAdapter?.setTheme(true)

        // Update recycle bin icon to Windows 98 version
        if (::recycleBin.isInitialized) {
            recycleBin.setThemeIcon(true)
        }

        // Update folder icons to Windows 98 version (only if no custom icon)
        desktopIconViews.forEach { iconView ->
            if (iconView is FolderView) {
                val packageName = iconView.getDesktopIcon()?.packageName
                if (packageName != null && !customIconMappings.containsKey(packageName)) {
                    iconView.setThemeIcon(true)
                }
            }
        }

        // Set up start banner cycling for Windows 98 theme
        setupStartBannerCycling()

        // Update dialog backgrounds for future dialogs - store theme preference
        // Dialogs will check this when they're created
    }

    private fun applyWindowsXPTheme() {
        Log.d("MainActivity", "Applying Windows XP theme")

        // Swap to Windows XP taskbar layout
        swapTaskbarLayout(R.layout.taskbar_xp)

        // Set up system tray toggle after layout is loaded
        setupSystemTrayToggle()

        // For Windows XP, respect the last saved visibility preference
        val systemTrayToggleArea = findViewById<LinearLayout>(R.id.system_tray_toggle_area)
        val savedVisibility = isSystemTrayVisible()
        systemTrayToggleArea?.visibility = if (savedVisibility) View.VISIBLE else View.GONE

        // Update toggle icon to reflect current state
        val systemTrayToggle = findViewById<ImageView>(R.id.system_tray_toggle)
        updateSystemTrayToggleIcon(systemTrayToggle, savedVisibility)

        // Reload start menu with Windows XP layout
        setupStartMenu("Windows XP")

        // Restore context menu background to Windows XP style
        contextMenu.setThemeBackground(false)


        // Restore desktop icons font to Tahoma
        desktopIconViews.forEach { iconView ->
            iconView.setThemeFont(false)
        }

        // Reload custom icon mappings for Windows XP theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Restore quick glance widget font to Tahoma
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(false)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // Restore start menu adapters font to Tahoma
        appsAdapter?.setTheme(false)
        commandsAdapter?.setTheme(false)

        // Restore recycle bin icon to Windows XP version
        if (::recycleBin.isInitialized) {
            recycleBin.setThemeIcon(false)
        }

        // Update folder icons to Windows XP version (only if no custom icon)
        desktopIconViews.forEach { iconView ->
            if (iconView is FolderView) {
                val packageName = iconView.getDesktopIcon()?.packageName
                if (packageName != null && !customIconMappings.containsKey(packageName)) {
                    iconView.setThemeIcon(false)
                }
            }
        }

        // Update dialog backgrounds for future dialogs - store theme preference
        // Dialogs will check this when they're created
    }

    private fun applyWindowsVistaTheme() {
        Log.d("MainActivity", "Applying Windows Vista theme")

        // Swap to Windows Vista taskbar layout
        swapTaskbarLayout(R.layout.taskbar_vista)

        // Apply blur effect to taskbar background (API 31+)
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
//            val taskbarBackground = findViewById<View>(R.id.taskbar_background)
//            taskbarBackground?.setRenderEffect(
//                android.graphics.RenderEffect.createBlurEffect(
//                    25f, 25f, android.graphics.Shader.TileMode.CLAMP
//                )
//            )
//        }

        // Set up system tray toggle after layout is loaded
        setupSystemTrayToggle()

        // For Windows Vista, respect the last saved visibility preference
        val systemTrayToggleArea = findViewById<LinearLayout>(R.id.system_tray_toggle_area)
        val savedVisibility = isSystemTrayVisible()
        systemTrayToggleArea?.visibility = if (savedVisibility) View.VISIBLE else View.GONE

        // Update toggle icon to reflect current state
        val systemTrayToggle = findViewById<ImageView>(R.id.system_tray_toggle)
        updateSystemTrayToggleIcon(systemTrayToggle, savedVisibility)

        // Reload start menu with Windows Vista layout
        setupStartMenu("Windows Vista")

        // Restore context menu background to Windows Vista style (same as XP for now)
        contextMenu.setThemeBackground(false)


        // Restore desktop icons font to Tahoma
        desktopIconViews.forEach { iconView ->
            iconView.setThemeFont(false)
        }

        // Reload custom icon mappings for Windows Vista theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Restore quick glance widget font to Tahoma
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(false)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // Restore start menu adapters font to Tahoma
        appsAdapter?.onThemeChanged(AppTheme.WindowsVista)
        commandsAdapter?.onThemeChanged(AppTheme.WindowsVista)

        // Restore recycle bin icon to Windows Vista version
        if (::recycleBin.isInitialized) {
            recycleBin.setThemeIcon(false)
        }

        // Update folder icons to Windows Vista version (only if no custom icon)
        desktopIconViews.forEach { iconView ->
            if (iconView is FolderView) {
                val packageName = iconView.getDesktopIcon()?.packageName
                if (packageName != null && !customIconMappings.containsKey(packageName)) {
                    iconView.setThemeIcon(false)
                }
            }
        }

        // Update dialog backgrounds for future dialogs - store theme preference
        // Dialogs will check this when they're created
    }

    private fun setupStartBannerCycling() {
        try {
            // Only set up banner cycling if we're in Windows 98 theme
            val startMenuContent = findViewById<View>(R.id.start_menu_content)
            val bannerFrame = startMenuContent?.findViewById<android.widget.FrameLayout>(R.id.start_banner_frame)

            bannerFrame?.let { frame ->
                // Load current banner from SharedPreferences and set it
                loadCurrentStartBanner(frame)

                // Set up click listener for banner cycling
                frame.setOnClickListener {
                    cycleStartBanner(frame)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to set up start banner cycling: ${e.message}")
        }
    }

    private fun loadCurrentStartBanner(bannerFrame: android.widget.FrameLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentBanner = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"

        // Set the background using the asset image
        try {
            val inputStream = assets.open("start_banners/$currentBanner.png")
            val drawable = Drawable.createFromStream(inputStream, null)
            bannerFrame.background = drawable
            inputStream.close()
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to load start banner $currentBanner: ${e.message}")
            // Fallback to default drawable resource
            val resourceId = getBannerResourceId(currentBanner)
            if (resourceId != 0) {
                bannerFrame.setBackgroundResource(resourceId)
            }
        }
    }

    private fun cycleStartBanner(bannerFrame: android.widget.FrameLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentBanner = prefs.getString(KEY_START_BANNER_98, "start_banner_98") ?: "start_banner_98"

        // Find current index in cycle
        val currentIndex = START_BANNER_CYCLE.indexOf(currentBanner)
        val nextIndex = if (currentIndex == -1 || currentIndex == START_BANNER_CYCLE.size - 1) {
            0 // Reset to first if not found or at end
        } else {
            currentIndex + 1
        }

        val nextBanner = START_BANNER_CYCLE[nextIndex]

        // Save new banner to SharedPreferences
        prefs.edit { putString(KEY_START_BANNER_98, nextBanner) }

        // Apply new banner
        loadCurrentStartBanner(bannerFrame)

        Log.d("MainActivity", "Cycled start banner from $currentBanner to $nextBanner")
    }

    private fun setupGestureBarToggle() {
        val gestureBarBackground = findViewById<View>(R.id.gesture_bar_background)

        // Load saved visibility state
        loadGestureBarVisibility(gestureBarBackground)

        // Load saved taskbar height offset
        loadTaskbarHeightOffset()
    }

    private fun loadGestureBarVisibility(gestureBarBackground: View) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isVisible = prefs.getBoolean(KEY_GESTURE_BAR_VISIBLE, true) // Default to visible

        gestureBarBackground.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        Log.d("MainActivity", "Loaded gesture bar visibility: ${if (isVisible) "VISIBLE" else "INVISIBLE"}")
    }

    private fun applyTaskbarHeightOffset(offset: Int) {
        val gestureBarBackground = findViewById<View>(R.id.gesture_bar_background)
        val taskbarContainer = findViewById<View>(R.id.taskbar_container)
        val floatingWindowsContainer = findViewById<View>(R.id.floating_windows_container)
        val desktopIconsContainer = findViewById<View>(R.id.desktop_icons_container)
        val startMenuContainer = findViewById<View>(R.id.start_menu_container)
        val notificationBubble = findViewById<View>(R.id.notification_bubble)

        // Calculate new dimensions
        val baseGestureBarHeight = 30 // Base height in dp
        val baseTaskbarMarginBottom = 30 // Base margin in dp
        val baseFloatingWindowsMarginBottom = 70 // Base margin in dp
        val baseDesktopIconsMarginBottom = 20 // Base margin in dp
        val baseStartMenuMarginBottom = 70 // Base margin in dp
        val baseNotificationBubbleMarginBottom = 60 // Base margin in dp

        val newGestureBarHeight = baseGestureBarHeight + offset
        val newTaskbarMarginBottom = baseTaskbarMarginBottom + offset
        val newFloatingWindowsMarginBottom = baseFloatingWindowsMarginBottom + offset
        val newDesktopIconsMarginBottom = baseDesktopIconsMarginBottom + offset
        val newStartMenuMarginBottom = baseStartMenuMarginBottom + offset
        val newNotificationBubbleMarginBottom = baseNotificationBubbleMarginBottom + offset

        // Convert dp to pixels
        val density = resources.displayMetrics.density
        val gestureBarHeightPx = (newGestureBarHeight * density).toInt()
        val taskbarMarginBottomPx = (newTaskbarMarginBottom * density).toInt()
        val floatingWindowsMarginBottomPx = (newFloatingWindowsMarginBottom * density).toInt()
        val desktopIconsMarginBottomPx = (newDesktopIconsMarginBottom * density).toInt()
        val startMenuMarginBottomPx = (newStartMenuMarginBottom * density).toInt()
        val notificationBubbleMarginBottomPx = (newNotificationBubbleMarginBottom * density).toInt()

        // Apply to gesture bar background
        val gestureBarParams = gestureBarBackground.layoutParams
        gestureBarParams.height = gestureBarHeightPx
        gestureBarBackground.layoutParams = gestureBarParams

        // Apply to taskbar container
        val taskbarParams = taskbarContainer.layoutParams as RelativeLayout.LayoutParams
        taskbarParams.bottomMargin = taskbarMarginBottomPx
        taskbarContainer.layoutParams = taskbarParams

        // Apply to floating windows container
        val floatingWindowsParams = floatingWindowsContainer.layoutParams as RelativeLayout.LayoutParams
        floatingWindowsParams.bottomMargin = floatingWindowsMarginBottomPx
        floatingWindowsContainer.layoutParams = floatingWindowsParams

        // Apply to desktop icons container
        val desktopIconsParams = desktopIconsContainer.layoutParams as RelativeLayout.LayoutParams
        desktopIconsParams.bottomMargin = desktopIconsMarginBottomPx
        desktopIconsContainer.layoutParams = desktopIconsParams

        // Apply to start menu container
        val startMenuParams = startMenuContainer.layoutParams as RelativeLayout.LayoutParams
        startMenuParams.bottomMargin = startMenuMarginBottomPx
        startMenuContainer.layoutParams = startMenuParams

        // Reset saved layout params so they get re-saved with the new margin
        originalStartMenuLayoutParams = null

        // Apply to notification bubble
        val notificationBubbleParams = notificationBubble.layoutParams as RelativeLayout.LayoutParams
        notificationBubbleParams.bottomMargin = notificationBubbleMarginBottomPx
        notificationBubble.layoutParams = notificationBubbleParams

        Log.d("MainActivity", "Applied taskbar height offset: $offset (gesture bar: ${newGestureBarHeight}dp, taskbar: ${newTaskbarMarginBottom}dp, floating windows: ${newFloatingWindowsMarginBottom}dp, desktop icons: ${newDesktopIconsMarginBottom}dp, start menu: ${newStartMenuMarginBottom}dp, notification: ${newNotificationBubbleMarginBottom}dp)")
    }

    private fun loadTaskbarHeightOffset() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val offset = prefs.safeGetInt(KEY_TASKBAR_HEIGHT_OFFSET, 0)
        applyTaskbarHeightOffset(offset)
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
        val shouldScaleFont = selectedTheme == "Windows Classic"
        if(shouldScaleFont) {
            val config = Configuration(newBase.resources.configuration)
            config.fontScale = 1.05f // +20%
            val ctx = newBase.createConfigurationContext(config)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private fun setGrayscale(enabled: Boolean, onComplete: (() -> Unit)? = null) {
        val mainBackgroundView = findViewById<RelativeLayout>(R.id.main_background)
        if (enabled) {
            // Animate from full color (1f) to grayscale (0f) over 2 seconds
            val animator = android.animation.ValueAnimator.ofFloat(1f, 0f)
            animator.duration = 2000 // 2 seconds
            animator.addUpdateListener { animation ->
                val saturation = animation.animatedValue as Float
                val cm = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val effect = android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(cm)
                    )
                    mainBackgroundView.setRenderEffect(effect)
                }
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Wait 1 more second after animation completes before calling callback
                    Handler(Looper.getMainLooper()).postDelayed({
                        onComplete?.invoke()
                    }, 1000)
                }
            })
            animator.start()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mainBackgroundView.setRenderEffect(null)
            }
        }
    }

    private fun initializeTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
        Log.d("MainActivity", "initializeTheme: selectedTheme = '$selectedTheme'")

        // Check if this theme is already applied to prevent double application
        if (lastAppliedTheme == selectedTheme) {
            Log.d("MainActivity", "Theme $selectedTheme already applied in initializeTheme, skipping")
            return
        }

        // First reload the start menu layout for the correct theme
        setupStartMenu(selectedTheme)

        // Apply theme layouts directly (bypass tracking for initialization)
        when (selectedTheme) {
            "Windows Classic" -> {
                applyWindows98Theme()
            }
            "Windows XP" -> {
                applyWindowsXPTheme()
            }
            "Windows Vista" -> {
                applyWindowsVistaTheme()
            }
        }

        // Mark theme as applied to prevent future unnecessary applications
        lastAppliedTheme = selectedTheme

        refreshWeatherIfNeeded()
    }

    private fun getCustomIconsPath(): String {
        return getCustomIconKeyForCurrentTheme()
    }

    private fun updateAllCustomIcons() {
        Log.d("MainActivity", "updateAllCustomIcons called with ${customIconMappings.size} mappings")

        // Update all desktop icons to use theme-specific custom icons or fall back to default
        desktopIconViews.forEachIndexed { index, iconView ->
            val desktopIcon = desktopIcons.getOrNull(index)
            if (desktopIcon != null) {
                // Skip recycle bin - it has its own theme handling via recycleBin.setThemeIcon()
                if (desktopIcon.packageName == "recycle.bin") {
                    Log.d("MainActivity", "Skipping recycle bin - handled separately by setThemeIcon()")
                    return@forEachIndexed
                }

                // Check if there's a theme-specific custom icon for this package
                val hasCustomIcon = customIconMappings.containsKey(desktopIcon.packageName)
                Log.d("MainActivity", "Icon ${desktopIcon.name} (${desktopIcon.packageName}) hasCustomIcon: $hasCustomIcon")

                val updatedIcon = if (hasCustomIcon) {
                    Log.d("MainActivity", "Loading custom icon for ${desktopIcon.packageName}")
                    // Load the theme-specific custom icon
                    getAppIcon(desktopIcon.packageName)
                } else {
                    Log.d("MainActivity", "Loading default icon for ${desktopIcon.packageName}")
                    // No custom icon for this theme, use default app icon
                    loadAppIcon(desktopIcon.packageName)
                }

                if (updatedIcon != null) {
                    Log.d("MainActivity", "Successfully updated icon for ${desktopIcon.name}")
                    desktopIcon.icon = updatedIcon
                    iconView.setDesktopIcon(desktopIcon)
                } else {
                    Log.w("MainActivity", "Failed to load icon for ${desktopIcon.name}")
                }
            }
        }
    }

    private fun setupCursorEffect() {
        cursorEffect = findViewById(R.id.cursor_effect)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            // Reset screensaver timer on any touch event
            if (::screensaverManager.isInitialized) {
                screensaverManager.resetInactivityTimer()
            }

            // Check if touch is on an editable EditText
            val isTouchingEditableEditText = isTouchOnEditableEditText(event)

            // Check if touch is in Solitaire or Minesweeper game window
            val isTouchingGameWindow = isTouchInGameWindow(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    if (::cursorEffect.isInitialized && isCursorVisible() && !isTouchingEditableEditText && !isTouchingGameWindow) {
                        showCursorAt(event.rawX, event.rawY, isMoving = true)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (::cursorEffect.isInitialized && isCursorVisible() && !isTouchingEditableEditText && !isTouchingGameWindow) {
                        // Final position and start hide timer
                        showCursorAt(event.rawX, event.rawY, isMoving = false)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        // Reset screensaver timer on any keyboard input
        if (::screensaverManager.isInitialized) {
            screensaverManager.resetInactivityTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isTouchInGameWindow(event: MotionEvent): Boolean {
        // Check if touch is within Solitaire or Minesweeper game window
        val viewGroup = window.decorView.rootView as? ViewGroup ?: return false
        val touchedView = findViewAtPosition(viewGroup, event.rawX, event.rawY)

        // Check if the touched view or any of its parents has a specific ID
        var currentView: View? = touchedView
        while (currentView != null) {
            val id = currentView.id
            if (id == R.id.solitare_game_area || id == R.id.mine_grid) {
                return true
            }
            currentView = currentView.parent as? View
        }

        return false
    }

    private fun isTouchOnEditableEditText(event: MotionEvent): Boolean {
        // Get the view at the touch coordinates
        val viewGroup = window.decorView.rootView as? ViewGroup ?: return false
        val touchedView = findViewAtPosition(viewGroup, event.rawX, event.rawY)

        // Check if it's an EditText that is editable (not read-only)
        if (touchedView is EditText) {
            // An EditText is editable if it's focusable, enabled, and has a non-zero inputType
            // inputType of 0 (TYPE_NULL) typically means read-only
            return touchedView.isFocusable && touchedView.isEnabled && touchedView.inputType != 0
        }

        return false
    }

    private fun findViewAtPosition(viewGroup: ViewGroup, x: Float, y: Float): View? {
        // Iterate through children in reverse order (top to bottom in z-order)
        for (i in viewGroup.childCount - 1 downTo 0) {
            val child = viewGroup.getChildAt(i)

            if (child.visibility != View.VISIBLE) continue

            // Get view location on screen
            val location = IntArray(2)
            child.getLocationOnScreen(location)

            val left = location[0].toFloat()
            val top = location[1].toFloat()
            val right = left + child.width
            val bottom = top + child.height

            // Check if touch is within bounds
            if (x in left..right && y >= top && y <= bottom) {
                // If it's a ViewGroup, recursively search its children
                if (child is ViewGroup) {
                    val foundInChild = findViewAtPosition(child, x, y)
                    if (foundInChild != null) return foundInChild
                }
                return child
            }
        }
        return null
    }

    private fun showCursorAt(x: Float, y: Float, isMoving: Boolean = false) {
        // Cancel any pending cursor hide
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }


        // Position the cursor at the touch point (top-left corner)
        cursorEffect.x = x
        cursorEffect.y = y

        // Show the cursor
        cursorEffect.visibility = View.VISIBLE
        cursorEffect.bringToFront()


        // Only start hide timer when not moving (i.e., on touch up)
        if (!isMoving) {
            cursorRunnable = Runnable {
                cursorEffect.visibility = View.GONE
            }
            cursorHandler.postDelayed(cursorRunnable!!, 2000)
        }
    }

    // Switch cursor to busy (hourglass) state
    private fun setCursorBusy() {
        if (::cursorEffect.isInitialized) {
            cursorEffect.setImageResource(R.drawable.cursor_busy)
        }
    }

    // Switch cursor back to normal (pointer) state
    private fun setCursorNormal() {
        if (::cursorEffect.isInitialized) {
            cursorEffect.setImageResource(R.drawable.cursor)
        }
    }

    /**
     * Creates a WindowsDialog with the correct theme from the start to avoid re-inflation
     */
    private fun createThemedWindowsDialog(): WindowsDialog {
        val theme = themeManager.getSelectedTheme()
        return WindowsDialog(this, initialTheme = theme)
    }

    /**
     * Shows a notification bubble with title and description
     * @param title The notification title (application name)
     * @param description The notification message
     */
    private fun showNotification(title: String, description: String, onTap: (() -> Unit)? = null) {
        // Cancel any pending hide runnable
        notificationHideRunnable?.let { notificationHandler.removeCallbacks(it) }

        // Set the text
        notificationTitle.text = title
        notificationText.text = description

        // Store the callback
        notificationTapCallback = onTap

        // Show the notification
        notificationBubble.visibility = View.VISIBLE

        // Auto-hide after 5 seconds
        notificationHideRunnable = Runnable {
            hideNotification()
            notificationTapCallback = null // Clear callback if not tapped
        }
        notificationHandler.postDelayed(notificationHideRunnable!!, 7000)

        playSound(R.raw.bubble)
    }

    /**
     * Hides the notification bubble
     */
    private fun hideNotification() {
        notificationBubble.visibility = View.GONE
        notificationHideRunnable?.let { notificationHandler.removeCallbacks(it) }
        notificationHideRunnable = null
    }

    /**
     * Checks for app updates from remote config
     */
    private fun checkForUpdates(showCheckingNotification: Boolean = false) {
        Thread {
            try {
                val apiUrl = URL("https://api.github.com/repos/jovanovski/windowslauncher/releases/latest")
                val connection = apiUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    val gson = Gson()
                    val release = gson.fromJson(response, com.google.gson.JsonObject::class.java)

                    val latestTag = release.get("tag_name")?.asString ?: ""
                    val isPrerelease = release.get("prerelease")?.asBoolean ?: false

                    // Skip prereleases if you only want stable versions
                    if (isPrerelease) {
                        Log.d("MainActivity", "Skipping prerelease: $latestTag")
                        return@Thread
                    }

                    val downloadUrl = release.get("html_url")?.asString ?: ""

                    // Current app versionName (like "1.6" or "v1.6")
                    val currentVersionName = try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        pInfo.versionName ?: ""
                    } catch (e: Exception) {
                        ""
                    }

                    Log.d("MainActivity", "Current version: $currentVersionName | Latest: $latestTag")

                    val latestNumeric = latestTag.trim().removePrefix("v").removePrefix("V")
                    val currentNumeric = currentVersionName.trim().removePrefix("v").removePrefix("V")

                    val updateAvailable = try {
                        compareVersions(latestNumeric, currentNumeric) > 0
                    } catch (e: Exception) {
                        latestNumeric != currentNumeric // fallback simple check
                    }

                    if (updateAvailable) {
                        runOnUiThread {
                            updateDownloadLink = downloadUrl
                            updateIcon.visibility = View.VISIBLE

                            showNotification(
                                "Windows Update",
                                "A new version ($latestTag) is available. Tap to download."
                            ) {
                                if (downloadUrl.isNotEmpty()) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error opening link", e)
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d("MainActivity", "No update available")
                        if (showCheckingNotification) {
                            runOnUiThread {
                                showNotification("Up to date", "No new updates available")
                            }
                        }
                    }
                } else {
                    connection.disconnect()
                    Log.w("MainActivity", "GitHub API failed: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking for updates", e)
            }
        }.start()
    }

    /**
     * Simple semantic version comparator (e.g., 1.7.0 > 1.6)
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-")
        val parts2 = v2.split(".", "-")
        val len = maxOf(parts1.size, parts2.size)
        for (i in 0 until len) {
            val a = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val b = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }


    /**
     * Starts the periodic update checker
     */
    private fun startUpdateChecker() {
        // Check immediately on launch
        checkForUpdates()

        // Set up recurring check every hour
        updateCheckRunnable = object : Runnable {
            override fun run() {
                checkForUpdates()
                updateCheckHandler.postDelayed(this, UPDATE_CHECK_INTERVAL)
            }
        }
        updateCheckHandler.postDelayed(updateCheckRunnable!!, UPDATE_CHECK_INTERVAL)
    }

    /**
     * Stops the periodic update checker
     */
    private fun stopUpdateChecker() {
        updateCheckRunnable?.let { updateCheckHandler.removeCallbacks(it) }
        updateCheckRunnable = null
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun applyWallpaperToDevice(wallpaperItem: WallpaperItem, setHomeScreen: Boolean, setLockScreen: Boolean) {
        try {
            val wallpaperManager = android.app.WallpaperManager.getInstance(this)

            // Load the wallpaper drawable
            val drawable = if (wallpaperItem.filePath != null) {
                // Load from assets
                val inputStream = assets.open(wallpaperItem.filePath)
                val loadedDrawable = Drawable.createFromStream(inputStream, wallpaperItem.filePath)
                inputStream.close()
                loadedDrawable
            } else {
                wallpaperItem.drawable
            }

            if (drawable != null) {
                // Convert drawable to bitmap
                val bitmap = when (drawable) {
                    is android.graphics.drawable.BitmapDrawable -> {
                        drawable.bitmap
                    }
                    else -> {
                        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
                        val bitmap = createBitmap(width, height)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    }
                }

                // Set wallpaper based on selected options
                // Android 7.0+ supports separate home and lock screen wallpapers
                if (setHomeScreen && setLockScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK)
                } else if (setHomeScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                } else if (setLockScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
                }

                Log.d("MainActivity", "Successfully set device wallpaper: home=$setHomeScreen, lock=$setLockScreen")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set device wallpaper", e)
        }
    }

    private fun applyWallpaperToDeviceFromDrawable(drawable: Drawable, setHomeScreen: Boolean, setLockScreen: Boolean) {
        try {
            val wallpaperManager = android.app.WallpaperManager.getInstance(this)

            // Convert drawable to bitmap
            val bitmap = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> {
                    drawable.bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
                    val bitmap = createBitmap(width, height)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            // Set wallpaper based on selected options
            // Android 7.0+ supports separate home and lock screen wallpapers
            if (setHomeScreen && setLockScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK)
            } else if (setHomeScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            } else if (setLockScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
            }

            Log.d("MainActivity", "Successfully set device wallpaper from drawable: home=$setHomeScreen, lock=$setLockScreen")

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set device wallpaper from drawable", e)
        }
    }

}