package rocks.gorjan.gokixp.winui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import rocks.gorjan.gokixp.ContextMenuItem

/**
 * The drop-downs a menu bar opens.
 *
 * [WinUi.menuBar] draws the strip of words; this is what happens when one is pressed, and it
 * is the half that makes a menu bar a menu bar rather than a row of buttons: the word stays
 * lit while its menu is down, pressing another word moves the menu straight there without a
 * second tap, and pressing the lit word again puts it away.
 *
 * A menu is a [PopupWindow] rather than a view in the program's own layout, so it is free to
 * hang outside the window it belongs to - which is what a menu near the bottom of a small
 * window has to do.
 */

/** Underlines the letter after `&`, and drops the `&`. */
internal fun accelerated(raw: String): CharSequence {
    val at = raw.indexOf('&')
    if (at < 0 || at == raw.length - 1) return raw.replace("&&", "&")
    val stripped = raw.substring(0, at) + raw.substring(at + 1)
    return SpannableString(stripped).apply {
        setSpan(UnderlineSpan(), at, at + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** The tick beside a checked command, and the arrow beside one that opens a submenu. */
private class GlyphDrawable(
    private val density: Float,
    private val color: Int,
    private val arrow: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@GlyphDrawable.color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = density
        val path = Path()
        if (arrow) {
            path.moveTo(cx - 1.5f * u, cy - 3.5f * u)
            path.lineTo(cx + 2.5f * u, cy)
            path.lineTo(cx - 1.5f * u, cy + 3.5f * u)
            path.close()
            canvas.drawPath(path, paint)
            return
        }
        // A tick with square ends, the way every Windows menu has drawn one.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * u
        path.moveTo(cx - 3.5f * u, cy)
        path.lineTo(cx - 1f * u, cy + 2.6f * u)
        path.lineTo(cx + 3.5f * u, cy - 2.8f * u)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A menu's own background: the face and edge of the shell, and - from XP on - the pale strip
 * down the left that the ticks and icons sit in.
 */
private class MenuBackgroundDrawable(
    private val ui: WinUi,
) : Drawable() {

    private val fill = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = hair(ui.density) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = hair(ui.density)
        if (ui.isClassic) {
            fill.color = ui.pal.face
            canvas.drawRect(b, fill)
            // The 9x menu is a raised panel, same edge as a button.
            fill.color = ui.pal.hilight
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right - h, b.top + h, fill)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + h, b.bottom - h, fill)
            fill.color = ui.pal.shadow
            canvas.drawRect(b.left + h, b.bottom - 2 * h, b.right - h, b.bottom - h, fill)
            canvas.drawRect(b.right - 2 * h, b.top + h, b.right - h, b.bottom - h, fill)
            fill.color = ui.pal.dkShadow
            canvas.drawRect(b.left.toFloat(), b.bottom - h, b.right.toFloat(), b.bottom.toFloat(), fill)
            canvas.drawRect(b.right - h, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fill)
            return
        }
        fill.color = ui.pal.window
        canvas.drawRect(b, fill)
        fill.color = if (ui.isXp) ui.pal.face else 0xFFF1F1F1.toInt()
        canvas.drawRect(
            b.left.toFloat(), b.top.toFloat(),
            (b.left + ui.dp(WinMenuMetrics.GUTTER_DP)).toFloat(), b.bottom.toFloat(), fill,
        )
        line.color = ui.pal.shadow
        canvas.drawRect(b.left + h / 2, b.top + h / 2, b.right - h / 2, b.bottom - h / 2, line)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.OPAQUE
}

internal object WinMenuMetrics {
    /** The strip a tick sits in, and how far a command's text starts from the left. */
    const val GUTTER_DP = 22

    /** Room kept on the right for a shortcut, so two menus' commands line up. */
    const val SHORTCUT_GAP_DP = 18
}

/**
 * One open menu. Built fresh each time it is shown, because the items it lists are too -
 * a File menu's "Undo" is greyed or not depending on what happened a moment ago.
 */
class WinMenuPopup(private val ui: WinUi) {

    private var window: PopupWindow? = null
    private var child: WinMenuPopup? = null

    val isShowing: Boolean get() = window?.isShowing == true

    /** Shows [items] under [anchor] (a menu-bar word) or beside it (a submenu row). */
    fun show(
        anchor: View,
        items: List<ContextMenuItem>,
        toTheSide: Boolean = false,
        onDismiss: (() -> Unit)? = null,
    ) {
        dismiss()
        val body = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            background = MenuBackgroundDrawable(ui)
            val edge = if (ui.isClassic) ui.dp(3) else ui.dp(2)
            setPadding(edge, edge, edge, edge)
        }
        for (item in items) body.addView(rowFor(item))

        window = PopupWindow(body, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = if (ui.isClassic) 0f else 4f * ui.density
            setBackgroundDrawable(null)
            setOnDismissListener {
                child?.dismiss()
                onDismiss?.invoke()
            }
            if (toTheSide) showAsDropDown(anchor, anchor.width - ui.dp(4), -anchor.height)
            else showAsDropDown(anchor, 0, 0)
        }
    }

    fun dismiss() {
        child?.dismiss()
        child = null
        window?.dismiss()
        window = null
    }

    private fun rowFor(item: ContextMenuItem): View {
        if (item.isSeparator) {
            return View(ui.context).apply {
                background = ui.separator().background
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (ui.isClassic) ui.dp(2) else ui.dp(1),
                ).apply {
                    val side = if (ui.isClassic) ui.dp(2) else ui.dp(WinMenuMetrics.GUTTER_DP)
                    leftMargin = side
                    rightMargin = ui.dp(2)
                    topMargin = ui.dp(3)
                    bottomMargin = ui.dp(3)
                }
            }
        }

        val enabled = item.isEnabled
        val textColor = if (enabled) ui.pal.text else ui.pal.grayText
        val row = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(ui.rowHeightDp + 2)
            isClickable = enabled
            background = if (enabled) ui.rowSelector() else null
        }

        val tick = FrameLayout(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ui.dp(WinMenuMetrics.GUTTER_DP), ViewGroup.LayoutParams.MATCH_PARENT,
            )
            if (item.hasCheckbox && item.isChecked) {
                addView(
                    View(ui.context).apply { background = GlyphDrawable(ui.density, textColor, arrow = false) },
                    FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.CENTER),
                )
            }
        }
        row.addView(tick)

        row.addView(
            TextView(ui.context).apply {
                text = accelerated(item.title)
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ui.textSp)
                ui.applyFont(this)
                isSingleLine = true
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        item.shortcut?.let { keys ->
            row.addView(
                TextView(ui.context).apply {
                    text = keys
                    setTextColor(ui.pal.grayText)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ui.textSp)
                    ui.applyFont(this)
                    isSingleLine = true
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = ui.dp(WinMenuMetrics.SHORTCUT_GAP_DP) },
            )
        }

        val arrow = View(ui.context).apply {
            if (item.opensSubmenu) background = GlyphDrawable(ui.density, textColor, arrow = true)
        }
        row.addView(arrow, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply {
            rightMargin = ui.dp(2)
        })

        if (enabled) {
            // A highlighted command turns white on navy in 9x and XP, so the words on the row
            // have to follow the bar the selector paints under them.
            val hot = ui.pal.selectText
            val cold = textColor
            val words = listOf(row.getChildAt(1) as TextView) +
                listOfNotNull(row.getChildAt(2) as? TextView)
            row.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> words.forEach { it.setTextColor(hot) }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> words.forEach { it.setTextColor(cold) }
                }
                v.onTouchEvent(event)
            }
            row.setOnClickListener {
                val sub = item.submenu
                if (!sub.isNullOrEmpty()) {
                    child?.dismiss()
                    child = WinMenuPopup(ui).also { it.show(row, sub, toTheSide = true) }
                    return@setOnClickListener
                }
                // A command closes every menu above it, not just the one it was on.
                dismiss()
                onPicked?.invoke()
                item.action?.invoke()
            }
        }
        return row
    }

    /** Set by the bar so a command can put the whole chain away and unlight the word. */
    var onPicked: (() -> Unit)? = null
}

