package rocks.gorjan.gokixp.apps.elfbowling

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.edit
import rocks.gorjan.gokixp.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Elf Bowling (NVision Design, 1998). Santa bowls at ten elves on strike, on the original
 * game's own art, sounds and screen layout, all unpacked from Elf Bowling.exe.
 *
 * The screen is the original's: on the left, Santa's view up the icy lane; on the right, a
 * close-up of the elves at the far end; the Santa Instormomatic scoreboard over both and the
 * pin board between them. The bowling is ten frames of real ten-pin scoring.
 *
 * What changed for phones: the mouse that moved Santa is a finger dragged across the screen,
 * and the space bar that stopped the slider in front of him is a tap anywhere (the space bar
 * still works too). In a tall window the two halves of the screen stack, the elves over the
 * lane as on the Nintendo DS version, so the game fills a phone held upright.
 */
class ElfBowlingGame(context: Context, isMuted: () -> Boolean, onQuit: () -> Unit) {

    val root: View = ElfBowlingView(context, isMuted, onQuit).apply { id = R.id.elf_bowling_lane }

    fun cleanup() {
        (root as ElfBowlingView).release()
    }
}

@SuppressLint("ViewConstructor")
private class ElfBowlingView(
    context: Context,
    isMuted: () -> Boolean,
    private val onQuit: () -> Unit,
    art: ElfBowlingArt? = null,
) : View(context) {

    private enum class Mode { TITLE, RULES, PLAY, OVER }
    private enum class Phase { READY, WINDUP, ROLL_LEFT, ROLL_RIGHT, SETTLE, RAKE, CAGES }
    private enum class State { STAND, FLY, DOWN, GONE }
    private enum class Act { NONE, SIGN1, SIGN2, SIGN3, SMOKE, MOON, FART, HOLD_NOSE, DANCE, TALK, BLUSH, KICK }

    /** An elf standing in for a pin: [row] 0 is the head pin, [u] is across the lane in pin spacings. */
    private class Elf(val row: Int, val u: Float, val marker: Int) {
        var head = "elf0"
        var state = State.STAND
        var act = Act.NONE
        var actTicks = 0
        var scared = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var flyTicks = 0
        var landY = 0f
        var downSprite = "elf_dead0"
        val homeX get() = 480f + u * 56f
        val feetY get() = 322f - row * 20f
    }

    private class Knock(val elf: Elf, val d: Float, var ticks: Int)

    private class Flake(var x: Float, var y: Float, val speed: Float, val sprite: String, val phase: Float)

    /** One rectangle of the original screen, drawn at a place of its own in the window. */
    private class Panel(val src: RectF, val dst: RectF, val draw: (Canvas) -> Unit) {
        val scale get() = dst.width() / src.width()
    }

    private val art = art ?: ElfBowlingArt(context)
    private val sounds = ElfBowlingSounds(context, isMuted)
    private val random = Random(System.nanoTime())
    private val prefs = context.getSharedPreferences("elf_bowling", Context.MODE_PRIVATE)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = 9f
    }
    private val totalPaint = Paint(markPaint).apply { textSize = 10f }
    private val rulesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 40, 16)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 13f
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 16, 32)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = 40f
        setShadowLayer(3f, 2f, 2f, Color.WHITE)
    }
    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 236, 150)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = 13f
    }

    private var mode = Mode.TITLE
    private var phase = Phase.READY
    private var tick = 0
    private var running = false

    // The layout: which parts of the original screen go where, and how big.
    private val panels = ArrayList<Panel>()
    private var layoutW = 0
    private var layoutH = 0
    private var layoutMode: Mode? = null
    private var tall = false
    private var fit = 1f
    private var offX = 0f
    private var offY = 0f

    // The elves, back row first so they draw in order.
    private val elves = listOf(
        Elf(3, -1.5f, 3), Elf(3, -0.5f, 2), Elf(3, 0.5f, 1), Elf(3, 1.5f, 0),
        Elf(2, -1f, 6), Elf(2, 0f, 5), Elf(2, 1f, 4),
        Elf(1, -0.5f, 8), Elf(1, 0.5f, 7),
        Elf(0, 0f, 9),
    )
    private val titleElves = listOf(Elf(0, 0f, 0), Elf(0, 0f, 0))
    private val knocks = ArrayList<Knock>()
    private val flakes = ArrayList<Flake>()

    // Bowling.
    private val rolls = ArrayList<Int>()
    private var frame = 0
    private var ball = 0
    private var standingBefore = 10
    private var newRack = false
    private var gameOver = false
    private var showHint = true

    // Santa and the ball. u is across the lane in pin spacings; the gutters start past 1.73.
    private var santaU = 0f
    private var santaPose = "santa1"
    private var santaPoseTicks = 0
    private var santaY = SANTA_LOW
    private var slider = 7f
    private var sliderDir = 1f
    private var ballU0 = 0f
    private var ballUEnd = 0f
    private var deflect = 0f
    private var ballT = 0f
    private var ballQ = 0f
    private var gutter = 0f
    private val rowHit = BooleanArray(4)
    private var settleTicks = 0
    private var rakeY = RAKE_UP
    private var rakeDir = 0
    private var rakeHold = 0
    private var cageStage = 0
    private var cageOff = CAGE_TOP
    private var lightsTicks = 0
    private var tauntCooldown = 60
    private var best = prefs.getInt(KEY_BEST, 0)

    // The neighbours on the left of the lane.
    private var deerX = Float.NaN
    private var deerState = 0 // 0 walking, 1 falling, 2 dead
    private var deerTicks = 0
    private var frogX = Float.NaN
    private var frogDead = false
    private var frogTicks = 0
    private var birdX = Float.NaN
    private var birdY = 140f
    private var birdCarries = false

    // Touch.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressed: String? = null
    private var touchedButton: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            step()
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        for (i in 0 until 40) {
            flakes.add(
                Flake(
                    random.nextFloat() * 640f, random.nextFloat() * 480f, 0.5f + random.nextFloat(),
                    FLAKES[random.nextInt(FLAKES.size)], random.nextFloat() * 6f,
                )
            )
        }
        titleElves.forEach { it.head = HEADS[random.nextInt(HEADS.size)] }
    }

    // ---- lifecycle ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running || !isAttachedToWindow) return
        running = true
        sounds.resume()
        removeCallbacks(ticker)
        postDelayed(ticker, TICK_MS)
    }

    private fun stop() {
        if (!running) return
        running = false
        removeCallbacks(ticker)
        sounds.pause()
    }

    fun release() {
        stop()
        sounds.release()
        art.release()
    }

    // ---- the game ----

    private fun newGame() {
        rolls.clear()
        frame = 0
        ball = 0
        gameOver = false
        showHint = true
        santaU = 0f
        santaY = SANTA_LOW
        santaPose = "santa1"
        santaPoseTicks = 0
        cageOff = CAGE_TOP
        rack()
        rakeY = RAKE_UP
        rakeDir = 0
        knocks.clear()
        phase = Phase.READY
        mode = Mode.PLAY
        tauntCooldown = 45
    }

    private fun rack() {
        for (elf in elves) {
            elf.state = State.STAND
            elf.act = Act.NONE
            elf.scared = false
            elf.head = HEADS[random.nextInt(HEADS.size)]
        }
        standingBefore = 10
    }

    private fun standing() = elves.count { it.state == State.STAND }

    private fun throwBall() {
        if (mode != Mode.PLAY || phase != Phase.READY) return
        val aim = (slider - 7f) / 7f
        ballU0 = santaU
        ballUEnd = santaU + aim * 1.8f + (random.nextFloat() - 0.5f) * 0.2f
        deflect = 0f
        ballT = 0f
        ballQ = 0f
        gutter = 0f
        rowHit.fill(false)
        standingBefore = standing()
        showHint = false
        santaPose = "santa1"
        santaPoseTicks = 0
        phase = Phase.WINDUP
    }

    /** Santa is up from behind the lane with the ball: off it goes. */
    private fun letGo() {
        santaPose = "santa2"
        santaPoseTicks = 8
        phase = Phase.ROLL_LEFT
        sounds.play("bowl_drop")
        sounds.roll(true)
    }

    /** Where the ball is across the lane, [t] running 0 to 1 from Santa's hand to the back row. */
    private fun ballUAt(t: Float): Float {
        if (gutter != 0f) return gutter * 1.95f
        return ballU0 + (ballUEnd - ballU0) * t + deflect
    }

    private fun step() {
        tick++
        for (flake in flakes) {
            flake.y += flake.speed
            flake.x += sin(tick * 0.05f + flake.phase) * 0.4f
            if (flake.y > 490f) {
                flake.y = -10f
                flake.x = random.nextFloat() * 640f
            }
        }
        if (mode == Mode.TITLE || mode == Mode.RULES) {
            for (elf in titleElves) taunt(elf, titleElves, 140)
            return
        }
        stepSanta()
        if (lightsTicks > 0) {
            lightsTicks--
            if (lightsTicks % 4 == 0) sounds.play("light", 0.5f)
        }
        stepNeighbours()
        when (phase) {
            Phase.READY -> {
                slider += sliderDir * 0.45f
                if (slider >= 14f) { slider = 14f; sliderDir = -1f }
                if (slider <= 0f) { slider = 0f; sliderDir = 1f }
            }
            Phase.ROLL_LEFT -> {
                ballT += 0.026f
                checkGutter(ballT * 0.7f)
                hitNeighbours()
                if (ballT > 0.55f) for (elf in elves) {
                    if (elf.state == State.STAND && !elf.scared && random.nextInt(40) == 0) {
                        elf.scared = true
                        elf.act = Act.NONE
                    }
                }
                if (ballT >= 1f) {
                    phase = Phase.ROLL_RIGHT
                    ballQ = 0f
                }
            }
            Phase.ROLL_RIGHT -> {
                ballQ += 0.03f
                val t = 0.7f + 0.3f * ballQ
                checkGutter(t)
                if (gutter == 0f) for (row in 0..3) {
                    if (!rowHit[row] && rightBallY() <= 322f - row * 20f + 4f) {
                        rowHit[row] = true
                        val u = ballUAt(t)
                        for (elf in elves) {
                            if (elf.row != row || elf.state != State.STAND) continue
                            var d = elf.u - u
                            if (abs(d) >= 0.55f) continue
                            if (abs(d) < 0.01f) d = if (random.nextBoolean()) 0.05f else -0.05f
                            knock(elf, d)
                            deflect -= sign(d) * 0.12f
                        }
                    }
                }
                if (ballQ >= 1.25f) {
                    sounds.roll(false)
                    phase = Phase.SETTLE
                    settleTicks = 30
                }
            }
            Phase.SETTLE -> {
                if (settleTicks > 0) settleTicks--
                if (settleTicks == 0 && knocks.isEmpty() && elves.none { it.state == State.FLY }) scoreRoll()
            }
            Phase.WINDUP -> if (santaY <= SANTA_HIGH) letGo()
            Phase.RAKE -> stepRake()
            Phase.CAGES -> stepCages()
        }
        stepKnocks()
        stepElves()
    }

    private fun checkGutter(t: Float) {
        if (gutter != 0f) return
        val u = ballUAt(t)
        if (abs(u) > 1.73f) {
            gutter = sign(u)
            sounds.play("gutter_ball")
        }
    }

    /** The ball knocks an elf over; the elf flies off at an angle and can take others with it. */
    private fun knock(elf: Elf, d: Float) {
        if (elf.state != State.STAND) return
        elf.state = State.FLY
        elf.act = Act.NONE
        elf.x = elf.homeX
        elf.y = elf.feetY
        elf.vx = sign(d) * (2f + abs(d) * 6f) + (random.nextFloat() - 0.5f) * 2f
        elf.vy = -(7f + random.nextFloat() * 4f)
        elf.flyTicks = 0
        elf.landY = elf.feetY + 10f + random.nextFloat() * 30f
        elf.downSprite = DOWN_SPRITES[random.nextInt(DOWN_SPRITES.size)]
        if (elves.count { it.state == State.FLY } == 1) sounds.play(listOf("hit", "hit", "elfbaby", "elfscream").random(random))
        else if (random.nextInt(3) == 0) sounds.play("pins", 0.8f)

        val s = (d * 1.8f + (random.nextFloat() - 0.5f) * 0.5f).coerceIn(-1.4f, 1.4f)
        for (other in elves) {
            if (other.state != State.STAND) continue
            if (other.row > elf.row) {
                val lateral = elf.u + s * 0.6f * (other.row - elf.row)
                if (abs(other.u - lateral) < 0.5f && random.nextFloat() < 0.85f) {
                    knocks.add(Knock(other, other.u - lateral + s * 0.3f, (other.row - elf.row) * 3))
                }
            } else if (other.row == elf.row && abs(s) > 0.8f && abs(other.u - (elf.u + sign(s))) < 0.1f && random.nextFloat() < 0.45f) {
                knocks.add(Knock(other, sign(s) * 0.3f, 2))
            }
        }
    }

    private fun stepKnocks() {
        val iterator = knocks.iterator()
        val due = ArrayList<Knock>()
        while (iterator.hasNext()) {
            val k = iterator.next()
            if (--k.ticks <= 0) {
                due.add(k)
                iterator.remove()
            }
        }
        for (k in due) knock(k.elf, k.d)
    }

    private fun stepElves() {
        for (elf in elves) {
            if (elf.state == State.FLY) {
                elf.flyTicks++
                elf.x += elf.vx
                elf.y += elf.vy
                elf.vy += 0.9f
                if (elf.flyTicks > 6 && elf.vy > 0f && elf.y >= elf.landY) {
                    elf.state = State.DOWN
                    elf.y = elf.landY
                }
            }
        }
        if (mode == Mode.PLAY && phase == Phase.READY) {
            val idle = elves.filter { it.state == State.STAND }
            for (elf in idle) taunt(elf, idle, 260)
        } else {
            for (elf in elves) if (elf.act != Act.NONE && --elf.actTicks <= 0) elf.act = Act.NONE
        }
    }

    /** Now and then an elf starts up a bit: a picket sign, a smoke, a moon, a fart. */
    private fun taunt(elf: Elf, crowd: List<Elf>, rarity: Int) {
        if (elf.act != Act.NONE) {
            if (--elf.actTicks <= 0) elf.act = Act.NONE
            else if (elf.act == Act.MOON && elf.actTicks % 12 == 0) sounds.play("slap_ass", 0.7f)
            return
        }
        if (tauntCooldown > 0) {
            if (elf === crowd.lastOrNull()) tauntCooldown--
            return
        }
        if (random.nextInt(rarity) != 0) return
        startTaunt(elf, crowd)
        tauntCooldown = 25 + random.nextInt(50)
    }

    private fun startTaunt(elf: Elf, crowd: List<Elf>) {
        val acts = if (elf.head == "elf0") Act.entries.drop(1) else Act.entries.drop(1).filter { it != Act.TALK }
        val act = acts.filter { it != Act.HOLD_NOSE }.random(random)
        elf.act = act
        elf.actTicks = 50 + random.nextInt(40)
        when (act) {
            Act.SIGN1 -> sounds.play("fewer")
            Act.SIGN2 -> sounds.play("taunt2b")
            Act.SIGN3 -> sounds.play("whosyrdaddy")
            Act.MOON -> sounds.play("slap_ass")
            Act.TALK -> sounds.play(listOf("taunt1b", "taunt3b", "hey_santa").random(random))
            Act.FART -> {
                sounds.play(if (random.nextBoolean()) "fart" else "elliot_farted")
                for (other in crowd) {
                    if (other !== elf && other.act == Act.NONE && abs(other.u - elf.u) <= 1f && abs(other.row - elf.row) <= 1) {
                        other.act = Act.HOLD_NOSE
                        other.actTicks = elf.actTicks
                    }
                }
            }
            else -> {}
        }
    }

    private fun scoreRoll() {
        val down = standingBefore - standing()
        rolls.add(down)
        val strike = down == 10 && standingBefore == 10
        val spare = !strike && standing() == 0
        when {
            strike -> {
                sounds.play("ho_ho_ho")
                santaPose = "santa_joy"
                santaPoseTicks = 45
                lightsTicks = 45
            }
            spare -> {
                santaPose = "santa_joy"
                santaPoseTicks = 35
                lightsTicks = 25
            }
            down == 0 -> {
                sounds.play(if (gutter != 0f) "gutterball2b" else "elves_laugh")
            }
            else -> if (down < 4) sounds.play("elves_laugh", 0.8f)
        }
        for (elf in elves) elf.scared = false

        // Where the game goes next.
        newRack = false
        if (frame < 9) {
            if (ball == 0 && standing() > 0) {
                ball = 1
            } else {
                frame++
                ball = 0
                newRack = true
            }
        } else {
            val tenth = rolls.size - firstRollOfTenth()
            val first = rolls[firstRollOfTenth()]
            val more = tenth == 1 || (tenth == 2 && first + rolls[firstRollOfTenth() + 1] >= 10)
            if (more) {
                ball = tenth
                newRack = standing() == 0
            } else {
                gameOver = true
            }
        }
        if (!gameOver && ball > 0 && !newRack) {
            // A few of the elves left standing have something to say about it.
            elves.filter { it.state == State.STAND }.randomOrNull(random)?.let { startTaunt(it, elves.filter { e -> e.state == State.STAND }) }
        }
        phase = Phase.RAKE
        rakeDir = 1
        rakeHold = 0
    }

    private fun firstRollOfTenth(): Int {
        var i = 0
        for (f in 0 until 9) i += if (rolls.getOrNull(i) == 10) 1 else 2
        return i
    }

    private fun stepRake() {
        when {
            rakeDir > 0 -> {
                rakeY += 16f
                if (rakeY >= RAKE_DOWN) {
                    rakeY = RAKE_DOWN
                    rakeDir = 0
                    rakeHold = 10
                    for (elf in elves) if (elf.state != State.STAND) elf.state = State.GONE
                }
            }
            rakeDir == 0 -> if (--rakeHold <= 0) rakeDir = -1
            else -> {
                rakeY -= 16f
                if (rakeY <= RAKE_UP) {
                    rakeY = RAKE_UP
                    rakeDir = 0
                    when {
                        gameOver -> finishGame()
                        newRack -> {
                            // The cages come down for whoever is still standing, lift them away,
                            // and bring down a fresh rack.
                            phase = Phase.CAGES
                            cageOff = CAGE_TOP
                            cageStage = if (standing() > 0) 0 else 2
                            if (cageStage == 2) rack()
                        }
                        else -> ready()
                    }
                }
            }
        }
    }

    private fun ready() {
        sounds.play("bowl_back")
        phase = Phase.READY
        tauntCooldown = 30
    }

    private fun stepCages() {
        when (cageStage) {
            0 -> {
                cageOff += 12f
                if (cageOff >= 0f) { cageOff = 0f; cageStage = 1; sounds.play("click") }
            }
            1 -> {
                cageOff -= 10f
                if (cageOff <= CAGE_TOP) { cageOff = CAGE_TOP; rack(); cageStage = 2 }
            }
            2 -> {
                cageOff += 10f
                if (cageOff >= 0f) {
                    cageOff = 0f
                    cageStage = 3
                    sounds.play(if (random.nextBoolean()) "rackpins" else "rack_pins2")
                }
            }
            else -> {
                cageOff -= 12f
                if (cageOff <= CAGE_TOP) { cageOff = CAGE_TOP; ready() }
            }
        }
    }

    /**
     * Santa waits ducked down behind the near end of the lane with only his hat showing, comes
     * up to bowl, turns round to watch the ball go, and ducks back down.
     */
    private fun stepSanta() {
        if (santaPoseTicks > 0) {
            santaPoseTicks--
            if (santaPoseTicks == 0 && santaPose == "santa2") {
                santaPose = "santa_walk_back"
                santaPoseTicks = 36
            }
        }
        val target = if (phase == Phase.WINDUP || santaPoseTicks > 0) SANTA_HIGH else SANTA_LOW
        santaY += (target - santaY).coerceIn(-14f, 10f)
        if (santaY >= SANTA_LOW && santaPoseTicks == 0) santaPose = "santa1"
    }

    private fun finishGame() {
        mode = Mode.OVER
        sounds.play("gameovr")
        for (elf in elves) elf.state = State.GONE
        val total = totals().lastOrNull { it != null } ?: 0
        if (total > best) {
            best = total
            prefs.edit { putInt(KEY_BEST, best) }
        }
    }

    // ---- the deer, Kalvin the frog and the bird, who wander across Santa's lane ----

    private fun stepNeighbours() {
        if (deerX.isNaN()) {
            if (random.nextInt(700) == 0) { deerX = -50f; deerState = 0; deerTicks = 0 }
        } else {
            deerTicks++
            when (deerState) {
                0 -> { deerX += 1.6f; if (deerX > 380f) deerX = Float.NaN }
                1 -> if (deerTicks > 10) { deerState = 2; deerTicks = 0 }
                else -> if (deerTicks > 200) deerX = Float.NaN
            }
        }
        if (frogX.isNaN()) {
            if (!frogDead && random.nextInt(600) == 0) {
                frogX = -32f
                frogTicks = 0
                sounds.play("frogcroak", 0.7f)
            }
        } else if (!frogDead) {
            frogTicks++
            if (frogTicks % 16 >= 10) frogX += 4f
            if (frogX > 360f) frogX = Float.NaN
        }
        if (birdX.isNaN()) {
            val chance = if (frogDead) 120 else 900
            if (random.nextInt(chance) == 0) {
                birdX = -60f
                birdY = 140f
                birdCarries = false
                if (frogDead) sounds.play("birddie", 0.8f)
            }
        } else {
            birdX += 4f
            if (frogDead && !frogX.isNaN()) {
                // Down for Kalvin, and off with him.
                val reach = 1f - min(1f, abs(birdX - frogX) / 120f)
                if (!birdCarries) birdY = 140f + 150f * reach
                if (birdX >= frogX) {
                    birdCarries = true
                    frogX = Float.NaN
                    frogDead = false
                }
            } else if (birdCarries) {
                birdY = max(140f, birdY - 3f)
            }
            if (birdX > 380f) birdX = Float.NaN
        }
    }

    private fun hitNeighbours() {
        val bx = leftBallX()
        val by = leftBallY()
        if (!deerX.isNaN() && deerState == 0 && by in 235f..275f && abs(bx - deerX) < 28f) {
            deerState = 1
            deerTicks = 0
            sounds.play("hit")
        }
        if (!frogX.isNaN() && !frogDead && by in 295f..330f && abs(bx - frogX) < 20f) {
            frogDead = true
            sounds.play("froguh")
        }
    }

    // ---- where things are, in the original's 640x480 pixels ----

    private fun laneHalfWidth(y: Float) = 33f + 0.358f * (y - 210f)

    private fun leftBallY() = 210f + 255f * (1f - ballT).pow(1.5f)

    private fun leftBallX(): Float {
        val y = leftBallY()
        return 160f + ballUAt(ballT * 0.7f) / 1.73f * laneHalfWidth(y)
    }

    private fun rightBallY() = 520f - 330f * ballQ

    private fun santaBallX() = 160f + santaU / 1.73f * laneHalfWidth(465f)

    private fun ballSprite(size: Float): String {
        var bestName = BALLS[0].second
        var bestDiff = Float.MAX_VALUE
        for ((s, name) in BALLS) {
            val diff = abs(s - size)
            if (diff < bestDiff) { bestDiff = diff; bestName = name }
        }
        return bestName
    }

    // ---- drawing ----

    private fun sprite(canvas: Canvas, name: String, cx: Float, cy: Float, mirror: Boolean = false) {
        val bitmap = art[name] ?: return
        if (mirror) {
            canvas.save()
            canvas.scale(-1f, 1f, cx, cy)
        }
        canvas.drawBitmap(bitmap, cx - bitmap.width / 2f, cy - bitmap.height / 2f, paint)
        if (mirror) canvas.restore()
    }

    private fun drawBackdrop(canvas: Canvas, fromX: Float, toX: Float, bases: Boolean) {
        canvas.drawRect(fromX, 0f, toX, 480f, blackPaint)
        sprite(canvas, "mountains", 107f, 107f)
        sprite(canvas, "mountains", 319f, 129f)
        sprite(canvas, "mountains", 532f, 107f, mirror = true)
        if (bases) for (i in 0 until 20) {
            val x = 16f + 32f * i
            if (x + 16f > fromX && x - 16f < toX) sprite(canvas, "mountain_base", x, 347f)
        }
    }

    private fun drawSnow(canvas: Canvas) {
        for (flake in flakes) sprite(canvas, flake.sprite, flake.x, flake.y)
    }

    /** Santa's view up the lane. */
    private fun drawLeft(canvas: Canvas, pinsX: Float? = null, pinsY: Float = 0f) {
        canvas.save()
        canvas.clipRect(0f, 0f, 320f, 480f)
        drawBackdrop(canvas, 0f, 320f, bases = false)
        sprite(canvas, "left_scene_top", 80f, 92f)
        sprite(canvas, "left_scene_top", 240f, 92f, mirror = true)
        sprite(canvas, "left_scene_bottom", 80f, 332f)
        sprite(canvas, "left_scene_bottom", 240f, 332f, mirror = true)

        // The same elves, small, in the dark at the far end of the lane.
        canvas.drawRect(121f, 184f, 199f, 217f, blackPaint)
        canvas.save()
        canvas.clipRect(116f, 184f, 204f, 240f)
        canvas.translate(160f, MINI_FEET)
        canvas.scale(MINI, MINI)
        canvas.translate(-480f, -322f)
        drawElves(canvas, withBall = false)
        canvas.restore()

        if (!birdX.isNaN()) {
            val frames = if (birdCarries) BIRD_FROG else BIRD
            sprite(canvas, frames[(tick / 3) % 3], birdX, birdY)
        }
        if (!deerX.isNaN()) {
            val name = when (deerState) { 0 -> DEER[(tick / 5) % 3]; 1 -> "deer_fall"; else -> "deer_dead" }
            sprite(canvas, name, deerX, 240f)
        }
        if (!frogX.isNaN()) {
            val name = when {
                frogDead -> "kalvin_dead"
                frogTicks % 16 < 10 -> "kalvin_hop_a"
                frogTicks % 16 < 13 -> "kalvin_hop_b"
                else -> "kalvin_hop_c"
            }
            sprite(canvas, name, frogX, 310f)
        }

        val rolling = mode == Mode.PLAY && phase == Phase.ROLL_LEFT
        if (rolling) {
            val y = leftBallY()
            sprite(canvas, ballSprite(52f - 38f * ballT), leftBallX(), y)
        }

        // The slider, and the sign that explains it.
        if (mode == Mode.PLAY && phase == Phase.READY) {
            val lit = slider.toInt()
            for (i in 0 until 15) {
                val name = when (abs(i - lit)) { 0 -> "marker_on"; 1 -> "marker_half"; else -> "marker_off" }
                sprite(canvas, name, 76f + 12f * i, 370f)
            }
            sprite(canvas, "marker_off", 160f, 357f)
        }

        val santaX = santaBallX() - 66f
        sprite(canvas, santaPose, santaX, santaY)
        if (phase == Phase.WINDUP) sprite(canvas, "ball60", santaX + 66f, santaY + 45f)
        if (mode == Mode.PLAY && phase == Phase.READY && showHint) sprite(canvas, "hint1a", 160f, 335f)

        drawSnow(canvas)
        canvas.restore()
        if (pinsX != null) drawPinsBoard(canvas, pinsX, pinsY)
    }

    /** The far end of the lane, where the elves stand. */
    private fun drawRight(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(320f, 0f, 640f, 480f)
        canvas.drawRect(320f, 0f, 640f, 480f, blackPaint)
        sprite(canvas, "right_scene_top", 400f, 40f)
        sprite(canvas, "right_scene_top", 560f, 40f, mirror = true)
        sprite(canvas, "right_scene_bottom", 400f, 280f)
        sprite(canvas, "right_scene_bottom", 560f, 280f, mirror = true)
        drawElves(canvas, withBall = true)
        if (mode == Mode.OVER) drawFinalScore(canvas)
        drawSnow(canvas)
        canvas.restore()
    }

    /** The elves, the ball among them, and the rake and cages that clear them. */
    private fun drawElves(canvas: Canvas, withBall: Boolean) {
        for (elf in elves) if (elf.state == State.DOWN) sprite(canvas, elf.downSprite, elf.x, elf.y - 45f, elf.vx < 0f)

        val caged = phase == Phase.CAGES
        val hanging = if (caged && (cageStage == 1 || cageStage == 2)) cageOff else 0f
        val showBall = withBall && mode == Mode.PLAY && phase == Phase.ROLL_RIGHT
        val by = rightBallY()
        val size = 80f - 56f * min(1f, ballQ)
        val ballBottom = by + size / 2f
        var ballDrawn = !showBall
        for (elf in elves) {
            if (elf.state != State.STAND) continue
            if (!ballDrawn && elf.feetY > ballBottom) {
                drawRightBall(canvas, by, size)
                ballDrawn = true
            }
            drawElf(canvas, elf, elf.homeX, elf.feetY - 45f + hanging)
        }
        if (!ballDrawn) drawRightBall(canvas, by, size)

        for (elf in elves) if (elf.state == State.FLY) sprite(canvas, "elf_fly0", elf.x, elf.y - 45f, elf.vx < 0f)

        if (caged) for (elf in elves) {
            if (cageStage >= 2 || elf.state == State.STAND) sprite(canvas, "one_racker", elf.homeX, elf.feetY - 142f + cageOff)
        }
        sprite(canvas, "rake", 480f, rakeY)
    }

    private fun drawRightBall(canvas: Canvas, y: Float, size: Float) {
        val t = 0.7f + 0.3f * ballQ
        val k = max(0.9f, 1f + (y - 300f) / 300f)
        sprite(canvas, ballSprite(size), 480f + ballUAt(t) * 56f * k, y)
    }

    private fun drawElf(canvas: Canvas, elf: Elf, cx: Float, cy: Float) {
        val flip = (tick / 6) % 2 == 1
        var body = "elf_body0"
        var head = elf.head
        var arms = "elf_arms_nothing"
        var overlay: String? = null
        when (elf.act) {
            Act.SIGN1 -> arms = if (flip) "elf_arms_sign1b" else "elf_arms_sign1a"
            Act.SIGN2 -> arms = if (flip) "elf_arms_sign2b" else "elf_arms_sign2a"
            Act.SIGN3 -> arms = if (flip) "arms_sign3b" else "arms_sign3"
            Act.SMOKE -> {
                arms = if (flip) "elf_arms_smoke2" else "elf_arms_smoke1"
                head = if (flip) "elf_smoke" else "elf_smoke2"
            }
            Act.MOON -> {
                body = "elf_body_ass"
                arms = "elf_arms_ass"
                head = "elf_head_back"
            }
            Act.FART -> head = "elf_fart"
            Act.HOLD_NOSE -> arms = "arms_hold_nose"
            Act.DANCE -> {
                body = if (flip) "elf_body_side_step" else "elf_body0"
                arms = "arms_dance1"
            }
            Act.TALK -> if (flip) overlay = "elf0_talk"
            Act.BLUSH -> head = "elf_head_blush"
            Act.KICK -> body = if (flip) "elf_body_kick" else "elf_body0"
            Act.NONE -> if (elf.scared) arms = when ((tick / 4) % 3) { 0 -> "arms_scared"; 1 -> "arms_scared2"; else -> "arms_flail" }
        }
        sprite(canvas, body, cx, cy)
        sprite(canvas, head, cx, cy)
        overlay?.let { sprite(canvas, it, cx, cy) }
        sprite(canvas, arms, cx, cy)
    }

    private fun drawPinsBoard(canvas: Canvas, cx: Float, cy: Float) {
        val dx = cx - 320f
        val dy = cy - 440f
        sprite(canvas, "pins_board", cx, cy)
        for (elf in elves) {
            val (mx, my) = PIN_MARKERS[elf.marker]
            sprite(canvas, if (elf.state == State.STAND) "elf_marker_on" else "elf_marker", mx + dx, my + dy)
        }
        sprite(canvas, if (ball == 0) "ball_on" else "ball_off", 286f + dx, 473f + dy)
        sprite(canvas, if (ball >= 1) "ball_on" else "ball_off", 300f + dx, 473f + dy)
    }

    private fun drawScoreboard(canvas: Canvas, lights: Boolean = true) {
        val lightsOn = lightsTicks > 0 && (lightsTicks / 4) % 2 == 0
        if (lights) sprite(canvas, if (lightsOn) "lights_on" else "lights_off", 317f, 66f)
        sprite(canvas, "score_board", 317f, 66f)
        if (pressed == "exit") sprite(canvas, "quit_on", 507f, 66f)
        val marks = marks()
        val totals = totals()
        for (f in 0 until 10) {
            val x = 164f + 30f * f
            if (f < 9) {
                canvas.drawText(marks[f][0], x + 10f, 65f, markPaint)
                canvas.drawText(marks[f][1], x + 22.5f, 64f, markPaint)
            } else {
                canvas.drawText(marks[f][0], x + 9f, 65f, markPaint)
                canvas.drawText(marks[f][1], x + 18.5f, 64f, markPaint)
                canvas.drawText(marks[f][2], x + 27.5f, 64f, markPaint)
            }
            totals[f]?.let { canvas.drawText(it.toString(), x + 12f, 78f, totalPaint) }
        }
    }

    /** Just the scoreboard's frames, with the lights strung round them. */
    private fun drawBoardCrop(canvas: Canvas) {
        val lightsOn = lightsTicks > 0 && (lightsTicks / 4) % 2 == 0
        art[if (lightsOn) "lights_on" else "lights_off"]?.let { canvas.drawBitmap(it, null, BOARD_CROP, paint) }
        canvas.save()
        canvas.clipRect(BOARD_INNER)
        drawScoreboard(canvas, lights = false)
        canvas.restore()
    }

    private fun drawFinalScore(canvas: Canvas) {
        val total = totals().lastOrNull { it != null } ?: 0
        sprite(canvas, "score", 480f, 170f)
        canvas.drawText(total.toString(), 480f, 222f, bigPaint)
        canvas.drawText(context.getString(R.string.elf_bowling_best, best), 480f, 244f, bestPaint)
        sprite(canvas, if (pressed == "again") "play_on" else "play_off", 440f, 290f)
        sprite(canvas, if (pressed == "quit") "intro_quit_on" else "intro_quit_off", 520f, 290f)
    }

    private fun drawTitle(canvas: Canvas) {
        drawBackdrop(canvas, 0f, 640f, bases = true)
        sprite(canvas, "bowling_logo", 340f, 130f)
        val (left, right) = titleElfX()
        drawElf(canvas, titleElves[0], left, 380f)
        drawElf(canvas, titleElves[1], right, 380f)
        if (mode == Mode.RULES) {
            sprite(canvas, "scroll", 325f, 310f)
            val lines = context.getString(R.string.elf_bowling_rules).split('\n')
            var y = 310f - (lines.size - 1) * 8.5f
            for (line in lines) {
                canvas.drawText(line, 325f, y + 4f, rulesPaint)
                y += 17f
            }
        } else {
            sprite(canvas, if (pressed == "play") "play_on" else "play_off", 240f, 429f)
            sprite(canvas, if (pressed == "rules") "rules_on" else "rules_off", 320f, 429f)
            sprite(canvas, if (pressed == "quit") "intro_quit_on" else "intro_quit_off", 400f, 429f)
        }
        drawSnow(canvas)
    }

    private fun titleElfX() = if (tall) 190f to 470f else 80f to 570f

    private fun buildLayout() {
        val w = width
        val h = height
        if (w == layoutW && h == layoutH && layoutMode == mode) return
        layoutW = w
        layoutH = h
        layoutMode = mode
        panels.clear()
        val title = mode == Mode.TITLE || mode == Mode.RULES

        // Stack the two halves when that shows the game bigger than side by side does.
        val wide = min(w / 640f, h / 480f)
        val stacked = min(w / 320f, h / STACKED_H)
        tall = 320f * STACKED_H * stacked * stacked > 640f * 480f * wide * wide

        val vw: Float
        val vh: Float
        if (title) {
            val src = if (tall) RectF(100f, 0f, 580f, 480f) else RectF(0f, 0f, 640f, 480f)
            panels.add(Panel(src, RectF(0f, 0f, src.width(), src.height())) { drawTitle(it) })
            vw = src.width()
            vh = src.height()
        } else if (tall) {
            // Two screens, as on the DS: the elves up close under the scoreboard with the pin
            // board in the corner, and Santa's lane below with the elves small at its end.
            panels.add(Panel(RectF(320f, 50f, 640f, 330f), RectF(0f, 0f, 320f, 280f)) {
                drawRight(it)
                drawPinsBoard(it, 594f, 285f)
            })
            panels.add(Panel(RectF(BOARD_CROP), RectF(0f, 0f, 320f, BOARD_CROP.height() * 320f / BOARD_CROP.width())) { drawBoardCrop(it) })
            panels.add(Panel(RectF(0f, 150f, 320f, 480f), RectF(0f, 280f, 320f, 610f)) { drawLeft(it) })
            vw = 320f
            vh = 610f
        } else {
            panels.add(Panel(RectF(0f, 0f, 640f, 480f), RectF(0f, 0f, 640f, 480f)) {
                drawLeft(it)
                drawRight(it)
                it.drawRect(316f, 0f, 324f, 480f, blackPaint)
                drawPinsBoard(it, 320f, 440f)
                drawScoreboard(it)
            })
            vw = 640f
            vh = 480f
        }
        fit = min(w / vw, h / vh)
        offX = (w - vw * fit) / 2f
        offY = (h - vh * fit) / 2f
        paint.isFilterBitmap = fit < 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        buildLayout()
        for (panel in panels) {
            canvas.save()
            canvas.translate(offX, offY)
            canvas.scale(fit, fit)
            canvas.clipRect(panel.dst)
            canvas.translate(panel.dst.left, panel.dst.top)
            canvas.scale(panel.scale, panel.scale)
            canvas.translate(-panel.src.left, -panel.src.top)
            panel.draw(canvas)
            canvas.restore()
        }
    }

    // ---- input ----

    /** A point in the window, as a point on the original screen, and the panel it fell in. */
    private fun toScene(x: Float, y: Float): Pair<Panel, FloatArray>? {
        val vx = (x - offX) / fit
        val vy = (y - offY) / fit
        val panel = panels.firstOrNull { it.dst.contains(vx, vy) } ?: return null
        val s = panel.scale
        return panel to floatArrayOf(panel.src.left + (vx - panel.dst.left) / s, panel.src.top + (vy - panel.dst.top) / s)
    }

    private fun buttonAt(x: Float, y: Float): String? {
        val (_, p) = toScene(x, y) ?: return null
        fun hit(cx: Float, cy: Float, w: Float, h: Float) = abs(p[0] - cx) <= w / 2f + 6f && abs(p[1] - cy) <= h / 2f + 8f
        return when (mode) {
            Mode.TITLE -> when {
                hit(240f, 429f, 73f, 27f) -> "play"
                hit(320f, 429f, 73f, 27f) -> "rules"
                hit(400f, 429f, 73f, 27f) -> "quit"
                else -> null
            }
            Mode.RULES -> null
            Mode.PLAY -> if (hit(507f, 66f, 57f, 21f)) "exit" else null
            Mode.OVER -> when {
                hit(440f, 290f, 73f, 27f) -> "again"
                hit(520f, 290f, 73f, 27f) -> "quit"
                hit(507f, 66f, 57f, 21f) -> "exit"
                else -> null
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                pressed = buttonAt(event.x, event.y)
                touchedButton = pressed
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchedButton != null) {
                    pressed = if (buttonAt(event.x, event.y) == touchedButton) touchedButton else null
                } else {
                    if (!dragging && abs(event.x - downX) > slop) dragging = true
                    if (dragging && mode == Mode.PLAY && phase == Phase.READY) {
                        val scale = fit * (panels.lastOrNull()?.scale ?: 1f)
                        santaU = (santaU + (event.x - lastX) / scale / 69f).coerceIn(-1.3f, 1.3f)
                    }
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                val button = touchedButton
                pressed = null
                touchedButton = null
                if (button != null) {
                    if (buttonAt(event.x, event.y) == button) press(button)
                } else if (!dragging) {
                    tap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                touchedButton = null
                dragging = false
            }
        }
        invalidate()
        return true
    }

    private fun press(button: String) {
        sounds.play("click")
        when (button) {
            "play", "again" -> newGame()
            "rules" -> mode = Mode.RULES
            "quit" -> if (mode == Mode.TITLE) onQuit() else toTitle()
            "exit" -> toTitle()
        }
    }

    private fun toTitle() {
        sounds.roll(false)
        mode = Mode.TITLE
        knocks.clear()
    }

    private fun tap() {
        when (mode) {
            Mode.RULES -> { sounds.play("click"); mode = Mode.TITLE }
            Mode.PLAY -> throwBall()
            else -> {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER) {
            tap()
            return true
        }
        if (mode == Mode.PLAY && phase == Phase.READY) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { santaU = (santaU - 0.1f).coerceAtLeast(-1.3f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { santaU = (santaU + 0.1f).coerceAtMost(1.3f); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- scoring ----

    private fun mark(pins: Int) = if (pins == 0) "-" else pins.toString()

    /** What each frame's boxes show: the first ball, the second, and the tenth frame's third. */
    private fun marks(): Array<Array<String>> {
        val marks = Array(10) { Array(3) { "" } }
        var i = 0
        for (f in 0 until 10) {
            if (i >= rolls.size) break
            if (f < 9) {
                val first = rolls[i]
                if (first == 10) {
                    marks[f][1] = "X"
                    i++
                    continue
                }
                marks[f][0] = mark(first)
                val second = rolls.getOrNull(i + 1) ?: break
                marks[f][1] = if (first + second == 10) "/" else mark(second)
                i += 2
            } else {
                var up = 10
                for (k in i until min(rolls.size, i + 3)) {
                    val r = rolls[k]
                    marks[9][k - i] = when {
                        r == up && up == 10 -> "X"
                        r == up -> "/"
                        else -> mark(r)
                    }
                    up -= r
                    if (up == 0) up = 10
                }
            }
        }
        return marks
    }

    /** The running total under each frame, once that frame's bonus balls are in. */
    private fun totals(): Array<Int?> {
        val totals = arrayOfNulls<Int>(10)
        var i = 0
        var total = 0
        for (f in 0 until 10) {
            if (i >= rolls.size) break
            if (f < 9) {
                val first = rolls[i]
                val second = rolls.getOrNull(i + 1)
                if (first == 10) {
                    val b1 = rolls.getOrNull(i + 1) ?: break
                    val b2 = rolls.getOrNull(i + 2) ?: break
                    total += 10 + b1 + b2
                    i++
                } else {
                    if (second == null) break
                    if (first + second == 10) {
                        val b = rolls.getOrNull(i + 2) ?: break
                        total += 10 + b
                    } else {
                        total += first + second
                    }
                    i += 2
                }
                totals[f] = total
            } else {
                val tenth = rolls.subList(i, rolls.size)
                val done = tenth.size == 3 || (tenth.size == 2 && tenth[0] + tenth[1] < 10)
                if (done) totals[9] = total + tenth.sum()
            }
        }
        return totals
    }

    companion object {
        private const val TICK_MS = 33L
        private const val KEY_BEST = "best_score"
        private const val RAKE_UP = -190f
        private const val RAKE_DOWN = 160f
        private const val STACKED_H = 610f
        private const val SANTA_LOW = 548f
        private const val SANTA_HIGH = 420f
        private const val CAGE_TOP = -340f
        private const val MINI = 0.2f
        private const val MINI_FEET = 221f
        private val BOARD_CROP = RectF(140f, 10f, 494f, 98f)
        private val BOARD_INNER = RectF(150f, 24f, 484f, 85f)

        private val HEADS = listOf("elf0", "elf1", "elf2")
        private val DOWN_SPRITES = listOf("elf_dead0", "elf_dead1", "elf_fall_back")
        private val FLAKES = listOf("snow_flake1b", "snow_flake2b", "snow_flake3b")
        private val BIRD = listOf("bird1", "bird2", "bird3")
        private val BIRD_FROG = listOf("bird_frog1", "bird_frog2", "bird_frog3")
        private val DEER = listOf("deer1", "deer2", "deer3")
        private val BALLS = listOf(
            80f to "ball120", 68f to "ball110", 56f to "ball100", 48f to "ball90", 42f to "ball80",
            38f to "ball70", 32f to "ball60", 28f to "ball50", 24f to "ball40", 22f to "ball30",
            20f to "ball20", 18f to "ball10", 16f to "ball00", 14f to "ballm10",
        )

        /** The pin board's lamps, from the original's layout table: back row right to left first. */
        private val PIN_MARKERS = listOf(
            352f to 410f, 331f to 410f, 310f to 410f, 289f to 410f,
            341f to 430f, 320f to 430f, 299f to 430f,
            330f to 449f, 309f to 449f,
            318f to 469f,
        )
    }
}
