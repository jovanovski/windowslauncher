package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * A Windows Phone text prompt.
 *
 * WP8.1 asks for a single value with a dimmed screen, a lowercase heading, one underlined
 * field and a pair of plain text commands along the bottom - no window chrome, no title
 * bar, no OK/Cancel buttons in the Vista sense.
 *
 * Used for renaming a tile, in place of the desktop themes' Vista rename window.
 */
@SuppressLint("ViewConstructor")
class WP81InputDialog(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val heading = TextView(context)
    private val field = EditText(context)
    private val underline = View(context)
    private val acceptButton = TextView(context)
    private val cancelButton = TextView(context)

    private var onAccept: ((String) -> Unit)? = null

    init {
        visibility = GONE
        isClickable = true

        scrim.setBackgroundColor(SCRIM)
        scrim.setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(24), dp(26), dp(24), dp(18))
        panel.isClickable = true

        heading.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        heading.textSize = 28f
        heading.includeFontPadding = false
        panel.addView(heading, wide())

        field.setSingleLine()
        field.background = null
        field.textSize = 18f
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        field.setPadding(0, dp(16), 0, dp(6))
        panel.addView(field, wide())

        panel.addView(underline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))

        val commands = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        for ((button, label) in listOf(acceptButton to "done", cancelButton to "cancel")) {
            button.text = label
            button.textSize = 16f
            button.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            button.setPadding(dp(18), dp(16), dp(6), dp(4))
            button.isClickable = true
            TiltEffect.apply(button)
        }
        acceptButton.setOnClickListener {
            val value = field.text.toString().trim()
            val callback = onAccept
            dismiss()
            callback?.invoke(value)
        }
        cancelButton.setOnClickListener { dismiss() }
        commands.addView(cancelButton)
        commands.addView(acceptButton)
        panel.addView(commands, wide())

        addView(panel, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        applyPalette(palette)
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun show(title: String, initial: String, onAccept: (String) -> Unit) {
        this.onAccept = onAccept
        heading.text = title.lowercase()
        field.setText(initial)
        field.setSelection(field.text.length)

        visibility = VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(140).start()

        panel.translationY = dp(24).toFloat()
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f)
            .setDuration(200).setInterpolator(DecelerateInterpolator()).start()

        field.requestFocus()
        field.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(field.windowToken ?: windowToken, 0)
        field.clearFocus()
        onAccept = null
        animate().alpha(0f).setDuration(120).withEndAction {
            visibility = GONE
            alpha = 1f
        }.start()
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    fun applyPalette(p: WP81Palette) {
        palette = p
        panel.setBackgroundColor(p.background)
        heading.setTextColor(p.foreground)
        p.applyToField(field)
        underline.setBackgroundColor(p.accent)
        acceptButton.setTextColor(p.accent)
        cancelButton.setTextColor(p.foregroundSubtle)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCRIM = 0xCC000000.toInt()
    }
}
