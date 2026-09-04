package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.provider.Settings
import android.speech.SpeechRecognizer

/**
 * Dictation, behind an interface so the engine underneath can be replaced.
 *
 * There will be two. The one here uses the platform's own recogniser, which every Android
 * phone has and which needs no download - and which, on most phones, is Google's, so it is not
 * where this is meant to end up. The other is Vosk: entirely offline, entirely local, and a
 * forty-megabyte model to fetch before it can say a word. This interface is what lets the
 * second arrive without the keyboard noticing.
 *
 * The platform one is not merely a stopgap either. Vosk has no Macedonian model - none for
 * Bulgarian or Serbian either - so for the language this keyboard was built for, the platform
 * recogniser is the only dictation there is, and it has to stay whichever becomes the default.
 */
internal interface VoiceInput {

    val isListening: Boolean

    fun start(listener: Listener)

    /** Stop and take whatever has been heard so far. */
    fun stop()

    fun destroy()

    interface Listener {
        /** Heard so far, and still subject to change. */
        fun onPartial(text: String)

        /** Settled. */
        fun onFinal(text: String)

        /** Listening has ended, with a message worth showing or null if it simply stopped. */
        fun onStopped(error: String?)
    }
}

/**
 * Dictation through whatever recogniser the phone itself provides.
 *
 * Prefers the **on-device** recogniser where the platform has one, which is not a detail: the
 * ordinary `SpeechRecognizer` on most phones sends the audio to Google to be transcribed, and
 * the on-device one does not send it anywhere. For a keyboard whose reason to exist is that
 * what you type is nobody else's business, that is the difference between a feature and a
 * contradiction - so the local one is used whenever it exists, and the networked one only when
 * there is no other way to hear anything at all.
 */
internal class PlatformVoiceInput(private val context: Context) : VoiceInput {

    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceInput.Listener? = null

    override var isListening = false
        private set

    /** True when the recogniser in use keeps the audio on the phone. */
    val isOnDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Whether this phone can transcribe anything at all.
     *
     * Worth asking before trying, because the failure is otherwise silent: with no recogniser
     * installed - or, as on this phone, one installed but not *selected* as the system's
     * (`secure voice_recognition_service` unset) - `startListening` neither works nor calls
     * back, and the microphone appears to do nothing whatsoever when pressed.
     */
    val isAvailable: Boolean
        get() = isOnDevice || (SpeechRecognizer.isRecognitionAvailable(context) && hasSelectedService)

    /**
     * Whether the system has actually *chosen* a recogniser, not merely got one installed.
     *
     * These are different questions and only the second one matters, which cost some time to
     * work out. `isRecognitionAvailable` answers the first: it returns true whenever some
     * package declares a `RecognitionService`, which on an ordinary phone is Google's search
     * app whether or not anything is set up to use it. If nothing is selected, `startListening`
     * fails immediately with a bare `ERROR_CLIENT` and one line in the log - which is exactly
     * what happens on a privacy-focused Android, where Google's recogniser is present and
     * deliberately not wired up.
     */
    private val hasSelectedService: Boolean
        get() = try {
            !Settings.Secure.getString(context.contentResolver, SELECTED_SERVICE).isNullOrBlank()
        } catch (e: Exception) {
            false
        }

    override fun start(listener: VoiceInput.Listener) {
        this.listener = listener
        stopInternal()

        if (!isAvailable) {
            listener.onStopped(
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    "no speech recogniser is selected on this phone"
                } else {
                    "no speech recogniser on this phone"
                }
            )
            return
        }

        val engine = try {
            if (isOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            listener.onStopped("speech recognition is unavailable")
            return
        }

        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { this@PlatformVoiceInput.listener?.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                best(results)?.let { this@PlatformVoiceInput.listener?.onFinal(it) }
                isListening = false
                this@PlatformVoiceInput.listener?.onStopped(null)
            }

            override fun onError(error: Int) {
                isListening = false
                this@PlatformVoiceInput.listener?.onStopped(describe(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Words as they are heard, so the text box fills in while somebody is still
            // talking rather than sitting empty until they stop.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            engine.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            listener.onStopped("speech recognition could not start")
        }
    }

    override fun stop() {
        // `stopListening` rather than `cancel`: it takes what has been said so far and
        // transcribes it, where cancelling throws it away. Somebody who has just finished a
        // sentence and pressed the button means the first.
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            stopInternal()
            listener?.onStopped(null)
        }
    }

    override fun destroy() {
        stopInternal()
        listener = null
    }

    private fun stopInternal() {
        isListening = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Nothing useful to do about a recogniser that will not shut down.
        }
        recognizer = null
    }

    private fun best(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * What to say when it stops by itself.
     *
     * Only the ones worth telling somebody about. Hearing nothing is not an error - it is what
     * happens when you press the button and then think for a moment - and saying so would put
     * a message on screen for the most ordinary thing that can happen.
     */
    private fun describe(error: Int): String? = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "the microphone is not allowed"
        // What the system returns when nothing is selected to do the recognising.
        SpeechRecognizer.ERROR_CLIENT -> "no speech recogniser is selected on this phone"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "no connection for speech recognition"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "that language cannot be dictated"
        else -> "speech recognition stopped"
    }

    private companion object {
        /** The system setting naming which installed recogniser is actually to be used. */
        const val SELECTED_SERVICE = "voice_recognition_service"
    }
}