/**
 * Keeps one menu bar's words and drop-downs in step: which word is lit, which menu is down,
 * and putting both away when a command is chosen or the user taps elsewhere.
 */
class WinMenuTracker(private val ui: WinUi) {

    private val popup = WinMenuPopup(ui)
    private var openWord: TextView? = null
    private var openPlain: Drawable? = null

    init {
        popup.onPicked = { close() }
    }

    fun toggle(word: TextView, items: List<ContextMenuItem>) {
        if (openWord === word && popup.isShowing) {
            close()
            return
        }
        close()
        openWord = word
        openPlain = word.background
        word.setBackgroundColor(ui.pal.select)
        word.setTextColor(ui.pal.selectText)
        popup.show(word, items) { restore() }
    }

    private fun close() {
        popup.dismiss()
        restore()
    }

    private fun restore() {
        openWord?.let {
            it.background = openPlain
            it.setTextColor(ui.pal.text)
        }
        openWord = null
    }
}

/**
 * A stand-alone menu on any view - what a program opens on a long press, and what
 * [WinUi.menuBar] opens under a word.
 */
fun WinUi.showMenu(anchor: View, items: List<ContextMenuItem>): WinMenuPopup =
    WinMenuPopup(this).also { menu ->
        menu.onPicked = { menu.dismiss() }
        menu.show(anchor, items)
    }
