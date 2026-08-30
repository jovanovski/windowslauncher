package rocks.gorjan.gokixp.wp81

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The one buzz the phone shell has.
 *
 * Windows Phone answered a command with a single short tick and never varied it: the
 * hold that picks a tile up off Start, the capacitive keys under the screen, a button on
 * an app bar and a row in a menu all felt the same in the hand. Two ticks of different
 * weight in the same shell do not read as two kinds of event, they read as a phone whose
 * motor is inconsistent - which is what had happened here, with the browser's app bar
 * buzzing on its own 50ms timer while a tile used the system's long-press tick.
 *
 * So there is one function, and it is the tile's: [HapticFeedbackConstants.LONG_PRESS],
 * the same constant the framework fires by itself when a view claims a long press. Going
 * through the view rather than the vibrator is the point of it - it picks up the device's
 * own long-press waveform, whatever the manufacturer tuned that to, and it stays silent
 * when the user has turned touch feedback off. A hand-rolled one-shot did neither.
 *
 * A view that answers a hold by returning true from its long-click listener must NOT call
 * this: the framework gives it this exact tick as soon as the press is claimed, and a
 * second one fired by hand is what makes a hold feel like two knocks.
 */
object Haptics {

    /** Tick, for a view that has just been tapped. See the note above about holds. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
