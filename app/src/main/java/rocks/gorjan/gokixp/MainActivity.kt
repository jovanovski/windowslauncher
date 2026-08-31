package rocks.gorjan.gokixp

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
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
import android.os.UserHandle
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
import androidx.activity.BackEventCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
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
import androidx.core.graphics.scale
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
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.text.util.Linkify
import java.io.File
import java.io.InputStream
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.content.res.AppCompatResources
import rocks.gorjan.gokixp.agent.Agent
import rocks.gorjan.gokixp.agent.AgentView
import rocks.gorjan.gokixp.agent.TTSService
import rocks.gorjan.gokixp.apps.dialer.DialerApp
import rocks.gorjan.gokixp.apps.iexplore.InternetExplorerApp
import rocks.gorjan.gokixp.apps.lights.ChristmasLightsManager
import rocks.gorjan.gokixp.apps.lights.SnowfallManager
import rocks.gorjan.gokixp.apps.minesweeper.MinesweeperGame
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
    // Adapters auto-(un)register for theme notifications as they're replaced, so a stale adapter
    // is never left in themeAwareComponents. (The `= null` initializer skips the setter.)
    private var appsAdapter: AppsAdapter? = null
        set(value) {
            field?.let { unregisterThemeAware(it) }
            field = value
            value?.let { registerThemeAware(it) }
        }
    private var commandsAdapter: CommandsAdapter? = null
        set(value) {
            field?.let { unregisterThemeAware(it) }
            field = value
            value?.let { registerThemeAware(it) }
        }
    private var cachedAppList: List<AppInfo>? = null
    private var isAppListLoading = false
    private var isCommandsListLoading = false
    private lateinit var contextMenu: ContextMenuView
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var desktopContainer: RelativeLayout
    private lateinit var recycleBin: RecycleBinView
    private var myComputer: rocks.gorjan.gokixp.apps.explorer.MyComputerView? = null
    private lateinit var agentView: AgentView
    private lateinit var speechBubbleView: SpeechBubbleView
    private lateinit var quickGlanceWidget: QuickGlanceWidget
    private lateinit var cursorEffect: ImageView
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var christmasLightsManager: ChristmasLightsManager? = null
    private var snowfallManager: SnowfallManager? = null
    private var jingleBellsMediaPlayer: MediaPlayer? = null
    private var cursorRunnable: Runnable? = null
    private lateinit var notificationBubble: RelativeLayout
    private lateinit var notificationTitle: TextView
    private lateinit var notificationText: TextView
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationHideRunnable: Runnable? = null
    private var notificationTapCallback: (() -> Unit)? = null
    var isStartMenuVisible = false

    // Back gesture tracking
    private var isBackGestureInProgress = false
    private var potentialBackGestureStartTime = 0L
    private val BACK_GESTURE_EDGE_THRESHOLD_DP = 5 // Touch within 20dp from edge is potential back gesture
    private val BACK_GESTURE_TIMEOUT_MS = 300L // If no back gesture confirmed within 300ms, allow touch

    // Update checker
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private var updateCheckRunnable: Runnable? = null
    private val UPDATE_CHECK_INTERVAL = 3600000L // 1 hour in milliseconds
    private lateinit var updateIcon: LinearLayout
    private var updateDownloadLink: String? = null

    /** The version waiting to be installed, or null. Shown on the Welcome tile. */
    private var updateAvailableVersion: String? = null
    
    // App detection
    private var lastKnownAppCount = 0
    private var appCheckRunnable: Runnable? = null
    private val APP_CHECK_INTERVAL = 30000L // 30 seconds
    private val ICON_REFRESH_DEBOUNCE_MS = 250L // Coalesces bursts of package-change callbacks
    private var isContextMenuVisible = false
    private var isProgramsMenuExpanded = false
    private var isStartMenuShowingApps = false // Track Vista start menu state
    // Set by "Open Start with hidden apps"; hidden apps are listed (dimmed) for as long as
    // that session of the start menu stays open, and reset when it closes.
    private var isShowingHiddenApps = false
    private var lastAppliedTheme: String? = null
    private var selectedIcon: DesktopIconView? = null
    private val desktopIcons = mutableListOf<DesktopIcon>()
    private val desktopIconViews = mutableListOf<DesktopIconView>()
    private var areDesktopIconsHidden = false // When true, icons are invisible but still tappable
    private var wallpaperSlideRunnable: Runnable? = null
    private var wallpaperSlidePositionMs = 0L // elapsed within the slide cycle, so it resumes where it stopped
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

    // Watches for apps being added, removed or updated while the launcher is running
    private var launcherAppsCallback: LauncherApps.Callback? = null

    // Packages whose icons changed, waiting for the coalesced refresh in refreshIconsForPackage
    private val pendingIconRefreshes = mutableSetOf<String>()
    private var iconRefreshRunnable: Runnable? = null

    // System apps configuration
    private val systemAppActions = mutableMapOf<String, (AppInfo?) -> Unit>() // packageName -> action function with optional AppInfo

    // Permission request codes
    private val CALENDAR_PERMISSION_REQUEST_CODE = 1003
    private val AUDIO_PERMISSION_REQUEST_CODE = 200
    private val VIDEO_PERMISSION_REQUEST_CODE = 201
    private val STORAGE_PERMISSION_REQUEST_CODE = 202

    // When the wallpaper selection dialog is open, its Browse button sets this so the
    // picked image updates the dialog's live preview instead of jumping to the target dialog.
    private var onWallpaperImagePicked: ((Uri) -> Unit)? = null

    // Image picker launcher for wallpaper selection
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            // What the pick was *for* is recorded in SharedPreferences rather than held in
            // a lambda. The system picker is another activity, and this one can be
            // recreated behind it; an in-memory handler is then gone by the time the result
            // arrives, and the pick silently falls through to the wallpaper flow - which is
            // how choosing a tile icon ended up asking where to apply a wallpaper.
            when (val target = consumePendingImagePick()) {
                null -> {
                    val previewHandler = onWallpaperImagePicked
                    if (previewHandler != null) {
                        onWallpaperImagePicked = null
                        previewHandler(selectedUri)
                    } else {
                        handleSelectedImage(selectedUri)
                    }
                }
                PICK_TARGET_WP81_BACKGROUND -> applyPickedWP81Background(selectedUri)
                else -> {
                    if (target.startsWith(PICK_TARGET_WP81_ICON_PREFIX)) {
                        applyPickedWP81Icon(
                            target.removePrefix(PICK_TARGET_WP81_ICON_PREFIX), selectedUri)
                    } else {
                        handleSelectedImage(selectedUri)
                    }
                }
            }
        }
    }

    /**
     * The system's own "make this your browser?" prompt, and what it answered.
     *
     * Registered rather than fired and forgotten so the settings that offered it can say
     * where things stand the moment the user comes back from it - see [refreshDefaultBrowserUi].
     */
    private val defaultBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshDefaultBrowserUi()
    }

    /** Set by whichever settings surface is open, so it can be told the answer. */
    private var refreshDefaultBrowser: (() -> Unit)? = null

    private fun refreshDefaultBrowserUi() {
        refreshDefaultBrowser?.invoke()
    }

    // When the Change Icon dialog's Browse button is used, this receives the path of the
    // image the user picked, after it has been imported into the app's own icon storage.
    private var onCustomIconImagePicked: ((String) -> Unit)? = null

    // Image picker launcher for importing an icon from the device (keeps PNG transparency)
    private val customIconPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val pickedHandler = onCustomIconImagePicked
        onCustomIconImagePicked = null
        if (uri != null && pickedHandler != null) {
            val importedPath = importCustomIconFromUri(uri)
            if (importedPath != null) {
                pickedHandler(importedPath)
            } else {
                showNotification("Change Icon", "That image could not be used as an icon")
            }
        }
    }

    // Notepad image pickers
    private var currentNotepadApp: NotepadApp? = null

    /**
     * The phone's Notepad, when the shell is wearing Windows Phone.
     *
     * Its own instance beside the desktop one rather than an interface over the two: they
     * share the notes and nothing else, and only one of them can be open at a time - the
     * theme decides which. Both are offered the picture, and whichever is up takes it.
     */
    private var metroNotepadAppInstance:
        rocks.gorjan.gokixp.apps.notepad.MetroNotepadApp? = null

    /**
     * The two games, when the shell is wearing Windows Phone.
     *
     * Held for the same reason the notepad is: the back key has to reach whatever the game
     * has open over itself before the window treats it as a way out, and the clock in each
     * of them has to be stopped when the window closes.
     */
    private var metroMinesweeperInstance:
        rocks.gorjan.gokixp.apps.minesweeper.MetroMinesweeperApp? = null
    private var metroSolitaireInstance:
        rocks.gorjan.gokixp.apps.solitare.MetroSolitaireApp? = null

    private val notepadGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        currentNotepadApp?.onImageSelected(uri)
        metroNotepadAppInstance?.onImageSelected(uri)
    }

    private val notepadCameraPickerLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            // Camera captured successfully, URI is already set
            currentNotepadApp?.onImageSelected(pendingCameraUri)
            metroNotepadAppInstance?.onImageSelected(pendingCameraUri)
        }
        pendingCameraUri = null
    }

    private var pendingCameraUri: Uri? = null
    private var pendingMidtown2App: rocks.gorjan.gokixp.apps.midtown2.Midtown2App? = null

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
                    PrefsBackup.restore(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), jsonString)
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

    /**
     * Attribution tags are an API 30 diagnostics feature. On Android 10 there is no
     * equivalent, so fall back to the plain context.
     */
    private fun Context.attributionContext(tag: String): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) createAttributionContext(tag) else this

    private fun getMediaDuration(resourceId: Int): Long {
        return try {
            val audioContext = attributionContext("system")
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

        /** How often the Windows Phone 8.1 Start screen refreshes and flips its live tiles. */
        private const val WP81_LIVE_TILE_INTERVAL_MS = 12_000L

        // Stable ids for the built-in live widgets, so they never collide with a real
        // package name or a desktop icon id.
        /**
         * Whether Start has played its entrance in this process.
         *
         * Deliberately not per-activity: an activity recreated behind a window the user is
         * already looking at is not an arrival, and treating it as one is what made coming
         * home flash.
         */
        private var wp81EntrancePlayed = false

        /**
         * Whether a windowed program was covering the shell last time the count changed.
         *
         * Per-process for the same reason: what matters is whether the program the user
         * just closed was there, not which activity was underneath it.
         */
        private var wp81ProgramOnScreen = false

        /** The icons offered when a Windows Phone tile is being given a new one. */
        private const val WP81_ICON_FOLDER = "custom_icons_8"

        /** Inside this, an appointment is said as a countdown. See wp81EventWhen. */
        private const val RELATIVE_EVENT_MINUTES = 120L

        private const val WP81_WIDGET_CLOCK = "wp81.widget.clock"
        private const val WP81_WIDGET_CALENDAR = "wp81.widget.calendar"
        private const val WP81_WIDGET_NEWS = "wp81.widget.news"
        private const val WP81_WIDGET_PHOTOS = "wp81.widget.photos"
        private const val WP81_WIDGET_PEOPLE = "wp81.widget.people"
        private const val WP81_WIDGET_AQI = "wp81.widget.aqi"
        private const val WP81_WIDGET_WEATHER = "wp81.widget.weather"
        private const val WP81_WIDGET_SETTINGS = "wp81.widget.settings"
        private const val KEY_WP81_BUILTIN_TILES = "wp81_builtin_tiles"

        /** Set once the phone's custom icons have been moved off the desktop themes' keys. */
        private const val KEY_WP81_ICONS_SPLIT = "wp81_custom_icons_split"

        /** The same, for the phone on its side. See wp81Landscape. */
        private const val KEY_WP81_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"

        /** What the in-flight system image pick is for; see imagePickerLauncher. */
        private const val KEY_PENDING_IMAGE_PICK = "pending_image_pick"
        private const val PICK_TARGET_WP81_BACKGROUND = "wp81_background"
        private const val PICK_TARGET_WP81_ICON_PREFIX = "wp81_icon:"

        /** How long an in-app notification stays on screen. */
        private const val NOTIFICATION_DURATION_MS = 7000L

        /** Icons handed to the picker per batch, so the grid fills as it decodes. */
        private const val WP81_ICON_BATCH = 40

        /** Slider movement smaller than this reuses the cached blur rather than re-scaling. */
        private const val BLUR_QUANTISATION = 0.02f
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"

        /**
         * Programs that were part of the shell once and are not any more.
         *
         * Retiring one is a matter of adding its package here; see
         * [purgeRetiredSystemApps], which takes it back out of whatever the user had
         * done with it.
         */
        private val RETIRED_SYSTEM_APPS = setOf("system.msn")

        /** Which of [RETIRED_SYSTEM_APPS] have already been swept out of the user's arrangement. */
        private const val KEY_RETIRED_APPS_PURGED = "retired_system_apps_purged"
        private const val KEY_SOUND_MUTED = "sound_muted"
        private const val KEY_PLAY_EMAIL_SOUND = "play_email_sound"
        private const val KEY_SHOW_NOTIFICATION_DOTS = "show_notification_dots"
        private const val KEY_CLOCK_24_HOUR = "clock_24_hour"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_ROVER_VISIBLE = "rover_visible"
        private const val KEY_RECYCLE_BIN_VISIBLE = "recycle_bin_visible"
        private const val KEY_MY_COMPUTER_VISIBLE = "my_computer_visible"
        private const val KEY_SHORTCUT_ARROW_VISIBLE = "shortcut_arrow_visible"
        private const val KEY_WALLPAPER_XP_PATH = "wallpaper_xp_path"
        private const val KEY_WALLPAPER_XP_URI = "wallpaper_xp_uri"
        private const val KEY_WALLPAPER_CLASSIC_PATH = "wallpaper_classic_path"
        private const val KEY_WALLPAPER_CLASSIC_URI = "wallpaper_classic_uri"
        private const val KEY_WALLPAPER_VISTA_PATH = "wallpaper_vista_path"
        private const val KEY_WALLPAPER_VISTA_URI = "wallpaper_vista_uri"
        private const val KEY_WALLPAPER_XP_FOCUS_X = "wallpaper_xp_focus_x"
        private const val KEY_WALLPAPER_CLASSIC_FOCUS_X = "wallpaper_classic_focus_x"
        private const val KEY_WALLPAPER_VISTA_FOCUS_X = "wallpaper_vista_focus_x"
        private const val KEY_SLIDE_WALLPAPER_ENABLED = "slide_wallpaper_enabled"
        private const val KEY_SLIDE_WALLPAPER_DURATION = "slide_wallpaper_duration" // whole 0->max->0 cycle, in seconds
        private const val DEFAULT_SLIDE_WALLPAPER_DURATION = 10
        private const val KEY_CURSOR_VISIBLE = "cursor_visible"
        private const val KEY_ICON_TEXT_BACKGROUND_VISIBLE = "icon_text_background_visible"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_NAMES = "custom_names"
        private const val KEY_WEATHER_DATA = "weather_data"
        private const val KEY_WEATHER_TIMESTAMP = "weather_timestamp"
        private const val KEY_WEATHER_UNIT = "weather_unit"
        private const val KEY_AQI_DATA = "aqi_data"
        private const val KEY_AQI_TIMESTAMP = "aqi_timestamp"
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
        private const val KEY_CHRISTMAS_LIGHTS_VISIBLE = "christmas_lights_visible"
        private const val KEY_CHRISTMAS_LIGHTS_MARGIN = "christmas_lights_margin"
        private const val KEY_TASKBAR_HEIGHT_OFFSET = "taskbar_height_offset"
        private const val KEY_SHOWN_WELCOME_FOR_VERSION = "shown_welcome_for_version"
        private const val KEY_SYSTEM_TRAY_VISIBLE = "system_tray_visible"
        private const val KEY_SELECTED_SCREENSAVER = "selected_screensaver"
        private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
        private const val KEY_LAST_GOOGLE_DRIVE_SYNC = "last_google_drive_sync"
        private const val KEY_WINDOW_STATES = "window_states"
        private const val KEY_TAP_TO_HIDE_ICONS = "tap_to_hide_icons"
        private const val KEY_OPEN_URLS_IN_IE = "open_urls_in_ie"
        private const val KEY_SHOW_AQI = "show_aqi"
        private const val AIRCARE_URL = "https://getaircare.com"

        // Screensaver types
        private const val SCREENSAVER_NONE = 0
        private const val SCREENSAVER_3D_PIPES = 1
        private const val SCREENSAVER_UNDERWATER = 2
        private const val DEFAULT_SCREENSAVER_TIMEOUT = 30 // Default 30 seconds
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val MIDTOWN2_LOCATION_PERMISSION_REQUEST_CODE = 1004

        /** Asked for by the Photos tile, the first time it is tapped. */
        private const val PHOTOS_PERMISSION_REQUEST_CODE = 1005

        /** And by the People tile, on the same terms. */
        private const val CONTACTS_PERMISSION_REQUEST_CODE = 1006

        /** What the Photos tile opens. */
        private const val WP81_PHOTOS_PACKAGE = "com.google.android.apps.photos"

        /**
         * How long the camera roll is trusted before it is read again.
         *
         * Long enough that returning to Start does not re-query MediaStore every time,
         * short enough that a picture taken a few minutes ago turns up on the tile.
         */
        private const val WP81_PHOTOS_MAX_AGE_MS = 5 * 60 * 1000L

        /**
         * How long the address book is trusted before it is read again.
         *
         * Longer than the camera roll's: people are added to a phone far less often than
         * pictures are taken, and the tile only wants to know who is in there.
         */
        private const val WP81_PEOPLE_MAX_AGE_MS = 30 * 60 * 1000L

        // System app package name prefix
        private const val SYSTEM_APP_PREFIX = "system."

        // Icons the user imported from their device live here, under filesDir.
        // Icon mappings store them as "imported_icons/<file>.png" so they're told apart from asset icons.
        private const val IMPORTED_ICONS_DIR = "imported_icons"

        // Standard size icons are rendered at
        private const val ICON_SIZE_PX = 288

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
        // Iterate a copy: a component's onThemeChanged() may add/remove views (and thus mutate
        // the list via attach/detach) while we're notifying.
        themeAwareComponents.toList().forEach { it.onThemeChanged(theme) }
    }

    /**
     * Registers a component to receive theme-change notifications via [notifyThemeChanged].
     * Idempotent. Desktop icon views and the context menu call this from onAttachedToWindow();
     * adapters register through their field setters. Pair every register with [unregisterThemeAware].
     */
    fun registerThemeAware(component: ThemeAware) {
        if (component !in themeAwareComponents) themeAwareComponents.add(component)
    }

    /** Removes a component previously registered with [registerThemeAware]. */
    fun unregisterThemeAware(component: ThemeAware) {
        themeAwareComponents.remove(component)
    }

    /**
     * Applies a Plus! 95 theme override on top of Windows Classic. Passing "default" clears the override.
     * Refreshes cursor, recycle bin / my computer icons, wallpaper, and the Classic gray menu tint.
     */
    fun applyPlus95Theme(slug: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(ThemeManager.KEY_PLUS95_THEME, slug) }

        // Release any cached click player since the active sound may have changed
        plus95ClickPlayer?.release()
        plus95ClickPlayer = null
        plus95ClickPlayerKey = null

        val plus95 = themeManager.getActivePlus95()

        // Refresh cursor
        if (::cursorEffect.isInitialized) {
            applyCursorNormalDrawable()
        }

        // Refresh all registered theme-aware components (desktop icons, context menu, start-menu
        // adapters) so their icons/colours reload from the newly selected Plus! slug.
        notifyThemeChanged(themeManager.getSelectedTheme())

        // Wallpaper — write the Plus! theme's own wallpaper (or the default) under the Classic
        // keys. Resolve from the slug + asset existence, NOT getActivePlus95(): this method also
        // runs while switching XP→Classic *before* the base theme flips, so getActivePlus95()
        // would still be null and we'd wrongly stage the default wallpaper. Themes that don't
        // ship a wall.jpg (e.g. the Plus! 98 set) fall back to the default Classic wallpaper.
        val classicPath = plus95WallpaperPath(slug) ?: "wallpapers/Windows ME (m).jpg"
        prefs.edit {
            putString(KEY_WALLPAPER_CLASSIC_PATH, classicPath)
            remove(KEY_WALLPAPER_CLASSIC_URI)
        }
        if (themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
            applyCustomWallpaperFromAssets(classicPath)
        }

        // Menu colour walker — tint or untint the Windows Classic gray
        val targetColor = plus95?.menuColor ?: ThemeManager.CLASSIC_GRAY
        val root = findViewById<View>(R.id.main_background)
        if (root != null) applyPlus95MenuColor(root, targetColor)

        // Announce the newly applied theme with its startup sound (start.ogg), if it ships one.
        if (plus95 != null && plus95.startupAsset != null) {
            playPlus95StartupSound(plus95.slug, plus95.startupAsset)
        }
    }

    /**
     * Walks a view tree and swaps any ColorDrawable / GradientDrawable whose color is the
     * stock Classic gray (#d3cec7) with `newColor`. Intended to tint taskbar, start menu,
     * dialog, and system tray gray surfaces without editing each XML.
     */
    fun applyPlus95MenuColor(root: View, newColor: Int) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            tintClassicGrayBackground(v, newColor)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
    }

    /**
     * Colours that count as a repaintable "Classic gray" surface: the stock gray plus every
     * Plus! menu colour. Matching all of them — not just the stock gray — lets us re-tint
     * surfaces that a previous Plus! theme already painted. Otherwise switching between Plus!
     * themes, or reverting to Default, left stale colours behind until an app restart
     * re-inflated the layouts fresh (the reported "needs a restart to take effect" bug).
     */
    private val plus95RepaintableColors: Set<Int> by lazy {
        ThemeManager.PLUS95_THEMES.mapTo(mutableSetOf(ThemeManager.CLASSIC_GRAY)) { it.menuColor }
    }

    private fun tintClassicGrayBackground(view: View, newColor: Int) {
        val bg = view.background ?: return
        when (bg) {
            is android.graphics.drawable.ColorDrawable -> {
                if (bg.color in plus95RepaintableColors) {
                    view.setBackgroundColor(newColor)
                }
            }
            is android.graphics.drawable.GradientDrawable -> {
                if (gradientColorIsRepaintable(bg)) {
                    bg.mutate()
                    (bg as android.graphics.drawable.GradientDrawable).setColor(newColor)
                }
            }
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until bg.numberOfLayers) {
                    val layer = bg.getDrawable(i)
                    if (layer is android.graphics.drawable.GradientDrawable && gradientColorIsRepaintable(layer)) {
                        layer.mutate()
                        (layer as android.graphics.drawable.GradientDrawable).setColor(newColor)
                    }
                }
            }
        }
    }

    private fun gradientColorIsRepaintable(drawable: android.graphics.drawable.GradientDrawable): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        val stateList = drawable.color ?: return false
        return stateList.defaultColor in plus95RepaintableColors
    }

    // ========== Theme-Specific Resource Helper Methods ==========

    /**
     * Gets the wallpaper storage keys for the current theme.
     * Returns Pair(path_key, uri_key)
     */
    private fun getCurrentThemeWallpaperKeysTypeSafe(): Pair<String, String> {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> Pair(KEY_WALLPAPER_CLASSIC_PATH, KEY_WALLPAPER_CLASSIC_URI)
            DesktopChrome.XP -> Pair(KEY_WALLPAPER_XP_PATH, KEY_WALLPAPER_XP_URI)
            DesktopChrome.VISTA -> Pair(KEY_WALLPAPER_VISTA_PATH, KEY_WALLPAPER_VISTA_URI)
        }
    }

    /**
     * Gets the wallpaper X focus storage key for the current theme.
     */
    private fun getCurrentThemeWallpaperFocusXKey(): String {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> KEY_WALLPAPER_CLASSIC_FOCUS_X
            DesktopChrome.XP -> KEY_WALLPAPER_XP_FOCUS_X
            DesktopChrome.VISTA -> KEY_WALLPAPER_VISTA_FOCUS_X
        }
    }

    /**
     * Gets the default wallpaper path for the current theme.
     */
    private fun getDefaultWallpaperForCurrentTheme(): String {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> "wallpapers/Windows ME (m).jpg"
            DesktopChrome.XP -> "wallpapers/Bliss (m).jpg"
            DesktopChrome.VISTA -> "wallpapers/Windows Vista (m).jpg" // Can be changed to Vista default later
        }
    }

    /**
     * Gets the custom icon storage key for the current theme.
     *
     * Answered by the theme and not by its chrome: Windows Phone borrows Vista's windows
     * but not its Start screen, so an icon picked for a tile has no business landing in
     * the Vista desktop's set. See [AppTheme.customIconsKey].
     */
    private fun getCustomIconKeyForCurrentTheme(): String =
        themeManager.getSelectedTheme().customIconsKey

    /**
     * Gets the button background drawable resource for the current theme.
     */
    private fun getButtonBackgroundForCurrentTheme(): Int {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> R.drawable.win98_start_menu_border
            DesktopChrome.XP -> R.drawable.button_xp_background
            DesktopChrome.VISTA -> R.drawable.button_xp_background // Can be changed to Vista button later
        }
    }

    /**
     * Gets the display properties icon for the current theme.
     */
    private fun getDisplayPropertiesIconForCurrentTheme(): Int {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> R.drawable.display_98
            DesktopChrome.XP -> R.drawable.display_xp
            DesktopChrome.VISTA -> R.drawable.display_xp // Can be changed to Vista icon later
        }
    }

    /**
     * Gets the volume icon based on theme and mute state.
     */
    private fun getVolumeIconForCurrentTheme(isMuted: Boolean): Int {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> if (isMuted) R.drawable.mute_98 else R.drawable.sound_98
            DesktopChrome.XP -> if (isMuted) R.drawable.mute else R.drawable.sound
            DesktopChrome.VISTA -> if (isMuted) R.drawable.mute_vista else R.drawable.sound_vista
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
        setupDesktopAgent()
        
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

        // Give the phone shell back the icons it filed under a desktop theme's key
        migrateWP81CustomIconsIfNeeded()

        // Take programs that have since left the shell out of the user's arrangement,
        // before anything reads that arrangement back in
        purgeRetiredSystemApps()

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

        // Inset the whole launcher up to the system navigation bar (e.g. 3-button navigation),
        // so the desktop fills up to the nav bar instead of drawing under it.
        setupNavigationBarInsets()

        // Request notification permission on first launch (Android 13+)
        requestNotificationPermissionIfNeeded()
        
        // Set up app install/uninstall listener
        AppInstallReceiver.setListener(this)

        // Set up the LauncherApps callback that also catches in-place app updates
        registerLauncherAppsCallback()
        
        // Handle pending app installation/removal from broadcast receiver
        handlePendingPackageAction()

        // Handle a URL shared into the launcher on cold start, and one it was sent to open
        // as the phone's browser.
        handleSharedUrlIntent(intent)
        handleViewUrlIntent(intent)

        // Initialize app detection
        initializeAppDetection()

        // Start update checker (checks immediately and then every hour)
        startUpdateChecker()

        refreshDesktopIcons()

        // Show welcome screen if this is the first launch for this version
        Handler(Looper.getMainLooper()).postDelayed({
            showWelcomeScreenIfNeeded()
            refreshDesktopIcons()

            // Set up Christmas lights if enabled (after taskbar is set up so tray icon can be added)
            val christmasLightsEnabled = prefs.getBoolean(KEY_CHRISTMAS_LIGHTS_VISIBLE, false)
            if (christmasLightsEnabled) {
                initializeChristmasLights()
            }

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
                    openUrlShortcut(link)
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

        // Initialize AQI display from cached data
        initializeAqiDisplay()

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

                val monthName = SimpleDateFormat("MMM", Locale.getDefault()).format(currentDate)
                val ordinalSuffix = getOrdinalSuffix(day)

                // Build "Feb 1st" with superscript ordinal
                val dayStr = day.toString()
                val fullText = "$monthName $dayStr$ordinalSuffix"
                val dateSpan = SpannableStringBuilder(fullText)
                val ordinalStart = monthName.length + 1 + dayStr.length
                val ordinalEnd = ordinalStart + ordinalSuffix.length
                dateSpan.setSpan(SuperscriptSpan(), ordinalStart, ordinalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                dateSpan.setSpan(RelativeSizeSpan(0.7f), ordinalStart, ordinalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                // Update separate date and time displays
                dateDay.text = dateSpan
                dateOrdinal.text = ""
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
            attributionContext("system")

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
        soundIds[R.raw.error_xp] = soundPool.load(audioContext, R.raw.error_xp, 1)
        soundIds[R.raw.warning_xp] = soundPool.load(audioContext, R.raw.warning_xp, 1)
        soundIds[R.raw.information_xp] = soundPool.load(audioContext, R.raw.information_xp, 1)

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
                // Play sound asynchronously to avoid blocking UI
                Thread {
                    try {
                        val streamId = soundPool.play(soundId, 1f, 1f, 1, 0, 1.0f)
                        if (streamId == 0) {
                            Log.e("MainActivity", "Failed to play sound resource $soundResourceId (may not be loaded yet)")
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
                attributionContext("system")

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
            showRegistryEditorDialog()
        }

        // Register Dialer
        systemAppActions["system.dialer"] = { appInfo ->
            showDialerDialog()
        }

        // Register Notepad
        systemAppActions["system.notepad"] = { appInfo ->
            showNotepadDialog()
        }

        // Register Winamp
        systemAppActions["system.winamp"] = { appInfo ->
            showWinampDialog(appInfo = appInfo)
        }

        // Register Windows Media Player
        systemAppActions["system.wmp"] = { appInfo ->
            showWmpDialog(appInfo = appInfo)
        }

        // Register Zune. Offered only under Windows Phone 8.1 - see getSystemAppsList -
        // but registered unconditionally, so a tile pinned before a theme switch still
        // opens rather than doing nothing.
        systemAppActions["system.zune"] = { _ ->
            showZuneDialog()
        }

        // And News, on the same terms.
        systemAppActions["system.news"] = { _ ->
            showNewsDialog()
        }

        // Calculator, on the same terms: registered whatever the theme, offered only
        // under Windows Phone 8.1.
        systemAppActions["system.calculator"] = { _ ->
            showCalculatorDialog()
        }

        // Welcome. Tapping it while an update is waiting goes to the update instead: the
        // tile is showing the update, and what a tile shows is what tapping it should be
        // about.
        systemAppActions["system.welcome"] = { _ ->
            val update = updateDownloadLink
            if (!update.isNullOrEmpty()) openUrlShortcut(update)
            else showWelcomeDialogWP81()
        }

        // Register Minesweeper
        systemAppActions["system.minesweeper"] = { appInfo ->
            showMinesweeperDialog(appInfo = appInfo)
        }

        // Register Solitare
        systemAppActions["system.solitare"] = { appInfo ->
            showSolitareDialog(appInfo = appInfo)
        }

        // Register Pinball
        systemAppActions["system.pinball"] = { appInfo ->
            showPinballDialog(appInfo = appInfo)
        }


        // Register Clock
        systemAppActions["system.clock"] = { appInfo ->
            createAndShowClockDialog()
        }

        // Register Midtown Madness 2
        systemAppActions["system.midtown2"] = { appInfo ->
            showMidtown2Dialog()
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
                exeName = "iexplore.exe",
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
                exeName = "regedit.exe",
                packageName = "system.registry_editor",
                icon = createSquareDrawable(regeditDrawable)
            ))
        }

        // Dialer - scale icon to match app icon size
        val dialerDrawable = AppCompatResources.getDrawable(this,R.drawable.dialer_icon)
        if (dialerDrawable != null) {
            systemApps.add(AppInfo(
                name = "Phone Dialer",
                exeName = "dialer.exe",
                packageName = "system.dialer",
                icon = createSquareDrawable(dialerDrawable)
            ))
        }

        // Notepad - scale icon to match app icon size
        val notepadDrawable = AppCompatResources.getDrawable(this,themeManager.getNotepadIcon())
        if (notepadDrawable != null) {
            systemApps.add(AppInfo(
                name = "Notepad",
                exeName = "notepad.exe",
                packageName = "system.notepad",
                icon = createSquareDrawable(notepadDrawable)
            ))
        }

        // Winamp - scale icon to match app icon size
        val winampDrawable = AppCompatResources.getDrawable(this,themeManager.getWinampIcon())
        if (winampDrawable != null) {
            systemApps.add(AppInfo(
                name = "Winamp",
                exeName = "winamp.exe",
                packageName = "system.winamp",
                icon = createSquareDrawable(winampDrawable)
            ))
        }

        // Windows Media Player - scale icon to match app icon size
        val wmpDrawable = AppCompatResources.getDrawable(this,themeManager.getWmpIcon())
        if (wmpDrawable != null) {
            systemApps.add(AppInfo(
                name = "Windows Media Player",
                exeName = "wmplayer.exe",
                packageName = "system.wmp",
                icon = createSquareDrawable(wmpDrawable)
            ))
        }

        // Music - the phone's player, and the one program here with no desktop counterpart:
        // it is a Windows Phone app through and through, and on a Windows 98 desktop it
        // would be an anachronism sitting next to Winamp doing the same job. Called Music
        // because that is what the phone called it; the package is still system.zune,
        // which is what everything already pinned is filed under.
        if (themeManager.isWindowsPhone81()) {
            AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_headphones)?.let { glyph ->
                systemApps.add(AppInfo(
                    name = "Music",
                    exeName = "zune.exe",
                    packageName = "system.zune",
                    icon = createSquareDrawable(glyph)
                ))
            }
        }

        // Welcome, which the desktop themes show as a window after an update. Windows
        // Phone only: the desktop has its own, in its own chrome.
        if (themeManager.isWindowsPhone81()) {
            AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_welcome)?.let { glyph ->
                val tinted = glyph.mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                    tinted, themeManager.getWP81Accent())
                systemApps.add(AppInfo(
                    name = "Welcome",
                    exeName = "welcome.exe",
                    packageName = "system.welcome",
                    icon = createSquareDrawable(tinted)
                ))
            }
        }

        // Calculator. Windows Phone only: the desktop themes have no calculator to be a
        // second copy of, and this one is the phone's keypad rather than a program window.
        if (themeManager.isWindowsPhone81()) {
            AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_calculator)?.let { glyph ->
                // Drawn white for tiles; the app list is not always dark, so it takes the
                // accent here rather than vanishing on a Light theme.
                val tinted = glyph.mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                    tinted, themeManager.getWP81Accent())
                systemApps.add(AppInfo(
                    name = "Calculator",
                    exeName = "calc.exe",
                    packageName = "system.calculator",
                    icon = createSquareDrawable(tinted)
                ))
            }
        }

        // News, the reader behind the News tile. Windows Phone only, for the same reason
        // Zune is: it is built out of this shell's own furniture and would be an
        // anachronism on a desktop that already has Internet Explorer.
        if (themeManager.isWindowsPhone81()) {
            AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_news)?.let { glyph ->
                // The glyph is drawn white for tiles; the app list is not always dark, so
                // it takes the accent here rather than vanishing on a Light theme.
                val tinted = glyph.mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                    tinted, themeManager.getWP81Accent())
                systemApps.add(AppInfo(
                    name = "News",
                    exeName = "news.exe",
                    packageName = "system.news",
                    icon = createSquareDrawable(tinted)
                ))
            }
        }

        // Minesweeper - scale icon to match app icon size
        val minesweeperDrawable = AppCompatResources.getDrawable(this,themeManager.getMinesweeperIcon())
        if (minesweeperDrawable != null) {
            systemApps.add(AppInfo(
                name = "Minesweeper",
                exeName = "minesweeper.exe",
                packageName = "system.minesweeper",
                icon = createSquareDrawable(minesweeperDrawable)
            ))
        }

        // Solitare - scale icon to match app icon size
        val solitareDrawable = AppCompatResources.getDrawable(this,themeManager.getSolitareIcon())
        if (solitareDrawable != null) {
            systemApps.add(AppInfo(
                name = "Solitaire",
                exeName = "solitare.exe",
                packageName = "system.solitare",
                icon = createSquareDrawable(solitareDrawable)
            ))
        }

        // Pinball - scale icon to match app icon size
        val pinballDrawable = AppCompatResources.getDrawable(this, R.drawable.pinball)
        if (pinballDrawable != null) {
            systemApps.add(AppInfo(
                name = "Pinball",
                exeName = "pinball.exe",
                packageName = "system.pinball",
                icon = createSquareDrawable(pinballDrawable)
            ))
        }


        // Clock - scale icon to match app icon size
        val clockDrawable = AppCompatResources.getDrawable(this,themeManager.getClockIcon())
        if (clockDrawable != null) {
            systemApps.add(AppInfo(
                name = "Clock",
                exeName = "clock.exe",
                packageName = "system.clock",
                icon = createSquareDrawable(clockDrawable)
            ))
        }

        // Midtown Madness 2 - scale icon to match app icon size
        val midtown2Drawable = AppCompatResources.getDrawable(this, R.drawable.midtown2)
        if (midtown2Drawable != null) {
            systemApps.add(AppInfo(
                name = "Midtown Madness 2",
                exeName = "midtown2.exe",
                packageName = "system.midtown2",
                icon = createSquareDrawable(midtown2Drawable),
                minWindowWidthDp = 360
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

    /**
     * Get the icon drawable for a system app by package name
     */
    fun getSystemAppIconDrawable(packageName: String): android.graphics.drawable.Drawable? {
        val systemApps = getSystemAppsList()
        return systemApps.find { it.packageName == packageName }?.icon
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

    private fun showMidtown2Dialog() {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowMidtown2Dialog()
        }
    }

    private fun createAndShowMidtown2Dialog() {
        // Request location permission if not granted
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                MIDTOWN2_LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.midtown2"  // Set identifier for tracking
        windowsDialog.setTitle("Midtown Madness 2")
        windowsDialog.setTaskbarIcon(R.drawable.midtown2)

        // Inflate the midtown2 content
        val contentView = layoutInflater.inflate(R.layout.program_midtown2, null)

        // Create Midtown2 app instance
        val midtown2App = rocks.gorjan.gokixp.apps.midtown2.Midtown2App(
            context = this,
            onRequestLocationPermission = {
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    MIDTOWN2_LOCATION_PERMISSION_REQUEST_CODE
                )
            },
            onShowNotification = { title, description, onTap ->
                showNotification(title, description, onTap)
            },
            onSoundPlay = { playClickSound() }
        )

        // Store reference for permission callback
        pendingMidtown2App = midtown2App

        // Setup the app
        midtown2App.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        // Use a good size for the map
        windowsDialog.setWindowSize(380, 500)
        windowsDialog.setMaximizable(true)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            midtown2App.cleanup()
            pendingMidtown2App = null
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Set cursor back to normal after window is shown and loaded
        Handler(Looper.getMainLooper()).postDelayed({
            setCursorNormal()
            windowsDialog.maximizeWindow()
        }, 1000) // Small delay to ensure window is fully rendered
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
            
            // stackFromEnd keeps the list pinned to the bottom of its box, so a short
            // filtered result still grows upward from the search box like the real Start menu.
            // It replaces the old wrap_content + alignParentBottom trick, which forced a full
            // RecyclerView layout pass inside every onMeasure.
            appsRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            // The list box is a fixed size now, so adapter updates can never change our
            // bounds - this skips the requestLayout that walked all the way to root_container
            // (re-measuring the whole desktop) on every keystroke.
            appsRecyclerView.setHasFixedSize(true)

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

                // Detect backspace on empty search box, and handle search/enter key
                searchBoxView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DEL &&
                        searchBoxView.text.isEmpty() &&
                        isStartMenuShowingApps) {
                        switchToCommands()
                        true
                    } else if ((keyCode == android.view.KeyEvent.KEYCODE_SEARCH || keyCode == android.view.KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                        val query = searchBox.text.toString().trim()
                        if (query.isNotEmpty()) {
                            if (query == "marti") {
                                val url = "https://gorjan.rocks/clients/marti/"
                                openUrlShortcut(url)
                            } else {
                                openSearchWithQuery(query)
                            }
                            hideStartMenu()
                        }
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
            },
            onOpenWithHiddenApps = {
                showStartMenuWithHiddenApps()
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
                "system.pinball" ->AppCompatResources.getDrawable(this, R.drawable.pinball)
                "system.registry_editor" ->AppCompatResources.getDrawable(this, themeManager.getRegeditIcon())
                "system.winamp" ->AppCompatResources.getDrawable(this, themeManager.getWinampIcon())
                "system.zune" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_headphones)
                "system.news" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_news)
                "system.welcome" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_welcome)
                "system.calculator" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_calculator)
                "system.wmp" ->AppCompatResources.getDrawable(this, themeManager.getWmpIcon())
                else -> null
            }
        }
        
        return try {
            try {
                val launcherContext =
                    attributionContext("system")
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
        // Get pinned apps, minus any the user hid (unless hidden apps were asked for)
        val hiddenApps = getHiddenApps()
        val pinnedApps = getPinnedApps().let { pinned ->
            if (isShowingHiddenApps) pinned else pinned.filterNot { hiddenApps.contains(it) }
        }
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
            },
            hiddenApps = getHiddenApps()
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

    private fun isMyComputerVisible(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_MY_COMPUTER_VISIBLE, true) // Default to visible
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

    private fun isTapToHideIconsEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_TAP_TO_HIDE_ICONS, false) // Default to off
    }

    private fun isOpenUrlsInIeEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_OPEN_URLS_IN_IE, false) // Default to the system default browser
    }

    fun isShowAqiEnabled(): Boolean = wp81TileHost.showAqi()

    private fun toggleCursorVisibility() {
        val isCurrentlyVisible = isCursorVisible()
        val newVisibility = !isCurrentlyVisible

        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_CURSOR_VISIBLE, newVisibility) }

    }

    private fun initializeChristmasLights() {
        val wrapper = findViewById<RelativeLayout>(R.id.christmas_wrapper)
        val container = findViewById<LinearLayout>(R.id.christmas_lights)
        val snowContainer = findViewById<RelativeLayout>(R.id.christmas_snow_wrapper)

        // Show the wrapper (contains both lights and snow)
        wrapper.visibility = View.VISIBLE

        // Apply saved margin
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val marginTop = prefs.getSafeInt(KEY_CHRISTMAS_LIGHTS_MARGIN, 0)
        val layoutParams = container.layoutParams as RelativeLayout.LayoutParams
        layoutParams.topMargin = (marginTop * resources.displayMetrics.density).toInt()
        container.layoutParams = layoutParams

        // Initialize lights
        if (christmasLightsManager == null) {
            christmasLightsManager = ChristmasLightsManager(
                context = this,
                container = container,
                onShowSettings = { createAndShowWallpaperDialog("settings") },
                onExitLights = {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    prefs.edit { putBoolean(KEY_CHRISTMAS_LIGHTS_VISIBLE, false) }
                    cleanupChristmasLights()
                }
            )
        }
        christmasLightsManager?.initialize()

        // Initialize and start snowfall
        if (snowfallManager == null) {
            snowfallManager = SnowfallManager(this, snowContainer)
        }
        snowfallManager?.start()

        // Set up click listener to toggle jingle bells music
        container.setOnClickListener {
            toggleJingleBellsMusic()
        }

        // Set up long press listener for context menu
        container.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Store touch coordinates for potential long press
                view.tag = Pair(event.rawX, event.rawY)
            }
            false // Let other touch events process normally
        }

        container.setOnLongClickListener { view ->
            // Get stored touch coordinates
            val coords = view.tag as? Pair<*, *>
            val x = (coords?.first as? Float) ?: 0f
            val y = (coords?.second as? Float) ?: 0f
            showChristmasLightsContextMenu(x, y)
            true
        }

        Log.d("MainActivity", "Christmas lights and snowfall initialized with margin: ${marginTop}dp")
    }

    private fun showChristmasLightsContextMenu(x: Float, y: Float) {
        if (isBackGestureInProgress) {
            return
        }
        Log.d("MainActivity", "showChristmasLightsContextMenu called")
        Helpers.performHapticFeedback(this)

        if (::contextMenu.isInitialized) {
            // Create context menu items
            val menuItems = listOf(
                ContextMenuItem(
                    title = "Hide Christmas Lights",
                    action = {
                        // Update SharedPreferences to disable Christmas lights
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit { putBoolean(KEY_CHRISTMAS_LIGHTS_VISIBLE, false) }

                        // Clean up and hide
                        cleanupChristmasLights()

                        Log.d("MainActivity", "Christmas lights hidden via context menu")
                    }
                )
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

    private fun cleanupChristmasLights() {
        val wrapper = findViewById<RelativeLayout>(R.id.christmas_wrapper)

        // Hide the wrapper
        wrapper.visibility = View.GONE

        // Cleanup lights (also removes tray icon)
        christmasLightsManager?.cleanup()
        christmasLightsManager = null

        // Stop and cleanup snowfall
        snowfallManager?.stop()
        snowfallManager = null

        // Stop music
        stopJingleBellsMusic()

        Log.d("MainActivity", "Christmas lights and snowfall cleaned up")
    }

    private fun toggleJingleBellsMusic() {
        if (jingleBellsMediaPlayer?.isPlaying == true) {
            stopJingleBellsMusic()
        } else {
            playJingleBellsMusic()
        }
    }

    private fun playJingleBellsMusic() {
        try {
            // Stop and release any existing player
            stopJingleBellsMusic()

            // Create and configure new MediaPlayer
            jingleBellsMediaPlayer = MediaPlayer.create(this, R.raw.jingle_bells)
            jingleBellsMediaPlayer?.isLooping = true
            jingleBellsMediaPlayer?.start()
            Log.d("MainActivity", "Jingle bells music started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing jingle bells music", e)
        }
    }

    private fun stopJingleBellsMusic() {
        try {
            jingleBellsMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                jingleBellsMediaPlayer = null
                Log.d("MainActivity", "Jingle bells music stopped")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping jingle bells music", e)
        }
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

    private fun toggleMyComputer() {
        val isCurrentlyVisible = isMyComputerVisible()
        val newVisibility = !isCurrentlyVisible

        // Save new state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_MY_COMPUTER_VISIBLE, newVisibility) }

        if (newVisibility) {
            // Show My Computer - add it to the first available grid space
            showMyComputer()
        } else {
            // Hide My Computer - remove it from desktop and free up grid space
            hideMyComputer()
        }

        Log.d("MainActivity", "My Computer visibility changed to: ${if (newVisibility) "VISIBLE" else "GONE"}")
    }

    private fun showMyComputer() {
        val myComputer = this.myComputer ?: return
        if (myComputer.parent == null) {
            // Find first available grid position
            val position = findFirstAvailableGridPosition()
            // Add to desktop at the calculated position
            val layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.leftMargin = position.first
            layoutParams.topMargin = position.second

            desktopContainer.addView(myComputer, layoutParams)
            myComputer.visibility = View.VISIBLE

            // Set My Computer to the calculated grid position
            myComputer.x = position.first.toFloat()
            myComputer.y = position.second.toFloat()

            // Update desktop icon position and save
            val desktopIcon = myComputer.getDesktopIcon()
            if (desktopIcon != null) {
                desktopIcon.x = position.first.toFloat()
                desktopIcon.y = position.second.toFloat()
                saveDesktopIconPosition(desktopIcon)
            } else {
                Log.d("MainActivity", "My Computer added to grid at position: ${position.first}, ${position.second}")
            }
        } else {
            // Just make it visible if already in layout
            myComputer.visibility = View.VISIBLE
        }
    }

    private fun hideMyComputer() {
        val myComputer = this.myComputer ?: return
        myComputer.visibility = View.GONE
        // Optionally remove from parent to truly free up space
        val parent = myComputer.parent as? RelativeLayout
        parent?.removeView(myComputer)
        Log.d("MainActivity", "My Computer hidden and removed from desktop")
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
        val hiddenApps = getHiddenApps()

        // Hidden apps are only listed when the menu was opened with them explicitly requested
        val visibleApps = if (isShowingHiddenApps) {
            appList
        } else {
            appList.filterNot { hiddenApps.contains(it.packageName) }
        }

        // Create final list with all apps
        val finalAppsList = mutableListOf<Any>()
        finalAppsList.addAll(visibleApps)

        Log.d("MainActivity", "Setting up apps adapter with ${visibleApps.size} apps (${pinnedApps.size} pinned, ${hiddenApps.size} hidden)")

        appsAdapter = AppsAdapter(this, finalAppsList,
            onAppClick = { hideStartMenu() },
            onAppLongClick = { appInfo, x, y ->
                showStartMenuAppContextMenu(appInfo, x, y)
            },
            pinnedApps = pinnedApps.toSet(),
            onAppLaunched = {
                // No automatic tracking - apps must be manually pinned
            },
            recentApps = pinnedApps.toSet(),
            hiddenApps = hiddenApps
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
                // filter() reports whether the visible set actually changed, so we only pay
                // for a scroll when the results moved.
                if (appsAdapter?.filter(query) == true && ::appsRecyclerView.isInitialized) {
                    appsRecyclerView.scrollToPosition(0)
                }
            }
        })

        // Handle search key press to open search intent
        searchBox.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == android.view.KeyEvent.KEYCODE_SEARCH || keyCode == android.view.KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
                val query = searchBox.text.toString().trim()
                if (query.isNotEmpty()) {
                    if(query == "marti"){
                        val url = "https://gorjan.rocks/clients/marti/"
                        openUrlShortcut(url)
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
        openUrlShortcut(searchUrl)
        Log.d("MainActivity", "Searched the web for '$query'")
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

            // The start menu lives outside main_background, so applyPlus95Theme's walk never
            // reaches it — tint (or reset) it here every time it opens. Passing CLASSIC_GRAY when
            // no Plus! theme is active clears any stale tint left over from a previous selection.
            if (themeManager.isClassicTheme()) {
                val menuColor = themeManager.getActivePlus95()?.menuColor ?: ThemeManager.CLASSIC_GRAY
                applyPlus95MenuColor(startMenu, menuColor)
            }

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
                    attributionContext("system")
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

            // Showing hidden apps lasts only for one opening of the menu. Rebuild both lists
            // now (while the app cache is still warm) so the next open starts clean.
            if (isShowingHiddenApps) {
                isShowingHiddenApps = false
                refreshCommandsList()
                loadInstalledApps()
            }

            // MEMORY OPTIMIZATION: Clear cached app list to release icon memory when menu closes
            // Icons will be reloaded (from cache) next time menu opens
            cachedAppList = null
        }
    }
    
    /**
     * Opens the start menu on its full app list with hidden apps included, drawn at half
     * opacity. Long pressing one of them offers "Unhide app".
     */
    private fun showStartMenuWithHiddenApps() {
        if (!::startMenu.isInitialized) {
            Log.d("MainActivity", "Start menu not initialized")
            return
        }

        isShowingHiddenApps = true
        refreshCommandsList()
        loadInstalledApps()

        // showStartMenu resets the menu to its command list, so switch to apps afterwards
        showStartMenu()
        showAppList()
    }

    /**
     * Switches the open start menu to its app list - the same state the "All Programs" /
     * "Programs" entry puts it in, minus the click.
     */
    private fun showAppList() {
        if (themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
            val appList98 = findViewById<RelativeLayout>(R.id.start_menu_app_list_98)
            appList98?.visibility = View.VISIBLE
            isProgramsMenuExpanded = true
            commandsAdapter?.setProgramsExpanded(true)
        } else {
            isStartMenuShowingApps = true
            findViewById<LinearLayout>(R.id.app_list_wrapper)?.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.command_list_wrapper)?.visibility = View.GONE
            findViewById<TextView>(R.id.all_programs_text)?.text = "Back to Pinned"
            findViewById<ImageView>(R.id.all_programs_arrow)?.rotation = 180f
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
                    attributionContext("system")
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
                // Reset the gesture flag when back is completed

                when {
                    // WP8.1 shell, but only when nothing is open on top of it: dismiss the
                    // jump list, leave tile edit mode, or page back from the app list to
                    // Start. A window on screen owns back before the shell does.
                    floatingWindowManager.getFrontVisibleWindow() == null &&
                        wp81Shell?.handleBack() == true -> {
                        Log.d("MainActivity", "Back pressed (modern): handled by WP8.1 shell")
                    }
                    isStartMenuVisible -> {
                        // If start menu is open, close it
                        Log.d("MainActivity", "Back pressed (modern): closing start menu")
                        hideStartMenu()
                    }
                    floatingWindowManager.getFrontVisibleWindow() != null -> {
                        val frontWindow = floatingWindowManager.getFrontVisibleWindow()

                        // The phone's browser answers first: back there closes the app
                        // bar menu, drops out of the address field, or steps back through
                        // the pages, and only leaves the window when none of those apply.
                        val metroIE =
                            if (frontWindow?.windowIdentifier == "system.internet_explorer")
                                metroIEAppInstance
                            else null

                        // Check if front window is Internet Explorer with navigation history
                        val ieApp = frontWindow?.internetExplorerApp as? InternetExplorerApp
                        if (metroIE != null && metroIE.handleBack()) {
                            Log.d("MainActivity", "Back pressed (modern): handled by IE (phone)")
                        } else if (ieApp != null && ieApp.canNavigateBack()) {
                            // Navigate back in browser history
                            Log.d("MainActivity", "Back pressed (modern): navigating back in IE")
                            ieApp.navigateBack()
                        } else {
                            // Check if front window is My Computer with navigation history
                            val mcApp = frontWindow?.myComputerApp as? rocks.gorjan.gokixp.apps.explorer.MyComputerApp
                            if (mcApp != null && mcApp.canNavigateBack()) {
                                // Navigate back in folder history
                                Log.d("MainActivity", "Back pressed (modern): navigating back in My Computer")
                                mcApp.navigateBackPublic()
                            } else if (frontWindow?.windowIdentifier == "system.news" &&
                                newsAppInstance?.handleBack() == true
                            ) {
                                Log.d("MainActivity", "Back pressed (modern): handled by News")
                            } else if (frontWindow?.windowIdentifier == "system.notepad" &&
                                metroNotepadAppInstance?.handleBack() == true
                            ) {
                                // A menu, a rename or the note itself was open over the
                                // list. Backing out of one of those is a step inside the
                                // app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Notepad")
                            } else if (frontWindow?.windowIdentifier == "system.minesweeper" &&
                                metroMinesweeperInstance?.handleBack() == true
                            ) {
                                // The strip's own command list was open over the field.
                                Log.d("MainActivity", "Back pressed (modern): handled by Minesweeper")
                            } else if (frontWindow?.windowIdentifier == "system.solitare" &&
                                metroSolitaireInstance?.handleBack() == true
                            ) {
                                Log.d("MainActivity", "Back pressed (modern): handled by Solitaire")
                            } else if (frontWindow?.windowIdentifier == "system.zune" &&
                            zuneAppInstance?.handleBack() == true
                        ) {
                            // A record, a sheet or the play queue was open over the player.
                            // Backing out of one of those is a step inside the app rather
                            // than a way out of it.
                            Log.d("MainActivity", "Back pressed (modern): handled by Zune")
                        } else if (frontWindow?.windowIdentifier == "system.zune") {
                                // Zune keeps playing when you leave it, so backing out of
                                // it means what it means everywhere else on a phone: put
                                // it away, do not shut it down. Its window stays, and the
                                // tile on Start opens it again where it left off.
                                Log.d("MainActivity", "Back pressed (modern): minimising Zune")
                                frontWindow.minimize()
                            } else {
                                // Close the front-most window
                                Log.d("MainActivity", "Back pressed (modern): closing front window")
                                floatingWindowManager.closeFrontWindow()
                            }
                        }
                    }
                    else -> {
                        // If start menu is closed, do nothing
                        // This prevents the home screen from closing/restarting
                        Log.d("MainActivity", "Back pressed (modern): ignored (home screen)")
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    isBackGestureInProgress = false
                }, 500)

            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                // Mark that back gesture has started
                isBackGestureInProgress = true
                Log.d("MainActivity", "Back gesture started")
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                // Back gesture is in progress - keep blocking touches
                // No action needed, just keep the flag set
            }

            override fun handleOnBackCancelled() {
                // User cancelled the back gesture - re-enable touches
                isBackGestureInProgress = false
                potentialBackGestureStartTime = 0L
                Log.d("MainActivity", "Back gesture cancelled")
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

                if (!isBackGestureInProgress) {
                    Log.v("GOKII", "OPA1")
                    showContextMenu(e.x, e.y)
                }
            }
            
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                Log.d("MainActivity", "Single tap detected")
                hideContextMenu()
                if (isStartMenuVisible) {
                    hideStartMenu()
                } else if (isTapToHideIconsEnabled()) {
                    // Tapping empty desktop space toggles icon visibility.
                    // Icons stay clickable so they can be tapped from memory.
                    toggleDesktopIconsVisibility()
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
    
    private fun setupDesktopAgent() {
        Log.d("MainActivity", "Setting up Desktop Agent...")
        
        // Create and configure ClippyView
        agentView = AgentView(this)
        Log.d("MainActivity", "AgentView created")
        
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
            val savedX = prefs.getSafeFloat(KEY_AGENT_X, -1f)
            val savedY = prefs.getSafeFloat(KEY_AGENT_Y, -1f)

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
            val savedX = prefs.getSafeFloat(KEY_WIDGET_X, -1f)
            val savedY = prefs.getSafeFloat(KEY_WIDGET_Y, -1f)
            
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

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+, check MANAGE_EXTERNAL_STORAGE
            android.os.Environment.isExternalStorageManager()
        } else {
            // For older versions, check READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+, request MANAGE_EXTERNAL_STORAGE via settings
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general storage settings
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            // For older versions, request READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showContextMenu(x: Float, y: Float) {
        Log.d("MainActivity", "showContextMenu called")
        Helpers.performHapticFeedback(this)
        
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
    
    private fun toggleDesktopIconsVisibility() {
        areDesktopIconsHidden = !areDesktopIconsHidden
        // Vista fades; XP and 9x (Classic) toggle instantly.
        val duration = if (themeManager.isVistaTheme()) 150L else 0L
        desktopIconViews.forEach { iconView ->
            if (areDesktopIconsHidden) {
                // Fade out, then fully hide (INVISIBLE) so hidden icons can't be tapped.
                iconView.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .withEndAction { iconView.visibility = View.INVISIBLE }
                    .start()
            } else {
                // Make visible (and tappable) again, then fade in.
                iconView.visibility = View.VISIBLE
                iconView.animate()
                    .alpha(1f)
                    .setDuration(duration)
                    .start()
            }
        }
    }

    private fun showStartMenuAppContextMenu(appInfo: AppInfo, x: Float, y: Float) {
        Log.d("MainActivity", "showStartMenuAppContextMenu called for ${appInfo.name}")
        Helpers.performHapticFeedback(this)
        
        // Clear any previously selected icon
        selectedIcon?.setSelected(false)
        selectedIcon = null

        if (::contextMenu.isInitialized) {
            // Check if this is a system app
            val isSystemApp = isSystemApp(appInfo.packageName)

            // Create start menu app context menu items
            val isPinned = isAppPinned(appInfo.packageName)
            val isHidden = isAppHidden(appInfo.packageName)
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
                onHideToggle = {
                    toggleHiddenApp(appInfo.packageName)
                    // Same refresh dance as pinning: commands immediately, apps just after the
                    // context menu has closed. The menu stays open so several apps can be
                    // hidden in a row.
                    refreshCommandsList()
                    Handler(Looper.getMainLooper()).postDelayed({
                        loadInstalledApps()
                    }, 50)
                },
                isHidden = isHidden,
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
        Log.v("GOKII", "OPA2")

        if(isBackGestureInProgress){
            Log.v("GOKII", "OPA2 BLOCKED")
            return
        }
        Log.d("MainActivity", "showDesktopIconContextMenu called")
        Helpers.performHapticFeedback(this)        
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
            val isUrlShortcut = icon?.type == IconType.URL_SHORTCUT

            // Create desktop icon context menu items
            val menuItems = ContextMenuItems.getDesktopIconMenuItems(
                onOpen = {
                    icon?.let { desktopIcon ->
                        try {
                            if (desktopIcon.type == IconType.URL_SHORTCUT) {
                                openUrlShortcut(desktopIcon.targetUrl)
                            } else if (isSystemApp(desktopIcon.packageName)) {
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
                isSystemApp = isSystemApp,
                isUrlShortcut = isUrlShortcut
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
        Helpers.performHapticFeedback(this)

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        // Set this icon as selected
        selectedIcon = recycleBinView
        recycleBinView.setSelected(true)

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
        if (isBackGestureInProgress) return

        Log.d("MainActivity", "showFolderContextMenu called")
        Helpers.performHapticFeedback(this)

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        // Set this folder as selected
        selectedIcon = folderView
        folderView.setSelected(true)

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

    fun showMyComputerContextMenu(myComputerView: rocks.gorjan.gokixp.apps.explorer.MyComputerView, x: Float, y: Float) {
        Log.d("MainActivity", "showMyComputerContextMenu called")
        Helpers.performHapticFeedback(this)

        // Clear any previously selected icon or icon in move mode
        selectedIcon?.setSelected(false)
        if (iconInMoveMode != null) {
            exitIconMoveMode()
        }

        // Set this icon as selected
        selectedIcon = myComputerView
        myComputerView.setSelected(true)

        if (::contextMenu.isInitialized) {
            // Create My Computer context menu items: Open, separator, Move Icon, Properties
            val menuItems = ContextMenuItems.getMyComputerMenuItems(
                onOpen = {
                    openMyComputer(myComputerView)
                },
                onMove = {
                    startIconMoveMode(myComputerView)
                },
                onHideMyComputer = {
                    toggleMyComputer() // Hide My Computer
                },
                onProperties = {
                    createAndShowWallpaperDialog("settings")
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
        Helpers.performHapticFeedback(this)

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
                            // Close the folder window after launching the app
                            parentDialog.closeWindow()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${icon.packageName}", e)
                        }
                    }
                    IconType.FOLDER -> {
                        showFolderWindow(iconView as FolderView)
                    }
                    IconType.URL_SHORTCUT -> {
                        openUrlShortcut(icon.targetUrl)
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
        folderDialog: WindowsDialog? = null,
        /** Invoked once a new icon has been applied and saved, so callers can repaint. */
        onApplied: (() -> Unit)? = null
    ) {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Change Icon")

        // Get current theme for content layout selection
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Chrome string, not the raw pref: this picks in-window art, and Windows Phone 8.1
        // draws its windows in Vista chrome. Reading the pref directly would silently
        // fall through the `else` arm to XP assets.
        val selectedTheme = themeManager.chromeThemeString()

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
        val browseRow = contentView.findViewById<LinearLayout>(R.id.browse_icon_row)
        val btnBrowseIcon = contentView.findViewById<TextView>(R.id.btn_browse_icon)

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

        // Applies an icon path ("default", a bundled asset, or an imported image) and closes the window
        fun applyChosenIcon(iconPath: String) {
            val packageName = desktopIcon?.packageName ?: ""

            // Apply the custom icon immediately
                    try {
                        val customDrawable = if (iconPath == "default") {
                            // Use default app icon
                            loadAppIcon(packageName)
                        } else {
                            loadIconFromPath(iconPath)
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

                            // Drop any imported image the mappings no longer point at
                            pruneUnusedImportedIcons()

                            // Drop this package's cached bitmaps (and the cached app list) so the
                            // icon is re-read rather than served from the pre-update cache
                            invalidateIconCache(packageName)

                            // Now use the same loading process as refresh to get properly sized icon
                            val properlyScaledIcon = getAppIcon(packageName) ?: customDrawable

                            onApplied?.invoke()

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

        // Create adapter that can be updated dynamically
        val adapter = CustomIconAdapter(initialIcons) { chosenIcon ->
            applyChosenIcon(chosenIcon.filePath ?: "default")
        }
        recyclerView.adapter = adapter

        // Browse: use any image on the device as the icon (PNG transparency is kept)
        browseRow.visibility = View.VISIBLE
        btnBrowseIcon.setOnClickListener {
            playClickSound()
            onCustomIconImagePicked = { importedPath -> applyChosenIcon(importedPath) }
            try {
                customIconPickerLauncher.launch("image/*")
            } catch (e: Exception) {
                onCustomIconImagePicked = null
                Log.e("MainActivity", "No app available to pick an icon image", e)
                showNotification("Change Icon", "No app available to pick an image")
            }
        }

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

        // Browsing for an image only applies to app icons, not the user picture
        contentView.findViewById<LinearLayout>(R.id.browse_icon_row).visibility = View.GONE

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

    private fun showFileRenameDialog(file: java.io.File, onRename: (String) -> Unit) {
        val currentName = file.name

        showRenameDialog(
            title = "Rename File",
            initialText = currentName,
            hint = "File name"
        ) { newName ->
            onRename(newName)
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
        // Chrome string, not the raw pref: this picks in-window art, and Windows Phone 8.1
        // draws its windows in Vista chrome. Reading the pref directly would silently
        // fall through the `else` arm to XP assets.
        val selectedTheme = themeManager.chromeThemeString()

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
                            // Close the folder window after launching the app
                            windowsDialog.closeWindow()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching app: ${icon.packageName}", e)
                        }
                    }
                    IconType.FOLDER -> {
                        showFolderWindow(iconView as FolderView)
                    }
                    IconType.URL_SHORTCUT -> {
                        openUrlShortcut(icon.targetUrl)
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

        val currentTheme = themeManager.getSelectedTheme()
        val themeKey = currentTheme.customIconsKey

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
            "GokiXP"  // Internet Explorer homepage
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

    /**
     * Moves Windows Phone's hand-picked icons out of the desktop themes' sets.
     *
     * Until this release the Start screen had no icon key of its own: it took whichever
     * one its window chrome pointed at, which is Vista's, so a tile icon overwrote a Vista
     * desktop icon for the same app and the car screen - reading XP's - showed neither.
     *
     * An icon from the phone's own set (assets/custom_icons_8) can only have been chosen
     * from a tile, so those move across and are struck out of the desktop set they were
     * polluting. Icons the user picked from the shared Programs folder or imported from
     * their gallery could have come from either shell, so they are left where they are
     * rather than guessed at and taken away from a desktop that may be showing them.
     */
    private fun migrateWP81CustomIconsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WP81_ICONS_SPLIT, false)) return

        fun parse(key: String): MutableMap<String, String> =
            (prefs.getString(key, "") ?: "").split(";")
                .mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap(mutableMapOf())

        fun serialise(mappings: Map<String, String>) =
            mappings.entries.joinToString(";") { "${it.key}:${it.value}" }

        val phoneKey = AppTheme.WindowsPhone81.customIconsKey
        val phoneIcons = parse(phoneKey)
        val moved = mutableMapOf<String, String>()

        // Vista's is where the writes went, XP's is what the car screen was reading and
        // what the shell carried into memory when it was entered from XP. Both are swept.
        val donors = listOf(AppTheme.WindowsVista, AppTheme.WindowsXP).map { it.customIconsKey }

        prefs.edit {
            for (donorKey in donors) {
                val donor = parse(donorKey)
                val phonePicks = donor.filterValues { it.startsWith("$WP81_ICON_FOLDER/") }
                if (phonePicks.isEmpty()) continue
                phonePicks.keys.forEach { donor.remove(it) }
                // An icon already chosen under the new key wins - it is the more recent answer.
                phonePicks.forEach { (pkg, path) -> moved.putIfAbsent(pkg, path) }
                putString(donorKey, serialise(donor))
            }
            if (moved.isNotEmpty()) {
                moved.forEach { (pkg, path) -> phoneIcons.putIfAbsent(pkg, path) }
                putString(phoneKey, serialise(phoneIcons))
            }
            putBoolean(KEY_WP81_ICONS_SPLIT, true)
        }

        if (moved.isNotEmpty()) {
            Log.d("MainActivity", "Moved ${moved.size} Windows Phone icons to $phoneKey")
        }
    }

    /**
     * Takes a retired program back out of everything the user had put it in.
     *
     * A program that leaves the shell leaves its traces behind: a desktop shortcut, a
     * tile on Start, a pin in the Start menu, an icon or a name the user chose for it by
     * hand. All of those are filed under the package name and nothing prunes them, so
     * without this a shortcut to a program that no longer exists survives as its old
     * name under Internet Explorer's icon - that is the fallback in [loadDesktopIcons] -
     * and tapping it does nothing whatever, there being no action registered any more.
     *
     * The packages already swept are written down rather than a single "done" flag, so
     * that retiring the next program is one entry in [RETIRED_SYSTEM_APPS] and not
     * another migration.
     */
    private fun purgeRetiredSystemApps() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val swept = (prefs.getString(KEY_RETIRED_APPS_PURGED, "") ?: "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        val retiring = RETIRED_SYSTEM_APPS - swept
        if (retiring.isEmpty()) return

        // Nothing is written unless the whole sweep gets through: edit {} applies at the
        // end of the block, so a throw half way leaves the arrangement as it was and the
        // packages unmarked, to be tried again on the next launch.
        try {
            prefs.edit {
                // Desktop shortcuts, the icons filed inside folders, and - a Start screen
                // tile being a desktop icon under Windows Phone 8.1 - the tiles as well.
                val json = prefs.getString(KEY_DESKTOP_ICONS, null)
                if (json != null) {
                    val gson = Gson()
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val icons: List<Map<String, Any>> = gson.fromJson(json, type)
                    val kept = icons.filterNot { it["packageName"] in retiring }
                    if (kept.size != icons.size) putString(KEY_DESKTOP_ICONS, gson.toJson(kept))
                }

                // Pins in the Start menu, and anything the user had hidden from the app list.
                for (key in listOf(KEY_PINNED_APPS, KEY_HIDDEN_APPS)) {
                    purgeListedPackages(prefs, key, ",", retiring) { it }
                }

                // Icons chosen by hand - one set per theme - and renamed shortcuts. Both
                // are "package:value" pairs, and a renamed one escapes the colons in the
                // value, so the package is always what stands before the first.
                for (key in AppTheme.all().map { it.customIconsKey } + KEY_CUSTOM_NAMES) {
                    purgeListedPackages(prefs, key, ";", retiring) { it.substringBefore(":") }
                }

                // The gestures that are pointed at one particular program.
                for (key in listOf(KEY_SWIPE_RIGHT_APP, KEY_WEATHER_APP)) {
                    if (prefs.getString(key, null) in retiring) remove(key)
                }

                // MSN Messenger read the phone's messages, and stamped when each
                // correspondent was last read. Nothing is left to read those stamps, and
                // message data has no business travelling on in a settings backup.
                if ("system.msn" in retiring) {
                    prefs.all.keys.filter { it.startsWith("last_read_") }.forEach { remove(it) }
                }

                putString(KEY_RETIRED_APPS_PURGED, (swept + retiring).joinToString(","))
            }
            Log.d("MainActivity", "Swept retired programs out of the user arrangement: $retiring")
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not sweep retired programs, will retry next launch", e)
        }
    }

    /**
     * Drops the retired packages from one of the delimited lists the shell keeps.
     *
     * [packageOf] pulls the package out of an entry, which for the plain lists is the
     * entry itself and for the mapped ones is the half before the colon.
     */
    private fun android.content.SharedPreferences.Editor.purgeListedPackages(
        prefs: android.content.SharedPreferences,
        key: String,
        separator: String,
        retiring: Set<String>,
        packageOf: (String) -> String
    ) {
        val entries = (prefs.getString(key, "") ?: "").split(separator).filter { it.isNotEmpty() }
        val kept = entries.filterNot { packageOf(it) in retiring }
        if (kept.size != entries.size) putString(key, kept.joinToString(separator))
    }

    private fun loadCustomIconMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val currentTheme = themeManager.getSelectedTheme()
        val themeKey = currentTheme.customIconsKey

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
     * Loads an icon referenced by an icon mapping. Paths either point into the bundled assets
     * or, for icons the user imported from their device, into [IMPORTED_ICONS_DIR] under filesDir.
     */
    private fun loadIconFromPath(iconPath: String): Drawable? =
        rocks.gorjan.gokixp.wp81.WP81TileHost.loadIconFromPath(this, iconPath)

    /**
     * Copies an image the user picked from their device into the app's own icon storage,
     * downsampled to icon size and re-encoded as PNG so transparency is preserved.
     * Returns the path to store in the icon mappings, or null if the image couldn't be read.
     */
    private fun importCustomIconFromUri(uri: Uri): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, ICON_SIZE_PX, ICON_SIZE_PX)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // keep the alpha channel

            val decoded = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Fit inside the icon size without upscaling; createSquareDrawable pads the rest
            val scale = minOf(
                ICON_SIZE_PX.toFloat() / decoded.width,
                ICON_SIZE_PX.toFloat() / decoded.height,
                1f
            )
            val bitmap = if (scale < 1f) {
                decoded.scale(
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                decoded
            }

            val iconsDir = File(filesDir, IMPORTED_ICONS_DIR).apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${System.currentTimeMillis()}.png")
            iconFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()

            Log.d("MainActivity", "Imported custom icon from $uri to ${iconFile.name}")
            "$IMPORTED_ICONS_DIR/${iconFile.name}"
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to import custom icon from $uri", e)
            null
        }
    }

    /**
     * Deletes imported icon files that no theme's icon mappings reference any more,
     * so replacing a custom icon doesn't leave the old image behind forever.
     */
    private fun pruneUnusedImportedIcons() {
        val iconsDir = File(filesDir, IMPORTED_ICONS_DIR)
        val files = iconsDir.listFiles() ?: return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inUse = AppTheme.all().map { it.customIconsKey }
            .flatMap { key -> (prefs.getString(key, "") ?: "").split(";") }
            .mapNotNull { entry -> entry.substringAfter(":", "").takeIf { it.isNotEmpty() } }
            .toSet()

        files.forEach { file ->
            if ("$IMPORTED_ICONS_DIR/${file.name}" !in inUse) {
                if (file.delete()) Log.d("MainActivity", "Removed unused imported icon: ${file.name}")
            }
        }
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
                    val drawable = loadIconFromPath(customIconPath)
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
        val iconSize = ICON_SIZE_PX // Standard size for desktop icons

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

    /**
     * Drops every cached bitmap belonging to a package so the next getAppIcon() call re-reads it
     * from the system. An in-place app update keeps the same package name, so without this the
     * LruCache keeps handing back the icon the app shipped with before the update - including
     * when the user picks "Default" in the Change Icon dialog.
     */
    private fun invalidateIconCache(packageName: String) {
        val staleKeys = iconBitmapCache.snapshot().keys.filter { key ->
            key == "app_$packageName" || key.startsWith("custom_${packageName}_")
        }
        staleKeys.forEach { iconBitmapCache.remove(it) }

        // The cached app list holds the old drawables too, so it has to be rebuilt
        cachedAppList = null

        Log.d("MainActivity", "Invalidated ${staleKeys.size} cached icons for $packageName")
    }

    /**
     * Repaints the desktop shortcuts (and folder contents, which have no view while the folder is
     * closed) that show a package, using the icon it currently has.
     */
    private fun repaintDesktopIconsFor(packageName: String) {
        val freshIcon = getAppIcon(packageName) ?: return

        desktopIcons.filter { it.packageName == packageName }.forEach { it.icon = freshIcon }

        desktopIconViews.forEach { iconView ->
            if (iconView.getDesktopIcon()?.packageName == packageName) {
                iconView.setIconDrawable(freshIcon)
            }
        }
    }

    /**
     * Repaints every place a package's icon is shown (desktop shortcuts, folder contents, start
     * menu and the pinned commands list) with the icon it currently has.
     *
     * The cache is dropped straight away so nothing can read a stale icon in the meantime, but the
     * repaint itself is coalesced: onPackageChanged also fires for component enable/disable, which
     * some apps do in bursts, and rebuilding the app list on each one would be wasteful.
     */
    private fun refreshIconsForPackage(packageName: String) {
        invalidateIconCache(packageName)
        pendingIconRefreshes.add(packageName)

        iconRefreshRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            iconRefreshRunnable = null
            val packages = pendingIconRefreshes.toList()
            pendingIconRefreshes.clear()

            packages.forEach { repaintDesktopIconsFor(it) }
            loadInstalledApps()
            refreshCommandsList()
        }
        iconRefreshRunnable = runnable
        handler.postDelayed(runnable, ICON_REFRESH_DEBOUNCE_MS)
    }

    /**
     * Package changes arrive through LauncherApps rather than the manifest receiver alone:
     * PACKAGE_ADDED/REPLACED broadcasts are unreliable for manifest receivers on modern Android,
     * and an in-place update leaves the app count unchanged so the periodic checker never notices
     * it either. Registered for the activity's lifetime, which is also the icon cache's lifetime.
     */
    private fun registerLauncherAppsCallback() {
        try {
            val launcherApps = attributionContext("system")
                .getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            val callback = object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) {
                    onAppInstalled(packageName)
                }

                override fun onPackageRemoved(packageName: String, user: UserHandle) {
                    onAppRemoved(packageName)
                }

                override fun onPackageChanged(packageName: String, user: UserHandle) {
                    // Fired when an app is updated in place - its icon may have changed
                    onAppReplaced(packageName)
                }

                override fun onPackagesAvailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean
                ) {
                    packageNames.forEach { refreshIconsForPackage(it) }
                }

                override fun onPackagesUnavailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean
                ) {
                    packageNames.forEach { invalidateIconCache(it) }
                }
            }

            // Own handler rather than the shared `handler` field, which isn't created until
            // later in onCreate - callbacks are delivered on the main thread either way
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
            launcherAppsCallback = callback
            Log.d("MainActivity", "LauncherApps package callback registered")
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not register LauncherApps callback", e)
        }
    }

    private fun unregisterLauncherAppsCallback() {
        val callback = launcherAppsCallback ?: return
        launcherAppsCallback = null
        try {
            val launcherApps = attributionContext("system")
                .getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not unregister LauncherApps callback", e)
        }
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
        // Chrome string, not the raw pref: this picks in-window art, and Windows Phone 8.1
        // draws its windows in Vista chrome. Reading the pref directly would silently
        // fall through the `else` arm to XP assets.
        val selectedTheme = themeManager.chromeThemeString()
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

        // Use the same grid system as icon dragging for consistency
        val currentOrientation = getCurrentOrientation()
        val columns = calculateGridColumns(currentOrientation)
        val rows = calculateGridRows(currentOrientation)

        // Get all occupied grid indices (same as snapSingleIconToGrid)
        val occupiedIndices = mutableSetOf<Int>()
        desktopIcons.forEach { icon ->
            // Skip icons in folders
            if (icon.parentFolderId != null) return@forEach

            val view = desktopIconViews.find { it.getDesktopIcon() == icon }
            if (view != null && view.parent != null && view.isVisible) {
                val gridIndex = when (currentOrientation) {
                    ScreenOrientation.PORTRAIT -> icon.portraitGridIndex
                    ScreenOrientation.LANDSCAPE -> icon.landscapeGridIndex
                }

                if (gridIndex != null) {
                    occupiedIndices.add(gridIndex)
                }
            }
        }

        // Convert menu position to grid index
        val menuGridIndex = convertXYToGridIndex(menuX, menuY, currentOrientation)

        // Find nearest available index
        var nearestIndex = menuGridIndex
        if (occupiedIndices.contains(nearestIndex)) {
            nearestIndex = findNearestAvailableIndex(menuGridIndex, occupiedIndices, columns, rows)
        }

        // Convert grid index to position (same as snapSingleIconToGrid)
        val (row, col) = convertIndexToPosition(nearestIndex, currentOrientation)
        val (newX, newY) = getGridCoordinatesFromIndex(row, col)
        val gridIndex = nearestIndex

        // Generate unique ID for the folder
        val folderId = "folder_${System.currentTimeMillis()}"

        // Create desktop icon for the folder with proper grid index
        val desktopIcon = DesktopIcon(
            name = "New Folder",
            packageName = folderId,
            icon = folderIcon,
            x = newX,
            y = newY,
            id = folderId,
            type = IconType.FOLDER,
            portraitGridIndex = if (currentOrientation == ScreenOrientation.PORTRAIT) gridIndex else null,
            landscapeGridIndex = if (currentOrientation == ScreenOrientation.LANDSCAPE) gridIndex else null
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
        val showClippyImageCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_clippy_image_checkbox)
        val alignRightCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.quick_glance_align_right_checkbox)
        val showRecycleBinCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_recycle_bin_checkbox)
        val showMyComputerCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_my_computer_checkbox)
        val showShortcutArrowOnIcons = contentView.findViewById<android.widget.CheckBox>(R.id.show_shortcut_arrow)
        val tapToHideIconsCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.tap_to_hide_icons_checkbox)
        val openUrlsInIeCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.open_urls_in_ie_checkbox)
        val defaultBrowserStatus = contentView.findViewById<TextView>(R.id.default_browser_status)
        val defaultBrowserLink = contentView.findViewById<TextView>(R.id.default_browser_link)
        val showAirQualityCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_air_quality_checkbox)
        val airQualityAttribution = contentView.findViewById<TextView>(R.id.air_quality_attribution)
        val showCursorCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_cursor_checkbox)
        val playEmailSoundCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.play_email_sound_checkbox)
        val showNotificationDotsCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_notification_dots_checkbox)
        val screensaverSelector = contentView.findViewById<android.widget.Spinner>(R.id.screensaver_selector)
        val previewScreensaverVideo = contentView.findViewById<VideoView>(R.id.preview_screensaver_video)
        val previewScreensaverButton = contentView.findViewById<TextView>(R.id.preview_screensaver_button)
        val customWallpaperButton = contentView.findViewById<View>(R.id.custom_wallpaper_button)

        // Set up theme spinner with appropriate layouts based on current theme
        val themes = arrayOf("Windows Classic", "Windows XP", "Windows Vista", "Windows Phone 8")
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

        // Set up Plus! theme spinner
        val plusThemeLabel = contentView.findViewById<TextView>(R.id.plus_theme_label)
        val plusThemeSpinner = contentView.findViewById<android.widget.Spinner>(R.id.plus_theme_spinner)
        val plusThemeDefaults = arrayOf("Default") + themeManager.getAllPlus95Themes().map { it.displayName }.toTypedArray()
        val plusSlugByName = mapOf(
            "Default" to ThemeManager.PLUS95_DEFAULT
        ) + themeManager.getAllPlus95Themes().associate { it.displayName to it.slug }
        val plusSpinnerAdapter = android.widget.ArrayAdapter(this, spinnerLayoutId, plusThemeDefaults)
        plusSpinnerAdapter.setDropDownViewResource(dropdownLayoutId)
        plusThemeSpinner.adapter = plusSpinnerAdapter

        val currentPlusSlug = themeManager.getPlus95Slug()
        val currentPlusName = plusSlugByName.entries.find { it.value == currentPlusSlug }?.key ?: "Default"
        val plusIndex = plusThemeDefaults.indexOf(currentPlusName)
        if (plusIndex != -1) {
            plusThemeSpinner.setSelection(plusIndex)
        }

        // Show/hide flavour + plus spinners based on current theme
        var flavourVisibility = if (shouldShowFlavourSpinner()) View.VISIBLE else View.GONE
        flavourLabel.visibility = flavourVisibility
        flavourSpinner.visibility = flavourVisibility
        plusThemeLabel.visibility = flavourVisibility
        plusThemeSpinner.visibility = flavourVisibility

        // Track pending theme and flavour selections (don't apply immediately)
        var pendingTheme: String? = null
        var pendingFlavour: String? = null
        var pendingPlus95: String? = null

        // The phone's accent and its Light/Dark setting used to be offered here too,
        // hidden behind a theme check. They are Windows Phone settings and Windows Phone
        // has a settings page of its own, reached from its own key strip - which is where
        // anyone looking for them goes, and where changing one shows immediately rather
        // than after a dialog is dismissed.


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

        // Handle Plus! theme selection - just track it, don't apply
        plusThemeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                pendingPlus95 = plusSlugByName[plusThemeDefaults[position]] ?: ThemeManager.PLUS95_DEFAULT
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

        // Set up Show Clippy image checkbox (Quick Glance)
        showClippyImageCheckbox.isChecked =
            if (::quickGlanceWidget.isInitialized) quickGlanceWidget.isShowClippyImageEnabled() else true
        showClippyImageCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (::quickGlanceWidget.isInitialized && isChecked != quickGlanceWidget.isShowClippyImageEnabled()) {
                quickGlanceWidget.setShowClippyImage(isChecked)
            }
        }

        // Set up Align right checkbox (Quick Glance)
        alignRightCheckbox.isChecked =
            if (::quickGlanceWidget.isInitialized) quickGlanceWidget.isAlignRightEnabled() else false
        alignRightCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (::quickGlanceWidget.isInitialized && isChecked != quickGlanceWidget.isAlignRightEnabled()) {
                quickGlanceWidget.setAlignRight(isChecked)
            }
        }

        // Set up Show Recycle Bin checkbox
        showRecycleBinCheckbox.isChecked = isRecycleBinVisible()
        showRecycleBinCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isRecycleBinVisible()) {
                toggleRecycleBin()
            }
        }

        // Set up Show My Computer checkbox
        showMyComputerCheckbox.isChecked = isMyComputerVisible()
        showMyComputerCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isMyComputerVisible()) {
                toggleMyComputer()
            }
        }

        // Set up Show Shortcut Arrow checkbox
        showShortcutArrowOnIcons.isChecked = isShortcutArrowVisible()
        showShortcutArrowOnIcons.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != isShortcutArrowVisible()) {
                toggleShortcutArrow()
            }
        }

        // Set up Tap Desktop To Hide Icons checkbox
        tapToHideIconsCheckbox.isChecked = isTapToHideIconsEnabled()
        tapToHideIconsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_TAP_TO_HIDE_ICONS, isChecked) }
            Log.d("MainActivity", "Tap to hide icons changed to: $isChecked")
            // If disabling while icons are hidden, restore them so the user isn't stuck.
            if (!isChecked && areDesktopIconsHidden) {
                toggleDesktopIconsVisibility()
            }
        }

        // Set up Open Desktop URLs In Internet Explorer checkbox
        openUrlsInIeCheckbox.isChecked = isOpenUrlsInIeEnabled()
        openUrlsInIeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_OPEN_URLS_IN_IE, isChecked) }
            Log.d("MainActivity", "Open URLs in IE changed to: $isChecked")
        }

        // Where the rest of the phone's links go. Read back from the system every time this
        // is shown rather than remembered, since the user can hand the role to something
        // else from Android's own settings without passing through here.
        defaultBrowserLink.paintFlags =
            defaultBrowserLink.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        val showDefaultBrowser = {
            val held = isDefaultBrowser()
            defaultBrowserStatus.text = if (held) {
                "Links from other apps open in Internet Explorer"
            } else {
                "Links from other apps open in the system browser"
            }
            defaultBrowserLink.text =
                if (held) "Change the default browser" else "Set as default browser"
        }
        showDefaultBrowser()
        refreshDefaultBrowser = showDefaultBrowser
        defaultBrowserLink.setOnClickListener {
            playClickSound()
            requestDefaultBrowser()
        }

        // Set up Show Air Quality checkbox (opt-in; default off)
        showAirQualityCheckbox.isChecked = isShowAqiEnabled()
        showAirQualityCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_SHOW_AQI, isChecked) }
            Log.d("MainActivity", "Show air quality changed to: $isChecked")
            val aqiContainer = findViewById<LinearLayout>(R.id.aqi_container)
            if (isChecked) {
                // Turned on: show cached value now and pull fresh data.
                aqiContainer?.visibility = View.VISIBLE
                getCachedAqi()?.let { if (isAqiDataFresh(90)) updateAqiDisplay(it) }
                refreshAqiData()
            } else {
                // Turned off: hide the taskbar indicator immediately.
                aqiContainer?.visibility = View.GONE
            }
            // Keep the Quick Glance tile in sync.
            if (::quickGlanceWidget.isInitialized) {
                quickGlanceWidget.refreshData()
            }
        }

        // Make "AirCare" in the attribution a link to the AirCare website.
        airQualityAttribution?.let { attribution ->
            val fullText = "(provided by AirCare)"
            val linkStart = fullText.indexOf("AirCare")
            val spannable = android.text.SpannableString(fullText)
            if (linkStart >= 0) {
                val clickable = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AIRCARE_URL)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error opening AirCare link", e)
                        }
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = "#0000EE".toColorInt()
                        ds.isUnderlineText = true
                    }
                }
                spannable.setSpan(clickable, linkStart, linkStart + "AirCare".length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            attribution.text = spannable
            attribution.movementMethod = android.text.method.LinkMovementMethod.getInstance()
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

        // Set up Show Christmas Lights checkbox
        val showChristmasLightsCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.show_christmas_lights_checkbox)
        val christmasLightsEnabled = prefs.getBoolean(KEY_CHRISTMAS_LIGHTS_VISIBLE, false)
        showChristmasLightsCheckbox.isChecked = christmasLightsEnabled

        // Set up Christmas Lights margin slider
        val christmasLightsMarginContainer = contentView.findViewById<LinearLayout>(R.id.christmas_lights_margin_container)
        val christmasLightsMarginSlider = contentView.findViewById<android.widget.SeekBar>(R.id.christmas_lights_margin_slider)
        val christmasLightsMarginValue = contentView.findViewById<TextView>(R.id.christmas_lights_margin_value)

        // Load saved margin value
        val savedMargin = prefs.getSafeInt(KEY_CHRISTMAS_LIGHTS_MARGIN, 0)
        christmasLightsMarginSlider.progress = savedMargin
        christmasLightsMarginValue.text = savedMargin.toString()

        // Show/hide slider based on checkbox state
        christmasLightsMarginContainer.visibility = if (christmasLightsEnabled) View.VISIBLE else View.GONE

        // Handle slider changes
        christmasLightsMarginSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Update the value display
                christmasLightsMarginValue.text = progress.toString()

                // Save to SharedPreferences
                prefs.edit { putInt(KEY_CHRISTMAS_LIGHTS_MARGIN, progress) }

                // Apply margin immediately if lights are visible
                val container = findViewById<LinearLayout>(R.id.christmas_lights)
                val layoutParams = container.layoutParams as RelativeLayout.LayoutParams
                layoutParams.topMargin = (progress * resources.displayMetrics.density).toInt()
                container.layoutParams = layoutParams

                Log.d("MainActivity", "Christmas lights margin changed to: ${progress}dp")
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        showChristmasLightsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_CHRISTMAS_LIGHTS_VISIBLE, isChecked) }
            Log.d("MainActivity", "Show Christmas lights changed to: $isChecked")

            // Show/hide margin slider
            christmasLightsMarginContainer.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Apply the change immediately
            if (isChecked) {
                initializeChristmasLights()
            } else {
                cleanupChristmasLights()
            }
        }

        // Set up Taskbar Height Slider
        val taskbarHeightSlider = contentView.findViewById<android.widget.SeekBar>(R.id.taskbar_height_slider)
        val taskbarHeightValue = contentView.findViewById<TextView>(R.id.taskbar_height_value)

        // Load current offset from SharedPreferences (range -30 to +30, slider range 0-60)
        val currentOffset = prefs.safeGetInt(KEY_TASKBAR_HEIGHT_OFFSET, 0)
        taskbarHeightSlider.progress = currentOffset + 30 // Convert from -30..30 to 0..60
        taskbarHeightValue.text = currentOffset.toString()

        // Track pending offset value (for OK/Apply buttons)
        var pendingTaskbarOffset: Int? = null

        // Handle slider changes
        taskbarHeightSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert slider value (0-60) to offset (-30 to +30)
                val offset = progress - 30
                taskbarHeightValue.text = offset.toString()
                pendingTaskbarOffset = offset

                // Save to SharedPreferences
                prefs.edit { putInt(KEY_TASKBAR_HEIGHT_OFFSET, offset) }

                // Apply immediately
                applyTaskbarHeightOffset(offset)

                Log.d("MainActivity", "Taskbar height offset changed to: $offset")
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Set up Slide Desktop Wallpaper checkbox + duration slider
        val slideWallpaperCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.slide_wallpaper_checkbox)
        val slideWallpaperDurationContainer = contentView.findViewById<LinearLayout>(R.id.slide_wallpaper_duration_container)
        val slideWallpaperDurationSlider = contentView.findViewById<android.widget.SeekBar>(R.id.slide_wallpaper_duration_slider)
        val slideWallpaperDurationValue = contentView.findViewById<TextView>(R.id.slide_wallpaper_duration_value)

        val slideWallpaperEnabled = prefs.getBoolean(KEY_SLIDE_WALLPAPER_ENABLED, false)
        val slideWallpaperDuration = prefs.safeGetInt(KEY_SLIDE_WALLPAPER_DURATION, DEFAULT_SLIDE_WALLPAPER_DURATION)
        slideWallpaperCheckbox.isChecked = slideWallpaperEnabled
        slideWallpaperDurationSlider.progress = slideWallpaperDuration
        slideWallpaperDurationValue.text = "${slideWallpaperDuration}s"
        slideWallpaperDurationContainer.visibility = if (slideWallpaperEnabled) View.VISIBLE else View.GONE

        slideWallpaperCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_SLIDE_WALLPAPER_ENABLED, isChecked) }
            slideWallpaperDurationContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) startWallpaperSlideIfEnabled() else stopWallpaperSlide()
            Log.d("MainActivity", "Slide desktop wallpaper changed to: $isChecked")
        }

        slideWallpaperDurationSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress.coerceIn(10, 60) // slider range 10s–60s
                slideWallpaperDurationValue.text = "${seconds}s"
                prefs.edit { putInt(KEY_SLIDE_WALLPAPER_DURATION, seconds) }
                // Restart with the new duration if the slide is currently running
                if (wallpaperSlideRunnable != null) startWallpaperSlideIfEnabled()
                Log.d("MainActivity", "Slide wallpaper duration changed to: ${seconds}s")
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
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
                val videoUri = "android.resource://${packageName}/${videoResource}".toUri()
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
                screensaverManager.setSelectedScreensaver(pendingScreensaverSelection)
                screensaverManager.showScreensaver()
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
        screensaverTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString().toIntOrNull()
                if (value != null && value in 10..60) {
                    pendingScreensaverTimeout = value
                } else if (s.toString().isEmpty()) {
                    pendingScreensaverTimeout = DEFAULT_SCREENSAVER_TIMEOUT
                }
            }
        })

        // Note: the Browse (custom wallpaper) button handler is set up later, after the
        // preview ImageView exists, so a picked image can update the live preview.

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
                plusThemeLabel.visibility = flavourVisibility
                plusThemeSpinner.visibility = flavourVisibility
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

        // Track a custom image picked via Browse (mutually exclusive with selectedWallpaper)
        var pickedCustomUri: Uri? = null

        // Clear any stale picker callback from a previous dialog instance
        onWallpaperImagePicked = null

        // Get wallpaper preview ImageView
        val wallpaperPreview = contentView.findViewById<ImageView>(R.id.wallpaper_preview)

        // Load and display current wallpaper in preview
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()
        var currentWallpaperPath = prefs.getString(pathKey, null) ?: getDefaultWallpaperForTheme()

        // Track the horizontal focus point [0,1] used to pan the wallpaper.
        // 0.5 == centered (CENTER_CROP). Lower => show more left, higher => show more right.
        val focusXKey = getCurrentThemeWallpaperFocusXKey()
        var currentFocusX = prefs.getSafeFloat(focusXKey, 0.5f)

        // Sets the preview scaleType and applies the current focus when appropriate.
        // "(m)" wallpapers stay FIT_CENTER (no cropping, dragging is disabled for them).
        fun configurePreviewForCurrent(isMinimized: Boolean) {
            if (isMinimized) {
                wallpaperPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                applyWallpaperFocusXToImageView(wallpaperPreview, currentFocusX)
            }
        }

        // Check if there's a custom wallpaper URI first
        val customWallpaperUri = prefs.getString(uriKey, null)
        if (customWallpaperUri != null) {
            // Load custom wallpaper from URI (downsampled to preview size to avoid huge bitmaps)
            try {
                val uri = customWallpaperUri.toUri()
                val previewPx = (160 * resources.displayMetrics.density).toInt()
                val bitmap = decodeSampledBitmapFromUri(uri, previewPx, previewPx)
                if (bitmap != null) {
                    wallpaperPreview.setImageDrawable(bitmap.toDrawable(resources))
                    configurePreviewForCurrent(isMinimized = false)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load custom wallpaper for preview: $customWallpaperUri", e)
            }
        } else {
            // Load built-in wallpaper from assets
            try {
                val inputStream = assets.open(currentWallpaperPath)
                val currentDrawable = Drawable.createFromStream(inputStream, currentWallpaperPath)
                inputStream.close()
                if (currentDrawable != null) {
                    wallpaperPreview.setImageDrawable(currentDrawable)
                    configurePreviewForCurrent(isMinimized = currentWallpaperPath.contains("(m)"))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load current wallpaper for preview: $currentWallpaperPath", e)
            }
        }

        // Horizontal drag on the preview pans the wallpaper live (preview + launcher).
        var dragStartRawX = 0f
        var dragStartFocusX = 0.5f
        var dragScaledImgWidthPx = 0f
        wallpaperPreview.setOnTouchListener { v, event ->
            if (wallpaperPreview.scaleType != ImageView.ScaleType.MATRIX) return@setOnTouchListener false
            val drawable = wallpaperPreview.drawable ?: return@setOnTouchListener false
            val imgW = drawable.intrinsicWidth.toFloat()
            val imgH = drawable.intrinsicHeight.toFloat()
            val vw = wallpaperPreview.width.toFloat()
            val vh = wallpaperPreview.height.toFloat()
            if (imgW <= 0f || imgH <= 0f || vw <= 0f || vh <= 0f) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartFocusX = currentFocusX
                    dragScaledImgWidthPx = maxOf(vw / imgW, vh / imgH) * imgW
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragScaledImgWidthPx > 0f) {
                        val dx = event.rawX - dragStartRawX
                        val focusDelta = -dx / dragScaledImgWidthPx
                        currentFocusX = (dragStartFocusX + focusDelta).coerceIn(0f, 1f)
                        applyWallpaperFocusXToImageView(wallpaperPreview, currentFocusX)
                        val launcherWallpaper = findViewById<RelativeLayout>(R.id.main_background)
                            ?.findViewWithTag<ImageView>("wallpaper")
                        if (launcherWallpaper != null) {
                            applyWallpaperFocusXToImageView(launcherWallpaper, currentFocusX)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit { putFloat(focusXKey, currentFocusX) }
                    true
                }
                else -> false
            }
        }

        // Get button references
        val okButton = contentView.findViewById<View>(R.id.wallpaper_ok_button)
        val cancelButton = contentView.findViewById<View>(R.id.wallpaper_cancel_button)
        val applyButton = contentView.findViewById<View>(R.id.wallpaper_apply_button)

        val adapter = WallpaperAdapter(wallpapers) { wallpaper ->
            // Preview the wallpaper instead of showing target dialog immediately
            selectedWallpaper = wallpaper
            pickedCustomUri = null
            wallpaperPreview.setImageDrawable(wallpaper.drawable)
            configurePreviewForCurrent(isMinimized = wallpaper.name.contains("(m)"))
            playClickSound()
        }
        recyclerView.adapter = adapter

        // Browse button: pick a custom image and show it in the live preview immediately.
        customWallpaperButton.setOnClickListener {
            playClickSound()
            onWallpaperImagePicked = { uri ->
                try {
                    val previewPx = (160 * resources.displayMetrics.density).toInt()
                    val bitmap = decodeSampledBitmapFromUri(uri, previewPx, previewPx)
                    if (bitmap != null) {
                        selectedWallpaper = null
                        pickedCustomUri = uri
                        currentFocusX = 0.5f
                        wallpaperPreview.setImageDrawable(bitmap.toDrawable(resources))
                        // Custom images support panning/cropping like built-in ones
                        configurePreviewForCurrent(isMinimized = false)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to load picked wallpaper for preview: $uri", e)
                }
            }
            imagePickerLauncher.launch("image/*")
        }

        // Set up OK button - apply theme/flavour changes, show target dialog if wallpaper changed, then close
        okButton.setOnClickListener {
            playClickSound()

            // Apply pending Plus! theme first so wallpaper/cursor/sound pick up its overrides
            if (pendingPlus95 != null && pendingPlus95 != themeManager.getPlus95Slug()) {
                applyPlus95Theme(pendingPlus95!!)
            }

            // Persist the WP8.1 accent and background before any theme switch: applyTheme()
            // recreates the activity, and the rebuilt shell reads these on the way up.

            // Apply pending theme changes
            if (pendingTheme != null && pendingTheme != currentTheme.toString()) {
                prefs.edit {
                    putString("selected_theme", pendingTheme)
                    // Switching away from Classic resets the Plus! slug to "default"
                    if (AppTheme.fromString(pendingTheme) !is AppTheme.WindowsClassic) {
                        putString(ThemeManager.KEY_PLUS95_THEME, ThemeManager.PLUS95_DEFAULT)
                    }
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
            if (pickedCustomUri != null) {
                handleSelectedImage(pickedCustomUri!!)
            } else if (selectedWallpaper != null) {
                showWallpaperTargetDialog(selectedWallpaper)
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

            // Apply pending Plus! theme first
            if (pendingPlus95 != null && pendingPlus95 != themeManager.getPlus95Slug()) {
                applyPlus95Theme(pendingPlus95!!)
                pendingPlus95 = null
            }

            // Persist the WP8.1 accent and background before any theme switch: applyTheme()
            // recreates the activity, and the rebuilt shell reads these on the way up.

            // Apply pending theme changes
            if (pendingTheme != null && pendingTheme != currentTheme.toString()) {
                prefs.edit {
                    putString("selected_theme", pendingTheme)
                    if (AppTheme.fromString(pendingTheme) !is AppTheme.WindowsClassic) {
                        putString(ThemeManager.KEY_PLUS95_THEME, ThemeManager.PLUS95_DEFAULT)
                    }
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
            if (pickedCustomUri != null) {
                handleSelectedImage(pickedCustomUri!!)
                pickedCustomUri = null
            } else if (selectedWallpaper != null) {
                showWallpaperTargetDialog(selectedWallpaper)
                // Update current wallpaper path after applying
                selectedWallpaper = null
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
        // The phone has its own browser: the same engine and the same favourites, with the
        // page given the whole screen and one dark strip along the bottom instead of a
        // toolbar. A window with a title bar and eight buttons across the top would be the
        // one thing in this shell that still looked like a desktop.
        if (themeManager.isWindowsPhone81()) {
            showMetroIEDialog(initialUrl)
            return
        }

        // Check if an IE window is already open
        val existingIEWindow = findExistingInternetExplorerWindow()

        if (existingIEWindow != null && initialUrl != null) {
            // Reuse existing window and navigate to new URL
            val ieApp = existingIEWindow.internetExplorerApp as? InternetExplorerApp
            if (ieApp != null) {
                ieApp.navigateToUrl(initialUrl)
                // Bring the window to front and restore if minimized
                existingIEWindow.bringToFront()
                if (existingIEWindow.isMinimized()) {
                    existingIEWindow.restore()
                }
                setCursorNormal()
                return
            }
        }

        // Set cursor to busy while loading
        setCursorBusy()
        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowInternetExplorerDialog(initialUrl, appInfo)
        }
    }

    /**
     * Find an existing Internet Explorer window if one is open
     */
    private fun findExistingInternetExplorerWindow(): WindowsDialog? {
        val floatingWindowsContainer = findViewById<android.widget.FrameLayout>(R.id.floating_windows_container)
        for (i in 0 until floatingWindowsContainer.childCount) {
            val child = floatingWindowsContainer.getChildAt(i)
            if (child is WindowsDialog && child.windowIdentifier == "system.internet_explorer") {
                return child
            }
        }
        return null
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
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) },
            onShowContextMenu = { items, x, y ->
                if (::contextMenu.isInitialized) {
                    contextMenu.showMenu(items, x, y)
                }
            }
        )

        ieApp.setupApp(contentView, initialUrl)

        // Store IE app instance in window for back navigation handling
        windowsDialog.internetExplorerApp = ieApp

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

    private fun showRegistryEditorDialog() {
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
            // Store the JSON temporarily for the launcher callback
            pendingExportJson = PrefsBackup.toJson(prefs)

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
            val jsonString = PrefsBackup.toJson(prefs)

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
                        PrefsBackup.restore(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), jsonString)
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

    private fun showDialerDialog() {
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

    private fun showNotepadDialog() {
        // The phone has its own notepad: the same notes, laid out as a panorama of what
        // you have written with a page per note, and one strip of commands along the
        // bottom. A window with a title bar, a list down one side and buttons among the
        // text would be the one thing in this shell that still looked like a desktop.
        if (themeManager.isWindowsPhone81()) {
            showMetroNotepadDialog()
            return
        }

        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowNotepadDialog()
        }
    }

    /**
     * Opens the phone's Notepad.
     *
     * Full-screen and chromeless like Zune, News and the browser. One window only: the
     * notes are a place rather than a document, and a second copy of the list would be two
     * views of one file with no way to tell which one had the newest keystroke in it.
     */
    private fun showMetroNotepadDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.notepad")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.notepad"

        val notepadApp = rocks.gorjan.gokixp.apps.notepad.MetroNotepadApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onShowNotification = { title, message -> showNotification(title, message) },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) },
            galleryPickerLauncher = notepadGalleryPickerLauncher,
            onCameraCapture = { uri ->
                pendingCameraUri = uri
                notepadCameraPickerLauncher.launch(uri)
            },
            onShowFullscreenImage = { uri -> showFullscreenImage(uri) }
        )
        metroNotepadAppInstance = notepadApp

        val notepadView = notepadApp.createView()
        windowsDialog.setContentView(notepadView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_notepad)
        windowsDialog.setTitle("Notepad")
        windowsDialog.setOnCloseListener {
            notepadApp.cleanup()
            metroNotepadAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(notepadView)
    }

    /**
     * Opens the phone's Minesweeper.
     *
     * Full screen and chromeless like the rest of the shell's own programs, and one window
     * only: a second copy would be a second game running its own clock behind the first.
     */
    private fun showMetroMinesweeperDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.minesweeper")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.minesweeper"

        val game = rocks.gorjan.gokixp.apps.minesweeper.MetroMinesweeperApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )
        metroMinesweeperInstance = game

        val view = game.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_minesweeper)
        windowsDialog.setTitle("Minesweeper")
        windowsDialog.setOnCloseListener {
            game.cleanup()
            metroMinesweeperInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /** Opens the phone's Solitaire. The same window rules as Minesweeper above. */
    private fun showMetroSolitaireDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.solitare")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.solitare"

        val game = rocks.gorjan.gokixp.apps.solitare.MetroSolitaireApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )
        metroSolitaireInstance = game

        val view = game.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_solitaire)
        windowsDialog.setTitle("Solitaire")
        windowsDialog.setOnCloseListener {
            game.cleanup()
            metroSolitaireInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
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
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        try {
            // First, decode the bitmap
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)
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


    /**
     * Gets the button background drawable resource for the current theme
     */
    private fun getThemedButtonBackground(): Int {
        return when (themeManager.getSelectedTheme().chrome) {
            DesktopChrome.CLASSIC -> R.drawable.win98_start_menu_border
            DesktopChrome.XP -> R.drawable.button_xp_background
            DesktopChrome.VISTA -> R.drawable.button_xp_background
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
        // Create Windows dialog
        val windowsDialog = createThemedWindowsDialog()

        // Inflate layout
        val contentView = layoutInflater.inflate(R.layout.program_dialog_box, null)

        // Create DialogBoxApp instance with cancel button enabled
        val dialogBoxApp = rocks.gorjan.gokixp.apps.dialogbox.DialogBoxApp(
            context = this,
            theme = themeManager.getSelectedTheme(),
            themeManager = themeManager,
            dialogType = rocks.gorjan.gokixp.apps.dialogbox.DialogType.WARNING,
            message = message,
            onClose = {
                playClickSound()
                onConfirm()
                floatingWindowManager.removeWindow(windowsDialog)
            },
            onPlaySound = { soundResId ->
                playSound(soundResId)
            },
            showCancelButton = true,
            onCancel = {
                playClickSound()
                floatingWindowManager.removeWindow(windowsDialog)
            }
        )

        // Setup the dialog
        dialogBoxApp.setupDialog(contentView)

        // Set window properties
        windowsDialog.setTitle(title)
        windowsDialog.setTaskbarIcon(dialogBoxApp.getIconResId())
        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(260)
        windowsDialog.setMinimizable(false)

        // Set context menu reference
        windowsDialog.setContextMenuView(contextMenu)

        // Show the window
        floatingWindowManager.showWindow(windowsDialog)
    }


    // Winamp state for permission handling
    private var winampAppInstance: rocks.gorjan.gokixp.apps.winamp.WinampApp? = null

    // WMP state for permission handling
    private var wmpAppInstance: rocks.gorjan.gokixp.apps.wmp.WmpApp? = null

    private fun showMinesweeperDialog(appInfo: AppInfo? = null) {
        // The phone has its own, which is the same game on a page rather than in a window.
        if (themeManager.isWindowsPhone81()) {
            showMetroMinesweeperDialog()
            return
        }

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
        // Likewise: the same deck, dealt onto a page. See showMetroSolitaireDialog.
        if (themeManager.isWindowsPhone81()) {
            showMetroSolitaireDialog()
            return
        }

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

    private fun showPinballDialog(appInfo: AppInfo? = null) {
        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.pinball"  // Set identifier for tracking
        windowsDialog.setTitle("Pinball")
        windowsDialog.setTaskbarIcon(R.drawable.pinball)

        // Inflate the pinball layout (a full-bleed WebView)
        val contentView = layoutInflater.inflate(R.layout.program_pinball, null)

        // Create Pinball app instance and load the bundled web game
        val pinballApp = rocks.gorjan.gokixp.apps.pinball.PinballApp(this)
        pinballApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSizePercentage(90f, 60f)  // Restore size when un-maximized
        windowsDialog.setMaximizable(true)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            pinballApp.cleanup()
        }
        // Cleanup on minimize
        windowsDialog.setOnMinimizeListener {
            // Game continues running when minimized
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Always open maximized (after the window is added and laid out)
        Handler(Looper.getMainLooper()).postDelayed({
            windowsDialog.maximizeWindow()
        }, 100)
    }

    /**
     * Public API to open Winamp with optional file to play
     */
    fun openWinamp(fileToPlay: String? = null) {
        showWinampDialog(fileToPlay = fileToPlay)
    }

    private fun showWinampDialog(appInfo: AppInfo? = null, fileToPlay: String? = null) {
        // Check if Winamp is already open
        val existingWindow = floatingWindowManager.findWindowByIdentifier("system.winamp")
        if (existingWindow != null) {
            // Winamp already open, bring to front
            floatingWindowManager.findAndFocusWindow("system.winamp")
            // If a file was specified, play it
            if (fileToPlay != null) {
                winampAppInstance?.playSpecificFile(fileToPlay)
            }
            return
        }

        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowWinampDialog(fileToPlay)
        }
    }

    private fun createAndShowWinampDialog(fileToPlay: String? = null) {
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
            contextMenuView = contextMenu,
            fileToPlay = fileToPlay
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

    private var zuneAppInstance: rocks.gorjan.gokixp.apps.zune.ZuneApp? = null

    /**
     * Opens Zune.
     *
     * Borderless, and maximised because every maximisable window is under this theme, so
     * what appears is a full-screen phone app rather than a program in a frame. That is
     * not decoration: the whole design is built on having the screen to itself, and in a
     * 358x420 window with a title bar it would look like a mistake.
     */
    private fun showZuneDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.zune")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.zune"

        val zuneApp = rocks.gorjan.gokixp.apps.zune.ZuneApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = {
                val permission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                requestPermissions(arrayOf(permission), AUDIO_PERMISSION_REQUEST_CODE)
            },
            hasAudioPermission = {
                val permission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        zuneAppInstance = zuneApp

        val zuneView = zuneApp.createView()
        windowsDialog.setContentView(zuneView)
        windowsDialog.setBorderless()
        // No size of its own: the forced maximise fills the container, which is the screen
        // minus the navigation keys. Stating a size in screen percent instead made the
        // frame taller than the space it lives in, and a window bigger than its overlay is
        // centred rather than clamped - so it hung off the top and the bottom at once.
        //
        // Nothing about its geometry is worth remembering either: it is always full-screen,
        // and a saved position could only ever be wrong.
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_headphones)
        windowsDialog.setTitle("Music")
        windowsDialog.setOnCloseListener {
            zuneApp.cleanup()
            zuneAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(zuneView)
    }

    private var newsAppInstance: rocks.gorjan.gokixp.apps.news.NewsApp? = null

    /**
     * Opens Calculator.
     *
     * Full-screen and chromeless like Zune and News, and one window only: the keypad has
     * no notion of a second sum going on somewhere else, and a calculator opened twice
     * would be two calculators disagreeing about what is in memory.
     */
    private fun showCalculatorDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.calculator")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.calculator"

        val calculator = rocks.gorjan.gokixp.apps.calculator.CalculatorApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )

        val view = calculator.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_calculator)
        windowsDialog.setTitle("Calculator")
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * Opens Welcome, the phone's version of the window the desktop themes show after an
     * update.
     */
    private fun showWelcomeDialogWP81() {
        if (floatingWindowManager.findAndFocusWindow("system.welcome")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.welcome"

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val welcomeApp = rocks.gorjan.gokixp.apps.welcome.WelcomeApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            versionName = version,
            onOpenLink = { url ->
                if (url.startsWith("mailto:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    } catch (e: Exception) {
                        Log.w("MainActivity", "No mail app for $url", e)
                    }
                } else {
                    openUrlShortcut(url)
                }
            },
            loadReleaseNotes = { onReady -> fetchWP81ReleaseNotes(onReady) }
        )

        val view = welcomeApp.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_welcome)
        windowsDialog.setTitle("Welcome")
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * The release notes, from the same GitHub releases the desktop welcome reads.
     *
     * One list of what changed, fetched rather than bundled, so it is never a build behind
     * what is actually out.
     */
    private fun fetchWP81ReleaseNotes(onReady: (String) -> Unit) {
        Thread {
            val text = try {
                val url = URL("https://api.github.com/repos/jovanovski/windowslauncher/releases")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    "Could not reach GitHub for the release notes."
                } else {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val releases = Gson().fromJson(response, com.google.gson.JsonArray::class.java)
                    if (releases == null || releases.size() == 0) {
                        "No release notes yet."
                    } else {
                        buildString {
                            for (i in 0 until releases.size()) {
                                val release = releases[i].asJsonObject
                                val name = release.get("name")?.asString
                                    ?: release.get("tag_name")?.asString ?: "Unknown version"
                                val body = release.get("body")?.asString.orEmpty()
                                append(name).append("\n")
                                if (body.isNotEmpty()) append(body).append("\n")
                                if (i < releases.size() - 1) append("\n")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not read the release notes", e)
                "Could not load the release notes."
            }
            runOnUiThread { onReady(text) }
        }.start()
    }

    /**
     * Turns a full-screen program in the way the shell's own pages turn.
     *
     * These are pages as far as the user is concerned - they fill the screen, they are
     * reached from Start and left with back - so they arrive the same way the settings and
     * folder pages do rather than simply being there. Deferred a frame: the window sizes
     * itself on the next pass, and a turn measured against a view with no height yet
     * pivots around the wrong place.
     */
    private fun turnWP81PageIn(view: View) {
        if (wp81Shell == null) return
        view.post { rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playIn() }
    }

    private var metroIEAppInstance: rocks.gorjan.gokixp.apps.iexplore.MetroIEApp? = null

    /**
     * Opens the phone's Internet Explorer.
     *
     * Full-screen and chromeless like Zune and News. One window only: a phone browser is a
     * place rather than a document, so a second address arriving while it is open is a
     * navigation in the browser that is already there, not another copy of it.
     */
    private fun showMetroIEDialog(initialUrl: String? = null) {
        val open = floatingWindowManager.findWindowByIdentifier("system.internet_explorer")
        if (open != null) {
            floatingWindowManager.findAndFocusWindow("system.internet_explorer")
            if (initialUrl != null) metroIEAppInstance?.navigateToUrl(initialUrl)
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.internet_explorer"

        val ieApp = rocks.gorjan.gokixp.apps.iexplore.MetroIEApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onShowNotification = { title, message -> showNotification(title, message) },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) }
        )
        metroIEAppInstance = ieApp

        val ieView = ieApp.createView(initialUrl)
        windowsDialog.setContentView(ieView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_ie)
        windowsDialog.setTitle("Internet Explorer")
        windowsDialog.setOnCloseListener {
            ieApp.cleanup()
            metroIEAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(ieView)
    }

    /**
     * Opens the News reader.
     *
     * Full-screen and chromeless like Zune - it is a phone app, and the panorama it is
     * built on needs the screen to itself.
     */
    private fun showNewsDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.news")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.news"

        val newsApp = rocks.gorjan.gokixp.apps.news.NewsApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            feed = wp81NewsFeed,
            onOpenStory = { story ->
                if (story.link.isBlank()) showNotification("News", "That story has no link")
                else openUrlShortcut(story.link)
            },
            onRefresh = {
                wp81NewsFeed.refreshIfStale(
                    themeManager.getWP81NewsFeeds().toList().sorted(), force = true)
            },
            enabledFeeds = { themeManager.getWP81NewsFeeds() },
            onFeedsChanged = { ids ->
                themeManager.setWP81NewsFeeds(ids)
                // Forced: the answer has changed, whatever the last fetch was and whenever
                // it happened. The tile is told straight away too, so it says what it is
                // doing rather than sitting on the old stories until the new ones land.
                refreshWP81News()
                refreshWP81NewsFeeds(force = true)
            }
        )
        newsAppInstance = newsApp

        val newsView = newsApp.createView()
        windowsDialog.setContentView(newsView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_news)
        windowsDialog.setTitle("News")
        windowsDialog.setOnCloseListener { newsAppInstance = null }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(newsView)

        // Asked for on the way in, whatever the tile has been doing: opening a reader is a
        // request for what is current, and forced so it does not sit on half-hour-old
        // stories because the tile happened to fetch them recently.
        wp81NewsFeed.refreshIfStale(
            themeManager.getWP81NewsFeeds().toList().sorted(), force = true)
    }

    /**
     * Public API to open Windows Media Player with optional file to play
     */
    fun openWmp(fileToPlay: String? = null) {
        showWmpDialog(fileToPlay = fileToPlay)
    }

    private fun showWmpDialog(appInfo: AppInfo? = null, fileToPlay: String? = null) {
        // Check if WMP is already open
        val existingWindow = floatingWindowManager.findWindowByIdentifier("system.wmp")
        if (existingWindow != null) {
            // WMP already open, bring to front
            floatingWindowManager.findAndFocusWindow("system.wmp")
            // If a file was specified, play it
            if (fileToPlay != null) {
                wmpAppInstance?.playSpecificFile(fileToPlay)
            }
            return
        }

        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowWmpDialog(fileToPlay)
        }
    }

    private fun createAndShowWmpDialog(fileToPlay: String? = null) {
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
            },
            fileToPlay = fileToPlay
        )

        // Store reference for permission callback
        wmpAppInstance = wmpApp

        // Setup the app
        wmpApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)

        // Disable state persistence - WMP has fixed size per theme
        windowsDialog.setSaveState(false)

        // Set window size based on theme
        when {
            themeManager.isClassicTheme() -> {
                windowsDialog.setWindowSize(286, 412)
            }
            themeManager.isXPTheme() -> {
                windowsDialog.setWindowSize(384, 262)
            }
            themeManager.isVistaChrome() -> {
                windowsDialog.setWindowSize(384, 284)
            }
            else -> {
                // Fallback to XP size
                windowsDialog.setWindowSize(384, 262)
            }
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
     * Public API to open Photo Viewer with an image file
     */
    fun openPhotoViewer(imagePath: String) {
        // Set cursor to busy while loading
        setCursorBusy()

        // Defer the actual loading to allow cursor to render
        Handler(Looper.getMainLooper()).post {
            createAndShowPhotoViewerDialog(imagePath)
        }
    }

    private fun createAndShowPhotoViewerDialog(imagePath: String) {
        val imageFile = java.io.File(imagePath)
        if (!imageFile.exists()) {
            Log.e("MainActivity", "Image file does not exist: $imagePath")
            setCursorNormal()
            return
        }

        // Create Windows-style dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.photoviewer.${imagePath.hashCode()}"  // Unique identifier per image
        windowsDialog.setTitle(imageFile.name)
        windowsDialog.setTaskbarIcon(themeManager.getPhotosIcon())

        // Inflate the photo viewer content
        val contentView = layoutInflater.inflate(R.layout.program_photos, null)

        // Create Photo Viewer app instance
        val photoViewerApp = rocks.gorjan.gokixp.apps.photos.PhotoViewerApp(
            context = this,
            imageFile = imageFile
        )

        // Setup the app
        photoViewerApp.setupApp(contentView)

        windowsDialog.setContentView(contentView)
        windowsDialog.setMaximizable(true)
        windowsDialog.setWindowSizePercentage(90f, 40f)

        // Cleanup on close
        windowsDialog.setOnCloseListener {
            photoViewerApp.cleanup()
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
            if (themeManager.isWindowsPhone81()) {
                // The same moment, in this theme's own program: a Vista dialog with a
                // picture and two buttons over a Start screen would be a window from
                // another operating system.
                showWelcomeDialogWP81()
            } else if(shownForVersion == null){
                showWelcomeToWindows()
            }
            else{
                showWelcomeToWindows(showChangeLog = true)
            }


            // Save that we've shown it for this version
            prefs.edit { putString(KEY_SHOWN_WELCOME_FOR_VERSION, currentVersion) }
        }
    }

    private fun showWelcomeToWindows(showChangeLog: Boolean = false) {
        // Get theme preferences
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Chrome string, not the raw pref: this picks in-window art, and Windows Phone 8.1
        // draws its windows in Vista chrome. Reading the pref directly would silently
        // fall through the `else` arm to XP assets.
        val selectedTheme = themeManager.chromeThemeString()

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
        val welcomeMessage = "Windows has updated to version $versionName, tap 'Change Log' to see what's new!\n\nIf you like what I'm building, buy me a coffee here: https://buymeacoffee.com/jovanovski.\n\nThis is a passion project from Gorjan Jovanovski, a developer who grew up with these aesthetics and prefers them over new design any day.\n\nIf you're a 80s or 90s kid, you remember these days fondly, and this is a change to relive them on a modern daily driver, in your pocket!\n\nA few tips:\n1) Tap on things that look tappable, chances are they are.\n2) Swipe back to close the active open window.\n3) Swipe up, down and right on the desktop for different actions.\n4) Long press on the desktop to change wallpapers and themes.\n5) There are multiple Windows apps in the start menu, all with their own purpose.\n\nAll the copyrighted information belongs to their respective authors, the aim here is to just recreate nostalgia for fun.\n\nThe music you're listening to from the legendary Stan LePard, rest in peace!\n\nFor any feature requests, drop me an email at hey@gorjan.rocks\n\nThanks for using Windows!"

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

        // Set welcome message with automatic link detection
        val versionTextView = contentView.findViewById<TextView>(R.id.version)

        // Set version text (using the versionName we already retrieved)
        versionTextView?.text = "Version: $versionName"

        // Set welcome text and auto-linkify URLs and email addresses
        welcomeTextView.text = welcomeMessage
        Linkify.addLinks(welcomeTextView, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        welcomeTextView.movementMethod = LinkMovementMethod.getInstance()
        welcomeTextView.setLinkTextColor(Color.parseColor("#0000FF")) // Windows blue

        // Now add custom clickable spans on top of auto-linkified text
        val spannableString = SpannableString(welcomeTextView.text)

        // Make "Windows" (first word) clickable to open GitHub repo
        val windowsStart = welcomeMessage.indexOf("Windows")
        val windowsEnd = windowsStart + "Windows".length

        if (windowsStart != -1) {
            val windowsClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrlShortcut("https://github.com/jovanovski/windowslauncher/")
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.color = Color.BLUE
                }
            }
            spannableString.setSpan(windowsClickableSpan, windowsStart, windowsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Make "Change Log" clickable
        val changeLogStart = welcomeMessage.indexOf("Change Log")
        val changeLogEnd = changeLogStart + "Change Log".length

        if (changeLogStart != -1) {
            val changeLogClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    changeLogButton?.performClick()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.color = Color.BLUE
                }
            }
            spannableString.setSpan(changeLogClickableSpan, changeLogStart, changeLogEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Make "Gorjan Jovanovski" clickable
        val gorjanStart = welcomeMessage.indexOf("Gorjan Jovanovski")
        val gorjanEnd = gorjanStart + "Gorjan Jovanovski".length

        if (gorjanStart != -1) {
            val gorjanClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrlShortcut("https://gorjan.rocks")
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.color = Color.BLUE
                }
            }
            spannableString.setSpan(gorjanClickableSpan, gorjanStart, gorjanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        welcomeTextView.text = spannableString

        // Set up button click listeners to switch between welcome and changelog
        welcomeButton?.setOnClickListener {
            // Switch to welcome image
            backgroundImageView.setImageResource(welcomeDrawable)

            // Restore welcome text with clickable links
            welcomeTextView.text = spannableString
            welcomeTextView.movementMethod = LinkMovementMethod.getInstance()
            welcomeTextView.setLinkTextColor(Color.parseColor("#0000FF")) // Windows blue
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
                    // Make URLs in the changelog clickable with Windows blue color
                    Linkify.addLinks(welcomeTextView, Linkify.WEB_URLS)
                    welcomeTextView.movementMethod = LinkMovementMethod.getInstance()
                    welcomeTextView.setLinkTextColor(Color.parseColor("#0000FF")) // Windows blue
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
    
    /** True if a bundled asset exists at [path] under assets/. */
    private fun assetExists(path: String): Boolean = try {
        assets.open(path).close(); true
    } catch (e: Exception) { false }

    /**
     * The Plus! theme's bundled wallpaper asset path (plus95/<slug>/wall.jpg) if it ships one,
     * else null. Themes without a wall.jpg (e.g. the Plus! 98 set) return null so callers fall
     * back to the default Classic wallpaper.
     */
    private fun plus95WallpaperPath(slug: String): String? {
        if (slug == ThemeManager.PLUS95_DEFAULT) return null
        val path = themeManager.plus95Path(slug, "wall.jpg")
        return if (assetExists(path)) path else null
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

    /**
     * Pans an ImageView's image horizontally using a focusX in [0,1] (0.5 == CENTER_CROP).
     * Uses MATRIX scaleType and clamps so the image always covers the view.
     */
    private fun applyWallpaperFocusXToImageView(imageView: ImageView, focusX: Float) {
        val drawable = imageView.drawable ?: return
        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        if (imgWidth <= 0f || imgHeight <= 0f) return

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            imageView.post { applyWallpaperFocusXToImageView(imageView, focusX) }
            return
        }

        val scale = maxOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val scaledImgWidth = imgWidth * scale
        val scaledImgHeight = imgHeight * scale

        val clampedFocusX = focusX.coerceIn(0f, 1f)
        var translateX = viewWidth / 2f - clampedFocusX * scaledImgWidth
        val minTranslateX = minOf(viewWidth - scaledImgWidth, 0f)
        translateX = translateX.coerceIn(minTranslateX, 0f)
        val translateY = (viewHeight - scaledImgHeight) / 2f

        val matrix = android.graphics.Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(translateX, translateY)

        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
    }

    private fun getWallpaperImageView(): ImageView? {
        return findViewById<RelativeLayout>(R.id.main_background)?.findViewWithTag<ImageView>("wallpaper")
    }

    /**
     * Starts (or restarts) the wallpaper slide animation if the setting is enabled.
     * The wallpaper slowly pans from X offset 0 to the max X offset and back, looping.
     * The configured duration covers the whole 0 -> max -> 0 cycle.
     */
    private fun startWallpaperSlideIfEnabled() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SLIDE_WALLPAPER_ENABLED, false)) return

        val wallpaperImageView = getWallpaperImageView() ?: return
        val drawable = wallpaperImageView.drawable ?: return
        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = wallpaperImageView.width.toFloat()
        val viewHeight = wallpaperImageView.height.toFloat()

        // Cancel any running loop (don't restore the manual offset; we're about to drive it).
        wallpaperSlideRunnable?.let { wallpaperImageView.removeCallbacks(it) }
        wallpaperSlideRunnable = null

        // Wait until the view is laid out and the drawable has real dimensions.
        if (imgWidth <= 0f || imgHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
            wallpaperImageView.post { startWallpaperSlideIfEnabled() }
            return
        }

        // The image is scaled to cover the view (center-crop). Work out exactly how many pixels
        // of horizontal slack there are: that's the full distance we can pan.
        val scale = maxOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val scaledImgWidth = imgWidth * scale
        val scaledImgHeight = imgHeight * scale
        val panRangePx = scaledImgWidth - viewWidth // >= 0; total horizontal travel
        val translateY = (viewHeight - scaledImgHeight) / 2f

        Log.d("WPSLIDE", "START img=${imgWidth}x${imgHeight} view=${viewWidth}x${viewHeight} scale=$scale scaledW=$scaledImgWidth panRangePx=$panRangePx")

        // Nothing to slide if the image isn't wider than the view.
        if (panRangePx < 1f) {
            applyWallpaperFocusXToImageView(wallpaperImageView, 0.5f)
            return
        }

        val durationSeconds = prefs.getSafeInt(KEY_SLIDE_WALLPAPER_DURATION, DEFAULT_SLIDE_WALLPAPER_DURATION)
        // durationSeconds is the ONE-WAY time (left edge -> right edge); round trip is 2x.
        val cycleMs = durationSeconds * 2 * 1000L
        // Easing should total ~2s per leg: 1s ramping up + 1s ramping down, with the rest of the
        // leg at constant speed. The ramp covers (1s / leg duration) of each end.
        val rampFraction = (1f / durationSeconds).coerceIn(0.01f, 0.5f)

        // Drive from REAL elapsed time via a per-frame Choreographer callback (postOnAnimation),
        // which ignores the system "Animator duration scale" developer setting, so the configured
        // duration is honored exactly. Translate X goes 0 -> -panRangePx -> 0, eased in/out.
        // Offset the start so we resume from where the slide last stopped (e.g. after backgrounding).
        val startTime = android.os.SystemClock.uptimeMillis() - (wallpaperSlidePositionMs % cycleMs)
        var frameCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (wallpaperSlideRunnable !== this) return // superseded/stopped
                val elapsed = (android.os.SystemClock.uptimeMillis() - startTime) % cycleMs
                wallpaperSlidePositionMs = elapsed // remember where we are so we can resume later
                val phase = elapsed.toFloat() / cycleMs.toFloat()
                // Linear progress of the current leg: 0->1 going out, 1->0 coming back.
                val legProgress = if (phase < 0.5f) phase * 2f else 2f - phase * 2f
                // Ease in/out so it accelerates from and decelerates to rest at each end (~1s each),
                // with a constant-speed glide through the middle.
                val eased = easeWithRamp(legProgress, rampFraction)
                val translateX = -panRangePx * eased

                val matrix = android.graphics.Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(translateX, translateY)
                wallpaperImageView.scaleType = ImageView.ScaleType.MATRIX
                wallpaperImageView.imageMatrix = matrix

                if (frameCount % 30 == 0) {
                    Log.d("WPSLIDE", "elapsedMs=$elapsed legProgress=$legProgress eased=$eased translateX=$translateX")
                }
                frameCount++
                wallpaperImageView.postOnAnimation(this)
            }
        }
        wallpaperSlideRunnable = runnable
        wallpaperImageView.postOnAnimation(runnable)
    }

    /**
     * Eases progress t in [0,1] -> [0,1] with smooth (cosine) acceleration over the first [ramp]
     * fraction, a constant-speed glide through the middle, and symmetric deceleration over the
     * last [ramp] fraction. Velocity starts and ends at zero. ramp=0.5 eases the whole leg;
     * smaller values make the ease shorter (0.25 = half as long, longer constant-speed middle).
     */
    private fun easeWithRamp(t: Float, ramp: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val r = ramp.coerceIn(0.001f, 0.5f)
        val vMax = 1f / (1f - r) // peak speed so total travel is exactly 1
        val pi = Math.PI.toFloat()
        return when {
            x < r -> vMax / 2f * (x - (r / pi) * kotlin.math.sin(pi * x / r))
            x <= 1f - r -> vMax * r / 2f + vMax * (x - r)
            else -> {
                val s = 1f - x
                1f - vMax / 2f * (s - (r / pi) * kotlin.math.sin(pi * s / r))
            }
        }
    }

    /**
     * Stops the wallpaper slide animation and restores the saved (manual) focus X offset.
     */
    private fun stopWallpaperSlide() {
        val running = wallpaperSlideRunnable
        wallpaperSlideRunnable = null
        val wallpaperImageView = getWallpaperImageView() ?: return
        running?.let { wallpaperImageView.removeCallbacks(it) }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val focusX = prefs.getSafeFloat(getCurrentThemeWallpaperFocusXKey(), 0.5f)
        applyWallpaperFocusXToImageView(wallpaperImageView, focusX)
    }

    private fun applyWallpaperDrawable(drawable: Drawable, uri: Uri? = null) {
        if (themeManager.isWindowsPhone81()) return

        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)

        // Create or find existing wallpaper ImageView
        var wallpaperImageView = mainBackground.findViewWithTag<ImageView>("wallpaper")

        if (wallpaperImageView == null) {
            // Create new ImageView for wallpaper
            wallpaperImageView = ImageView(this)
            wallpaperImageView.tag = "wallpaper"
            wallpaperImageView.scaleType = ImageView.ScaleType.MATRIX
            wallpaperImageView.adjustViewBounds = false

            // The wallpaper uses a MATRIX scale type whose crop/pan is computed from the view's
            // size. When that size changes (e.g. an orientation change), recompute the matrix so
            // the wallpaper re-fits the new dimensions instead of keeping a stale transform.
            wallpaperImageView.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
                if (sizeChanged) {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_SLIDE_WALLPAPER_ENABLED, false)) {
                        // Restart the slide so it recomputes its pan range for the new size.
                        startWallpaperSlideIfEnabled()
                    } else {
                        val fx = prefs.getSafeFloat(getCurrentThemeWallpaperFocusXKey(), 0.5f)
                        applyWallpaperFocusXToImageView(view as ImageView, fx)
                    }
                }
            }

            // Add as first child (behind everything else)
            val layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            mainBackground.addView(wallpaperImageView, 0, layoutParams)
        }

        // Set the wallpaper image
        wallpaperImageView.setImageDrawable(drawable)

        // Apply the saved horizontal focus offset for the current theme
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val focusX = prefs.getSafeFloat(getCurrentThemeWallpaperFocusXKey(), 0.5f)
        applyWallpaperFocusXToImageView(wallpaperImageView, focusX)

        // Resume sliding the wallpaper if the setting is enabled (new ImageView/drawable).
        startWallpaperSlideIfEnabled()

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
//                    val intent = Intent(Intent.ACTION_VIEW)
//                    intent.data = link.toUri()
//                    startActivity(intent)
//                    playClickSound()
                    openUrlShortcut(link)
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

        // Set up AQI click
        val aqiText = findViewById<TextView>(R.id.aqi_text)
        aqiText?.setOnClickListener {
            handleAqiTap()
        }

        // Long press to refresh AQI data
        aqiText?.setOnLongClickListener {
            refreshAqiData()
            true
        }

        // Apply AQI visibility for the (re-inflated) taskbar so a disabled
        // indicator never lingers as "?" after a theme change.
        initializeAqiDisplay()

        // Initialize volume icon state
        updateVolumeIcon()

        // Set up gesture bar toggle functionality
        setupGestureBarToggle()
    }

    private fun playStartupSound() {

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Plus! 95 theme overrides the Classic startup sound
        val plus95 = themeManager.getActivePlus95()
        if (plus95 != null && plus95.startupAsset != null) {
            if (playPlus95StartupSound(plus95.slug, plus95.startupAsset)) return
        }

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

    /**
     * Strong reference to the currently-playing startup jingle. Required: several Plus! 98
     * themes ship 10–12s startup sounds, and without a field holding the MediaPlayer the GC
     * finalizes the local instance mid-playback and the clip cuts off partway through. (Older
     * themes' ~3s clips finished before a GC ever ran, which is why only the new ones cut off.)
     */
    private var plus95StartupPlayer: android.media.MediaPlayer? = null

    private fun playPlus95StartupSound(slug: String, startupAsset: String): Boolean {
        if (isSoundMuted()) return true
        return try {
            // Stop any startup jingle still playing from a previous apply
            plus95StartupPlayer?.release()
            plus95StartupPlayer = null
            val afd = assets.openFd(themeManager.plus95Path(slug, startupAsset))
            val mp = android.media.MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener {
                it.release()
                if (plus95StartupPlayer === it) plus95StartupPlayer = null
            }
            mp.prepare()
            mp.start()
            // Keep a strong reference for the whole clip so the GC can't finalize it early.
            plus95StartupPlayer = mp
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Plus! startup sound failed for $slug/$startupAsset", e)
            false
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
        // The Windows Phone 8.1 shell draws its own flat background; there is no desktop
        // to paper. Loading one anyway would put a full-bleed ImageView into
        // main_background that the shell then has to fight for z-order.
        if (themeManager.isWindowsPhone81()) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

        // A Plus! theme's bundled wallpaper is the source of truth for the Classic desktop.
        // Apply it up front so that switching the base theme to Classic (which recreates the
        // activity and lands here) shows the theme's own wallpaper immediately, instead of the
        // previously-saved Classic wallpaper until the theme is manually re-applied. A user
        // picked custom image (uriKey) still takes precedence if one has been set.
        val plus95 = themeManager.getActivePlus95()
        if (plus95 != null && prefs.getString(uriKey, null) == null) {
            val plusWall = plus95WallpaperPath(plus95.slug)
            if (plusWall != null) {
                prefs.edit { putString(pathKey, plusWall) }
                applyCustomWallpaperFromAssets(plusWall)
                return
            }
        }

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

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= reqWidth &&
               bounds.outHeight / (sampleSize * 2) >= reqHeight) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun applyCustomWallpaperFromUri(uri: Uri) {
        try {
            val metrics = resources.displayMetrics
            val targetW = metrics.widthPixels
            val targetH = metrics.heightPixels
            val decoded = decodeSampledBitmapFromUri(uri, targetW, targetH)
            if (decoded != null) {
                // Cap final bitmap to fit within screen bounds (preserve aspect ratio).
                // Sampling alone can leave the bitmap up to 2x the target; this keeps
                // memory well under Canvas's hardware-bitmap limit.
                val scale = minOf(
                    targetW.toFloat() / decoded.width,
                    targetH.toFloat() / decoded.height,
                    1f
                )
                val bitmap = if (scale < 1f) {
                    val scaled = Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled !== decoded) decoded.recycle()
                    scaled
                } else decoded
                applyWallpaperDrawable(bitmap.toDrawable(resources))
                Log.d("MainActivity", "Applied custom wallpaper from URI: $uri")
            } else {
                throw Exception("Failed to decode bitmap from URI")
            }
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
            // Extend behind system bars but keep them visible
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Don't hide the system bars, just allow content to draw behind them.
            // Keep the navigation bar transparent (and disable the system's translucent
            // scrim) so the black backdrop we draw behind it shows cleanly. This matters for
            // button/3-button navigation, where setupNavigationBarInsets() pads the content up
            // to the bar and the space behind the buttons is filled by root_container's black.
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error enabling edge-to-edge", e)
        }
    }

    /**
     * Inset the entire launcher so it stops at the system navigation bar instead of drawing
     * behind it. This matters for button/3-button (and 2-button) navigation, whose bar is
     * either a tall strip at the bottom (portrait) or a strip down one side (landscape).
     *
     * The base layout already reserves a 30dp strip at the bottom (the gesture bar) for the
     * navigation area, so we only pad the root by the *extra* nav bar height beyond that: with
     * gesture navigation the bottom inset is <= 30dp, so nothing changes; with a taller button
     * nav bar the content is lifted to clear it. Left/right insets (a side nav bar in landscape)
     * are applied in full. The space freed up behind the bar is filled by root_container's black
     * background, matching the app's black gesture-bar look.
     */
    private fun setupNavigationBarInsets() {
        val root = findViewById<View>(R.id.root_container)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())

            // The desktop themes leave the top alone - nothing sits up there but wallpaper -
            // and already reserve 30dp at the bottom via the gesture bar / taskbar margins.
            //
            // The WP8.1 shell has neither: its status bar sits hard against the top edge and
            // its navigation bar against the bottom. Padding root_container (rather than the
            // shell itself) means floating windows, which are siblings of the shell, clear
            // the system bars too.
            val isPhoneShell = themeManager.isWindowsPhone81()
            val reservedBottomPx =
                if (isPhoneShell) 0
                else (30 * resources.displayMetrics.density).toInt()
            val padLeft = navBars.left
            val padRight = navBars.right
            val padBottom = maxOf(0, navBars.bottom - reservedBottomPx)
            val padTop = if (isPhoneShell) statusBars.top else 0

            if (view.paddingLeft != padLeft || view.paddingRight != padRight ||
                view.paddingBottom != padBottom || view.paddingTop != padTop) {
                view.setPadding(padLeft, padTop, padRight, padBottom)

                // The usable desktop area changed (narrower in landscape, shorter in portrait),
                // so re-place the icons for the new bounds — same reflow used on rotation.
                if (::desktopContainer.isInitialized) {
                    desktopContainer.post {
                        positionIconsFromGridIndices()
                        reflowIconsWithoutPosition()
                        refreshDesktopIcons()
                    }
                }
            }

            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    fun playClickSound() {
        val plus95 = themeManager.getActivePlus95()
        if (plus95 != null && plus95.soundAsset != null) {
            playPlus95ClickSound(plus95.slug, plus95.soundAsset)
            return
        }
        playSound(R.raw.click)
    }

    // MediaPlayer pool for Plus! click sound (kept short, reused across taps)
    private var plus95ClickPlayer: android.media.MediaPlayer? = null
    private var plus95ClickPlayerKey: String? = null

    private fun playPlus95ClickSound(slug: String, soundAsset: String) {
        if (isSoundMuted()) return
        val key = "$slug/$soundAsset"
        try {
            if (plus95ClickPlayerKey != key) {
                plus95ClickPlayer?.release()
                val afd = assets.openFd(themeManager.plus95Path(slug, soundAsset))
                plus95ClickPlayer = android.media.MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
                afd.close()
                plus95ClickPlayerKey = key
            }
            plus95ClickPlayer?.let {
                if (it.isPlaying) {
                    it.seekTo(0)
                } else {
                    it.start()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Plus! click sound failed for $key, falling back", e)
            playSound(R.raw.click)
        }
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

    private fun addDesktopIcon(appInfo: AppInfo, x: Float = 100f, y: Float = 100f, iconTypeOverride: IconType? = null, targetUrl: String? = null) {

        // Use custom icon if available, otherwise use app icon
        val iconToUse = getAppIcon(appInfo.packageName) ?: appInfo.icon

        // Convert x/y to grid index for current orientation
        val currentOrientation = getCurrentOrientation()
        val gridIndex = if (desktopContainer.width > 0) {
            convertXYToGridIndex(x, y, currentOrientation)
        } else {
            null // Will be assigned during migration/reflow
        }

        // Determine icon type based on package name
        val iconType = iconTypeOverride ?: when (appInfo.packageName) {
            "recycle.bin" -> IconType.RECYCLE_BIN
            "my.computer" -> IconType.MY_COMPUTER
            else -> IconType.APP
        }

        val desktopIcon = DesktopIcon(
            name = appInfo.name,
            packageName = appInfo.packageName,
            icon = iconToUse,
            x = x,
            y = y,
            type = iconType,
            portraitGridIndex = if (currentOrientation == ScreenOrientation.PORTRAIT) gridIndex else null,
            landscapeGridIndex = if (currentOrientation == ScreenOrientation.LANDSCAPE) gridIndex else null,
            targetUrl = targetUrl
        )

        desktopIcons.add(desktopIcon)
        Log.d("MainActivity", "Added desktop icon ${appInfo.name} with grid index $gridIndex for $currentOrientation")
        
        // Create appropriate icon view (RecycleBinView for recycle bin, MyComputerView for my computer, DesktopIconView for others)
        val iconView = when (appInfo.packageName) {
            "recycle.bin" -> {
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
            }
            "my.computer" -> {
                rocks.gorjan.gokixp.apps.explorer.MyComputerView(this).apply {
                    setDesktopIcon(desktopIcon)
                    val isClassic = themeManager.getSelectedTheme() is AppTheme.WindowsClassic
                    setThemeFont(isClassic)
                    setThemeIcon(isClassic)
                }
            }
            else -> {
                DesktopIconView(this).apply {
                    setDesktopIcon(desktopIcon)
                    setThemeFont(themeManager.getSelectedTheme() is AppTheme.WindowsClassic)
                }
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

    // ===== URL shortcuts (shared via the Android share sheet) =====

    /**
     * Handles an incoming ACTION_SEND intent carrying shared text/URL.
     * Called from onCreate (cold start) and onNewIntent (warm start).
     */
    /**
     * A link tapped somewhere else on the phone, handed to the launcher to open.
     *
     * Straight into the launcher's own browser rather than through [openUrlShortcut]: that
     * one asks whether links should go to Internet Explorer or to the phone's default
     * browser, and neither question applies here - the phone has already decided the
     * launcher *is* the browser, and sending the link back out to be resolved would hand it
     * to whatever answers next, or to this activity a second time.
     *
     * The data is cleared once it has been read so a rotation or a return to the launcher
     * does not open the same page again - the same reason [handleSharedUrlIntent] clears
     * its extra - and delayed for the same reason as well: on a cold start the window
     * manager and the shell are not up yet, and a browser opened into a launcher that has
     * not finished being built is a window with nowhere to go.
     */
    private fun handleViewUrlIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val scheme = data.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return

        val url = data.toString()
        intent.data = null

        Handler(Looper.getMainLooper()).postDelayed({
            showInternetExplorerDialog(url)
        }, 500)
    }

    /**
     * Whether the phone sends its links here.
     *
     * Asked of the system rather than kept as a setting of our own: the user can change
     * this from Android's own screens at any time, and a checkbox in Display Properties
     * remembering an answer the system has since overruled is a setting that lies.
     */
    private fun isDefaultBrowser(): Boolean = try {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        roles?.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) == true
    } catch (e: Exception) {
        Log.w("MainActivity", "Could not ask about the browser role", e)
        false
    }

    /**
     * Asks to be made the phone's browser.
     *
     * Android puts the choice to the user itself - it is not ours to make - so this raises
     * the system's own request where there is one to raise, and drops the user at the
     * default-apps screen where there is not: either the role is already held, in which
     * case the request would be refused outright and that screen is where it can be given
     * away again, or the phone has no such role and the list is all there is.
     */
    private fun requestDefaultBrowser() {
        try {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null &&
                roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) &&
                !roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
            ) {
                defaultBrowserLauncher.launch(
                    roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)
                )
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask for the browser role", e)
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            Log.e("MainActivity", "No default-apps screen on this phone", e)
            showNotification("Default browser", "This phone has no default apps screen")
        }
    }

    private fun handleSharedUrlIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(sharedText)
        if (url == null) {
            Log.w("MainActivity", "Shared text contained no usable URL: $sharedText")
            return
        }

        // Clear the extra so the same URL isn't handled again on rotation / re-entry
        intent.removeExtra(Intent.EXTRA_TEXT)

        // Delay so the UI (window manager + desktop container) is ready, mirroring
        // handlePendingPackageAction() which does the same for cold-start actions.
        Handler(Looper.getMainLooper()).postDelayed({
            promptCreateUrlShortcut(url)
        }, 500)
    }

    /**
     * Pulls the first web URL out of shared text (browsers sometimes prepend a title)
     * and ensures it has a scheme. Returns null if there's nothing usable.
     */
    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val matcher = android.util.Patterns.WEB_URL.matcher(trimmed)
        val candidate = if (matcher.find()) {
            trimmed.substring(matcher.start(), matcher.end())
        } else {
            trimmed
        }

        return if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)) {
            candidate
        } else {
            "https://$candidate"
        }
    }

    /** Suggests a friendly default name (the host) for a URL shortcut. */
    private fun suggestShortcutName(url: String): String {
        return try {
            val host = android.net.Uri.parse(url).host
            if (host.isNullOrEmpty()) url else host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }
    }

    /** Prompts the user for a name (reusing the rename dialog), then creates the shortcut. */
    private fun promptCreateUrlShortcut(url: String) {
        showRenameDialog(
            title = "Add to Desktop",
            initialText = suggestShortcutName(url),
            hint = "Shortcut name"
        ) { name ->
            createUrlShortcutOnDesktop(name, url)
        }
    }

    /** Creates a URL shortcut desktop icon and places it in the first free grid slot. */
    private fun createUrlShortcutOnDesktop(name: String, url: String) {
        val urlIcon = AppCompatResources.getDrawable(this, R.drawable.url_shortcut)!!
        // Unique, stable packageName (like folders) so rename/custom-icon mappings key off it
        val packageName = "url_${System.currentTimeMillis()}"
        val appInfo = AppInfo(name = name, packageName = packageName, icon = urlIcon)

        val firstAvailablePosition = findFirstAvailableGridSlot()
        if (firstAvailablePosition != null) {
            val (newX, newY) = getGridCoordinates(firstAvailablePosition.first, firstAvailablePosition.second)
            addDesktopIcon(appInfo, newX, newY, IconType.URL_SHORTCUT, url)
        } else {
            addDesktopIcon(appInfo, 100f, 100f, IconType.URL_SHORTCUT, url)
        }

        showNotification("Shortcut Added", "\"$name\" was added to your desktop")
    }

    /**
     * Opens a URL shortcut's target. Honors the "open in Internet Explorer" setting:
     * when enabled, opens in the built-in IE window; otherwise (the default) opens in
     * the system default browser.
     */
    /**
     * Opens a link, wherever the user has said links should open.
     *
     * The one way in for every address this launcher follows - a shortcut tile, a story in
     * the news reader, the update, a link in Welcome - rather than each of them deciding
     * for itself. Internet Explorer is a program here, and opening one to follow a link
     * meant leaving the user inside a toy browser they did not ask for; it is offered
     * behind a setting instead, and used as the fallback when nothing on the phone will
     * take an http intent at all.
     */
    fun openUrlShortcut(url: String?) {
        val target = url?.trim()
        if (target.isNullOrEmpty()) {
            Log.w("MainActivity", "URL shortcut has no target URL")
            return
        }

        // Ours to open, either because the user asked for that or because the phone has made
        // the launcher its browser - in which case handing the link to the system would
        // only bring it straight back here through the front door.
        if (isOpenUrlsInIeEnabled() || isDefaultBrowser()) {
            showInternetExplorerDialog(target)
            return
        }

        // Default: open in the system default browser
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening URL in default browser: $target", e)
            // Fall back to the built-in browser if no external handler is available
            showInternetExplorerDialog(target)
        }
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
                "landscapeGridIndex" to icon.landscapeGridIndex,
                "targetUrl" to icon.targetUrl,
                "tileSize" to icon.tileSize,
                "tileIndex" to icon.tileIndex,
                "tileSizeLandscape" to icon.tileSizeLandscape,
                "tileIndexLandscape" to icon.tileIndexLandscape
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
                // The player is called Music, as the phone called it, and was called Zune
                // for a while. Only the untouched ones: a name the user typed themselves
                // is theirs to keep.
                val name = (iconData["name"] as String)
                    .let { if (packageName == "system.zune" && it == "Zune") "Music" else it }
                val x = (iconData["x"] as Double).toFloat()
                val y = (iconData["y"] as Double).toFloat()
                val id = iconData["id"] as String
                val parentFolderId = iconData["parentFolderId"] as? String
                val typeStr = iconData["type"] as? String
                val targetUrl = iconData["targetUrl"] as? String

                // Read grid indices (may be null for old data)
                val portraitGridIndex = (iconData["portraitGridIndex"] as? Double)?.toInt()
                val landscapeGridIndex = (iconData["landscapeGridIndex"] as? Double)?.toInt()

                // Windows Phone 8.1 tile placement; absent for icons saved before it existed.
                val tileSize = iconData["tileSize"] as? String
                val tileIndex = (iconData["tileIndex"] as? Double)?.toInt()
                val tileSizeLandscape = iconData["tileSizeLandscape"] as? String
                val tileIndexLandscape = (iconData["tileIndexLandscape"] as? Double)?.toInt()


                val iconType = if (typeStr != null) {
                    try {
                        IconType.valueOf(typeStr)
                    } catch (e: Exception) {
                        when (packageName) {
                            "recycle.bin" -> IconType.RECYCLE_BIN
                            "my.computer" -> IconType.MY_COMPUTER
                            else -> IconType.APP
                        }
                    }
                } else {
                    when (packageName) {
                        "recycle.bin" -> IconType.RECYCLE_BIN
                        "my.computer" -> IconType.MY_COMPUTER
                        else -> IconType.APP
                    }
                }

                try {
                    val icon = when (iconType) {
                        IconType.RECYCLE_BIN -> {
                            // Special case for recycle bin - use recycle drawable
                            AppCompatResources.getDrawable(this, R.drawable.recycle)!!
                        }
                        IconType.MY_COMPUTER -> {
                            // Special case for My Computer - use theme-appropriate icon
                            AppCompatResources.getDrawable(this, themeManager.getMyComputerIcon())!!
                        }
                        IconType.FOLDER -> {
                            // Use custom icon if available, otherwise use theme-appropriate folder icon
                            getAppIcon(packageName) ?: run {
                                // Chrome string: Windows Phone 8.1 needs the Vista folder art.
                                val selectedTheme = themeManager.chromeThemeString()
                                AppCompatResources.getDrawable(this, if (selectedTheme == "Windows Classic") R.drawable.folder_98 else if (selectedTheme == "Windows Vista") R.drawable.folder_vista else R.drawable.folder_xp)!!
                            }
                        }
                        IconType.URL_SHORTCUT -> {
                            // URL shortcut: use custom icon if set, otherwise the URL icon
                            getAppIcon(packageName) ?: AppCompatResources.getDrawable(this, R.drawable.url_shortcut)!!
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

                    val desktopIcon = DesktopIcon(
                        name, packageName, icon, x, y, id, iconType, parentFolderId,
                        portraitGridIndex, landscapeGridIndex, targetUrl,
                        tileSize, tileIndex, tileSizeLandscape, tileIndexLandscape)
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
                        IconType.MY_COMPUTER -> {
                            rocks.gorjan.gokixp.apps.explorer.MyComputerView(this).apply {
                                setDesktopIcon(desktopIcon)
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
                        IconType.URL_SHORTCUT -> {
                            DesktopIconView(this).apply { setDesktopIcon(desktopIcon) }
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

        // Ensure My Computer exists as desktop icon
        ensureMyComputerExists()

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
            val iconResource = if (themeManager.getSelectedTheme() is AppTheme.WindowsClassic) {
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

    private fun ensureMyComputerExists() {
        // Check if My Computer already exists in desktop icons
        val myComputerExists = desktopIcons.any { it.packageName == "my.computer" }

        if (!myComputerExists) {
            // Create My Computer as a desktop icon with theme-appropriate icon
            val myComputerDrawable = AppCompatResources.getDrawable(this, themeManager.getMyComputerIcon())!!
            val myComputerAppInfo = AppInfo(
                name = "My Computer",
                packageName = "my.computer",
                icon = myComputerDrawable
            )

            // Set default position (top-left, below Recycle Bin)
            val defaultX = 50f
            val defaultY = 200f

            addDesktopIcon(myComputerAppInfo, defaultX, defaultY)
            saveDesktopIcons() // Save immediately
        }

        // Update myComputer reference to point to the MyComputerView (after all icons are loaded)
        Handler(Looper.getMainLooper()).post { updateMyComputerReference() }

        Log.d("MainActivity", "My Computer ensured in desktop icons")
    }

    private fun updateMyComputerReference() {
        // Find the MyComputerView in desktopIconViews
        myComputer = desktopIconViews.find {
            it is rocks.gorjan.gokixp.apps.explorer.MyComputerView
        } as? rocks.gorjan.gokixp.apps.explorer.MyComputerView
        // Restore visibility state - if hidden, remove from desktop
        if (!isMyComputerVisible()) {
            hideMyComputer()
        }
    }

    fun openMyComputer(myComputerView: rocks.gorjan.gokixp.apps.explorer.MyComputerView) {
        val desktopIcon = myComputerView.getDesktopIcon() ?: return

        // Check and request storage permissions
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        // Check if window is already open
        val windowId = "mycomputer:${desktopIcon.id}"
        if (floatingWindowManager.findAndFocusWindow(windowId)) {
            // Window already open, reset clipboard
            val existingWindow = floatingWindowManager.findWindowByIdentifier(windowId)
            (existingWindow?.myComputerApp as? rocks.gorjan.gokixp.apps.explorer.MyComputerApp)?.resetClipboard()
            return
        }

        // Create Windows dialog
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = windowId
        windowsDialog.setTitle("My Computer")
        windowsDialog.setTaskbarIcon(themeManager.getMyComputerIcon())

        // Inflate layout based on current theme (reuse Windows Explorer layouts)
        val explorerLayoutRes = themeManager.getWindowsExplorerLayoutRes(themeManager.getSelectedTheme())
        val contentView = layoutInflater.inflate(explorerLayoutRes, null)

        // Create MyComputerApp instance
        val myComputerApp = rocks.gorjan.gokixp.apps.explorer.MyComputerApp(
            context = this,
            theme = themeManager.getSelectedTheme(),
            themeManager = themeManager,
            onSoundPlay = { soundType ->
                when (soundType) {
                    "click" -> playClickSound()
                    else -> playClickSound()
                }
            },
            onUpdateWindowTitle = { title ->
                windowsDialog.setTitle(title)
            },
            onSetCursorBusy = {
                setCursorBusy()
            },
            onSetCursorNormal = {
                setCursorNormal()
            },
            onShowDialog = { dialogType, message ->
                showDialogBox(dialogType, message)
            },
            onShowContextMenu = { items, x, y ->
                if (::contextMenu.isInitialized) {
                    contextMenu.showMenu(items, x, y)
                }
            },
            onShowRenameDialog = { file, onRename ->
                showFileRenameDialog(file, onRename)
            },
            onShowConfirmDialog = { title, message, onConfirm ->
                showConfirmDialog(title, message, onConfirm)
            },
            onLaunchSystemApp = { packageName ->
                launchSystemApp(packageName)
            },
            getSystemAppIcon = { packageName ->
                getSystemAppIconDrawable(packageName)
            },
            getSystemAppsList = {
                getSystemAppsList().map { Pair(it.exeName, it.packageName) }
            }
        )

        // Setup the app
        myComputerApp.setupApp(contentView)

        // Setup context menu callback to clear selection when menu is hidden
        if (::contextMenu.isInitialized) {
            myComputerApp.setupContextMenuCallback(contextMenu)
        }

        // Store reference to MyComputerApp instance
        windowsDialog.myComputerApp = myComputerApp

        // Set content and show window
        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSizePercentage(90f, 30f)
        windowsDialog.setMaximizable(true)
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    /**
     * Show a system dialog box (Information, Warning, or Error)
     */
    fun showDialogBox(dialogType: rocks.gorjan.gokixp.apps.dialogbox.DialogType, message: String) {
        // Create Windows dialog
        val windowsDialog = createThemedWindowsDialog()

        // Inflate layout
        val contentView = layoutInflater.inflate(R.layout.program_dialog_box, null)

        // Create DialogBoxApp instance
        val dialogBoxApp = rocks.gorjan.gokixp.apps.dialogbox.DialogBoxApp(
            context = this,
            theme = themeManager.getSelectedTheme(),
            themeManager = themeManager,
            dialogType = dialogType,
            message = message,
            onClose = {
                floatingWindowManager.removeWindow(windowsDialog)
            },
            onPlaySound = { soundResId ->
                playSound(soundResId)
            }
        )

        // Setup the dialog
        dialogBoxApp.setupDialog(contentView)

        // Set window properties
        windowsDialog.setTitle(dialogBoxApp.getTitle())
        windowsDialog.setTaskbarIcon(dialogBoxApp.getIconResId())
        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(260)
        windowsDialog.setMinimizable(false)
        windowsDialog.setContextMenuView(contextMenu)

        // Show the dialog
        floatingWindowManager.showWindow(windowsDialog)
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

    private fun getHiddenApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return try {
            val hiddenAppsString = prefs.getString(KEY_HIDDEN_APPS, "") ?: ""
            if (hiddenAppsString.isEmpty()) emptySet()
            else hiddenAppsString.split(",").filter { it.isNotEmpty() }.toSet()
        } catch (e: ClassCastException) {
            Log.w("MainActivity", "Unexpected hidden apps format, ignoring", e)
            emptySet()
        }
    }

    private fun toggleHiddenApp(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hidden = getHiddenApps().toMutableSet()

        if (!hidden.remove(packageName)) {
            hidden.add(packageName)
            Log.d("MainActivity", "Hid app: $packageName")
        } else {
            Log.d("MainActivity", "Unhid app: $packageName")
        }

        prefs.edit { putString(KEY_HIDDEN_APPS, hidden.joinToString(",")) }
    }

    private fun isAppHidden(packageName: String): Boolean {
        return getHiddenApps().contains(packageName)
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
    
    // "statusbar" is not in getSystemService's @ServiceName allow-list because it is not
    // reachable by normal apps; every call below is best-effort and guarded by try/catch.
    @SuppressLint("WrongConstant")
    fun expandNotificationShade() {
        Log.d("MainActivity", "🔥 expandNotificationShade() called")
        
        try {
            // Method 1: Try the standard StatusBarManager approach
            Log.d("MainActivity", "Trying StatusBarManager approach...")
            val statusContext =
                attributionContext("system")
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
                attributionContext("system")
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
                attributionContext("system")
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
    
    /**
     * Searches the web for [query], preferring the Google app.
     *
     * Three attempts, narrowing as they go: Google's own app with the query already run,
     * then whatever app claims a web search, then the query as a Google URL for the
     * browser to open. A phone with no Google app and no browser is not one this can help.
     */
    private fun searchTheWebFor(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return

        val google = "com.google.android.googlequicksearchbox"
        try {
            val inGoogle = Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage(google)
                putExtra(SearchManager.QUERY, term)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            startActivity(inGoogle)
            Log.d("MainActivity", "Searched Google for '$term'")
            return
        } catch (e: Exception) {
            Log.d("MainActivity", "Google app would not take the search: ${e.message}")
        }

        try {
            val anySearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, term)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            if (anySearch.resolveActivity(packageManager) != null) {
                startActivity(anySearch)
                return
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "No app claims web search: ${e.message}")
        }

        try {
            val url = "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(term, "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Nothing on this phone can search the web", e)
            showNotification("Search", "No browser to search with")
        }
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
            // WP8.1 shell, but only when nothing is open on top of it. A window on screen
            // owns back before the shell does.
            floatingWindowManager.getFrontVisibleWindow() == null &&
                wp81Shell?.handleBack() == true -> {
                Log.d("MainActivity", "Back pressed (legacy): handled by WP8.1 shell")
            }
            isStartMenuVisible -> {
                // If start menu is open, close it
                Log.d("MainActivity", "Back pressed: closing start menu")
                hideStartMenu()
            }
            floatingWindowManager.getFrontVisibleWindow() != null -> {
                val frontWindow = floatingWindowManager.getFrontVisibleWindow()

                // The phone's browser, as above.
                val metroIE =
                    if (frontWindow?.windowIdentifier == "system.internet_explorer")
                        metroIEAppInstance
                    else null

                // Check if front window is Internet Explorer with navigation history
                val ieApp = frontWindow?.internetExplorerApp as? InternetExplorerApp
                if (metroIE != null && metroIE.handleBack()) {
                    Log.d("MainActivity", "Back pressed (legacy): handled by IE (phone)")
                } else if (ieApp != null && ieApp.canNavigateBack()) {
                    // Navigate back in browser history
                    Log.d("MainActivity", "Back pressed: navigating back in IE")
                    ieApp.navigateBack()
                } else {
                    // Check if front window is My Computer with navigation history
                    val mcApp = frontWindow?.myComputerApp as? rocks.gorjan.gokixp.apps.explorer.MyComputerApp
                    if (mcApp != null && mcApp.canNavigateBack()) {
                        // Navigate back in folder history
                        Log.d("MainActivity", "Back pressed: navigating back in My Computer")
                        mcApp.navigateBackPublic()
                    } else if (frontWindow?.windowIdentifier == "system.notepad" &&
                        metroNotepadAppInstance?.handleBack() == true
                    ) {
                        // A menu, a rename or the note itself was open over the list.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Notepad")
                    } else if (frontWindow?.windowIdentifier == "system.minesweeper" &&
                        metroMinesweeperInstance?.handleBack() == true
                    ) {
                        // The strip's own command list was open over the field.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Minesweeper")
                    } else if (frontWindow?.windowIdentifier == "system.solitare" &&
                        metroSolitaireInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Solitaire")
                    } else if (frontWindow?.windowIdentifier == "system.zune" &&
                        zuneAppInstance?.handleBack() == true
                    ) {
                        // Something was open over the player - a record, a sheet, the
                        // queue. That is what back closes first.
                        Log.d("MainActivity", "Back pressed: handled by Zune")
                    } else if (frontWindow?.windowIdentifier == "system.zune") {
                        // Put Zune away rather than shutting it down; it keeps playing.
                        Log.d("MainActivity", "Back pressed: minimising Zune")
                        frontWindow.minimize()
                    } else {
                        // Close the front-most window
                        Log.d("MainActivity", "Back pressed: closing front window")
                        floatingWindowManager.closeFrontWindow()
                    }
                }
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

        // Stop jingle bells music when app loses focus
        stopJingleBellsMusic()

        // Pause snowfall and save state
        snowfallManager?.pause()

        // Stop sliding the wallpaper while backgrounded (restarts on return via reloadWallpaperBitmap)
        wallpaperSlideRunnable?.let { getWallpaperImageView()?.removeCallbacks(it) }
        wallpaperSlideRunnable = null

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

        // Resume snowfall animation
        snowfallManager?.resume()

        // Resume the wallpaper slide from where it stopped. Covered here (not just onStart) so it
        // also restarts when the activity stays visible (e.g. tapping Home while already on the
        // launcher), where onStop/onStart don't fire.
        startWallpaperSlideIfEnabled()

        // Update permission error visibility when returning from settings
        updateEmailPermissionError?.invoke()
        updateNotificationDotsPermissionError?.invoke()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        val newOrientation = getCurrentOrientation()
        Log.d("MainActivity", "Configuration changed to: $newOrientation")

        // The phone shell keeps a wall for each way up - see wp81Landscape - so turning it
        // over is not a re-layout of the same wall but a different one, read from the
        // arrangement that was made in this orientation. Rebuilt rather than repacked: the
        // sizes are the other wall's too, and which of them a tile is wearing is part of
        // what the user arranged.
        if (wp81Shell != null) {
            wp81Shell?.startScreen?.columns = themeManager.getWP81Columns()
            refreshWP81Tiles()
        }

        // The wallpaper and any open floating windows re-fit themselves via OnLayoutChangeListeners
        // (see applyWallpaperDrawable and WindowsDialog.setupDialogLayout) once their views are
        // re-laid-out for the new size, so neither needs handling here.

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

        // A folder on the phone shell is opened into the wall to get at what is inside it,
        // so opening one of those is the end of what the folder was for. Left standing, it
        // is what the user comes home to: a wall still parted around a folder they finished
        // with a moment ago, with the tiles they actually arranged pushed a row down.
        // Closed here rather than at the launch, so that going out to an app and coming
        // straight back is the one gesture that does it, and closed without animation
        // because there is nothing on screen to watch it.
        wp81Shell?.startScreen?.closeFolder(animated = false)

        // The app list is put back to rest on the same terms: whatever was searched for is
        // cleared and the rows are back at the top, ready for the next time it is opened.
        // Here rather than at the tap that launched the app, so none of it happens in front
        // of the user - see the app list's onLaunch.
        wp81Shell?.appList?.let {
            it.endSearch()
            it.scrollToTop()
        }

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
            resetWP81ToStart()
        }
        
        // Update intent for activity
        setIntent(intent)

        // Handle a URL shared into the launcher while it's already running (the common path)
        handleSharedUrlIntent(intent)
        handleViewUrlIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)

        // Windows Phone 8.1 shell: the live-tile flip is a repeating post and would
        // otherwise outlive the activity.
        stopWP81LiveTiles()
        floatingWindowManager.onWindowCountChanged = null
        wp81Shell = null

        // Release Plus! 95 click sound MediaPlayer
        plus95ClickPlayer?.release()
        plus95ClickPlayer = null
        plus95ClickPlayerKey = null

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

        // Clean up Christmas lights
        cleanupChristmasLights()

        // Clean up SoundPool
        if (::soundPool.isInitialized) {
            soundPool.release()
            Log.d("MainActivity", "SoundPool released")
        }

        // Clean up app install receiver listener
        AppInstallReceiver.setListener(null)
        unregisterLauncherAppsCallback()
        iconRefreshRunnable?.let { handler.removeCallbacks(it) }
        iconRefreshRunnable = null

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

    private fun initializeAqiDisplay() {
        // The taskbar AQI indicator is opt-in (Settings > "Show air quality").
        val aqiContainer = findViewById<LinearLayout>(R.id.aqi_container)
        if (!isShowAqiEnabled()) {
            aqiContainer?.visibility = View.GONE
            return
        }
        aqiContainer?.visibility = View.VISIBLE

        // Display cached AQI if available (click handlers are in initializeTaskbarElements)
        val cachedAqi = getCachedAqi()
        if (cachedAqi != null && isAqiDataFresh(90)) {
            updateAqiDisplay(cachedAqi)
        }
    }

    private fun handleAqiTap() {
        val aqiAppPackage = "com.gorjan.airquality"
        try {
            val intent = packageManager.getLaunchIntentForPackage(aqiAppPackage)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                startActivity(intent)
                Log.d("MainActivity", "Launched AQI app: $aqiAppPackage")
            } else {
                // App not installed, open Play Store
                openPlayStoreForAqiApp(aqiAppPackage)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error launching AQI app", e)
            openPlayStoreForAqiApp(aqiAppPackage)
        }
    }

    private fun openPlayStoreForAqiApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            // Play Store not available, open in browser
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }

    private fun refreshAqiData() {
        Log.d("MainActivity", "Refreshing AQI data with fresh GPS location...")
        val locationContext = attributionContext("aqi")
        val locationManager = locationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        Log.d("MainActivity", "Got fresh location for AQI: ${location.latitude}, ${location.longitude}")
                        fetchAqiData(location.latitude, location.longitude)
                        locationManager.removeUpdates(this)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                // Request fresh GPS location first, fall back to network
                if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    Log.d("MainActivity", "Requesting fresh GPS location for AQI...")
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        0, 0f, locationListener)
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    Log.d("MainActivity", "GPS not available, using network location for AQI...")
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        0, 0f, locationListener)
                } else {
                    Log.w("MainActivity", "No location provider available for AQI refresh")
                }

                // Timeout after 15 seconds - fall back to cached location
                handler.postDelayed({
                    locationManager.removeUpdates(locationListener)
                    // Try cached location as fallback
                    var cachedLocation: android.location.Location? = null
                    if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        cachedLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    }
                    if (cachedLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        cachedLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    }
                    if (cachedLocation != null) {
                        Log.d("MainActivity", "GPS timeout, using cached location for AQI")
                        fetchAqiData(cachedLocation.latitude, cachedLocation.longitude)
                    }
                }, 15000)
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Location permission denied for AQI refresh", e)
        }
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
                    val unitTemp = getWeatherUnit()
                    weatherTemp?.text = "$roundedTemp°$unitTemp"
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
            attributionContext("weather")
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
                    
                    // Two days of daily figures alongside the current reading: index 0 is
                    // today, index 1 tomorrow, which is what the Start screen's weather
                    // tile turns over to show. Costs nothing extra - one request either
                    // way - and everything downstream reads the same cached response.
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                            "&current=temperature_2m,weather_code" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                            "&forecast_days=2&timezone=auto"
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
                            // Also fetch AQI data
                            fetchAqiData(latitude, longitude)
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
        var unit = getWeatherUnit()
        return "$temp°$unit"
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

    // AQI (Air Quality Index) methods
    private fun fetchAqiData(latitude: Double, longitude: Double) {
        // Don't hit the AirCare API at all when the indicator is disabled.
        if (!isShowAqiEnabled()) return
        Thread {
            try {
                val url = "https://getaircare.com/api/v4/api.php?requestType=point&lat=$latitude&lng=$longitude"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(response)
                    val measurements = jsonObject.getJSONArray("measurements")

                    // Find pid: 7 (EU AQI)
                    var aqiValue: Int? = null
                    for (i in 0 until measurements.length()) {
                        val measurement = measurements.getJSONObject(i)
                        if (measurement.getInt("pid") == 7) {
                            aqiValue = measurement.getInt("val")
                            break
                        }
                    }

                    if (aqiValue != null) {
                        saveAqiData(aqiValue)
                        runOnUiThread {
                            updateAqiDisplay(aqiValue)
                            // Notify QuickGlanceWidget to refresh
                            if (::quickGlanceWidget.isInitialized) {
                                quickGlanceWidget.refreshData()
                            }
                        }
                        Log.d("MainActivity", "AQI updated successfully: $aqiValue")
                    }
                } else {
                    Log.e("MainActivity", "AQI API error: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error fetching AQI data", e)
            }
        }.start()
    }

    private fun saveAqiData(aqi: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putInt(KEY_AQI_DATA, aqi)
                putLong(KEY_AQI_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            Log.d("MainActivity", "AQI data saved: $aqi")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving AQI data", e)
        }
    }

    fun getCachedAqi(): Int? = wp81TileHost.cachedAqi()

    fun isAqiDataFresh(maxAgeMinutes: Int = 60): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timestamp = prefs.getSafeLong(KEY_AQI_TIMESTAMP, 0L)
        if (timestamp == 0L) return false

        val ageMinutes = (System.currentTimeMillis() - timestamp) / (1000 * 60)
        return ageMinutes < maxAgeMinutes
    }

    private fun updateAqiDisplay(aqi: Int) {
        val aqiContainer = findViewById<LinearLayout>(R.id.aqi_container)
        val aqiText = findViewById<TextView>(R.id.aqi_text)

        // Respect the opt-in setting: never surface AQI when it's disabled.
        if (!isShowAqiEnabled()) {
            aqiContainer?.visibility = View.GONE
            return
        }

        aqiContainer?.visibility = View.VISIBLE
        aqiText?.text = aqi.toString()

        // Get underline color based on AQI value
        val underlineColor = when {
            aqi <= 26 -> "#4CAF50".toColorInt() // Green
            aqi <= 33 -> "#FFEB3B".toColorInt() // Yellow
            aqi <= 66 -> "#FF9800".toColorInt() // Orange
            aqi <= 100 -> "#F44336".toColorInt() // Red
            else -> "#9C27B0".toColorInt() // Purple
        }

        // Clear container background
        aqiContainer?.setBackgroundColor(Color.TRANSPARENT)

        // Set text color based on theme (black for Classic/98, white for XP/Vista)
        val textColor = if (themeManager.isClassicTheme()) Color.BLACK else Color.WHITE
        aqiText?.setTextColor(textColor)

        // Create 2px colored underline as background drawable, offset 2px down
        aqiText?.let { textView ->
            val density = resources.displayMetrics.density
            val underlineHeight = (2 * density).toInt()
            val underlineOffset = (2 * density).toInt()
            val underlineDrawable = GradientDrawable().apply {
                setColor(underlineColor)
            }
            val layerDrawable = LayerDrawable(arrayOf(underlineDrawable)).apply {
                setLayerGravity(0, Gravity.BOTTOM)
                setLayerHeight(0, underlineHeight)
                setLayerInsetBottom(0, -underlineOffset)
            }
            textView.background = layerDrawable
        }
        aqiContainer?.clipToPadding = false
        aqiContainer?.clipChildren = false
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
                zuneAppInstance?.refreshLibrary()
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

            PHOTOS_PERMISSION_REQUEST_CODE -> {
                // Any of them: on Android 14 the user may have granted a selection rather
                // than the library, which arrives as the second permission and not the first.
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // The tile fills with pictures where the invitation was. Opening the
                    // gallery on top of that would take the user away from the thing they
                    // have just this second switched on.
                    refreshWP81Photos(force = true)
                } else {
                    showNotification("Photos", "Photo access is needed to show your pictures")
                }
            }

            CONTACTS_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // The tile fills with faces where the invitation was. Opening the
                    // dialler on top of that would take the user away from the thing they
                    // have just this second switched on.
                    refreshWP81People(force = true)
                } else {
                    showNotification("People", "Contact access is needed to show your people")
                }
            }

            MIDTOWN2_LOCATION_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                pendingMidtown2App?.onPermissionResult(granted)
                if (!granted) {
                    Log.d("Midtown2", "Location permission denied")
                }
            }
        }
    }
    
    // Notification monitoring functionality
    fun updateNotificationDots() {
        handler.post {
            // The WP8.1 tiles surface the notification text itself, not just a dot, and
            // they ignore the dots setting - a live tile *is* the notification.
            refreshWP81Notifications()
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
            // Drop anything cached under this package name (a reinstall reuses it) and refresh
            invalidateIconCache(packageName)
            loadInstalledApps()
            refreshCommandsList()
            refreshWP81ForPackageChange(packageName)

            // Then create a desktop icon for the new app (only if it's launchable)
            try {
                // The install can be reported by both the broadcast receiver and the LauncherApps
                // callback, so only add a shortcut when the desktop doesn't already have one
                val alreadyOnDesktop = desktopIcons.any { it.packageName == packageName }

                // Check if the app has a launcher intent (is launchable by user)
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null && !alreadyOnDesktop) {
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
                    Log.d("MainActivity", "Skipping desktop shortcut for $packageName (launchable: ${launchIntent != null}, already on desktop: $alreadyOnDesktop)")
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error creating desktop shortcut for: $packageName", e)
            }
        }
    }
    
    override fun onAppRemoved(packageName: String) {
        Log.d("MainActivity", "App removed notification: $packageName")
        runOnUiThread {
            // Drop the removed app's cached icons, then refresh the app list
            invalidateIconCache(packageName)
            loadInstalledApps()
            refreshWP81ForPackageChange(packageName)
            
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
            // An update can ship a different icon under the same package name, so the cached
            // bitmaps have to go before anything reads the icon again
            refreshIconsForPackage(packageName)
            refreshWP81ForPackageChange(packageName)
        }
    }

    /**
     * Brings the Windows Phone 8.1 shell up to date after a package changed.
     *
     * The desktop themes were already told - this is the same routine, which rebuilt the
     * app list and the desktop icons and simply had no branch for this shell, so under
     * WP8.1 a newly installed app stayed invisible until the theme was applied again.
     *
     * Both surfaces are refreshed because both are downstream of the change: the app list
     * reads the package manager, and Start is built from the desktop icons this routine
     * has just added one to (or taken one away from).
     *
     * All three calls no-op when the shell is not up, so this is safe on every theme.
     */
    private fun refreshWP81ForPackageChange(packageName: String) {
        if (wp81Shell == null) return
        // The glyph is derived from the app's icon and cached by package; an update can
        // ship a new one under the same name.
        wp81IconProvider.invalidate(packageName)
        refreshWP81AppList()
        refreshWP81Tiles()
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

        // Reload custom icon mappings for Windows Classic theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Update quick glance widget font to Microsoft Sans Serif
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(true)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // One hub for every registered theme-aware component: desktop-icon fonts + system icons
        // (recycle bin, my computer, non-custom folders), start-menu adapters, and the context menu.
        // Runs after updateAllCustomIcons() so custom folder icons are preserved.
        notifyThemeChanged(AppTheme.WindowsClassic)

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

        // Reload custom icon mappings for Windows XP theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Restore quick glance widget font to Tahoma
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(false)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // One hub for every registered theme-aware component (see applyWindows98Theme).
        notifyThemeChanged(AppTheme.WindowsXP)

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

        // Reload custom icon mappings for Windows Vista theme and update all icons
        loadCustomIconMappings()
        updateAllCustomIcons()

        // Restore quick glance widget font to Tahoma
        if (::quickGlanceWidget.isInitialized) {
            quickGlanceWidget.setThemeFont(false)
            // Force refresh to ensure font change is applied
            quickGlanceWidget.refreshData()
        }

        // One hub for every registered theme-aware component (see applyWindows98Theme).
        notifyThemeChanged(AppTheme.WindowsVista)

        // Update dialog backgrounds for future dialogs - store theme preference
        // Dialogs will check this when they're created
    }


    // ==================================================================================
    // Windows Phone 8.1 shell
    //
    // Unlike the other three themes this is not a reskin of the desktop: it replaces the
    // desktop, taskbar and Start menu outright with a phone UI. Windowed programs still
    // run in the existing floating window container, in Vista chrome (see
    // AppTheme.chrome / DesktopChrome.VISTA), above the shell.
    // ==================================================================================

    private var wp81Shell: rocks.gorjan.gokixp.wp81.WP81Shell? = null
    private val wp81IconProvider by lazy {
        rocks.gorjan.gokixp.wp81.MonochromeIconProvider(this)
    }
    private var wp81LiveTileRunnable: Runnable? = null
    private val wp81Handler = Handler(Looper.getMainLooper())

    /** Latest next-event summary for the Calendar live widget, or null when there is none. */
    private var wp81NextCalendarEvent: Pair<String, Long>? = null

    /** Tomorrow, as the calendar tile's reverse says it: how many, and the first of them. */
    private var wp81TomorrowEvents: Pair<Int, String?> = 0 to null
    private var wp81CalendarProvider:
        rocks.gorjan.gokixp.quickglance.CalendarDataProvider? = null
    /**
     * What is on the Start screen, and how each tile is painted.
     *
     * Shared with the car screen, which builds its own wall from this same object so that
     * a tile pinned or recoloured here turns up there without being added twice.
     */
    private val wp81TileHost by lazy {
        rocks.gorjan.gokixp.wp81.WP81TileHost(
            context = this,
            icons = { desktopIcons },
            saveIcons = { saveDesktopIcons() },
            persistTiles = { tiles -> persistWP81Tiles(tiles) },
            displayName = { pkg, original -> getCustomOrOriginalName(pkg, original) },
            landscape = { wp81Landscape() }
        )
    }

    private var wp81MediaSessions: rocks.gorjan.gokixp.wp81.MediaSessions? = null

    /**
     * Tile glyphs for the built-in programs. Third-party apps go through
     * [rocks.gorjan.gokixp.wp81.MonochromeIconProvider] instead; these are fixed because
     * the built-ins have no Android icon to derive anything from.
     */
    private val wp81SystemGlyphs: Map<String, Int> = mapOf(
        "system.internet_explorer" to R.drawable.wp81_glyph_ie,
        "system.registry_editor" to R.drawable.wp81_glyph_regedit,
        "system.dialer" to R.drawable.wp81_glyph_dialer,
        "system.notepad" to R.drawable.wp81_glyph_notepad,
        "system.winamp" to R.drawable.wp81_glyph_winamp,
        "system.zune" to R.drawable.wp81_glyph_headphones,
        "system.news" to R.drawable.wp81_glyph_news,
        "system.welcome" to R.drawable.wp81_glyph_welcome,
        "system.wmp" to R.drawable.wp81_glyph_wmp,
        "system.minesweeper" to R.drawable.wp81_glyph_minesweeper,
        "system.solitare" to R.drawable.wp81_glyph_solitaire,
        "system.pinball" to R.drawable.wp81_glyph_pinball,
        "system.clock" to R.drawable.wp81_glyph_clock,
        "system.midtown2" to R.drawable.wp81_glyph_midtown,
        "system.calculator" to R.drawable.wp81_glyph_calculator
    )

    /**
     * The shell's own Metro programs, as against the desktop-era ones it also carries.
     *
     * These are the apps written for this shell - full screen, no title bar, laid out the
     * way the phone laid things out - and in the app list they are drawn the way Windows
     * Phone drew the programs that came with it: the glyph in white on a square of the
     * accent. Notepad, the browser and the calculator have desktop versions too, and open
     * as those under the desktop themes; what is listed here is what they are *under this
     * one*, which is where the app list is.
     *
     * A new Metro app belongs in this set and in [wp81SystemGlyphs], and nowhere else.
     */
    private val wp81MetroApps: Set<String> = setOf(
        "system.internet_explorer",
        "system.notepad",
        "system.welcome",
        "system.zune",
        "system.news",
        "system.calculator",
        "system.minesweeper",
        "system.solitare"
    )

    /**
     * Puts the desktop back after the Windows Phone shell has been over it.
     *
     * The mirror of what [applyWindowsPhone81Theme] takes away. Written as a restore rather
     * than as an undo inside each desktop theme, because there are four of those and they
     * all want the same thing: a screen that looks like nothing else was ever on it.
     */
    private fun restoreDesktopAfterWP81() {
        wp81Shell?.let { shell ->
            stopWP81LiveTiles()
            (shell.parent as? ViewGroup)?.removeView(shell)
        }
        wp81Shell = null

        findViewById<View>(R.id.desktop_icons_container)?.visibility = View.VISIBLE
        findViewById<View>(R.id.taskbar_container)?.visibility = View.VISIBLE
        findViewById<View>(R.id.christmas_wrapper)?.visibility = View.VISIBLE
        // A Start menu is opened by the Start button, not by arriving on the desktop.
        findViewById<View>(R.id.start_menu_container)?.visibility = View.GONE

        // The pointer is a desktop conceit the phone hides; on a desktop it is how the
        // machine is used at all, and its absence is the most disabling half of this.
        findViewById<View>(R.id.cursor_effect)?.visibility = View.VISIBLE

        // Back to whatever the user set it to rather than simply on.
        findViewById<View>(R.id.gesture_bar_background)?.let { loadGestureBarVisibility(it) }

        // The phone paints these its own accent; a desktop draws its wallpaper over the
        // one and wants black behind the system bars in the other.
        findViewById<View>(R.id.root_container)?.setBackgroundColor(android.graphics.Color.BLACK)
        findViewById<RelativeLayout>(R.id.main_background)?.setBackgroundColor(
            android.graphics.Color.TRANSPARENT)
    }

    private fun applyWindowsPhone81Theme() {
        Log.d("MainActivity", "Applying Windows Phone 8.1 theme")

        // Tear down the desktop metaphor. None of this has a counterpart on a phone.
        findViewById<View>(R.id.desktop_icons_container)?.visibility = View.GONE
        findViewById<View>(R.id.taskbar_container)?.visibility = View.GONE
        findViewById<View>(R.id.start_menu_container)?.visibility = View.GONE
        findViewById<View>(R.id.gesture_bar_background)?.visibility = View.GONE
        findViewById<View>(R.id.christmas_wrapper)?.visibility = View.GONE

        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)

        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)
        mainBackground?.background = null
        mainBackground?.setBackgroundColor(palette.background)

        // root_container is declared black in the layout, and it is what shows through the
        // padding held back for the system status and navigation bars. Left alone, a Light
        // theme would sit in a black frame top and bottom.
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)

        // The fake mouse cursor is a desktop conceit; a phone is touch-only.
        findViewById<View>(R.id.cursor_effect)?.visibility = View.GONE

        // Drop any wallpaper a previous theme left behind. It is added at index 0 of
        // main_background, so it would otherwise sit on top of anything added there.
        mainBackground?.findViewWithTag<ImageView>("wallpaper")?.let { wallpaper ->
            mainBackground.removeView(wallpaper)
        }

        val shell = wp81Shell ?: rocks.gorjan.gokixp.wp81.WP81Shell(
            this, palette, wp81IconProvider
        ).also { created ->
            wp81Shell = created
            // Added last, at elevation 0: above every other elevation-0 sibling, but still
            // below floating_windows_container (elevation 50dp), so windowed programs keep
            // drawing over the shell.
            mainBackground?.addView(
                created,
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                )
            )
            wireWP81Shell(created)
        }

        shell.applyPalette(palette)
        applyWP81SystemBarAppearance(palette)
        applyWP81StartBackground()
        rebaseFloatingWindowsForWP81()

        // The phone keeps its own hand-picked icons, so they have to be read for this
        // theme the way each desktop theme reads its own. Without this the shell arrived
        // still holding the previous theme's mappings - showing its icons on the tiles,
        // and writing that borrowed set back out under the phone's key the moment one
        // tile icon was changed.
        loadCustomIconMappings()
        wp81IconProvider.invalidateAll()

        refreshWP81Tiles()
        refreshWP81AppList()
        startWP81LiveTiles()

        notifyThemeChanged(AppTheme.WindowsPhone81)
    }

    /**
     * Points Android's own status bar icons the right way.
     *
     * WP8.1's shell is either black or white end to end, and the system bar sits directly
     * on it, so the icons have to invert with the Light/Dark setting or they vanish.
     */
    private fun applyWP81SystemBarAppearance(palette: rocks.gorjan.gokixp.wp81.WP81Palette) {
        val controller = androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.isDark
        controller.isAppearanceLightNavigationBars = !palette.isDark
    }

    private fun wireWP81Shell(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        wireWP81Settings(shell)
        shell.startScreen.onLaunch = { tile -> launchWP81Tile(tile) }
        shell.startScreen.onSwipeDownAtTop = { expandNotificationShade() }
        // Pushing up at the bottom of Start reaches the app list, which still arrives from
        // the side: the gesture is a shortcut to the page, not a different way of showing it.
        shell.startScreen.onSwipeUpAtBottom = {
            // The same knock the shade gives when it is pulled out of the other end. Both
            // gestures push the wall off an edge and hand the screen to something else, so
            // both should land the same way.
            rocks.gorjan.gokixp.wp81.Haptics.tap(shell.startScreen)
            shell.openAppSearch()
        }
        shell.startScreen.onTilesChanged = { tiles -> persistWP81Tiles(tiles) }
        shell.startScreen.onTileUnpin = { tile -> unpinOrHideWP81Tile(tile) }
        // The arrow under the wall goes where the leftward swipe goes.
        // The arrow pages to the list and stops there. It used to drop into search as
        // well, which put a keyboard over the very list the arrow had just been pressed to
        // see - the arrow is how somebody who wants to *look* through their apps gets
        // there, and typing is what the search key and the jump list's globe are for.
        shell.startScreen.onOpenAppList = { shell.goToAppList() }
        // A folder opens into the wall rather than onto a page of its own.
        shell.startScreen.onFolderOpened = { folder -> openWP81FolderInline(folder) }
        // Resizing a tile inside an opened folder writes back to the folder's own icons.
        shell.startScreen.onFolderTilesChanged = { folderId, tiles ->
            persistWP81FolderTiles(folderId, tiles)
        }
        shell.startScreen.onTileFiled = { tile, folderId, alreadyMoved ->
            fileWP81Tile(tile, folderId, alreadyMoved)
        }
        shell.startScreen.onFolderRename = { folder -> renameWP81Tile(folder) }
        shell.startScreen.folderPreviewOf = { pair -> wp81PreviewOf(pair) }
        shell.startScreen.folderPreviewWith = { folder, incoming ->
            wp81PreviewWith(folder, incoming)
        }
        shell.startScreen.onTilesFoldered = { dragged, onto -> foldWP81Tiles(dragged, onto) }

        shell.appList.onLaunch = { app ->
            if (isSystemApp(app.packageName)) {
                // A program of this shell's own opens in a window over the shell, so the
                // shell has to be somewhere worth opening over: left in search, the list
                // and its keyboard stayed in front of the window that had just opened, and
                // pressing search on a sole result looked like it had done nothing.
                shell.appList.endSearch()
                shell.goToStart(animated = false)
                launchSystemApp(app.packageName)
            } else {
                // Straight out to the app, with nothing done to the list first. Leaving
                // search here - re-filtering the rows, dropping the keyboard - was work
                // done in front of somebody who had already asked for something else, and
                // it read as the tap hesitating before the app opened. The list is put
                // back to rest once the launcher is actually behind the app: see onStop.
                packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
            }
        }
        // The shell's own programs wear their glyph on an accent square; everything else
        // keeps the icon it was installed with. See AppListView.metroGlyph.
        shell.appList.metroGlyph = { app ->
            if (app.packageName in wp81MetroApps) wp81SystemGlyphs[app.packageName] else null
        }
        shell.appList.onLongPress = { app, anchorY ->
            // No buzz of its own: the row gives the shell's tick as it claims the press.
            // See AppListView's long-click listener, and wp81.Haptics.
            // A menu over a keyboard leaves the commands squeezed into what is left of the
            // screen; the search text is kept so the list is unchanged on the way back.
            shell.appList.hideKeyboard()
            shell.contextMenu.show(app.name, wp81AppMenu(app), anchorY)
        }

        shell.onSearch = { launchWebSearch() }
        shell.appList.onSearchWeb = { query ->
            // Leaving search behind: coming back to a list still filtered to nothing, with
            // the keyboard up, is not where anyone wants to land after being sent away.
            shell.appList.endSearch()
            searchTheWebFor(query)
        }
        shell.navBar.onBack = { onBackPressedDispatcher.onBackPressed() }
        // Folders are made by holding one tile over another, as on the phone, so there is
        // no "new folder" command any more. "Remove from folder" is on the tile's own
        // command list, where the rest of the once-a-tile things live.
        shell.secondaryBar.onAddApp = { addAppToOpenWP81Folder() }
        shell.secondaryBar.onTileColor = {
            shell.selectedTile()?.let { tile ->
                shell.colorPicker.show(tile.label, themeManager.getWP81TileColors()[tile.id])
            }
        }
        shell.colorPicker.onPicked = { color ->
            shell.selectedTile()?.let { tile ->
                themeManager.setWP81TileColor(tile.id, color)
                wp81TileHost.refreshColors()
                if (themeManager.getWP81HideTileColors()) {
                    // Stored, but nothing on screen will change until the switch in
                    // settings goes back off - and a command that silently does nothing is
                    // a command the user will assume is broken.
                    showNotification("Tile colour", "Saved \u2013 tile colours are hidden")
                } else {
                    // Repainted in place: rebuilding the wall to change one colour would
                    // drop the selection and replay every tile's entrance.
                    shell.startScreen.setTileColor(tile.id, color)
                    shell.folderPage.contents.setTileColor(tile.id, color)
                }
            }
        }
        shell.secondaryBar.onTileMenu = {
            // Anchored to the strip the command came from, so the list opens next to it.
            shell.selectedTile()?.let { tile ->
                shell.contextMenu.show(
                    tile.label,
                    wp81TileMenu(tile, inFolder = shell.isFolderOpen()),
                    shell.height * 0.45f
                )
            }
        }

        // Windowed programs sit on black: WP8.1 has no desktop for them to float over.
        // The same signal tells the shell where the user is, so the key strip answers for
        // the program on screen rather than for the Start screen hidden behind it.
        floatingWindowManager.onWindowCountChanged = { visible ->
            val wasCovered = wp81ProgramOnScreen
            wp81ProgramOnScreen = visible > 0
            shell.setWindowBackdropVisible(visible > 0)
            shell.programOnScreen = visible > 0
            // The last program has gone and Start is back, so it arrives rather than
            // simply being there again - and it has to. Launching from a tile turns the
            // whole wall away and leaves it transparent, and the only thing that ever put
            // it back was the Start screen becoming visible again. That never happens to a
            // program in a window: the shell is covered by a backdrop drawn over it, not
            // hidden, so closing one left the wall turned away on a black screen.
            if (wasCovered && visible == 0) shell.startScreen.playEntrance()
        }
        // And once now: the callback only fires on a change, so a program left open across
        // a theme switch would otherwise have the shell believing it was on Start.
        floatingWindowManager.notifyWindowVisibilityChanged()

        // The Start key means Start. With a program on screen the shell's own answer -
        // "you are already on Start, so open the searchable app list" - was true of the
        // shell and wrong for the user, who was looking at Zune and got a keyboard.
        //
        // Programs are put away rather than shut down: nothing here has been asked to
        // stop, Zune in particular is still playing, and every one of them comes back by
        // being launched again.
        // Holding the Start key: the shell's own commands, as against a tile's. Settings
        // is here rather than on the key strip, which is now the three hardware keys on
        // every page - and the shell's commands are what a hold on Start should offer
        // anyway. The app list is built once and cached, so an app installed since then is
        // invisible until something asks for it again - and until now nothing did short of
        // switching theme.
        shell.navBar.onStartLongPress = {
            // The command list belongs to the shell, which sits under the window
            // container - so with a program on screen it would open where nobody could
            // see it. There the hold does what the tap does: put the program away.
            if (!minimiseWP81Windows()) shell.contextMenu.show(
                "start",
                listOfNotNull(
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("settings") {
                        openWP81Settings()
                    },
                    // What is hidden is a fact about the wall, not about whichever tile
                    // happened to be selected when the user went looking for it - which is
                    // where this used to be, on the command list of a tile that had nothing
                    // to do with it. Offered only once something is hidden: a list of
                    // nothing is a command that answers a question nobody asked.
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("hidden tiles") {
                        showWP81HiddenTiles()
                    }.takeIf { themeManager.getWP81HiddenTiles().isNotEmpty() },
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("refresh app list") {
                        refreshWP81AppList()
                        showNotification("App list", "Looking for new apps")
                    },
                    // Welcome is where the release notes are, and it is the nearest thing
                    // this shell has to an about box. Straight to it, rather than to the
                    // update the tile diverts to: this is a command that was asked for by
                    // name, not a tile showing something.
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("about") {
                        showWelcomeDialogWP81()
                    }
                ),
                // Low on the screen, next to the key the command came from.
                shell.height * 0.62f
            )
        }
        shell.navBar.onStart = {
            when {
                minimiseWP81Windows() -> Unit
                // To the list, not into a search of it - the same thing the arrow under
                // the wall does. A key pressed to see what is installed should not answer
                // with a keyboard over it; the swipe across is the gesture that means
                // "and I am about to type".
                shell.isOnStartPage() -> shell.goToAppList()
                else -> shell.goToStart()
            }
        }
    }

    /**
     * The floating window container is laid out for the desktop themes - 60dp of headroom
     * and 70dp for the taskbar. Under WP8.1 it has to clear the status and navigation bars
     * instead.
     */
    private fun rebaseFloatingWindowsForWP81() {
        val container = findViewById<View>(R.id.floating_windows_container) ?: return
        val density = resources.displayMetrics.density
        val params = container.layoutParams as RelativeLayout.LayoutParams
        params.topMargin = 0
        params.bottomMargin = (rocks.gorjan.gokixp.wp81.WP81NavBar.HEIGHT_DP * density).toInt()
        container.layoutParams = params
    }

    // ---------------------------------------------------------------- tiles

    /**
     * Builds the Start screen from the launcher's existing desktop icons.
     *
     * Icons with no tile placement yet - which is all of them the first time this theme is
     * used - are migrated in their existing desktop grid order and given a medium tile,
     * so the user's arrangement carries over rather than being thrown away.
     */
    private fun buildWP81Tiles(): List<rocks.gorjan.gokixp.wp81.Tile> =
        wp81TileHost.buildTiles()

    /**
     * The tiles the shell always provides, pinned above the user's own.
     *
     * Three live widgets - clock, calendar and air quality - plus Settings. All are part
     * of the shell rather than the user's arrangement: they always show current content
     * and cannot be unpinned, so they are rebuilt on every refresh instead of being
     * persisted as desktop icons.
     */
    private fun wp81BuiltInTiles(
        placements: MutableMap<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>>
    ): List<rocks.gorjan.gokixp.wp81.Tile> = wp81TileHost.builtInTiles(placements)

    /**
     * Pushes current notification text onto the tiles.
     *
     * Called from [updateNotificationDots], which the listener already invokes on every
     * change, so tiles track the shade without a poll of their own.
     */
    private fun refreshWP81Notifications() {
        val shell = wp81Shell ?: return
        shell.startScreen.setNotifications { tile -> wp81NotificationsFor(tile) }
        // The folder page shows tiles too, so it gets the same treatment while it is open.
        shell.folderPage.setNotifications { tile -> wp81NotificationsFor(tile) }
        // A folder's preview carries the same information as a dot, one app at a time, so
        // it is refreshed on the same pass.
        shell.startScreen.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        shell.folderPage.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        refreshWP81Media()
    }

    /**
     * Which package's media session a tile should read.
     *
     * Its own, for a real app. Zune is a program inside this launcher rather than an
     * installed app, so its session is held by *this* process - and a tile looking for one
     * under "system.zune" would never find anything, leaving the one player on the phone
     * that belongs to this shell as the only one whose tile stayed dead.
     */
    private fun wp81MediaPackageFor(tile: rocks.gorjan.gokixp.wp81.Tile): String =
        if (tile.packageName == "system.zune") packageName else tile.packageName

    /**
     * Pushes what each app is playing onto its tile.
     *
     * Applied after notifications so that media wins where both exist: a tile can only say
     * one thing, and what is playing now beats what arrived earlier.
     */
    private fun refreshWP81Media() {
        val shell = wp81Shell ?: return
        val sessions = wp81MediaSessions?.active().orEmpty()
        val lookup: (rocks.gorjan.gokixp.wp81.Tile) -> rocks.gorjan.gokixp.wp81.MediaSessions.Info? =
            { tile -> sessions[wp81MediaPackageFor(tile)] }
        shell.startScreen.setMedia(lookup)
        shell.folderPage.contents.setMedia(lookup)

        for (surface in listOf(shell.startScreen, shell.folderPage.contents)) {
            surface.setMediaHandlers(
                onPlayPause = { tile -> wp81MediaSessions?.togglePlayPause(wp81MediaPackageFor(tile)) },
                onNext = { tile -> wp81MediaSessions?.next(wp81MediaPackageFor(tile)) },
                onPrevious = { tile -> wp81MediaSessions?.previous(wp81MediaPackageFor(tile)) }
            )
        }
    }

    /**
     * Notification lines for a tile.
     *
     * A folder has no notifications of its own, so it stands in for everything filed
     * inside it - otherwise something arriving in a folder would be invisible from Start,
     * which is the one screen the user is looking at.
     */
    /** The stories behind the News tile. Fed by whichever feeds are switched on. */
    private val wp81NewsFeed by lazy {
        rocks.gorjan.gokixp.wp81.NewsFeed { refreshWP81News() }
    }

    /**
     * Hands the News tile the run of stories it turns through.
     *
     * Headline over source: a tile has room for what happened and who says so, and the
     * summary underneath it would leave neither legible.
     */
    private fun refreshWP81News() {
        // The reader, if it is open, shows the same stories as the tile.
        newsAppInstance?.bind()
        val shell = wp81Shell ?: return
        val faces = wp81NewsFeed.stories().map { story ->
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = story.title,
                detail = story.source.takeIf { it.isNotBlank() },
                image = story.image
            )
        }
        val waiting = listOf(
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = if (themeManager.getWP81NewsFeeds().isEmpty()) "No feeds turned on"
                        else "Fetching the news…",
                detail = "News"
            )
        )
        // The tiles are rebuilt often; the loader is theirs for as long as they live. One
        // loader serves the whole wall, so it answers for both kinds of picture: a story's,
        // fetched over the network, and one of the user's own, read out of MediaStore.
        shell.startScreen.setBackdropLoader { source, onReady ->
            if (source.startsWith("content:")) {
                rocks.gorjan.gokixp.wp81.PhotoFeed.load(this, source, onReady)
            } else {
                rocks.gorjan.gokixp.wp81.NewsImages.load(source, onReady)
            }
        }
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_NEWS,
            faces.ifEmpty { waiting },
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.STORY
        )
    }

    /** Reads the feeds, if the tile that shows them is on Start. */
    private fun refreshWP81NewsFeeds(force: Boolean = false) {
        if (!wp81HasNewsTile()) return
        wp81NewsFeed.refreshIfStale(themeManager.getWP81NewsFeeds().toList().sorted(), force)
    }

    private fun wp81HasNewsTile(): Boolean =
        wp81Shell?.startScreen?.tiles()?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS } == true

    // ---------------------------------------------------------------- photos

    /** The camera roll the Photos tile is turning through, newest first. */
    private var wp81Photos: List<rocks.gorjan.gokixp.wp81.PhotoFeed.Shot> = emptyList()

    /** When that was last read, so new pictures arrive without re-reading on every tick. */
    private var wp81PhotosReadAt = 0L
    private var wp81PhotosLoading = false

    private fun wp81HasPhotosTile(): Boolean =
        wp81Shell?.startScreen?.tiles()
            ?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS } == true

    /**
     * Hands the Photos tile the run of pictures it turns through.
     *
     * Three states, and the tile says which one it is in: no permission yet, in which case
     * it is an invitation rather than a slideshow; permission but nothing read back; and
     * the pictures themselves, which carry no words at all - a photograph on a tile is not
     * captioned, it is looked at.
     */
    private fun refreshWP81Photos(force: Boolean = false) {
        val shell = wp81Shell ?: return
        if (!wp81HasPhotosTile()) return

        if (!rocks.gorjan.gokixp.wp81.PhotoFeed.hasAccess(this)) {
            wp81Photos = emptyList()
            wp81PhotosReadAt = 0L
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PHOTOS,
                listOf(
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "Photos",
                        detail = "tap to allow",
                        glyph = R.drawable.wp81_glyph_photos
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        val age = System.currentTimeMillis() - wp81PhotosReadAt
        if (!wp81PhotosLoading && (force || wp81PhotosReadAt == 0L || age > WP81_PHOTOS_MAX_AGE_MS)) {
            wp81PhotosLoading = true
            // A forced read is one where what was there is no longer to be trusted - the
            // permission just changed - so what was decoded under it goes too.
            if (force) rocks.gorjan.gokixp.wp81.PhotoFeed.clear()
            rocks.gorjan.gokixp.wp81.PhotoFeed.recent(this) { shots ->
                wp81PhotosLoading = false
                wp81PhotosReadAt = System.currentTimeMillis()
                wp81Photos = shots
                refreshWP81Photos()
            }
        }

        val faces = wp81Photos.map { shot ->
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = "",
                detail = null,
                image = shot.uri,
                // The picture is the tile. See TileView.LiveFace.washed.
                washed = false,
                // A clip plays on the tile rather than sitting there as its first frame.
                motion = shot.isVideo
            )
        }
        val waiting = listOf(
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = "Photos",
                detail = if (wp81PhotosLoading) "looking\u2026" else "no pictures yet",
                glyph = R.drawable.wp81_glyph_photos
            )
        )
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_PHOTOS,
            faces.ifEmpty { waiting },
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
        )
    }

    /**
     * Tapping the Photos tile: the permission first, the app after.
     *
     * The first tap is the opt-in, because the shell has nowhere else to ask - and asking
     * on first run, for a tile the user may never have wanted, is how a launcher earns a
     * reputation. Once it has been granted the tile does what it says: it opens the
     * pictures.
     */
    private fun openWP81Photos() {
        if (!rocks.gorjan.gokixp.wp81.PhotoFeed.hasAccess(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                rocks.gorjan.gokixp.wp81.PhotoFeed.permissions(),
                PHOTOS_PERMISSION_REQUEST_CODE
            )
            return
        }
        // Reading the roll again on the way out. A tap on this tile is someone going to
        // look at their pictures, which is exactly the moment the tile behind them should
        // stop showing the ones from before the last time they did - and it is the only
        // signal the shell gets that anything has been taken since.
        refreshWP81Photos(force = true)
        packageManager.getLaunchIntentForPackage(WP81_PHOTOS_PACKAGE)?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(it)
            return
        }
        // No Google Photos on this phone; hand the pictures to whatever does show them.
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: nothing on this phone opens pictures", e)
            showNotification("Photos", "No gallery app found")
        }
    }

    // ---------------------------------------------------------------- people

    /** The address book the People tile fills itself from: favourites, then the rest. */
    private var wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())

    /** When that was last read, so a new contact arrives without re-reading on every tick. */
    private var wp81PeopleReadAt = 0L
    private var wp81PeopleLoading = false

    private fun wp81HasPeopleTile(): Boolean =
        wp81Shell?.startScreen?.tiles()
            ?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE } == true

    /**
     * Hands the People tile the faces it fills itself with.
     *
     * Three states, as the Photos tile has: no permission, in which case the tile is an
     * invitation rather than a wall; permission but an address book with nobody in it,
     * which the tile says in as many words so it is clear it is working; and the people
     * themselves, who arrive as a mosaic rather than as a run of faces the tile turns over
     * - the tile *is* the grid, and it does its own shuffling from there.
     *
     * Handed over in the two parts ContactFeed reads them in, favourites and everybody
     * else, because how many of the second the wall needs depends on how many squares it
     * has - which is the tile's business, not this one's. See TileView.applyPeopleGrid.
     */
    private fun refreshWP81People(force: Boolean = false) {
        val shell = wp81Shell ?: return
        if (!wp81HasPeopleTile()) return

        if (!rocks.gorjan.gokixp.wp81.ContactFeed.hasAccess(this)) {
            wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())
            wp81PeopleReadAt = 0L
            shell.startScreen.setPeopleMosaic(WP81_WIDGET_PEOPLE, emptyList(), emptyList())
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PEOPLE,
                listOf(
                    // No mark in the corner: the tile says what it is in words, and a
                    // silhouette over them is a second way of saying the same thing on a
                    // tile whose whole subject is faces.
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "People",
                        detail = "tap to allow"
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        val age = System.currentTimeMillis() - wp81PeopleReadAt
        if (!wp81PeopleLoading && (force || wp81PeopleReadAt == 0L || age > WP81_PEOPLE_MAX_AGE_MS)) {
            wp81PeopleLoading = true
            // A forced read re-reads *who* is starred, and nothing more. The pictures are
            // not re-decoded with it: they belong to people whose faces have not changed
            // because the tile was tapped, and throwing them away here emptied the wall of
            // every face it had - the mosaic only asks for a picture when a square turns
            // over to somebody new, so the tile came back as a grid of initials and filled
            // itself in one square at a time over the next minute.
            rocks.gorjan.gokixp.wp81.ContactFeed.people(this) { book ->
                wp81PeopleLoading = false
                wp81PeopleReadAt = System.currentTimeMillis()
                wp81People = book
                refreshWP81People()
            }
        }

        if (wp81People.isEmpty) {
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PEOPLE,
                listOf(
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "People",
                        detail = if (wp81PeopleLoading) "looking\u2026" else "no contacts yet"
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        // The words go before the faces do: the tile is one or the other, and clearing the
        // run it was turning through is what stops it flipping over a wall that has just
        // taken the front.
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_PEOPLE,
            emptyList(),
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
        )
        shell.startScreen.setPeopleMosaic(
            WP81_WIDGET_PEOPLE, wp81People.favourites, wp81People.others)
    }

    /**
     * Tapping the People tile: the permission first, the phone after.
     *
     * The first tap is the opt-in, exactly as it is on the Photos tile - the shell has
     * nowhere else to ask, and an address book is not something to demand on first run for
     * a tile the user may never have wanted.
     *
     * Then the phone. Windows Phone opened its own People hub here, which this launcher
     * has no counterpart to; what a wall of the people you know is actually a shortcut to
     * is calling one of them, so the tile opens the dialler - the same way the Clock tile
     * opens the phone's own clock.
     */
    private fun openWP81People() {
        if (!rocks.gorjan.gokixp.wp81.ContactFeed.hasAccess(this)) {
            // The address book, and only the starred part of it - see ContactFeed.
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                rocks.gorjan.gokixp.wp81.ContactFeed.permissions(),
                CONTACTS_PERMISSION_REQUEST_CODE
            )
            return
        }
        // Reading the book again on the way out: a tap on this tile is somebody going to
        // find a person, which is the moment the tile behind them should stop showing the
        // faces from before the last time they added one.
        refreshWP81People(force = true)
        openPhoneApp()
    }

    /**
     * Opens whichever app the phone dials with.
     *
     * The default dialler by name first, since that is the one the user actually set, and
     * a plain dial intent after it for a phone that names none - which is also what a
     * tablet with no telephony ends up on, and where the last line reports that there was
     * nothing to open rather than failing silently.
     */
    private fun openPhoneApp() {
        val dialer = try {
            (getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager)
                ?.defaultDialerPackage
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: could not ask which dialler is default", e)
            null
        }
        if (dialer != null) {
            packageManager.getLaunchIntentForPackage(dialer)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(it)
                return
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: nothing on this phone dials", e)
            showNotification("People", "No phone app found")
        }
    }

    private fun wp81NotificationsFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> =
        // An app that is playing something shows that instead; its notifications would be
        // competing for the same tile and are the less useful of the two.
        if (wp81MediaSessions?.active()?.containsKey(wp81MediaPackageFor(tile)) == true) {
            emptyList()
        } else if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER) {
            collectFolderNotifications(tile.id, mutableSetOf())
        } else {
            NotificationListenerService.getNotificationLines(tile.packageName)
                .map { rocks.gorjan.gokixp.wp81.TileView.Line(it.title, it.text) }
        } + wp81UpdateLine(tile)

    /**
     * The Welcome tile's live content: the update, when there is one.
     *
     * Reusing the notification path rather than inventing a second one - the tile turns
     * over to it, wears the unread dot and comes back to its icon exactly as an app's
     * tile does with a message. There is no notification behind it, only a version number
     * this launcher happens to know about itself.
     */
    private fun wp81UpdateLine(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> {
        if (tile.packageName != "system.welcome") return emptyList()
        val version = updateAvailableVersion ?: return emptyList()
        return listOf(
            rocks.gorjan.gokixp.wp81.TileView.Line(
                "Update available", "Version $version \u00b7 tap to download")
        )
    }

    /**
     * Every notification from the apps inside [folderId], recursively.
     *
     * Each line is titled with the app it came from, since a folder tile showing bare
     * message text gives no clue which of its contents produced it. [visited] guards
     * against a folder cycle, which the data model does not forbid.
     */
    private fun collectFolderNotifications(
        folderId: String,
        visited: MutableSet<String>
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> {
        if (!visited.add(folderId)) return emptyList()
        val lines = mutableListOf<rocks.gorjan.gokixp.wp81.TileView.Line>()
        for (child in desktopIcons.filter { it.parentFolderId == folderId }) {
            if (child.type == IconType.FOLDER) {
                lines += collectFolderNotifications(child.id, visited)
                continue
            }
            val appName = getCustomOrOriginalName(child.packageName, child.name)
            for (line in NotificationListenerService.getNotificationLines(child.packageName)) {
                val detail = listOf(line.title, line.text)
                    .filter { it.isNotBlank() }
                    .joinToString(" \u00b7 ")
                lines += rocks.gorjan.gokixp.wp81.TileView.Line(appName, detail)
            }
        }
        return lines
    }

    /**
     * What each folder's tile previews, keyed by folder id.
     *
     * Built with the tiles rather than looked up per refresh: resolving a glyph reads the
     * package manager and rasterises artwork to measure it, and the notification pass runs
     * every couple of seconds. The contents of a folder change when the user changes them,
     * which is one of the things that rebuilds the tiles anyway.
     */
    private var wp81FolderPreviews: Map<String, List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry>> =
        emptyMap()

    /** Resolves every folder's contents into the mini tiles its own tile shows. */
    private fun buildWP81FolderPreviews() {
        wp81FolderPreviews = desktopIcons
            .filter { it.parentFolderId != null }
            .groupBy { it.parentFolderId!! }
            .mapValues { (_, children) ->
                children
                    // The same order the folder page lists them in, so the preview and the
                    // page inside it agree about what comes first.
                    .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                    .map { icon -> wp81FolderEntryFor(icon) }
            }
    }

    /** What a folder holding these tiles would show, for the fold-together preview. */
    private fun wp81PreviewOf(
        tiles: List<rocks.gorjan.gokixp.wp81.Tile>
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> =
        tiles.mapNotNull { tile ->
            desktopIcons.firstOrNull { it.id == tile.id }?.let { wp81FolderEntryFor(it) }
        }

    /** What an existing folder would show with one more tile in it, for the hover offer. */
    private fun wp81PreviewWith(
        folder: rocks.gorjan.gokixp.wp81.Tile,
        incoming: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> =
        // The arrival last, which is where filing it will actually put it: a folder keeps
        // what it already had in order and adds to the end.
        wp81FolderPreviewFor(folder) + wp81PreviewOf(listOf(incoming))

    /**
     * Makes a folder of two tiles held together, where the lower one was standing, and
     * opens it.
     *
     * Opened straight away because a folder with two things in it is not finished: it wants
     * a name, and the heading of the open folder is where that is done. Arriving inside it
     * also shows what was actually made, which a new tile appearing on the wall does not.
     */
    private fun foldWP81Tiles(
        dragged: rocks.gorjan.gokixp.wp81.Tile,
        onto: rocks.gorjan.gokixp.wp81.Tile
    ) {
        val first = desktopIcons.firstOrNull { it.id == onto.id } ?: return
        val second = desktopIcons.firstOrNull { it.id == dragged.id } ?: return
        if (first.type == IconType.FOLDER || second.type == IconType.FOLDER) return

        val folderId = "folder_${System.currentTimeMillis()}"
        val folderIcon = AppCompatResources.getDrawable(this, R.drawable.folder_vista) ?: return
        desktopIcons.add(
            DesktopIcon(
                name = "New Folder",
                packageName = folderId,
                icon = folderIcon,
                x = 0f,
                y = 0f,
                id = folderId,
                type = IconType.FOLDER,
                // In the slot the tile it was dropped on was in, at that tile's size: the
                // folder appears where the user was looking, not at the end of the wall.
                tileSize = onto.size.name,
                tileIndex = first.tileIndex,
                tileSizeLandscape = onto.size.name,
                tileIndexLandscape = first.tileIndexLandscape
            )
        )

        // The one that was underneath first, so the folder opens in the order they were
        // put together in.
        first.parentFolderId = folderId
        first.wp81TileIndex = 0
        second.parentFolderId = folderId
        second.wp81TileIndex = 1

        saveDesktopIcons()
        refreshWP81Tiles()

        // After the rebuild, and after it has been laid out: the gap is placed against the
        // folder's own row, and the packer has not worked out where that is until then.
        wp81Shell?.startScreen?.post {
            wp81Shell?.startScreen?.tiles()?.firstOrNull { it.id == folderId }
                ?.let { openWP81FolderInline(it) }
        }
    }

    /** One app inside a folder, as its mini tile draws it. */
    private fun wp81FolderEntryFor(
        icon: DesktopIcon
    ): rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry {
        // Routed through the tile glyph resolver rather than straight to the launcher
        // icon, so a mini tile shows exactly what the app's own tile would: a custom icon
        // if the user set one, the themed monochrome layer if the app ships one.
        val glyph = wp81GlyphFor(
            rocks.gorjan.gokixp.wp81.Tile(
                id = icon.id,
                label = icon.name,
                packageName = icon.packageName,
                size = rocks.gorjan.gokixp.wp81.TileSize.SMALL,
                index = 0,
                kind = wp81KindFor(icon)
            )
        )
        return rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry(
            id = icon.id,
            icon = when (glyph) {
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome -> glyph.drawable
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor -> glyph.drawable
                null -> null
            },
            tint = glyph is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome,
            contentRatio = when (glyph) {
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome -> glyph.contentRatio
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor -> glyph.contentRatio
                null -> 1f
            }
        )
    }

    /**
     * A folder's contents, as its tile previews them.
     *
     * Straight from the cache: a preview is icons, and what is unread inside the folder is
     * marked on the folder's name rather than on the squares.
     */
    private fun wp81FolderPreviewFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> {
        if (tile.kind != rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER) return emptyList()
        return wp81FolderPreviews[tile.id].orEmpty()
    }

    /** Which kind of tile a desktop icon becomes. Shared by Start, folders and previews. */
    private fun wp81KindFor(icon: DesktopIcon): rocks.gorjan.gokixp.wp81.Tile.Kind =
        wp81TileHost.kindFor(icon)

    /**
     * Whether the phone is on its side.
     *
     * The Start screen keeps a wall for each way up: turned sideways the screen is twice
     * as wide and half as tall and the wall is packed into twice the columns, so the
     * arrangement that suited it upright is not an arrangement at all - tiles the user put
     * side by side end up on different rows and the order they chose reads as nothing.
     * Two arrangements, remembered separately, and each one only written when the phone is
     * being held that way.
     */
    private fun wp81Landscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * Where this icon sits on the wall the phone is currently holding, and how big.
     *
     * Reading sideways falls back to the upright placement while there is none of its own,
     * so the first turn of the phone lands on the wall the user already knows rather than
     * on an alphabetical one. Writing never falls back: it puts the arrangement where the
     * orientation it was made in will find it.
     */
    private var DesktopIcon.wp81TileIndex: Int?
        get() = if (wp81Landscape()) tileIndexLandscape ?: tileIndex else tileIndex
        set(value) {
            if (wp81Landscape()) tileIndexLandscape = value else tileIndex = value
        }

    private var DesktopIcon.wp81TileSize: String?
        get() = if (wp81Landscape()) tileSizeLandscape ?: tileSize else tileSize
        set(value) {
            if (wp81Landscape()) tileSizeLandscape = value else tileSize = value
        }

    /**
     * What a tile should actually be painted, which is nothing while colours are hidden.
     *
     * Null rather than the accent: a tile with no colour of its own is a window onto the
     * Start background, where one painted the accent would be a solid block of it - and
     * seeing the wallpaper is the entire point of the switch. See TileView.onDraw.
     */
    private fun wp81ColorFor(tile: rocks.gorjan.gokixp.wp81.Tile): Int? =
        wp81TileHost.colorFor(tile)

    /**
     * Repaints the wall for the current colour setting, without rebuilding it.
     *
     * In place, like the colour picker: rebuilding to change what is only a paint job
     * would drop the selection and replay every tile's entrance.
     */
    private fun applyWP81TileColors() {
        val shell = wp81Shell ?: return
        wp81TileHost.refreshColors()
        for (tile in shell.startScreen.tiles()) {
            shell.startScreen.setTileColor(tile.id, wp81ColorFor(tile))
        }
        // The folder page is a wall of tiles too, and may be open in front of this one.
        for (tile in shell.folderPage.contents.tiles()) {
            shell.folderPage.contents.setTileColor(tile.id, wp81ColorFor(tile))
        }
    }

    private fun refreshWP81Tiles() {
        val shell = wp81Shell ?: return
        wp81TileHost.refreshColors()
        // Set before the tiles are built, so each one is born knowing which mark to wear.
        shell.startScreen.countsEnabled = themeManager.getWP81TileCounts()
        shell.startScreen.columns = themeManager.getWP81Columns()
        buildWP81FolderPreviews()
        shell.startScreen.setTiles(
            buildWP81Tiles(),
            liveWidget = { tile -> wp81LiveWidgetContent(tile) },
            widgetGlyphs = { tile -> wp81WidgetGlyphFor(tile) },
            widgetBacks = { tile -> wp81LiveWidgetBack(tile) },
            tileColors = { tile -> wp81ColorFor(tile) }
        ) { tile -> wp81GlyphFor(tile) }
        // The entrance is for arriving at Start, not for keeping it up to date. Every
        // rebuild replayed it - pinning an app, renaming a tile, a package changing
        // underneath - and every one of those blanked the whole wall and faded it back in.
        //
        // Coming home from another app is the worst of them: the system shows the launcher
        // as it left it, the activity is rebuilt behind that, and the tiles the user is
        // already looking at drop to nothing and stagger back. Played once per process, so
        // a genuine cold start still gets it.
        if (!wp81EntrancePlayed) {
            wp81EntrancePlayed = true
            shell.startScreen.playEntrance()
        }
        refreshWP81News()
        refreshWP81Photos()
        refreshWP81People()
        refreshWP81Weather()
        refreshWP81Notifications()
        refreshWP81Media()
    }

    /**
     * Headline and detail for a built-in live widget, or null if the tile is not one.
     *
     * Fed from the caches the Quick Glance widget already maintains, so the tiles say
     * something real rather than animating for its own sake, and cost nothing extra to
     * keep current.
     */
    private fun wp81LiveWidgetContent(tile: rocks.gorjan.gokixp.wp81.Tile): rocks.gorjan.gokixp.wp81.TileView.Reading? =
        wp81TileHost.liveContent(tile) { size -> wp81CalendarSummary(size) }

    /**
     * The date, over the next calendar entry - or a friendly empty state when there is
     * nothing on (or no permission to look).
     *
     * The day of the month is the reading; the weekday and the month stand beside it. The
     * narrowest strip abbreviates the weekday, which is otherwise wider than the room
     * beside a number.
     */
    private fun wp81CalendarSummary(size: rocks.gorjan.gokixp.wp81.TileSize): rocks.gorjan.gokixp.wp81.TileView.Reading =
        wp81TileHost.calendarSummary(
            size,
            wp81NextCalendarEvent?.let { "${it.first}\n${wp81EventWhen(it.second)}" }
        )

    /**
     * How many appointments tomorrow holds, and the first of them.
     *
     * Its own query rather than the Quick Glance provider's: that one exists to answer
     * "what is next", which is a different question and never looks past the event it
     * finds. Instances rather than Events, so a weekly meeting counts once for each time
     * it actually occurs rather than once for the series.
     *
     * Runs off the main thread - a calendar query walks every provider on the phone.
     */
    /**
     * When an appointment starts, in the terms it is worth saying it in.
     *
     * Close to, a clock time is arithmetic the reader has to do: "14:20" is only useful
     * once you have worked out what it is now, and the answer they wanted was how long
     * they have. Past a couple of hours that stops being true - "in 5 hours 40 minutes" is
     * a number nobody holds on to - and the time of day is the better answer again.
     */
    private fun wp81EventWhen(starts: Long): String {
        val locale = java.util.Locale.getDefault()
        val minutes = Math.ceil((starts - System.currentTimeMillis()) / 60000.0).toLong()
        // Already running: it was found because it has not finished, not because it has
        // not begun.
        if (minutes <= 0L) return "now"
        if (minutes < 60L) return if (minutes == 1L) "in 1 minute" else "in $minutes minutes"
        if (minutes <= RELATIVE_EVENT_MINUTES) {
            val hours = minutes / 60
            val rest = minutes % 60
            val said = if (hours == 1L) "in 1 hour" else "in $hours hours"
            return when (rest) {
                0L -> said
                1L -> "$said 1 minute"
                else -> "$said $rest minutes"
            }
        }
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(starts))
    }

    /**
     * The next appointment left today, and when it starts.
     *
     * Its own query rather than the Quick Glance provider's, which answers "what is next"
     * in words - "in 20 minutes" - and looks no further than six hours ahead. A tile says
     * the time itself and says it about the whole day.
     */
    private fun refreshWP81TodayEvent() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            wp81NextCalendarEvent = null
            return
        }
        Thread {
            val found = try {
                queryWP81NextEvent()
            } catch (e: Exception) {
                Log.w("MainActivity", "WP8.1: could not read today's calendar", e)
                null
            }
            runOnUiThread {
                wp81NextCalendarEvent = found
                wp81Shell?.startScreen?.let { start ->
                    start.tiles()
                        .firstOrNull { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR }
                        ?.let { tile ->
                            wp81LiveWidgetContent(tile)?.let { reading ->
                                start.setLiveWidgetContent(tile.id, reading)
                            }
                        }
                }
            }
        }.start()
    }

    /** The first appointment still to come today: its name, and when it begins. */
    private fun queryWP81NextEvent(): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        val end = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.timeInMillis.toString())
            .build()

        contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY
            ),
            null,
            null,
            "${android.provider.CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                // All-day entries are not appointments - see queryWP81TomorrowEvents.
                if (cursor.getInt(2) != 0) continue
                val title = cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                return title to cursor.getLong(1)
            }
        }
        return null
    }

    private fun refreshWP81TomorrowEvents() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            wp81TomorrowEvents = 0 to null
            return
        }
        Thread {
            val found = try {
                queryWP81TomorrowEvents()
            } catch (e: Exception) {
                Log.w("MainActivity", "WP8.1: could not read tomorrow's calendar", e)
                0 to null
            }
            runOnUiThread { wp81TomorrowEvents = found }
        }.start()
    }

    private fun queryWP81TomorrowEvents(): Pair<Int, String?> {
        val start = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.timeInMillis.toString())
            .appendPath(end.timeInMillis.toString())
            .build()

        var count = 0
        var first: String? = null
        contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY
            ),
            null,
            null,
            "${android.provider.CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                // All-day entries are not appointments: birthdays, holidays and the
                // fortnight somebody blocked out as leave would have the tile reporting a
                // full day when there is nothing to actually be anywhere for. They also
                // begin at UTC midnight rather than local, so a day's worth of them lands
                // in whichever day that happens to be.
                //
                // Read from the cursor rather than filtered in the query: not every
                // provider honours a selection on Instances, and this cannot be argued
                // with.
                if (cursor.getInt(2) != 0) continue
                count++
                if (first == null) {
                    first = cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        return count to first
    }

    /**
     * Opens the phone's own clock app.
     *
     * Deliberately not [openClockApp], which is the launcher's fake Date and Time
     * Properties window: on a phone shell, tapping the clock should land in the real
     * clock. Falls back through the alarm intent, then the launch intent of whichever
     * clock package is installed.
     */
    private fun openPhoneClockApp() {
        val candidates = listOf(
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALENDAR")
        )
        for (intent in candidates) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    Log.w("MainActivity", "Clock intent failed: $intent", e)
                }
            }
        }
        val fallbackPackages = listOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        for (pkg in fallbackPackages) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(it)
                return
            }
        }
        Log.w("MainActivity", "No clock app found on this device")
        showNotification("Clock", "No clock app is installed")
    }

    /**
     * Temperature and conditions for the weather tile.
     *
     * Reads the cache the taskbar readout already fills, so the tile costs no extra
     * network calls and shows the same reading the other themes do.
     */
    private fun wp81WeatherSummary(size: rocks.gorjan.gokixp.wp81.TileSize): Pair<String, String?> {
        val cached = getCachedWeatherJson()
            ?: return "--" to "Weather \u00b7 tap to refresh"
        return try {
            val current = cached.getJSONObject("current")
            val temperature = kotlin.math.round(current.getDouble("temperature_2m")).toInt()
            val code = current.optInt("weather_code", -1)
            // The 1x1 drops the unit. C or F is a footnote next to the number itself, and
            // on the one tile where the reading has to carry the whole face, the degree
            // sign already says what kind of number it is.
            val small = size == rocks.gorjan.gokixp.wp81.TileSize.SMALL
            val unit = if (small) "" else getWeatherUnit()
            // The condition is a phrase, and the 1x1 has room for a word - so there the
            // caption says which day this is instead, which is the thing the reverse is
            // about to contradict. The mark in the corner carries the condition either way.
            val caption = if (small) "today" else wp81WeatherCondition(code)
            "$temperature\u00b0$unit" to caption
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: could not read cached weather", e)
            "--" to "Weather \u00b7 tap to refresh"
        }
    }

    /**
     * The reverse of a live widget: the same reading told another way, or more of it.
     *
     * Returning null leaves a widget one-sided, and turning it over spins it back to
     * itself rather than doing nothing.
     */
    private fun wp81LiveWidgetBack(tile: rocks.gorjan.gokixp.wp81.Tile): rocks.gorjan.gokixp.wp81.TileView.Reading? {
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        return when (tile.kind) {
            // Front says the time; the back says which day it is. The full date is a
            // caption a 1x1 cannot hold, so there the weekday stands alone.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> rocks.gorjan.gokixp.wp81.TileView.Reading(
                number = java.text.SimpleDateFormat("EEEE", locale).format(now).lowercase(locale),
                caption = java.text.SimpleDateFormat("d MMMM yyyy", locale).format(now)
                    .lowercase(locale)
                    .takeUnless { tile.size == rocks.gorjan.gokixp.wp81.TileSize.SMALL }
            )

            // The weather turns through three faces of its own; it has no reverse.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null

            // One-sided: the index is on the front and the mark in the corner says which
            // index it is. Nothing is held back for a reverse.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null

            // Today on the front, tomorrow on the reverse - the one thing a calendar can
            // tell you that looking at it cannot. Counted rather than listed: a tile has
            // room for one appointment, and which one that is matters less than knowing
            // whether tomorrow is empty or full.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> {
                val (count, first) = wp81TomorrowEvents
                val small = tile.size == rocks.gorjan.gokixp.wp81.TileSize.SMALL
                val tomorrow = java.util.Calendar.getInstance()
                    .apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                // Tomorrow's date, set out exactly as today's is, with what tomorrow
                // holds above it: the first appointment by name, and under it how many
                // follow. Named rather than counted, because the first one is what decides
                // what kind of day it is - and the count only appears when there is one to
                // give, since a second line saying "and 0 more" is a line saying nothing.
                val weekdayPattern =
                    if (tile.size == rocks.gorjan.gokixp.wp81.TileSize.WIDE) "EEEE" else "EEE"
                val weekday = java.text.SimpleDateFormat(weekdayPattern, locale)
                    .format(tomorrow.time).lowercase(locale)
                val caption = when {
                    small -> null
                    count == 0 || first.isNullOrBlank() -> "no events"
                    count > 1 -> "$first\nand ${count - 1} more"
                    else -> first
                }
                rocks.gorjan.gokixp.wp81.TileView.Reading(
                    number = tomorrow.get(java.util.Calendar.DAY_OF_MONTH).toString(),
                    caption = caption,
                    aside = weekday
                )
            }

            else -> null
        }
    }

    /**
     * Tomorrow's forecast, for the weather tile's reverse - or null before the cache has a
     * daily block in it, which leaves the tile one-sided rather than turning over to
     * nothing.
     *
     * The reading itself is the headline, as on the front; what makes it tomorrow's is
     * said underneath, because a second temperature with no label is just a number that
     * disagrees with the one before it.
     */
    private fun wp81TomorrowSummary(size: rocks.gorjan.gokixp.wp81.TileSize): Pair<String, String?>? {
        val cached = getCachedWeatherJson() ?: return null
        return try {
            val daily = cached.getJSONObject("daily")
            val highs = daily.getJSONArray("temperature_2m_max")
            val codes = daily.getJSONArray("weather_code")
            // Index 1 is tomorrow. A response that only carries today has nothing to show.
            if (highs.length() < 2 || codes.length() < 2) return null

            val high = kotlin.math.round(highs.getDouble(1)).toInt()
            val code = codes.getInt(1)
            val small = size == rocks.gorjan.gokixp.wp81.TileSize.SMALL
            val unit = if (small) "" else getWeatherUnit()
            val condition = wp81WeatherCondition(code)
            // The 1x1 has one line and the corner mark already carries the condition, so
            // there the word "tomorrow" is the only thing worth the second line.
            val detail = if (small) "tomorrow" else "tomorrow \u00b7 ${condition.lowercase()}"
            "$high\u00b0$unit" to detail
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: no forecast in the cached weather", e)
            null
        }
    }

    /**
     * What the weather tile turns through: what it is doing now, and the highs either side
     * of tonight.
     *
     * Three readings of the same kind, each labelled - a temperature with no label is a
     * number, and three of them in turn without labels is three numbers that appear to
     * disagree. The 1x1 shortens the labels rather than dropping them: "max tomorrow" does
     * not fit across it, and "tomorrow" says the necessary half.
     *
     * Falls back to the current reading alone when the forecast has not arrived, which
     * leaves the tile still rather than turning between a number and two blanks.
     */
    private fun wp81WeatherFaces(
        size: rocks.gorjan.gokixp.wp81.TileSize
    ): List<rocks.gorjan.gokixp.wp81.TileView.LiveFace> = wp81TileHost.weatherFaces(size)

    /** Hands the weather tile the run of faces it turns through. */
    private fun refreshWP81Weather() {
        val shell = wp81Shell ?: return
        val tile = shell.startScreen.tiles()
            .firstOrNull { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER }
            ?: return
        val faces = wp81WeatherFaces(tile.size)
        if (faces.isEmpty()) return
        shell.startScreen.setLiveWidgetRotation(
            tile.id, faces, rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING)
    }

    /** The WMO code from the cached reading, or -1 if there is none. */
    private fun wp81CurrentWeatherCode(): Int = try {
        getCachedWeatherJson()?.getJSONObject("current")?.optInt("weather_code", -1) ?: -1
    } catch (e: Exception) {
        -1
    }

    /**
     * The corner mark a live widget carries, if any.
     *
     * Only the News tile, now. Every other widget is a reading with a caption that names
     * it in words - "sunny", "good aqi", an appointment - and a mark repeating that is one
     * more thing on a tile whose whole job is to be read at a glance.
     */
    private fun wp81WidgetGlyphFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): Pair<Int?, Int?> = when (tile.kind) {
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null to null
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null to null
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> null to null
        // The mark stays up on both faces: every one of them is a story, and a tile of
        // nothing but text needs something to say what it is.
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS ->
            R.drawable.wp81_glyph_news to R.drawable.wp81_glyph_news
        else -> null to null
    }

    /**
     * WMO weather code to the one word a tile has room for.
     *
     * Nine of them for a hundred codes, so the grouping is coarse on purpose: drizzle,
     * rain and showers are all rain to somebody deciding whether to take a coat, and the
     * distinctions worth a word of their own are the ones that change the answer - a
     * storm, thunder, hail on the way down.
     */
    private fun wp81WeatherWord(code: Int): String? = wp81TileHost.weatherWord(code)

    /**
     * WMO weather code to plain words.
     *
     * Grouped rather than enumerated one-for-one: a tile has room for "light snow", not for
     * distinguishing dense freezing drizzle from moderate freezing drizzle.
     */
    private fun wp81WeatherCondition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Weather"
    }

    /** Opens whatever the user set as their weather app, or refreshes if none is set. */
    private fun openWeatherAppOrRefresh() {
        val packageName = getWeatherApp()
        if (packageName != null) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(it)
                return
            }
        }
        refreshWeatherIfNeeded()
        showNotification("Weather", "Refreshing\u2026")
    }

    /** Matches the bands the desktop AQI readout colours by. */
    private fun wp81AqiLabel(aqi: Int): String = wp81TileHost.aqiLabel(aqi)

    /** Resolves the art for one tile: fixed glyph for built-ins, provider for real apps. */
    private fun wp81GlyphFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        // An icon the user chose outranks everything derived - the app's themed monochrome
        // layer, its notification silhouette, and the built-in glyphs for system tiles.
        // Without this check the provider preferred an app's Android 13 themed icon, and a
        // custom icon simply never appeared for any app that ships one.
        if (hasCustomIcon(tile.packageName)) {
            getAppIcon(tile.packageName)?.let { drawable ->
                return rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor(
                    drawable,
                    wp81IconProvider.ratioFor("custom:${tile.packageName}", drawable)
                )
            }
        }

        val fixed = when (tile.kind) {
            rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER -> R.drawable.wp81_glyph_folder
            rocks.gorjan.gokixp.wp81.Tile.Kind.MY_COMPUTER -> R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.RECYCLE_BIN -> R.drawable.wp81_glyph_recycle
            rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT -> R.drawable.wp81_glyph_ie
            rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP ->
                wp81SystemGlyphs[tile.packageName] ?: R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.APP -> null
            rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS -> R.drawable.wp81_glyph_settings
            rocks.gorjan.gokixp.wp81.Tile.Kind.WELCOME -> R.drawable.wp81_glyph_welcome
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null
            // Live widgets render their content directly; there is no glyph to resolve.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null
        }
        if (fixed != null) {
            val drawable = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, fixed) ?: return null
            return rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome(
                drawable,
                wp81IconProvider.ratioFor("res:$fixed", drawable)
            )
        }
        return wp81IconProvider.glyphFor(tile.packageName, getAppIcon(tile.packageName))
    }

    private fun launchWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val icon = desktopIcons.firstOrNull { it.id == tile.id }
        when (tile.kind) {
            // Folders are opened in place by the wall itself - see onFolderOpened - so a
            // launch only reaches here for one tapped somewhere that has no wall to part:
            // inside another folder's band.
            rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER -> openWP81Folder(tile)
            rocks.gorjan.gokixp.wp81.Tile.Kind.MY_COMPUTER -> {
                val view = rocks.gorjan.gokixp.apps.explorer.MyComputerView(this)
                icon?.let { view.setDesktopIcon(it) }
                openMyComputer(view)
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.RECYCLE_BIN -> {
                // No-op, matching the desktop: the Recycle Bin has no window of its own
                // there either - it is a drop target with a context menu. The tile is kept
                // so the migrated Start screen still mirrors what was on the desktop.
                Log.d("MainActivity", "WP8.1: Recycle Bin tile tapped; no window to open")
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT -> openUrlShortcut(icon?.targetUrl)
            rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP -> launchSystemApp(tile.packageName)
            rocks.gorjan.gokixp.wp81.Tile.Kind.APP -> {
                packageManager.getLaunchIntentForPackage(tile.packageName)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> openPhoneClockApp()
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> openCalendarApp()
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> openWeatherAppOrRefresh()
            // The reader, not the story. A tile shows one headline at a time and tapping
            // it is a request for the rest of them; the story on the face is one tap
            // further in, where the whole front page is on offer.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS -> showNewsDialog()

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS -> openWP81Photos()

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE -> openWP81People()

            rocks.gorjan.gokixp.wp81.Tile.Kind.WELCOME -> launchSystemApp("system.welcome")

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> {
                // Tapping doubles as the opt-in, since the desktop checkbox that normally
                // enables air quality lives inside Settings. Enabling it also asks for a
                // reading, so the tile has something to show on the way back.
                if (!isShowAqiEnabled()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit { putBoolean(KEY_SHOW_AQI, true) }
                    refreshAqiData()
                }
                // Then AirCare itself, which is where the taskbar indicator goes on every
                // other theme - the tile is that indicator, and should not behave differently
                // for being square.
                handleAqiTap()
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS -> openWP81Settings()
        }
    }

    /**
     * Commands for a Start screen tile.
     *
     * Deliberately shorter than the desktop's icon menu: "Send to Desktop", "Set as Swipe
     * Right App" and "Change Icon" are desktop notions with nowhere to land here.
     *
     * Resizing and reordering are absent too - they are direct manipulations now, on the
     * tile's own bottom-right and top-right handles, which beats picking a verb from a
     * list and then doing the gesture anyway.
     */
    private fun wp81TileMenu(
        tile: rocks.gorjan.gokixp.wp81.Tile,
        inFolder: Boolean
    ): List<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item> {
        val shell = wp81Shell
        val items = mutableListOf<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item>()

        // Taking a tile out of a folder deletes it; on Start the same act is "unpin".
        if (inFolder && !tile.kind.isBuiltIn) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("remove from folder") {
                removeSelectedFromWP81Folder()
            })
        }

        // Both of these used to have a key on the strip, which is now the colour key.
        // Settings is excluded on purpose: it is the only route back to this screen.
        if (tile.kind.isBuiltIn && tile.kind != rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("hide tile") { hideWP81Tile(tile.id) })
        }

        if (!tile.kind.isBuiltIn && !inFolder) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("unpin from start") {
                shell?.startScreen?.unpinTile(tile)
            })
        }

        val icon = desktopIcons.firstOrNull { it.id == tile.id }

        if (!tile.kind.isBuiltIn) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("rename") { renameWP81Tile(tile) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("change icon") { changeWP81TileIcon(tile) })
        }

        // A web shortcut exists only on the Start screen, so removing it *is* deleting it -
        // "unpin" would understate what happens.
        if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT && icon != null) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("delete") {
                desktopIcons.removeAll { it.id == icon.id }
                saveDesktopIcons()
                refreshWP81Tiles()
            })
        }

        // Only real installed apps can be uninstalled or inspected.
        if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.APP) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("app info") { openAppInfo(tile.packageName) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("uninstall") {
                uninstallApp(AppInfo(tile.label, tile.packageName, icon = wp81BlankIcon()))
            })
        }

        return items
    }

    /** Commands for a row in the app list. */
    private fun wp81AppMenu(app: AppInfo): List<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item> {
        val shell = wp81Shell
        val items = mutableListOf<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item>()

        val pinnedIcon = desktopIcons.firstOrNull {
            it.packageName == app.packageName && it.parentFolderId == null
        }
        if (pinnedIcon != null) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("unpin from start") {
                desktopIcons.removeAll { it.id == pinnedIcon.id }
                saveDesktopIcons()
                refreshWP81Tiles()
                showNotification("Unpinned", app.name)
            })
        } else {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("pin to start") { pinWP81Tile(app) })
        }

        // Reuses the launcher's existing hidden-apps set, so a app hidden here is hidden
        // in the desktop themes' start menu too.
        val hidden = isAppHidden(app.packageName)
        items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(if (hidden) "unhide" else "hide from list") {
            toggleHiddenApp(app.packageName)
            refreshWP81AppList()
        })

        if (!isSystemApp(app.packageName)) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("app info") { openAppInfo(app.packageName) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("uninstall") { uninstallApp(app) })
        }

        return items
    }

    /**
     * Renames a tile, through the Metro prompt rather than the Vista rename window.
     *
     * Writes to the same customNameMappings the desktop themes use, so a rename here shows
     * up on the desktop too rather than being a WP8.1-only alias.
     */
    private fun renameWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val current = getCustomOrOriginalName(icon.packageName, icon.name)
        shell.inputDialog.show("rename", current) { newName ->
            if (newName.isEmpty()) customNameMappings.remove(icon.packageName)
            else customNameMappings[icon.packageName] = newName
            saveCustomNameMappings()
            refreshWP81Tiles()
        }
    }

    /**
     * Opens the Metro icon picker for a tile.
     *
     * Icons are decoded in batches on a background thread and handed over as they arrive:
     * the bundled sets run to several hundred files, and decoding them before showing the
     * page would stall it for seconds.
     */
    private fun changeWP81TileIcon(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val picker = shell.iconPicker

        picker.show(tile.label)
        picker.onPicked = { path ->
            applyWP81CustomIcon(icon.packageName, path)
            picker.dismiss()
        }
        picker.onResetToDefault = {
            applyWP81CustomIcon(icon.packageName, "default")
            picker.dismiss()
        }
        picker.onBrowse = {
            setPendingImagePick(PICK_TARGET_WP81_ICON_PREFIX + icon.packageName)
            imagePickerLauncher.launch("image/*")
        }

        loadWP81IconChoices(picker)
    }

    // ---- pending image pick -----------------------------------------------------------

    private fun setPendingImagePick(target: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_PENDING_IMAGE_PICK, target)
        }
    }

    /** Reads and clears the pending target, so a stale one cannot claim a later pick. */
    private fun consumePendingImagePick(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val target = prefs.getString(KEY_PENDING_IMAGE_PICK, null)
        if (target != null) prefs.edit { remove(KEY_PENDING_IMAGE_PICK) }
        return target
    }

    /**
     * Applies an image picked for the Start background.
     *
     * Copied into app storage rather than stored as a content URI: the picker is
     * GetContent, which grants read access for this session only, so a remembered URI
     * would stop resolving after a reboot.
     */
    private fun applyPickedWP81Background(uri: Uri) {
        val stored = copyWP81BackgroundLocally(uri)
        if (stored == null) {
            showNotification("Start background", "That image could not be used")
            return
        }
        themeManager.setWP81StartBackground(stored)
        themeManager.setWP81StartBackgroundFocusX(0.5f)
        applyWP81StartBackground()
        refreshWP81BackgroundControls()
        // Straight onto the same command list a bundled wallpaper answers a hold with.
        // A photo the user went looking for is the one most likely to be wanted on the
        // phone as well, and there is nothing to hold here - it was chosen in a picker
        // that has already closed. Posted so the list is placed against a laid-out shell.
        wp81Shell?.let { shell ->
            shell.post { showWP81WallpaperMenu(stored, shell.height * 0.4f) }
        }
    }

    /** Applies an image picked as a tile icon for [packageName]. */
    private fun applyPickedWP81Icon(packageName: String, uri: Uri) {
        val stored = importCustomIconFromUri(uri)
        if (stored == null) {
            showNotification("Change icon", "That image could not be used")
            return
        }
        applyWP81CustomIcon(packageName, stored)
        wp81Shell?.iconPicker?.dismiss()
    }

    /** Streams the current theme's icon set into the picker, a batch at a time. */
    private fun loadWP81IconChoices(picker: rocks.gorjan.gokixp.wp81.WP81IconPicker) {
        // The phone's own set, which is not the set its window chrome would suggest: the
        // desktop icons Vista's key holds are drawn for a desktop, in colour, with
        // shadows, and none of them is what a Windows Phone tile should be offered.
        val folders = listOf(WP81_ICON_FOLDER, "custom_icons_programs")
        Thread {
            for (folder in folders) {
                val names = try {
                    assets.list(folder)?.sorted().orEmpty()
                } catch (e: Exception) {
                    Log.w("MainActivity", "WP8.1: cannot list $folder", e)
                    emptyList()
                }
                val batch = mutableListOf<rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice>()
                for (name in names) {
                    if (!name.matches(".*\\.(svg|png|jpg|jpeg|webp)$".toRegex(RegexOption.IGNORE_CASE))) continue
                    val path = "$folder/$name"
                    val drawable = try {
                        loadIconFromPath(path)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    batch.add(rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice(path, drawable))
                    if (batch.size >= WP81_ICON_BATCH) {
                        val chunk = batch.toList()
                        batch.clear()
                        runOnUiThread { if (picker.isShowing()) picker.addChoices(chunk) }
                    }
                }
                if (batch.isNotEmpty()) {
                    val chunk = batch.toList()
                    runOnUiThread { if (picker.isShowing()) picker.addChoices(chunk) }
                }
            }
        }.start()
    }

    /** Commits a chosen icon through the same mappings the desktop themes read. */
    private fun applyWP81CustomIcon(packageName: String, path: String) {
        if (path == "default") customIconMappings.remove(packageName)
        else customIconMappings[packageName] = path
        saveCustomIconMappings()
        pruneUnusedImportedIcons()
        invalidateIconCache(packageName)
        // The artwork changed, so its measured proportions have to go too, or the new icon
        // is drawn scaled for the old one.
        wp81IconProvider.invalidate(packageName)
        refreshWP81Tiles()
    }

    /** Hides a built-in tile from Start. Its position is kept for when it comes back. */
    private fun hideWP81Tile(tileId: String) {
        themeManager.setWP81HiddenTiles(themeManager.getWP81HiddenTiles() + tileId)
        wp81Shell?.exitEditModeEverywhere()
        refreshWP81Tiles()
        showNotification("Tile hidden", "Bring it back from the hidden list while editing")
    }

    private fun restoreWP81Tile(tileId: String) {
        themeManager.setWP81HiddenTiles(themeManager.getWP81HiddenTiles() - tileId)
        refreshWP81Tiles()
    }

    /**
     * The top-right edit handle's job, which depends on whose tile it is.
     *
     * A tile the user pinned comes off Start; one the shell provides is hidden instead,
     * because unpinning it would only last until the next refresh rebuilt it.
     */
    private fun unpinOrHideWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        if (tile.kind.isBuiltIn) hideWP81Tile(tile.id) else shell.startScreen.unpinTile(tile)
    }

    /** Human-readable names for the hideable built-ins, for the restore list. */
    private fun wp81BuiltInLabel(id: String): String = when (id) {
        WP81_WIDGET_CLOCK -> "Clock"
        WP81_WIDGET_WEATHER -> "Weather"
        WP81_WIDGET_AQI -> "Air quality"
        WP81_WIDGET_CALENDAR -> "Calendar"
        WP81_WIDGET_NEWS -> "News"
        WP81_WIDGET_PHOTOS -> "Photos"
        WP81_WIDGET_PEOPLE -> "People"
        "system.welcome" -> "Welcome"
        WP81_WIDGET_SETTINGS -> "Settings"
        else -> id
    }

    /** Lists what has been hidden, so any of it can be put back. */
    private fun showWP81HiddenTiles() {
        val shell = wp81Shell ?: return
        val hidden = themeManager.getWP81HiddenTiles()
        if (hidden.isEmpty()) return
        val items = hidden.sorted().map { id ->
            rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(wp81BuiltInLabel(id)) { restoreWP81Tile(id) }
        }
        shell.contextMenu.show("hidden tiles", items, shell.height * 0.4f)
    }

    /** Placeholder icon for the AppInfo that [uninstallApp] only reads the package from. */
    private fun wp81BlankIcon(): Drawable =
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

    /**
     * Opens the WP8.1 settings page and populates its wallpaper strip.
     *
     * Wallpaper drawables are decoded off the main thread - there are a few dozen bundled
     * and decoding them inline visibly stutters the page-in.
     */
    private fun openWP81Settings() {
        val shell = wp81Shell ?: return
        shell.openSettings()
        shell.settingsPage.setDefaultBrowser(isDefaultBrowser())
        refreshDefaultBrowser = { shell.settingsPage.setDefaultBrowser(isDefaultBrowser()) }
        Thread {
            val items = try {
                loadWallpapers().mapNotNull { item ->
                    item.filePath?.let { path -> path to item.drawable }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to load wallpapers", e)
                emptyList()
            }
            runOnUiThread {
                shell.settingsPage.setWallpapers(items, themeManager.getWP81StartBackground())
                refreshWP81BackgroundControls()
            }
        }.start()
    }

    private fun wireWP81Settings(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        shell.settingsPage.onBack = { shell.closeSettings() }
        shell.settingsPage.onAccentPicked = { color ->
            commitWP81Appearance(color, themeManager.isWP81Dark())
        }
        shell.settingsPage.onDarkPicked = { dark ->
            commitWP81Appearance(themeManager.getWP81Accent(), dark)
        }
        shell.settingsPage.onBackgroundPicked = { path ->
            themeManager.setWP81StartBackground(path)
            // A fresh pick starts centred; the preview strip is how it gets reframed.
            themeManager.setWP81StartBackgroundFocusX(0.5f)
            applyWP81StartBackground()
            refreshWP81BackgroundControls()
        }
        shell.settingsPage.onBlurChanged = { amount ->
            themeManager.setWP81StartBackgroundBlur(amount)
            refreshWP81Blur()
        }
        shell.settingsPage.onDriftChanged = { enabled ->
            themeManager.setWP81StartBackgroundDrift(enabled)
            shell.setBackgroundDrift(enabled)
        }
        shell.settingsPage.onHideTileColorsChanged = { hidden ->
            themeManager.setWP81HideTileColors(hidden)
            applyWP81TileColors()
        }
        shell.settingsPage.onTileCountsChanged = { enabled ->
            themeManager.setWP81TileCounts(enabled)
            applyWP81TileCounts()
        }
        shell.settingsPage.onColumnsPicked = { columns ->
            themeManager.setWP81Columns(columns)
            shell.startScreen.columns = columns
            shell.folderPage.contents.columns = columns
        }
        // Written to the same key Display Properties uses, so the answer is the launcher's
        // rather than each shell's. See openUrlShortcut.
        shell.settingsPage.setOpenLinksInIe(isOpenUrlsInIeEnabled())
        shell.settingsPage.onOpenLinksInIeChanged = { enabled ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(KEY_OPEN_URLS_IN_IE, enabled)
            }
        }
        // Where the rest of the phone's links go. Read from the system rather than kept
        // here - see isDefaultBrowser - and asked again when the user comes back from
        // Android's own prompt.
        shell.settingsPage.setDefaultBrowser(isDefaultBrowser())
        shell.settingsPage.onDefaultBrowser = { requestDefaultBrowser() }
        shell.settingsPage.setLauncherThemes(
            AppTheme.all().map { it.toString() },
            themeManager.getSelectedTheme().toString()
        )
        shell.settingsPage.onThemePicked = { name ->
            // Same path the desktop Display Properties uses: persist, then applyTheme(),
            // which plays the grayscale transition and recreates the activity.
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putString("selected_theme", name)
                if (AppTheme.fromString(name) !is AppTheme.WindowsClassic) {
                    putString(ThemeManager.KEY_PLUS95_THEME, ThemeManager.PLUS95_DEFAULT)
                }
            }
            applyTheme(name)
        }
        shell.settingsPage.onBrowse = {
            setPendingImagePick(PICK_TARGET_WP81_BACKGROUND)
            imagePickerLauncher.launch("image/*")
        }
        shell.settingsPage.onWallpaperLongPress = { source, anchorY ->
            showWP81WallpaperMenu(source, anchorY)
        }
    }

    /**
     * Where a wallpaper can go besides Start.
     *
     * A tap on one of these dresses the Start screen, which is the shell's own business
     * and all it used to be able to do. The phone underneath has two more walls of its
     * own - the launcher it falls back to and the lock screen - and the picture the user
     * is looking at is as good for those as for this one. The desktop themes have offered
     * this since they had a wallpaper picker; this is the same offer in the shape WP8.1
     * asks a question, which is a command list under the thing being asked about.
     *
     * Start itself is not on the list. Tapping the wallpaper already does that, and a
     * command list that repeats the tap is a list with a wasted line on it.
     */
    private fun showWP81WallpaperMenu(source: String, anchorY: Float) {
        val shell = wp81Shell ?: return
        shell.contextMenu.show(
            "wallpaper",
            listOf(
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to lock screen") {
                    applyWP81WallpaperToDevice(source, system = false, lock = true)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to system wallpaper") {
                    applyWP81WallpaperToDevice(source, system = true, lock = false)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to both") {
                    applyWP81WallpaperToDevice(source, system = true, lock = true)
                },
                // Nothing to undo - the list has done nothing yet - so this is the row that
                // closes it. WP8.1 put one on every command list that could be opened by
                // accident, and a hold on a wallpaper is exactly that kind of press.
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("cancel") { }
            ),
            anchorY
        )
    }

    /**
     * Puts a wallpaper on the phone itself: the system wall, the lock screen, or both.
     *
     * Off the main thread, and at the size the wallpaper is actually drawn at rather than
     * the size Start needs. The Start background is downsampled to the screen and kept in
     * 565 - it sits behind tiles under a blur, where neither costs anything - but a lock
     * screen is the picture itself, and a gradient in 565 is a gradient in visible bands.
     */
    private fun applyWP81WallpaperToDevice(source: String, system: Boolean, lock: Boolean) {
        if (!system && !lock) return
        Thread {
            val bitmap = decodeWallpaperFullSize(source)
            if (bitmap == null) {
                runOnUiThread {
                    showNotification("Wallpaper", "That image could not be used")
                }
                return@Thread
            }
            val flags = (if (system) android.app.WallpaperManager.FLAG_SYSTEM else 0) or
                (if (lock) android.app.WallpaperManager.FLAG_LOCK else 0)
            val done = try {
                android.app.WallpaperManager.getInstance(this)
                    .setBitmap(bitmap, null, true, flags)
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to set device wallpaper", e)
                false
            }
            runOnUiThread {
                val where = when {
                    system && lock -> "Applied to lock screen and system wallpaper"
                    system -> "Applied to system wallpaper"
                    else -> "Applied to lock screen"
                }
                showNotification(
                    "Wallpaper",
                    if (done) where else "The wallpaper could not be changed"
                )
            }
        }.start()
    }

    /**
     * Decodes a wallpaper at its own size, from a bundled asset or a picked image.
     *
     * Sampled down only when the image is larger than the wall it is going on - the
     * wallpaper service's own desired size, doubled, which is the room a phone gives a
     * picture to be panned across. A bundled wallpaper is under that already and is
     * decoded whole.
     */
    private fun decodeWallpaperFullSize(source: String): Bitmap? = try {
        fun open(): java.io.InputStream? =
            if (source.startsWith("content://") || source.startsWith("file://")) {
                contentResolver.openInputStream(source.toUri())
            } else {
                assets.open(source)
            }

        val manager = android.app.WallpaperManager.getInstance(this)
        val metrics = resources.displayMetrics
        val targetW = maxOf(manager.desiredMinimumWidth, metrics.widthPixels * 2)
        val targetH = maxOf(manager.desiredMinimumHeight, metrics.heightPixels * 2)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: failed to decode wallpaper $source", e)
        null
    }

    /**
     * Copies a picked image into app storage and returns a file:// path for it, or null.
     * One slot, overwritten each time - only the current background is ever needed.
     */
    private fun copyWP81BackgroundLocally(uri: Uri): String? = try {
        val target = java.io.File(filesDir, "wp81_start_background.img")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() > 0) Uri.fromFile(target).toString() else null
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: could not copy picked background", e)
        null
    }

    /** Cached Start background at full sharpness, so a drag never re-decodes. */
    private var wp81BackgroundBitmap: Bitmap? = null

    /** The blurred derivative actually handed to the tiles, keyed by the blur it was made at. */
    private var wp81BlurredBackground: Bitmap? = null
    private var wp81BlurredAmount = -1f

    /**
     * Where the built-in tiles sit, by tile id.
     *
     * The widgets and Settings have no DesktopIcon to hang a position on, so unlike the
     * user's tiles their placement lives here. Seeded once, the first time the theme is
     * used, and thereafter owned by the user exactly like any other tile - rebuilding them
     * at fixed positions on every refresh is what made them jump back to the top whenever
     * an icon changed or the user came home.
     */
    private fun loadWP81BuiltInPlacements(): MutableMap<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>> =
        wp81TileHost.loadBuiltInPlacements()

    private fun saveWP81BuiltInPlacements(placements: Map<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>>) =
        wp81TileHost.saveBuiltInPlacements(placements)

    /** Guards against a slow blur landing after a newer one has already been requested. */
    private var wp81BlurGeneration = 0

    /**
     * The Start background as the tiles should draw it right now.
     *
     * Returns the sharp original when no blur is set, and otherwise whatever blurred copy
     * is currently cached - which may briefly be a stale one while a new blur is computed.
     */
    private fun wp81DisplayBackground(): Bitmap? {
        val source = wp81BackgroundBitmap ?: return null
        if (themeManager.getWP81StartBackgroundBlur() <= 0.01f) return source
        return wp81BlurredBackground ?: source
    }

    /**
     * Recomputes the blurred background off the main thread and hands it to the shell.
     *
     * Blurring a wallpaper is tens of milliseconds even at a reduced working size, which
     * is far too slow to run per frame while the slider is moving - so the slider stays
     * responsive and the result catches up. Requests are generation-stamped so a slow one
     * cannot overwrite a newer result.
     */
    private fun refreshWP81Blur() {
        val shell = wp81Shell ?: return
        val source = wp81BackgroundBitmap
        val amount = themeManager.getWP81StartBackgroundBlur()
        val focusX = themeManager.getWP81StartBackgroundFocusX()

        if (source == null || amount <= 0.01f) {
            // Supersede anything still blurring. The slider fires all the way down, so
            // sliding to zero leaves a request for the last non-zero amount in flight -
            // and without claiming the generation here it landed a moment later and put
            // the blur straight back, which is why zero was not sharp.
            wp81BlurGeneration++
            wp81BlurredBackground?.recycle()
            wp81BlurredBackground = null
            wp81BlurredAmount = -1f
            shell.setStartBackground(source, focusX)
            return
        }
        if (kotlin.math.abs(amount - wp81BlurredAmount) < BLUR_QUANTISATION &&
            wp81BlurredBackground != null) {
            shell.setStartBackground(wp81BlurredBackground, focusX)
            return
        }

        val generation = ++wp81BlurGeneration
        Thread {
            val blurred = try {
                rocks.gorjan.gokixp.wp81.Blur.apply(source, amount)
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: blur failed", e)
                null
            }
            runOnUiThread {
                if (generation != wp81BlurGeneration || wp81Shell == null) {
                    // Superseded while we were working; drop it rather than flicker back.
                    if (blurred !== source) blurred?.recycle()
                    return@runOnUiThread
                }
                val previous = wp81BlurredBackground
                wp81BlurredBackground = blurred
                wp81BlurredAmount = amount
                shell.setStartBackground(
                    blurred ?: source,
                    themeManager.getWP81StartBackgroundFocusX()
                )
                // Recycled only after the tiles have been handed the replacement.
                if (previous !== source) previous?.recycle()
            }
        }.start()
    }

    /** Offers the settings page's blur slider, for a background that is actually set. */
    /** Pushes the numbers-or-dots setting onto the wall, and onto an open folder page. */
    private fun applyWP81TileCounts() {
        val shell = wp81Shell ?: return
        val enabled = themeManager.getWP81TileCounts()
        shell.startScreen.countsEnabled = enabled
        shell.folderPage.contents.countsEnabled = enabled
    }

    private fun refreshWP81BackgroundControls() {
        val shell = wp81Shell ?: return
        shell.settingsPage.setTileControls(
            themeManager.getWP81TileCounts(), themeManager.getWP81Columns())
        shell.settingsPage.setBackgroundControls(
            wp81BackgroundBitmap != null,
            themeManager.getWP81StartBackgroundBlur(),
            themeManager.getWP81StartBackgroundDrift(),
            themeManager.getWP81HideTileColors()
        )
    }

    /**
     * Loads and applies the Start background photo, or clears it.
     *
     * Accepts either a bundled asset path or a content:// URI from the picker, and
     * downsamples to roughly screen size - a full-resolution camera photo is many times
     * more pixels than the tiles will ever show.
     */
    private fun applyWP81StartBackground() {
        val shell = wp81Shell ?: return
        val source = themeManager.getWP81StartBackground()
        val focusX = themeManager.getWP81StartBackgroundFocusX()
        // Set before the photo: the drift is cut out of the crop, so the crop has to know
        // whether there will be any before it is taken.
        shell.setBackgroundDrift(themeManager.getWP81StartBackgroundDrift())
        if (source == null) {
            wp81BackgroundBitmap = null
            wp81BlurredBackground = null
            wp81BlurredAmount = -1f
            shell.setStartBackground(null, focusX)
            refreshWP81BackgroundControls()
            return
        }
        Thread {
            val bitmap = decodeWP81Background(source)
            runOnUiThread {
                // Likewise: a blur of the photo being replaced must not land on top of
                // the one that replaced it.
                wp81BlurGeneration++
                wp81BackgroundBitmap = bitmap
                wp81BlurredBackground?.recycle()
                wp81BlurredBackground = null
                wp81BlurredAmount = -1f
                shell.setStartBackground(bitmap, focusX)
                refreshWP81Blur()
                refreshWP81BackgroundControls()
            }
        }.start()
    }

    /** Decodes a Start background from an asset path or a content URI, downsampled to fit. */
    private fun decodeWP81Background(source: String): Bitmap? = try {
        val metrics = resources.displayMetrics
        val targetW = metrics.widthPixels
        val targetH = metrics.heightPixels

        fun open(): java.io.InputStream? =
            if (source.startsWith("content://") || source.startsWith("file://")) {
                contentResolver.openInputStream(source.toUri())
            } else {
                assets.open(source)
            }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: failed to load start background $source", e)
        null
    }

    /** The folder page currently open, if any. */
    private var wp81OpenFolderId: String? = null

    /**
     * Sends the user to the app list to choose something to put in the open folder.
     *
     * The list doubles as a picker rather than getting a near-identical screen of its own -
     * see AppListView.onPick.
     */
    private fun addAppToOpenWP81Folder() {
        val shell = wp81Shell ?: return
        val folderId = wp81OpenFolderId ?: return
        shell.openAppPicker(onCancel = { reopenWP81Folder(folderId) }) { app ->
            val existing = desktopIcons.firstOrNull { it.packageName == app.packageName }
            if (existing != null) {
                // Already on Start or in another folder - move it rather than duplicating.
                existing.parentFolderId = folderId
                existing.wp81TileIndex = null
            } else {
                desktopIcons.add(
                    DesktopIcon(
                        name = app.name,
                        packageName = app.packageName,
                        icon = app.icon,
                        x = 0f,
                        y = 0f,
                        type = IconType.APP,
                        parentFolderId = folderId,
                        tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
                    )
                )
            }
            saveDesktopIcons()
            refreshWP81Tiles()
            reopenWP81Folder(folderId)
            showNotification("Added to folder", app.name)
        }
    }

    /**
     * Gives the tiles inside a folder the same behaviour they have on Start.
     *
     * Rewired per folder because the callbacks close over which folder is open - what
     * "unpin" and "reorder" mean depends on it.
     */
    private fun wireWP81FolderContents(shell: rocks.gorjan.gokixp.wp81.WP81Shell, folderId: String) {
        val contents = shell.folderPage.contents
        contents.onLaunch = { child -> launchWP81Tile(child) }
        contents.onEditModeChanged = { shell.refreshNavMode() }
        contents.onTilesChanged = { tiles -> persistWP81FolderTiles(folderId, tiles) }
        // Inside a folder the same handle means "take it out of here", which for a folder
        // is a deletion rather than an unpinning - see removeSelectedFromWP81Folder.
        contents.onTileUnpin = { removeSelectedFromWP81Folder() }
        // Wired before the page is filled, so its tiles are built knowing it too.
        contents.countsEnabled = themeManager.getWP81TileCounts()
        contents.columns = themeManager.getWP81Columns()
        shell.folderPage.onBack = { shell.closeFolder() }
    }

    /**
     * Writes size and order back onto the icons filed inside a folder.
     *
     * Kept separate from [persistWP81Tiles] because the two sweep different sets: that one
     * treats an icon missing from the list as unpinned from Start, which for folder
     * contents would delete every icon that merely lives somewhere else.
     */
    private fun persistWP81FolderTiles(
        folderId: String,
        tiles: List<rocks.gorjan.gokixp.wp81.Tile>
    ) {
        val byId = desktopIcons.associateBy { it.id }
        tiles.sortedBy { it.index }.forEachIndexed { position, tile ->
            byId[tile.id]?.let { icon ->
                icon.wp81TileSize = tile.size.name
                icon.wp81TileIndex = position
            }
        }
        // Removing a tile from inside a folder deletes that icon, matching Start. Scoped to
        // this folder's own children so nothing outside it is touched.
        val keep = tiles.map { it.id }.toSet()
        desktopIcons.removeAll { it.parentFolderId == folderId && it.id !in keep }
        saveDesktopIcons()
    }

    /**
     * Deletes the selected tile from the folder it is in.
     *
     * The icon is removed outright rather than relocated to Start - a folder is where the
     * user put it, and quietly moving it somewhere else means having to go and find it.
     */
    private fun removeSelectedFromWP81Folder() {
        val shell = wp81Shell ?: return
        val tile = shell.selectedTile() ?: return
        val folderId = wp81OpenFolderId ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val name = getCustomOrOriginalName(icon.packageName, icon.name)

        desktopIcons.removeAll { it.id == icon.id }
        saveDesktopIcons()

        shell.exitEditModeEverywhere()
        refreshWP81Tiles()
        reopenWP81Folder(folderId)
        showNotification("Removed", name)
    }

    /** Re-shows a folder page after its contents changed. */
    private fun reopenWP81Folder(folderId: String) {
        val icon = desktopIcons.firstOrNull { it.id == folderId } ?: return
        val tile = rocks.gorjan.gokixp.wp81.Tile(
            id = icon.id,
            label = getCustomOrOriginalName(icon.packageName, icon.name),
            packageName = icon.packageName,
            size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
            index = 0,
            kind = rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER
        )
        openWP81Folder(tile)
    }

    /**
     * Opens a folder as a Metro page rather than a Vista window.
     *
     * A folder is part of the shell, not a program: Solitaire and Internet Explorer are
     * genuine windowed applications and keep their Vista chrome, but a folder browsing its
     * own contents has no reason to leave the phone UI.
     */
    /**
     * Opens a folder into the Start screen, in the gap under its own tile.
     *
     * The contents are built exactly as the folder page built them; what changed is where
     * they are put. Nothing is pushed, nothing is navigated to, and the tile that was
     * tapped stays where it is with the gap hanging off it.
     */
    private fun openWP81FolderInline(folder: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val contents = wp81FolderContents(folder.id)
        if (contents.isEmpty()) {
            showNotification(folder.label, "This folder is empty")
            return
        }
        shell.startScreen.openFolder(
            folder, contents, { child -> wp81ColorFor(child) }
        ) { child -> wp81GlyphFor(child) }
        refreshWP81Notifications()
    }

    /**
     * Moves a tile into a folder, or back out onto Start.
     *
     * One field decides which of the two lists an icon is in, so this is a one-line change
     * followed by a rebuild of everything that reads it. Rebuilding rather than patching:
     * the wall repacks around the hole, the folder repacks around the arrival, and both of
     * those are the packer's job rather than something to reproduce here.
     */
    private fun fileWP81Tile(
        tile: rocks.gorjan.gokixp.wp81.Tile,
        folderId: String?,
        landed: Boolean
    ) {
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        // A folder cannot be put inside itself, and a tile already where it is asked to go
        // is a drop that changed nothing.
        if (folderId == icon.id || icon.parentFolderId == folderId) return

        val leaving = icon.parentFolderId
        icon.parentFolderId = folderId
        // [landed] means the tile is sitting in its new list's grid, in the place the user
        // put it, and that grid writes down the order. A tile posted into a folder that
        // was not open has no place in it yet, so it goes on the end.
        // Put on the end of the list it is joining, in whichever way up the phone is being
        // held: the other arrangement has its own idea of where things go and is not being
        // looked at.
        if (!landed) icon.wp81TileIndex = null
        saveDesktopIcons()

        // Nothing is rebuilt either way. The wall has already closed around the tile that
        // left, and replacing every view on it would undo the movement the user has just
        // watched. Only what reads the icons afresh has to be told.
        refreshWP81Notifications()
        buildWP81FolderPreviews()

        if (leaving != null) discardWP81FolderIfEmpty(leaving)
    }

    /**
     * Throws away a folder whose last tile has just been taken out of it.
     *
     * An empty folder is a tile that opens onto nothing. It was made by putting two things
     * together, so taking both out again undoes the making - leaving the shell to be tidied
     * up by hand would be asking the user to clear up after a gesture.
     */
    private fun discardWP81FolderIfEmpty(folderId: String) {
        if (desktopIcons.any { it.parentFolderId == folderId }) return
        val folder = desktopIcons.firstOrNull { it.id == folderId } ?: return
        if (folder.type != IconType.FOLDER) return

        // Whatever is on screen of it goes first: the gap belongs to a tile that is about
        // to stop existing.
        wp81Shell?.startScreen?.closeFolder(animated = false)
        desktopIcons.remove(folder)
        saveDesktopIcons()
        refreshWP81Tiles()
    }

    /** The tiles filed inside a folder, in the order the folder keeps them. */
    private fun wp81FolderContents(folderId: String): List<rocks.gorjan.gokixp.wp81.Tile> =
        desktopIcons
            .filter { it.parentFolderId == folderId }
            .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .mapIndexed { i, icon ->
                rocks.gorjan.gokixp.wp81.Tile(
                    id = icon.id,
                    label = icon.name.replace("\\n", " ").replace("\n", " "),
                    packageName = icon.packageName,
                    size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
                    index = i,
                    kind = wp81KindFor(icon)
                )
            }

    private fun openWP81Folder(folder: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val contents = desktopIcons
            .filter { it.parentFolderId == folder.id }
            .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .mapIndexed { i, icon ->
                rocks.gorjan.gokixp.wp81.Tile(
                    id = icon.id,
                    label = icon.name.replace("\\n", " ").replace("\n", " "),
                    packageName = icon.packageName,
                    size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
                    index = i,
                    kind = wp81KindFor(icon)
                )
            }
        wp81OpenFolderId = folder.id
        wireWP81FolderContents(shell, folder.id)
        // Read fresh rather than from the per-rebuild cache: a tile is repainted in place
        // without rebuilding the wall, so the cache can be a colour behind. Empty while
        // colours are being held back for the wallpaper's sake - see wp81ColorFor.
        val colors =
            if (themeManager.getWP81HideTileColors()) emptyMap()
            else themeManager.getWP81TileColors()
        // A folder that was painted a colour hands it to everything inside: the page *is*
        // the folder opened up, and a wall of accent tiles inside a green folder reads as
        // having arrived somewhere else. A tile the user painted individually keeps its
        // own - that was a deliberate choice about that app, and it outranks the folder's.
        val folderColor = colors[folder.id]
        shell.openFolder(
            folder.label,
            contents,
            notifications = { child -> wp81NotificationsFor(child) },
            tileColors = { child -> colors[child.id] ?: folderColor }
        ) { child -> wp81GlyphFor(child) }
        // A folder inside a folder previews its own contents too, and the page is on
        // screen before the next notification pass comes round.
        shell.folderPage.setFolderPreviews { child -> wp81FolderPreviewFor(child) }
    }

    /** Writes tile size and order back onto the desktop icons that back them. */
    /**
     * Writes tile size and order back onto the desktop icons that back them.
     *
     * Built-ins and user tiles share one index space, because the user can interleave
     * them freely. User positions ride on their DesktopIcon; the built-ins have no icon to
     * hang anything on, so theirs go to SharedPreferences.
     */
    private fun persistWP81Tiles(tiles: List<rocks.gorjan.gokixp.wp81.Tile>) {
        val byId = desktopIcons.associateBy { it.id }
        val ordered = tiles.sortedBy { it.index }

        // One shared index space: built-ins and user tiles are interleaved however the
        // user arranged them, so positions must be numbered across both, not per family.
        val builtInPlacements = mutableMapOf<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>>()
        ordered.forEachIndexed { position, tile ->
            if (tile.kind.isBuiltIn) {
                builtInPlacements[tile.id] = tile.size to position
            } else {
                byId[tile.id]?.let { icon ->
                    icon.wp81TileSize = tile.size.name
                    icon.wp81TileIndex = position
                }
            }
        }
        // Merged over what is already stored: a hidden tile is absent from this list, and
        // replacing outright would forget where it used to sit.
        saveWP81BuiltInPlacements(loadWP81BuiltInPlacements() + builtInPlacements)

        val userTiles = ordered.filterNot { it.kind.isBuiltIn }

        // Unpinning a tile removes the underlying desktop icon: in this theme the Start
        // screen *is* the icon list. Three kinds are exempt, because their absence from
        // Start means something other than "the user removed it":
        //   - icons filed inside a folder live on that folder's page;
        //   - the Recycle Bin and My Computer are deliberately not shown here at all, and
        //     deleting them would lose them from the desktop themes too.
        val keep = userTiles.map { it.id }.toSet()
        desktopIcons.removeAll {
            it.id !in keep &&
                it.parentFolderId == null &&
                it.type != IconType.RECYCLE_BIN &&
                it.type != IconType.MY_COMPUTER
        }
        saveDesktopIcons()
    }

    /** Pins an app from the app list as a new medium tile. */
    private fun pinWP81Tile(app: AppInfo) {
        // Already on the wall: nothing to do. Tested the same way the menu tests it - a
        // tile *on Start*, not an icon anywhere in the list. Refusing on the whole list
        // meant an app filed inside a folder could never be pinned: the menu offered
        // "pin to start", because there was no tile on Start, and this returned without
        // doing anything, because there was an icon somewhere.
        if (desktopIcons.any { it.packageName == app.packageName && it.parentFolderId == null }) return

        // On the end of both walls: a new tile has no place on either, and the one the
        // phone is not being held in would otherwise put it wherever its name falls.
        val nextIndex = (desktopIcons.mapNotNull { it.tileIndex }.maxOrNull() ?: -1) + 1
        val nextLandscape =
            (desktopIcons.mapNotNull { it.tileIndexLandscape }.maxOrNull() ?: -1) + 1

        // An app that is already in a folder moves out onto Start rather than appearing in
        // both places. A phone gives an app one tile, and two tiles for one app - one of
        // them buried in a folder - is not something the user could have asked for.
        val filed = desktopIcons.firstOrNull {
            it.packageName == app.packageName && it.parentFolderId != null
        }
        if (filed != null) {
            filed.parentFolderId = null
            filed.tileIndex = nextIndex
            filed.tileIndexLandscape = nextLandscape
            if (filed.tileSize == null) filed.tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
            if (filed.tileSizeLandscape == null) {
                filed.tileSizeLandscape = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
            }
            saveDesktopIcons()
            refreshWP81Tiles()
            wp81Shell?.let { shell ->
                shell.goToStart()
                shell.startScreen.scrollToEnd()
            }
            return
        }

        val icon = DesktopIcon(
            name = app.name,
            packageName = app.packageName,
            icon = app.icon,
            x = 0f,
            y = 0f,
            type = IconType.APP,
            tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
            tileIndex = nextIndex,
            tileSizeLandscape = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
            tileIndexLandscape = nextLandscape
        )
        desktopIcons.add(icon)
        saveDesktopIcons()
        refreshWP81Tiles()
        // Shown rather than announced: the tile goes on the end of the wall, which on a
        // full Start screen is off the bottom of it, so a message saying it had been
        // pinned was the only evidence the user got. Going there is better evidence, and
        // it also leaves them where they can move it.
        wp81Shell?.let { shell ->
            shell.goToStart()
            shell.startScreen.scrollToEnd()
        }
    }

    // ---------------------------------------------------------------- app list

    /**
     * Loads the app list for the shell.
     *
     * Deliberately independent of [cachedAppList]: that cache is nulled every time the
     * desktop Start menu closes, whereas the WP8.1 app list is a permanent page.
     */
    private fun refreshWP81AppList() {
        val shell = wp81Shell ?: return
        Thread {
            val apps = try {
                loadAppsInBackground()
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to load apps", e)
                emptyList()
            }
            val hidden = getHiddenApps()
            val visible = apps.filterNot { it.packageName in hidden }
            runOnUiThread { shell.setApps(visible) }
        }.start()
    }

    // ---------------------------------------------------------------- live tiles

    private fun startWP81LiveTiles() {
        stopWP81LiveTiles()

        // Only worth the request if the tile that shows it is on Start.
        refreshWP81NewsFeeds()
        refreshWP81News()
        refreshWP81TodayEvent()
        refreshWP81TomorrowEvents()

        // Media sessions are read through the notification listener this launcher already
        // runs, so no extra permission - but nothing shows until notification access is on.
        wp81MediaSessions = rocks.gorjan.gokixp.wp81.MediaSessions(this).also {
            it.startUpdates { runOnUiThread { refreshWP81Media() } }
        }

        // Reuse the Quick Glance calendar provider rather than querying the calendar again:
        // it already handles permissions, the all-day/next-event logic and its own refresh
        // cadence, and pushes results back on the main thread.
        wp81CalendarProvider = rocks.gorjan.gokixp.quickglance.CalendarDataProvider(this).also {
            it.startUpdates { data ->
                // The provider is the signal, not the content: it notices the calendar
                // moving, and both days are then re-read for what a tile actually shows -
                // a name and a time, rather than "in twenty minutes".
                refreshWP81TodayEvent()
                refreshWP81TomorrowEvents()
                wp81Shell?.startScreen?.let { start ->
                    wp81LiveWidgetContent(
                        start.tiles().firstOrNull { t ->
                            t.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR
                        } ?: return@let
                    )?.let { reading ->
                        start.setLiveWidgetContent(WP81_WIDGET_CALENDAR, reading)
                    }
                }
            }
        }
        val runnable = object : Runnable {
            override fun run() {
                wp81Shell?.startScreen?.let { start ->
                    // Built-in widgets show their content permanently, so they are simply
                    // refreshed in place.
                    // Cheap: it returns without doing anything until the stories are half
                    // an hour old, or the feeds turned on have changed.
                    refreshWP81NewsFeeds()

                    refreshWP81Weather()
                    // Media sessions announce themselves when they change, but a session
                    // quietly going away is a change nobody reports. Re-read on the tick
                    // so a tile cannot be left holding a track that finished.
                    refreshWP81Media()
                    for (tile in start.tiles()) {
                        wp81LiveWidgetContent(tile)?.let { reading ->
                            start.setLiveWidgetContent(tile.id, reading)
                        }
                        // Conditions change with the reading, so the mark refreshes with it.
                        val (frontGlyph, backGlyph) = wp81WidgetGlyphFor(tile)
                        start.setWidgetGlyph(tile.id, frontGlyph, backGlyph)
                        start.setWidgetBack(tile.id, wp81LiveWidgetBack(tile))
                    }
                }
                wp81Handler.postDelayed(this, WP81_LIVE_TILE_INTERVAL_MS)
            }
        }
        wp81LiveTileRunnable = runnable
        wp81Handler.postDelayed(runnable, WP81_LIVE_TILE_INTERVAL_MS)
    }

    private fun stopWP81LiveTiles() {
        wp81LiveTileRunnable?.let { wp81Handler.removeCallbacks(it) }
        wp81LiveTileRunnable = null
        wp81CalendarProvider?.stopUpdates()
        wp81CalendarProvider = null
        wp81MediaSessions?.stopUpdates()
        wp81MediaSessions = null
    }

    /**
     * Stores a new accent / background and repaints immediately.
     *
     * Deliberately does not recreate the activity: unlike a theme switch, changing accent
     * is meant to be instant, and every WP8.1 surface repaints from the palette.
     */
    private fun commitWP81Appearance(accent: Int, dark: Boolean) {
        val changed = accent != themeManager.getWP81Accent() || dark != themeManager.isWP81Dark()
        if (!changed) return
        themeManager.setWP81Accent(accent)
        themeManager.setWP81Dark(dark)
        refreshWP81Palette()
    }

    /**
     * Returns the WP8.1 shell to the top of Start.
     *
     * Home means home: whatever was open - the app list mid-search, a folder page,
     * settings, a selected tile, or Start scrolled halfway down - swiping home puts the
     * user back at the top of the Start screen, the way pressing Start on a real phone did.
     */
    /**
     * Everything "home" means under Windows Phone 8.1.
     *
     * Reached from the system's home gesture as well as from the Start key, and both mean
     * the same thing: whatever the user has got themselves into, put it away. Programs
     * included - resetting the shell while a maximised program covered it left the user
     * looking at exactly what they had just asked to leave, with a Start screen tidying
     * itself up behind it.
     */
    private fun resetWP81ToStart() {
        val shell = wp81Shell ?: return
        minimiseWP81Windows()
        wp81OpenFolderId = null
        shell.contextMenu.dismiss()
        shell.startScreen.exitEditMode()
        shell.goToStart(animated = false)
        shell.startScreen.scrollToTop()
    }

    /**
     * Takes every program that is on screen off it, and says whether there were any.
     *
     * Minimised rather than closed: none of them has been asked to stop, Zune in
     * particular is still playing, and each comes back by being launched again - which
     * restores it rather than starting it over.
     */
    private fun minimiseWP81Windows(): Boolean {
        val onScreen = floatingWindowManager.getAllActiveWindows().filterNot { it.isMinimized() }
        onScreen.forEach { it.minimize() }
        return onScreen.isNotEmpty()
    }

    /** Repaints the shell after an accent or Light/Dark change, without a recreate. */
    fun refreshWP81Palette() {
        val shell = wp81Shell ?: return
        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        shell.applyPalette(palette)
        applyWP81SystemBarAppearance(palette)
        findViewById<RelativeLayout>(R.id.main_background)?.setBackgroundColor(palette.background)
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)
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
        // The WP8.1 shell owns its own vertical layout - status bar on top, navigation bar
        // at the bottom - and this would stamp desktop taskbar margins over it.
        if (themeManager.isWindowsPhone81()) return

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
        // Read through AppTheme rather than compared as text. The phone theme answers to
        // two spellings - "Windows Phone 8" is what AppTheme.toString gives and what the
        // phone's own settings page therefore stores, "Windows Phone 8.1" is what the
        // desktop's Display Properties writes - and this used to test for one of them. A
        // theme stored under the other name matched no branch at all, so nothing was
        // applied: the desktop stayed up with the phone's colours on it and no shell.
        val stored = prefs.getString("selected_theme", "Windows XP") ?: "Windows XP"
        val selectedTheme = AppTheme.fromString(stored).toString()
        Log.d("MainActivity", "initializeTheme: stored='$stored' resolved='$selectedTheme'")

        // Check if this theme is already applied to prevent double application
        if (lastAppliedTheme == selectedTheme) {
            Log.d("MainActivity", "Theme $selectedTheme already applied in initializeTheme, skipping")
            return
        }

        // The WP8.1 shell has no Start menu - building one would inflate a taskbar-anchored
        // layout that is never shown.
        if (AppTheme.fromString(selectedTheme) != AppTheme.WindowsPhone81) {
            // And before anything is built on top: the phone shell takes the desktop apart
            // to make room for itself, and nothing ever put it back. A switch that reaches
            // here without the activity having been rebuilt in between - which does happen -
            // left a desktop with no cursor, no icons and whichever Start menu was there
            // two themes ago. Undoing it here means any theme applied over the phone shell
            // heals the screen rather than inheriting half of it.
            restoreDesktopAfterWP81()
            setupStartMenu(selectedTheme)
        }

        // Apply theme layouts directly (bypass tracking for initialization). On the enum,
        // so every spelling of a theme's name lands on the same branch and adding one can
        // never again leave a theme that matches nothing.
        when (AppTheme.fromString(selectedTheme)) {
            AppTheme.WindowsClassic -> applyWindows98Theme()
            AppTheme.WindowsXP -> applyWindowsXPTheme()
            AppTheme.WindowsVista -> applyWindowsVistaTheme()
            AppTheme.WindowsPhone81 -> applyWindowsPhone81Theme()
        }

        // Mark theme as applied to prevent future unnecessary applications
        lastAppliedTheme = selectedTheme

        // Apply Plus! 95 menu-colour tint over the freshly inflated Classic layouts
        themeManager.getActivePlus95()?.let { plus95 ->
            val root = findViewById<View>(R.id.main_background)
            if (root != null) applyPlus95MenuColor(root, plus95.menuColor)
        }

        refreshWeatherIfNeeded()
    }

    /** True if the user has set a custom icon for this package (folders check this before re-theming). */
    fun hasCustomIcon(packageName: String): Boolean = customIconMappings.containsKey(packageName)

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
        applyCursorNormalDrawable()
    }

    private fun applyCursorNormalDrawable() {
        val plus95 = themeManager.getActivePlus95()
        if (plus95 != null) {
            val d = loadPlus95Drawable(plus95.slug, "arrow.png", trimTransparent = true)
            if (d != null) {
                cursorEffect.setImageDrawable(d)
                return
            }
        }
        if (themeManager.isVistaTheme()) {
            cursorEffect.setImageResource(R.drawable.cursor_vista)
        } else {
            cursorEffect.setImageResource(R.drawable.cursor)
        }
    }

    /**
     * Loads a Plus! 95 PNG asset as a density-correct drawable.
     *
     * Assets are not density-scaled the way res/drawable resources are, so decoding one with
     * Drawable.createFromStream() yields a bitmap stamped at the device density — it then
     * renders at its raw pixel size (tiny on hi-dpi screens) inside a dp-sized slot. We stamp
     * the bitmap as mdpi so the BitmapDrawable scales up to the device density, matching how
     * the built-in drawables render (fixes the shrunken My Computer / Recycle Bin icons).
     *
     * @param trimTransparent when true, crops fully-transparent margins first. Plus! cursors
     *        ship as 32x32 canvases with the pointer tucked into one corner; trimming makes the
     *        pointer fill the fitXY cursor view — and sit at the touch point — the way the
     *        built-in cursor.png does, instead of appearing as a tiny arrow.
     */
    fun loadPlus95Drawable(
        slug: String,
        filename: String,
        trimTransparent: Boolean = false
    ): android.graphics.drawable.Drawable? {
        return try {
            assets.open(themeManager.plus95Path(slug, filename)).use { stream ->
                val decoded = android.graphics.BitmapFactory.decodeStream(stream)
                if (decoded == null) {
                    null
                } else {
                    val bitmap = if (trimTransparent) trimTransparentBorder(decoded) else decoded
                    bitmap.density = android.util.DisplayMetrics.DENSITY_DEFAULT
                    android.graphics.drawable.BitmapDrawable(resources, bitmap)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Plus! asset missing: $slug/$filename", e)
            null
        }
    }

    /** Crops fully-transparent rows/columns from the edges of a bitmap. */
    private fun trimTransparentBorder(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        // Fully transparent, or already edge-to-edge → nothing to crop.
        if (right < left || bottom < top) return src
        if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) return src
        return android.graphics.Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            val edgeThresholdPx = BACK_GESTURE_EDGE_THRESHOLD_DP * resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val isEdgeTouch = event.rawX <= edgeThresholdPx || event.rawX >= (screenWidth - edgeThresholdPx)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // If touch starts at edge, mark as potential back gesture
                    if (isEdgeTouch) {
                        potentialBackGestureStartTime = System.currentTimeMillis()
                        Log.d("MainActivity", "Potential back gesture detected at x=${event.rawX}")
                    } else {
                        potentialBackGestureStartTime = 0L
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Reset potential gesture tracking
                    potentialBackGestureStartTime = 0L
                }
            }

            // Block edge touches that could be back gestures
            if (isEdgeTouch && potentialBackGestureStartTime > 0L) {
                val timeSinceEdgeTouch = System.currentTimeMillis() - potentialBackGestureStartTime

                // Block if:
                // 1. Back gesture is confirmed, OR
                // 2. Touch is at edge and we're within the timeout window
                if (isBackGestureInProgress || timeSinceEdgeTouch < BACK_GESTURE_TIMEOUT_MS) {
                    Log.d("MainActivity", "Blocking edge touch (gesture=${isBackGestureInProgress}, time=${timeSinceEdgeTouch}ms)")
                    return true // Consume the event without processing
                }
            }

            // Also block if back gesture is confirmed (even if not at edge anymore)
            if (isBackGestureInProgress) {
                return true // Consume the event without processing
            }

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
            if (id == R.id.solitare_game_area || id == R.id.mine_grid || id == R.id.pinball_web_view) {
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
        // No mouse pointer under Windows Phone 8.1 - it is a touch OS.
        if (themeManager.isWindowsPhone81()) {
            cursorEffect.visibility = View.GONE
            return
        }
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
    private var busyCursorAnimator: android.animation.ObjectAnimator? = null

    private fun setCursorBusy() {
        // No mouse pointer under Windows Phone 8.1 - it is a touch OS.
        if (themeManager.isWindowsPhone81()) {
            cursorEffect.visibility = View.GONE
            return
        }
        if (::cursorEffect.isInitialized) {
            val plus95 = themeManager.getActivePlus95()
            if (plus95 != null && plus95.busyAsset != null) {
                val d = loadPlus95Drawable(plus95.slug, plus95.busyAsset, trimTransparent = true)
                if (d != null) {
                    cursorEffect.setImageDrawable(d)
                    return
                }
            }
            if (themeManager.isVistaTheme()) {
                cursorEffect.setImageResource(R.drawable.cursor_busy_vista)
                // Rotate the Vista busy cursor continuously
                busyCursorAnimator = android.animation.ObjectAnimator.ofFloat(cursorEffect, "rotation", 0f, 360f).apply {
                    duration = 4000
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    start()
                }
            } else {
                cursorEffect.setImageResource(R.drawable.cursor_busy)
            }
        }
    }

    // Switch cursor back to normal (pointer) state
    private fun setCursorNormal() {
        // No mouse pointer under Windows Phone 8.1 - it is a touch OS.
        if (themeManager.isWindowsPhone81()) {
            cursorEffect.visibility = View.GONE
            return
        }
        if (::cursorEffect.isInitialized) {
            // Stop any busy rotation animation
            busyCursorAnimator?.cancel()
            busyCursorAnimator = null
            cursorEffect.rotation = 0f
            applyCursorNormalDrawable()
        }
    }

    /**
     * Creates a WindowsDialog with the correct theme from the start to avoid re-inflation
     */
    private fun createThemedWindowsDialog(): WindowsDialog {
        // Pass the *chrome* theme, not the selected one. Under Windows Phone 8.1 this
        // hands the dialog AppTheme.WindowsVista, so every `currentTheme is WindowsVista`
        // check inside WindowsDialog - layout, border, focused/unfocused title bars -
        // takes the Vista branch without needing a WP8.1 arm of its own.
        val theme = themeManager.getSelectedTheme()
        val chromeTheme = when (theme.chrome) {
            DesktopChrome.CLASSIC -> AppTheme.WindowsClassic
            DesktopChrome.XP -> AppTheme.WindowsXP
            DesktopChrome.VISTA -> AppTheme.WindowsVista
        }
        return WindowsDialog(this, initialTheme = chromeTheme)
    }

    /**
     * Shows a notification bubble with title and description
     * @param title The notification title (application name)
     * @param description The notification message
     */
    private fun showNotification(title: String, description: String, onTap: (() -> Unit)? = null) {
        // Windows Phone 8.1 announces things with a band across the top instead of the
        // Vista speech bubble, which is anchored to a system tray this shell does not have.
        wp81Shell?.let { shell ->
            shell.toast.show(title, description, NOTIFICATION_DURATION_MS, onTap)
            // The phone's own alert, not the desktop's: a band across the top of a Start
            // screen announcing itself with Vista's bubble is two operating systems at once.
            playSound(R.raw.bubble_8)
            return
        }

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
        notificationHandler.postDelayed(notificationHideRunnable!!, NOTIFICATION_DURATION_MS)

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
                            updateAvailableVersion = latestTag
                            // The Welcome tile says so on Start, the way the taskbar icon
                            // says so on the desktop.
                            refreshWP81Notifications()
                            updateIcon.visibility = View.VISIBLE

                            showNotification(
                                "Windows Update",
                                "A new version ($latestTag) is available. Tap to download."
                            ) {
                                if (downloadUrl.isNotEmpty()) {
                                    try {
//                                        val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
//                                        startActivity(intent)
                                        openUrlShortcut(downloadUrl)
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