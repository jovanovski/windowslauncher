package rocks.gorjan.gokixp.winui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import rocks.gorjan.gokixp.theme.AppTheme

/**
 * A window frame, for the windows the desktop cannot draw.
 *
 * Nearly every program in this launcher is a [rocks.gorjan.gokixp.WindowsDialog] floating
 * on the desktop, and that class draws the title bar, runs the taskbar button and talks to
 * the window manager. None of which exists outside the launcher's own activity - and two
 * things genuinely live outside it: a call that arrives over the lock screen, and an alarm
 * that goes off while the phone is asleep. Both have to be a window with a title bar all
 * the same, or they are a black screen with buttons on it.
 *
 * So this is the frame and nothing else: a caption, an icon, a close box and a body, in
 * the four shells' colours. It has no taskbar, cannot be dragged and does not know what a
 * z-order is, because none of those mean anything on a screen with one window on it.
 */
class WinWindow(
    context: Context,
    private val ui: WinUi,
    title: String,
    iconRes: Int? = null,
    /** Shown only when there is something for it to do. */
    onClose: (() -> Unit)? = null
) : LinearLayout(context) {

    /** Where the window's contents go. */
    val body = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(ui.pal.face)
    }

    private val caption = TextView(context)

    init {
        orientation = VERTICAL
        background = frameBackground()
        val edge = ui.dp(if (ui.isClassic) 3 else 1)
        setPadding(edge, edge, edge, edge)

        addView(titleBar(title, iconRes, onClose), LayoutParams(MATCH, WRAP))
        addView(body, LayoutParams(MATCH, 0, 1f))
    }

    fun setTitle(text: String) {
        caption.text = text
    }

    /**
     * The border around the whole window.
     *
     * A raised bevel on Classic, which is what a 98 window's sizing border is; a flat
     * coloured edge on XP; and a grey frame on the Aero shells, which is as close as a
     * child of an ordinary activity can get to glass - real Aero is composited by the
     * window manager and there is none of that here.
     */
    private fun frameBackground() = when {
        ui.isClassic -> BevelDrawable(ui.pal, ui.density, BevelDrawable.Style.RAISED)
        ui.isXp -> GradientPanelDrawable(
            XP_FRAME, XP_FRAME, XP_FRAME, ui.density, 0f
        )
        else -> GradientPanelDrawable(
            AERO_FRAME, AERO_FRAME_END, 0xFF4A5C6B.toInt(), ui.density, 6f
        )
    }

    private fun titleBar(title: String, iconRes: Int?, onClose: (() -> Unit)?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = captionBackground()
            val pad = ui.dp(if (ui.isClassic) 2 else 4)
            setPadding(ui.dp(4), pad, ui.dp(2), pad)

            iconRes?.let { res ->
                addView(
                    ImageView(context).apply { setImageResource(res) },
                    LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(4) }
                )
            }

            caption.apply {
                text = title
                setTextColor(captionTextColor())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSp)
                typeface = ui.font(bold = true)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (!ui.isAero) setShadowLayer(1f, 1f, 1f, 0x66000000)
            }
            addView(caption, LayoutParams(0, WRAP, 1f))

            onClose?.let { action ->
                addView(closeBox(action), LayoutParams(ui.dp(18), ui.dp(16)))
            }
        }

    private fun captionBackground() = when {
        ui.isClassic -> GradientPanelDrawable(
            0xFF000080.toInt(), 0xFF1084D0.toInt(), Color.TRANSPARENT, ui.density, 0f
        )
        ui.isXp -> GradientPanelDrawable(
            0xFF0F63D6.toInt(), 0xFF2F87EA.toInt(), Color.TRANSPARENT, ui.density, 0f
        )
        else -> GradientPanelDrawable(
            0xFFE8F1FB.toInt(), 0xFFCFE0F2.toInt(), Color.TRANSPARENT, ui.density, 0f
        )
    }

    private fun captionTextColor(): Int = if (ui.isAero) 0xFF1F2F3F.toInt() else Color.WHITE

    /**
     * The close box.
     *
     * Drawn rather than taken from the drawables, because the art in there is cut for the
     * desktop's own title bars and is the wrong height for this one in three of the four
     * shells. An X on a red or grey square is little enough to draw.
     */
    private fun closeBox(action: () -> Unit): View = TextView(context).apply {
        text = "✕"
        gravity = Gravity.CENTER
        setTextColor(if (ui.isClassic) ui.pal.text else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.smallSp)
        typeface = ui.font(bold = true)
        background = when {
            ui.isClassic -> BevelDrawable(ui.pal, ui.density, BevelDrawable.Style.RAISED)
            else -> GradientPanelDrawable(
                0xFFE04343.toInt(), 0xFFB81C1C.toInt(), 0xFF8E1616.toInt(),
                ui.density, 2f, gloss = true
            )
        }
        isClickable = true
        setOnClickListener { action() }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val XP_FRAME = 0xFF0F63D6.toInt()
        private val AERO_FRAME = 0xFFB8C9D8.toInt()
        private val AERO_FRAME_END = 0xFF9FB2C4.toInt()
    }
}

/**
 * The desktop behind a window that has no desktop.
 *
 * A call over the lock screen has to stand on something, and the honest something is the
 * shell's own wallpaper colour rather than black. Each shell's is its default flat
 * background - the colour a machine shows before a picture is set - which is the one
 * choice here that is not the user's, because their wallpaper is a file this activity has
 * no business reading while the phone is locked.
 */
fun backdropColor(theme: AppTheme): Int = when (theme) {
    AppTheme.WindowsClassic -> 0xFF008080.toInt()
    AppTheme.WindowsXP -> 0xFF3A6EA5.toInt()
    AppTheme.WindowsVista -> 0xFF1B3A5C.toInt()
    AppTheme.Windows7 -> 0xFF1A2B44.toInt()
}
