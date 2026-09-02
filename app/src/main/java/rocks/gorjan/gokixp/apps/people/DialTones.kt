package rocks.gorjan.gokixp.apps.people

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The sound a telephone key makes.
 *
 * Two tones at once, one from each of two frequency bands - which is what "touch-tone"
 * means, and why a dialled number is recognisable as a tune. Android generates them for
 * free through [ToneGenerator]; what is worth writing down is when to make one and when
 * not to.
 *
 * The same tone in both places it can be typed. On the keypad it is feedback - the only
 * confirmation that a key registered, since the number at the top of the screen is where
 * the eye is not. In a call it is the same tone the network is being sent, played locally
 * so that pressing 4 for accounts sounds like something happened rather than like nothing
 * did.
 *
 * The generator is a native audio resource, so it is opened on the first key and let go a
 * few seconds after the last one. Neither end of that is worth plumbing through the two
 * keypads by hand: a dial is a burst of presses with long silences either side, and an
 * idle timer describes that exactly.
 */
object DialTones {

    private val main = Handler(Looper.getMainLooper())

    private var generator: ToneGenerator? = null

    private val release = Runnable { close() }

    /** Sounds the key for [digit]. */
    fun press(digit: Char) {
        val tone = toneFor(digit) ?: return
        val player = open() ?: return
        try {
            player.startTone(tone, LENGTH_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not sound a key", e)
        }
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_MS)
    }

    private fun open(): ToneGenerator? {
        generator?.let { return it }
        return try {
            ToneGenerator(STREAM, VOLUME).also { generator = it }
        } catch (e: Exception) {
            // Thrown when the device has no spare audio resources, which is a real thing
            // that happens and is not worth a key press failing over.
            Log.w(TAG, "No tone generator available", e)
            null
        }
    }

    /** Hands the audio resource back. Called by the idle timer, and safe to call twice. */
    fun close() {
        main.removeCallbacks(release)
        val player = generator ?: return
        generator = null
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the tone generator", e)
        }
    }

    private fun toneFor(digit: Char): Int? = when (digit) {
        '0' -> ToneGenerator.TONE_DTMF_0
        '1' -> ToneGenerator.TONE_DTMF_1
        '2' -> ToneGenerator.TONE_DTMF_2
        '3' -> ToneGenerator.TONE_DTMF_3
        '4' -> ToneGenerator.TONE_DTMF_4
        '5' -> ToneGenerator.TONE_DTMF_5
        '6' -> ToneGenerator.TONE_DTMF_6
        '7' -> ToneGenerator.TONE_DTMF_7
        '8' -> ToneGenerator.TONE_DTMF_8
        '9' -> ToneGenerator.TONE_DTMF_9
        // The two keys that are not digits, and are not called star and hash by the
        // standard that defines the tones: S for star, P for pound.
        '*' -> ToneGenerator.TONE_DTMF_S
        '#' -> ToneGenerator.TONE_DTMF_P
        else -> null
    }

    /**
     * How long a key sounds for.
     *
     * A fixed length rather than for as long as the key is held. The phone's own setting
     * has both - see `dtmf_tone_type` - and the fixed one is the default and the one that
     * suits a screen: there is no travel in a glass key, so a tone that ran until release
     * would be as long as the finger happened to linger.
     */
    private const val LENGTH_MS = 150

    /** How long the generator is kept after the last key, against the next one. */
    private const val IDLE_MS = 4_000L

    /**
     * Which volume a key is sounded at.
     *
     * The media stream rather than the dial-tone one. `STREAM_DTMF` is the obvious choice
     * and the wrong one here: it is tied to the ringer, so a phone on vibrate makes no
     * sound at all from it - and a keypad that goes silent because the ringer is off has
     * confused two different questions. What the key sounds at is what everything else on
     * the phone sounds at.
     */
    private const val STREAM = AudioManager.STREAM_MUSIC

    /** Out of a hundred. Loud enough to hear over a room, quiet enough not to startle. */
    private const val VOLUME = 80

    private const val TAG = "WP81Phone"
}
