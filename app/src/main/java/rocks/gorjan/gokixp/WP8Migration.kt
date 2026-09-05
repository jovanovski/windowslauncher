package rocks.gorjan.gokixp

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * Keeps a copy of a Windows Phone 8.1 setup for the launcher that inherited it.
 *
 * WP8.1 left this app and became one of its own. Everything it had lives in this app's
 * preferences and files, and the phone launcher can read it - but only while it is still
 * here to read. Two things would otherwise destroy it before anyone got the chance:
 * [MainActivity.purgeRetiredSystemApps], which takes a retired program back out of the
 * user's arrangement, and [MainActivity.pruneUnusedImportedIcons], which deletes icon
 * files no live theme points at. So a copy is taken first, once, and set aside.
 *
 * What is copied is deliberately more than the preferences file:
 *
 * - The main preferences hold the tiles, their colours, the hidden set, the accent and
 *   the icon map - but the icon map holds *paths* (`imported_icons/<file>.png`), and the
 *   Start background is a bare file. Carrying only the JSON hands the phone launcher a
 *   Start screen with broken icons and no background.
 * - Three preference files sit outside the main one and would be missed entirely:
 *   the keyboard's, Zune's and the file browser's.
 *
 * Nothing here deletes anything. The originals stay exactly where they are - this is a
 * copy, taken because the copy is what survives the cleanup that follows it.
 */
object WP8Migration {

    private const val TAG = "WP8Migration"

    /** Where the copy is kept, under `filesDir`. */
    const val DIR = "wp8_migration"

    /**
     * That this phone was running the Windows Phone theme when it updated.
     *
     * Recorded separately from `selected_theme` because that string does not survive:
     * the moment the user picks any theme it is overwritten, and after the fallback in
     * [rocks.gorjan.gokixp.theme.AppTheme.fromString] it no longer reads back as the
     * phone theme anyway. This is the durable answer to "was this a phone user", and it
     * is what decides whether they are shown the notice and offered the export.
     */
    const val KEY_WAS_PHONE_USER = "wp8_was_phone_user"

    /** That the "it moved" notice has been shown, so it is shown once and not again. */
    const val KEY_NOTICE_SHOWN = "wp8_moved_notice_shown"

    /** The stored `selected_theme` spellings that mean the phone theme. */
    private val PHONE_THEME_NAMES = setOf("Windows Phone 8", "Windows Phone 8.1")

    /** Preference files the phone shell wrote outside the launcher's main one. */
    private val SIDE_PREFS = listOf("wp81_keyboard", "zune_prefs", "wp81_files")

    /** Files the preferences refer to by path, and are useless without. */
    private const val IMPORTED_ICONS = "imported_icons"
    private const val START_BACKGROUND = "wp81_start_background.img"

    /**
     * Notices a phone user and puts their setup aside.
     *
     * Safe to call on every launch: the marker is written once and the copy is taken
     * once. Returns whether this phone was ever running the phone theme, which is what
     * the notice keys off.
     *
     * Call this **before** any sweep that edits the user's arrangement.
     */
    fun captureIfNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // The raw string, not AppTheme.fromString - which now answers Vista for it.
        val stored = prefs.getString("selected_theme", null)
        if (stored in PHONE_THEME_NAMES && !prefs.getBoolean(KEY_WAS_PHONE_USER, false)) {
            prefs.edit { putBoolean(KEY_WAS_PHONE_USER, true) }
        }

        if (!prefs.getBoolean(KEY_WAS_PHONE_USER, false)) return false

        val dir = File(context.filesDir, DIR)
        // Already taken. Deliberately not refreshed: after this point the arrangement is
        // being cleaned up, so a second copy would be a copy of the damage.
        if (!dir.exists()) capture(context, dir)
        return true
    }

    private fun capture(context: Context, dir: File) {
        try {
            dir.mkdirs()

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            File(dir, "prefs.json").writeText(PrefsBackup.toJson(prefs))

            for (name in SIDE_PREFS) {
                val side = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (side.all.isEmpty()) continue
                File(dir, "$name.json").writeText(PrefsBackup.toJson(side))
            }

            val files = File(dir, "files").apply { mkdirs() }
            File(context.filesDir, IMPORTED_ICONS)
                .takeIf { it.isDirectory }
                ?.copyRecursively(File(files, IMPORTED_ICONS), overwrite = true)
            File(context.filesDir, START_BACKGROUND)
                .takeIf { it.isFile }
                ?.copyTo(File(files, START_BACKGROUND), overwrite = true)

            Log.i(TAG, "Kept a copy of the Windows Phone setup in ${dir.absolutePath}")
        } catch (e: Exception) {
            // Left for the next launch rather than half-written: the directory existing is
            // what marks the job done, so a failed attempt that made it must not stand.
            Log.w(TAG, "Could not copy the Windows Phone setup", e)
            runCatching { dir.deleteRecursively() }
        }
    }
}
