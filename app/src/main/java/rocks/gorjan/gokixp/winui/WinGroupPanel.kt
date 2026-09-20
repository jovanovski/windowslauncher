package rocks.gorjan.gokixp.winui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.FrameLayout
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.winui.dialog.WinCaption
import rocks.gorjan.gokixp.winui.dialog.WinEdges
import rocks.gorjan.gokixp.winui.dialog.WinSkin

/**
 * A group box, in a window that can be resized.
 *
 * The frame and the caption are drawn by [WinSkin.groupBox] - the same call the Display
 * Properties sheet makes - so there is one place that knows a group box has a *gap* in its
 * top line where the caption sits rather than a caption painted over the line. That gap is
 * the whole tell: fake it by drawing a label with the dialog's background behind it and the
 * line reappears the moment anything is not quite the face colour.
 *
 * The sibling [rocks.gorjan.gokixp.winui.dialog.WinGroupBox] is the same drawing on
 * [rocks.gorjan.gokixp.winui.dialog.WinLayout]'s fixed design-pixel grid, which is right
 * for reproducing a particular dialog and wrong for a program window whose contents have to
 * reflow. This one holds ordinary children and lets them lay themselves out.
 */
@SuppressLint("ViewConstructor")
class WinGroupPanel(
    context: Context,
    theme: AppTheme,
    private val density: Float,
    title: String
) : FrameLayout(context) {

    private val skin: WinSkin = WinSkin.of(theme)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var caption = WinCaption(title)

    init {
        setWillNotDraw(false)
    }

    var title: String
        get() = caption.text
        set(value) {
            caption = WinCaption(value)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val e = WinEdges(canvas, density)
        skin.textPaint(context, density, paint)
        val titleWidth = if (caption.text.isEmpty()) 0f else paint.measureText(caption.text)

        skin.groupBox(e, width.toFloat(), height.toFloat(), titleWidth)
        if (titleWidth <= 0f) return
        skin.caption(
            e, caption, paint,
            e.px(skin.groupTitleX), e.px(skin.groupTitleBaseline),
            isEnabled, skin.groupTitleColor
        )
    }
}
