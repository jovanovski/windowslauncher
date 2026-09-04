package rocks.gorjan.gokixp.apps.people

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * The launcher, as the phone.
 *
 * Android will only let one app draw the screen a call is on, and it decides which by the
 * default-phone-app role: whoever holds it gets bound to this service and is handed every
 * call on the device, incoming and outgoing, from this app or any other. There is no
 * halfway - an app cannot answer its own calls and leave the rest to somebody else - so
 * holding the role is the price of having a call screen at all. See [PeopleApp]'s offer to
 * take it, which is the only place it is ever asked for.
 *
 * Almost nothing happens here. The calls go to [CallCentre], which the screen reads; this
 * exists to be bound, to pass things on, and to make sure there is a screen to pass them
 * to - which on a locked phone means a notification with a full-screen intent, because a
 * service is not allowed to start an activity out of the blue and a ringing phone is
 * exactly the case that rule was written to allow.
 */
class GokiInCallService : InCallService() {

    /**
     * Redraws the notification whenever the calls change, or whenever what is on screen does.
     *
     * The buttons on it are stateful - speaker is on or it is not - and a notification is a
     * picture rather than a live view, so the only way for it to stay true is to post it
     * again. Which is cheap: the system replaces the one already showing, in place. The same
     * callback carries word that the call screen has come or gone, which is the other thing
     * [sync] changes its mind on.
     */
    private val onCallsChanged = {
        sync()
        updateProximity()
    }

    override fun onCreate() {
        super.onCreate()
        CallCentre.listen(onCallsChanged)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallCentre.service = this
        CallCentre.add(applicationContext, call)
        ring()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallCentre.remove(call)
        if (CallCentre.isEmpty()) {
            CallCentre.service = null
            notifications().cancel(NOTIFICATION_ID)
        }
        updateProximity()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        CallCentre.onAudioChanged(audioState)
        // Putting a call on speaker is putting the phone down, and the screen should come
        // back the moment it happens rather than the next time anything else changes.
        updateProximity()
    }

    override fun onDestroy() {
        super.onDestroy()
        ProximityLock.release()
        CallCentre.forget(onCallsChanged)
        if (CallCentre.service === this) CallCentre.service = null
        // Telecom unbinds this the moment the last call is gone, and if the process goes
        // with it there is nobody left to take the notification down - which leaves an
        // ongoing call notification, undismissable by hand, about a call that ended. See
        // also [clearIfIdle], which sweeps one left by a process that never got here.
        if (CallCentre.isEmpty()) notifications().cancel(NOTIFICATION_ID)
    }

    // ---------------------------------------------------------------- the ear

    /**
     * The screen off while the phone is against somebody's head.
     *
     * Through the platform's own proximity wake lock rather than by reading the sensor and
     * dimming the window: what that lock does is turn the *display* off, which takes the
     * touchscreen with it. Reading the sensor ourselves could only black the picture out,
     * leaving a screen that is still listening - and a cheek on a live touchscreen is how a
     * call ends up on hold, on speaker, or hung up. It is also the only version that keeps
     * working once the user has left the call screen for another app.
     *
     * Only while a call is actually up: during ringing the phone is usually in a pocket or
     * face down on a table, and a lock held then would blank the screen of the very thing
     * being answered. And only on the earpiece - a speaker, a headset or a car is a call
     * being held at arm's length, where the screen is wanted.
     */
    private fun updateProximity() {
        val line = CallCentre.primary()
        val wanted = line != null &&
            line.stage != CallCentre.Stage.INCOMING &&
            line.stage != CallCentre.Stage.ENDED &&
            CallCentre.onEarpiece
        if (wanted) ProximityLock.hold(this) else ProximityLock.release()
    }

