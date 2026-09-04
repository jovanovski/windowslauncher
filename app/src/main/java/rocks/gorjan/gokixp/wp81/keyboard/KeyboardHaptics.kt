package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.Haptics

/**
 * How hard a key buzzes, and the one place in the shell allowed to answer that itself.
 *
 * `Haptics` carries an explicit rule and it is a good one: there is a single tick, it goes
 * through [View.performHapticFeedback] rather than the vibrator so it inherits whatever
 * waveform the manufacturer tuned, and it stays silent when the user has turned touch
 * feedback off. Nothing in the shell should be second-guessing any of that.
 *
 * A keyboard is the exception, and narrowly. A tile is tapped a few times a minute and a key
 * is tapped twenty times in ten seconds, which is the rate at which a buzz that is slightly
 * too heavy stops being feedback and becomes the phone shaking in your hand - and slightly
 * too light stops being felt at all. Where that line falls is a property of the phone's motor
 * and of the person holding it, and it is the one haptic in this app somebody has a real
 * reason to want to move. Every keyboard worth using has this control.
 *
 * So it is **additive**: the default is [ThemeManager.WP81_KB_VIBRATION_SYSTEM], which calls
 * straight through to [Haptics] and behaves exactly as the calculator does today, and the
 * vibrator is touched only by somebody who went and asked for it. That override is total -
 * it also overrides the system's touch-feedback switch, which is the point of an override and
 * is why it is not where the setting starts.
 *
 * [strength] is a field rather than a preference read, because these fire on the touch path:
 * the service sets it when the keyboard appears and nothing here goes near `SharedPreferences`
 * with a finger down.
 */
internal object KeyboardHaptics {

    /** [ThemeManager.WP81_KB_VIBRATION_SYSTEM], or 0 (silent) to 100. */
    @Volatile
    var strength: Int = ThemeManager.WP81_KB_VIBRATION_SYSTEM

    private var vibrator: Vibrator? = null

    /** Read once. The answer cannot change while the app is running. */
    private var amplitudeControl: Boolean? = null

    /** Takes the setting from [ThemeManager] and holds the vibrator this will need. */
    fun refresh(context: Context, themeManager: ThemeManager) {
        strength = themeManager.getWP81KeyboardVibration()
        if (vibrator == null) vibrator = vibratorOf(context)
    }

    /**
     * A keystroke - the light one.
     *
     * See [Haptics.key] for why a keystroke is not a tap: twenty of them happen in a row, so
     * the platform has a separate, fainter waveform for exactly this and the shell hands it
     * back rather than inventing one.
     */
    fun key(view: View) {
        if (strength == ThemeManager.WP81_KB_VIBRATION_SYSTEM) Haptics.key(view)
        else buzz(KEY_MS)
    }

    /**
     * A hold, or the moment a repeat begins - the heavier one.
     *
     * Twice the key's, on both paths, because a hold is a different event and has to be
     * distinguishable from the tick that came before it. On the system path that distinction
     * is the framework's two waveforms; here it is the duration.
     */
    fun tap(view: View) {
        if (strength == ThemeManager.WP81_KB_VIBRATION_SYSTEM) Haptics.tap(view)
        else buzz(HOLD_MS)
    }

    /**
     * The explicit path: one short pulse, as hard as was asked for.
     *
     * Amplitude where the motor has it, and duration where it does not - a device without
     * amplitude control can only be given a longer or shorter knock, so that is what the
     * slider moves there. It is a worse control on such a phone and it is not nothing, which
     * is the most that can be done: `hasAmplitudeControl` is false on a great many devices
     * and treating them as unable to vibrate at all would be the wrong call.
     */
    private fun buzz(baseMs: Long) {
        val level = strength
        if (level <= 0) return
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return

        try {
            val hasAmplitude = amplitudeControl ?: motor.hasAmplitudeControl().also {
                amplitudeControl = it
            }
            val effect = if (hasAmplitude) {
                // Amplitude runs 1..255 and the bottom of that range does not reach the
                // motor's starting threshold on most phones, so the slider's own range is
                // mapped onto the part of it that can actually be felt.
                val amplitude = MIN_AMPLITUDE +
                    (level * (255 - MIN_AMPLITUDE) / ThemeManager.WP81_KB_VIBRATION_MAX)
                VibrationEffect.createOneShot(baseMs, amplitude.coerceIn(1, 255))
            } else {
                val millis = baseMs * level / ThemeManager.WP81_KB_VIBRATION_MAX
                VibrationEffect.createOneShot(
                    millis.coerceAtLeast(MIN_MS),
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            }
            motor.vibrate(effect)
        } catch (e: Exception) {
            // A keyboard that cannot buzz still types.
        }
    }

    private fun vibratorOf(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * A keystroke, and a hold.
     *
     * Both are short enough to be a knock rather than a buzz. Twelve milliseconds is about
     * where a pulse stops being felt as having a length; anything appreciably longer under
     * every letter runs the strokes of fast typing into one continuous vibration.
     */
    private const val KEY_MS = 12L
    private const val HOLD_MS = 24L

    /** Below this the motor on most phones does not move at all. */
    private const val MIN_AMPLITUDE = 40

    /** And below this a pulse is too short to start one. */
    private const val MIN_MS = 5L
}
