package rocks.gorjan.gokixp.apps.destroyer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream

/**
 * A sprite as Desktop Games stored it: its phases run left to right and, for sprites that
 * turn, its directions run top to bottom.
 */
internal class Sheet(val bitmap: Bitmap, val frames: Int, val dirs: Int = 1) {
    val fw = bitmap.width / frames
    val fh = bitmap.height / dirs

    fun src(frame: Int, dir: Int, out: Rect): Rect {
        val f = frame.mod(frames)
        val d = dir.mod(dirs)
        out.set(f * fw, d * fh, f * fw + fw, d * fh + fh)
        return out
    }
}

/**
 * Every picture and sprite of the original game, pixel for pixel. They were decoded from
 * the program's .petprg section with the game's own decompressor and its 224-colour
 * palette; Peter's shadow colour became translucent black. The colour-thrower's splats
 * were stored in red and recoloured at run time, so each colour ships pre-tinted here.
 */
internal class DestroyerArt(private val openAsset: (String) -> InputStream, czech: Boolean) {

    private val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun load(name: String): Bitmap =
        openAsset("$DIR/$name.png").use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Missing Desktop Destroyer asset $name")

    private fun sheet(name: String, frames: Int, dirs: Int = 1) = Sheet(load(name), frames, dirs)

    // Screens
    val intro = load(if (czech) "intro_cz" else "intro_en")
    val menu = load(if (czech) "menu_cz" else "menu_en")
    val menuCursor = load("menu_cursor")

    // Marks left on the desktop
    val cracks = List(8) { load("crack_$it") }
    val holes = List(4) { load("hole_$it") }
    val scorches = List(4) { load("scorch_$it") }
    val burns = List(10) { load("burn_$it") }
    val splats = List(8) { color -> List(5) { load("splat_${color}_$it") } }
    val deadTermite = load("dead_termite")

    // The stamp's prints 3 and 4 were drawn once in Czech and once in English.
    val prints = (if (czech) listOf(0, 1, 2, 4, 6, 7, 8, 9, 10, 11) else listOf(0, 1, 3, 5, 6, 7, 8, 9, 10, 11))
        .map { load("print_$it") }

    // Tools
    val hammerUp = sheet("hammer_up", 1)
    val hammerDown = sheet("hammer_down", 1)
    val sawIdle = sheet("saw_idle", 2)
    val sawCut = sheet("saw_cut", 2, 8)
    val mgIdle = sheet("mg_idle", 1)
    val mgFire = sheet("mg_fire", 2)
    val flamer = sheet("flamer", 2)
    val colorer = sheet("colorer", 4)
    val phaserIdle = sheet("phaser_idle", 1)
    val phaserFire = sheet("phaser_fire", 3)
    val stampUp = sheet("stamp_up", 1)
    val stampDown = sheet("stamp_down", 1)
    val hand = sheet("hand", 3)
    val handEmpty = sheet("hand_empty", 1)
    val washerIdle = sheet("washer_idle", 1)
    val washerFire = sheet("washer_fire", 3)

    // Things that fly, burn and crawl
    val mgHit = sheet("mg_hit", 14)
    val shell = sheet("shell", 8)
    val shards = List(5) { sheet("shard_$it", 1) }
    val splinters = List(5) { sheet("splinter_$it", 1) }
    val flameFly = sheet("flame_fly", 20)
    val flameBurn = sheet("flame_burn", 8)
    val blobs = List(8) { sheet("blob_$it", 20) }
    val termite = sheet("termite", 4, 4)

    companion object {
        private const val DIR = "desktop_destroyer"
    }
}
