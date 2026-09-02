package rocks.gorjan.gokixp.apps.people

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import rocks.gorjan.gokixp.wp81.MessageStore

/**
 * "Can't talk now" - the message a ringing phone offers to send instead of answering.
 *
 * Declaring this is one of the four things an app must do to be offered the messaging
 * role, and unlike the picture-message receiver it does real work: when any dialler on the
 * phone declines a call with a message, Telecom hands the words to whichever app is the
 * messaging app and that app sends them.
 *
 * Which closes a loop this shell already had. The quick replies on the incoming call
 * screen decline with [android.telecom.Call.reject] and a line of text, and Telecom
 * carries that here - so with both roles held, People asks itself to send its own reply,
 * and the message lands in the conversation with that person like any other.
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val recipients = recipientsOf(intent)
        if (text.isNotEmpty()) {
            for (number in recipients) {
                MessageStore.send(applicationContext, number, text) { problem ->
                    if (problem != null) Log.w(TAG, "A call reply would not send: $problem")
                }
            }
        }
        // Not sticky. The request was to send one message; there is nothing to resume if
        // the phone runs out of memory in the middle of a call.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * Who to send it to, out of the `smsto:` the intent carries.
     *
     * Several are possible - a conference call declined with a message - and they arrive
     * separated by semicolons or commas. Each gets its own message, because this app sends
     * text messages and a text to several people at once is a multimedia message.
     */
    private fun recipientsOf(intent: Intent?): List<String> {
        val part = intent?.data?.schemeSpecificPart ?: return emptyList()
        return part.substringBefore('?')
            .split(';', ',')
            .map { android.net.Uri.decode(it).trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "WP81People"
    }
}
