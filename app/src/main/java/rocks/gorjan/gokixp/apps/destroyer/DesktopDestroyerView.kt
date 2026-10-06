package rocks.gorjan.gokixp.apps.destroyer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Build
import android.view.WindowInsets
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Desktop Games ("Desktop Destroyer") by Miroslav Němeček, Gemtree Software, rebuilt on its
 * own art and sounds. The desktop it wrecks is a snapshot of the launcher taken as it starts.
 *
 * The original ran on a 55 ms timer; so does this. Coordinates in the tool tables below are
 * in the original's pixels and get multiplied by [scale], an integer so the art stays crisp.
 *
 * What changed for phones: the mouse is the finger, so the tool stays where the finger last
 * was; right-click and Esc (the tool picker) became a button in the corner and Back; the
 * picker's own "right button = back" and "Esc = quit" captions are tappable.
 */
@SuppressLint("ViewConstructor")
internal class DesktopDestroyerView(
    context: Context,
    snapshot: Bitmap,
    czech: Boolean,
    private val onExit: () -> Unit,
    openAsset: (String) -> InputStream = { context.assets.open(it) },
) : View(context) {

    private enum class Mode { INTRO, MENU, PLAY }

    private val art = DestroyerArt(openAsset, czech)
    private val sounds = DestroyerSounds(context)
    private val random = Random(System.nanoTime())

    private val original: Bitmap = snapshot
    private val desk: Bitmap = snapshot.copy(Bitmap.Config.ARGB_8888, true)
    private val deskCanvas = Canvas(desk)

    // The art was drawn for ~96 dpi monitors; a phone needs it bigger to read the same, but
    // never so big that fewer than 400 of the original's pixels fit across the screen.
    private val scale = max(
        1,
        min(
            (resources.displayMetrics.density / 1.5f).roundToInt(),
            min(snapshot.width, snapshot.height) / 400,
        )
    ).toFloat()

    private var mode = Mode.INTRO
    private var tool = HAMMER
    private var tick = 0

    // The finger is the mouse; the tool waits where it was last.
    private var px = snapshot.width / 2f
    private var py = snapshot.height / 2f
    private var down = false
    private var lastX = px
    private var lastY = py
    private var sawDir = 5
    private var lastBurnX = 0f
    private var lastBurnY = 0f
    private var lastWashX = 0f
    private var lastWashY = 0f
    private var actionTicks = 0 // how long the hammer, stamp or hand stays down
    private var firedSincePress = false
    private var trackingButton = false
    private var menuTouch = false

    private val bullets = ArrayList<Bullet>()

    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cutShadowPaint = Paint().apply {
        color = Color.argb(128, 0, 0, 0)
        strokeWidth = 3f * scale
        strokeCap = Paint.Cap.ROUND
    }
    private val cutPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f * scale
        strokeCap = Paint.Cap.ROUND
    }
    private val eatenPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    private val buttonFace = Paint().apply { color = Color.rgb(192, 192, 192) }
    private val buttonLight = Paint().apply { color = Color.WHITE }
    private val buttonShade = Paint().apply { color = Color.rgb(128, 128, 128) }
    private val buttonDark = Paint().apply { color = Color.BLACK }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val washPath = Path()

    private val buttonRect = RectF()
    private var buttonPressed = false

    private val ticker = object : Runnable {
        override fun run() {
            step()
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }
    private var running = false

    init {
        isClickable = true
        isFocusable = true
        elevation = 1000f
    }

    // region lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    fun pause() {
        if (!running) return
        running = false
        removeCallbacks(ticker)
        sounds.pause()
    }

    fun resume() {
        if (running || !isAttachedToWindow) return
        running = true
        sounds.resume()
        postDelayed(ticker, TICK_MS)
    }

    fun release() {
        pause()
        sounds.release()
    }

    /** Back works as the original's Esc: the game opens the picker, the picker quits. */
    fun handleBack() {
        when (mode) {
            Mode.PLAY -> openMenu()
            Mode.INTRO, Mode.MENU -> onExit()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutButton()
    }

    private fun layoutButton() {
        val windowInsets = rootWindowInsets
        val insetTop: Int
        val insetRight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowInsets != null) {
            val i = windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = i.top
            insetRight = i.right
        } else {
            @Suppress("DEPRECATION")
            insetTop = windowInsets?.systemWindowInsetTop ?: 0
            @Suppress("DEPRECATION")
            insetRight = windowInsets?.systemWindowInsetRight ?: 0
        }
        val density = resources.displayMetrics.density
        val size = 52f * density
        val margin = 10f * density
        val right = width - margin - insetRight
        val top = margin + insetTop
        buttonRect.set(right - size, top, right, top + size)
    }

    // endregion

    // region input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.INTRO -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    mode = Mode.MENU
                    invalidate()
                }
            }
            Mode.MENU -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> menuTouch = true
                    MotionEvent.ACTION_UP -> if (menuTouch) {
                        menuTouch = false
                        menuTap(event.x, event.y)
                    }
                    MotionEvent.ACTION_CANCEL -> menuTouch = false
                }
            }
            Mode.PLAY -> playTouch(event)
        }
        return true
    }

    private fun playTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (buttonRect.contains(event.x, event.y)) {
                    trackingButton = true
                    buttonPressed = true
                } else {
                    px = event.x
                    py = event.y
                    lastX = px
                    lastY = py
                    down = true
                    press()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingButton) {
                    buttonPressed = buttonRect.contains(event.x, event.y)
                } else if (down) {
                    px = event.x
                    py = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (trackingButton) {
                    trackingButton = false
                    buttonPressed = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && buttonRect.contains(event.x, event.y)) {
                        openMenu()
                    }
                } else if (down) {
                    down = false
                    lift()
                }
            }
        }
        invalidate()
    }

    private fun openMenu() {
        if (down) {
            down = false
            lift()
        }
        // The original switched every bullet off when the picker came up, except termites.
        // Fire stays too: changing weapons shouldn't put it out.
        bullets.removeAll { it.type != TERMITE && it.type != FLAME && it.type != FLAME_FLY }
        sounds.stopLoops()
        mode = Mode.MENU
        invalidate()
    }

    private fun menuTap(x: Float, y: Float) {
        val m = menuRect()
        val ms = m.width() / art.menu.width
        val mx = (x - m.left) / ms
        val my = (y - m.top) / ms
        when {
            mx < 0 || my < 0 || mx >= art.menu.width || my >= art.menu.height -> mode = Mode.PLAY
            my < MENU_ROWS * MENU_CELL_H -> {
                tool = (my / MENU_CELL_H).toInt() * 3 + (mx / MENU_CELL_W).toInt()
                mode = Mode.PLAY
            }
            mx < art.menu.width / 2 -> mode = Mode.PLAY // "right button = back"
            else -> {                                    // "Esc = quit"
                onExit()
                return
            }
        }
        invalidate()
    }

    // endregion

    // region tools

    private fun press() {
        firedSincePress = false
        when (tool) {
            HAMMER -> {
                actionTicks = 3
                hammerHit(px, py)
            }
            CHAINSAW -> {
                lastX = px
                lastY = py
            }
            WASHER -> {
                lastWashX = px
                lastWashY = py
            }
            FLAMER -> sounds.play("flame_begin", pan(px))
            COLORER -> shootColor()
            STAMP -> {
                actionTicks = 4
                stamp(px, py)
            }
            TERMITES -> {
                actionTicks = 4
                val hit = termiteAt(px, py)
                if (hit != null) squish(hit) else releaseTermite(px, py)
            }
        }
    }

    private fun lift() {
        when (tool) {
            MACHINE_GUN -> if (firedSincePress) sounds.play("mg_echo", pan(px))
            FLAMER -> sounds.play("flame_end", pan(px))
        }
    }

    private fun hammerHit(x: Float, y: Float) {
        squishTermitesNear(x, y)
        // The original picked the smash by how bright the desktop was under the hammer.
        val idx = (brightness(x, y) * 8 / 256).coerceIn(0, 7)
        sounds.play("smash_${idx + 1}", pan(x))
        stampOnDesk(art.cracks.random(random), x, y)
        repeat(random.nextInt(5, 9)) {
            bullets += Bullet(SHARD).apply {
                this.x = x
                this.y = y
                vx = random.nextFloat(-7f, 7f) * scale
                vy = random.nextFloat(-12f, -3f) * scale
                floor = y + random.nextFloat(10f, 60f) * scale
                kind = random.nextInt(5)
            }
        }
    }

    private fun stamp(x: Float, y: Float) {
        squishTermitesNear(x, y)
        sounds.play("stamp", pan(x))
        stampOnDesk(art.prints.random(random), x, y)
    }

    private fun shootMachineGun() {
        firedSincePress = true
        sounds.play("mg_shot", pan(px))
        val hx = px + random.nextFloat(-6f, 6f) * scale
        val hy = py + random.nextFloat(-6f, 6f) * scale
        squishTermitesNear(hx, hy)
        stampOnDesk(art.holes.random(random), hx, hy)
        bullets += Bullet(SPARK).apply { x = hx; y = hy }
        bullets += Bullet(SHELL).apply {
            x = px + (MG_EJECT_X - MG_HOT_X) * scale
            y = py + (MG_EJECT_Y - MG_HOT_Y) * scale
            vx = random.nextFloat(3f, 7f) * scale
            vy = random.nextFloat(-9f, -5f) * scale
            floor = y + random.nextFloat(40f, 90f) * scale
            frame = random.nextInt(8)
        }
    }

    private fun shootFlame() {
        if (bullets.size >= MAX_BULLETS) return
        bullets += Bullet(FLAME_FLY).apply {
            sx = px + (FLAMER_NOZZLE_X - FLAMER_HOT_X) * scale
            sy = py + (FLAMER_NOZZLE_Y - FLAMER_HOT_Y) * scale
            tx = px + random.nextFloat(-20f, 20f) * scale
            ty = py + random.nextFloat(-20f, 20f) * scale
            x = sx
            y = sy
            life = 10
        }
    }

    private fun shootColor() {
        if (bullets.size >= MAX_BULLETS) return
        sounds.play("color_shot", pan(px))
        bullets += Bullet(BLOB).apply {
            sx = px + (COLORER_NOZZLE_X - FLAMER_HOT_X) * scale
            sy = py + (COLORER_NOZZLE_Y - FLAMER_HOT_Y) * scale
            tx = px + random.nextFloat(-30f, 30f) * scale
            ty = py + random.nextFloat(-30f, 30f) * scale
            x = sx
            y = sy
            life = 12
            kind = random.nextInt(8)
        }
    }

    private fun saw() {
        val dx = px - lastX
        val dy = py - lastY
        if (hypot(dx, dy) < 1f) return
        // Pick the drawing whose blade points along the cut.
        val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
        sawDir = SAW_BLADE_ANGLES.indices.minBy { angleDiff(SAW_BLADE_ANGLES[it], angle) }
        deskCanvas.drawLine(lastX + scale, lastY + scale, px + scale, py + scale, cutShadowPaint)
        deskCanvas.drawLine(lastX, lastY, px, py, cutPaint)
        squishTermitesNear(px, py)
        repeat(random.nextInt(1, 3)) {
            if (bullets.size < MAX_BULLETS) bullets += Bullet(SHARD).apply {
                x = px
                y = py
                vx = random.nextFloat(-6f, 6f) * scale
                vy = random.nextFloat(-9f, -2f) * scale
                floor = py + random.nextFloat(10f, 40f) * scale
                kind = 5 + random.nextInt(5)
            }
        }
        lastX = px
        lastY = py
    }

    private fun phaser() {
        // A burn on every tick in one spot is a black hole in a second; while the beam
        // rests, it burns at a quarter of the rate.
        val moved = hypot(px - lastBurnX, py - lastBurnY) >= 20f * scale
        if (!moved && tick % 4 != 0) return
        lastBurnX = px
        lastBurnY = py
        val x = px + random.nextFloat(-4f, 4f) * scale
        val y = py + random.nextFloat(-4f, 4f) * scale
        stampOnDesk(art.burns.random(random), x, y)
        squishTermitesNear(x, y)
    }

    private fun wash() {
        val r = WASH_RADIUS * scale
        // Clean the whole stroke since the last tick, not just where the finger is now, or a
        // fast drag leaves a row of separate circles.
        washPath.reset()
        val dx = px - lastWashX
        val dy = py - lastWashY
        val steps = max(1, (hypot(dx, dy) / (r / 3f)).toInt())
        for (i in 0..steps) {
            washPath.addCircle(lastWashX + dx * i / steps, lastWashY + dy * i / steps, r, Path.Direction.CW)
        }
        lastWashX = px
        lastWashY = py
        deskCanvas.save()
        deskCanvas.clipPath(washPath)
        deskCanvas.drawBitmap(original, 0f, 0f, null)
        deskCanvas.restore()
        // Water puts out fires. It does nothing to termites; only fire and a direct hit do.
        bullets.removeAll { (it.type == FLAME || it.type == FLAME_FLY) && hypot(it.x - px, it.y - py) < r * 1.5f }
    }

    private fun releaseTermite(x: Float, y: Float) {
        if (bullets.count { it.type == TERMITE } >= MAX_TERMITES) return
        bullets += Bullet(TERMITE).apply {
            this.x = x
            this.y = y
            dir = random.nextInt(4)
        }
    }

    private fun termiteAt(x: Float, y: Float): Bullet? =
        bullets.firstOrNull { it.type == TERMITE && hypot(it.x - x, it.y - y) < TERMITE_HIT_RADIUS * scale }

    private fun squishTermitesNear(x: Float, y: Float) {
        bullets.filter { it.type == TERMITE && hypot(it.x - x, it.y - y) < TERMITE_HIT_RADIUS * scale }.forEach { squish(it) }
    }

    private fun squish(t: Bullet) {
        bullets.remove(t)
        stampOnDesk(art.deadTermite, t.x, t.y)
        sounds.play("termite_squish", pan(t.x))
    }

    // endregion

    // region simulation

    private fun step() {
        if (mode != Mode.PLAY) return
        tick++
        if (actionTicks > 0) actionTicks--

        if (down) {
            when (tool) {
                CHAINSAW -> saw()
                MACHINE_GUN -> shootMachineGun()
                FLAMER -> shootFlame()
                COLORER -> if (tick % 3 == 0) shootColor()
                PHASER -> phaser()
                WASHER -> wash()
            }
        }

        val it = bullets.iterator()
        val landed = ArrayList<Bullet>()
        while (it.hasNext()) {
            val b = it.next()
            if (advance(b, landed)) it.remove()
        }
        bullets += landed

        // Termites don't survive the flame-thrower.
        val flames = bullets.filter { it.type == FLAME }
        if (flames.isNotEmpty()) {
            bullets.filter { t -> t.type == TERMITE && flames.any { hypot(it.x - t.x, it.y - t.y) < 32f * scale } }
                .forEach { squish(it) }
        }

        updateLoops()
    }

    /** Moves one bullet a tick on; true when it is done. New bullets go into [spawned]. */
    private fun advance(b: Bullet, spawned: MutableList<Bullet>): Boolean {
        b.t++
        when (b.type) {
            SPARK -> return b.t >= art.mgHit.frames
            SHELL -> {
                b.x += b.vx
                b.y += b.vy
                b.vy += 1.4f * scale
                if (b.t % 2 == 0) b.frame++
                if (b.vy > 0 && b.y >= b.floor) {
                    sounds.play("shell_${random.nextInt(1, 10)}", pan(b.x), 0.7f)
                    return true
                }
            }
            SHARD -> {
                b.x += b.vx
                b.y += b.vy
                b.vy += 1.5f * scale
                return b.vy > 0 && b.y >= b.floor
            }
            FLAME_FLY, BLOB -> {
                val p = b.t.toFloat() / b.life
                b.x = b.sx + (b.tx - b.sx) * p
                b.y = b.sy + (b.ty - b.sy) * p - sin(p * PI).toFloat() * 30f * scale
                if (b.t >= b.life) {
                    if (b.type == FLAME_FLY) {
                        stampOnDesk(art.scorches.random(random), b.tx, b.ty)
                        if (bullets.count { it.type == FLAME } + spawned.size < MAX_FLAMES) {
                            spawned += Bullet(FLAME).apply {
                                x = b.tx
                                y = b.ty
                                life = random.nextInt(30, 70)
                                frame = random.nextInt(8)
                                // Landed fire creeps off slowly in a direction of its own.
                                val heading = random.nextFloat() * 2f * PI.toFloat()
                                val speed = random.nextFloat(0.4f, 1.2f) * scale
                                vx = cos(heading) * speed
                                vy = sin(heading) * speed
                            }
                        }
                    } else {
                        stampOnDesk(art.splats[b.kind].random(random), b.tx, b.ty)
                        sounds.play("color_drop", pan(b.tx), 0.8f)
                    }
                    return true
                }
            }
            FLAME -> {
                if (b.t % 2 == 0) b.frame++
                b.x = (b.x + b.vx).coerceIn(0f, width.toFloat())
                b.y = (b.y + b.vy).coerceIn(0f, height.toFloat())
                // ...burning the desktop underneath as it goes.
                if (b.t % 6 == 0) stampOnDesk(art.scorches.random(random), b.x, b.y)
                return b.t >= b.life
            }
            TERMITE -> moveTermite(b)
        }
        return false
    }

    private fun moveTermite(b: Bullet) {
        if (b.t % 3 == 0) b.frame++
        val step = 2f * scale
        val ahead = 8f * scale
        var tries = 0
        while (tries < 4) {
            val nx = b.x + DIR_X[b.dir] * ahead
            val ny = b.y + DIR_Y[b.dir] * ahead
            val inside = nx > ahead && ny > ahead && nx < width - ahead && ny < height - ahead
            // Termites look for wood they haven't eaten yet.
            val eaten = inside && brightness(nx, ny) < 12
            if (inside && !eaten && random.nextInt(100) >= 4) break
            b.dir = random.nextInt(4)
            tries++
        }
        b.x = (b.x + DIR_X[b.dir] * step).coerceIn(0f, width.toFloat())
        b.y = (b.y + DIR_Y[b.dir] * step).coerceIn(0f, height.toFloat())
        deskCanvas.drawCircle(b.x, b.y, 3f * scale, eatenPaint)
    }

    private fun updateLoops() {
        val p = pan(px)
        sounds.loop("saw_idle", tool == CHAINSAW && !down, p)
        sounds.loop("saw_cut", tool == CHAINSAW && down, p)
        sounds.loop("flame", tool == FLAMER && down, p)
        sounds.loop("phaser", tool == PHASER && down, p)
        sounds.loop("washing", tool == WASHER && down, p)
        sounds.loop("fire", bullets.any { it.type == FLAME })
        sounds.loop("termite", bullets.any { it.type == TERMITE })
    }

    // endregion

    // region drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(desk, 0f, 0f, null)

        when (mode) {
            Mode.INTRO -> {
                drawScreen(canvas, art.intro)
                return
            }
            Mode.MENU -> {
                drawBullets(canvas)
                val m = menuRect()
                canvas.drawBitmap(art.menu, null, m, if (m.width() % art.menu.width == 0f) pixelPaint else smoothPaint)
                val ms = m.width() / art.menu.width
                val col = tool % 3
                val row = tool / 3
                dstRect.set(
                    m.left + col * MENU_CELL_W * ms, m.top + row * MENU_CELL_H * ms,
                    m.left + (col + 1) * MENU_CELL_W * ms, m.top + (row + 1) * MENU_CELL_H * ms,
                )
                canvas.drawBitmap(art.menuCursor, null, dstRect, pixelPaint)
                return
            }
            Mode.PLAY -> {
                drawBullets(canvas)
                drawTool(canvas)
                drawButton(canvas)
            }
        }
    }

    private fun drawBullets(canvas: Canvas) {
        // Standing fire and termites sit on the desktop, everything in flight above them.
        for (b in bullets) when (b.type) {
            FLAME -> drawSprite(canvas, art.flameBurn, b.frame, 0, b.x - 25 * scale, b.y - 40 * scale)
            TERMITE -> drawSprite(canvas, art.termite, b.frame, b.dir, b.x - 16 * scale, b.y - 16 * scale)
        }
        for (b in bullets) when (b.type) {
            SPARK -> drawSprite(canvas, art.mgHit, b.t, 0, b.x - 8 * scale, b.y - 8 * scale)
            SHELL -> drawSprite(canvas, art.shell, b.frame, 0, b.x - 32 * scale, b.y - 32 * scale)
            SHARD -> {
                val s = if (b.kind < 5) art.shards[b.kind] else art.splinters[b.kind - 5]
                drawSprite(canvas, s, 0, 0, b.x - 3 * scale, b.y - 3 * scale)
            }
            FLAME_FLY -> {
                val f = (b.t * (art.flameFly.frames - 1) / b.life).coerceAtMost(art.flameFly.frames - 1)
                drawCentred(canvas, art.flameFly, f, b.x, b.y)
            }
            BLOB -> {
                val f = (b.t * (art.blobs[b.kind].frames - 1) / b.life).coerceAtMost(art.blobs[b.kind].frames - 1)
                drawCentred(canvas, art.blobs[b.kind], f, b.x, b.y)
            }
        }
    }

    private fun drawTool(canvas: Canvas) {
        val anim = tick
        when (tool) {
            HAMMER -> if (actionTicks > 0) at(canvas, art.hammerDown, 0, 0, 4f, 11f)
                else at(canvas, art.hammerUp, 0, 0, 60f, 14f)
            CHAINSAW -> if (down) {
                val tip = SAW_TIPS[sawDir]
                at(canvas, art.sawCut, anim, sawDir, tip[0], tip[1])
            } else at(canvas, art.sawIdle, anim, 0, 58f, 31f)
            MACHINE_GUN -> if (down) at(canvas, art.mgFire, anim, 0, MG_HOT_X, MG_HOT_Y)
                else at(canvas, art.mgIdle, 0, 0, MG_HOT_X, MG_HOT_Y)
            FLAMER -> at(canvas, art.flamer, if (down) anim else 0, 0, FLAMER_HOT_X, FLAMER_HOT_Y)
            COLORER -> at(canvas, art.colorer, if (down) anim else 0, 0, FLAMER_HOT_X, FLAMER_HOT_Y)
            PHASER -> if (down) at(canvas, art.phaserFire, anim, 0, 34f, 34f)
                else at(canvas, art.phaserIdle, 0, 0, 34f, 34f)
            STAMP -> if (actionTicks > 0) at(canvas, art.stampDown, 0, 0, 46f, 222f)
                else at(canvas, art.stampUp, 0, 0, 46f, 222f)
            TERMITES -> if (actionTicks > 0) at(canvas, art.handEmpty, 0, 0, 42f, 36f)
                else at(canvas, art.hand, anim / 3, 0, 44f, 12f)
            WASHER -> if (down) at(canvas, art.washerFire, anim, 0, 34f, 34f)
                else at(canvas, art.washerIdle, 0, 0, 34f, 34f)
        }
    }

    /** Draws a tool so that its hot spot (in the original's pixels) lands on the finger. */
    private fun at(canvas: Canvas, sheet: Sheet, frame: Int, dir: Int, hotX: Float, hotY: Float) =
        drawSprite(canvas, sheet, frame, dir, px - hotX * scale, py - hotY * scale)

    /** Draws a frame with the middle of what is painted in it on (x, y), wherever in the frame that is. */
    private fun drawCentred(canvas: Canvas, sheet: Sheet, frame: Int, x: Float, y: Float) {
        val c = sheet.centre(frame)
        drawSprite(canvas, sheet, frame, 0, x - c[0] * scale, y - c[1] * scale)
    }

    private fun drawSprite(canvas: Canvas, sheet: Sheet, frame: Int, dir: Int, left: Float, top: Float) {
        dstRect.set(left, top, left + sheet.fw * scale, top + sheet.fh * scale)
        canvas.drawBitmap(sheet.bitmap, sheet.src(frame, dir, srcRect), dstRect, pixelPaint)
    }

    /** The picker button: a raised 9x-style button showing the current tool from the picker. */
    private fun drawButton(canvas: Canvas) {
        val r = buttonRect
        val b = max(2f, resources.displayMetrics.density * 1.5f)
        canvas.drawRect(r, if (buttonPressed) buttonDark else buttonLight)
        canvas.drawRect(r.left + b, r.top + b, r.right, r.bottom, if (buttonPressed) buttonLight else buttonDark)
        canvas.drawRect(r.left + b, r.top + b, r.right - b, r.bottom - b, if (buttonPressed) buttonShade else buttonFace)
        canvas.drawRect(r.left + b, r.top + b, r.right - b * 2, r.bottom - b * 2, if (buttonPressed) buttonShade else buttonFace)
        if (!buttonPressed) {
            canvas.drawRect(r.right - b * 2, r.top + b, r.right - b, r.bottom - b, buttonShade)
            canvas.drawRect(r.left + b, r.bottom - b * 2, r.right - b, r.bottom - b, buttonShade)
        }
        val col = tool % 3
        val row = tool / 3
        srcRect.set(col * MENU_CELL_W + 24, row * MENU_CELL_H + 4, col * MENU_CELL_W + 104, row * MENU_CELL_H + 76)
        val inset = b * 3 + if (buttonPressed) b else 0f
        val iw = r.width() - inset * 2
        val ih = iw * srcRect.height() / srcRect.width()
        dstRect.set(r.left + inset, r.centerY() - ih / 2 + (if (buttonPressed) b else 0f), r.left + inset + iw, r.centerY() + ih / 2 + (if (buttonPressed) b else 0f))
        canvas.drawBitmap(art.menu, srcRect, dstRect, smoothPaint)
    }

    private fun drawScreen(canvas: Canvas, bitmap: Bitmap) {
        val r = fitRect(bitmap, 0.94f)
        canvas.drawBitmap(bitmap, null, r, if (r.width() % bitmap.width == 0f) pixelPaint else smoothPaint)
    }

    private fun menuRect(): RectF = fitRect(art.menu, 0.94f)

    /**
     * Centres [bitmap] at the largest size that fits. Whole-number scales keep the pixel art
     * crisp, so one is used unless it would leave the picture much smaller than the screen.
     */
    private fun fitRect(bitmap: Bitmap, fill: Float): RectF {
        val fit = min(width * fill / bitmap.width, height * fill / bitmap.height)
        val whole = floor(fit)
        val s = if (whole >= 1f && whole / fit >= 0.75f) whole else fit
        val w = bitmap.width * s
        val h = bitmap.height * s
        val left = ((width - w) / 2f).roundToInt().toFloat()
        val top = ((height - h) / 2f).roundToInt().toFloat()
        return RectF(left, top, left + w, top + h)
    }

    // endregion

    // region helpers

    /** Draws a mark onto the desktop itself, centred on (x, y). */
    private fun stampOnDesk(bitmap: Bitmap, x: Float, y: Float) {
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        dstRect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        deskCanvas.drawBitmap(bitmap, null, dstRect, pixelPaint)
    }

    private fun brightness(x: Float, y: Float): Int {
        val c = desk.getPixel(x.toInt().coerceIn(0, desk.width - 1), y.toInt().coerceIn(0, desk.height - 1))
        return (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
    }

    private fun pan(x: Float) = if (width > 0) (x / width).coerceIn(0f, 1f) else 0.5f

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun Random.nextFloat(from: Float, until: Float) = from + nextFloat() * (until - from)

    private class Bullet(val type: Int) {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var sx = 0f
        var sy = 0f
        var tx = 0f
        var ty = 0f
        var floor = 0f
        var t = 0
        var life = 0
        var frame = 0
        var dir = 0
        var kind = 0
    }

    // endregion

    companion object {
        private const val TICK_MS = 55L

        // Tools, in the picker's order
        private const val HAMMER = 0
        private const val CHAINSAW = 1
        private const val MACHINE_GUN = 2
        private const val FLAMER = 3
        private const val COLORER = 4
        private const val PHASER = 5
        private const val STAMP = 6
        private const val TERMITES = 7
        private const val WASHER = 8

        // Bullet types, numbered as the original's "bullet type"
        private const val SPARK = 1
        private const val SHELL = 2
        private const val SHARD = 3
        private const val FLAME_FLY = 4
        private const val FLAME = 5
        private const val BLOB = 6
        private const val TERMITE = 7

        private const val MAX_BULLETS = 200 // the original's own limit
        private const val MAX_FLAMES = 60
        private const val MAX_TERMITES = 40

        private const val MENU_CELL_W = 128
        private const val MENU_CELL_H = 96
        private const val MENU_ROWS = 3

        // The throwers aim at the empty corner of their picture; their shots leave the nozzle.
        private const val FLAMER_HOT_X = 36f
        private const val FLAMER_HOT_Y = 40f
        // The middle of the barrel's open end (the flamer's pilot light sits just left of it).
        private const val FLAMER_NOZZLE_X = 107f
        private const val FLAMER_NOZZLE_Y = 111f
        private const val COLORER_NOZZLE_X = 107f
        private const val COLORER_NOZZLE_Y = 111f

        // The machine gun aims with its red laser dot; spent shells leave the breech.
        private const val MG_HOT_X = 90.5f
        private const val MG_HOT_Y = 85f
        private const val MG_EJECT_X = 100f
        private const val MG_EJECT_Y = 90f

        private const val WASH_RADIUS = 30f
        private const val TERMITE_HIT_RADIUS = 40f // generous, so a finger can hit one

        // The sawing chain-saw was drawn in eight poses; these are where each blade points
        // (degrees, counter-clockwise from right) and where its tip is.
        private val SAW_BLADE_ANGLES = floatArrayOf(-123f, -77f, -31f, -103f, -57f, -12f, 149f, -167f)
        private val SAW_TIPS = arrayOf(
            floatArrayOf(77f, 96f), floatArrayOf(88f, 102f), floatArrayOf(95f, 106f), floatArrayOf(103f, 102f),
            floatArrayOf(108f, 95f), floatArrayOf(104f, 86f), floatArrayOf(96f, 85f), floatArrayOf(87f, 86f),
        )

        // Termite directions as the sprite stores them: right, up, left, down
        private val DIR_X = floatArrayOf(1f, 0f, -1f, 0f)
        private val DIR_Y = floatArrayOf(0f, -1f, 0f, 1f)
    }
}
