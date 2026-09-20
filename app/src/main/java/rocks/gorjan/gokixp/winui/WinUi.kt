package rocks.gorjan.gokixp.winui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.FontManager
import rocks.gorjan.gokixp.theme.FontStyle
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * The common controls, for programs this launcher draws itself.
 *
 * Windows's own programs were not four programs. `calc.exe` is one binary; what changes
 * between 98 and 7 is the theme the common controls draw themselves with, and the program
 * asks for "a push button" without knowing or caring which shell is answering. This is that
 * arrangement, rebuilt: a program says [button], [field], [listBody], [groupBox], and the
 * four shells come out of the one call.
 *
 * It is deliberately not a view hierarchy or a layout system - the programs build their own
 * out of `LinearLayout`, the way the rest of this app does. What lives here is only the part
 * that would otherwise be copied four times and then drift: the colours, the font, the edges,
 * and the handful of composite controls (a menu bar, a list header, a status bar) that every
 * Windows program has and no two of ours should draw differently.
 *
 * Created per window, from the theme in force when the window opened. A theme change closes
 * the windows, so nothing here has to survive one.
 */
class WinUi(
    val context: Context,
    val theme: AppTheme = ThemeManager(context).getSelectedTheme()
) {
    val pal: WinPalette = WinPalette.of(theme)
    val density: Float = context.resources.displayMetrics.density
    private val fonts = FontManager(context)

    val isClassic: Boolean get() = theme is AppTheme.WindowsClassic
    val isXp: Boolean get() = theme is AppTheme.WindowsXP
    val isAero: Boolean get() = theme.isAero
    val isWin7: Boolean get() = theme is AppTheme.Windows7

    // ---------------------------------------------------------------- measures

    fun dp(value: Number): Int = (value.toFloat() * density + 0.5f).toInt()

    /**
     * The size body text is at, in sp.
     *
     * Not the same number in every shell: 98 set MS Sans Serif 8pt on a 96-dpi screen, XP
     * kept Tahoma at 8pt, and the Aero shells moved to Segoe UI at 9pt, which is a
     * noticeably bigger face. Getting this wrong is the single most obvious tell.
     */
    val textSp: Float get() = if (isAero) 12f else 11f

    val smallSp: Float get() = if (isAero) 11f else 10f

    val titleSp: Float get() = if (isAero) 15f else 13f

    /** The height of one row in a list. Aero's lists are roomier than 98's. */
    val rowHeightDp: Int get() = if (isAero) 22 else 18

    fun font(bold: Boolean = false): Typeface? =
        fonts.getFontForTheme(theme, if (bold) FontStyle.Bold else FontStyle.Normal)

    fun applyFont(view: TextView, bold: Boolean = false) {
        view.typeface = font(bold)
    }

    // ---------------------------------------------------------------- surfaces

    /** The dialog face: what a window's background is, in every one of these shells. */
    fun facePanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(pal.face)
    }

    /**
     * The push button.
     *
     * Classic gets the 9x bevel and goes in when pressed. XP gets Luna's rounded capsule.
     * Vista gets the Aero gradient. Windows 7 gets the genuine article - the nine-patches
     * pulled out of `aero.msstyles`, which are already in the drawables for the start menu
     * and are the same control.
     */
    fun buttonBackground(): Drawable {
        if (isWin7) {
            ContextCompat.getDrawable(context, R.drawable.button_win7_states)?.let { return it }
        }
        if (isClassic) {
            return StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    BevelDrawable(pal, density, BevelDrawable.Style.PRESSED)
                )
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    BevelDrawable(pal, density, BevelDrawable.Style.PRESSED)
                )
                addState(
                    intArrayOf(),
                    BevelDrawable(pal, density, BevelDrawable.Style.RAISED)
                )
            }
        }
        fun face(top: Int, bottom: Int) = GradientPanelDrawable(
            top, bottom, pal.buttonBorder, density, pal.radiusDp, gloss = isAero
        )
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                face(pal.buttonPressedTop, pal.buttonPressedBottom)
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                face(pal.buttonPressedTop, pal.buttonPressedBottom)
            )
            addState(intArrayOf(), face(pal.buttonTop, pal.buttonBottom))
        }
    }

    /**
     * A push button with a word on it.
     *
     * 75x23 is not a round number picked for looks: it is 50x14 dialog units at 8 point,
     * which is what every OK button in Windows has measured since 1995.
     */
    fun button(
        text: String,
        widthDp: Int = 75,
        heightDp: Int = 23,
        onClick: (() -> Unit)? = null
    ): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(pal.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        applyFont(this)
        background = buttonBackground()
        isClickable = onClick != null
        isFocusable = onClick != null
        setPadding(dp(6), 0, dp(6), 0)
        layoutParams = LinearLayout.LayoutParams(
            if (widthDp <= 0) ViewGroup.LayoutParams.WRAP_CONTENT else dp(widthDp),
            dp(heightDp)
        )
        onClick?.let { action -> setOnClickListener { action() } }
    }

    /** Greys a button out, the way a disabled command reads in every shell. */
    fun setEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.isClickable = enabled
        button.setTextColor(if (enabled) pal.text else pal.grayText)
        button.alpha = if (enabled || isClassic) 1f else 0.6f
    }

    /** The edge an edit field or a list box is sunk behind. */
    fun fieldBackground(fill: Int = pal.window, focused: Boolean = false): Drawable =
        if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.SUNKEN, fill)
        else GradientPanelDrawable(
            fill, fill,
            if (focused) pal.fieldFocusBorder else pal.fieldBorder,
            density,
            if (isXp) 0f else 1f
        )

    /** A single-line edit field. */
    fun field(hint: String = "", numeric: Boolean = false): EditText = EditText(context).apply {
        background = fieldBackground()
        setTextColor(pal.text)
        setHintTextColor(pal.grayText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        applyFont(this)
        this.hint = hint
        isSingleLine = true
        setPadding(dp(4), dp(3), dp(4), dp(3))
        minHeight = dp(if (isAero) 22 else 20)
        if (numeric) inputType = InputType.TYPE_CLASS_PHONE
        // The system's own blinking bar rather than a themed one: a caret is the one part
        // of a text field the platform draws better than a reconstruction would.
        highlightColor = pal.select
    }

    /** The white body a list, a tree or a message pane sits in. */
    fun listBody(): FrameLayout = FrameLayout(context).apply {
        background = fieldBackground()
        val edge = if (isClassic) dp(2) else dp(1)
        setPadding(edge, edge, edge, edge)
    }

    /**
     * One row of a list, at rest, hovered or selected.
     *
     * Classic and XP fill the row flat in the highlight colour and turn the text white;
     * Vista and 7 draw Explorer's rounded blue wash and leave the text black. Both are
     * right for their shell, and the difference is why [rowTextColor] exists alongside.
     */
    fun rowBackground(selected: Boolean, pressed: Boolean = false): Drawable = when {
        selected && isClassic -> BevelDrawable(pal, density, BevelDrawable.Style.FLAT, pal.select)
        selected && isXp -> BevelDrawable(pal, density, BevelDrawable.Style.FLAT, pal.select)
        selected -> GradientPanelDrawable(
            pal.select, pal.selectEnd, pal.selectBorder, density, 2f, gloss = false
        )
        pressed && isAero -> GradientPanelDrawable(
            pal.hover, pal.hoverEnd, pal.hoverBorder, density, 2f, gloss = false
        )
        else -> BevelDrawable(pal, density, BevelDrawable.Style.FLAT, Color.TRANSPARENT)
    }

    fun rowTextColor(selected: Boolean): Int =
        if (selected) pal.selectText else pal.text

    /**
     * A row that reacts to being touched, rather than one drawn in a fixed state.
     *
     * [rowBackground] answers "what does a selected row look like", which is what an
     * adapter binding a list of them needs. A row that is simply *in* a list needs the
     * other thing: nothing at rest, the shell's own highlight under a finger. Handing it
     * `rowBackground(selected = false, pressed = true)` gets a row that looks permanently
     * hovered, which is the mistake this exists to stop.
     */
    fun rowSelector(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rowBackground(selected = true))
        addState(
            intArrayOf(android.R.attr.state_selected),
            rowBackground(selected = true)
        )
        addState(
            intArrayOf(android.R.attr.state_hovered),
            rowBackground(selected = false, pressed = true)
        )
        addState(
            intArrayOf(),
            BevelDrawable(pal, density, BevelDrawable.Style.FLAT, Color.TRANSPARENT)
        )
    }

    /**
     * A group box: the frame that boxes a set of related controls, with its caption
     * sitting in a gap in the top line.
     *
     * Drawn by the shell's own skin - see [WinGroupPanel] - rather than assembled out of
     * views. A label with the dialog's background behind it is the usual shortcut and it
     * is wrong: Windows leaves an actual gap in the line, and the painted-over version
     * shows its seams the moment the surface behind the caption is not exactly the face
     * colour.
     *
     * Returns the frame; [GroupBox.body] is where the contents go.
     */
    class GroupBox(val frame: WinGroupPanel, val body: LinearLayout) {
        var title: String
            get() = frame.title
            set(value) {
                frame.title = value
            }
    }

    fun groupBox(title: String): GroupBox {
        val frame = WinGroupPanel(context, theme, density, title)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(18), dp(9), dp(9))
        }
        frame.addView(
            body,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return GroupBox(frame, body)
    }

    /** A plain caption. */
    fun label(
        text: String,
        bold: Boolean = false,
        size: Float = textSp,
        color: Int = pal.text
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        applyFont(this, bold)
    }

    /** A horizontal rule, etched on Classic and a hairline on the rest. */
    fun separator(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (isClassic) dp(2) else dp(1)
        )
        background =
            if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.ETCHED, Color.TRANSPARENT)
            else GradientPanelDrawable(pal.etch, pal.etch, Color.TRANSPARENT, density, 0f)
    }

    // ---------------------------------------------------------------- chrome

    /** One drop-down on a menu bar: its word, and what is under it when it is opened. */
    class Menu(val title: String, val items: () -> List<ContextMenuItem>)

    /**
     * The menu bar: File, Edit, View, Help.
     *
     * Opens its own drop-downs, keeps the word lit while one is down, and moves the menu
     * straight across when another word is pressed - see [WinMenuTracker]. A program that
     * would rather host the menu itself, in the launcher's own
     * [rocks.gorjan.gokixp.ContextMenuView], passes [onShow] and gets only the strip of words.
     */
    fun menuBar(
        menus: List<Menu>,
        onShow: ((List<ContextMenuItem>, Float, Float) -> Unit)? = null,
        onSound: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = when {
            isClassic -> BevelDrawable(pal, density, BevelDrawable.Style.FLAT, pal.face)
            else -> GradientPanelDrawable(
                pal.toolbarTop, pal.toolbarBottom, pal.etch, density, 0f,
                sides = GradientPanelDrawable.Sides.BOTTOM
            )
        }
        val tracker = if (onShow == null) WinMenuTracker(this@WinUi) else null
        for (menu in menus) {
            addView(
                label(menu.title).apply {
                    text = accelerated(menu.title)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    isClickable = true
                    setOnClickListener { view ->
                        onSound?.invoke()
                        if (tracker != null) {
                            tracker.toggle(view as TextView, menu.items())
                        } else {
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            onShow!!(menu.items(), at[0].toFloat(), (at[1] + view.height).toFloat())
                        }
                    }
                }
            )
        }
    }

    /** The strip buttons and boxes sit on above a window's contents. */
    fun toolbar(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = when {
            isClassic -> BevelDrawable(pal, density, BevelDrawable.Style.FLAT, pal.face)
            else -> GradientPanelDrawable(
                pal.toolbarTop, pal.toolbarBottom, pal.etch, density, 0f,
                sides = GradientPanelDrawable.Sides.BOTTOM
            )
        }
    }

    /**
     * A flat toolbar button: an icon, a word, or both.
     *
     * Flat until it is touched, which is what a toolbar button has done since Internet
     * Explorer 3 taught the shell the trick. Classic raises a bevel under the finger; the
     * later shells light up instead.
     */
    fun toolButton(
        text: String? = null,
        iconRes: Int? = null,
        iconSizeDp: Int = 16,
        onClick: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(3), dp(6), dp(3))
        background = StateListDrawable().apply {
            val hot: Drawable =
                if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.RAISED)
                else GradientPanelDrawable(
                    pal.hover, pal.hoverEnd, pal.hoverBorder, density, 2f
                )
            val down: Drawable =
                if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.PRESSED)
                else GradientPanelDrawable(
                    pal.buttonPressedTop, pal.buttonPressedBottom, pal.hoverBorder, density, 2f
                )
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(android.R.attr.state_selected), down)
            addState(intArrayOf(android.R.attr.state_hovered), hot)
            addState(
                intArrayOf(),
                BevelDrawable(pal, density, BevelDrawable.Style.FLAT, Color.TRANSPARENT)
            )
        }
        iconRes?.let { res ->
            addView(
                ImageView(context).apply {
                    setImageResource(res)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)).apply {
                    rightMargin = if (text != null) dp(4) else 0
                }
            )
        }
        text?.let { addView(label(it)) }
        isClickable = onClick != null
        onClick?.let { action -> setOnClickListener { action() } }
    }

    /** The heading strip above a list's columns. */
    fun columnHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background =
            if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.FLAT, pal.face)
            else GradientPanelDrawable(
                pal.headerTop, pal.headerBottom, pal.headerLine, density, 0f,
                sides = GradientPanelDrawable.Sides.BOTTOM
            )
    }

    /** One column heading, which on Classic is a little raised button of its own. */
    fun columnLabel(text: String, weight: Float = 1f): TextView = label(text).apply {
        setPadding(dp(5), dp(3), dp(5), dp(3))
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (isClassic) background = BevelDrawable(pal, density, BevelDrawable.Style.RAISED)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    /**
     * The status bar along the bottom, divided into panes.
     *
     * Classic sinks each pane into its own little well; the later shells run one flat strip
     * with hairlines between. Returns the panes in the order asked for, so a program can
     * write into them by index.
     */
    class StatusBar(val bar: LinearLayout, val panes: List<TextView>)

    fun statusBar(weights: List<Float>): StatusBar {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.face)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val panes = weights.map { weight ->
            label("", size = smallSp).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(2), dp(4), dp(2))
                background =
                    if (isClassic) BevelDrawable(pal, density, BevelDrawable.Style.SUNKEN, pal.face)
                    else GradientPanelDrawable(pal.face, pal.face, pal.etch, density, 0f)
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                        rightMargin = dp(2)
                    }
            }
        }
        panes.forEach { bar.addView(it) }
        return StatusBar(bar, panes)
    }

    /**
     * A tab control.
     *
     * The real one: [WinTabStrip], which draws itself through the shell's skin so a tab
     * here is the same control as a tab on the Display Properties sheet. This is only the
     * handle onto it, kept because a program wants to say `tabs.page` and `tabs.onSelect`
     * rather than reach into a view.
     */
    class Tabs(val strip: WinTabStrip) {
        val root: View get() = strip
        val page: FrameLayout get() = strip.page
        val selected: Int get() = strip.selected
        var onSelect: ((Int) -> Unit)?
            get() = strip.onSelect
            set(value) {
                strip.onSelect = value
            }
    }

    fun tabs(titles: List<String>): Tabs =
        Tabs(WinTabStrip(context, theme, density).apply { setTabs(titles) })

    fun selectTab(tabs: Tabs, index: Int, notify: Boolean = true) {
        tabs.strip.select(index, notify)
    }

    // ---------------------------------------------------------------- scrolling

    /** A vertical scroller with this shell's own scrollbar on it. */
    fun scroller(): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        ThemeManager(context).applyThemedScrollbars(this, theme)
    }

    /** Puts this shell's scrollbar on a list that is already built. */
    fun themeScrollbars(view: View) {
        ThemeManager(context).applyThemedScrollbars(view, theme)
    }

    // ---------------------------------------------------------------- odds and ends

    /**
     * A round face for a person, or their initials where there is no picture.
     *
     * Windows did not have one of these - a contact in Outlook Express was a line of text -
     * but a phone book without faces is unusable on a screen this size, and every shell from
     * XP onwards did draw round user pictures on the log-on screen and the start menu. The
     * frame follows the shell: a square bevel on 98, XP's rounded blue frame, Aero's ring.
     */
    fun avatarBackground(): Drawable = when {
        isClassic -> BevelDrawable(pal, density, BevelDrawable.Style.SUNKEN, pal.window)
        isXp -> GradientPanelDrawable(pal.window, pal.window, pal.buttonBorder, density, 2f)
        else -> GradientPanelDrawable(pal.window, pal.light, pal.fieldBorder, density, 24f)
    }

    /** A block of colour with one line of white text on it - a heading, a banner. */
    fun banner(text: String): TextView = label(text, bold = true, size = titleSp).apply {
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = when {
            isClassic -> GradientPanelDrawable(
                0xFF000080.toInt(), 0xFF1084D0.toInt(), Color.TRANSPARENT, density, 0f
            )
            isXp -> GradientPanelDrawable(
                0xFF3A6EA5.toInt(), 0xFF6D9BD1.toInt(), Color.TRANSPARENT, density, 0f
            )
            else -> GradientPanelDrawable(
                pal.toolbarTop, pal.toolbarBottom, pal.etch, density, 0f,
                sides = GradientPanelDrawable.Sides.BOTTOM
            )
        }
        setTextColor(if (isAero) pal.text else Color.WHITE)
    }

    /**
     * Two drawables stacked, for putting a focus rectangle over a row.
     *
     * A convenience because every list in every one of these programs needs it and
     * `LayerDrawable` is three lines of ceremony each time.
     */
    fun withFocus(base: Drawable): Drawable =
        LayerDrawable(arrayOf(base, FocusRectDrawable(density)))
}
