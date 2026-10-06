package rocks.gorjan.gokixp.apps.hearts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.cards.CardArt
import rocks.gorjan.gokixp.apps.cards.CardHost
import rocks.gorjan.gokixp.apps.cards.CardModals
import rocks.gorjan.gokixp.apps.cards.tween
import rocks.gorjan.gokixp.winui.WinUi
import rocks.gorjan.gokixp.winui.radioButton
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hearts: The Microsoft Hearts Network. Nobody else ever answers on the network, so Pauline,
 * Michele and Ben (the computer) take the seats.
 *
 * A port of winos's `Hearts` object (js/075-hearts.js): the same welcome and "no dealer" dance,
 * the same passing, scoring and moon-shooting, and the same three computer players. On a phone
 * there is no F2, so "Waiting for others to join" says to tap the table, which winos already
 * accepted as starting the game.
 */
class HeartsGame(private val context: Context, private val host: CardHost) {

    class Card(val s: Int, val r: Int) {
        var sel = false
        var used = false
        var flash = false
    }

    private class Player(val id: Int) {
        var name = ""
        var hand: MutableList<Card> = mutableListOf()
        var score = 0
        var won: MutableList<Card> = mutableListOf()
        var present = id == 0
        var shoot = false
    }

    private enum class Mode { STARTING, DEALING, SELECTING, ACCEPTING, WAITING, PLAYING, SCORING }

    private class Moon(var risk: Boolean = true, var shooter: Int = -1)
    private class Fly(val c: Card, var x: Float, var y: Float)

    /** Where a seat's hand starts and runs, where its card lands in the trick, and where its tricks go. */
    private class Seat(
        val loc: FloatArray, val d: FloatArray, val play: FloatArray, val home: FloatArray,
        val dot: FloatArray?, val nameX: Float, val nameY: Float, val nameAlign: Align, val nameTop: Boolean,
    )

    private class Layout(
        val s: Float, val w: Float, val h: Float, val hs: Float, val e: Float, val pop: Float,
        val cw: Float, val ch: Float, val seats: List<Seat>,
    )

