package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Dictation that never leaves the phone.
 *
 * The reason this keyboard exists in the form it does. The platform's recogniser is Google's
 * on most Android phones and transcribes in the cloud; on a privacy-focused build it is not
 * wired up at all, so there is no local option to fall back on and the microphone simply does
 * nothing. Vosk is the local option: a model on disk, a native decoder, and audio that goes
 * from the microphone to the text box without touching a network.
 *
 * Two costs, both real and both worth being plain about. The model is about forty megabytes
 * and has to be fetched once - see [VoskModels] - and the recognition is not as good as a
 * datacentre's. For dictating a message it is entirely usable; for dictating a name it is not.
 *
 * The model and the recogniser are heavy objects and are held between uses. Loading a model
 * takes a second or two, which is fine once and intolerable every time the microphone is
 * pressed.
 */
internal class VoskVoiceInput(
    private val context: Context,
    private val models: VoskModels,
    /** Which language to listen for, as the bare code the layouts use. */
    private val language: String
) : VoiceInput {

    private var model: Model? = null
    private var loadedFor: String? = null
    private var speech: SpeechService? = null
    private var listener: VoiceInput.Listener? = null

    override var isListening = false
        private set

    /** Whether this can be used at all right now: a model exists and has been fetched. */
    val isReady: Boolean get() = models.supports(language) && models.isDownloaded(language)

    override fun start(listener: VoiceInput.Listener) {
        this.listener = listener
        stopInternal()

        if (!models.supports(language)) {
            listener.onStopped("offline dictation has no model for this language")
            return
        }
        if (!models.isDownloaded(language)) {
            listener.onStopped("the speech model has not been downloaded yet")
            return
        }

        val loaded = try {
            openModel(models.directory(language))
        } catch (e: Throwable) {
            // Throwable rather than Exception: a missing or mismatched native library comes
            // through as an UnsatisfiedLinkError, which is an Error, and taking the keyboard
            // down over a failed model load would be far worse than not dictating.
            listener.onStopped("the speech model could not be loaded")
            return
        }

        try {
            val recognizer = Recognizer(loaded, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speech = service
            service.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String?) {
                    textIn(hypothesis, PARTIAL)?.let { this@VoskVoiceInput.listener?.onPartial(it) }
                }

                override fun onResult(hypothesis: String?) {
                    textIn(hypothesis, FINAL)?.let { this@VoskVoiceInput.listener?.onFinal(it) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    textIn(hypothesis, FINAL)?.let { this@VoskVoiceInput.listener?.onFinal(it) }
                    isListening = false
                    this@VoskVoiceInput.listener?.onStopped(null)
                }

                override fun onError(exception: Exception?) {
                    isListening = false
                    this@VoskVoiceInput.listener?.onStopped("dictation stopped")
                }

                /**
                 * Silence for long enough that it is not a pause.
                 *
                 * Not an error and not worth a message: it is what happens when somebody
                 * presses the microphone and then thinks for a moment.
                 */
                override fun onTimeout() {
                    isListening = false
                    this@VoskVoiceInput.listener?.onStopped(null)
                }
            })
            isListening = true
        } catch (e: Throwable) {
            isListening = false
            listener.onStopped("the microphone could not be opened")
        }
    }

    override fun stop() {
        // `stop` rather than `cancel`: it transcribes what has been said so far, where
        // cancelling throws it away. Somebody who has finished a sentence and pressed the
        // button means the first.
        try {
            speech?.stop()
        } catch (e: Throwable) {
            stopInternal()
            listener?.onStopped(null)
        }
        isListening = false
    }

    override fun destroy() {
        stopInternal()
        try {
            model?.close()
        } catch (e: Throwable) {
            // Nothing useful to do about a model that will not close.
        }
        model = null
        loadedFor = null
        listener = null
    }

    /** Kept between uses: opening one takes a second or two. */
    private fun openModel(directory: File): Model {
        model?.let { if (loadedFor == language) return it }
        try {
            model?.close()
        } catch (e: Throwable) {
            // Replacing it either way.
        }
        return Model(directory.absolutePath).also {
            model = it
            loadedFor = language
        }
    }

    private fun stopInternal() {
        isListening = false
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (e: Throwable) {
            // Nothing useful to do about a recogniser that will not shut down.
        }
        speech = null
    }

    /**
     * Vosk reports what it heard as JSON, so this is where it becomes a sentence.
     *
     * Partial results arrive under `partial` and settled ones under `text`, and either can be
     * an empty string - which happens constantly while somebody is not speaking and must not
     * be treated as having heard silence.
     */
    private fun textIn(hypothesis: String?, key: String): String? {
        if (hypothesis == null) return null
        return try {
            JSONObject(hypothesis).optString(key).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        /** What the small models are trained at. Feeding anything else transcribes gibberish. */
        const val SAMPLE_RATE = 16_000f

        const val PARTIAL = "partial"
        const val FINAL = "text"
    }
}
