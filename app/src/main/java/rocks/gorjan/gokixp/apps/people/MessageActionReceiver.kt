package rocks.gorjan.gokixp.apps.people

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import rocks.gorjan.gokixp.wp81.MessageStore

/**
 * The buttons on a message notification: replying, and saying it has been read.
 *
 * Both are things done *to* a conversation without opening it, which is what a shade is
 * for - and both are only possible because this app holds the messaging role. Replying
 * goes out through the same [MessageStore.send] a message typed in the app does, so a
 * reply from the shade is written into the conversation exactly like any other.
 *
 * Not exported. Nothing outside this app has any business sending messages as it.
 */
class MessageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        when (intent.action) {
            ACTION_REPLY -> reply(context, address, intent)
            ACTION_MARK_READ -> {
                MessageStore.markRead(context, address)
                MessageNotifier.clear(context, address)
            }
        }
    }

    /**
     * Sends what was typed into the notification.
     *
     * The broadcast is held open until the message is *handed to the radio*, and released
     * there - not held until the network says whether it arrived. That distinction is the
     * whole of this method: a broadcast has about ten seconds to finish, a message can take
     * longer than that to be acknowledged, and waiting for the acknowledgement meant the
     * launcher stopped answering. Which, on a phone whose home screen this is, looks
     * exactly like the phone itself dying - and did.
     *
     * The answer still arrives; it is simply not what the broadcast waits on. If the
     * message did not go, that is said afterwards in a notification of its own, which is
     * the right place for it anyway - the shade is where this conversation is happening.
     */
    private fun reply(context: Context, address: String, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val app = context.applicationContext
        val pending = goAsync()
        // Taken down now rather than when the send finishes: it has been answered, and a
        // notification that lingers while the radio thinks invites a second answer.
        MessageNotifier.clear(app, address)
        MessageStore.markRead(app, address)
        // Finished at most once, from whichever of the two paths gets there first.
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                try {
                    pending.finish()
                } catch (e: Exception) {
                    Log.d(TAG, "The broadcast was already finished", e)
                }
            }
        }
        try {
            MessageStore.send(
                app,
                address,
                text,
                onDispatched = release,
                onDone = { problem ->
                    if (problem != null) {
                        Log.w(TAG, "A reply from the shade would not send: $problem")
                        MessageNotifier.failed(app, address, text)
                    }
                    // Belt and braces: if the message never reached the radio at all, the
                    // dispatch signal may not have come, and a broadcast nobody finishes is
                    // the bug this method exists to not have.
                    release()
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not reply from the shade", e)
            MessageNotifier.failed(app, address, text)
            release()
        }
    }

    companion object {
        private const val TAG = "WP81People"

        const val ACTION_REPLY = "rocks.gorjan.gokixp.message.REPLY"
        const val ACTION_MARK_READ = "rocks.gorjan.gokixp.message.MARK_READ"

        /** Who the action is about. */
        const val EXTRA_ADDRESS = "address"

        /** What the typed reply arrives under. */
        const val KEY_REPLY = "reply"

        /**
         * A pending broadcast for one of the above.
         *
         * The reply's own is mutable, which is the one exception in this app and a
         * required one: a remote input works by having the system write what was typed
         * into the intent before it is sent, and an immutable one arrives empty. Every
         * other pending intent here is immutable.
         *
         * The number is in the request code as well as in the extras, because two pending
         * intents that differ only in what they carry are the same one as far as the
         * system is concerned - without it, a reply would go to whoever texted first.
         */
        fun pending(
            context: Context,
            action: String,
            address: String,
            mutable: Boolean = false
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (action + address).hashCode(),
                Intent(context, MessageActionReceiver::class.java)
                    .setAction(action)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ADDRESS, address),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            )
    }
}
