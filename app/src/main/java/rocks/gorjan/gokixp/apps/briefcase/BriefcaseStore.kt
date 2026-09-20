package rocks.gorjan.gokixp.apps.briefcase

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the phone remembers about the Briefcase between runs: which computer it belongs to,
 * the token that reaches it, the revision it last caught up to, and one row per file.
 *
 * All of it lives in its own preferences file rather than the launcher's, because that file
 * is excluded from cloud backup (see `backup_rules.xml`) and is never written into a
 * registry export. A token that rode a backup onto a second phone would be a copy of the key
 * to somebody's files; this way a restored phone simply has to be connected again.
 */
class BriefcaseStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val entryListType = object : TypeToken<List<BriefcaseEntry>>() {}.type

    /** Worked out once; cleared by [forgetFolder] when the answer could have changed. */
    @Volatile private var cachedFolder: File? = null

    // ---- The connection --------------------------------------------------------------------

    /** The origin this Briefcase belongs to, e.g. `https://gorjan.rocks`. */
    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        private set(value) = prefs.edit { putString(KEY_HOST, value) }

    /** What the computer calls itself - "Windows 98" - shown wherever the person is told where their files went. */
    var site: String
        get() = prefs.getString(KEY_SITE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SITE, value) }

    /** The account on that computer. */
    var user: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        private set(value) = prefs.edit { putString(KEY_USER, value) }

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        private set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    /** What this phone is called in the computer's Connected Devices list. */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value) }

    /** The revision last caught up to; passed as `since` on the next listing. */
    var rev: Long
        get() = prefs.getLong(KEY_REV, -1L)
        set(value) = prefs.edit { putLong(KEY_REV, value) }

    var limits: BriefcaseLimits
        get() = BriefcaseLimits(
            fileBytes = prefs.getLong(KEY_LIMIT_BYTES, BriefcaseLimits().fileBytes),
            nameChars = prefs.getInt(KEY_LIMIT_NAME, BriefcaseLimits().nameChars),
            pollSeconds = prefs.getInt(KEY_LIMIT_POLL, BriefcaseLimits().pollSeconds)
        )
        set(value) = prefs.edit {
            putLong(KEY_LIMIT_BYTES, value.fileBytes)
            putInt(KEY_LIMIT_NAME, value.nameChars)
            putInt(KEY_LIMIT_POLL, value.pollSeconds)
        }

    /**
     * The bearer token, kept encrypted by a key that never leaves this phone.
     *
     * Null when there is none, or when the ciphertext no longer decrypts - which is what a
     * restored backup looks like, and means the same thing to everyone above: not connected.
     */
    val token: String?
        get() {
            val stored = prefs.getString(KEY_TOKEN, null) ?: return null
            return TokenVault.decrypt(stored)
        }

    val isConnected: Boolean get() = host.isNotEmpty() && !token.isNullOrEmpty()

    /** Everything a successful pair or logon hands back, written down in one go. */
    fun remember(host: String, pairing: BriefcasePairing) {
        val stored = TokenVault.encrypt(pairing.token) ?: run {
            Log.w(TAG, "Storing the token unencrypted - this phone has no usable key store")
            TokenVault.PLAIN_PREFIX + pairing.token
        }
        prefs.edit {
            putString(KEY_HOST, host)
            putString(KEY_TOKEN, stored)
            putString(KEY_SITE, pairing.site)
            putString(KEY_USER, pairing.user)
            putString(KEY_DEVICE_ID, pairing.deviceId)
            putString(KEY_DEVICE_NAME, pairing.deviceName)
            // A fresh connection knows nothing about what used to be there: section 2.2 says
            // a first sync lists what is there now and nothing about what has gone.
            putLong(KEY_REV, -1L)
        }
        limits = pairing.limits
    }

    /**
     * Forgets the computer. The files stay where they are - they become the phone's own
     * copies, and the folder is still a folder - but nothing in it points anywhere now.
     */
    fun forget() {
        prefs.edit {
            remove(KEY_HOST)
            remove(KEY_TOKEN)
            remove(KEY_SITE)
            remove(KEY_USER)
            remove(KEY_DEVICE_ID)
            remove(KEY_REV)
        }
        saveEntries(entries().map { it.orphaned() })
    }

    // ---- Settings --------------------------------------------------------------------------

    /**
     * Whether a sync may run on mobile data. Off by default: section 5.7 asks first, and a
     * Briefcase full of holiday photos is not what anybody wants on a metered connection.
     */
    var syncOnMobileData: Boolean
        get() = prefs.getBoolean(KEY_MOBILE_DATA, false)
        set(value) = prefs.edit { putBoolean(KEY_MOBILE_DATA, value) }

    /** Whether the "Welcome to the Windows Briefcase" dialog has had its one showing. */
    var hasSeenWelcome: Boolean
        get() = prefs.getBoolean(KEY_WELCOMED, false)
        set(value) = prefs.edit { putBoolean(KEY_WELCOMED, value) }

    /** The address last typed into the Connect dialog, so it need not be typed twice. */
    var lastHost: String
        get() = prefs.getString(KEY_LAST_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_HOST, value) }

    // ---- The folder ------------------------------------------------------------------------

    /**
     * Where the copies live.
     *
     * `My Briefcase` at the top of the phone's own storage when that can be written to, so it
     * is a folder the person can see from My Computer and from any file manager - the way the
     * Briefcase is a folder on the desktop, not a hidden cache. Falls back to the app's own
     * storage when it cannot be, so the Briefcase works with no permission granted at all.
     */
    fun folder(): File {
        cachedFolder?.let { return it }

        val external = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
        val chosen = if (canUse(external)) {
            external
        } else {
            File(appContext.filesDir, "briefcase").also { it.mkdirs() }
        }

        // Storage permission may have arrived since the last run, in which case the folder
        // moves up out of the app's own storage and the copies go with it - a Briefcase that
        // emptied itself because a permission changed would be a Briefcase nobody trusts.
        val previous = prefs.getString(KEY_FOLDER, null)
        if (previous != null && previous != chosen.absolutePath) {
            moveContents(File(previous), chosen)
        }
        prefs.edit { putString(KEY_FOLDER, chosen.absolutePath) }

        cachedFolder = chosen
        return chosen
    }

    /** Called when storage permission may have changed, so [folder] works the answer out again. */
    fun forgetFolder() {
        cachedFolder = null
    }

    private fun moveContents(from: File, to: File) {
        if (!from.isDirectory || from.absolutePath == to.absolutePath) return
        try {
            from.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val destination = File(to, file.name)
                if (destination.exists()) return@forEach
                if (!file.renameTo(destination)) {
                    file.copyTo(destination, overwrite = false)
                    file.delete()
                }
            }
            if (from.listFiles()?.isEmpty() == true) from.delete()
            Log.d(TAG, "Moved the Briefcase from ${from.absolutePath} to ${to.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not move the Briefcase to ${to.absolutePath}", e)
        }
    }

    private fun canUse(folder: File): Boolean = try {
        (folder.isDirectory || folder.mkdirs()) && folder.canWrite()
    } catch (e: Exception) {
        Log.w(TAG, "Cannot use ${folder.absolutePath} for the Briefcase", e)
        false
    }

    fun fileFor(name: String): File = File(folder(), name)

    // ---- The mirror ------------------------------------------------------------------------

    fun entries(): List<BriefcaseEntry> {
        val json = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            gson.fromJson<List<BriefcaseEntry>>(json, entryListType) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "The Briefcase index would not read; starting a new one", e)
            emptyList()
        }
    }

    fun saveEntries(entries: List<BriefcaseEntry>) {
        prefs.edit { putString(KEY_INDEX, gson.toJson(entries, entryListType)) }
    }

    companion object {
        private const val TAG = "BriefcaseStore"

        /** Its own file, excluded from backup. See the class comment. */
        const val PREFS = "briefcase"

        const val FOLDER_NAME = "My Briefcase"

        private const val KEY_HOST = "host"
        private const val KEY_TOKEN = "token"
        private const val KEY_SITE = "site"
        private const val KEY_USER = "user"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_REV = "rev"
        private const val KEY_INDEX = "index"
        private const val KEY_LIMIT_BYTES = "limit_file_bytes"
        private const val KEY_LIMIT_NAME = "limit_name_chars"
        private const val KEY_LIMIT_POLL = "limit_poll_seconds"
        private const val KEY_MOBILE_DATA = "sync_on_mobile_data"
        private const val KEY_WELCOMED = "welcomed"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_FOLDER = "folder"
    }
}

