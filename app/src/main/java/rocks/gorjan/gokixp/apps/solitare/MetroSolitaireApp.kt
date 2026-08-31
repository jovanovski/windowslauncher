package rocks.gorjan.gokixp.apps.solitare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Solitaire, as the phone would have had it.
 *
 * Klondike is Klondike - the same seven piles, the same four homes, the same deck the
 * desktop version deals from and the same card faces on it, down to the back you picked in
 * that one. What changes is everything around them. The green baize goes, because it was a
 * picture of a card table drawn for a screen pretending to be a desk; the board is the
 * page's own colour, the empty places are outlines in it, and the two numbers that say how
 * the game is going are set above the board in the accent, in the same hand as the rest of
 * the shell.
 *
 * Cards are dragged, as cards are. A tap is the shortcut rather than the whole of the
 * input: it sends a card wherever it can go, home first, which is what a tap on a card
 * nearly always means. Both are on the same touch - what tells them apart is whether the
 * finger travelled - so nobody has to know which one this game wanted.
 */
class MetroSolitaireApp(
    private val context: Context,
    private val palette: WP81Palette
) {

    private enum class Suit(val asset: String, val red: Boolean) {
        CLUB("club", false),
        DIAMOND("diamond", true),
        HEART("heart", true),
        SPADE("spade", false)
    }

    private class Card(val suit: Suit, val rank: Int, var faceUp: Boolean = false)

    private enum class Kind { STOCK, WASTE, FOUNDATION, TABLEAU }

    private class Pile(val kind: Kind, val index: Int) {
        val cards = mutableListOf<Card>()
        val top: Card? get() = cards.lastOrNull()

        /** Where the pile sits on the board, set on every layout pass. */
        var x = 0f
        var y = 0f
    }

    private val stock = Pile(Kind.STOCK, 0)
    private val waste = Pile(Kind.WASTE, 0)
    private val foundations = List(4) { Pile(Kind.FOUNDATION, it) }
    private val tableaus = List(7) { Pile(Kind.TABLEAU, it) }
    private val piles: List<Pile>
        get() = listOf(stock, waste) + foundations + tableaus

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** How many the stock turns at a time. Three is the game as Windows dealt it. */
    private var drawThree = true

    /** How much of the waste is fanned out, so a three-card turn shows all three. */
    private var wasteShowing = 0

    /** Which back the deck is dealt with, and which set of faces it is drawn from. */
    private var back = prefs.getInt(PREF_BACK, 1)
    private var vistaFaces = prefs.getBoolean(PREF_FACES, true)

    private var moves = 0
    private var seconds = 0
    private var running = false
    private var won = false

    /**
     * The board before each move, newest last.
     *
     * A whole copy rather than a description of what changed: fifty-two cards is nothing to
     * keep, and a move here can flip a card, empty a pile and complete a suit at once - a
     * list of those consequences is a second implementation of the rules, and the one that
     * would be wrong.
     */
    private val history = mutableListOf<List<List<Triple<Suit, Int, Boolean>>>>()

    // --- the card in hand ---------------------------------------------------------------
    // A dragged run is left where it is and drawn somewhere else rather than lifted out of
    // its pile: the rules only ever see a move that lands, so a drag that is given up on -
    // or a window that closes mid-gesture - cannot leave cards in the air.

    private var dragFrom: Pile? = null
    private var dragIndex = 0
    private var dragX = 0f
    private var dragY = 0f

    /** What the hint is pointing at, until it is taken or it expires. */
    private var hintFrom: Pile? = null
    private var hintIndex = -1
    private var hintTo: Pile? = null

    private lateinit var board: BoardView
    private lateinit var movesLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var status: TextView
    private lateinit var bar: MetroAppBar
    private lateinit var undoButton: ImageView

    private val faces = mutableMapOf<String, Drawable?>()

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            seconds++
            timeLabel.text = seconds.toString()
            main.postDelayed(this, 1000L)
        }
    }

    private val clearHint = Runnable {
        hintFrom = null
        hintTo = null
        hintIndex = -1
        if (!won) status.text = ""
        board.invalidate()
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

        column.addView(TextView(context).apply {
            text = "solitaire"
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
            textSize = TITLE_SP
            includeFontPadding = false
            setTextColor(palette.foreground)
            setPadding(dp(PAGE_MARGIN_DP), dp(14), dp(PAGE_MARGIN_DP), dp(2))
        }, wide())

        movesLabel = counter()
        timeLabel = counter()
        val counters = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(6))
        }
        val movesBlock = counterBlock("moves", movesLabel)
        counters.addView(movesBlock, LinearLayout.LayoutParams(WRAP, WRAP))
        counters.addView(counterBlock("time", timeLabel), LinearLayout.LayoutParams(0, WRAP, 1f))
        // The two numbers stand on the table's own columns rather than on halves of the
        // screen: the time begins where the four homes begin, so the pair reads as a
        // heading for the two halves of the board under them instead of as a row that
        // happens to be up there. Measured from the same rule the board is laid out by -
        // see [columnLeft] - so they cannot drift apart.
        counters.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val lead = (columnLeft(FOUNDATION_COLUMN, right - left) - dp(PAGE_MARGIN_DP)).toInt()
            val params = movesBlock.layoutParams as LinearLayout.LayoutParams
            if (lead > 0 && params.width != lead) {
                params.width = lead
                movesBlock.layoutParams = params
            }
        }
        column.addView(counters, wide())

        board = BoardView(context)
        column.addView(board, LinearLayout.LayoutParams(MATCH, 0, 1f))

        status = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            setPadding(dp(PAGE_MARGIN_DP), dp(2), dp(PAGE_MARGIN_DP), dp(8))
        }
        column.addView(status, wide())

        bar = MetroAppBar(context, palette)
        bar.addCommand(NEW_ICON) { deal() }
        undoButton = bar.addCommand(UNDO_ICON) { undo() }
        bar.addCommand(HINT_ICON) { hint() }
        bar.menu = {
            listOf(
                MetroAppBar.Item(if (drawThree) "turn one" else "turn three") {
                    drawThree = !drawThree
                    deal()
                },
                MetroAppBar.Item("change deck") { changeDeck() },
                MetroAppBar.Item(if (vistaFaces) "classic cards" else "modern cards") {
                    changeFaces()
                },
                MetroAppBar.Item("send home") { sendHome() }
            )
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        deal()
        return root
    }

    /** Back closes the strip's own list before it closes the game. */
    fun handleBack(): Boolean = bar.closeMenu()

    fun cleanup() {
        running = false
        main.removeCallbacks(tick)
        main.removeCallbacks(clearHint)
    }

    // ------------------------------------------------------------------- dealing

    private fun deal() {
        for (pile in piles) pile.cards.clear()
        history.clear()
        clearDrag()
        moves = 0
        seconds = 0
        won = false
        wasteShowing = 0
        status.text = ""
        main.removeCallbacks(clearHint)
        clearHint.run()

        val deck = mutableListOf<Card>()
        for (suit in Suit.entries) for (rank in 1..13) deck.add(Card(suit, rank))
        deck.shuffle()

        // Seven piles, one card longer each, the last of each turned over. What is left is
        // the stock, face down, which is the whole of the deal.
        for (column in 0 until 7) {
            for (row in 0..column) {
                val card = deck.removeLast()
                card.faceUp = row == column
                tableaus[column].cards.add(card)
            }
        }
        for (card in deck) card.faceUp = false
        stock.cards.addAll(deck)

        // The clock waits for the player. A deal that has not been touched is not a game
        // being played badly, and a timer that starts while the cards are still being
        // looked at counts the looking.
        running = false
        main.removeCallbacks(tick)
        showCounters()
        board.invalidate()
    }

    /** The next back in the pack. Written where the desktop game keeps it: same deck, both. */
    private fun changeDeck() {
        back = back % BACKS + 1
        prefs.edit { putInt(PREF_BACK, back) }
        board.invalidate()
    }

    /**
     * Swaps the faces between the two sets the launcher carries.
     *
     * The desktop game picks between them by chrome - Vista windows get the drawn set, the
     * older ones the flat set - which is a rule about what the window looks like and has
     * nothing to say here, where there is no window. So it is offered as what it actually
     * is: a choice of cards.
     */
    private fun changeFaces() {
        vistaFaces = !vistaFaces
        prefs.edit { putBoolean(PREF_FACES, vistaFaces) }
        board.invalidate()
    }

    // ------------------------------------------------------------------- moves

    /**
     * What a tap on a card means.
     *
     * Home if it can go home, and across if it cannot: a card is tapped because the player
     * has seen somewhere for it to be, and in a game where four of the twelve places are
     * the piles at the top, trying those first is right more often than it is wrong. A run
     * of cards can only go across, since a foundation takes them one at a time.
     */
    private fun play(pile: Pile, index: Int) {
        if (won) return
        val card = pile.cards.getOrNull(index) ?: return
        if (!card.faceUp) {
            // The only face-down card that answers a tap is the one on top of its pile,
            // and what it does is turn over.
            if (pile.kind == Kind.TABLEAU && index == pile.cards.lastIndex) {
                remember()
                card.faceUp = true
                counted()
            }
            return
        }

        val run = pile.cards.subList(index, pile.cards.size).toList()
        if (run.size == 1) {
            val home = foundations.firstOrNull { canGoHome(card, it) }
            if (home != null) {
                remember()
                move(pile, home, 1)
                counted()
                return
            }
        }
        // A run is only a run if it is ordered; the middle of a fan that is not is a card
        // nobody can pick up.
        if (!ordered(run)) return
        // A pile with something on it before an empty one: sending a king across to bare
        // felt when it could have gone onto a queen is the move nobody means by a tap.
        val across = tableaus.firstOrNull { it !== pile && it.cards.isNotEmpty() && canGoAcross(run.first(), it) }
            ?: tableaus.firstOrNull { it !== pile && canGoAcross(run.first(), it) }
        if (across != null) {
            remember()
            move(pile, across, run.size)
            counted()
        }
    }

    /** Turns the stock, or puts the waste back under it once it has run out. */
    private fun turnStock() {
        if (won) return
        remember()
        if (stock.cards.isEmpty()) {
            // Back the way it came, so a second pass through the pack shows the same cards
            // in the same order as the first.
            while (waste.cards.isNotEmpty()) {
                val card = waste.cards.removeLast()
                card.faceUp = false
                stock.cards.add(card)
            }
            wasteShowing = 0
        } else {
            val turned = minOf(if (drawThree) 3 else 1, stock.cards.size)
            repeat(turned) {
                val card = stock.cards.removeLast()
                card.faceUp = true
                waste.cards.add(card)
            }
            wasteShowing = turned
        }
        counted()
    }

    private fun move(from: Pile, to: Pile, count: Int) {
        val moving = from.cards.takeLast(count)
        repeat(count) { from.cards.removeLast() }
        to.cards.addAll(moving)
        // The card a move uncovers turns over on its own: leaving it face down and asking
        // for a tap would be asking for a tap that has exactly one possible answer.
        val exposed = from.top
        if (from.kind == Kind.TABLEAU && exposed != null && !exposed.faceUp) exposed.faceUp = true
        if (from.kind == Kind.WASTE) wasteShowing = (wasteShowing - count).coerceAtLeast(0)
    }

    /** Sends every card that can go home, over and over until none can. */
    private fun sendHome() {
        if (won) return
        remember()
        var moved = true
        while (moved) {
            moved = false
            for (pile in listOf(waste) + tableaus) {
                val card = pile.top ?: continue
                if (!card.faceUp) continue
                val home = foundations.firstOrNull { canGoHome(card, it) } ?: continue
                move(pile, home, 1)
                moves++
                moved = true
            }
        }
        counted()
    }

    private fun canGoHome(card: Card, foundation: Pile): Boolean {
        val top = foundation.top ?: return card.rank == 1
        return card.suit == top.suit && card.rank == top.rank + 1
    }

    private fun canGoAcross(card: Card, tableau: Pile): Boolean {
        val top = tableau.top ?: return card.rank == 13
        return top.faceUp && top.suit.red != card.suit.red && card.rank == top.rank - 1
    }

    /** Whether a run is in the alternating, descending order a pile can be picked up in. */
    private fun ordered(run: List<Card>): Boolean {
        for (i in 1 until run.size) {
            val above = run[i - 1]
            val below = run[i]
            if (!below.faceUp) return false
            if (below.rank != above.rank - 1) return false
            if (below.suit.red == above.suit.red) return false
        }
        return true
    }

    private fun counted() {
        moves++
        // The first move is what starts the clock. See [deal].
        if (!running && !won) {
            running = true
            main.postDelayed(tick, 1000L)
        }
        showCounters()
        main.removeCallbacks(clearHint)
        clearHint.run()
        if (foundations.all { it.cards.size == 13 }) finish()
        board.invalidate()
    }

    private fun finish() {
        won = true
        running = false
        main.removeCallbacks(tick)
        status.text = "out in $moves moves"
        Haptics.tap(board)
    }

    private fun showCounters() {
        movesLabel.text = moves.toString()
        timeLabel.text = seconds.toString()
        if (::undoButton.isInitialized) bar.setCommandEnabled(undoButton, history.isNotEmpty())
    }

    // ------------------------------------------------------------------- the hint

    /**
     * Points at a move, rather than describing one.
     *
     * The desktop game says "put the six of clubs on the seven of hearts", which is a
     * sentence the player then has to find on the table. Here the card and the place it can
     * go are outlined in the accent for a few seconds, which is the same help without the
     * reading - and it is the phone's own way of pointing, being what a tile does when it
     * wants attention.
     *
     * Looked for in the order a player would want them: something that can go home, then a
     * turned-over card that can go across, then a run that would uncover something, and
     * last the pack itself, which is the move there always is until it runs out.
     */
    private fun hint() {
        if (won) return
        main.removeCallbacks(clearHint)

        for (pile in listOf(waste) + tableaus) {
            val card = pile.top ?: continue
            if (!card.faceUp) continue
            val home = foundations.firstOrNull { canGoHome(card, it) } ?: continue
            point(pile, pile.cards.lastIndex, home)
            return
        }
        waste.top?.let { card ->
            if (card.faceUp) {
                val across = tableaus.firstOrNull { canGoAcross(card, it) }
                if (across != null) {
                    point(waste, waste.cards.lastIndex, across)
                    return
                }
            }
        }
        for (pile in tableaus) {
            val start = pile.cards.indexOfFirst { it.faceUp }
            if (start < 0) continue
            val run = pile.cards.subList(start, pile.cards.size).toList()
            if (!ordered(run)) continue
            // Only a move that changes something: shuffling a run between two piles when
            // it uncovers nothing and empties nothing is a move that leaves the game where
            // it was, and a hint that suggests it is a hint that will suggest it again.
            val uncovers = start > 0
            val target = tableaus.firstOrNull {
                it !== pile && it.cards.isNotEmpty() && canGoAcross(run.first(), it)
            } ?: tableaus.firstOrNull { it !== pile && uncovers && canGoAcross(run.first(), it) }
            if (target != null) {
                point(pile, start, target)
                return
            }
        }
        if (stock.cards.isNotEmpty() || waste.cards.isNotEmpty()) {
            point(stock, -1, null)
            return
        }
        status.text = "no moves left"
        main.postDelayed(clearHint, HINT_MS)
    }

    private fun point(from: Pile, index: Int, to: Pile?) {
        hintFrom = from
        hintIndex = index
        hintTo = to
        board.invalidate()
        main.postDelayed(clearHint, HINT_MS)
    }

    // ------------------------------------------------------------------- undo

    private fun remember() {
        history.add(piles.map { pile ->
            pile.cards.map { Triple(it.suit, it.rank, it.faceUp) }
        })
        if (history.size > HISTORY) history.removeFirst()
    }

    private fun undo() {
        val previous = history.removeLastOrNull() ?: return
        for ((pile, saved) in piles.zip(previous)) {
            pile.cards.clear()
            for ((suit, rank, faceUp) in saved) pile.cards.add(Card(suit, rank, faceUp))
        }
        // The move is not un-counted. It happened, and a counter that goes backwards is a
        // score rather than a count of what the player has done.
        moves++
        won = false
        status.text = ""
        clearDrag()
        showCounters()
        board.invalidate()
    }

    private fun clearDrag() {
        dragFrom = null
        dragIndex = 0
    }

    // ------------------------------------------------------------------- the board

    /**
     * The table: two rows, drawn to whatever it is given.
     *
     * Everything is worked out from the width of one card, which is a seventh of the board
     * once the gaps between the piles are off it. The fan a face-up run is spread by then
     * follows the height rather than the width - a long pile has to reach the bottom of the
     * screen and no further - so the same layout holds on a tall phone and a short one.
     */
    @SuppressLint("ViewConstructor")
    private inner class BoardView(context: Context) : View(context) {

        private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textAlign = Paint.Align.CENTER
        }
        private val outline = RectF()
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

        private var cardW = 0f
        private var cardH = 0f
        private var gap = 0f
        private var faceFan = 0f
        private var downFan = 0f

        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private var downX = 0f
        private var downY = 0f
        private var grabX = 0f
        private var grabY = 0f
        private var pressed: Pair<Pile, Int>? = null
        private var travelled = false

        init {
            isClickable = true
        }

        /**
         * One touch, two gestures.
         *
         * A press that stays put is a tap and is answered on the way up; a press that
         * travels picks the cards up at the moment it passes the system's own slop, which
         * is the distance the phone uses everywhere else to tell a tap from a drag. Nobody
         * has to choose between the two, and neither has to be learnt.
         */
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    travelled = false
                    pressed = locate(event.x, event.y)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!travelled &&
                        (kotlin.math.abs(event.x - downX) > slop ||
                            kotlin.math.abs(event.y - downY) > slop)
                    ) {
                        travelled = true
                        lift()
                    }
                    if (dragFrom != null) {
                        dragX = event.x - grabX
                        dragY = event.y - grabY
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragFrom != null) {
                        drop()
                    } else if (!travelled) {
                        Haptics.tap(this)
                        pressed?.let { (pile, index) ->
                            if (pile.kind == Kind.STOCK) turnStock() else play(pile, index)
                        }
                    }
                    pressed = null
                }

                MotionEvent.ACTION_CANCEL -> {
                    clearDrag()
                    pressed = null
                    invalidate()
                }
            }
            return true
        }

        /** Takes hold of whatever the press landed on, if it is something that can move. */
        private fun lift() {
            val (pile, index) = pressed ?: return
            if (won || pile.kind == Kind.STOCK) return
            val card = pile.cards.getOrNull(index) ?: return
            if (!card.faceUp) return
            val run = pile.cards.subList(index, pile.cards.size).toList()
            if (!ordered(run)) return

            dragFrom = pile
            dragIndex = index
            val cardX = pile.x + if (pile.kind == Kind.WASTE) wasteOffset(index) else 0f
            val cardY = pile.y + if (pile.kind == Kind.TABLEAU) offsetOf(pile, index) else 0f
            grabX = downX - cardX
            grabY = downY - cardY
            dragX = cardX
            dragY = cardY
            Haptics.tap(this)
            invalidate()
        }

        /**
         * Lets go.
         *
         * The pile the cards land on is the legal one they overlap most, not the one under
         * the finger: a run held by its top card hangs down over everything below it, and
         * the finger is at the far end of it from where the eye is.
         */
        private fun drop() {
            val from = dragFrom ?: return
            val count = from.cards.size - dragIndex
            val moving = from.cards[dragIndex]
            val held = RectF(dragX, dragY, dragX + cardW, dragY + cardH)

            var best: Pile? = null
            var bestOverlap = 0f
            for (pile in foundations + tableaus) {
                if (pile === from) continue
                val allowed = if (pile.kind == Kind.FOUNDATION) {
                    count == 1 && canGoHome(moving, pile)
                } else {
                    canGoAcross(moving, pile)
                }
                if (!allowed) continue
                val area = overlap(held, landingOf(pile))
                if (area > bestOverlap) {
                    bestOverlap = area
                    best = pile
                }
            }

            if (best != null) {
                remember()
                move(from, best, count)
                clearDrag()
                counted()
            } else {
                // Straight back where it came from. A card that will not go somewhere is
                // one the player has already looked at; walking it home in an animation
                // says nothing they cannot see.
                clearDrag()
                invalidate()
            }
        }

        private fun overlap(a: RectF, b: RectF): Float {
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return if (w <= 0f || h <= 0f) 0f else w * h
        }

        /** Where a card would land on [pile], which is under whatever is already there. */
        private fun landingOf(pile: Pile): RectF {
            val y = pile.y + if (pile.kind == Kind.TABLEAU) offsetOf(pile, pile.cards.size) else 0f
            return RectF(pile.x, y, pile.x + cardW, y + cardH)
        }

        /**
         * What was under the finger.
         *
         * Asked of the piles in the order they are drawn in, back to front, so a card lying
         * over another is the one that answers - which on a fanned pile is every card but
         * the last, since each one covers the top of the card above it.
         */
        private fun locate(x: Float, y: Float): Pair<Pile, Int>? {
            if (cardW <= 0f) return null
            if (inRect(x, y, stock.x, stock.y)) return stock to stock.cards.lastIndex
            // The waste is fanned, so its top card is not over its own slot: it is up to
            // two cards' worth to the right of it, which is where the tap has to be caught.
            val wasteTop = waste.cards.lastIndex
            if (wasteTop >= 0 && inRect(x, y, waste.x + wasteOffset(wasteTop), waste.y)) {
                return waste to wasteTop
            }
            for (pile in foundations) {
                if (pile.cards.isEmpty()) continue
                if (inRect(x, y, pile.x, pile.y)) return pile to pile.cards.lastIndex
            }
            for (pile in tableaus) {
                if (x < pile.x || x > pile.x + cardW) continue
                if (pile.cards.isEmpty()) continue
                for (index in pile.cards.indices.reversed()) {
                    val top = pile.y + offsetOf(pile, index)
                    val bottom = if (index == pile.cards.lastIndex) top + cardH
                    else pile.y + offsetOf(pile, index + 1)
                    if (y in top..bottom) return pile to index
                }
            }
            return null
        }

        private fun inRect(x: Float, y: Float, left: Float, top: Float) =
            x >= left && x <= left + cardW && y >= top && y <= top + cardH

        /** How far down its pile the card at [index] is drawn. */
        private fun offsetOf(pile: Pile, index: Int): Float {
            var offset = 0f
            for (i in 0 until index) {
                offset += if (pile.cards[i].faceUp) faceFan else downFan
            }
            return offset
        }

        /** How far across the waste the card at [index] is drawn. */
        private fun wasteOffset(index: Int): Float {
            val shown = maxOf(wasteShowing, 1)
            val first = (waste.cards.size - shown).coerceAtLeast(0)
            return ((index - first).coerceAtLeast(0)) * cardW * WASTE_FAN
        }

        override fun onDraw(canvas: Canvas) {
            layoutBoard()
            if (cardW <= 0f) return

            drawSlot(canvas, stock.x, stock.y, stock.cards.isEmpty())
            drawSlot(canvas, waste.x, waste.y, false)
            for (foundation in foundations) {
                drawSlot(canvas, foundation.x, foundation.y, false)
                foundation.top?.let { drawCard(canvas, it, foundation.x, foundation.y) }
            }
            // Only the back of the stock is worth drawing: the rest of it is exactly behind.
            stock.top?.let { drawCard(canvas, it, stock.x, stock.y) }

            // The turned cards, fanned across so a three-card turn is three cards and not
            // one with two hidden behind it.
            val shown = maxOf(wasteShowing, 1)
            val first = (waste.cards.size - shown).coerceAtLeast(0)
            for (index in first until waste.cards.size) {
                if (waste === dragFrom && index >= dragIndex) continue
                drawCard(canvas, waste.cards[index], waste.x + wasteOffset(index), waste.y)
            }

            for (pile in tableaus) {
                if (pile.cards.isEmpty() || (pile === dragFrom && dragIndex == 0)) {
                    drawSlot(canvas, pile.x, pile.y, false)
                }
                pile.cards.forEachIndexed { index, card ->
                    if (pile === dragFrom && index >= dragIndex) return@forEachIndexed
                    drawCard(canvas, card, pile.x, pile.y + offsetOf(pile, index))
                }
            }

            drawHint(canvas)

            // Last, and over everything: the cards in hand are the ones being looked at.
            dragFrom?.let { from ->
                for (index in dragIndex until from.cards.size) {
                    drawCard(
                        canvas,
                        from.cards[index],
                        dragX,
                        dragY + (index - dragIndex) * faceFan
                    )
                }
            }
        }

        /**
         * Works out where everything is, from the size the board turned out to be.
         *
         * The fan is what gives: a pile of thirteen has to fit under the row above it, so
         * the spread is whatever is left of the height divided by the longest pile the deal
         * can produce, capped at the spread a run reads comfortably at.
         */
        private fun layoutBoard() {
            gap = dp(GAP_DP).toFloat()
            cardW = cardWidth(width)
            cardH = cardW * CARD_RATIO
            if (cardW <= 0f) return

            val top = 0f
            stock.x = columnLeft(0, width)
            stock.y = top
            waste.x = columnLeft(1, width)
            waste.y = top
            foundations.forEachIndexed { i, pile ->
                pile.x = columnLeft(FOUNDATION_COLUMN + i, width)
                pile.y = top
            }

            val tableauTop = top + cardH + dp(ROW_GAP_DP)
            val room = height - tableauTop - cardH
            faceFan = (room / MAX_FAN).coerceIn(dp(6).toFloat(), cardH * FAN_SHARE)
            downFan = faceFan * DOWN_SHARE
            tableaus.forEachIndexed { i, pile ->
                pile.x = columnLeft(i, width)
                pile.y = tableauTop
            }
        }

        /**
         * An empty place on the table.
         *
         * An outline in the page's own grey rather than a picture of a card: what is being
         * drawn is the absence of one. The stock's carries a mark when it is empty, which is
         * the one slot on the table that does something when it is tapped.
         */
        private fun drawSlot(canvas: Canvas, x: Float, y: Float, recycles: Boolean) {
            outline.set(x, y, x + cardW, y + cardH)
            slot.color = palette.inactive
            slot.strokeWidth = dp(2).toFloat()
            canvas.drawRect(outline, slot)
            if (!recycles) return
            marker.color = palette.foregroundSubtle
            marker.textSize = cardH * MARKER_SHARE
            canvas.drawText(
                "↺",
                outline.centerX(),
                outline.centerY() - (marker.descent() + marker.ascent()) / 2f,
                marker
            )
        }

        /** The accent, around the card the hint means and the place it can go. */
        private fun drawHint(canvas: Canvas) {
            val from = hintFrom ?: return
            slot.color = palette.accent
            slot.strokeWidth = dp(3).toFloat()
            val y = when {
                hintIndex < 0 -> from.y
                from.kind == Kind.TABLEAU -> from.y + offsetOf(from, hintIndex)
                else -> from.y
            }
            val x = if (hintIndex >= 0 && from.kind == Kind.WASTE) {
                from.x + wasteOffset(hintIndex)
            } else {
                from.x
            }
            outline.set(x, y, x + cardW, y + cardH)
            canvas.drawRect(outline, slot)
            hintTo?.let { canvas.drawRect(landingOf(it), slot) }
        }

        private fun drawCard(canvas: Canvas, card: Card, x: Float, y: Float) {
            val art = face(card) ?: run {
                // Nothing to draw it with: a plain rectangle rather than a hole in the
                // table, so a missing picture is a card that cannot be read rather than a
                // card that is not there.
                shadow.color = if (card.faceUp) 0xFFF2F2F2.toInt() else palette.accent
                canvas.drawRect(x, y, x + cardW, y + cardH, shadow)
                return
            }
            art.setBounds(x.toInt(), y.toInt(), (x + cardW).toInt(), (y + cardH).toInt())
            art.draw(canvas)
        }
    }

    /**
     * The picture for a card, kept once it has been asked for.
     *
     * The desktop game's own artwork, by the same names: this is the same deck in a
     * different room, and cards redrawn flat for the occasion would be a different game's
     * cards.
     */
    private fun face(card: Card): Drawable? {
        val name = if (card.faceUp) {
            "solitare_${card.suit.asset}_${card.rank}" + if (vistaFaces) "_vista" else ""
        } else {
            "solitare_card_back_$back"
        }
        return faces.getOrPut(name) {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) null else ContextCompat.getDrawable(context, id)
        }
    }

    // ------------------------------------------------------------------- furniture

    private fun counter(): TextView = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        textSize = COUNTER_SP
        includeFontPadding = false
        setTextColor(palette.accent)
    }

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

    /**
     * How wide a card is on a board of [boardWidth], and where each column of them starts.
     *
     * Seven columns and the six gaps between them, inside the page's margins. Everything on
     * the table is placed by this - and so is the counter row above it, which is why it is
     * here rather than inside the board.
     */
    private fun cardWidth(boardWidth: Int): Float =
        (boardWidth - dp(PAGE_MARGIN_DP) * 2 - dp(GAP_DP) * 6) / 7f

    private fun columnLeft(index: Int, boardWidth: Int): Float =
        dp(PAGE_MARGIN_DP) + index * (cardWidth(boardWidth) + dp(GAP_DP))

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private const val PAGE_MARGIN_DP = 12
        private const val TITLE_SP = 34f
        private const val COUNTER_SP = 30f

        /** Between one pile and the next, and between the two rows. */
        private const val GAP_DP = 4

        /**
         * Which of the seven columns the four homes begin on.
         *
         * Two for the pack and its turned cards, one left empty between, then the four -
         * which is the layout Klondike is always dealt in, and it happens to divide the
         * table into the two halves the counters above it are about.
         */
        private const val FOUNDATION_COLUMN = 3
        private const val ROW_GAP_DP = 14

        /** A playing card, taller than it is wide, in the proportion the artwork is drawn in. */
        private const val CARD_RATIO = 1.35f

        /** The longest a pile gets: six face down, then a run of the rest. */
        private const val MAX_FAN = 18f

        /** How far down a card the next one starts, at most, and how far for a face-down one. */
        private const val FAN_SHARE = 0.28f
        private const val DOWN_SHARE = 0.45f

        /** How far across the waste spreads its turned cards. */
        private const val WASTE_FAN = 0.32f

        /** How much of an empty stock the recycle mark fills. */
        private const val MARKER_SHARE = 0.4f

        /** How long the accent stays around a hint. */
        private const val HINT_MS = 2600L

        /** How many moves can be taken back. Deep enough to unpick a bad run of taps. */
        private const val HISTORY = 40

        /** How many backs the launcher carries, and where the choice is kept. */
        private const val BACKS = 14
        private const val PREFS = "SolitarePrefs"

        /** The desktop game's own key: pick a deck on the phone and it is dealt on both. */
        private const val PREF_BACK = "cardBack"
        private const val PREF_FACES = "metroVistaCards"

        private const val ICON_DIR = "custom_icons_8"
        private const val NEW_ICON = "$ICON_DIR/appbar.refresh.svg"
        private const val UNDO_ICON = "$ICON_DIR/appbar.undo.curve.svg"
        private const val HINT_ICON = "$ICON_DIR/appbar.lightbulb.svg"
    }
}
