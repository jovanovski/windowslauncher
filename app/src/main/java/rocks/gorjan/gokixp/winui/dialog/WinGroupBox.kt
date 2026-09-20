package rocks.gorjan.gokixp.winui.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import rocks.gorjan.gokixp.R

/**
 * A group box: the frame that boxes a set of related controls, with its caption sitting in a
 * gap in the top line.
 *
 * Unlike a Win32 GROUPBOX this one really is the parent of what it frames, so the controls
 * inside are positioned relative to the box. The caption straddles the frame's top line, so
 * the page it sits on must not clip its children.
 */
class WinGroupBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WinLayout(context, attrs) {

    private var caption = WinCaption("")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WinCaptioned)
            caption = WinCaption(a.getString(R.styleable.WinCaptioned_winText) ?: "")
            a.recycle()
        }
    }

    var title: String
        get() = caption.text
        set(value) {
            caption = WinCaption(value)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val unit = unitPx
        val e = WinEdges(canvas, unit)
        skin.textPaint(context, unit, paint)
        val titleWidth = if (caption.text.isEmpty()) 0f else paint.measureText(caption.text)

        skin.groupBox(e, width.toFloat(), height.toFloat(), titleWidth)
        if (titleWidth <= 0f) return
        skin.caption(
            e, caption, paint,
            e.px(skin.groupTitleX), e.px(skin.groupTitleBaseline),
            isEnabled, skin.groupTitleColor,
        )
    }
}
