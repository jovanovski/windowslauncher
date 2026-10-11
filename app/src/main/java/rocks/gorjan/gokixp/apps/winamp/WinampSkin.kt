package rocks.gorjan.gokixp.apps.winamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A classic Winamp 2 skin: the `.wsz` files skins.webamp.org hands out.
 *
 * A `.wsz` is nothing but a zip of Windows bitmaps and two text files. Winamp drew every
 * control by cutting rectangles out of those bitmaps, and [WinampSkinView] does the same, so
 * any skin made for the real thing since 1998 dresses this one too.
 *
 * Skins were allowed to leave files out - plenty never bothered with a playlist editor - and
 * Winamp filled the gaps from its own base skin. [fallback] is that base skin.
 */
class WinampSkin private constructor(
    val name: String,
    private val bitmaps: Map<String, Bitmap>,
    private val ownVisColors: IntArray?,
    private val ownPlaylistColors: PlaylistColors?,
    private val fallback: WinampSkin?
) {
    /** PLEDIT.TXT: the playlist editor's colours and font. */
    data class PlaylistColors(
        val normal: Int,
        val current: Int,
        val normalBg: Int,
        val selectedBg: Int,
        val font: Typeface
    )

    fun bitmap(file: String): Bitmap? = bitmaps[file] ?: fallback?.bitmap(file)

    /** NUMS_EX.BMP, the later digits with a minus sign, wins over NUMBERS.BMP when a skin has both. */
    val digits: Bitmap? get() = bitmaps[NUMS_EX] ?: bitmaps[NUMBERS] ?: fallback?.digits

    /** A skin with no BALANCE.BMP had its balance drawn from VOLUME.BMP. */
    val balance: Bitmap? get() = bitmaps[BALANCE] ?: bitmaps[VOLUME] ?: fallback?.balance

    /** VISCOLOR.TXT's 24 colours: background, grid dots, 16 for the analyser, 5 for the scope, peaks. */
    val visColors: IntArray get() = ownVisColors ?: fallback?.visColors ?: DEFAULT_VIS_COLORS

    val playlistColors: PlaylistColors
        get() = ownPlaylistColors ?: fallback?.playlistColors ?: DEFAULT_PLAYLIST_COLORS

    companion object {
        private const val TAG = "WinampSkin"

        const val MAIN = "main.bmp"
        const val TITLEBAR = "titlebar.bmp"
        const val CBUTTONS = "cbuttons.bmp"
        const val PLAYPAUS = "playpaus.bmp"
        const val NUMBERS = "numbers.bmp"
        const val NUMS_EX = "nums_ex.bmp"
        const val TEXT = "text.bmp"
        const val MONOSTER = "monoster.bmp"
        const val POSBAR = "posbar.bmp"
        const val VOLUME = "volume.bmp"
        const val BALANCE = "balance.bmp"
        const val SHUFREP = "shufrep.bmp"
        const val PLEDIT = "pledit.bmp"
        private const val VISCOLOR_TXT = "viscolor.txt"
        private const val PLEDIT_TXT = "pledit.txt"

        private val WANTED = setOf(
            MAIN, TITLEBAR, CBUTTONS, PLAYPAUS, NUMBERS, NUMS_EX, TEXT, MONOSTER, POSBAR,
            VOLUME, BALANCE, SHUFREP, PLEDIT, VISCOLOR_TXT, PLEDIT_TXT
        )

        /** No skin's bitmap comes anywhere near this; it only keeps a bad file from eating memory. */
        private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        private val DEFAULT_VIS_COLORS = intArrayOf(
            Color.rgb(0, 0, 0), Color.rgb(24, 33, 41),
            Color.rgb(239, 49, 16), Color.rgb(206, 41, 16), Color.rgb(214, 90, 0),
            Color.rgb(214, 102, 0), Color.rgb(214, 115, 0), Color.rgb(198, 123, 8),
            Color.rgb(222, 165, 24), Color.rgb(214, 181, 33), Color.rgb(189, 222, 41),
            Color.rgb(148, 222, 33), Color.rgb(41, 206, 16), Color.rgb(50, 190, 16),
            Color.rgb(57, 181, 16), Color.rgb(49, 156, 8), Color.rgb(41, 148, 0),
            Color.rgb(24, 132, 8),
            Color.rgb(255, 255, 255), Color.rgb(214, 214, 222), Color.rgb(181, 189, 189),
            Color.rgb(160, 170, 175), Color.rgb(148, 156, 165), Color.rgb(150, 150, 150)
        )

        private val DEFAULT_PLAYLIST_COLORS = PlaylistColors(
            normal = Color.rgb(0, 255, 0),
            current = Color.WHITE,
            normalBg = Color.BLACK,
            selectedBg = Color.rgb(0, 0, 198),
            font = Typeface.SANS_SERIF
        )

        /**
         * Reads a `.wsz`. Throws if it holds no MAIN.BMP, since then it is not a Winamp 2 skin
         * at all - most likely a Winamp 3/5 "modern" skin (.wal), which this cannot draw.
         */
        fun read(name: String, input: InputStream, fallback: WinampSkin?): WinampSkin {
            val files = HashMap<String, ByteArray>()
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Skins zip their files at the root, in a folder, in any case at all.
                        val base = entry.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
                        if (base in WANTED && base !in files) {
                            readCapped(zip)?.let { files[base] = it }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if (MAIN !in files) throw IllegalArgumentException("Not a Winamp 2 skin: no MAIN.BMP")

            val bitmaps = HashMap<String, Bitmap>()
            for ((file, bytes) in files) {
                if (!file.endsWith(".bmp")) continue
                decodeBitmap(bytes)?.let { bitmaps[file] = it } ?: Log.w(TAG, "$name: could not read $file")
            }
            if (MAIN !in bitmaps) throw IllegalArgumentException("Not a Winamp 2 skin: MAIN.BMP unreadable")

            val visColors = files[VISCOLOR_TXT]?.let { parseVisColors(String(it, Charsets.ISO_8859_1), fallback) }
            val playlistColors = files[PLEDIT_TXT]?.let { parsePlaylistColors(String(it, Charsets.ISO_8859_1), fallback) }
            return WinampSkin(name, bitmaps, visColors, playlistColors, fallback)
        }

        private fun readCapped(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_ENTRY_BYTES) return null
            }
            return out.toByteArray()
        }

        /**
         * Most skins' bitmaps are 8-bit and many are RLE-compressed. An RLE bitmap may stop
         * short and leave the rest of the picture unwritten, which Android decodes as
         * transparent and Winamp drew as black, so every sheet is laid on black.
         */
        private fun decodeBitmap(bytes: ByteArray): Bitmap? {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (!decoded.hasAlpha()) return decoded
            val opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply {
                drawColor(Color.BLACK)
                drawBitmap(decoded, 0f, 0f, null)
            }
            decoded.recycle()
            return opaque
        }

        /** One "r,g,b, // comment" per line, the first 24 lines that have three numbers. */
        private fun parseVisColors(text: String, fallback: WinampSkin?): IntArray {
            val number = Regex("\\d+")
            val colors = text.lineSequence()
                .mapNotNull { line ->
                    val values = number.findAll(line.substringBefore("//")).take(3)
                        .map { it.value.toInt().coerceIn(0, 255) }.toList()
                    if (values.size == 3) Color.rgb(values[0], values[1], values[2]) else null
                }
                .take(24)
                .toList()
            val base = fallback?.visColors ?: DEFAULT_VIS_COLORS
            return IntArray(24) { colors.getOrNull(it) ?: base[it] }
        }

        private fun parsePlaylistColors(text: String, fallback: WinampSkin?): PlaylistColors {
            val base = fallback?.playlistColors ?: DEFAULT_PLAYLIST_COLORS
            val values = HashMap<String, String>()
            text.lineSequence().forEach { line ->
                val key = line.substringBefore('=', "").trim().lowercase()
                if (key.isNotEmpty()) values[key] = line.substringAfter('=').trim()
            }
            fun color(key: String, default: Int): Int {
                val hex = values[key]?.removePrefix("#")?.take(6) ?: return default
                return hex.toIntOrNull(16)?.let { Color.rgb(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) } ?: default
            }
            val font = values["font"]?.takeIf { it.isNotBlank() }
                ?.let { Typeface.create(it, Typeface.NORMAL) } ?: base.font
            return PlaylistColors(
                normal = color("normal", base.normal),
                current = color("current", base.current),
                normalBg = color("normalbg", base.normalBg),
                selectedBg = color("selectedbg", base.selectedBg),
                font = font
            )
        }
    }
}

