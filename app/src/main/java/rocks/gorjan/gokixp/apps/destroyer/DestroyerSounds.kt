package rocks.gorjan.gokixp.apps.destroyer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import rocks.gorjan.gokixp.R

/**
 * The game's 31 original sounds, decoded from the ADPCM clips inside Desktop Games'
 * .petprg section. One-shots go through [play]; the sounds the original kept running
 * (the idling and sawing chain-saw, the flame, the phaser, the washer, the crackle of
 * standing fire and the munching of termites) go through [loop], which the game calls
 * every tick with the state it wants, so a loop that missed its load simply starts a
 * tick later.
 *
 * Like the original's "sound stereobase", every sound is panned to where it happens.
 */
internal class DestroyerSounds(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(16)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<String, Int>()
    private val loops = HashMap<String, Int>()

    init {
        for ((name, res) in RESOURCES) ids[name] = pool.load(context, res, 1)
    }

    /** [pan] runs from 0 (left edge of the screen) to 1 (right edge). */
    fun play(name: String, pan: Float = 0.5f, volume: Float = 1f) {
        val id = ids[name] ?: return
        pool.play(id, left(pan) * volume, right(pan) * volume, 1, 0, 1f)
    }

    fun loop(name: String, on: Boolean, pan: Float = 0.5f) {
        val stream = loops[name]
        if (on) {
            if (stream == null) {
                val id = ids[name] ?: return
                val started = pool.play(id, left(pan), right(pan), 2, -1, 1f)
                if (started != 0) loops[name] = started
            } else {
                pool.setVolume(stream, left(pan), right(pan))
            }
        } else if (stream != null) {
            pool.stop(stream)
            loops.remove(name)
        }
    }

    fun stopLoops() {
        for (stream in loops.values) pool.stop(stream)
        loops.clear()
    }

    fun pause() = pool.autoPause()

    fun resume() = pool.autoResume()

    fun release() {
        stopLoops()
        pool.release()
    }

    private fun left(pan: Float) = (2f * (1f - pan)).coerceIn(0f, 1f)
    private fun right(pan: Float) = (2f * pan).coerceIn(0f, 1f)

    companion object {
        private val RESOURCES = mapOf(
            "smash_1" to R.raw.destroyer_smash_1,
            "smash_2" to R.raw.destroyer_smash_2,
            "smash_3" to R.raw.destroyer_smash_3,
            "smash_4" to R.raw.destroyer_smash_4,
            "smash_5" to R.raw.destroyer_smash_5,
            "smash_6" to R.raw.destroyer_smash_6,
            "smash_7" to R.raw.destroyer_smash_7,
            "smash_8" to R.raw.destroyer_smash_8,
            "saw_idle" to R.raw.destroyer_saw_idle,
            "saw_cut" to R.raw.destroyer_saw_cut,
            "mg_shot" to R.raw.destroyer_mg_shot,
            "mg_echo" to R.raw.destroyer_mg_echo,
            "shell_1" to R.raw.destroyer_shell_1,
            "shell_2" to R.raw.destroyer_shell_2,
            "shell_3" to R.raw.destroyer_shell_3,
            "shell_4" to R.raw.destroyer_shell_4,
            "shell_5" to R.raw.destroyer_shell_5,
            "shell_6" to R.raw.destroyer_shell_6,
            "shell_7" to R.raw.destroyer_shell_7,
            "shell_8" to R.raw.destroyer_shell_8,
            "shell_9" to R.raw.destroyer_shell_9,
            "fire" to R.raw.destroyer_fire,
            "flame_begin" to R.raw.destroyer_flame_begin,
            "flame" to R.raw.destroyer_flame,
            "flame_end" to R.raw.destroyer_flame_end,
            "color_shot" to R.raw.destroyer_color_shot,
            "color_drop" to R.raw.destroyer_color_drop,
            "phaser" to R.raw.destroyer_phaser,
            "stamp" to R.raw.destroyer_stamp,
            "termite" to R.raw.destroyer_termite,
            "termite_squish" to R.raw.destroyer_termite_squish,
            "washing" to R.raw.destroyer_washing,
        )
    }
}
