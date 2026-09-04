package rocks.gorjan.gokixp.apps.people

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The window a call happens in.
 *
 * An activity of its own rather than a page inside the launcher, for one reason: most
 * calls arrive when the launcher is not what you are looking at. The phone may be locked,
 * face down, or showing somebody else's app, and only an activity can be put in front of
 * all three - see [android.app.Activity.setShowWhenLocked], which is how a call screen has
 * been allowed over a lock screen since Android 8.
 *
 * It holds nothing. [CallScreen] reads [CallCentre] directly, so this is a frame around
 * that and a set of window flags, and it goes away the moment there is no call left.
 */
class InCallActivity : Activity() {

    private var screen: CallScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Over the lock screen, and it wakes the phone to get there. A ringing telephone
        // that lit nothing until it was picked up would be a telephone nobody answers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The shell's accent, because this is the shell's phone - the one thing the call
        // screen takes from the theme. Its black and its white are its own; see CallScreen.
        val palette = WP81Palette.from(ThemeManager(this))
        val view = CallScreen(this, palette) { finishAndRemoveTask() }
        screen = view
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        // On screen, so the call has no business in the notification shade as well. See
        // [CallCentre.screenShowing].
        CallCentre.screenShowing = true
    }

    override fun onStop() {
        super.onStop()
        // Gone - home, another app, or a phone that has been put to sleep - and from here
        // the notification is the only way back to the call.
        CallCentre.screenShowing = false
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        screen?.bind()
    }

    override fun onResume() {
        super.onResume()
        // The calls may have moved on entirely while this was off screen - answered on a
        // watch, or ended - so what is drawn is re-read rather than assumed.
        screen?.bind()
    }

    /**
     * Back does not end a call.
     *
     * It closes whatever is open over the call - the keypad, the quick replies - and
     * otherwise does nothing at all: on every phone ever made, the way to end a call is to
     * say so, and a stray press of a key at the side of the screen is not saying so. The
     * call screen is left by going home, which leaves the call running exactly as it should.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen?.handleBack() == true) return
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        screen = null
    }

    companion object {
        /**
         * The intent that opens this, from the service or from a notification.
         *
         * A task of its own that is not kept in recents: a call is not somewhere you go
         * back to afterwards, and a dead call screen sitting in the switcher is litter.
         */
        fun intentFor(context: Context): Intent =
            Intent(context, InCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
    }
}
