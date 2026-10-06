package rocks.gorjan.gokixp.apps.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.delay
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.winui.WinUi

/**
 * What FreeCell and Hearts share, the way winos's `CardGame` object is shared by its card games:
 * the cards themselves, the game's own dialogs, and the tween that slides a card across the table.
 *
 * Suits are numbered the way cards.dll numbers them - clubs, diamonds, hearts, spades - and ranks
 * run ace = 1 to king = 13, so the two games' rules read the same as winos's.
 */

/** The window a card game lives in, as much of it as the game needs to reach. */
interface CardHost {
    /** Opens [content] in a dialog window of its own; returns what closes it. [onClosed] runs once, however it closes. */
    fun openDialog(title: String, content: View, widthDp: Int, onClosed: () -> Unit): () -> Unit
    fun playSound(resId: Int)
    fun setTitle(title: String)
    fun closeWindow()
}

/**
 * The card faces and backs Solitaire already ships - the 98 deck, or Vista's on the Aero shells,
 * as Solitaire picks them - with the back Solitaire's Change Deck chose.
 *
 * [large] swaps the faces for Solitaire's high-visibility style ("Card Style"): one big rank
 * and suit per card, readable on a phone where the 71x96 originals shrink to a thumbnail.
 */
class CardArt(private val context: Context) {
    private val themeManager = ThemeManager(context)
    private val aero = themeManager.isAeroTheme()
    private val classic = themeManager.getSelectedTheme() == AppTheme.WindowsClassic
    var large = false
    private val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = try {
            context.resources.getFont(themeManager.getPrimaryFontRes(themeManager.getSelectedTheme()))
        } catch (e: Exception) {
            null
        }
    }
    private val backIndex = context.getSharedPreferences("SolitarePrefs", Context.MODE_PRIVATE).getInt("cardBack", 1)
    private val cache = HashMap<String, Drawable?>()
    private val clip = Path()
    private val invert = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    )

    private fun drawable(name: String): Drawable? = cache.getOrPut(name) {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) null else context.getDrawable(id)?.mutate()
    }

    private fun face(s: Int, r: Int): Drawable? {
        val suit = SUITS[s]
        return drawable("solitare_${suit}_$r" + if (aero) "_vista" else "")
    }

    /**
     * One card. Its corners are clipped off, as cards.dll leaves them, so an overlapping card
     * shows no specks; [hilite] inverts it, which is how 98 shows the card a click picked up.
     */
    fun draw(canvas: Canvas, s: Int, r: Int, up: Boolean, x: Float, y: Float, w: Float, h: Float, hilite: Boolean = false) {
        val d = (if (up && large) drawable("solitare_card_empty") else if (up) face(s, r) else drawable("solitare_card_back_$backIndex")) ?: return
        val k = w / 71f * if (aero) 4f else 2f
        clip.reset()
        clip.addRoundRect(x, y, x + w, y + h, k, k, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        d.setBounds(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        d.colorFilter = if (hilite) invert else null
        d.draw(canvas)
        d.colorFilter = null
        if (up && large) drawLarge(canvas, s, r, x, y, w, h, hilite)
        canvas.restore()
    }

    /**
     * Solitaire's drawLargeCard, with its pixel offsets turned into fractions of the card's
     * width so the same layout holds on FreeCell's narrower cards: the rank top left, the suit
     * top right, and a big suit along the bottom.
     */
    private fun drawLarge(canvas: Canvas, s: Int, r: Int, x: Float, y: Float, w: Float, h: Float, hilite: Boolean) {
        val red = s == 1 || s == 2
        val ink = if (red) 0xFFFF0000.toInt() else 0xFF000000.toInt()
        rankPaint.color = if (hilite) ink.inv() or 0xFF000000.toInt() else ink
        rankPaint.textSize = w * if (classic) 0.69f else 0.46f
        val pad = w * 0.05f
        var rankX = x + w * 0.20f
        if (r == 10) rankX += w * 0.054f
        if (classic) rankX += w * 0.04f
        canvas.drawText(RANKS[r - 1], rankX, y + pad + w * if (classic) 0.5f else 0.42f, rankPaint)

        val symbol = drawable("solitare_${SUITS[s]}_symbol_large") ?: return
        symbol.colorFilter = if (hilite) invert else null
        val small = w * 0.35f
        val sx = x + w * 0.75f - small / 2f + w * 0.03f
        val sy = y + pad + w * 0.023f
        symbol.setBounds(sx.toInt(), sy.toInt(), (sx + small).toInt(), (sy + small).toInt())
        symbol.draw(canvas)
        val big = w * 0.7f
        val bx = x + (w - big) / 2f
        val by = y + h - big - pad
        symbol.setBounds(bx.toInt(), by.toInt(), (bx + big).toInt(), (by + big).toInt())
        symbol.draw(canvas)
        symbol.colorFilter = null
    }

    companion object {
        val SUITS = listOf("club", "diamond", "heart", "spade")
        private val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        /** The games start in whatever Card Style Solitaire was last left in. */
        fun solitaireLarge(context: Context) =
            context.getSharedPreferences("SolitarePrefs", Context.MODE_PRIVATE).getBoolean("largeCards", false)
    }
}

/**
 * A game's dialogs. While one is up the table ignores touches, as winos's `CardGame.modal` has
 * it; they all go when the game's window does.
 */
class CardModals(private val host: CardHost, val ui: WinUi) {
    private val open = mutableListOf<() -> Unit>()

    val busy: Boolean get() = open.isNotEmpty()

    fun show(title: String, content: View, widthDp: Int, onClosed: () -> Unit = {}): () -> Unit {
        var closer: (() -> Unit)? = null
        var done = false
        val c = host.openDialog(title, content, widthDp) {
            if (done) return@openDialog
            done = true
            open.remove(closer)
            onClosed()
        }
        closer = c
        open.add(c)
        return c
    }

    fun closeAll() {
        open.toList().forEach { it() }
        open.clear()
    }

    /** The padded face a dialog's controls sit on. */
    fun panel(): LinearLayout = ui.facePanel().apply {
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    }

    /** A row of buttons along the bottom, centred, the way MessageBox lays them. */
    fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, ui.dp(12), 0, 0)
        buttons.forEachIndexed { i, b ->
            (b.parent as? ViewGroup)?.removeView(b)
            addView(b, LinearLayout.LayoutParams(b.layoutParams).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
    }

    /** OK and Cancel stacked on the right of the dialog, as Hearts' and FreeCell's options have them. */
    fun sideButtons(main: View, vararg buttons: View): LinearLayout = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, 0, 0)
            buttons.forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(b.layoutParams).apply { if (i > 0) topMargin = ui.dp(6) })
            }
        })
    }

    /**
     * A message box: the text and a row of buttons. [onResult] gets the button's word, or null
     * when the window was closed instead.
     */
    fun message(title: String, text: String, buttons: List<String> = listOf("OK"), widthDp: Int = 280, onResult: (String?) -> Unit = {}) {
        var result: String? = null
        var close: () -> Unit = {}
        val content = panel().apply {
            addView(ui.label(text))
            addView(buttonRow(*buttons.map { word -> ui.button(word) { result = word; close() } }.toTypedArray()))
        }
        close = show(title, content, widthDp) { onResult(result) }
    }
}

/** Runs [step] from 0 to 1 over [ms] milliseconds, a frame at a time. */
suspend fun tween(ms: Float, step: (Float) -> Unit) {
    if (ms <= 0f) { step(1f); return }
    val t0 = SystemClock.uptimeMillis()
    while (true) {
        val t = ((SystemClock.uptimeMillis() - t0) / ms).coerceAtMost(1f)
        step(t)
        if (t >= 1f) return
        delay(16)
    }
}