/**
 * The token's keeper.
 *
 * An AES key generated inside the phone's hardware-backed key store encrypts it; the key
 * itself cannot be read out, so the stored ciphertext is worth nothing anywhere else - not on
 * another phone, and not in a backup. [PLAIN_PREFIX] marks the one case where a device has no
 * usable key store and the token had to be written down as it is.
 */
private object TokenVault {

    private const val TAG = "BriefcaseToken"
    private const val ALIAS = "briefcase_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    const val PLAIN_PREFIX = "plain:"

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        base64(cipher.iv) + ":" + base64(encrypted)
    } catch (e: Exception) {
        Log.w(TAG, "Could not encrypt the token", e)
        null
    }

    fun decrypt(stored: String): String? {
        if (stored.startsWith(PLAIN_PREFIX)) return stored.removePrefix(PLAIN_PREFIX)
        return try {
            val (iv, payload) = stored.split(":", limit = 2).let { it[0] to it[1] }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, unbase64(iv)))
            String(cipher.doFinal(unbase64(payload)), Charsets.UTF_8)
        } catch (e: Exception) {
            // A key that is gone (a restored backup, a cleared key store) is not an error to
            // report: it means this phone is not connected, which is what the caller will do
            // something sensible about.
            Log.w(TAG, "The stored token would not decrypt; treating this phone as not connected")
            null
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
