package rocks.gorjan.gokixp.winui

import android.app.Activity
import android.content.ContextWrapper
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
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.MenuAnimator

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
    private val gutterDp: Int = WinMenuMetrics.GUTTER_DP,
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
            (b.left + ui.dp(gutterDp)).toFloat(), b.bottom.toFloat(), fill,
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

    /** The wider strip of a menu of programs, which holds each one's icon. */
    const val ICON_GUTTER_DP = 28

    /** How wide every menu of programs is. */
    const val PROGRAMS_WIDTH_DP = 180

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

    // The menu this one is a submenu of, and the row it was opened from
    private var parent: WinMenuPopup? = null
    private var anchorView: View? = null
    private var body: View? = null

    // Where a menu beside its row sits, and what keeps it there while the row moves
    private var placedX = 0
    private var placedY = 0
    private var follow: ViewTreeObserver.OnPreDrawListener? = null

    /**
     * Whether a tap outside this menu, on a row beside the one it opened from, goes on to that
     * row - so tapping the next Start menu folder opens it rather than only closing this one.
     * Submenus always pass a tap on their parent menu's rows along.
     */
    var passTapsToSiblings: Boolean = false

    val isShowing: Boolean get() = window?.isShowing == true

    /**
     * Shows [items] under [anchor] (a menu-bar word) or beside it (a submenu row, or a Start
     * menu folder). Beside means to the right, or to the left when the right has no room for
     * it, top level with the row and moved up as far as it needs to fit on the screen.
     */
    fun show(
        anchor: View,
        items: List<ContextMenuItem>,
        toTheSide: Boolean = false,
        onDismiss: (() -> Unit)? = null,
    ) {
        dismiss()
        anchorView = anchor
        val withIcons = items.any { it.icon != null }
        val body = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            background = MenuBackgroundDrawable(
                ui, if (withIcons) WinMenuMetrics.ICON_GUTTER_DP else WinMenuMetrics.GUTTER_DP,
            )
            val edge = if (ui.isClassic) ui.dp(3) else ui.dp(2)
            setPadding(edge, edge, edge, edge)
        }
        for (item in items) body.addView(rowFor(item, withIcons))

        // As wide as its longest command, not as wide as the screen will let it be: every row
        // is match-parent with a weighted label, which a wrap-content popup otherwise stretches.
        // A menu of programs is the exception: every one of those is the same width, as the
        // Start menu's were, and a name too long for it ends in an ellipsis.
        val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val width = if (withIcons) {
            View.MeasureSpec.makeMeasureSpec(ui.dp(WinMenuMetrics.PROGRAMS_WIDTH_DP), View.MeasureSpec.EXACTLY)
        } else unbounded
        body.measure(width, unbounded)
        this.body = body

        window = PopupWindow(body, body.measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = if (ui.isClassic) 0f else 4f * ui.density
            setBackgroundDrawable(null)
            // XP and Aero fade the whole window in and out; 9x slides the menu out of the bar
            // (or out of the row, for a submenu) below, and drops it with no animation.
            animationStyle = if (ui.isClassic) 0 else R.style.MenuFadeAnimation
            setOnDismissListener {
                stopFollowing()
                child?.dismiss()
                onDismiss?.invoke()
            }
            // A tap outside the menu closes it, and - since the menu otherwise swallows that
            // tap - is handed on to the row it landed on, if that is one worth handing it to.
            setTouchInterceptor { _, event ->
                val outside = event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE ||
                    (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                        (event.x < 0 || event.y < 0 || event.x >= body.width || event.y >= body.height))
                if (outside) tappedOutside(event.rawX, event.rawY)
                outside
            }
            var toTheLeft = false
            if (toTheSide) {
                // Placed on the activity's own window rather than the anchor's, which for a
                // submenu is the small popup it hangs from.
                val screen = activityRoot(anchor)
                val (x, y, left) = besideAnchor(anchor, body)
                toTheLeft = left
                placedX = x
                placedY = y
                showAtLocation(screen, Gravity.NO_GRAVITY, x, y)
                // A menu hanging off a row in the activity (not off another menu) moves with
                // it - as when the keyboard goes away under the Start menu and it drops - and
                // takes its submenus along.
                if (parent == null) {
                    follow = ViewTreeObserver.OnPreDrawListener {
                        val (nx, ny, _) = besideAnchor(anchor, body)
                        if (nx != placedX || ny != placedY) moveBy(nx - placedX, ny - placedY)
                        true
                    }.also { anchor.viewTreeObserver.addOnPreDrawListener(it) }
                }
            } else {
                showAsDropDown(anchor, 0, 0)
            }
            if (ui.isClassic) {
                MenuAnimator.show(
                    body,
                    ui.theme,
                    when {
                        toTheLeft -> MenuAnimator.Direction.LEFT
                        toTheSide -> MenuAnimator.Direction.RIGHT
                        isAboveAnchor -> MenuAnimator.Direction.UP
                        else -> MenuAnimator.Direction.DOWN
                    },
                )
            }
        }
    }

    /** Where a menu goes beside [anchor]: x, y on the activity's window, and whether it went left. */
    private fun besideAnchor(anchor: View, body: View): Triple<Int, Int, Boolean> {
        val screen = activityRoot(anchor)
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        val origin = IntArray(2)
        screen.getLocationOnScreen(origin)
        val left = at[0] - origin[0]
        val top = at[1] - origin[1]
        val overlap = ui.dp(4)
        val right = left + anchor.width - overlap
        val toTheLeft = right + body.measuredWidth > screen.width
        val x = if (toTheLeft) (left + overlap - body.measuredWidth).coerceAtLeast(0) else right
        val y = top.coerceAtMost(screen.height - body.measuredHeight).coerceAtLeast(0)
        return Triple(x, y, toTheLeft)
    }

    /** Moves this menu and every submenu open off it by the same amount. */
    private fun moveBy(dx: Int, dy: Int) {
        val w = window ?: return
        placedX += dx
        placedY += dy
        w.update(placedX, placedY, -1, -1)
        child?.moveBy(dx, dy)
    }

    private fun stopFollowing() {
        val listener = follow ?: return
        follow = null
        anchorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
    }

    /** The menu row under a point on the screen, if the point is on this menu at all. */
    private fun rowAt(x: Float, y: Float): View? {
        val menu = body as? ViewGroup ?: return null
        val at = IntArray(2)
        for (i in 0 until menu.childCount) {
            val row = menu.getChildAt(i)
            row.getLocationOnScreen(at)
            if (x >= at[0] && x < at[0] + row.width && y >= at[1] && y < at[1] + row.height) return row
        }
        return null
    }

    private fun contains(x: Float, y: Float): Boolean {
        val menu = body ?: return false
        val at = IntArray(2)
        menu.getLocationOnScreen(at)
        return x >= at[0] && x < at[0] + menu.width && y >= at[1] && y < at[1] + menu.height
    }

    /**
     * A tap landed outside this menu: it closes, and the tap goes to whatever it landed on
     * below - a row of the menu this one came out of, or with [passTapsToSiblings], a row
     * beside the one this menu opened from. The row that opened it only closes it again.
     */
    private fun tappedOutside(x: Float, y: Float) {
        val opener = anchorView
        val above = parent
        dismiss()
        if (above != null) {
            if (!above.contains(x, y)) {
                above.tappedOutside(x, y)
                return
            }
            above.rowAt(x, y)?.takeIf { it !== opener && it.isClickable }?.performClick()
            return
        }
        if (!passTapsToSiblings) return
        val list = opener?.parent as? ViewGroup ?: return
        val at = IntArray(2)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (row === opener || !row.isClickable) continue
            row.getLocationOnScreen(at)
            if (x >= at[0] && x < at[0] + row.width && y >= at[1] && y < at[1] + row.height) {
                row.performClick()
                return
            }
        }
    }

    private fun activityRoot(view: View): View {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context.window.decorView
            context = context.baseContext
        }
        return view.rootView
    }

    fun dismiss() {
        stopFollowing()
        child?.dismiss()
        child = null
        window?.dismiss()
        window = null
    }

    private fun rowFor(item: ContextMenuItem, withIcons: Boolean): View {
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
            minimumHeight = ui.dp(ui.rowHeightDp + if (withIcons) 10 else 2)
            isClickable = enabled
            background = if (enabled) ui.rowSelector() else null
        }

        val tick = FrameLayout(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ui.dp(if (withIcons) WinMenuMetrics.ICON_GUTTER_DP else WinMenuMetrics.GUTTER_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val icon = item.icon
            if (icon != null) {
                addView(
                    // Its own copy, since the same drawable may be on show in the list too
                    ImageView(ui.context).apply { setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon) },
                    FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER),
                )
            } else if (item.hasCheckbox && item.isChecked) {
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
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (withIcons) leftMargin = ui.dp(4)
            },
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
                    // The row stays lit for as long as what it opened is open
                    row.isSelected = true
                    words.forEach { it.setTextColor(hot) }
                    child = WinMenuPopup(ui).also { menu ->
                        menu.parent = this
                        // A command down there puts this menu away too
                        menu.onPicked = { dismiss(); onPicked?.invoke() }
                        menu.show(row, sub, toTheSide = true) {
                            row.isSelected = false
                            words.forEach { it.setTextColor(cold) }
                        }
                    }
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
