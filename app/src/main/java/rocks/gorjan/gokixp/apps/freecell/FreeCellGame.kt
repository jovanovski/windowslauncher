package rocks.gorjan.gokixp.apps.freecell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.cards.CardArt
import rocks.gorjan.gokixp.apps.cards.CardHost
import rocks.gorjan.gokixp.apps.cards.CardModals
import rocks.gorjan.gokixp.apps.cards.tween
import rocks.gorjan.gokixp.winui.WinUi
import rocks.gorjan.gokixp.winui.checkBox
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FreeCell. Jim Horne's game: his rules, his king, and the deals behind every game number from
 * 1 to 32000.
 *
 * A port of winos's `FreeCell` object (js/074-freecell.js), rules and arithmetic unchanged. What
 * changed is input: there is no right button to hold, so holding a finger on a card shows the
 * card hidden under the others, and there is no keyboard, so the Ctrl+Shift+F10 cheat lives on a
 * long press of the king instead.
 */
class FreeCellGame(private val context: Context, private val host: CardHost) {

    data class Card(val s: Int, val r: Int)

    /** A place on the table: a top cell (0-3 free, 4-7 home), or a column and a card in it (pos -1 off its cards). */
    data class At(val top: Int = -1, val col: Int = -1, val pos: Int = -1) {
        val isTop get() = top >= 0
        val isCol get() = col >= 0
    }

    data class Move(val f: At, val t: At)

    /** The cards, as the game has them or as a move is worked out on a copy of them. */
    class Board(
        val top: Array<Card?> = arrayOfNulls(8),
        val cols: Array<MutableList<Card>> = Array(8) { mutableListOf() },
        val home: IntArray = IntArray(4) { -1 },
        val homesuit: IntArray = IntArray(4) { -1 },
    ) {
        val moves = mutableListOf<Move>()
        fun copy() = Board(top.copyOf(), Array(8) { cols[it].toMutableList() }, home.copyOf(), homesuit.copyOf())
    }

    private enum class Valid { NO, YES, ASK }

    private class Options(var messages: Boolean, var quick: Boolean, var dbl: Boolean)
    private class Flying(val c: Card, var x: Float, var y: Float)
    private class Drag(val from: At, val cards: Int, var x: Float, var y: Float, val dx: Float, val dy: Float)
    private class Tap(val at: Long, val col: Int, val x: Float, val y: Float, var selected: Boolean)
    private class Down(val x: Float, val y: Float, val t: At)

    private val ui = WinUi(context)
    private val modals = CardModals(host, ui)
    private val art = CardArt(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val kings: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.freecell_kings)

    // ---------------------------------------------------------------- state

    private var b = Board()
    private var num = 0
    private var oldNum = 0
    private var sel: At? = null
    private var undo: List<Move> = emptyList()
    private var inProgress = false
    private var restartable = false
    private var wonState = false
    private var selecting = false
    private var busy = false
    private var cheat = 0
    private var king = KING_RIGHT
    private var dead = false
    private val opts = Options(
        prefs.getBoolean("messages", true), prefs.getBoolean("quick", false), prefs.getBoolean("dbl", true)
    )
    private var session = intArrayOf(0, 0)

    private var flying: Flying? = null
    private var drag: Drag? = null
    private var reveal: At? = null
    private var tap: Tap? = null
    private var down: Down? = null
    private var longPress: Runnable? = null
    private var cheatPress: Runnable? = null