/**
 * Where skins live: the base skin in the app's assets, and every skin somebody has loaded as
 * a copy of its `.wsz` in the app's own files, so it is still there once the download is gone.
 */
object WinampSkins {
    private const val TAG = "WinampSkins"
    private const val BASE_ASSET = "winamp/base-2.91.wsz"
    const val BASE_NAME = "Base Skin"

    @Volatile
    private var base: WinampSkin? = null

    /** Winamp 2.91's own skin, read once per process. */
    fun base(context: Context): WinampSkin {
        base?.let { return it }
        synchronized(this) {
            base?.let { return it }
            val skin = context.assets.open(BASE_ASSET).use { WinampSkin.read(BASE_NAME, it, null) }
            base = skin
            return skin
        }
    }

    fun directory(context: Context): File = File(context.filesDir, "winamp_skins").apply { mkdirs() }

    /** The loaded skins, by name. */
    fun installed(context: Context): List<File> =
        directory(context).listFiles { file -> file.isFile && file.extension.equals("wsz", true) }
            ?.sortedBy { it.nameWithoutExtension.lowercase() }
            ?: emptyList()

    /** Reads an installed skin, or null if it has gone or will not read. */
    fun load(context: Context, fileName: String): WinampSkin? {
        val file = File(directory(context), fileName)
        if (!file.isFile) return null
        return try {
            file.inputStream().use { WinampSkin.read(file.nameWithoutExtension, it, base(context)) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read skin $fileName", e)
            null
        }
    }

    /**
     * Checks [bytes] is a skin and keeps a copy under [displayName]. Returns the kept file's
     * name and the skin read from it; throws if it is not a Winamp 2 skin.
     */
    fun install(context: Context, displayName: String, bytes: ByteArray): Pair<String, WinampSkin> {
        val name = displayName.substringAfterLast('/')
            .replace(Regex("\\.(wsz|zip)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z0-9 ._()\\-\\[\\]]"), "_")
            .trim().ifEmpty { "Skin" }
            .take(64)
        val skin = WinampSkin.read(name, ByteArrayInputStream(bytes), base(context))
        val fileName = "$name.wsz"
        File(directory(context), fileName).writeBytes(bytes)
        return fileName to skin
    }

    fun delete(context: Context, fileName: String) {
        File(directory(context), fileName).delete()
    }
}
