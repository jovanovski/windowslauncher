package rocks.gorjan.gokixp.apps.people

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import rocks.gorjan.gokixp.wp81.MessageStore

/**
 * A text message arriving, handed to this app because it is the phone's messaging app.
 *
 * `SMS_DELIVER` goes to exactly one app on the device and carries the whole job with it:
 * the platform does not write the message down, does not announce it, and does not keep a
 * copy. Whatever this receiver fails to do is a message that never existed. That is the
 * entire weight of the messaging role, and it is why the write comes first and everything
 * else after it.
 *
 * Not to be confused with `SMS_RECEIVED`, which every app with the permission gets and
 * which none of them may act on. This one is ours alone, and only while the role is held -
 * the system stops sending it the moment another app takes over, which is the whole
 * mechanism by which two messaging apps never both write the same message down.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "A message arrived that could not be read", e)
            null
        } ?: return
        if (parts.isEmpty()) return

        val first = parts[0] ?: return
        val from = first.displayOriginatingAddress?.trim().orEmpty()
        if (from.isEmpty()) return
        // A long message arrives as several parts in one broadcast, in order. Joined here
        // and stored as one row, because it is one message - the split is a fact about how
        // the network carries a hundred and sixty characters, not about what was said.
        val body = parts.joinToString("") { it?.displayMessageBody.orEmpty() }
        val sentAt = try {
            first.timestampMillis
        } catch (e: Exception) {
            0L
        }
        // Which SIM it came in on, where the phone has more than one.
        val subscription = intent.getIntExtra(SUBSCRIPTION, -1)

        // The store and the address book are both on disk and a receiver has a few
        // milliseconds to live, so the work is done on the far side of goAsync - which
        // keeps the process alive exactly as long as it takes and no longer.
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                // First, and on its own: a message that is not written down is gone, and
                // everything below this line is about a message that has been kept.
                val kept = MessageStore.writeIncoming(app, from, body, sentAt, subscription)
                if (!kept) Log.w(TAG, "An arriving message could not be stored")
                MessageNotifier.post(app, from, body)
            } catch (e: Exception) {
                Log.w(TAG, "Could not take delivery of a message", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        /** What the SIM a message arrived on is carried under. Not a named constant anywhere. */
        const val SUBSCRIPTION = "subscription"

        const val TAG = "WP81People"
    }
}
