package rocks.gorjan.gokixp.apps.elfbowling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import rocks.gorjan.gokixp.R
import java.io.InputStream

/**
 * Every picture of the original game. They were unpacked from the zlib-compressed "NVD"
 * resource inside Elf Bowling.exe and saved as PNGs, with the colour the game keyed out
 * (palette white for sprites, palette black for the scenery) turned transparent.
 */
internal class ElfBowlingArt(
    context: Context,
    listAssets: () -> List<String> = { context.assets.list(DIR).orEmpty().toList() },
    openAsset: (String) -> InputStream = { context.assets.open("$DIR/$it") },
) {

    private val bitmaps = HashMap<String, Bitmap>()

    init {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        for (file in listAssets()) {
            if (!file.endsWith(".png")) continue
            try {
                openAsset(file).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)?.let { bitmaps[file.removeSuffix(".png")] = it }
                }
            } catch (e: Exception) {
                Log.w("ElfBowling", "Could not load $file", e)
            }
        }
    }

    operator fun get(name: String): Bitmap? = bitmaps[name]

    fun release() {
        for (bitmap in bitmaps.values) bitmap.recycle()
        bitmaps.clear()
    }

    companion object {
        private const val DIR = "elf_bowling"
    }
}

/** The original game's sounds, as it shipped them: 8-bit 11 kHz WAVs. */
internal class ElfBowlingSounds(context: Context, private val isMuted: () -> Boolean) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<String, Int>()
    private var rolling = 0

    init {
        for ((name, res) in RESOURCES) ids[name] = pool.load(context, res, 1)
    }

    fun play(name: String, volume: Float = 1f) {
        if (isMuted()) return
        val id = ids[name] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    /** The ball's rumble, which runs for as long as the ball is on the lane. */
    fun roll(on: Boolean) {
        if (on && rolling == 0 && !isMuted()) {
            val id = ids["roll6"] ?: return
            rolling = pool.play(id, 0.6f, 0.6f, 2, -1, 1f)
        } else if (!on && rolling != 0) {
            pool.stop(rolling)
            rolling = 0
        }
    }

    fun pause() = pool.autoPause()

    fun resume() = pool.autoResume()

    fun release() {
        roll(false)
        pool.release()
    }

    companion object {
        private val RESOURCES = mapOf(
            "birddie" to R.raw.elfbowl_birddie,
            "bowl_back" to R.raw.elfbowl_bowl_back,
            "bowl_drop" to R.raw.elfbowl_bowl_drop,
            "click" to R.raw.elfbowl_click,
            "elfbaby" to R.raw.elfbowl_elfbaby,
            "elfscream" to R.raw.elfbowl_elfscream,
            "elliot_farted" to R.raw.elfbowl_elliot_farted,
            "elves_laugh" to R.raw.elfbowl_elves_laugh,
            "fart" to R.raw.elfbowl_fart,
            "fewer" to R.raw.elfbowl_fewer,
            "frogcroak" to R.raw.elfbowl_frogcroak,
            "froguh" to R.raw.elfbowl_froguh,
            "gameovr" to R.raw.elfbowl_gameovr,
            "gutter_ball" to R.raw.elfbowl_gutter_ball,
            "gutterball2b" to R.raw.elfbowl_gutterball2b,
            "hey_santa" to R.raw.elfbowl_hey_santa,
            "hit" to R.raw.elfbowl_hit,
            "ho_ho_ho" to R.raw.elfbowl_ho_ho_ho,
            "light" to R.raw.elfbowl_light,
            "pins" to R.raw.elfbowl_pins,
            "rack_pins2" to R.raw.elfbowl_rack_pins2,
            "rackpins" to R.raw.elfbowl_rackpins,
            "roll6" to R.raw.elfbowl_roll6,
            "slap_ass" to R.raw.elfbowl_slap_ass,
            "taunt1b" to R.raw.elfbowl_taunt1b,
            "taunt2b" to R.raw.elfbowl_taunt2b,
            "taunt3b" to R.raw.elfbowl_taunt3b,
            "whosyrdaddy" to R.raw.elfbowl_whosyrdaddy,
        )
    }
}
