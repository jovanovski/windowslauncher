package rocks.gorjan.gokixp.apps.people

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MessageStore
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * A message, in the shade, as the person who sent it.
 *
 * This exists because of the role. While another app is the phone's messaging app, that
 * app announces arriving messages and there is nothing for the launcher to do; the moment
 * People takes the role, that app is not told a message arrived at all - so a text that is
 * not announced here is a text nobody ever finds out about. It is the other half of
 * [MessageStore.writeIncoming], and just as non-optional.
 *
 * Built as a conversation - a [NotificationCompat.MessagingStyle] against a [Person], with
 * a long-lived shortcut behind it - which is the one arrangement that puts somebody's face
 * where an app's icon would otherwise be, and the same one the missed call uses. See
 * [MissedCallReceiver] for the longer note on why a plain notification with a large icon is
 * not the same thing.
 *
 * One notification per conversation, replaced rather than stacked, carrying the last few
 * messages: two texts from the same person are one conversation with two lines in it, and
 * two entries in the shade for one conversation is a shade that has to be cleared twice.
 */
object MessageNotifier {

    /**
     * Puts an arriving message up, with whatever else has recently been said.
     *
     * On a worker thread: it reads the address book and the message store, and it is
     * called from a receiver that is already on one.
     */
    fun post(context: Context, address: String, body: String) {
        val person = PeopleStore.lookupNow(context, address)
        val who = person?.name?.takeIf { it.isNotBlank() } ?: address
        val face = facePhoto(context, person?.photoUri)
        ensureChannel(context)

        val them = Person.Builder()
            .setName(who)
            .setKey(MessageStore.keyOf(address))
            .setImportant(true)
            .apply { face?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) } }
            .build()
        // The reader themself, which MessagingStyle wants so it knows which side is which.
        val me = Person.Builder().setName("You").setKey("me").build()

        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_MESSAGE_THREAD)
            .putExtra(MainActivity.EXTRA_MESSAGE_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = idFor(address)
        val opening = PendingIntent.getActivity(
            context, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The conversation as it stands, not just the line that has just arrived: a
        // message that lands while an earlier one is still unread should show both, and
        // the store is the only thing that remembers there was an earlier one.
        val style = NotificationCompat.MessagingStyle(me)
        val recent = MessageStore.recentNow(context, address, HISTORY)
        for (message in recent) {
            style.addMessage(message.body, message.at, if (message.outgoing) null else them)
        }
        if (recent.isEmpty()) {
            style.addMessage(body, System.currentTimeMillis(), them)
        }

        val shortcut = "message:" + MessageStore.keyOf(address)
        pushConversation(context, shortcut, who, face, them, open)

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .metroLook(context)
            // The speech bubble, not a face: the small icon is what says at a glance in
            // the status bar what kind of thing this is.
            .setSmallIcon(R.drawable.wp81_notify_message)
            .setContentIntent(opening)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(style)
            .setShortcutId(shortcut)
            .addPerson(them)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Answering is the thing a message is about, and doing it without leaving whatever
        // is on screen is most of what a modern shade is for. The reply goes out through
        // the same send as one typed in the app; see MessageActionReceiver.
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Reply",
                MessageActionReceiver.pending(
                    context, MessageActionReceiver.ACTION_REPLY, address, mutable = true)
            )
                .addRemoteInput(
                    RemoteInput.Builder(MessageActionReceiver.KEY_REPLY)
                        .setLabel("message")
                        .build()
                )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Mark as read",
                MessageActionReceiver.pending(
                    context, MessageActionReceiver.ACTION_MARK_READ, address)
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        )

        try {
            notifications(context).notify(id, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Could not announce a message", e)
        }
    }

    /**
     * A message that would not go, said once and plainly.
     *
     * Only for the ones sent from the shade. A send from inside the app has the app itself
     * to say so in - the bubble goes red and offers to try again - and a notification
     * about a screen the user is looking at is a notification saying what they can see.
     */
    fun failed(context: Context, address: String, body: String) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_MESSAGE_THREAD)
            .putExtra(MainActivity.EXTRA_MESSAGE_ADDRESS, address)
            // Handed back so it is in the box when the conversation opens: the words were
            // typed once already and losing them is the one unforgivable part of failing.
            .putExtra(MainActivity.EXTRA_MESSAGE_DRAFT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = idFor(address) + FAILED_OFFSET
        try {
            notifications(context).notify(
                id,
                NotificationCompat.Builder(context, CHANNEL)
                    .metroLook(context)
                    .setSmallIcon(R.drawable.wp81_notify_message)
                    .setContentTitle("Message not sent")
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, id, open,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not report a message that would not send", e)
        }
    }

    /** Takes a conversation's notification down, for when it has been read. */
    fun clear(context: Context, address: String) {
        try {
            notifications(context).cancel(idFor(address))
        } catch (e: Exception) {
            Log.d(TAG, "The message notification was already gone", e)
        }
    }

    /**
     * The shortcut that makes the notification above a conversation.
     *
     * Pushed rather than declared, because who it is about is only known when the message
     * arrives. Long-lived is the part that matters: the platform only treats a
     * notification as a conversation when a long-lived shortcut backs it, and without one
     * the style renders as an ordinary notification again.
     */
    private fun pushConversation(
        context: Context,
        id: String,
        who: String,
        face: android.graphics.Bitmap?,
        person: Person,
        open: Intent
    ) {
        try {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(who)
                    .setIntent(open)
                    .setPerson(person)
                    .setLongLived(true)
                    .setCategories(setOf(CONVERSATION_CATEGORY))
                    .apply {
                        face?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
                    }
                    .build()
            )
        } catch (e: Exception) {
            // Worth nothing more than the avatar treatment, so a phone that will not take
            // the shortcut still gets the message.
            Log.w(TAG, "Could not push a sender as a conversation", e)
        }
    }

    /**
     * Which notification a conversation is.
     *
     * Keyed on the number the way the list is - see [MessageStore.keyOf] - so a second
     * message from the same person replaces the first rather than joining it, and so a
     * conversation opened in the app can take down the right one.
     */
    fun idFor(address: String): Int =
        BASE_ID + (MessageStore.keyOf(address).hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        val manager = notifications(context)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            // High, and so it arrives with a sound and comes down over whatever is on
            // screen. A text message is the one notification a phone has always done that
            // for, and a launcher that took the role and then delivered them quietly would
            // have turned messages off without saying so.
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Text messages as they arrive" }
        )
    }

    private fun notifications(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    const val CHANNEL = "wp81_messages"

    /** How many lines of a conversation the shade is given. */
    private const val HISTORY = 6

    /**
     * Where this app's message notifications start.
     *
     * Clear of the call (8101) and the missed call (8102), and with room for the low
     * sixteen bits of a conversation's key underneath it.
     */
    private const val BASE_ID = 9000

    /** A failure is about the same conversation but is not it, so it sits alongside. */
    private const val FAILED_OFFSET = 0x10000

    /** See the note in MissedCallReceiver: the framework's constant, inlined at build time. */
    private const val CONVERSATION_CATEGORY =
        android.content.pm.ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION

    private const val TAG = "WP81People"
}
