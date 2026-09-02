package rocks.gorjan.gokixp.apps.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * The thing that is actually going off.
 *
 * A service rather than an activity, because an alarm has to survive everything that can
 * happen to a screen: the user pressing home, the screen turning itself off, another app
 * being opened over it, the ring screen being killed for memory. The screen
 * ([AlarmRingActivity]) is a view onto this, and stopping the noise is this object's job
 * whether or not anybody is looking at it.
 *
 * **Why it rings on a silent phone.** The sound plays with
 * [android.media.AudioAttributes.USAGE_ALARM] - see [AlarmSounds.attributes] - which puts
 * it on the alarm stream. That stream has its own volume, is not touched by the ringer
 * being switched to silent or to vibrate, and is let through Do Not Disturb by the
 * platform's own default policy. Media volume is a different stream again and has nothing
 * to do with it. So the phone being "on silent" or having the music turned down does not
 * quiet this, and none of that requires forcing a volume or claiming an exemption: the
 * alarm asks to be an alarm and the system's own rules do the rest, which is the only way
 * to do it that stays true when the user changes those rules deliberately.
 *
 * The vibration is not a fallback for that - it rides along with the sound, because a
 * phone face down on a mattress is quieter than its volume setting suggests.
 */
class AlarmRingService : Service() {

