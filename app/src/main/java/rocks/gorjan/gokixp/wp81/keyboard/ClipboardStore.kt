package rocks.gorjan.gokixp.wp81.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing that was copied, and what is known about it.
 *
 * [sensitive] is what the *copier* said, not what this guessed: a password manager, an
 * autofill provider or any app that knows it is handing over a secret marks the clip, which
 * is the same flag Android's own clipboard toast reads when it says "content hidden" instead
 * of showing a preview. Guessing would be worse than useless - a keyboard that decided for
 * itself which of your text was a password would mask the wrong things and, far worse, would
 * confidently show the right ones.
 */
internal data class Clip(val text: String, val at: Long, val sensitive: Boolean) {

    /**
     * What this looks like on screen: itself, or one dot per character.
     *
     * The length is kept because the length is the part that is safe and is also the part
     * that identifies it - out of two secrets copied this afternoon, the eight-dot one and
     * the twenty-dot one are told apart at a glance without either being on display.
     */
    fun display(): String =
        if (sensitive) "•".repeat(text.length.coerceAtMost(MASK_MAX))
        // One line. A copied paragraph is a paragraph, and a pill that grew to three lines
        // would be a pill that pushed the keys off the bottom of the screen.
        else text.replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Beyond this the dots stop counting: a masked essay is a row of dots either way. */
        const val MASK_MAX = 32
    }
}

/**
 * What has been copied lately.
 *
 * An input method is one of the two things on Android still allowed to read the clipboard
 * without being in the foreground - the other is the app with focus - which is what makes a
 * paste offer on the suggestion bar possible at all. Everything here hangs off that: the
 * store is read when the keyboard comes up and whenever the clipboard changes underneath it,
 * and a read that the system refuses simply leaves the history as it was.
 *
 * **The last two hours, and no more.** A clipboard history is a list of the things somebody
 * has copied, which over a day is a genuinely revealing document - addresses, one-time codes,
 * whatever was in the last email. Two hours is long enough to cover "I copied that a while
 * ago and now I need it" and short enough that the list is never a diary.
 *
 * **Secrets are never written to disk.** A clip the copier marked sensitive is held in memory
 * for as long as the keyboard's process happens to live and is never persisted, so a password
 * copied out of a manager does not end up sitting in a preferences file - or in a backup of
 * one - waiting for the two hours to elapse. Losing it when the process is reclaimed is the
 * right trade: it was one tap from being copied again, and the alternative is a plaintext
 * password in a file.
 */
internal object ClipboardStore {

    /** How far back the history goes. */
    private const val WINDOW_MS = 2 * 60 * 60 * 1000L

    /** How many are kept, however recent. Past this the older ones fall off the end. */
    private const val MAX = 20

    /**
     * How much of one clip is kept.
     *
     * Somebody who copies a whole document does not want it back off a keyboard's pill, and
     * a preferences file is not the place for a megabyte of somebody else's text.
     */
    private const val MAX_LENGTH = 4000

    private const val KEY = "wp81_kb_clipboard"

    private val entries = mutableListOf<Clip>()
    private var loaded = false

    /**
     * Reads the system clipboard, and remembers what is on it.
     *
     * Safe to call as often as there is any reason to: a clip that is already the newest
     * entry is not added again. Copying the same text twice does move it back to the front,
     * because that is a deliberate act and it says which of the last twenty is wanted now.
     */
    fun refresh(context: Context) {
        load(context)
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = try {
            // Refused when the keyboard is not the active input method, which is most of the
            // time. Null then, and there is nothing to be done about it or said about it.
            manager.primaryClip
        } catch (e: Exception) {
            null
        } ?: return

        val description: ClipDescription? = clip.description
        if (description != null && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return

        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(context)?.toString() }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_LENGTH)
            ?: return

        remember(context, Clip(text, System.currentTimeMillis(), isSensitive(description)))
    }

    /**
     * The most recent thing copied, or null when there is nothing worth offering.
     *
     * Not `history().firstOrNull()`, though it means the same thing: the bar asks this on
     * every keystroke to decide whether to draw its mark, and copying the whole list to read
     * one entry off the front of it is an allocation per letter typed.
     */
    fun latest(context: Context): Clip? {
        load(context)
        prune(context)
        return entries.firstOrNull()
    }

    /** Everything still inside the window, newest first. */
    fun history(context: Context): List<Clip> {
        load(context)
        prune(context)
        return entries.toList()
    }

    /** Forgets everything, on disk as well as in memory. */
    fun clear(context: Context) {
        entries.clear()
        save(context)
    }

    // ---------------------------------------------------------------- inside

    private fun remember(context: Context, clip: Clip) {
        if (clip.text.isBlank()) return
        val already = entries.indexOfFirst { it.text == clip.text }
        if (already == 0) {
            // Already the one being offered. Nothing has changed and nothing needs writing.
            return
        }
        if (already > 0) entries.removeAt(already)
        entries.add(0, clip)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        prune(context)
        save(context)
    }

    private fun prune(context: Context) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        // Also drops anything stamped in the future, which is what a clock that has been put
        // back leaves behind - otherwise those entries would outlive every window there is.
        val stale = entries.removeAll { it.at < now - WINDOW_MS || it.at > now }
        if (stale) save(context)
    }

    /**
     * What the copier said about it.
     *
     * Two spellings of the same flag: the platform's, which is what an app targeting Android
     * 13 or later sets, and AndroidX's, which is what everything built against the support
     * library sets on older releases. Read as literals rather than through the constant so
     * that a phone below 33 still honours a marked clip.
     */
    private fun isSensitive(description: ClipDescription?): Boolean {
        val extras = description?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false) ||
            extras.getBoolean("androidx.content.extra.IS_SENSITIVE", false)
    }

    // ---------------------------------------------------------------- on disk

    /**
     * The keyboard's own file, not the shell's.
     *
     * The shell's preferences are copied to a new phone when one is set up - see the note in
     * [WP81KeyboardService] about the subtype flag - and the last two hours of somebody's
     * clipboard is not something to carry across a device migration.
     */
    private fun prefs(context: Context) =
        context.getSharedPreferences(WP81KeyboardService.KEYBOARD_PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val stored = prefs(context).getString(KEY, null) ?: return
        try {
            val array = JSONArray(stored)
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val text = entry.optString("t").takeIf { it.isNotEmpty() } ?: continue
                entries.add(Clip(text, entry.optLong("a"), sensitive = false))
            }
        } catch (e: Exception) {
            // A file written by some earlier shape of this. Start again rather than argue.
            entries.clear()
        }
        prune(context)
    }

    /** Writes everything that is not a secret. See the note on this object. */
    private fun save(context: Context) {
        val array = JSONArray()
        for (clip in entries) {
            if (clip.sensitive) continue
            array.put(JSONObject().put("t", clip.text).put("a", clip.at))
        }
        try {
            prefs(context).edit().putString(KEY, array.toString()).apply()
        } catch (e: Exception) {
            // Nothing to be done about a preferences file that will not write.
        }
    }
}
