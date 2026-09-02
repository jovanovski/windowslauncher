package rocks.gorjan.gokixp.apps.people

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * The missed call, told to the phone app rather than posted over its head.
 *
 * Telecom will hand a missed call to whichever app is the phone - but only if that app
 * says it wants one, and the way it says so is by having a receiver for this action in its
 * manifest. Without one Telecom posts a notification of its own instead, which is what was
 * happening here: a notification saying "Missed call" twice over, opening whichever other
 * app on the device claims `vnd.android.cursor.dir/calls`. Which was the AOSP dialler, so
 * tapping the launcher's missed call opened somebody else's phone app.
 *
 * The declaration in the manifest is therefore not decoration - it is the whole mechanism.
 * Telecom checks for it, finds it, and stops posting; from then on the notification is
 * ours, which means it can say who rang and open the page that has their call on it.
 */
class MissedCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return

        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)

        // Zero is Telecom saying the missed calls have been dealt with - the user opened a
        // call log somewhere, or rang them back. The notification goes with them.
        if (count <= 0) {
            notifications(context).cancel(NOTIFICATION_ID)
            return
        }

        // The address book is on disk and a receiver has a few milliseconds to live, so the
        // lookup is done on the far side of goAsync - which keeps the process alive exactly
        // as long as it takes and no longer.
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            var named = false
            try {
                named = post(app, count, number, deep = false)
            } catch (e: Exception) {
                Log.w(TAG, "Could not report a missed call", e)
            } finally {
                // Released here, before anything that goes near the network. A broadcast
                // has about ten seconds; a directory somewhere answering a question about
                // a number has no such promise, and holding one open on the other is how
                // an app stops responding.
                pending.finish()
            }
            // The broadcast is done and the notification is up. If nobody on this phone
            // owns the number, the directories are worth asking now - a colleague in a work
            // account's own directory is not on the phone at all - and the notification is
            // said again with their name on it.
            if (!named && !number.isNullOrBlank()) {
                try {
                    val found = rocks.gorjan.gokixp.wp81.PeopleStore
                        .lookupDirectoryNow(app, number)
                    if (found != null) post(app, count, number, deep = true)
                } catch (e: Exception) {
                    Log.w(TAG, "No directory could name a missed call", e)
                }
            }
        }.start()
    }

    /**
     * The missed call, in the shade, as the person who rang.
     *
     * Built as a conversation - a [NotificationCompat.MessagingStyle] against a
     * [Person], with a long-lived shortcut behind it - which is the one arrangement that
     * puts somebody's face where an app's icon would otherwise be. A plain notification
     * with a large icon on it shows the launcher's own logo and files the caller away on
     * the right-hand side, which is a notification about an app that happens to mention a
     * person. This is the other way round, and a missed call is about the person.
     *
     * The shortcut is not decoration either: the platform only treats a notification as a
     * conversation when one backs it, and without that the style renders as an ordinary
     * notification again.
     */
    private fun post(context: Context, count: Int, number: String?, deep: Boolean): Boolean {
        // The app's own resolver, which reads every account on the phone - see
        // PeopleStore.lookupNow. This used to ask PhoneLookup directly and take the first
        // row, which is how a caller the address book knows arrives as a bare number.
        //
        // Local only on the first pass. The directories are a network question and this
        // runs while a broadcast is waiting; see onReceive, which asks them afterwards.
        val caller = number?.let {
            if (deep) rocks.gorjan.gokixp.wp81.PeopleStore.nameNumbers(context, listOf(it))[it]
            else rocks.gorjan.gokixp.wp81.PeopleStore.lookupNow(context, it)
        }
        val photo = facePhoto(context, caller?.photoUri)
        val who = caller?.name?.takeIf { it.isNotBlank() } ?: number
        ensureChannel(context)

        // Straight to the history page, and explicitly: an intent that merely asked to
        // view a call log is what sent the user to another app in the first place.
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_CALL_HISTORY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val opening = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            // Still the handset. The small icon is what says at a glance in the status bar
            // what *kind* of thing this is, and a face there would say nothing.
            .setSmallIcon(R.drawable.wp81_notify_missed_call)
            .metroLook(context)
            .setContentIntent(opening)
            // What the People tile reads to know it should stand aside for this. It is also
            // what it is: the platform has a category for exactly this notification, and
            // Telecom's own - the thing this replaces - is the one that leaves it unset.
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setShowWhen(true)

        // Somebody to be about. Several missed calls have no single one, and Telecom sends
        // no number with them - so those stay an ordinary notification with a count, which
        // is the honest shape for a thing that is not one conversation.
        if (who != null) {
            val person = Person.Builder()
                .setName(who)
                .setKey(number ?: who)
                .setImportant(true)
                .apply { photo?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) } }
                .build()
            val shortcut = "missed-call:" + (number ?: who)
            pushConversation(context, shortcut, who, photo, person, open)
            builder
                .setShortcutId(shortcut)
                .addPerson(person)
                .setStyle(
                    NotificationCompat.MessagingStyle(person).addMessage(
                        if (count > 1) "$count missed calls" else "Missed call",
                        System.currentTimeMillis(),
                        person
                    )
                )
        } else {
            builder
                .setContentTitle(if (count > 1) "$count missed calls" else "Missed call")
                .setContentText("Tap to see them")
        }

        // Ringing back is the thing a missed call is actually about, and it is worth a
        // button rather than three taps through a page. No icon, as with the buttons on the
        // call notification: what the shade shows for these is the word.
        if (!number.isNullOrBlank()) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Call Back",
                    CallActionReceiver.pending(
                        context, CallActionReceiver.ACTION_CALL_BACK, number)
                ).build()
            )
        }

        notifications(context).notify(NOTIFICATION_ID, builder.build())
        // Whether anybody could be put to the number, which decides if it is worth asking
        // the directories once the broadcast is out of the way.
        return caller?.name?.isNotBlank() == true
    }

    /**
     * The shortcut that makes the notification above a conversation.
     *
     * Pushed rather than declared, because who it is about is only known when the call
     * comes in. Long-lived is the part that matters: the platform keeps a conversation's
     * shortcut around after it is gone from the dynamic list, and one that is not is not
     * eligible to back a conversation at all.
     */
    private fun pushConversation(
        context: Context,
        id: String,
        who: String,
        photo: android.graphics.Bitmap?,
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
                        photo?.let { setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
                    }
                    .build()
            )
        } catch (e: Exception) {
            // Worth nothing more than the avatar treatment, so a device that will not take
            // the shortcut still gets the notification.
            Log.w(TAG, "Could not push the caller as a conversation", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = notifications(context)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Calls that were not answered" }
        )
    }

    private fun notifications(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL = "wp81_missed_calls"
        const val NOTIFICATION_ID = 8102

        /** Takes the notification down, for when the user has been shown the history. */
        fun clear(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }

        /**
         * What marks a shortcut as one a conversation can be hung on.
         *
         * The framework's own constant, not the support library's - `ShortcutInfoCompat`
         * has never defined one, so this is not a matter of the version in use. It is
         * declared at API 30 and this app runs from 29, which is safe because it is a
         * compile-time constant: the string is written into this class at build time and
         * nothing is looked up on the platform at run time. Referring to it rather than
         * writing the string out is what keeps the two from ever disagreeing.
         */
        private const val CONVERSATION_CATEGORY =
            android.content.pm.ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION

        private const val TAG = "WP81Phone"
    }
}
