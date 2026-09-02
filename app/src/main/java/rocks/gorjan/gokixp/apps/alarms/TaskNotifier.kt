package rocks.gorjan.gokixp.apps.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * Says a task, in the shade.
 *
 * The user's own words on the first line, and what they are on the second. A task is one
 * line the user wrote for themselves, so it is the heading; underneath it goes the only
 * thing this app knows that they do not, which is which of their tasks this was and when
 * it was set for.
 *
 * Default importance, so it arrives with the phone's ordinary notification sound rather
 * than silently or as an alarm. It is not an alarm: the whole point of it being a task is
 * that it does not take over the screen, and the icon it carries is the app's, which is
 * how somebody who has just been told to put the bins out knows which program said so.
 */
object TaskNotifier {

    fun post(context: Context, task: Task) {
        ensureChannel(context)
        val pattern =
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val at = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, task.hour)
                set(java.util.Calendar.MINUTE, task.minute)
            }.time
        )
        val open = PendingIntent.getActivity(
            context, OPEN_CODE,
            Intent(context, rocks.gorjan.gokixp.MainActivity::class.java).apply {
                action = AlarmScheduler.ACTION_SHOW_ALARMS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.wp81_notify_task)
            .setContentTitle(task.text.ifBlank { "Task" })
            // Not the text again. A BigTextStyle carrying the same words as the title is a
            // notification that says everything twice, which is what this used to do.
            .setContentText("task at $at")
            .metroLook(context)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()

        try {
            notifications(context).notify(idFor(task.id), notification)
        } catch (e: Exception) {
            Log.w(TAG, "The system would not take the task notification", e)
        }
    }

    /** Takes one down: its task has been deleted, switched off or rewritten. */
    fun clear(context: Context, taskId: Long) {
        try {
            notifications(context).cancel(idFor(taskId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear the task notification", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = notifications(context)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notes you asked to be shown at a time"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun notifications(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** One line per task, so two due together are two notifications. */
    private fun idFor(taskId: Long): Int = ID_BASE + (taskId % 100).toInt()

    private const val CHANNEL = "wp81_tasks"
    private const val ID_BASE = 8500
    private const val OPEN_CODE = 8599

    private const val TAG = "WP81Alarms"
}
