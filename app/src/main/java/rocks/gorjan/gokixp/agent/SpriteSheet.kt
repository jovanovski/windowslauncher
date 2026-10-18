package rocks.gorjan.gokixp.agent

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.IOException

/** One animation: an index into the agent's frames (-1 for an empty frame) and how long each one shows. */
class SpriteAnimation(val frames: IntArray, val durationsMs: IntArray)

/**
 * An agent's animations, every distinct frame stored once at its original pixel size. The frames sit in
 * grids of [framesPerSheet] across assets/agents/<agent id>/sheet<n>.webp, and animations.json says which
 * frames each animation plays. Sheets are decoded when a frame on them is first needed.
 */
class SpriteSheet private constructor(
    private val assets: AssetManager,
    val agentId: String,
    val frameWidth: Int,
    val frameHeight: Int,
    private val columns: Int,
    private val framesPerSheet: Int,
    private val animations: Map<String, SpriteAnimation>
) {
    // An animation can run across a sheet boundary, so keep a couple of sheets around
    private val sheets = LruCache<Int, Bitmap>(SHEET_CACHE_SIZE)

    val animationNames: Set<String> get() = animations.keys

    fun animation(name: String): SpriteAnimation? = animations[name]

    /** The sheet holding [frame], with [src] set to where the frame is on it; null for an empty frame. */
    fun frameBitmap(frame: Int, src: Rect): Bitmap? {
        if (frame < 0) return null
        val sheetIndex = frame / framesPerSheet
        val cell = frame % framesPerSheet
        val sheet = sheets.get(sheetIndex) ?: decodeSheet(sheetIndex)?.also { sheets.put(sheetIndex, it) } ?: return null
        val x = (cell % columns) * frameWidth
        val y = (cell / columns) * frameHeight
        src.set(x, y, x + frameWidth, y + frameHeight)
        return sheet
    }

    private fun decodeSheet(index: Int): Bitmap? = try {
        assets.open("agents/$agentId/sheet$index.webp").use { BitmapFactory.decodeStream(it) }
    } catch (e: IOException) {
        Log.e("SpriteSheet", "Failed to load sheet $index for $agentId", e)
        null
    }

    companion object {
        private const val SHEET_CACHE_SIZE = 3

        fun load(assets: AssetManager, agentId: String): SpriteSheet? = try {
            val json = JSONObject(assets.open("agents/$agentId/animations.json").bufferedReader().use { it.readText() })
            val animationsJson = json.getJSONObject("animations")
            val animations = mutableMapOf<String, SpriteAnimation>()
            for (name in animationsJson.keys()) {
                val animation = animationsJson.getJSONObject(name)
                val frames = animation.getJSONArray("frames")
                val durations = animation.getJSONArray("durations")
                animations[name] = SpriteAnimation(
                    IntArray(frames.length()) { frames.getInt(it) },
                    IntArray(durations.length()) { durations.getInt(it) }
                )
            }
            SpriteSheet(
                assets,
                agentId,
                json.getInt("frameWidth"),
                json.getInt("frameHeight"),
                json.getInt("columns"),
                json.getInt("framesPerSheet"),
                animations
            )
        } catch (e: Exception) {
            Log.e("SpriteSheet", "Failed to load animations for $agentId", e)
            null
        }
    }
}

/** Draws one frame of a [SpriteSheet], scaled up without smoothing so the pixel art stays crisp. */
class SpriteFrameDrawable(private val frameWidth: Int, private val frameHeight: Int) : Drawable() {
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private var bitmap: Bitmap? = null
    private val src = Rect()

    fun showFrame(sheet: SpriteSheet, frame: Int) {
        bitmap = sheet.frameBitmap(frame, src)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val sheet = bitmap ?: return
        canvas.drawBitmap(sheet, src, bounds, paint)
    }

    override fun getIntrinsicWidth() = frameWidth

    override fun getIntrinsicHeight() = frameHeight

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
