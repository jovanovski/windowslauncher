package rocks.gorjan.gokixp.apps.minesweeper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.WP81Palette
import kotlin.random.Random

/**
 * Minesweeper, as the phone would have had it.
 *
 * The desktop version is a window sized to its own grid, with a sunken field of grey
 * buttons, a smiley and two seven-segment counters - furniture from an operating system
 * that had a mouse and a title bar to hang it on. Here the field *is* the page: a wall of
 * accent squares filling the screen, the two numbers set in the accent above it, and every
 * command on the strip along the bottom.
 *
 * The squares are the same squares Start is made of, at the size a finger wants rather
 * than the size a mouse pointer allows, and what is under them is drawn in the phone's own
 * twenty accent colours - one per count - rather than in Windows' primaries. That is the
 * whole of the port: the game is the game, and what changes is what it is made of.
 *
 * Held rather than right-clicked, since a phone has no second button; and because holding
 * every square you are unsure of is slow, the flag can be latched on the strip instead -
 * see [flagButton].
 */
class MetroMinesweeperApp(
    private val context: Context,
    private val palette: WP81Palette
) {

    /** How large the field is, and how much of it is mined. */
    private enum class Level(val label: String, val size: Int, val mines: Int) {
        EASY("beginner", 9, 10),
        MEDIUM("intermediate", 12, 25),
        HARD("expert", 14, 45)
    }

    private class Cell {
        var mined = false
        var revealed = false
        var flagged = false
        var adjacent = 0
    }

    private var level = Level.EASY
    private var grid = newField(level)
    private var flags = 0
    private var seconds = 0

    /** Whether the field has been laid yet: the first tap is what decides where it goes. */
    private var laid = false
    private var over = false

    /** Which square ended it, so it can be shown as the one that did. */
    private var struckRow = -1
    private var struckCol = -1

    /** Whether a tap plants a flag rather than uncovering. */
    private var flagging = false

    private lateinit var board: BoardView
    private lateinit var minesLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var status: TextView
    private lateinit var bar: MetroAppBar
    private lateinit var flagButton: ImageView

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (over) return
            seconds++
            showTime()
            main.postDelayed(this, 1000L)
        }
    }

    fun createView(): View {
        val root = FrameLayout(context).apply { setBackgroundColor(palette.background) }
        // The strip is over the page rather than part of the column, so the list behind
        // its dots opens *across* the page instead of shortening it. The column keeps clear
        // of the strip's own height, which is the only part of it that is always there.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))
        }

        // The app's own name, set the way a panorama sets one: lowercase, light, and large
        // enough to be the first thing on the page rather than a label above it.
        column.addView(TextView(context).apply {
            text = "minesweeper"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
            textSize = TITLE_SP
            includeFontPadding = false
            setTextColor(palette.foreground)
            setPadding(dp(PAGE_MARGIN_DP), dp(18), dp(PAGE_MARGIN_DP), dp(2))
        }, wide())

        // What is left to find, and how long it has taken. Two numbers with a word each,
        // rather than the desktop's two counters: a phone has the width for words and the
        // digits alone said nothing about which was which.
        minesLabel = counter()
        timeLabel = counter()
        val counters = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(10))
        }
        counters.addView(counterBlock("mines", minesLabel), half())
        counters.addView(counterBlock("time", timeLabel), half())
        column.addView(counters, wide())

        board = BoardView(context)
        column.addView(board, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // How it ended, under the field rather than over it: a message drawn across the
        // squares would cover the one thing the user wants to look at when they lose.
        status = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(PAGE_MARGIN_DP), dp(4), dp(PAGE_MARGIN_DP), dp(10))
        }
        column.addView(status, wide())

        bar = MetroAppBar(context, palette)
        bar.addCommand(NEW_ICON) { deal(level) }
        flagButton = bar.addCommand(FLAG_ICON) {
            flagging = !flagging
            bar.setCommandOn(flagButton, flagging)
        }
        bar.menu = {
            // The level the user is already on is not offered: it is the one command on the
            // list that would do nothing, and a list of three where one is a no-op reads as
            // a list of three.
            Level.entries.filter { it != level }.map { next ->
                MetroAppBar.Item(next.label) { deal(next) }
            }
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        deal(level)
        return root
    }

    /** Back closes the strip's own list before it closes the game. */
    fun handleBack(): Boolean = bar.closeMenu()

    fun cleanup() {
        main.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------- the game

    private fun deal(next: Level) {
        level = next
        grid = newField(level)
        flags = 0
        seconds = 0
        laid = false
        over = false
        struckRow = -1
        struckCol = -1
        main.removeCallbacks(tick)
        showMines()
        showTime()
        status.text = ""
        board.invalidate()
    }

    /**
     * Lays the mines, once the first square has been asked for.
     *
     * Placed *around* that square rather than before it, so the opening tap can never be
     * the last one. Its neighbours are kept clear as well: an opening move that uncovers a
     * single number and nothing else is a game that starts with a guess.
     */
    private fun lay(row: Int, col: Int) {
        val size = level.size
        var placed = 0
        while (placed < level.mines) {
            val r = Random.nextInt(size)
            val c = Random.nextInt(size)
            if (grid[r][c].mined) continue
            if (kotlin.math.abs(r - row) <= 1 && kotlin.math.abs(c - col) <= 1) continue
            grid[r][c].mined = true
            placed++
        }
        for (r in 0 until size) {
            for (c in 0 until size) {
                grid[r][c].adjacent = neighbours(r, c).count { (nr, nc) -> grid[nr][nc].mined }
            }
        }
        laid = true
        main.postDelayed(tick, 1000L)
    }

    private fun neighbours(row: Int, col: Int): List<Pair<Int, Int>> {
        val size = level.size
        val found = mutableListOf<Pair<Int, Int>>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until size && c in 0 until size) found.add(r to c)
            }
        }
        return found
    }

    private fun tap(row: Int, col: Int) {
        if (over) return
        val cell = grid[row][col]
        if (flagging) {
            flag(row, col)
            return
        }
        if (cell.flagged) return
        if (!laid) lay(row, col)
        if (cell.revealed) {
            // A tap on a number that already has its flags is the fast way through a
            // cleared area - the same shortcut the desktop game gave to both buttons at
            // once, which is a gesture a phone does not have.
            clearAround(row, col)
            return
        }
        uncover(row, col)
        finishIfDone()
        board.invalidate()
    }

    private fun flag(row: Int, col: Int) {
        if (over) return
        val cell = grid[row][col]
        if (cell.revealed) return
        cell.flagged = !cell.flagged
        flags += if (cell.flagged) 1 else -1
        showMines()
        finishIfDone()
        board.invalidate()
    }

    /**
     * Uncovers a square, and everything that follows from it.
     *
     * A square with nothing next to it opens its neighbours, and theirs, until the run
     * reaches numbers on every side. Written as a list rather than as recursion: an empty
     * expert field is two hundred squares deep and the stack is not the place to keep them.
     */
    private fun uncover(row: Int, col: Int) {
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(row to col)
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            val cell = grid[r][c]
            if (cell.revealed || cell.flagged) continue
            cell.revealed = true
            if (cell.mined) {
                struckRow = r
                struckCol = c
                lose()
                return
            }
            if (cell.adjacent == 0) queue.addAll(neighbours(r, c))
        }
    }

    /** Opens what is left around a number that has as many flags beside it as it says. */
    private fun clearAround(row: Int, col: Int) {
        val cell = grid[row][col]
        if (cell.adjacent == 0) return
        val around = neighbours(row, col)
        if (around.count { (r, c) -> grid[r][c].flagged } != cell.adjacent) return
        for ((r, c) in around) {
            if (!grid[r][c].flagged && !grid[r][c].revealed) uncover(r, c)
            if (over) break
        }
        finishIfDone()
        board.invalidate()
    }

    private fun finishIfDone() {
        if (over) return
        val size = level.size
        val hidden = (0 until size).sumOf { r ->
            (0 until size).count { c -> !grid[r][c].revealed }
        }
        if (hidden != level.mines) return
        over = true
        main.removeCallbacks(tick)
        // Every mine is spoken for at the end, whether or not the user marked them: the
        // field is finished, and a won game showing unmarked mines looks unfinished.
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (grid[r][c].mined && !grid[r][c].flagged) {
                    grid[r][c].flagged = true
                    flags++
                }
            }
        }
        showMines()
        status.setTextColor(palette.accent)
        status.text = "cleared in $seconds seconds"
        Haptics.tap(board)
    }

    private fun lose() {
        over = true
        main.removeCallbacks(tick)
        for (row in grid) for (cell in row) if (cell.mined) cell.revealed = true
        status.setTextColor(MINE_RED)
        status.text = "mine"
        Haptics.tap(board)
        board.invalidate()
    }

    private fun showMines() {
        minesLabel.text = (level.mines - flags).coerceAtLeast(0).toString()
    }

    private fun showTime() {
        timeLabel.text = seconds.toString()
    }

    // ------------------------------------------------------------------- the field

    /**
     * The wall of squares.
     *
     * Drawn rather than built out of views: a fourteen-by-fourteen field is nearly two
     * hundred of them, all identical, all changing together - which is a canvas, not a
     * layout. The squares are sized from whichever of the two directions runs out first,
     * so the field is square on any screen and centred in what it is given.
     */
    @SuppressLint("ViewConstructor")
    private inner class BoardView(context: Context) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
            textAlign = Paint.Align.CENTER
        }
        private val flagPath = Path()
        private val square = RectF()

        private var cell = 0f
        private var originX = 0f
        private var originY = 0f

        private val gestures = GestureDetector(context, object :
            GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                at(e.x, e.y)?.let { (row, col) ->
                    Haptics.tap(this@BoardView)
                    tap(row, col)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                at(e.x, e.y)?.let { (row, col) ->
                    Haptics.tap(this@BoardView)
                    // A hold is the flag whichever way the strip is set: it is the gesture
                    // that means "not this one", and having it uncover while the latch was
                    // on would be the hold doing the opposite of what it always does.
                    if (flagging) tap(row, col) else flag(row, col)
                }
            }
        })

        init {
            isClickable = true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestures.onTouchEvent(event)
            return true
        }

        /** Which square a point is on, or null for the margin around the field. */
        private fun at(x: Float, y: Float): Pair<Int, Int>? {
            if (cell <= 0f) return null
            val col = ((x - originX) / cell).toInt()
            val row = ((y - originY) / cell).toInt()
            if (row !in 0 until level.size || col !in 0 until level.size) return null
            return row to col
        }

        override fun onDraw(canvas: Canvas) {
            val size = level.size
            val gap = dp(GAP_DP).toFloat()
            cell = minOf(width.toFloat() / size, height.toFloat() / size)
            if (cell <= 0f) return
            originX = (width - cell * size) / 2f
            originY = (height - cell * size) / 2f

            ink.textSize = cell * NUMBER_SHARE
            val baseline = -(ink.descent() + ink.ascent()) / 2f

            for (row in 0 until size) {
                for (col in 0 until size) {
                    val state = grid[row][col]
                    square.set(
                        originX + col * cell + gap / 2f,
                        originY + row * cell + gap / 2f,
                        originX + (col + 1) * cell - gap / 2f,
                        originY + (row + 1) * cell - gap / 2f
                    )
                    when {
                        // The one that ended it, and then the rest of them: a red square
                        // for the mine that was stepped on, the covered accent kept for the
                        // others so the two read differently at a glance.
                        state.revealed && state.mined && row == struckRow && col == struckCol -> {
                            fill.color = MINE_RED
                            canvas.drawRect(square, fill)
                            drawMine(canvas, Color.WHITE)
                        }
                        state.revealed && state.mined -> {
                            fill.color = palette.inactive
                            canvas.drawRect(square, fill)
                            drawMine(canvas, MINE_RED)
                        }
                        state.revealed -> {
                            fill.color = palette.inactive
                            canvas.drawRect(square, fill)
                            if (state.adjacent > 0) {
                                ink.color = COUNT_COLOURS[state.adjacent - 1]
                                canvas.drawText(
                                    state.adjacent.toString(),
                                    square.centerX(),
                                    square.centerY() + baseline,
                                    ink
                                )
                            }
                        }
                        else -> {
                            fill.color = palette.accent
                            canvas.drawRect(square, fill)
                            if (state.flagged) drawFlag(canvas)
                        }
                    }
                }
            }
        }

        /** A disc, which is what a mine is once it is not a cartoon of one. */
        private fun drawMine(canvas: Canvas, colour: Int) {
            fill.color = colour
            canvas.drawCircle(square.centerX(), square.centerY(), square.width() * MINE_SHARE, fill)
        }

        /** A pennant on a staff, in the ink the accent carries its own marks in. */
        private fun drawFlag(canvas: Canvas) {
            val h = square.height() * FLAG_SHARE
            val top = square.centerY() - h / 2f
            val left = square.centerX() - h / 3f
            fill.color = palette.onAccent()
            canvas.drawRect(
                left, top, left + dp(2).toFloat(), top + h, fill
            )
            flagPath.reset()
            flagPath.moveTo(left + dp(2).toFloat(), top)
            flagPath.lineTo(left + h * 0.75f, top + h * 0.25f)
            flagPath.lineTo(left + dp(2).toFloat(), top + h * 0.5f)
            flagPath.close()
            canvas.drawPath(flagPath, fill)
        }
    }

    // ------------------------------------------------------------------- furniture

    private fun newField(level: Level) = Array(level.size) { Array(level.size) { Cell() } }

    private fun counter(): TextView = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        textSize = COUNTER_SP
        includeFontPadding = false
        setTextColor(palette.accent)
    }

    /** A number with the word for what it counts under it, in the phone's own hierarchy. */
    private fun counterBlock(name: String, value: TextView): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(value, wide())
            addView(TextView(context).apply {
                text = name
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
                textSize = 13f
                setTextColor(palette.foregroundSubtle)
            }, wide())
        }

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun half() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private const val PAGE_MARGIN_DP = 22
        private const val TITLE_SP = 34f
        private const val COUNTER_SP = 30f

        /** Between one square and the next. The grid is squares, not a grid with lines. */
        private const val GAP_DP = 3

        /** How much of a square its number fills, and how much of one a mine does. */
        private const val NUMBER_SHARE = 0.5f
        private const val MINE_SHARE = 0.22f
        private const val FLAG_SHARE = 0.5f

        /**
         * One count, one colour, taken from the phone's own twenty.
         *
         * Windows drew these in primaries because it had eight to tell apart and a palette
         * of sixteen to do it with. These are the accents Windows Phone shipped - lime,
         * green, emerald, teal, cobalt, indigo, violet, magenta - which are the same eight
         * distinctions made in the colours this shell is already painted in.
         */
        private val COUNT_COLOURS = intArrayOf(
            0xFF1BA1E2.toInt(), // 1 - cyan
            0xFF60A917.toInt(), // 2 - green
            0xFFE51400.toInt(), // 3 - red
            0xFF6A00FF.toInt(), // 4 - indigo
            0xFFFA6800.toInt(), // 5 - orange
            0xFF00ABA9.toInt(), // 6 - teal
            0xFFD80073.toInt(), // 7 - magenta
            0xFFA0A0A0.toInt()  // 8 - grey
        )

        private val MINE_RED = 0xFFE51400.toInt()

        private const val ICON_DIR = "custom_icons_8"
        private const val NEW_ICON = "$ICON_DIR/appbar.refresh.svg"
        private const val FLAG_ICON = "$ICON_DIR/appbar.flag.svg"
    }
}
