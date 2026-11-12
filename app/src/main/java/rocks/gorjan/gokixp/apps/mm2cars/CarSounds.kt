package rocks.gorjan.gokixp.apps.mm2cars

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import rocks.gorjan.gokixp.R

/**
 * Midtown Madness 2's own crunches, from the web port: hit-wall, hit-npc-hard and -huge,
 * glass-break, suspension-fall and checkpoint. Off unless the tray menu turns them on - a home screen
 * should not start crashing out loud by itself.
 */
internal class CarSounds(context: Context, var enabled: Boolean) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = RESOURCES.mapValues { (_, res) -> pool.load(context, res, 1) }

    /** [pan] runs from 0 (left edge of the screen) to 1 (right edge). */
    fun play(name: String, pan: Float, volume: Float) {
        if (!enabled) return
        val id = ids[name] ?: return
        val p = pan.coerceIn(0f, 1f)
        val v = volume * VOLUME
        pool.play(id, v * (2f * (1f - p)).coerceIn(0f, 1f), v * (2f * p).coerceIn(0f, 1f), 1, 0, 1f)
    }

    fun pause() = pool.autoPause()

    fun resume() = pool.autoResume()

    fun release() = pool.release()

    companion object {
        private const val VOLUME = 0.6f

        private val RESOURCES = mapOf(
            "crash" to R.raw.mm2cars_crash,
            "bump" to R.raw.mm2cars_bump,
            "boom" to R.raw.mm2cars_boom,
            "glass" to R.raw.mm2cars_glass,
            "land" to R.raw.mm2cars_land,
            "checkpoint" to R.raw.mm2cars_checkpoint
        )
    }
}