    private val table = Table()
    private val cardsLeftLabel = ui.label("").apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(GREEN)
        val bar = ui.menuBar(
            listOf(WinUi.Menu("&Game") { gameMenu() }, WinUi.Menu("&Help") { helpMenu() }),
            onSound = null
        )
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(cardsLeftLabel)
        bar.gravity = Gravity.CENTER_VERTICAL
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(table, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init {
        update()
    }

    private fun gameMenu() = listOf(
        ContextMenuItem("New Game", shortcut = "F2", action = { newGame(NEW) }),
        ContextMenuItem("Select Game", shortcut = "F3", action = { newGame(SELECT) }),
        ContextMenuItem("Restart Game", isEnabled = restartable, action = { newGame(RESTART) }),
        ContextMenuItem.separator(),
        ContextMenuItem("Statistics...", shortcut = "F4", action = { statsDialog() }),
        ContextMenuItem("Options...", shortcut = "F5", action = { options() }),
        ContextMenuItem.separator(),
        ContextMenuItem("Undo", shortcut = "F10", isEnabled = undo.isNotEmpty(), action = { undo() }),
        ContextMenuItem.separator(),
        ContextMenuItem("Exit", action = { host.closeWindow() }),
    )

    private fun helpMenu() = listOf(
        ContextMenuItem("About FreeCell...", action = {
            modals.message("About FreeCell", "FreeCell\nby Jim Horne\n\nGame numbers 1 to 32000 deal the same cards they always have.")
        }),
    )

    /** The window is going: a game still being played counts as resigned, as it would after Yes. */
    fun cleanup() {
        if (inProgress) lost()
        dead = true
        scope.cancel()
        longPress?.let(handler::removeCallbacks)
        cheatPress?.let(handler::removeCallbacks)
        modals.closeAll()
    }

    // ---------------------------------------------------------------- the deal

    /** Microsoft's C library rand(), seeded with the game number. Games -1 and -2 are the hidden special deals. */
    private fun deal(n: Int): Array<MutableList<Card>> {
        val cols = Array(8) { MutableList<Card?>(8) { null } }
        fun card(i: Int) = Card(i % 4, i / 4 + 1)
        if (n == -1) {
            var i = 0
            for (pos in 0 until 7) { for (c in 0 until 4) cols[c][pos] = card(i++); i += 4 }
            for (pos in 0 until 6) { i -= 12; for (c in 4 until 8) cols[c][pos] = card(i++) }
            return Array(8) { c -> cols[c].filterNotNull().toMutableList() }
        }
        if (n == -2) {
            var i = 3
            for (c in 0 until 4) cols[c][0] = card(i--)
            i = 51
            for (pos in 1 until 7) for (c in 0 until 4) cols[c][pos] = card(i--)
            for (pos in 0 until 6) for (c in 4 until 8) cols[c][pos] = card(i--)
            return Array(8) { c -> cols[c].filterNotNull().toMutableList() }
        }
        val out = Array(8) { mutableListOf<Card>() }
        var seed = n.toLong()
        fun rand(): Int {
            seed = (seed * 214013 + 2531011) and 0x7fffffff
            return (seed shr 16).toInt()
        }
        val deck = IntArray(52) { it }
        var left = 52
        for (i in 0 until 52) {
            val j = rand() % left--
            out[i % 8].add(card(deck[j]))
            deck[j] = deck[left]
        }
        return out
    }

    // ---------------------------------------------------------------- rules

    private fun red(c: Card) = c.s == 1 || c.s == 2
    private fun fits(f: Card?, t: Card?) = f != null && t != null && t.r - f.r == 1 && red(f) != red(t)
    private fun last(s: Board, col: Int) = s.cols[col].lastOrNull()
    private fun freeCells(s: Board) = (0..3).count { s.top[it] == null }
    private fun cardsLeft(s: Board) = s.cols.sumOf { it.size } + (0..3).count { s.top[it] != null }
    private fun cardAt(s: Board, at: At) = if (at.isTop) s.top[at.top] else last(s, at.col)

    /** Which "column" a place is in the original's terms: 0 is the whole top row. */
    private fun colOf(at: At) = if (at.isTop) 0 else at.col + 1

    /** (free cells + 1) for every empty column, plus one: the original's arithmetic, not the textbook's doubling. */
    private fun maxTransfer(s: Board): Int {
        val cols = s.cols.count { it.isEmpty() }
        return (freeCells(s) + 1) * (cols + 1)
    }

    /** How many cards moving between columns takes, or 0 if it can't be done. */
    private fun toTransfer(s: Board, f: Int, t: Int): Int {
        if (f == t) return 1
        val src = s.cols[f]
        if (src.isEmpty()) return 0
        var pos = src.size - 1
        var n = 0
        if (s.cols[t].isEmpty()) {
            while (pos > 0 && fits(src[pos], src[pos - 1])) { pos--; n++ }
            return n + 1
        }
        val tcard = last(s, t)
        while (true) {
            n++
            if (fits(src[pos], tcard)) return n
            if (pos == 0 || !fits(src[pos], src[pos - 1])) return 0
            pos--
        }
    }

    /** A single-card move to or from the top row, or any move to an empty column. ASK when a whole column could go. */
    private fun isValid(s: Board, from: At, to: At): Valid {
        fun yes(ok: Boolean) = if (ok) Valid.YES else Valid.NO
        if (from.isTop && to.top == from.top) return Valid.YES
        val fcard = cardAt(s, from) ?: return Valid.NO
        if (from.isCol && to.isCol && s.cols[to.col].isEmpty()) {
            var n = toTransfer(s, from.col, to.col)
            if (freeCells(s) == 0 && n > 1) n = 1
            return if (n == 1) Valid.YES else if (n != 0) Valid.ASK else Valid.NO
        }
        if (to.isTop) {
            val tcard = s.top[to.top]
            if (to.top < 4) return yes(tcard == null)
            if (fcard.r == 1) return yes(tcard == null)
            return yes(tcard != null && tcard.s == fcard.s && fcard.r == tcard.r + 1)
        }
        return yes(s.cols[to.col].isEmpty() || fits(fcard, last(s, to.col)))
    }

    /** A card no card still in play could go on, so it can go home by itself. */
    private fun useless(s: Board, c: Card?): Boolean {
        if (c == null) return false
        if (cheat == CHEAT_WIN) return true
        if (c.r == 1) return true
        if (c.r == 2) return s.home[c.s] == 0
        val limit = c.r - 2
        if (s.home[c.s] != limit) return false
        val (a, bb) = if (red(c)) 0 to 3 else 1 to 2
        return s.home[a] >= limit && s.home[bb] >= limit
    }

    // ---------------------------------------------------------------- moves: worked out on a copy, then played back card by card

    private fun take(s: Board, f: At, t: At?): Card? {
        if (f.isTop) {
            if (f.top > 3 || s.top[f.top] == null || (t != null && t.top == f.top)) return null
            val c = s.top[f.top]
            s.top[f.top] = null
            return c
        }
        if (s.cols[f.col].isEmpty() || (t != null && t.col == f.col)) return null
        return s.cols[f.col].removeAt(s.cols[f.col].size - 1)
    }

    private fun put(s: Board, t: At, c: Card) {
        if (!t.isTop) { s.cols[t.col].add(c); return }
        s.top[t.top] = c
        if (t.top > 3) {
            s.home[c.s] = c.r - 1
            if (c.r == 1) s.homesuit[c.s] = t.top
        }
    }

    private fun q(s: Board, f: At, t: At) {
        s.moves.add(Move(f, t))
        take(s, f, t)?.let { put(s, t, it) }
    }

    private fun homeFor(s: Board, c: Card): Int {
        if (s.homesuit[c.s] < 0) {
            var i = 4
            while (s.top[i] != null) i++
            s.homesuit[c.s] = i
        }
        return s.homesuit[c.s]
    }

    private fun moveCol(s: Board, f: Int, t: Int, count: Int = 0) {
        val free = (0..3).filter { s.top[it] == null }
        var n = if (count > 0) count else toTransfer(s, f, t)
        n = min(n, free.size + 1) - 1
        for (i in 0 until n) q(s, At(col = f), At(top = free[i]))
        q(s, At(col = f), At(col = t))
        for (i in n - 1 downTo 0) q(s, At(top = free[i]), At(col = t))
    }

    /** More cards than the free cells hold go through the empty columns. */
    private fun multiMove(s: Board, f: Int, t: Int) {
        val free = freeCells(s)
        var n = toTransfer(s, f, t)
        if (n <= free + 1) return moveCol(s, f, t)
        val empty = (0..7).filter { s.cols[it].isEmpty() }
        var i = 0
        while (n > free + 1) { moveCol(s, f, empty[i]); n -= free + 1; i++ }
        moveCol(s, f, t)
        i--
        while (i >= 0) { moveCol(s, empty[i], t); i-- }
    }

    private fun cleanupHome(s: Board) {
        var more = true
        while (more) {
            more = false
            for (i in 0 until 4) {
                val c = s.top[i]
                if (useless(s, c)) { more = true; q(s, At(top = i), At(top = homeFor(s, c!!))) }
            }
            for (col in 0 until 8) {
                val c = last(s, col)
                if (useless(s, c)) { more = true; q(s, At(col = col), At(top = homeFor(s, c!!))) }
            }
        }
    }

    /** The moves the player asked for, then the cards that go home by themselves; a drag's own moves land instantly. */
    private fun commit(instant: Boolean = false, fn: (Board) -> Unit) {
        val s = b.copy()
        fn(s)
        val mine = s.moves.size
        cleanupHome(s)
        sel = null
        playMoves(s.moves.toList(), if (instant) mine else 0)
    }

    private fun playMoves(moves: List<Move>, instant: Int) {
        busy = true
        update()
        scope.launch {
            for (i in moves.indices) {
                if (dead) return@launch
                val (f, t) = moves[i]
                val c = take(b, f, t) ?: continue
                if (i >= instant) glide(c, f, t)
                put(b, t, c)
                if (t.isTop) king = if (t.top < 4) KING_LEFT else KING_RIGHT
                update()
            }
            if (dead) return@launch
            busy = false
            undo = if (moves.size > 1 || (moves.isNotEmpty() && colOf(moves[0].f) != colOf(moves[0].t))) moves else emptyList()
            update()
            if (num != 0 && cardsLeft(b) == 0) won()
            else if (num != 0) checkLost()
        }
    }

    /** Where a card sits, or where the next card in a column will go. */
    private fun place(at: At, next: Boolean = false): Pair<Float, Float> {
        val l = table.l!!
        if (at.isTop) return l.topX(at.top) to 0f
        val n = b.cols[at.col].size
        val ys = colYs(at.col, if (next) n + 1 else n)
        return l.colX(at.col) to (ys.getOrNull(if (next) n else n - 1) ?: l.colY)
    }

    private suspend fun glide(c: Card, f: At, t: At) {
        if (opts.quick || table.l == null) return
        // The card has already left its column; measure it as if it were still there
        var a = place(f)
        if (f.isCol) {
            b.cols[f.col].add(c)
            a = place(f)
            b.cols[f.col].removeAt(b.cols[f.col].size - 1)
        }
        val e = place(t, true)
        val dist = hypot(e.first - a.first, e.second - a.second) / density
        val fly = Flying(c, a.first, a.second)
        flying = fly
        tween(max(40f, dist / 2.4f)) { k ->
            fly.x = a.first + (e.first - a.first) * k
            fly.y = a.second + (e.second - a.second) * k
            table.invalidate()
        }
        flying = null
    }

    /** Undo takes back the last move, including the cards that went home after it. */
    private fun undo() {
        if (undo.isEmpty() || busy || num == 0) return
        val moves = undo
        undo = emptyList()
        busy = true
        scope.launch {
            for (i in moves.indices.reversed()) {
                if (dead) return@launch
                val to = moves[i].f
                val from = moves[i].t
                if (from.isCol && from.col == to.col) break
                val c: Card
                if (from.isTop && from.top > 3) {
                    c = b.top[from.top] ?: continue
                    b.home[c.s]--
                    b.top[from.top] = if (c.r == 1) null else Card(c.s, c.r - 1)
                    if (c.r == 1) b.homesuit[c.s] = -1
                } else {
                    c = take(b, from, null) ?: continue
                }
                glide(c, from, to)
                if (to.isTop) b.top[to.top] = c else b.cols[to.col].add(c)
                update()
            }
            busy = false
            update()
        }
    }

    // ---------------------------------------------------------------- taps

    /** What a point on the table refers to: a top cell, or a column and the card in it. */
    private fun hit(x: Float, y: Float): At? {
        val l = table.l ?: return null
        if (y < l.h) {
            if (x < 4 * l.w) return At(top = floor(x / l.w).toInt())
            if (x >= l.topX(4) && x < l.topX(4) + 4 * l.w) return At(top = 4 + min(3, floor((x - l.topX(4)) / l.w).toInt()))
            return null
        }
        if (y < l.colY || x < l.colX(0)) return null
        val col = min(7, floor((x - l.colX(0)) / (l.colX(1) - l.colX(0))).toInt())
        val p = b.cols[col]
        val ys = colYs(col)
        var pos = -1
        // A finger is wider than the gap between columns, so the gap counts as the column
        for (j in p.size - 1 downTo 0) {
            if (y >= ys[j] && y < (if (j == p.size - 1) ys[j] + l.h else ys[j + 1])) { pos = j; break }
        }
        return At(col = col, pos = pos)
    }

    private fun blocked() = num == 0 || busy || modals.busy

    /** The first tap picks a card up; selecting clears Undo, like on 98. */
    private fun select(t: At?) {
        undo = emptyList()
        sel = null
        if (t != null && t.isTop && t.top < 4 && b.top[t.top] != null) { sel = At(top = t.top); king = KING_LEFT }
        else if (t != null && t.isCol && t.pos >= 0) sel = At(col = t.col)
        update()
    }

    /** The message is modal: the card is let go only once it's dismissed. */
    private fun illegal(text: String, then: (() -> Unit)?) {
        modals.message("FreeCell", text) { then?.invoke() }
    }

    private fun tooMany(n: Int, max: Int) = "That move requires moving $n cards. You only have enough free space to move $max."

    /** The second tap says where it goes. */
    private fun request(t: At?) {
        val from = sel ?: return
        var to = if (t != null && (t.isCol || t.isTop)) t else from
        val deselect = { commit { q(it, from, from) } }
        if (from.isCol && to.isCol && b.cols[to.col].isNotEmpty()) {
            val n = toTransfer(b, from.col, to.col)
            val max = maxTransfer(b)
            if (n != 0 && n <= max) return commit { multiMove(it, from.col, to.col) }
            if (!opts.messages) return
            return illegal(if (n != 0) tooMany(n, max) else "That move is not allowed.", deselect)
        }
        if (to.isTop && to.top > 3 && isValid(b, from, to) == Valid.NO) {
            (4..7).firstOrNull { isValid(b, from, At(top = it)) != Valid.NO }?.let { to = At(top = it) }
        }
        val dest = to
        when (isValid(b, from, dest)) {
            Valid.ASK -> askColumn { choice ->
                when (choice) {
                    "column" -> commit { moveCol(it, from.col, dest.col) }
                    "single" -> commit { q(it, from, dest) }
                    else -> deselect()
                }
            }
            Valid.YES -> commit { q(it, from, dest) }
            Valid.NO -> if (opts.messages) illegal("That move is not allowed.", deselect)
        }
    }

    private fun askColumn(done: (String) -> Unit) {
        var choice = "cancel"
        var close: () -> Unit = {}
        fun pick(c: String) = { choice = c; close() }
        val content = modals.panel().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.button("Move column", 136, onClick = pick("column")))
            addView(ui.button("Move single card", 136, onClick = pick("single")).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6)
            })
            addView(ui.button("Cancel", 64, onClick = pick("cancel")).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(14)
            })
        }
        close = modals.show("Move to Empty Column...", content, 210) { done(choice) }
    }

    /** Double tap: the bottom card of a column goes to the leftmost free cell. */
    private fun toFreeCell(): Boolean {
        val cell = (0..3).firstOrNull { b.top[it] == null }
        val from = sel
        if (cell == null || from == null || !from.isCol) return false
        commit { q(it, from, At(top = cell)) }
        return true
    }

    /** A drag says how many cards it carries, so it needs no "Move to Empty Column" question. */
    private fun drop(d: Drag, t: At?) {
        val from = d.from
        val n = d.cards
        sel = null
        val refuse = { text: String ->
            if (opts.messages) illegal(text, null)
            update()
        }
        if (t == null || (!t.isCol && !t.isTop) || (from.isCol && t.col == from.col) || (from.isTop && t.top == from.top)) return update()
        if (from.isCol && t.isCol) {
            if (b.cols[t.col].isNotEmpty()) {
                val need = toTransfer(b, from.col, t.col)
                val max = maxTransfer(b)
                if (need != n) return refuse("That move is not allowed.")
                if (need > max) return refuse(tooMany(need, max))
                return commit(true) { multiMove(it, from.col, t.col) }
            }
            val max = freeCells(b) + 1
            if (n > max) return refuse(tooMany(n, max))
            return commit(true) { moveCol(it, from.col, t.col, n) }
        }
        if (n != 1) return refuse("That move is not allowed.")
        var dest: At = t
        if (t.isTop && t.top > 3 && isValid(b, from, t) == Valid.NO) {
            (4..7).firstOrNull { isValid(b, from, At(top = it)) != Valid.NO }?.let { dest = At(top = it) }
        }
        val to = dest
        if (isValid(b, from, to) != Valid.YES) return refuse("That move is not allowed.")
        commit(true) { q(it, from, to) }
    }

    /** A drag may pick up from any card that heads an ordered run to the bottom of its column. */
    private fun grab(t: At): Int {
        if (t.isTop) return 1
        val p = b.cols[t.col]
        var start = p.size - 1
        while (start > 0 && fits(p[start], p[start - 1])) start--
        return p.size - max(t.pos, start)
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                table.parent?.requestDisallowInterceptTouchEvent(true)
                val l = table.l ?: return true
                // Holding the king is the cheat's way in, now there is no Ctrl+Shift+F10 to press
                val k = l.king
                if (num != 0 && !modals.busy && y < l.h && x >= k.x && x < k.x + k.k) {
                    cheatPress = Runnable { cheatDialog() }.also { handler.postDelayed(it, 1200) }
                }
                if (blocked()) return true
                val t = hit(x, y)
                val now = SystemClock.uptimeMillis()
                val prev = tap
                val newTap = Tap(now, t?.col ?: -1, x, y, false)
                tap = newTap
                if (sel == null) {
                    select(t)
                    if (sel == null) return true
                    newTap.selected = true
                    down = Down(x, y, t!!)
                    if (t.isCol) longPress = Runnable {
                        if (down != null && drag == null) { reveal = t; table.invalidate() }
                    }.also { handler.postDelayed(it, 550) }
                    return true
                }
                val dbl = prev != null && prev.selected && now - prev.at < 500 && t != null && t.isCol &&
                    t.col == prev.col && hypot(x - prev.x, y - prev.y) < 24 * density
                if (dbl && opts.dbl && sel?.col == t!!.col && toFreeCell()) return true
                request(t)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = down ?: return true
                val l = table.l ?: return true
                if (drag == null && hypot(x - d.x, y - d.y) > 10 * density && sel != null && reveal == null) {
                    longPress?.let(handler::removeCallbacks)
                    cheatPress?.let(handler::removeCallbacks)
                    val from = sel!!
                    val n = grab(d.t)
                    val at = if (from.isTop) place(from) else l.colX(from.col) to colYs(from.col)[b.cols[from.col].size - n]
                    drag = Drag(from, n, at.first, at.second, d.x - at.first, d.y - at.second)
                    sel = null
                }
                drag?.let {
                    it.x = x - it.dx
                    it.y = y - it.dy
                    table.invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cheatPress?.let(handler::removeCallbacks)
                if (reveal != null) { reveal = null; table.invalidate() }
                down ?: return true
                longPress?.let(handler::removeCallbacks)
                down = null
                val dr = drag ?: return true
                drag = null
                val l = table.l ?: return true
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { update(); return true }
                drop(dr, hit(dr.x + l.w / 2, dr.y + l.h / 2))
            }
        }
        return true
    }

    private fun cheatDialog() {
        if (modals.busy || dead) return
        modals.message(
            "User-Friendly User Interface", "Choose Abort to Win,\nRetry to Lose,\nor Ignore to Cancel.",
            listOf("Abort", "Retry", "Ignore")
        ) { r -> cheat = when (r) { "Abort" -> CHEAT_WIN; "Retry" -> CHEAT_LOSE; else -> 0 } }
    }

    // ---------------------------------------------------------------- games, won and lost

    private fun resign(then: () -> Unit) {
        modals.message("FreeCell", "Do you want to resign this game?", listOf("Yes", "No")) { r ->
            if (r != "Yes") return@message
            lost()
            inProgress = false
            then()
        }
    }

    private fun newGame(kind: Int) {
        if (busy || modals.busy || dead) return
        if (kind == RESTART && !restartable) return
        val was = inProgress
        val go = {
            sel = null
            undo = emptyList()
            if (kind == RESTART) start(if (was) num else oldNum)
            else {
                selecting = kind == SELECT
                val n = 1 + Random.nextInt(32000)
                if (kind == NEW) start(n) else selectDialog(n)
            }
        }
        if (was) resign(go) else go()
    }

    private fun selectDialog(n: Int) {
        val input = ui.field(numeric = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(n.toString())
            selectAll()
            gravity = Gravity.CENTER
        }
        var close: () -> Unit = {}
        val ok = {
            val v = input.text.toString().trim()
            val chosen = v.toIntOrNull() ?: 0
            if (chosen < -2 || chosen > 32000 || chosen == 0) input.selectAll()
            else { close(); start(chosen) }
        }
        val content = modals.panel().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.label("Select a game number").apply { gravity = Gravity.CENTER })
            addView(ui.label("from 1 to 32000").apply { gravity = Gravity.CENTER })
            addView(input, LinearLayout.LayoutParams(ui.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
            addView(modals.buttonRow(ui.button("OK", onClick = ok)))
        }
        close = modals.show("Game Number", content, 220)
        input.requestFocus()
    }

    private fun start(n: Int) {
        b = Board(cols = deal(n))
        num = n
        sel = null
        undo = emptyList()
        inProgress = true
        restartable = true
        wonState = false
        king = KING_RIGHT
        host.setTitle("FreeCell Game #$n")
        update()
    }

    /** Won, lost, the record streaks and the current one, kept like the registry keeps them. */
    private class Stats(var won: Int, var lost: Int, var wins: Int, var losses: Int, var streak: Int, var stype: String)

    private fun record(clear: Boolean = false): Stats {
        if (clear) {
            prefs.edit { remove("won"); remove("lost"); remove("wins"); remove("losses"); remove("streak"); remove("stype") }
            session = intArrayOf(0, 0)
        }
        return Stats(
            prefs.getInt("won", 0), prefs.getInt("lost", 0), prefs.getInt("wins", 0), prefs.getInt("losses", 0),
            prefs.getInt("streak", 0), prefs.getString("stype", "won") ?: "won"
        )
    }

    private fun save(s: Stats) = prefs.edit {
        putInt("won", s.won); putInt("lost", s.lost); putInt("wins", s.wins); putInt("losses", s.losses)
        putInt("streak", s.streak); putString("stype", s.stype)
    }

    /** Percentages are rounded, but never up to 100 while there's a loss. */
    private fun pct(won: Int, lost: Int): Int {
        if (won == 0 && lost == 0) return 0
        val p = (won * 200 + won + lost) / (2 * (won + lost))
        return if (p >= 100 && lost > 0) 99 else p
    }

    private fun lost() {
        if (num > 0 && num != oldNum) {
            val s = record()
            s.lost++
            session[1]++
            s.streak = if (s.stype == "won") 1 else s.streak + 1
            s.stype = "lost"
            s.losses = max(s.losses, s.streak)
            save(s)
        }
        oldNum = num
    }

    private fun won() {
        undo = emptyList()
        inProgress = false
        cheat = 0
        if (num != oldNum) {
            val s = record()
            s.won++
            session[0]++
            s.streak = if (s.stype == "lost") 1 else s.streak + 1
            s.stype = "won"
            s.wins = max(s.wins, s.streak)
            save(s)
        }
        wonState = true
        oldNum = num
        num = 0
        update()
        var again = false
        var close: () -> Unit = {}
        val content = modals.panel().apply {
            addView(ui.label("Congratulations, you win!\nDo you want to play again?").apply { gravity = Gravity.CENTER })
            addView(ui.checkBox("Select game", selecting) { selecting = it }.apply { setPadding(0, ui.dp(10), 0, 0) })
            addView(modals.buttonRow(ui.button("Yes") { again = true; close() }, ui.button("No") { close() }))
        }
        close = modals.show("Game Over", content, 240) { if (again) newGame(if (selecting) SELECT else NEW) }
    }

    private fun checkLost() {
        if (cheat != CHEAT_LOSE) {
            if ((0..3).any { b.top[it] == null } || b.cols.any { it.isEmpty() }) return
            var moves = 0
            val bottoms = b.cols.map { it.last() }
            fun home(c: Card) = b.home[c.s] == c.r - 2
            bottoms.forEach { c -> if (c.r == 1) moves++; if (home(c)) moves++ }
            (0..3).forEach { i ->
                val c = b.top[i]!!
                if (home(c)) moves++
                bottoms.forEach { bt -> if (fits(c, bt)) moves++ }
            }
            bottoms.forEachIndexed { i, f -> bottoms.forEachIndexed { j, t -> if (i != j && fits(f, t)) moves++ } }
            if (moves > 0) return
        }
        undo = emptyList()
        inProgress = false
        cheat = 0
        lost()
        num = 0
        update()
        var same = true
        var again = false
        var close: () -> Unit = {}
        val content = modals.panel().apply {
            addView(ui.label("Sorry, you lose.  There are no more legal moves.\nDo you want to play again?"))
            addView(ui.checkBox("Same game", true) { same = it }.apply { setPadding(0, ui.dp(10), 0, 0) })
            addView(modals.buttonRow(ui.button("Yes") { again = true; close() }, ui.button("No") { close() }))
        }
        close = modals.show("Game Over", content, 270) {
            if (again) newGame(if (same) RESTART else if (selecting) SELECT else NEW)
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun statsDialog() {
        if (modals.busy) return
        val s = record()
        val streak = when {
            s.streak == 0 -> "0"
            s.streak == 1 -> if (s.stype == "won") "1 win" else "1 loss"
            else -> "${s.streak} ${if (s.stype == "won") "wins" else "losses"}"
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row(a: String, bb: String, c: String) = grid.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.label(a), LinearLayout.LayoutParams(ui.dp(if (a.isEmpty()) 22 else 120), ViewGroup.LayoutParams.WRAP_CONTENT))
            if (a.isEmpty()) addView(ui.label(bb), LinearLayout.LayoutParams(ui.dp(98), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ui.label(c))
        })
        fun gap() = grid.addView(View(context), LinearLayout.LayoutParams(1, ui.dp(10)))
        row("This session", "", "${pct(session[0], session[1])}%"); row("", "won:", "${session[0]}"); row("", "lost:", "${session[1]}")
        gap()
        row("Total", "", "${pct(s.won, s.lost)}%"); row("", "won:", "${s.won}"); row("", "lost:", "${s.lost}")
        gap()
        row("Streaks", "", ""); row("", "wins:", "${s.wins}"); row("", "losses:", "${s.losses}"); row("", "current:", streak)
        var close: () -> Unit = {}
        val content = modals.panel().apply {
            addView(grid)
            addView(modals.buttonRow(
                ui.button("OK") { close() },
                ui.button("Clear") {
                    modals.message("FreeCell", "Are you sure you want to delete all statistics?", listOf("Yes", "No")) { r ->
                        if (r == "Yes") { record(true); close() }
                    }
                }
            ))
        }
        close = modals.show("FreeCell Statistics", content, 260)
    }

    private fun options() {
        if (modals.busy) return
        var messages = opts.messages
        var quick = opts.quick
        var dbl = opts.dbl
        var close: () -> Unit = {}
        val checks = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.checkBox("Display messages on illegal moves", messages) { messages = it })
            addView(ui.checkBox("Quick play (no animation)", quick) { quick = it }.apply { setPadding(0, ui.dp(10), 0, 0) })
            addView(ui.checkBox("Double tap moves card to free cell", dbl) { dbl = it }.apply { setPadding(0, ui.dp(10), 0, 0) })
        }
        val content = modals.panel().apply {
            addView(modals.sideButtons(
                checks,
                ui.button("OK") {
                    opts.messages = messages; opts.quick = quick; opts.dbl = dbl
                    prefs.edit { putBoolean("messages", messages); putBoolean("quick", quick); putBoolean("dbl", dbl) }
                    close()
                },
                ui.button("Cancel") { close() }
            ))
        }
        close = modals.show("FreeCell Options", content, 340)
    }

    // ---------------------------------------------------------------- drawing

    private class King(val k: Float, val x: Float, val y: Float)

    private inner class Layout(val s: Float, val w: Float, val h: Float, val cw: Float, val ch: Float) {
        private val left = (cw - 8 * w) / 9
        fun colX(i: Int) = left + i * (cw - left) / 8
        fun topX(i: Int) = if (i < 4) i * w else cw - (8 - i) * w
        val colY = h + 10 * s * density
        val step = 18 * s * density
        val king: King = run {
            val k = min(32 * density, floor(cw - 8 * w - 10 * density))
            King(k, ((cw - k) / 2).roundToInt().toFloat(), ((h - k) / 3).roundToInt().toFloat())
        }
    }

    /** Columns too long for the window squeeze together rather than running off the bottom. */
    private fun colYs(i: Int, count: Int = b.cols[i].size): List<Float> {
        val l = table.l!!
        val room = l.ch - l.colY - l.h - 2 * density
        // A tall narrow window (a phone) spreads the cards out so more of each shows
        var step = max(l.step, min(26 * density, room / 14))
        if (count > 1 && (count - 1) * step > room) step = max(4 * l.s * density, room / (count - 1))
        return List(count) { l.colY + it * step }
    }

    private fun update() {
        cardsLeftLabel.text = "Cards Left: ${cardsLeft(b)}"
        table.invalidate()
    }

    @SuppressLint("ViewConstructor")
    private inner class Table : View(context) {
        var l: Layout? = null
        private val fill = Paint()
        private val pixel = Paint().apply { isFilterBitmap = false }
        private val src = Rect()
        private val dst = RectF()

        init {
            id = R.id.freecell_table
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val s = min(1f, w / (632 * density))
            l = Layout(s, 71 * s * density, 96 * s * density, w.toFloat(), h.toFloat())
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)

        private fun card(canvas: Canvas, c: Card, x: Float, y: Float, hilite: Boolean = false) {
            val l = l ?: return
            art.draw(canvas, c.s, c.r, true, x, y, l.w, l.h, hilite)
        }

        private fun rect(canvas: Canvas, color: Int, x: Float, y: Float, w: Float, h: Float) {
            fill.color = color
            canvas.drawRect(x, y, x + w, y + h, fill)
        }

        /** An empty cell: a black edge top and left, a bright green one right and bottom. */
        private fun ghost(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
            val px = max(1f, density.roundToInt().toFloat() / 2)
            rect(canvas, BLACK, x, y, px, h - px)
            rect(canvas, BLACK, x, y, w - px, px)
            rect(canvas, LIME, x + w - px, y + px, px, h - px)
            rect(canvas, LIME, x + px, y + h - px, w - px, px)
        }

        private fun sprite(canvas: Canvas, index: Int, x: Float, y: Float, size: Float) {
            val bmp = kings ?: return
            val cell = bmp.height
            src.set(index * cell, 0, index * cell + cell, cell)
            dst.set(x, y, x + size, y + size)
            canvas.drawBitmap(bmp, src, dst, pixel)
        }

        override fun onDraw(canvas: Canvas) {
            val l = l ?: return
            canvas.drawColor(GREEN)

            // The king in his box between the free cells and the home cells
            val k = l.king
            val u = k.k / 32
            if (k.k >= 12 * density) {
                val px = max(1f, density.roundToInt().toFloat() / 2)
                rect(canvas, LIME, k.x - 3 * u, k.y - 3 * u, px, 37 * u)
                rect(canvas, LIME, k.x - 3 * u, k.y - 3 * u, 37 * u, px)
                rect(canvas, BLACK, k.x + 34 * u, k.y - 2 * u, px, 37 * u)
                rect(canvas, BLACK, k.x - 2 * u, k.y + 34 * u, 37 * u, px)
                if (!wonState) sprite(canvas, if (king == KING_LEFT) 1 else 0, k.x, k.y, k.k)
            }

            fun hl(at: At): Boolean {
                val s = sel ?: return false
                return drag == null && if (at.isTop) s.top == at.top else s.col == at.col
            }
            for (i in 0 until 8) {
                val c = b.top[i]
                val x = l.topX(i)
                if (c != null) card(canvas, c, x, 0f, hl(At(top = i))) else ghost(canvas, x, 0f, l.w, l.h)
            }
            val d = drag
            val dragFrom = if (d != null && d.from.isCol) d.from.col else -1
            b.cols.forEachIndexed { i, p ->
                val ys = colYs(i)
                val n = p.size - if (i == dragFrom) d!!.cards else 0
                for (j in 0 until n) card(canvas, p[j], l.colX(i), ys[j], j == p.size - 1 && hl(At(col = i)))
            }
            reveal?.let { r ->
                b.cols.getOrNull(r.col)?.getOrNull(r.pos)?.let { card(canvas, it, l.colX(r.col), colYs(r.col)[r.pos]) }
            }
            if (d != null) {
                val cards = if (d.from.isTop) listOfNotNull(b.top[d.from.top]) else b.cols[d.from.col].takeLast(d.cards)
                if (d.from.isTop) ghost(canvas, l.topX(d.from.top), 0f, l.w, l.h)
                cards.forEachIndexed { j, c -> card(canvas, c, d.x, d.y + j * l.step) }
            }
            flying?.let { card(canvas, it.c, it.x, it.y) }

            // Winning: the big smiling king
            if (wonState) {
                val size = minOf(320 * density, l.cw - 20 * density, l.ch - l.h - 20 * density)
                if (size > 0) sprite(canvas, 2, 10 * density, l.h + 10 * density, size)
            }
        }
    }

    companion object {
        private const val PREFS = "FreeCellPrefs"
        private const val NEW = 0
        private const val SELECT = 1
        private const val RESTART = 2
        private const val CHEAT_WIN = 1
        private const val CHEAT_LOSE = 2
        private const val KING_RIGHT = 0
        private const val KING_LEFT = 1
        private const val GREEN = 0xFF008000.toInt()
        private const val LIME = 0xFF00FF00.toInt()
        private const val BLACK = 0xFF000000.toInt()
    }
}
