package rocks.gorjan.gokixp.apps.people

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * A picture message arriving, which this app does not do - said out loud.
 *
 * It has to exist. An app is only offered the messaging role if it declares a receiver for
 * this, alongside the one for text messages, an activity that answers `SENDTO` and a
 * service that answers `RESPOND_VIA_MESSAGE`; three of those four do real work and this is
 * the fourth. Without it People cannot be the phone's messaging app at all.
 *
 * What arrives here is not the picture. It is a push saying one is waiting on the network,
 * and fetching it means decoding a binary PDU, opening a connection on the carrier's own
 * APN and reassembling the parts - a protocol, not a feature, and one this app has
 * deliberately not taken on.
 *
 * So the message is not shown, and the notice says so. That is the point of this class:
 * the push is delivered to one app and consumed, so a picture message that arrived while
 * People held the role is a picture message nobody else will ever show either. Silence
 * would make that look like nothing had happened - somebody sent something, and the phone
 * would simply never mention it. This at least tells the reader there is something they
 * are not seeing, and who to ask about it.
 *
 * Group messages are the common case, because most carriers carry a text to several people
 * as a multimedia message.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WAP_PUSH_DELIVER) return
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_MESSAGES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.wp81_notify_message)
                        .metroLook(context)
                        .setContentTitle("Picture message")
                        .setContentText("People does not show picture messages")
                        .setStyle(
                            NotificationCompat.BigTextStyle().bigText(
                                "Somebody sent a picture or group message. People handles " +
                                    "text messages only, so this one cannot be opened here."
                            )
                        )
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context, 0, open,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setAutoCancel(true)
                        .build()
                )
        } catch (e: Exception) {
            Log.w(TAG, "Could not report a picture message", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Picture messages", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Messages that arrived and cannot be shown here" }
        )
    }

    private companion object {
        /**
         * `Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION`, written out.
         *
         * The constant is hidden on the platform - only the manifest string is public - so
         * the action is compared against the same text the filter declares.
         */
        const val WAP_PUSH_DELIVER = "android.provider.Telephony.WAP_PUSH_DELIVER"

        const val CHANNEL = "wp81_picture_messages"

        /** One at a time. Two unopenable messages are the same fact said twice. */
        const val NOTIFICATION_ID = 8103

        const val TAG = "WP81People"
    }
}
