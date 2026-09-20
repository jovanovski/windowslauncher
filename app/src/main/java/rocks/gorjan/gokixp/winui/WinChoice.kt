package rocks.gorjan.gokixp.winui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton

/**
 * Check boxes and radio buttons.
 *
 * Thirteen pixels square in every shell since 1995, which is why they are specified that way
 * here rather than in anything relative. What changes is the box: 9x sinks it behind a bevel,
 * XP rounds it and draws one flat blue-grey line, Aero rounds it further and lights the inside
 * with a gradient.
 *
 * The tick's own colour is the one place this is eyeballed rather than sourced - 9x and XP draw
 * a black one, and Aero's is a dark blue-green that is approximated here.
 */
private class WinTickDrawable(
    private val ui: WinUi,
    private val radio: Boolean,
    private val checked: Boolean,
    private val enabled: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private val tickColor: Int
        get() = when {
            !enabled -> ui.pal.grayText
            ui.isAero -> 0xFF1F5C87.toInt()
            else -> ui.pal.text
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = hair(ui.density)
        val fill = if (enabled) ui.pal.window else ui.pal.face
        box.set(b.left + h / 2, b.top + h / 2, b.right - h / 2, b.bottom - h / 2)

        if (radio) drawCircle(canvas, fill, h) else drawBox(canvas, fill, h)
        if (!checked) return
        if (radio) {
            paint.style = Paint.Style.FILL
            paint.color = tickColor
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() * 0.19f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f * ui.density
            paint.color = tickColor
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val u = ui.density
            canvas.drawPath(Path().apply {
                moveTo(cx - 3.2f * u, cy - 0.2f * u)
                lineTo(cx - 0.9f * u, cy + 2.4f * u)
                lineTo(cx + 3.3f * u, cy - 2.8f * u)
            }, paint)
        }
    }

    private fun drawBox(canvas: Canvas, fill: Int, h: Float) {
        paint.style = Paint.Style.FILL
        if (ui.isClassic) {
            // The same sunken client edge an edit field sits behind, at 13 px square.
            paint.color = fill
            canvas.drawRect(bounds, paint)
            val b = bounds
            paint.color = ui.pal.shadow
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right - h, b.top + h, paint)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + h, b.bottom - h, paint)
            paint.color = ui.pal.dkShadow
            canvas.drawRect(b.left + h, b.top + h, b.right - 2 * h, b.top + 2 * h, paint)
            canvas.drawRect(b.left + h, b.top + h, b.left + 2 * h, b.bottom - 2 * h, paint)
            paint.color = ui.pal.hilight
            canvas.drawRect(b.left.toFloat(), b.bottom - h, b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawRect(b.right - h, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
            return
        }
        val radius = 2f * ui.density
        paint.color = fill
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h
        paint.color = if (enabled) ui.pal.fieldBorder else ui.pal.shadow
        canvas.drawRoundRect(box, radius, radius, paint)
    }

    private fun drawCircle(canvas: Canvas, fill: Int, h: Float) {
        val b = bounds
        val r = b.width() / 2f - h / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h
        paint.color = when {
            !enabled -> ui.pal.shadow
            ui.isClassic -> ui.pal.dkShadow
            else -> ui.pal.fieldBorder
        }
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
        if (!ui.isClassic) return
        // 9x lights the top-left of the ring and shades the bottom-right, the way it does
        // everything else round.
        paint.color = ui.pal.hilight
        canvas.drawArc(
            RectF(b.left + h, b.top + h, b.right - h, b.bottom - h), 45f, 180f, false, paint,
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = ui.dp(13)
    override fun getIntrinsicHeight() = ui.dp(13)
}

private fun WinUi.tickStates(radio: Boolean): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
        WinTickDrawable(this@tickStates, radio, checked = true, enabled = false),
    )
    addState(
        intArrayOf(-android.R.attr.state_enabled),
        WinTickDrawable(this@tickStates, radio, checked = false, enabled = false),
    )
    addState(
        intArrayOf(android.R.attr.state_checked),
        WinTickDrawable(this@tickStates, radio, checked = true, enabled = true),
    )
    addState(
        intArrayOf(),
        WinTickDrawable(this@tickStates, radio, checked = false, enabled = true),
    )
}

private fun WinUi.dressChoice(button: CompoundButton, text: String) {
    button.text = text
    button.setTextColor(pal.text)
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
    applyFont(button)
    button.buttonDrawable = null
    button.background = null
    button.setButtonDrawable(null)
    button.setPadding(dp(6), dp(2), dp(4), dp(2))
    button.compoundDrawablePadding = dp(6)
    button.minHeight = dp(rowHeightDp)
    button.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

/**
 * The glyph on its own, for a check box or radio button that already exists in a layout file.
 *
 *     checkBox.buttonDrawable = ui.choiceDrawable(radio = false)
 *
 * Which is how a screen built in XML gets a themed box without being rebuilt in code.
 */
fun WinUi.choiceDrawable(radio: Boolean = false): Drawable =
    tickStates(radio).also { it.setBounds(0, 0, dp(13), dp(13)) }

/** A check box: a square that remembers, with its label beside it. */
fun WinUi.checkBox(
    text: String,
    checked: Boolean = false,
    onChange: ((Boolean) -> Unit)? = null,
): CheckBox = CheckBox(context).apply {
    dressChoice(this, text)
    val glyph = tickStates(radio = false)
    glyph.setBounds(0, 0, dp(13), dp(13))
    setCompoundDrawablesRelative(glyph, null, null, null)
    isChecked = checked
    onChange?.let { listener -> setOnCheckedChangeListener { _, on -> listener(on) } }
}

/**
 * A radio button. Android keeps one of a group checked for you as long as they share a
 * `RadioGroup`; nothing here changes that.
 */
fun WinUi.radioButton(
    text: String,
    checked: Boolean = false,
    onChange: ((Boolean) -> Unit)? = null,
): RadioButton = RadioButton(context).apply {
    dressChoice(this, text)
    val glyph = tickStates(radio = true)
    glyph.setBounds(0, 0, dp(13), dp(13))
    setCompoundDrawablesRelative(glyph, null, null, null)
    isChecked = checked
    onChange?.let { listener -> setOnCheckedChangeListener { _, on -> listener(on) } }
}

/** Greys a check box or radio button out, label and all. */
fun WinUi.setChoiceEnabled(button: CompoundButton, enabled: Boolean) {
    button.isEnabled = enabled
    button.setTextColor(if (enabled) pal.text else pal.grayText)
}