    /**
     * Gets the call screen in front of the user, whatever the phone is doing.
     *
     * Both ways at once, on purpose. The launcher is the home screen, so most of the time
     * it is the foreground app and can simply start the activity - which is instant, and is
     * what somebody who has just tapped "call" expects. A locked or sleeping phone allows
     * no such thing, and there the notification's full-screen intent is what the system
     * honours instead. Whichever gets there first, the activity is a single instance, so
     * the other finds it already up rather than starting a second one.
     */
    private fun ring() {
        val screen = InCallActivity.intentFor(this)
        sync()
        try {
            startActivity(screen)
        } catch (e: Exception) {
            // Expected on a locked phone, and not a failure: the notification above is the
            // path the system has left open for exactly this, and it has already been sent.
            Log.d(TAG, "Could not open the call screen directly; the notification has it", e)
        }
    }

    /**
     * Decides whether there should be a notification at all, and there usually is not.
     *
     * Two reasons for one to exist: there is a call, and the call screen is not what the
     * user is looking at. A notification about the very screen somebody is on is the call
     * twice over - a card in the shade behind it, and on a modern phone a chip in the
     * status bar sitting on top of the call screen's own - so while that screen is up this
     * takes it away, and puts it back the moment the screen is left for anything else.
     *
     * Called for every change to the calls and for every change of what is on screen,
     * because either can be what makes the answer different from last time.
     */
    private fun sync() {
        if (CallCentre.isEmpty() || CallCentre.screenShowing) {
            notifications().cancel(NOTIFICATION_ID)
        } else {
            post()
        }
    }

    /**
     * Puts the notification up, and does not take the call down with it if it cannot.
     *
     * The system is strict about what a call notification may be - a CallStyle one is
     * rejected outright unless it carries a full-screen intent, and the rules have moved
     * with nearly every release. Throwing here would take out the service that is holding
     * somebody's call, which is a far worse outcome than a call with no notification, so a
     * refusal is logged and the screen carries on alone.
     */
    private fun post() {
        try {
            notifications().notify(NOTIFICATION_ID, notification(InCallActivity.intentFor(this)))
        } catch (e: Exception) {
            Log.w(TAG, "The system would not take the call notification", e)
        }
    }

    /**
     * The call, in the shade.
     *
     * Worth building properly rather than as a label with a tap target, because it is what
     * somebody sees when the call screen is not in front of them - the phone in a pocket,
     * or another app open over it - and hanging up should not require going back to the
     * screen first.
     *
     * [Notification.CallStyle] where the platform has it, which is every phone this would
     * actually be used on. It is the reason the hang-up button is red: the system draws
     * that one itself, in the colour it uses for ending a call everywhere else, and an app
     * cannot tint an ordinary action at all. Speaker and mute go on beside it as plain
     * actions, which CallStyle keeps.
     */
    private fun notification(screen: Intent): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this, 0, screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val line = CallCentre.primary()
        val ringing = line?.stage == CallCentre.Stage.INCOMING

        val builder = Notification.Builder(this, if (ringing) CHANNEL else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.wp81_notify_call)
            .metroLook(this)
            .setContentTitle(line?.title ?: "Call")
            .setContentText(if (ringing) "Incoming call" else "In call")
            .setContentIntent(open)
            // What tells the system this may take over a locked screen. Without the
            // category it is an ordinary notification and the phone rings behind whatever
            // the user was looking at.
            .setCategory(Notification.CATEGORY_CALL)
            // Carried by a call in progress too, which never wants to be flung at anybody:
            // since Android 14 the system throws out a CallStyle notification with no
            // full-screen intent unless it belongs to a foreground service, and this one
            // belongs to a bound InCallService. What decides whether the screen is actually
            // taken over is the channel it went out on - see [ensureChannels].
            .setFullScreenIntent(open, ringing)
            .setOngoing(true)