    private val ui = WinUi(context)
    private val modals = CardModals(host, ui)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val art = CardArt(context).apply { large = prefs.getBoolean("large", CardArt.solitaireLarge(context)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = context.resources.displayMetrics.density

    // ---------------------------------------------------------------- state

    private val players = List(4) { Player(it) }
    private val history = mutableListOf<IntArray>()
    private var passDir = 0
    private var mode = Mode.STARTING
    private var dealer = false
    private var inGame = false
    private var dealt = 52
    private val trick = arrayOfNulls<Card>(4)
    private var led = -1
    private var turn = -1
    private var broken = false
    private var qs = false
    private var moon = Moon()
    private var tricksLeft = 0
    private val gone = HashSet<Int>()
    private val fly = mutableListOf<Fly>()
    private var showWon = false
    private var flashing = false
    private var sound = prefs.getBoolean("sound", false)
    private var dead = false
    private var flashJob: kotlinx.coroutines.Job? = null

    private val table = Table()
    private val passButton = ui.button("", 100) { button() }.apply { visibility = View.GONE }
    private val status = ui.statusBar(listOf(1f))

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ui.pal.face)
        addView(
            ui.menuBar(listOf(WinUi.Menu("&Game") { gameMenu() }, WinUi.Menu("&Help") { helpMenu() })),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(FrameLayout(context).apply {
            setBackgroundColor(GREEN)
            addView(table, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(passButton, FrameLayout.LayoutParams(ui.dp(100), ui.dp(25)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(status.bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        status("Welcome to the Microsoft Hearts Network.")
        // Like the original, the welcome dialog is posted once the window is up
        root.post { if (!dead) welcome() }
    }

    private fun gameMenu() = listOf(
        ContextMenuItem("New Game", shortcut = "F2", isEnabled = !inGame && dealer, action = { newGame() }),
        ContextMenuItem.separator(),
        ContextMenuItem("Options...", shortcut = "F7", action = { options() }),
        ContextMenuItem("Sound", shortcut = "F8", hasCheckbox = true, isChecked = sound, action = { toggleSound() }),
        ContextMenuItem("Score...", shortcut = "F9", action = { scoreSheet() }),
        ContextMenuItem("Large Cards", hasCheckbox = true, isChecked = art.large, action = { toggleLarge() }),
        ContextMenuItem.separator(),
        ContextMenuItem("Exit", action = { host.closeWindow() }),
    )

    private fun helpMenu() = listOf(
        ContextMenuItem("About Hearts", action = {
            if (!modals.busy) modals.message("About Hearts", "The Microsoft Hearts Network\n\nThe lowest score when someone reaches 100 wins.")
        }),
    )

    fun cleanup() {
        dead = true
        scope.cancel()
        modals.closeAll()
    }

    private fun status(text: String) {
        status.panes[0].text = text
    }

    private fun playSound(resId: Int) {
        if (sound) host.playSound(resId)
    }

    private fun toggleSound() {
        sound = !sound
        prefs.edit { putBoolean("sound", sound) }
    }

    /** Solitaire's high-visibility faces; the computer players' hands stay face down either way. */
    private fun toggleLarge() {
        art.large = !art.large
        prefs.edit { putBoolean("large", art.large) }
        table.invalidate()
    }

    private fun speed() = prefs.getString("speed", "normal") ?: "normal"

    // ---------------------------------------------------------------- joining the network

    private fun welcome() {
        val name = ui.field().apply {
            setText(prefs.getString("name", null) ?: MainActivity.getUserName(context))
            filters = arrayOf(android.text.InputFilter.LengthFilter(14))
        }
        val asDealer = prefs.getBoolean("dealer", true)
        val join = ui.radioButton("I want to connect to another game.", !asDealer)
        val meister = ui.radioButton("I want to be dealer.", asDealer)
        val group = ui.groupBox("How do you want to play?")
        group.body.addView(RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            addView(join)
            addView(meister)
        })
        var done = false
        var close: () -> Unit = {}
        val ok = ok@{
            val who = name.text.toString().trim()
            if (who.isEmpty()) { name.requestFocus(); return@ok }
            done = true
            val beDealer = meister.isChecked
            prefs.edit { putString("name", who); putBoolean("dealer", beDealer) }
            close()
            players[0].name = who
            if (beDealer) becomeDealer() else locate()
        }
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Welcome to the Microsoft Hearts Network."))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.label("What is your name?"))
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(10) })
            })
            addView(group.frame)
        }
        val content = modals.panel().apply {
            addView(modals.sideButtons(main, ui.button("OK", onClick = ok), ui.button("Quit") { close() }))
        }
        close = modals.show("The Microsoft Hearts Network", content, 380) {
            if (!done && !dead) root.post { host.closeWindow() }
        }
    }

    private fun locate() {
        val input = ui.field().apply {
            setText(prefs.getString("server", ""))
            filters = arrayOf(android.text.InputFilter.LengthFilter(15))
        }
        var done = false
        var close: () -> Unit = {}
        val ok = {
            done = true
            val server = input.text.toString().trim()
            prefs.edit { putString("server", server) }
            close()
            connect(server)
        }
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.label("Enter the dealer's computer name:"))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) })
        }
        val content = modals.panel().apply {
            addView(modals.sideButtons(main, ui.button("OK", onClick = ok), ui.button("Cancel") { close() }))
        }
        close = modals.show("Locate dealer", content, 330) { if (!done && !dead) root.post { welcome() } }
    }

    /** There is never a dealer out there: say so, and deal the game here instead. */
    private fun connect(server: String) {
        status("Trying to connect with dealer...")
        scope.launch {
            delay(2200)
            if (dead) return@launch
            val where = if (server.isNotEmpty()) "No computer named ${server.trimStart('\\').uppercase()} is dealing a game of Hearts."
            else "No Hearts dealers were found on the network."
            modals.message(
                "The Microsoft Hearts Network",
                "Unable to connect with dealer.\n\n$where You will be the dealer instead, and the computer will play the other hands.",
                widthDp = 340
            ) { if (!dead) becomeDealer() }
        }
    }

    private fun becomeDealer() {
        dealer = true
        mode = Mode.STARTING
        status("Waiting for others to join...   Tap the table to begin with current players.")
        table.invalidate()
    }

    // ---------------------------------------------------------------- dealing and passing

    private fun names(): List<String> = (0 until 3).map { prefs.getString("name$it", null) ?: NAMES[it] }

    private fun newGame() {
        if (inGame || !dealer || modals.busy || dead) return
        val names = names()
        players.forEachIndexed { i, p ->
            if (i > 0) p.name = names[i - 1]
            p.present = true
            p.score = 0
        }
        inGame = true
        passDir = 0
        history.clear()
        deal()
    }

    private fun deal() {
        val deck = mutableListOf<Card>()
        for (s in 0 until 4) for (r in 1..13) deck.add(Card(s, r))
        deck.shuffle()
        players.forEachIndexed { i, p ->
            p.hand = deck.subList(i * 13, i * 13 + 13).toMutableList()
            p.won = mutableListOf()
            p.shoot = false
        }
        trick.fill(null)
        led = -1
        turn = -1
        gone.clear()
        showWon = false
        fly.clear()
        mode = Mode.DEALING
        val passing = passDir != 3
        passButton.visibility = if (passing) View.VISIBLE else View.GONE
        ui.setEnabled(passButton, false)
        passButton.text = if (passing) listOf("Pass Left", "Pass Right", "Pass Across")[passDir] else ""
        placeButton()
        if (passing) players.drop(1).forEach { p -> ai.pass(p).forEach { it.sel = true } }
        scope.launch {
            // Cards appear one at a time around the table, as if dealt
            dealt = 0
            while (dealt < 52 && !dead) {
                table.invalidate()
                delay(if (speed() == "fast") 4 else 14)
                dealt++
            }
            if (dead) return@launch
            sort(players[0])
            table.invalidate()
            if (passing) {
                mode = Mode.SELECTING
                status("Select three cards to pass to ${players[OFFSET[passDir]].name}.")
                return@launch
            }
            firstMove()
        }
    }

    /** Clubs, diamonds, spades, hearts, so the colours alternate; aces high, played cards to the end. */
    private fun sort(p: Player) {
        val order = intArrayOf(0, 1, 3, 2)
        p.hand.sortBy { if (it.used) 99 else order[it.s] * 20 + v(it) }
    }

    private fun placeButton() {
        val l = table.l ?: return
        val bw = ui.dp((100 * max(l.s, 0.8f)).roundToInt())
        passButton.layoutParams = (passButton.layoutParams as FrameLayout.LayoutParams).apply {
            width = bw
            leftMargin = (l.cw / 2 - bw / 2f).roundToInt()
            topMargin = (l.ch - l.h - 2 * l.pop - ui.dp(25)).roundToInt()
        }
    }

    private fun pop(slot: Int) {
        val p = players[0]
        val c = p.hand[slot]
        val count = p.hand.count { it.sel }
        if (!c.sel && count == 3) return
        c.sel = !c.sel
        ui.setEnabled(passButton, p.hand.count { it.sel } == 3)
        table.invalidate()
    }

    private fun button() {
        if (modals.busy || dead) return
        if (mode == Mode.ACCEPTING) {
            passButton.visibility = View.GONE
            players[0].hand.forEach { it.sel = false }
            mode = Mode.WAITING
            table.invalidate()
            firstMove()
            return
        }
        if (mode != Mode.SELECTING || players[0].hand.count { it.sel } != 3) return
        val passed = players.map { p -> p.hand.filter { it.sel } }
        fun from(i: Int) = passed[(i + 4 - OFFSET[passDir]) % 4]
        val hands = players.mapIndexed { i, p ->
            var j = 0
            p.hand.map { if (it.sel) from(i)[j++] else it }.toMutableList()
        }
        // The local player's new cards stay popped up until OK; everyone else's just join the hand
        players.forEachIndexed { i, p ->
            p.hand = hands[i]
            p.hand.forEach { c -> c.sel = i == 0 && from(i).any { it === c } }
        }
        sort(players[0])
        mode = Mode.ACCEPTING
        passButton.text = "OK"
        ui.setEnabled(passButton, true)
        status("Press OK to accept cards.")
        table.invalidate()
    }

    // ---------------------------------------------------------------- tricks

    private fun firstMove() {
        val holder = players.first { p -> p.hand.any { it.s == 0 && it.r == 2 } }
        led = holder.id
        turn = holder.id
        broken = false
        qs = false
        moon = Moon()
        tricksLeft = 13
        trick.fill(null)
        players.forEach { p ->
            p.won = mutableListOf()
            if (p.id != 0) p.shoot = ai.moonHand(inHand(p))
        }
        next()
    }

    private fun next() {
        if (dead) return
        val p = players[turn]
        if (p.id == 0) {
            mode = Mode.PLAYING
            status("Select a card to play.")
            return
        }
        mode = Mode.WAITING
        status("Waiting for ${p.name} to move...")
        scope.launch {
            delay(when (speed()) { "slow" -> 350L; "fast" -> 40L; else -> 180L })
            if (dead) return@launch
            play(p, ai.play(p))
        }
    }

    /** Why a card can't be played, or null when it can. */
    private fun refuse(c: Card): String? {
        val hand = inHand(players[0])
        if (led == 0) {
            if (!(c.s == 0 && c.r == 2) && hand.any { it.s == 0 && it.r == 2 }) return "You must lead the two of clubs."
            if (c.s == 2 && !broken && hand.any { it.s != 2 }) return "Hearts has not been broken.  Choose another suit."
            return null
        }
        val ledCard = trick[led]!!
        if (c.s == ledCard.s) return null
        if (hand.any { it.s == ledCard.s }) return "You must follow suit.  Play a ${SUITS[ledCard.s]}."
        if (ledCard.s == 0 && ledCard.r == 2 && points(c) > 0 && hand.any { points(it) == 0 }) return "You cannot play a point card on the first trick.  Select again."
        return null
    }

    private fun legal(p: Player): List<Card> {
        val hand = inHand(p)
        if (turn == led) {
            if (tricksLeft == 13) return hand.filter { it.s == 0 && it.r == 2 }
            val other = hand.filter { it.s != 2 }
            return if (broken || other.isEmpty()) hand else other
        }
        val ledCard = trick[led]!!
        val follow = hand.filter { it.s == ledCard.s }
        if (follow.isNotEmpty()) return follow
        if (ledCard.s == 0 && ledCard.r == 2) {
            val safe = hand.filter { points(it) == 0 }
            if (safe.isNotEmpty()) return safe
        }
        return hand
    }

    private fun click(slot: Int) {
        val c = players[0].hand[slot]
        val why = refuse(c)
        if (why == null) {
            mode = Mode.WAITING
            play(players[0], c)
            return
        }
        // A card that can't be played flashes
        status(why)
        c.flash = true
        flashing = true
        table.invalidate()
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(250)
            c.flash = false
            flashing = false
            table.invalidate()
        }
    }

    private fun seatPos(p: Player, slot: Int): Pair<Float, Float> {
        val st = table.l!!.seats[p.id]
        return (st.loc[0] + slot * st.d[0]) to (st.loc[1] + slot * st.d[1])
    }

    private fun play(p: Player, c: Card) {
        scope.launch {
            val from = seatPos(p, p.hand.indexOf(c))
            val to = table.l!!.seats[p.id].play
            c.used = true
            gone.add(c.s * 13 + c.r)
            glide(c, from, to[0] to to[1], when (speed()) { "slow" -> 300f; "fast" -> 3600f; else -> 900f })
            if (dead) return@launch
            trick[p.id] = c
            if (!broken && c.s == 2) { broken = true; playSound(R.raw.hearts_glass) }
            if (isQS(c)) { qs = true; playSound(R.raw.hearts_timpani) }
            table.invalidate()
            turn = (turn + 1) % 4
            if (turn == led) endTrick() else next()
        }
    }

    private suspend fun glide(c: Card, a: Pair<Float, Float>, e: Pair<Float, Float>, speed: Float) {
        val l = table.l ?: return
        val f = Fly(c, a.first, a.second)
        fly.add(f)
        val dist = hypot(e.first - a.first, e.second - a.second) / density
        tween(dist / (speed * max(l.s, 0.5f)) * 1000) { k ->
            f.x = a.first + (e.first - a.first) * k
            f.y = a.second + (e.second - a.second) * k
            table.invalidate()
        }
        fly.remove(f)
    }

    private suspend fun endTrick() {
        val ledCard = trick[led]!!
        var winner = led
        for (i in 1 until 4) {
            val j = (led + i) % 4
            val c = trick[j]!!
            if (c.s == ledCard.s && v(c) > v(trick[winner]!!)) winner = j
        }
        if (moon.risk && trick.any { it != null && points(it) > 0 }) {
            if (moon.shooter < 0) moon.shooter = winner
            else if (moon.shooter != winner) moon.risk = false
        }
        mode = Mode.WAITING
        delay(1000)
        if (dead) return
        // The trick is swept off toward whoever took it, last card first
        val l = table.l!!
        val home = l.seats[winner].home
        val fast = if (speed() == "slow") 300f else 1800f
        for (i in led + 3 downTo led) {
            val c = trick[i % 4] ?: continue
            val at = l.seats[i % 4].play
            trick[i % 4] = null
            glide(c, at[0] to at[1], home[0] to home[1], fast)
            if (dead) return
            if (points(c) > 0) players[winner].won.add(c)
        }
        led = winner
        turn = winner
        if (--tricksLeft > 0) { table.invalidate(); next(); return }
        endHand()
    }

    private fun endHand() {
        val taken = players.map { p -> p.won.sumOf { points(it) } }
        val moonIdx = players.indexOfFirst { it.won.size == 14 }
        players.forEachIndexed { i, p -> p.score += if (moonIdx < 0) taken[i] else if (i == moonIdx) 0 else 26 }
        history.add(IntArray(4) { players[it].score })
        if (history.size > 12) history.removeAt(0)
        showWon = true
        mode = Mode.SCORING
        status("Score")
        table.invalidate()
        scoreSheet {
            if (dead) return@scoreSheet
            if (players.maxOf { it.score } >= 100) return@scoreSheet gameOver()
            passDir = (passDir + 1) % 4
            deal()
        }
    }

    private fun gameOver() {
        inGame = false
        mode = Mode.STARTING
        showWon = false
        history.clear()
        players.forEach { p -> p.hand = mutableListOf(); p.won = mutableListOf(); p.score = 0; p.present = p.id == 0 }
        passButton.visibility = View.GONE
        becomeDealer()
    }

    // ---------------------------------------------------------------- dialogs

    /** Each column is a player: earlier totals struck out, the latest in bold, the leader in blue (dark red once someone reaches 100). */
    private fun scoreSheet(then: (() -> Unit)? = null) {
        if (then == null && modals.busy) return
        val n = history.size
        val latest = if (n > 0) history[n - 1] else IntArray(4)
        val best = latest.min()
        val over = latest.max() >= 100
        var title = "Score Sheet"
        if (n > 0) title += " -- " + PLACES[(1..3).count { latest[it] < latest[0] }]
        if (over) title = if (latest[0] == best) "Game Over -- You Win" else "Game Over"
        val cols = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = ui.dp(120)
        }
        players.forEachIndexed { i, p ->
            val color = when {
                n > 0 && latest[i] == best -> if (over) 0xFF7F0000.toInt() else 0xFF0000FF.toInt()
                else -> ui.pal.text
            }
            cols.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                fun cell(text: CharSequence) = addView(ui.label("", bold = true, color = color).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                cell(if (p.present) p.name else "")
                history.dropLast(1).forEach { row ->
                    cell(SpannableString(row[i].toString()).apply { setSpan(StrikethroughSpan(), 0, length, 0) })
                }
                if (n > 0) cell(latest[i].toString())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        var close: () -> Unit = {}
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(8), 0, 0, 0)
            addView(ui.button("OK") { close() })
            if (over) addView(ImageView(context).apply { setImageResource(R.drawable.hearts_icon) },
                LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { topMargin = ui.dp(40) })
        }
        val content = modals.panel().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(cols, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(side)
            })
        }
        close = modals.show(title, content, 380) { if (then != null) root.post(then) }
    }

    private fun options() {
        if (modals.busy) return
        val speed = speed()
        val names = names()
        val slow = ui.radioButton("Slow", speed == "slow")
        val normal = ui.radioButton("Normal", speed == "normal")
        val fast = ui.radioButton("Fast", speed == "fast")
        val speedBox = ui.groupBox("Animation speed")
        speedBox.body.addView(RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            addView(slow); addView(normal); addView(fast)
        })
        val fields = (0 until 3).map { i ->
            ui.field().apply {
                setText(names[i])
                filters = arrayOf(android.text.InputFilter.LengthFilter(14))
            }
        }
        val namesBox = ui.groupBox("Computer player names")
        fields.forEachIndexed { i, f ->
            namesBox.body.addView(f, LinearLayout.LayoutParams(ui.dp(130), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = ui.dp(6)
            })
        }
        var close: () -> Unit = {}
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(speedBox.frame)
            addView(namesBox.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) })
        }
        val ok = {
            prefs.edit {
                putString("speed", if (slow.isChecked) "slow" else if (fast.isChecked) "fast" else "normal")
                fields.forEachIndexed { i, f -> putString("name$i", f.text.toString().trim().ifEmpty { NAMES[i] }) }
            }
            close()
        }
        val content = modals.panel().apply {
            addView(modals.sideButtons(main, ui.button("OK", onClick = ok), ui.button("Cancel") { close() }))
        }
        close = modals.show("Hearts Options", content, 300)
    }

    // ---------------------------------------------------------------- input

    private fun handSlot(x: Float, y: Float): Int {
        val l = table.l ?: return -1
        val st = l.seats[0]
        val lx = st.loc[0]
        val ly = st.loc[1]
        val hand = players[0].hand
        if (hand.isEmpty() || y < ly - l.pop || y > ly + l.h || x < lx || x > lx + 12 * l.hs + l.w) return -1
        var s = min(12, floor((x - lx) / l.hs).toInt()).coerceAtMost(hand.size - 1)
        fun back(ok: (Card) -> Boolean): Boolean {
            while (true) {
                if (s == 0) return false
                s--
                if (lx + s * l.hs + l.w < x) return false
                if (ok(hand[s])) return true
            }
        }
        // Above the row only a popped-up card counts; under a played card, the card to its left may still reach
        if (y < ly && !hand[s].sel && !back { it.sel }) return -1
        if (hand[s].used && !back { !it.used }) return -1
        return s
    }

    private fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        table.parent?.requestDisallowInterceptTouchEvent(true)
        if (modals.busy || table.l == null || flashing) return true
        // Waiting for players who will never come: a tap on the table starts the game too
        if (mode == Mode.STARTING && dealer && !inGame) { newGame(); return true }
        val slot = handSlot(e.x, e.y)
        if (slot < 0) return true
        if (mode == Mode.SELECTING) pop(slot)
        else if (mode == Mode.PLAYING) click(slot)
        return true
    }

    // ---------------------------------------------------------------- drawing

    private fun layout(cwi: Int, chi: Int): Layout {
        val cw = cwi.toFloat()
        val ch = chi.toFloat()
        val dp = density
        val s = minOf(1f, cw / (430 * dp), ch / (380 * dp))
        val w = 71 * s * dp
        val h = 96 * s * dp
        val hs = 15 * s * dp
        val e = 3 * s * dp
        val pop = 20 * s * dp
        val base = (cw - (12 * hs + w)) / 2
        val vbase = (ch - (12 * hs + h)) / 2
        val cx = cw / 2 - w / 2
        val cy = ch / 2 - h / 2
        val seats = listOf(
            Seat(floatArrayOf(base, ch - h - e), floatArrayOf(hs, 0f), floatArrayOf(cx - 5 * s * dp, cy + 30 * s * dp), floatArrayOf(cx - 5 * s * dp, ch + h),
                null, base - e, ch - e, Align.RIGHT, false),
            Seat(floatArrayOf(3 * e, vbase), floatArrayOf(0f, hs), floatArrayOf(cx - 30 * s * dp, cy - 5 * s * dp), floatArrayOf(-w, cy - 5 * s * dp),
                floatArrayOf(3 * e + w + e, vbase + hs / 2), 3 * e + 2 * dp, vbase - dp, Align.LEFT, false),
            Seat(floatArrayOf(base + 12 * hs, e), floatArrayOf(-hs, 0f), floatArrayOf(cx + 5 * s * dp, cy - 30 * s * dp), floatArrayOf(cx + 5 * s * dp, -h),
                floatArrayOf(base + 12 * hs + w - hs / 2, e + h + e), base + 12 * hs + w + e, e, Align.LEFT, true),
            Seat(floatArrayOf(cw - w - 3 * e, vbase + 12 * hs), floatArrayOf(0f, -hs), floatArrayOf(cx + 30 * s * dp, cy + 5 * s * dp), floatArrayOf(cw, cy + 5 * s * dp),
                floatArrayOf(cw - w - 4 * e, vbase + 12 * hs + h - hs / 2), cw - 3 * e - 2 * dp, vbase + 12 * hs + h + dp, Align.RIGHT, true),
        )
        return Layout(s, w, h, hs, e, pop, cw, ch, seats)
    }

    @SuppressLint("ViewConstructor")
    private inner class Table : View(context) {
        var l: Layout? = null
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            typeface = ui.font(bold = true) ?: Typeface.DEFAULT_BOLD
        }
        private val dot = Paint().apply { color = 0xFFFFFFFF.toInt() }

        init {
            id = R.id.hearts_table
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            l = layout(w, h)
            post { placeButton() }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)

        private fun card(canvas: Canvas, c: Card, up: Boolean, x: Float, y: Float, hilite: Boolean = false) {
            val l = l ?: return
            art.draw(canvas, c.s, c.r, up, x, y, l.w, l.h, hilite)
        }

        override fun onDraw(canvas: Canvas) {
            val l = l ?: return
            canvas.drawColor(GREEN)
            text.textSize = max(10f, 13 * max(l.s, 0.8f)) * density
            val fm = text.fontMetrics
            players.forEach { p ->
                val st = l.seats[p.id]
                if (p.present && p.name.isNotEmpty()) {
                    var x = st.nameX
                    var y = st.nameY
                    var align = st.nameAlign
                    var top = st.nameTop
                    // A window too narrow for the name beside the hand puts it above the cards
                    if (p.id == 0 && text.measureText(p.name) > x - 2 * density) {
                        x = st.loc[0]; y = st.loc[1] - l.pop - 2 * density; align = Align.LEFT; top = false
                    }
                    text.textAlign = align
                    canvas.drawText(p.name, x, if (top) y - fm.ascent else y - fm.descent, text)
                }
                if (showWon) {
                    val skip = (14 - p.won.size) / 2
                    p.won.forEachIndexed { i, c -> card(canvas, c, true, st.loc[0] + (skip + i) * st.d[0], st.loc[1] + (skip + i) * st.d[1]) }
                    return@forEach
                }
                p.hand.forEachIndexed { slot, c ->
                    if (c.used || slot * 4 + p.id >= dealt) return@forEachIndexed
                    val x = st.loc[0] + slot * st.d[0]
                    val y = st.loc[1] + slot * st.d[1]
                    if (p.id == 0) card(canvas, c, true, x, y - if (c.sel) l.pop else 0f, c.flash)
                    else card(canvas, c, false, x, y)
                }
                // Everyone else's picks show as little white dots beside their cards
                val d = st.dot
                if (p.id != 0 && d != null && (mode == Mode.SELECTING || mode == Mode.DEALING)) {
                    val size = 2 * density
                    p.hand.forEachIndexed { slot, c ->
                        if (c.sel && slot * 4 + p.id < dealt) {
                            val x = d[0] + slot * st.d[0]
                            val y = d[1] + slot * st.d[1]
                            canvas.drawRect(x, y, x + size, y + size, dot)
                        }
                    }
                }
            }
            if (led >= 0) {
                for (i in 0 until 4) {
                    val id = (led + i) % 4
                    trick[id]?.let { card(canvas, it, true, l.seats[id].play[0], l.seats[id].play[1]) }
                }
            }
            fly.forEach { card(canvas, it.c, true, it.x, it.y) }
        }
    }

    // ---------------------------------------------------------------- the computer players

    private fun v(c: Card) = if (c.r == 1) 14 else c.r
    private fun isQS(c: Card) = c.s == 3 && c.r == 12
    private fun points(c: Card) = if (c.s == 2) 1 else if (isQS(c)) 13 else 0
    private fun inHand(p: Player) = p.hand.filter { !it.used }

    private val ai = object {
        /** Three cards to pass: the queen of spades (and her escorts when spades are short), high hearts, and short suits. */
        fun pass(p: Player): List<Card> {
            val hand = p.hand.toList()
            if (moonHand(hand)) return hand.sortedBy { v(it) }.take(3)
            fun count(s: Int) = hand.count { it.s == s }
            val guards = hand.count { it.s == 3 && it.r > 1 && it.r < 12 }
            fun danger(c: Card): Int {
                var d = v(c)
                if (isQS(c)) d += if (guards >= 4) 2 else 40
                else if (c.s == 3 && v(c) > 12) d += if (guards >= 4) 0 else 24
                else if (c.s == 3) d -= 8
                if (c.s == 2) d += if (v(c) > 10) 8 else 2
                if (c.s < 2 && count(c.s) <= 2) d += 7
                if (c.s == 0 && c.r == 2) d -= 20
                return d
            }
            return hand.sortedByDescending { danger(it) }.take(3)
        }

        /** A hand strong enough to try taking every point. */
        fun moonHand(hand: List<Card>): Boolean {
            val hearts = hand.filter { it.s == 2 }
            return hearts.size >= 6 && hearts.count { v(it) >= 11 } >= 3 && hand.count { v(it) >= 12 } >= 6
        }

        fun play(p: Player): Card {
            val legal = legal(p)
            if (legal.size == 1) return legal[0]
            fun low(arr: List<Card>) = arr.reduce { a, b -> if (v(b) < v(a)) b else a }
            fun high(arr: List<Card>) = arr.reduce { a, b -> if (v(b) > v(a)) b else a }
            val hand = inHand(p)
            // Cards of a suit nobody has played yet and that aren't in this hand
            fun out(s: Int) = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1)
                .filter { r -> !gone.contains(s * 13 + r) && hand.none { it.s == s && it.r == r } }
                .map { if (it == 1) 14 else it }
            if (p.shoot && moon.shooter >= 0 && (moon.shooter != p.id || !moon.risk)) p.shoot = false
            val shooter = if (moon.risk && moon.shooter >= 0 && moon.shooter != p.id && players[moon.shooter].won.size >= 5) moon.shooter else -1
            val holdsQS = hand.any { isQS(it) }

            if (turn == led) {
                if (p.shoot) {
                    val boss = legal.filter { c -> out(c.s).none { it > v(c) } }
                    return high(boss.ifEmpty { legal })
                }
                // Flush the queen out with a spade she has to beat
                val spades = legal.filter { it.s == 3 }
                if (!qs && !holdsQS && spades.isNotEmpty() && spades.all { v(it) < 12 }) return high(spades)
                fun risk(c: Card): Float {
                    val o = out(c.s)
                    val above = o.count { it > v(c) }
                    val below = o.size - above
                    var r = if (above > 0) below.toFloat() / o.size else 2f
                    if (c.s == 3 && (holdsQS || (!qs && v(c) > 12))) r += 2
                    if (c.s == 2) r += 0.3f
                    return r + v(c) / 100f
                }
                return legal.minBy { risk(it) }
            }

            val ledCard = trick[led]!!
            val played = trick.filterNotNull()
            val winning = high(played.filter { it.s == ledCard.s })
            val leader = trick.indexOf(winning)
            val last = played.size == 3
            if (legal[0].s == ledCard.s) {
                val under = legal.filter { v(it) < v(winning) }
                val over = legal.filter { v(it) > v(winning) }
                if (p.shoot) return if (over.isNotEmpty()) (if (last) low(over) else high(over)) else low(legal)
                if (shooter >= 0 && leader == shooter && over.isNotEmpty() && (played.any { points(it) > 0 } || ledCard.s == 2)) return if (last) low(over) else high(over)
                if (under.isNotEmpty()) return under.find { isQS(it) } ?: high(under)
                val noQ = legal.filter { !isQS(it) }
                if (last) return high(noQ.ifEmpty { legal })
                return low(noQ.ifEmpty { legal })
            }
            // Can't follow suit: throw something away
            val safe = legal.filter { points(it) == 0 }
            if (p.shoot) return low(safe.ifEmpty { legal })
            if (shooter >= 0 && leader == shooter && safe.isNotEmpty()) return high(safe)
            legal.find { isQS(it) }?.let { return it }
            if (!qs) {
                val big = legal.filter { it.s == 3 && v(it) > 12 }
                if (big.isNotEmpty()) return high(big)
            }
            val hearts = legal.filter { it.s == 2 }
            if (hearts.isNotEmpty()) return high(hearts)
            fun count(s: Int) = hand.count { it.s == s }
            return legal.minBy { 2 * count(it.s) - v(it) }
        }
    }

    companion object {
        private const val PREFS = "HeartsPrefs"
        private const val GREEN = 0xFF008000.toInt()
        private val NAMES = listOf("Pauline", "Michele", "Ben")
        private val SUITS = listOf("club", "diamond", "heart", "spade")
        private val PLACES = listOf("First Place", "Second Place", "Third Place", "Last Place")

        /** Seats pass to the player this many seats around: left, right, across. */
        private val OFFSET = intArrayOf(1, 3, 2, 0)
    }
}
