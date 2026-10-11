package rocks.gorjan.gokixp.apps.winamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Winamp 2's main window with the playlist editor docked under it, drawn from a [WinampSkin].
 *
 * Everything is laid out in the skin's own pixels - the main window is 275 by 116, as it
 * always was - painted 1:1 into a small bitmap and then blown up to the view's width with
 * nearest-neighbour scaling, so a skin's pixel art stays pixel art. Only the playlist's text
 * is drawn at full resolution, the way Winamp drew it with a real font.
 *
 * The view owns no playback. It shows what [WinampApp] tells it and reports taps and drags
 * back through [listener].
 */
class WinampSkinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPrevious()
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onNext()
        fun onEject(x: Float, y: Float)
        fun onSeek(fraction: Float)
        fun onVolume(volume: Float)
        fun onBalance(balance: Float)
        fun onShuffle()
        fun onRepeat()
        /** The options button, top left of the title bar, or the logo. Coordinates are the view's. */
        fun onOptions(x: Float, y: Float)
        fun onRowClick(index: Int)
        fun onRowLongClick(index: Int, x: Float, y: Float)
        fun onPlaylistAdd()
        fun onPlaylistRemove()
        fun onPlaylistSelect()
        fun onPlaylistMisc()
        fun onPlaylistList()
    }

    enum class Status { STOPPED, PLAYING, PAUSED }

    data class Row(val text: String, val time: String)

    var listener: Listener? = null

    var skin: WinampSkin? = null
        set(value) {
            field = value
            visBackground = null
            invalidate()
        }

    var status: Status = Status.STOPPED
        set(value) {
            field = value
            updateTicker()
            invalidate()
        }

    /** Whether a track is loaded: the clock, the seek thumb and the bitrate show only then. */
    var hasMedia: Boolean = false
        set(value) { field = value; invalidate() }

    var elapsedMs: Int = 0
        set(value) { field = value; invalidate() }

    var durationMs: Int = 0
        set(value) { field = value; invalidate() }

    /** The scrolling song title, already in Winamp's "1. Artist - Title (3:45)" form. */
    var title: String = ""
        set(value) {
            if (field != value) marqueeOffset = 0f
            field = value
            updateTicker()
            invalidate()
        }

    var kbps: String = ""
        set(value) { field = value; invalidate() }
    var khz: String = ""
        set(value) { field = value; invalidate() }

    /** Lights MONO or STEREO; null lights neither. */
    var stereo: Boolean? = null
        set(value) { field = value; invalidate() }

    var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var balance: Float = 0f
        set(value) { field = value.coerceIn(-1f, 1f); invalidate() }

    var shuffle: Boolean = false
        set(value) { field = value; invalidate() }

    var repeat: Boolean = false
        set(value) { field = value; invalidate() }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            listScroll = listScroll.coerceIn(0f, maxListScroll())
            invalidate()
        }

    /** The row drawn selected, in the skin's "current" colour. */
    var highlighted: Int = -1
        set(value) { field = value; invalidate() }

    /** Shown in the playlist editor's running-time box. */
    var playlistName: String = ""
        set(value) { field = value; invalidate() }

    // ---------------------------------------------------------------------------------------
    // Layout, in skin pixels

    private companion object {
        const val WIDTH = 275
        const val MAIN_HEIGHT = 116
        const val PLAYLIST_TILES = 5
        const val PLAYLIST_HEIGHT = 20 + 29 * PLAYLIST_TILES + 38
        const val HEIGHT = MAIN_HEIGHT + PLAYLIST_HEIGHT

        const val PL_TOP = MAIN_HEIGHT
        const val LIST_X = 12
        const val LIST_Y = PL_TOP + 20
        const val LIST_W = WIDTH - 12 - 20
        const val LIST_H = 29 * PLAYLIST_TILES
        const val ROW_H = 14
        const val PL_BOTTOM = PL_TOP + PLAYLIST_HEIGHT - 38
        const val SCROLL_X = WIDTH - 15
        const val SCROLL_THUMB_H = 18

        const val MARQUEE_X = 111
        const val MARQUEE_W = 154
        const val VIS_X = 24
        const val VIS_Y = 43
        const val VIS_W = 76
        const val VIS_H = 16
        const val VIS_BARS = 19

        const val POS_X = 16
        const val POS_W = 248
        const val POS_THUMB_W = 29
        const val VOL_X = 107
        const val VOL_W = 68
        const val BAL_X = 177
        const val BAL_W = 38
        const val SLIDER_THUMB_W = 14

        const val TICK_MS = 33L

        /** TEXT.BMP: which cell holds each character, as column, row. */
        val FONT: Map<Char, Pair<Int, Int>> = HashMap<Char, Pair<Int, Int>>().apply {
            "abcdefghijklmnopqrstuvwxyz\"@".forEachIndexed { i, c -> put(c, i to 0) }
            "0123456789".forEachIndexed { i, c -> put(c, i to 1) }
            "….:()-'!_+\\/[]^&%,=$#".forEachIndexed { i, c -> put(c, 10 + i to 1) }
            put('å', 0 to 2); put('ö', 1 to 2); put('ä', 2 to 2)
            put('?', 3 to 2); put('*', 4 to 2)
            put('<', 22 to 1); put('>', 23 to 1); put('{', 22 to 1); put('}', 23 to 1)
            put('`', 16 to 1); put('~', 15 to 1); put('|', 21 to 1); put(';', 12 to 1)
        }
    }

    private enum class Target {
        NONE, OPTIONS, LOGO, PREV, PLAY, PAUSE, STOP, NEXT, EJECT, SHUFFLE, REPEAT, EQ, PL,
        POSITION, VOLUME, BALANCE,
        LIST, SCROLLBAR, PL_ADD, PL_REM, PL_SEL, PL_MISC, PL_LIST,
        MINI_PREV, MINI_PLAY, MINI_PAUSE, MINI_STOP, MINI_NEXT, MINI_EJECT
    }

    /** Hit boxes, as x, y, width, height. Sliders and the list are handled on their own. */
    private val buttons: List<Pair<Target, Rect>> = listOf(
        Target.OPTIONS to box(4, 1, 13, 13),
        Target.PREV to box(16, 88, 23, 18),
        Target.PLAY to box(39, 88, 23, 18),
        Target.PAUSE to box(62, 88, 23, 18),
        Target.STOP to box(85, 88, 23, 18),
        Target.NEXT to box(108, 88, 22, 18),
        Target.EJECT to box(136, 89, 22, 16),
        Target.SHUFFLE to box(164, 89, 47, 15),
        Target.REPEAT to box(210, 89, 28, 15),
        Target.LOGO to box(246, 86, 26, 24),
        Target.EQ to box(219, 58, 23, 12),
        Target.PL to box(242, 58, 23, 12),
        Target.POSITION to box(POS_X, 70, POS_W, 14),
        Target.VOLUME to box(VOL_X, 55, VOL_W, 17),
        Target.BALANCE to box(BAL_X, 55, BAL_W, 17),
        Target.SCROLLBAR to box(SCROLL_X - 3, LIST_Y, 14, LIST_H),
        Target.LIST to box(LIST_X, LIST_Y, LIST_W, LIST_H),
        Target.PL_ADD to box(14, PL_BOTTOM + 8, 22, 18),
        Target.PL_REM to box(43, PL_BOTTOM + 8, 22, 18),
        Target.PL_SEL to box(72, PL_BOTTOM + 8, 22, 18),
        Target.PL_MISC to box(101, PL_BOTTOM + 8, 22, 18),
        Target.PL_LIST to box(WIDTH - 46, PL_BOTTOM + 8, 23, 18),
        Target.MINI_PREV to box(130, PL_BOTTOM + 20, 9, 12),
        Target.MINI_PLAY to box(139, PL_BOTTOM + 20, 10, 12),
        Target.MINI_PAUSE to box(149, PL_BOTTOM + 20, 9, 12),
        Target.MINI_STOP to box(158, PL_BOTTOM + 20, 10, 12),
        Target.MINI_NEXT to box(168, PL_BOTTOM + 20, 8, 12),
        Target.MINI_EJECT to box(176, PL_BOTTOM + 20, 11, 12)
    )

    private fun box(x: Int, y: Int, w: Int, h: Int) = Rect(x, y, x + w, y + h)

    // ---------------------------------------------------------------------------------------
    // Drawing state

    private val frame: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frame)
    private var visBackground: Bitmap? = null
    private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val fillPaint = Paint().apply { isAntiAlias = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val dstF = RectF()

    private var scale = 1f

    private var marqueeOffset = 0f
    private val bars = FloatArray(VIS_BARS)
    private val peaks = FloatArray(VIS_BARS)
    private val barTargets = FloatArray(VIS_BARS)
    private var ticking = false

    // Touch state
    private var target = Target.NONE
    private var pressedInside = false
    private var downX = 0f
    private var downY = 0f
    private var dragFraction = -1f
    private var listScroll = 0f
    private var listScrolling = false
    private var longPressed = false
    private var touchedRow = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val longPress = Runnable {
        if (target == Target.LIST && !listScrolling && touchedRow >= 0) {
            longPressed = true
            listener?.onRowLongClick(touchedRow, downX, downY)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (width.toFloat() * HEIGHT / WIDTH).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w.toFloat() / WIDTH, h.toFloat() / HEIGHT).coerceAtLeast(0.1f)
    }

    // ---------------------------------------------------------------------------------------
    // Painting

    override fun onDraw(canvas: Canvas) {
        val skin = skin ?: return
        paintFrame(skin)
        dstF.set(0f, 0f, WIDTH * scale, HEIGHT * scale)
        canvas.drawBitmap(frame, null, dstF, spritePaint)
        drawPlaylistText(canvas, skin)
    }

    private fun paintFrame(skin: WinampSkin) {
        val c = frameCanvas
        c.drawColor(android.graphics.Color.BLACK)
        paintMain(c, skin)
        paintPlaylist(c, skin)
    }

    private fun blit(c: Canvas, bmp: Bitmap?, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        bmp ?: return
        if (sx < 0 || sy < 0) return
        // Skins are often smaller than the spec says; draw whatever part of the cell is there.
        val cw = min(w, bmp.width - sx)
        val ch = min(h, bmp.height - sy)
        if (cw <= 0 || ch <= 0) return
        src.set(sx, sy, sx + cw, sy + ch)
        dst.set(dx, dy, dx + cw, dy + ch)
        c.drawBitmap(bmp, src, dst, spritePaint)
    }

    private fun pressed(t: Target) = target == t && pressedInside

    private fun paintMain(c: Canvas, skin: WinampSkin) {
        blit(c, skin.bitmap(WinampSkin.MAIN), 0, 0, WIDTH, MAIN_HEIGHT, 0, 0)

        // Title bar, its buttons, and the clutter bar down the left of the display
        val titlebar = skin.bitmap(WinampSkin.TITLEBAR)
        blit(c, titlebar, 27, 0, WIDTH, 14, 0, 0)
        blit(c, titlebar, 0, if (pressed(Target.OPTIONS)) 9 else 0, 9, 9, 6, 3)
        blit(c, titlebar, 9, 0, 9, 9, 244, 3)
        blit(c, titlebar, 0, 18, 9, 9, 254, 3)
        blit(c, titlebar, 18, 0, 9, 9, 264, 3)
        blit(c, titlebar, 304, 0, 8, 43, 10, 22)

        // Play / pause / stop indicator
        val playpaus = skin.bitmap(WinampSkin.PLAYPAUS)
        when (status) {
            Status.PLAYING -> {
                blit(c, playpaus, 0, 0, 9, 9, 26, 28)
                blit(c, playpaus, 36, 0, 3, 9, 24, 28)
            }
            Status.PAUSED -> blit(c, playpaus, 9, 0, 9, 9, 26, 28)
            Status.STOPPED -> blit(c, playpaus, 18, 0, 9, 9, 26, 28)
        }

        // Clock; paused, it blinks
        val blinkOff = status == Status.PAUSED && (SystemClock.uptimeMillis() / 500) % 2 == 1L
        if (hasMedia && !blinkOff) {
            val totalSeconds = elapsedMs / 1000
            val minutes = min(totalSeconds / 60, 99)
            val seconds = totalSeconds % 60
            val digits = skin.digits
            blit(c, digits, (minutes / 10) * 9, 0, 9, 13, 48, 26)
            blit(c, digits, (minutes % 10) * 9, 0, 9, 13, 60, 26)
            blit(c, digits, (seconds / 10) * 9, 0, 9, 13, 78, 26)
            blit(c, digits, (seconds % 10) * 9, 0, 9, 13, 90, 26)
        }

        paintVisualizer(c, skin)

        // Song title
        drawMarquee(c, skin)

        if (hasMedia) {
            drawText(c, skin, kbps.padStart(3).takeLast(3), 111, 43, 15)
            drawText(c, skin, khz.padStart(2).takeLast(2), 156, 43, 10)
        }
        val monoster = skin.bitmap(WinampSkin.MONOSTER)
        blit(c, monoster, 0, if (stereo == true) 0 else 12, 29, 12, 239, 41)
        blit(c, monoster, 29, if (stereo == false) 0 else 12, 27, 12, 212, 41)

        // Volume and balance
        val volumeBmp = skin.bitmap(WinampSkin.VOLUME)
        val volumeFrame = (volume * 27).toInt().coerceIn(0, 27)
        blit(c, volumeBmp, 0, volumeFrame * 15, VOL_W, 13, VOL_X, 57)
        if (volumeBmp != null && volumeBmp.height >= 433) {
            val x = VOL_X + (volume * (VOL_W - SLIDER_THUMB_W)).toInt()
            blit(c, volumeBmp, if (pressed(Target.VOLUME) || target == Target.VOLUME) 0 else 15, 422, 14, 11, x, 58)
        }
        val balanceBmp = skin.balance
        val balanceFrame = (abs(balance) * 27).toInt().coerceIn(0, 27)
        blit(c, balanceBmp, 9, balanceFrame * 15, BAL_W, 13, BAL_X, 57)
        if (balanceBmp != null && balanceBmp.height >= 433) {
            val x = BAL_X + ((balance + 1f) / 2f * (BAL_W - SLIDER_THUMB_W)).toInt()
            blit(c, balanceBmp, if (target == Target.BALANCE) 0 else 15, 422, 14, 11, x, 58)
        }

        // EQ and PL toggles. The playlist is always docked here, so PL is always lit.
        val shufrep = skin.bitmap(WinampSkin.SHUFREP)
        blit(c, shufrep, if (pressed(Target.EQ)) 46 else 0, 61, 23, 12, 219, 58)
        blit(c, shufrep, if (pressed(Target.PL)) 69 else 23, 73, 23, 12, 242, 58)

        // Seek bar; the thumb only shows while there is something to seek in
        val posbar = skin.bitmap(WinampSkin.POSBAR)
        blit(c, posbar, 0, 0, POS_W, 10, POS_X, 72)
        if (hasMedia) {
            val fraction = if (target == Target.POSITION && dragFraction >= 0f) dragFraction
            else if (durationMs > 0) elapsedMs.toFloat() / durationMs else 0f
            val x = POS_X + (fraction.coerceIn(0f, 1f) * (POS_W - POS_THUMB_W)).toInt()
            blit(c, posbar, if (target == Target.POSITION) 278 else 248, 0, POS_THUMB_W, 10, x, 72)
        }

        // Transport
        val cbuttons = skin.bitmap(WinampSkin.CBUTTONS)
        blit(c, cbuttons, 0, if (pressed(Target.PREV)) 18 else 0, 23, 18, 16, 88)
        blit(c, cbuttons, 23, if (pressed(Target.PLAY)) 18 else 0, 23, 18, 39, 88)
        blit(c, cbuttons, 46, if (pressed(Target.PAUSE)) 18 else 0, 23, 18, 62, 88)
        blit(c, cbuttons, 69, if (pressed(Target.STOP)) 18 else 0, 23, 18, 85, 88)
        blit(c, cbuttons, 92, if (pressed(Target.NEXT)) 18 else 0, 22, 18, 108, 88)
        blit(c, cbuttons, 114, if (pressed(Target.EJECT)) 16 else 0, 22, 16, 136, 89)

        // Shuffle and repeat: off, off pressed, on, on pressed
        blit(c, shufrep, 28, (if (shuffle) 30 else 0) + (if (pressed(Target.SHUFFLE)) 15 else 0), 47, 15, 164, 89)
        blit(c, shufrep, 0, (if (repeat) 30 else 0) + (if (pressed(Target.REPEAT)) 15 else 0), 28, 15, 210, 89)
    }

    private fun paintVisualizer(c: Canvas, skin: WinampSkin) {
        val colors = skin.visColors
        val background = visBackground ?: Bitmap.createBitmap(VIS_W, VIS_H, Bitmap.Config.ARGB_8888).also { bmp ->
            // Winamp's analyser sits on colour 0 with a grid of colour 1 dots
            bmp.eraseColor(colors[0])
            for (y in 1 until VIS_H step 2) for (x in 1 until VIS_W step 2) bmp.setPixel(x, y, colors[1])
            visBackground = bmp
        }
        c.drawBitmap(background, VIS_X.toFloat(), VIS_Y.toFloat(), spritePaint)
        for (i in 0 until VIS_BARS) {
            val x = VIS_X + i * 4
            val height = bars[i].toInt().coerceIn(0, VIS_H)
            for (row in VIS_H - height until VIS_H) {
                // Colour 2 is the top of the analyser and colour 17 the bottom
                fillPaint.color = colors[2 + row]
                c.drawRect(x.toFloat(), (VIS_Y + row).toFloat(), (x + 3).toFloat(), (VIS_Y + row + 1).toFloat(), fillPaint)
            }
            val peak = peaks[i].toInt()
            if (peak > 0) {
                val row = (VIS_H - peak).coerceIn(0, VIS_H - 1)
                fillPaint.color = colors[23]
                c.drawRect(x.toFloat(), (VIS_Y + row).toFloat(), (x + 3).toFloat(), (VIS_Y + row + 1).toFloat(), fillPaint)
            }
        }
    }

    private fun drawMarquee(c: Canvas, skin: WinampSkin) {
        val text = title
        if (text.isEmpty()) return
        val fits = text.length * 5 <= MARQUEE_W
        c.save()
        c.clipRect(MARQUEE_X, 27, MARQUEE_X + MARQUEE_W, 33)
        if (fits) {
            drawText(c, skin, text, MARQUEE_X, 27, MARQUEE_W)
        } else {
            val loop = "$text  ***  "
            val loopWidth = loop.length * 5
            val offset = marqueeOffset.toInt() % loopWidth
            val firstChar = offset / 5
            var x = MARQUEE_X - offset % 5
            var i = firstChar
            val text2 = loop + loop
            while (x < MARQUEE_X + MARQUEE_W && i < text2.length) {
                drawChar(c, skin, text2[i], x, 27)
                x += 5
                i++
            }
        }
        c.restore()
    }

    private fun drawText(c: Canvas, skin: WinampSkin, text: String, x: Int, y: Int, maxWidth: Int) {
        var cx = x
        for (ch in text) {
            if (cx + 5 > x + maxWidth) break
            drawChar(c, skin, ch, cx, y)
            cx += 5
        }
    }

    private fun drawChar(c: Canvas, skin: WinampSkin, ch: Char, x: Int, y: Int) {
        val (col, row) = FONT[ch.lowercaseChar()] ?: (30 to 0)
        blit(c, skin.bitmap(WinampSkin.TEXT), col * 5, row * 6, 5, 6, x, y)
    }

    private fun paintPlaylist(c: Canvas, skin: WinampSkin) {
        val pledit = skin.bitmap(WinampSkin.PLEDIT)
        val colors = skin.playlistColors

        // Title bar: corners, a tiled fill, and the title centred over it
        var x = 25
        while (x < WIDTH - 25) {
            blit(c, pledit, 127, 0, 25, 20, x, PL_TOP)
            x += 25
        }
        blit(c, pledit, 0, 0, 25, 20, 0, PL_TOP)
        blit(c, pledit, 26, 0, 100, 20, (WIDTH - 100) / 2, PL_TOP)
        blit(c, pledit, 153, 0, 25, 20, WIDTH - 25, PL_TOP)

        for (i in 0 until PLAYLIST_TILES) {
            blit(c, pledit, 0, 42, 12, 29, 0, LIST_Y + 29 * i)
            blit(c, pledit, 31, 42, 20, 29, WIDTH - 20, LIST_Y + 29 * i)
        }

        blit(c, pledit, 0, 72, 125, 38, 0, PL_BOTTOM)
        blit(c, pledit, 126, 72, 150, 38, WIDTH - 150, PL_BOTTOM)

        // The list's background and the selected row's bar; the text goes on at full size
        fillPaint.color = colors.normalBg
        c.drawRect(LIST_X.toFloat(), LIST_Y.toFloat(), (LIST_X + LIST_W).toFloat(), (LIST_Y + LIST_H).toFloat(), fillPaint)
        val rowTop = LIST_Y + highlighted * ROW_H - listScroll
        if (highlighted >= 0 && rowTop + ROW_H > LIST_Y && rowTop < LIST_Y + LIST_H) {
            fillPaint.color = colors.selectedBg
            c.save()
            c.clipRect(LIST_X, LIST_Y, LIST_X + LIST_W, LIST_Y + LIST_H)
            c.drawRect(LIST_X.toFloat(), rowTop, (LIST_X + LIST_W).toFloat(), rowTop + ROW_H, fillPaint)
            c.restore()
        }

        // Scroll thumb
        val maxScroll = maxListScroll()
        val thumbY = LIST_Y + if (maxScroll > 0f) ((listScroll / maxScroll) * (LIST_H - SCROLL_THUMB_H)).toInt() else 0
        blit(c, pledit, if (target == Target.SCROLLBAR) 61 else 52, 53, 8, SCROLL_THUMB_H, SCROLL_X, thumbY)

        // Running-time box shows which playlist this is; the mini clock, the elapsed time
        drawText(c, skin, playlistName, WIDTH - 143, PL_BOTTOM + 10, 90)
        if (hasMedia) {
            val seconds = elapsedMs / 1000
            drawText(c, skin, String.format("%d:%02d", seconds / 60, seconds % 60).padStart(5), WIDTH - 87, PL_BOTTOM + 23, 30)
        }
    }

    private fun drawPlaylistText(canvas: Canvas, skin: WinampSkin) {
        if (rows.isEmpty()) return
        val colors = skin.playlistColors
        textPaint.typeface = colors.font
        textPaint.textSize = 10f * scale
        val left = (LIST_X + 2) * scale
        val right = (LIST_X + LIST_W - 2) * scale
        canvas.save()
        canvas.clipRect(LIST_X * scale, LIST_Y * scale, (LIST_X + LIST_W) * scale, (LIST_Y + LIST_H) * scale)
        val first = (listScroll / ROW_H).toInt().coerceAtLeast(0)
        val last = min(rows.size - 1, ((listScroll + LIST_H) / ROW_H).toInt())
        val baseline = (ROW_H / 2f) * scale - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in first..last) {
            val row = rows[i]
            val top = (LIST_Y + i * ROW_H - listScroll) * scale
            textPaint.color = if (i == highlighted || i == touchedRow && target == Target.LIST && !listScrolling) colors.current else colors.normal
            val timeWidth = if (row.time.isNotEmpty()) textPaint.measureText(row.time) else 0f
            if (row.time.isNotEmpty()) canvas.drawText(row.time, right - timeWidth, top + baseline, textPaint)
            val room = right - left - timeWidth - 4f * scale
            val shown = ellipsize(row.text, room)
            canvas.drawText(shown, left, top + baseline, textPaint)
        }
        canvas.restore()
    }

    private fun ellipsize(text: String, room: Float): String {
        if (textPaint.measureText(text) <= room) return text
        val count = textPaint.breakText(text, true, room - textPaint.measureText("…"), null)
        return text.take(max(count, 0)) + "…"
    }

    // ---------------------------------------------------------------------------------------
    // Animation: the song title scrolls, the analyser dances

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        ticking = false
    }

    private fun needsTicking(): Boolean =
        status != Status.STOPPED || title.length * 5 > MARQUEE_W || bars.any { it > 0f } || peaks.any { it > 0f }

    private fun updateTicker() {
        if (!isAttachedToWindow) return
        if (needsTicking() && !ticking) {
            ticking = true
            postDelayed(tick, TICK_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (title.length * 5 > MARQUEE_W) marqueeOffset += 1f
            stepVisualizer()
            invalidate()
            if (needsTicking()) postDelayed(this, TICK_MS) else ticking = false
        }
    }

    /**
     * The analyser only pretends to listen: reading the real output would take the
     * microphone permission. Bars chase random levels, weighted toward the bass, and peaks
     * hang a moment before they fall, which is most of what the eye reads as Winamp.
     */
    private fun stepVisualizer() {
        val playing = status == Status.PLAYING
        for (i in 0 until VIS_BARS) {
            if (playing) {
                if (Random.nextInt(4) == 0) {
                    val weight = 1f - i.toFloat() / VIS_BARS * 0.6f
                    barTargets[i] = (Random.nextFloat() * VIS_H * weight + 2f).coerceAtMost(VIS_H.toFloat())
                }
            } else {
                barTargets[i] = 0f
            }
            bars[i] = if (barTargets[i] > bars[i]) barTargets[i] else max(0f, bars[i] - 1.2f)
            peaks[i] = if (bars[i] >= peaks[i]) bars[i] else max(0f, peaks[i] - 0.35f)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Touch

    private fun maxListScroll(): Float = max(0f, rows.size * ROW_H - LIST_H.toFloat())

    /** Brings [index] into the middle of the list. */
    fun scrollToRow(index: Int) {
        if (index < 0) return
        scroller.forceFinished(true)
        listScroll = (index * ROW_H + ROW_H / 2f - LIST_H / 2f).coerceIn(0f, maxListScroll())
        invalidate()
    }

    private fun targetAt(sx: Float, sy: Float): Target {
        val x = sx.toInt()
        val y = sy.toInt()
        return buttons.firstOrNull { it.second.contains(x, y) }?.first ?: Target.NONE
    }

    private fun rowAt(sy: Float): Int {
        val index = ((sy - LIST_Y + listScroll) / ROW_H).toInt()
        return if (sy >= LIST_Y && index in rows.indices) index else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sx = event.x / scale
        val sy = event.y / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                target = targetAt(sx, sy)
                if (target == Target.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                pressedInside = true
                downX = event.x
                downY = event.y
                listScrolling = false
                longPressed = false
                touchedRow = -1
                when (target) {
                    Target.POSITION -> if (hasMedia) dragFraction = positionFraction(sx) else target = Target.NONE
                    Target.VOLUME -> setVolumeFrom(sx)
                    Target.BALANCE -> setBalanceFrom(sx)
                    Target.SCROLLBAR -> scrollFromThumb(sy)
                    Target.LIST -> {
                        touchedRow = rowAt(sy)
                        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    else -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (target) {
                    Target.NONE -> return false
                    Target.POSITION -> dragFraction = positionFraction(sx)
                    Target.VOLUME -> setVolumeFrom(sx)
                    Target.BALANCE -> setBalanceFrom(sx)
                    Target.SCROLLBAR -> scrollFromThumb(sy)
                    Target.LIST -> {
                        velocityTracker?.addMovement(event)
                        if (!listScrolling && abs(event.y - downY) > touchSlop) {
                            listScrolling = true
                            removeCallbacks(longPress)
                        }
                        if (listScrolling) {
                            listScroll = (listScroll - (event.y - downY) / scale).coerceIn(0f, maxListScroll())
                            downY = event.y
                        }
                    }
                    else -> pressedInside = targetAt(sx, sy) == target
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val released = target
                val inside = pressedInside
                when (released) {
                    Target.POSITION -> if (dragFraction >= 0f) listener?.onSeek(dragFraction)
                    Target.LIST -> {
                        if (listScrolling) {
                            velocityTracker?.let { tracker ->
                                tracker.addMovement(event)
                                tracker.computeCurrentVelocity(1000)
                                fling(-tracker.yVelocity)
                            }
                        } else if (!longPressed) {
                            val row = rowAt(sy)
                            if (row >= 0 && row == touchedRow) listener?.onRowClick(row)
                        }
                    }
                    else -> if (inside) click(released, event.x, event.y)
                }
                resetTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                resetTouch()
                return true
            }
        }
        return false
    }

    private fun resetTouch() {
        target = Target.NONE
        pressedInside = false
        dragFraction = -1f
        listScrolling = false
        touchedRow = -1
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
    }

    private fun click(t: Target, x: Float, y: Float) {
        val l = listener ?: return
        when (t) {
            Target.OPTIONS, Target.LOGO -> l.onOptions(x, y)
            Target.PREV, Target.MINI_PREV -> l.onPrevious()
            Target.PLAY, Target.MINI_PLAY -> l.onPlay()
            Target.PAUSE, Target.MINI_PAUSE -> l.onPause()
            Target.STOP, Target.MINI_STOP -> l.onStop()
            Target.NEXT, Target.MINI_NEXT -> l.onNext()
            Target.EJECT, Target.MINI_EJECT -> l.onEject(x, y)
            Target.SHUFFLE -> l.onShuffle()
            Target.REPEAT -> l.onRepeat()
            Target.PL_ADD -> l.onPlaylistAdd()
            Target.PL_REM -> l.onPlaylistRemove()
            Target.PL_SEL -> l.onPlaylistSelect()
            Target.PL_MISC -> l.onPlaylistMisc()
            Target.PL_LIST -> l.onPlaylistList()
            else -> Unit
        }
    }

    private fun positionFraction(sx: Float): Float =
        ((sx - POS_X - POS_THUMB_W / 2f) / (POS_W - POS_THUMB_W)).coerceIn(0f, 1f)

    private fun setVolumeFrom(sx: Float) {
        volume = (sx - VOL_X - SLIDER_THUMB_W / 2f) / (VOL_W - SLIDER_THUMB_W)
        listener?.onVolume(volume)
    }

    private fun setBalanceFrom(sx: Float) {
        var value = ((sx - BAL_X - SLIDER_THUMB_W / 2f) / (BAL_W - SLIDER_THUMB_W)) * 2f - 1f
        // Winamp's balance snapped to the middle as it passed
        if (abs(value) < 0.15f) value = 0f
        balance = value
        listener?.onBalance(balance)
    }

    private fun scrollFromThumb(sy: Float) {
        val maxScroll = maxListScroll()
        if (maxScroll <= 0f) return
        val fraction = ((sy - LIST_Y - SCROLL_THUMB_H / 2f) / (LIST_H - SCROLL_THUMB_H)).coerceIn(0f, 1f)
        listScroll = fraction * maxScroll
    }

    private fun fling(velocityY: Float) {
        scroller.fling(
            0, (listScroll * scale).toInt(), 0, velocityY.toInt(),
            0, 0, 0, (maxListScroll() * scale).toInt()
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            listScroll = (scroller.currY / scale).coerceIn(0f, maxListScroll())
            postInvalidateOnAnimation()
        }
    }
}
