package rocks.gorjan.gokixp.apps.alarms

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RawRes
import rocks.gorjan.gokixp.R

/**
 * The five things an alarm can be.
 *
 * A fixed list rather than the phone's ringtone picker, deliberately: this shell brings
 * its own sounds for everything else it does - the charge chime, the error, the Windows
 * start-up - and an alarm that opened Android's own picker would be the one place the
 * illusion broke and a stock Material dialog appeared over the panorama.
 *
 * An alarm going off plays on the alarm stream and a preview plays on the media stream,
 * and the difference is deliberate. The alarm stream is the one Android keeps out of the
 * silent switch and out of Do Not Disturb by default, so an alarm played any other way is
 * an alarm that does not go off on a phone in a pocket. A preview is not an alarm, though:
 * it is somebody sitting in the sound picker deciding which one they like, and it should
 * behave like every other sound this shell plays while it is being looked at - turned by
 * the volume keys they are already holding, and silent when the phone is. Playing it at
 * alarm volume means a tap on a list row can be startlingly loud, at a volume the user
 * cannot lower without changing what they will be woken by tomorrow.
 */
object AlarmSounds {

    data class Sound(val id: String, val name: String, @RawRes val res: Int)

    val ALL: List<Sound> = listOf(
        Sound("alarm01", "alarm 01", R.raw.alarm01),
        Sound("alarm02", "alarm 02", R.raw.alarm02),
        Sound("alarm03", "alarm 03", R.raw.alarm03),
        Sound("alarm04", "alarm 04", R.raw.alarm04),
        Sound("alarm05", "alarm 05", R.raw.alarm05)
    )

    const val DEFAULT = "alarm01"

    /** Whatever was asked for, or the first one - a missing sound is still an alarm. */
    fun byId(id: String?): Sound = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /**
     * How an alarm that is going off asks to be heard.
     *
     * USAGE_ALARM is the whole of the "rings on silent" story. The alarm stream has its own
     * volume, is not touched by the ringer being switched to silent or vibrate, and is
     * allowed through Do Not Disturb by the platform's own default policy - so the correct
     * thing to do is to ask for it honestly and let the system apply its rules, rather than
     * to force a stream volume or claim an exemption the user did not grant.
     *
     * The vibration asks with these too, so that one answer about what this app is doing
     * covers both halves of it.
     */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * How a preview asks. Media, like everything else played to be listened to.
     *
     * See the note at the top of this file: a preview is a sound being auditioned, not an
     * alarm going off, and it belongs on the stream whose volume keys are under the user's
     * thumb while they are auditioning it.
     */
    private fun previewAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * A player already loaded with [sound], or null if it could not be opened.
     *
     * Built by hand rather than through `MediaPlayer.create`, because the attributes have
     * to be set before the source is prepared - a player told which stream it is on
     * afterwards has already routed itself.
     */
    fun open(
        context: Context,
        sound: Sound,
        looping: Boolean,
        attributes: AudioAttributes = attributes()
    ): MediaPlayer? = try {
        MediaPlayer().apply {
            context.resources.openRawResourceFd(sound.res).use { fd ->
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            // After the source and before prepare, which is the order that actually holds.
            // Set on a player that has no source yet, the attributes can be dropped on the
            // way through setDataSource, and a player that was meant to be an alarm comes
            // out as media - which is the one mistake here that is inaudible until the
            // morning it matters.
            setAudioAttributes(attributes)
            isLooping = looping
            prepare()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not open ${sound.id}", e)
        null
    }

    // ---------------------------------------------------------------- preview

    private var preview: MediaPlayer? = null

    /** Which sound the preview is of, so that tapping it again is understood as "stop". */
    private var previewingId: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val silence = Runnable { stopPreview() }

    /**
     * Plays a few seconds of a sound so it can be chosen by ear.
     *
     * Only ever one at a time, and tapping the sound that is playing stops it: a picker
     * that layered two alarms over each other would be unusable at exactly the moment it
     * is being used.
     */
    fun previewing(): String? = if (preview != null) previewingId else null

    fun preview(context: Context, id: String) {
        val sound = byId(id)
        val wasPlaying = previewingId
        stopPreview()
        if (wasPlaying == sound.id) return

        val player = open(
            context.applicationContext, sound, looping = false, attributes = previewAttributes()
        ) ?: return
        player.setOnCompletionListener { stopPreview() }
        previewingId = sound.id
        preview = player
        player.start()
        // Long enough to know the sound, short enough that walking away from the picker
        // does not leave an alarm going off in the user's hand.
        main.postDelayed(silence, PREVIEW_MS)
    }

    fun stopPreview() {
        main.removeCallbacks(silence)
        preview?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Preview was already finished", e)
            }
            player.release()
        }
        preview = null
        previewingId = null
    }

    private const val PREVIEW_MS = 7_000L
    private const val TAG = "WP81Alarms"
}