    /** What is going off, and enough about it for the screen to say so. */
    data class Ringing(
        /** The alarm's id, or 0 when it is the countdown. */
        val id: Long,
        val label: String,
        val sound: String,
        val vibrate: Boolean,
        val isCountdown: Boolean,
        /** When it started ringing, for the screen to show the time it went off. */
        val startedAt: Long = System.currentTimeMillis()
    ) {
        companion object {
            fun of(alarm: Alarm) = Ringing(
                id = alarm.id,
                label = alarm.name.ifBlank { "alarm" },
                sound = alarm.sound,
                vibrate = alarm.vibrate,
                isCountdown = false
            )

            fun countdown(sound: String) = Ringing(
                id = 0L,
                label = "countdown",
                sound = sound,
                vibrate = true,
                isCountdown = true
            )
        }
    }

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { silence() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> begin(intent)
            ACTION_SNOOZE -> {
                current?.let { if (!it.isCountdown) AlarmScheduler.snooze(this, it.id, SNOOZE_MINUTES) }
                stop()
            }
            ACTION_DISMISS -> stop()
            else -> stop()
        }
        // Not sticky: an alarm the system restarts hours later, with no intent and no idea
        // which alarm it was, is a phone going off for no reason.
        return START_NOT_STICKY
    }

    /**
     * Whether the phone is letting alarms through at all right now.
     *
     * This is the Do Not Disturb question, and it is a different question from whether the
     * phone is on silent. Silent and vibrate leave the alarm stream alone - that is the
     * whole reason an alarm is played on it - but Do Not Disturb can be set to hold alarms
     * back too, and when it is, an alarm is something the user has said they do not want.
     *
     * Total silence holds everything. "Alarms only" is, by name, the one setting that lets
     * an alarm through. Priority-only depends on whether alarms are one of the categories
     * the user allowed, which is read from the policy where this app is allowed to read it
     * and inferred from the alarm stream where it is not - the system mutes that stream
     * when it is suppressing alarms, so the answer ends up in the same place either way.
     */
    private fun alarmsSuppressed(): Boolean {
        val notifications =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return try {
            when (notifications.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> true
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> !alarmsInPolicy(notifications)
                else -> false
            }
        } catch (e: Exception) {
            // Never guess "suppressed" from a failure to ask: the cost of getting this
            // wrong in one direction is a phone that rings when it should not, and in the
            // other it is a phone that does not ring at all.
            Log.w(TAG, "Could not read the interruption filter; ringing anyway", e)
            false
        }
    }

    private fun alarmsInPolicy(notifications: NotificationManager): Boolean = try {
        if (notifications.isNotificationPolicyAccessGranted) {
            notifications.notificationPolicy.priorityCategories and
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
        } else {
            audio()?.isStreamMute(AudioManager.STREAM_ALARM) != true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the Do Not Disturb policy; ringing anyway", e)
        true
    }

    /** Whether a sound played now would actually be heard. */
    private fun audible(): Boolean {
        val manager = audio() ?: return true
        return !manager.isStreamMute(AudioManager.STREAM_ALARM) &&
            manager.getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }

    private fun audio(): AudioManager? =
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun begin(intent: Intent) {
        val ringing = Ringing(
            id = intent.getLongExtra(EXTRA_ID, 0L),
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "alarm" },
            sound = intent.getStringExtra(EXTRA_SOUND).orEmpty(),
            vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true),
            isCountdown = intent.getBooleanExtra(EXTRA_COUNTDOWN, false)
        )
        current = ringing
        announce()

        // Foreground first and immediately: a service started from the background has a
        // few seconds to say what it is for, and everything below this line can take a
        // moment - opening a sound file, waking a screen.
        startForeground(
            NOTIFICATION_ID,
            notification(ringing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // Do Not Disturb, set to hold alarms back, is the user saying they do not want to
        // be woken - and it is not this app's place to talk them out of it. So the alarm is
        // not rung, not buzzed and does not take over the screen; it is recorded as missed,
        // which is exactly what it is, and the notification saying so is itself subject to
        // the same rules.
        if (alarmsSuppressed()) {
            Log.d(TAG, "Do Not Disturb is holding alarms back; this one counts as missed")
            missed(ringing)
            stop()
            return
        }

        hold()
        play(ringing)
        if (ringing.vibrate) buzz()

        // Nothing rings forever. An alarm nobody answered has been missed, and a phone
        // left going off in an empty room is a flat battery and a bad neighbour.
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, AUTO_SILENCE_MS)

        openScreen()
    }

    /**
     * Puts the ring screen in front of whatever is there, lock screen included.
     *
     * Tried directly and allowed to fail. A service may not start an activity from the
     * background as a rule, and the exception this claims is the notification's own
     * full-screen intent - which the system will honour by itself if the direct attempt is
     * refused. So the notification is the mechanism and this is the shortcut.
     */
    private fun openScreen() {
        try {
            startActivity(AlarmRingActivity.intentFor(this))
        } catch (e: Exception) {
            Log.d(TAG, "The ring screen will come up from the notification instead", e)
        }
    }

    // ---------------------------------------------------------------- noise

    private fun play(ringing: Ringing) {
        // The alarm stream turned down to nothing, or muted. The vibration and the screen
        // still stand - the alarm is going off - but there is no point taking audio focus
        // and stopping somebody's music in order to play silence over it.
        if (!audible()) {
            Log.d(TAG, "The alarm stream is silent; ringing on the vibration alone")
            return
        }

        val sound = AlarmSounds.byId(ringing.sound)
        val manager = audio()

        // Asked for, and taken either way. Audio focus is how whatever is playing music
        // gets told to stop; it is not permission to make a noise, and an alarm that
        // declined to go off because a podcast would not yield would be useless.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AlarmSounds.attributes())
            .build()
        focus = request
        manager?.requestAudioFocus(request)

        player = AlarmSounds.open(this, sound, looping = true)?.apply {
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "The alarm sound stopped: $what/$extra")
                false
            }
            start()
        }
        if (player == null) Log.w(TAG, "No sound to ring with; the vibration is on its own")
    }

    private fun buzz() {
        val vibrator = vibrator() ?: return
        // Ringer mode is deliberately not consulted: silent and vibrate are about the
        // ringer, and an alarm is not the ringer. Do Not Disturb has already been asked,
        // once, in begin().
        if (!vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(
                VibrationEffect.createWaveform(PATTERN, 0),
                AlarmSounds.attributes()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * Keeps the processor up while the alarm is going off.
     *
     * The screen is the ring screen's business - it asks for that with window flags - but
     * a phone that dozed off mid-ring would stop the sound with it. Taken with a timeout
     * so a wake lock can never outlive the thing it was taken for, whatever happens next.
     */
    private fun hold() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(AUTO_SILENCE_MS + 5_000L)
        }
    }

    /** The alarm was left to ring itself out: it stops, and it counts as missed. */
    private fun silence() {
        Log.d(TAG, "Nobody answered; silencing")
        current?.let { missed(it) }
        stop()
    }

    /**
     * Says, afterwards, that an alarm went off and nobody stopped it.
     *
     * The one thing the ongoing notification cannot do: it is taken down with the service,
     * so once the ringing stops there is nothing left to say it ever happened. Somebody who
     * slept through an alarm, or whose Do Not Disturb held it back, should be able to find
     * that out when they pick the phone up rather than wondering why it never went off.
     *
     * An ordinary notification, dismissible and quiet, with an id of its own per alarm so
     * two missed in a row are two lines rather than one overwriting the other.
     */
    private fun missed(ringing: Ringing) {
        ensureMissedChannel()
        val open = PendingIntent.getActivity(
            this, MISSED_CODE,
            Intent(this, rocks.gorjan.gokixp.MainActivity::class.java).apply {
                action = AlarmScheduler.ACTION_SHOW_ALARMS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pattern =
            if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        val at = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date(ringing.startedAt))
        val what = if (ringing.isCountdown) "countdown" else "alarm"

        val notification = Notification.Builder(this, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.wp81_notify_alarm)
            .setContentTitle("Missed $what")
            .setContentText("You missed an $what at $at")
            .setContentIntent(open)
            .metroLook(this)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(ringing.startedAt)
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(MISSED_ID_BASE + (ringing.id % 100).toInt(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "The system would not take the missed-alarm notification", e)
        }
    }

    private fun stop() {
        main.removeCallbacks(giveUp)
        current = null
        announce()

        player?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "The player had already finished", e)
            }
            player.release()
        }
        player = null

        focus?.let {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(it)
        }
        focus = null

        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the vibration", e)
        }

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Belt and braces: a service taken down by the system rather than by stop() would
        // otherwise leave a sound playing with nothing left to stop it.
        if (current != null || player != null) stop()
    }

    // ---------------------------------------------------------------- the shade

    /**
     * The alarm, as the system sees it.
     *
     * CATEGORY_ALARM and a full-screen intent are what let this take over a locked phone,
     * exactly as a call does. The channel makes no sound of its own: this service is
     * already playing the alarm, and a channel that rang as well would be two sounds.
     */
    private fun notification(ringing: Ringing): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, AlarmRingActivity.intentFor(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.wp81_notify_alarm)
            .setContentTitle(if (ringing.isCountdown) "Countdown finished" else ringing.label)
            .setContentText(if (ringing.isCountdown) "Time is up" else "Alarm")
            .setContentIntent(open)
            .metroLook(this)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // Snooze is for alarms. A countdown that had run out and could be put off for nine
        // minutes would be a countdown that had not run out.
        // Stop first, snooze second - the same order and the same words as the ring
        // screen. Somebody reaching for a phone that is going off should not have to
        // re-read which button is which depending on where they are looking at it.
        builder.addAction(action("Stop", ACTION_DISMISS))
        if (!ringing.isCountdown) {
            builder.addAction(action("Snooze", ACTION_SNOOZE))
        }
        return builder.build()
    }

    /**
     * A button on the notification.
     *
     * Sent to this service rather than through a receiver, and as a foreground-service
     * start: it is the one kind the system will accept from the shade while the app has no
     * screen of its own, which is precisely the case a notification button is for.
     */
    private fun action(label: String, action: String): Notification.Action {
        val intent = Intent(this, AlarmRingService::class.java).setAction(action)
        val pending = PendingIntent.getForegroundService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            null as android.graphics.drawable.Icon?, label, pending).build()
    }

    private fun ensureMissedChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(MISSED_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                MISSED_CHANNEL, "Missed alarms", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alarms that rang themselves out, or that were held back"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarms and countdowns going off"
                // The service plays the alarm itself, on the alarm stream. A channel sound
                // would be a second one, on the notification stream, which is the stream
                // that *is* silenced by the silent switch.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun announce() = main.post { onChanged?.invoke() }

    companion object {
        const val ACTION_RING = "rocks.gorjan.gokixp.ALARM_RING"
        const val ACTION_SNOOZE = "rocks.gorjan.gokixp.ALARM_SNOOZE"
        const val ACTION_DISMISS = "rocks.gorjan.gokixp.ALARM_DISMISS"

        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_VIBRATE = "vibrate"
        private const val EXTRA_COUNTDOWN = "countdown"

        /**
         * What is going off right now, or null.
         *
         * Static because the thing that needs to read it - the ring screen - is an activity
         * in another task that may be created, destroyed and created again while one alarm
         * is ringing, and binding a service in order to ask it one question that changes
         * twice in ten minutes is a great deal of machinery for a nullable field.
         */
        @Volatile
        var current: Ringing? = null
            private set

        /** Set by the ring screen while it is up, so it hears the alarm being dismissed. */
        var onChanged: (() -> Unit)? = null

        /** How long a snooze lasts. */
        const val SNOOZE_MINUTES = 3

        fun ring(context: Context, ringing: Ringing) {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_ID, ringing.id)
                putExtra(EXTRA_LABEL, ringing.label)
                putExtra(EXTRA_SOUND, ringing.sound)
                putExtra(EXTRA_VIBRATE, ringing.vibrate)
                putExtra(EXTRA_COUNTDOWN, ringing.isCountdown)
            }
            context.startForegroundService(intent)
        }

        /** Snooze or dismiss from the ring screen, which goes through the service either way. */
        fun send(context: Context, action: String) {
            context.startForegroundService(
                Intent(context, AlarmRingService::class.java).setAction(action))
        }

        private const val CHANNEL = "wp81_alarms"
        private const val MISSED_CHANNEL = "wp81_alarms_missed"
        private const val NOTIFICATION_ID = 8102

        /** One id per alarm, so two missed in a row are two lines in the shade. */
        private const val MISSED_ID_BASE = 8200
        private const val MISSED_CODE = 8201
        private const val WAKE_TAG = "gokixp:alarm"

        /** How long a phone rings, unanswered, before the alarm counts as missed. */
        private const val AUTO_SILENCE_MS = 5 * 60 * 1000L

        /** Off, on, off, on: the double buzz an alarm has, rather than a steady drone. */
        private val PATTERN = longArrayOf(0, 500, 500, 500, 1500)

        private const val TAG = "WP81Alarms"
    }
}
