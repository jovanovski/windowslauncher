package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * The offline speech models: which languages have one, and fetching it the first time.
 *
 * A model is about forty megabytes, which is why it is not in the app. The launcher's APK is
 * already large and most people will never press the microphone; making everybody carry a
 * language pack for a feature they may not use is the sort of thing that makes an app feel
 * heavy for no reason they can see. So it arrives on demand, once, and stays.
 *
 * It is kept in [Context.getNoBackupFilesDir] for the same reason the learned words are: an
 * app's `filesDir` is copied to the cloud backup by default, and forty megabytes of speech
 * model has no business being uploaded and restored - it can always be fetched again.
 *
 * **Not every language has one.** Vosk publishes about twenty; Macedonian is not among them,
 * and neither are Bulgarian or Serbian. [supports] is how the keyboard finds that out before
 * offering to dictate in a language it cannot hear, and why the platform recogniser stays as
 * the fallback whichever engine is preferred.
 */
internal class VoskModels(private val context: Context) {

    /**
     * One published model: where it is, and how big the download is.
     *
     * The size is carried rather than discovered because it is needed *before* the download
     * starts - "this needs 303 MB" is only useful as a question, and asking it after the
     * bytes are already moving is not asking.
     */
    data class Model(private val name: String, val megabytes: Int) {
        val url: String get() = "$BASE$name.zip"
    }

    /** Where the finished model for [language] lives, whether or not it is there yet. */
    fun directory(language: String): File =
        File(File(context.noBackupFilesDir, MODELS_DIR), language)

    fun isDownloaded(language: String): Boolean {
        val dir = directory(language)
        // A model is a directory of several files; the marker is written last, so its
        // presence means the unpacking finished rather than merely started.
        return File(dir, DONE_MARKER).exists()
    }

    /** Whether Vosk publishes a model for [language] at all. */
    fun supports(language: String): Boolean = MODELS.containsKey(language)

    /** How large the download for [language] is, in megabytes, or zero if there is none. */
    fun sizeOf(language: String): Int = MODELS[language]?.megabytes ?: 0

    val isBusy: Boolean get() = working.get()

    /**
     * Fetches and unpacks the model for [language], if it is not already there.
     *
     * @param onProgress whole percent, on the main thread, for something to show.
     * @param onDone the finished directory, or null with a reason.
     */
    fun fetch(
        language: String,
        onProgress: (Int) -> Unit,
        onDone: (File?, String?) -> Unit
    ) {
        val model = MODELS[language]
        if (model == null) {
            onDone(null, "there is no offline model for this language")
            return
        }
        if (isDownloaded(language)) {
            onDone(directory(language), null)
            return
        }
        if (!working.compareAndSet(false, true)) {
            onDone(null, "already downloading")
            return
        }

        worker.execute {
            var failure: String? = null
            var result: File? = null
            try {
                result = download(model.url, directory(language)) { percent ->
                    main.post { onProgress(percent) }
                }
            } catch (e: Exception) {
                failure = "the speech model could not be downloaded"
                // A half-unpacked model is worse than none: it would look downloaded on the
                // next attempt and fail when Vosk tried to open it.
                directory(language).deleteRecursively()
            }
            working.set(false)
            main.post { onDone(result, failure) }
        }
    }

    /**
     * Streams the zip straight into place rather than saving it and unpacking afterwards.
     *
     * Forty megabytes compressed becomes rather more on disk, and writing the archive first
     * would mean holding both at once for no benefit - nothing here needs the zip again.
     */
    private fun download(url: String, into: File, onProgress: (Int) -> Unit): File {
        into.deleteRecursively()
        into.mkdirs()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var read = 0L
            var lastPercent = -1

            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // The archives have a single top-level directory named after the model,
                    // which is one level of nesting nobody wants in the path.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isNotEmpty() && !entry.isDirectory) {
                        val target = File(into, relative)
                        // Nothing outside the directory we are unpacking into, whatever the
                        // archive claims its entries are called.
                        if (!target.canonicalPath.startsWith(into.canonicalPath + File.separator)) {
                            throw SecurityException("archive entry outside its directory")
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count <= 0) break
                                out.write(buffer, 0, count)
                                read += count
                            }
                        }
                    }
                    if (total > 0) {
                        // Against the compressed size, which the unpacked bytes overshoot -
                        // so it is capped rather than allowed to report more than finished.
                        val percent = ((read * 100) / total).toInt().coerceIn(0, 99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } finally {
            connection.disconnect()
        }

        // Written last, so that a model interrupted part-way through is not mistaken for one
        // that arrived.
        File(into, DONE_MARKER).writeText(url)
        onProgress(100)
        return into
    }

    private val working = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wp81-keyboard-model").apply { isDaemon = true }
    }

    private companion object {
        const val MODELS_DIR = "vosk"
        const val DONE_MARKER = ".complete"
        const val BUFFER = 32 * 1024
        const val TIMEOUT_MS = 30_000

        /** Where Vosk publishes them. Every model below is this plus its name plus `.zip`. */
        const val BASE = "https://alphacephei.com/vosk/models/"

        /**
         * The small models, by language, with the size of the download.
         *
         * Fetched from Vosk's own site rather than mirrored: these are the published files,
         * they are versioned in the name so a URL never changes underneath, and mirroring
         * forty megabytes per language into this project's releases would be a lot of storage
         * to maintain for no gain.
         *
         * Taken from Vosk's own `model-list.json`, filtered to the `small` models that are
         * not marked obsolete, and every size below was read off the server rather than out
         * of that index - which has Swedish's wrong by a factor of six in the *other*
         * direction, and Swedish is the one where it matters.
         *
         * Eleven of the twenty-two languages that ship are here. **Macedonian is not**, and
         * neither are Serbian, Ukrainian, Greek, Slovak, Romanian, Hungarian, Finnish, Danish
         * or Norwegian - Vosk publishes nothing for any of them. So the language this
         * keyboard was built for is precisely the one that still falls back to the platform
         * recogniser, which is why that fallback is not optional.
         */
        val MODELS = mapOf(
            "en" to Model("vosk-model-small-en-us-0.15", 41),
            "cs" to Model("vosk-model-small-cs-0.4-rhasspy", 46),
            "de" to Model("vosk-model-small-de-0.15", 46),
            "es" to Model("vosk-model-small-es-0.42", 39),
            "fr" to Model("vosk-model-small-fr-0.22", 42),
            "it" to Model("vosk-model-small-it-0.22", 49),
            "nl" to Model("vosk-model-small-nl-0.22", 40),
            "pl" to Model("vosk-model-small-pl-0.22", 52),
            "pt" to Model("vosk-model-small-pt-0.3", 32),
            "ru" to Model("vosk-model-small-ru-0.22", 46),
            // The odd one out by a long way. Vosk has no ordinary small Swedish model, only
            // this one, and three hundred megabytes is enough that the keyboard says so
            // before starting rather than after - see [sizeOf].
            "sv" to Model("vosk-model-small-sv-rhasspy-0.15", 303),
            "tr" to Model("vosk-model-small-tr-0.3", 36)
        )
    }
}
