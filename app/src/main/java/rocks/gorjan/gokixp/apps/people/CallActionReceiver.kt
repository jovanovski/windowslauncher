package rocks.gorjan.gokixp.apps.people

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The buttons on the call notification, arriving as broadcasts.
 *
 * A notification's actions have to be [android.app.PendingIntent]s, and the only kind that
 * does something without putting a screen in front of the user is a broadcast - which is
 * exactly what these are for: answering, hanging up, and the two switches you would reach
 * for without looking. Every one of them is a single line into [CallCentre], because that
 * is where the calls are and this receiver holds nothing of its own.
 *
 * Not exported. Nothing outside this app has any business hanging up its calls.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallCentre.answer()
            ACTION_DECLINE -> CallCentre.reject()
            ACTION_HANG_UP -> CallCentre.hangUp()
            // Toggles rather than settings, because the button is a button and there is
            // only one of it: it says what the phone will do next, not what it is doing.
            ACTION_SPEAKER -> CallCentre.speaker = !CallCentre.speaker
            ACTION_MUTE -> CallCentre.muted = !CallCentre.muted
            ACTION_CALL_BACK -> callBack(context, intent.getStringExtra(EXTRA_NUMBER))
        }
    }

    /**
     * Rings back whoever was missed, from the notification and without opening anything.
     *
     * Through Telecom rather than by throwing a `tel:` intent at the system, because this
     * app is the phone: the call is asked for directly and its own screen comes up to meet
     * it. The notification goes first - it is about a call that is no longer missed the
     * moment this is tapped, and leaving it up to be tapped twice is how somebody rings a
     * person back two or three times.
     */
    private fun callBack(context: Context, number: String?) {
        if (number.isNullOrBlank()) return
        MissedCallReceiver.clear(context)
        val telecom = context.getSystemService(Context.TELECOM_SERVICE)
            as? android.telecom.TelecomManager
        try {
            telecom?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Only the default phone app may say this, and it is not always this one.
            Log.d(TAG, "Could not clear the missed calls", e)
        }
        val uri = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
        val allowed = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            if (allowed && telecom != null) {
                telecom.placeCall(uri, android.os.Bundle())
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Telecom would not ring back; handing it to the dialler", e)
        }
        // Nothing to place the call with, so the number is handed to whatever dials. From
        // a receiver that has to be a task of its own - there is no activity behind this.
        try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Nothing on this phone dials", e)
        }
    }

    companion object {
        private const val TAG = "WP81Phone"

        const val ACTION_ANSWER = "rocks.gorjan.gokixp.call.ANSWER"
        const val ACTION_DECLINE = "rocks.gorjan.gokixp.call.DECLINE"
        const val ACTION_HANG_UP = "rocks.gorjan.gokixp.call.HANG_UP"
        const val ACTION_SPEAKER = "rocks.gorjan.gokixp.call.SPEAKER"
        const val ACTION_MUTE = "rocks.gorjan.gokixp.call.MUTE"
        const val ACTION_CALL_BACK = "rocks.gorjan.gokixp.call.CALL_BACK"

        /** Who to ring, for [ACTION_CALL_BACK]. */
        const val EXTRA_NUMBER = "number"

        /**
         * A pending broadcast for one of the above.
         *
         * Immutable, and given a request code of its own per action: two pending intents
         * that differ only in their action are "the same" as far as the system is
         * concerned, and without distinct codes every button on the notification would end
         * up doing whatever the first one did.
         */
        fun pending(
            context: Context,
            action: String,
            number: String? = null
        ): android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                context,
                // The number is in the code as well as in the extras: two pending intents
                // that differ only in what they carry are the same one as far as the system
                // is concerned, so without it a call back would ring whoever was missed
                // first rather than whoever this notification is about.
                (action + number.orEmpty()).hashCode(),
                Intent(context, CallActionReceiver::class.java)
                    .setAction(action)
                    .setPackage(context.packageName)
                    .apply { number?.let { putExtra(EXTRA_NUMBER, it) } },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
    }
}