        val hangUp = CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANG_UP)
        val answer = CallActionReceiver.pending(this, CallActionReceiver.ACTION_ANSWER)
        val decline = CallActionReceiver.pending(this, CallActionReceiver.ACTION_DECLINE)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val who = android.app.Person.Builder()
                .setName(line?.title ?: "Call")
                .build()
            builder.setStyle(
                if (ringing) Notification.CallStyle.forIncomingCall(who, decline, answer)
                else Notification.CallStyle.forOngoingCall(who, hangUp)
            )
        } else {
            if (ringing) {
                builder.addAction(action("answer", ANSWER_ICON, answer))
                builder.addAction(action("decline", HANGUP_ICON, decline))
            } else {
                builder.addAction(action("hang up", HANGUP_ICON, hangUp))
            }
        }

        // The two switches, on a call that is up. A ringing phone has nothing to mute.
        //
        // Capitalised, and with no mark of their own. These sit beside a hang-up button
        // the system draws itself, in the system's own words and the system's own casing -
        // this shell's lowercase is a rule about its own surfaces, and the shade is not one
        // of them. The marks go because the system's button has none: two glyphs and a
        // plain word in a row read as one button missing its icon.
        if (!ringing) {
            builder.addAction(
                plainAction(
                    if (CallCentre.speaker) "Earpiece" else "Speaker",
                    CallActionReceiver.pending(this, CallActionReceiver.ACTION_SPEAKER)
                )
            )
            builder.addAction(
                plainAction(
                    if (CallCentre.muted) "Unmute" else "Mute",
                    CallActionReceiver.pending(this, CallActionReceiver.ACTION_MUTE)
                )
            )
        }
        return builder.build()
    }

    private fun action(
        label: String,
        asset: String,
        intent: PendingIntent
    ): Notification.Action =
        Notification.Action.Builder(icon(asset), label, intent).build()

    /** A word and nothing else. An action's icon may be left out, and here it is. */
    private fun plainAction(label: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, intent)
            .build()

    /**
     * One of the app bar's own glyphs, as something a notification will take.
     *
     * Drawn into a bitmap rather than pulled from the drawables, because these icons live
     * in the assets as SVG paths - see [rocks.gorjan.gokixp.wp81.SvgIcon] - and there is no
     * resource id to hand a notification. White, which is what an action icon is tinted to
     * anyway.
     */
    private fun icon(asset: String): android.graphics.drawable.Icon {
        val side = (ICON_DP * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(
            side, side, android.graphics.Bitmap.Config.ARGB_8888)
        rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(this, asset)?.apply {
            setBounds(0, 0, side, side)
            draw(android.graphics.Canvas(bitmap))
        }
        return android.graphics.drawable.Icon.createWithBitmap(bitmap)
    }

    /**
     * Two channels, because a ringing phone and a call in progress want opposite things.
     *
     * The ring has to be able to take over whatever the screen is doing, and a full-screen
     * intent is only honoured on a channel at high importance. A call already in progress
     * must not: its notification goes up the moment the call screen is left, and at high
     * importance the phone would light itself back up and put the call screen straight back
     * the instant somebody pressed the power button to put it away. Low is silent and does
     * not push in, while still showing the call in the shade and in the status bar, which is
     * all the in-progress one was ever for.
     */
    private fun ensureChannels() {
        val manager = notifications()
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "A phone that is ringing"
                    // The network is already ringing the phone; a second sound over the top
                    // of it is this app ringing as well.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING, "Calls in progress", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "The call you are on, while you are looking at something else"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun notifications() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        /**
         * Takes down a call notification that outlived its call.
         *
         * One can be left behind by a process killed mid-call - an update, or the system
         * reclaiming memory - and because it is an ongoing call notification the user
         * cannot swipe it away. Called as the launcher starts, which is the next moment
         * anything of this app's is running to notice.
         */
        fun clearIfIdle(context: Context) {
            if (!CallCentre.isEmpty()) return
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }

        const val CHANNEL = "wp81_calls"
        const val CHANNEL_ONGOING = "wp81_call_ongoing"
        const val NOTIFICATION_ID = 8101
        const val TAG = "WP81Phone"

        const val ICON_DIR = "custom_icons_8"
        const val ANSWER_ICON = "$ICON_DIR/appbar.phone.svg"
        const val HANGUP_ICON = "$ICON_DIR/appbar.phone.hangup.svg"
        const val ICON_DP = 24
    }
}
