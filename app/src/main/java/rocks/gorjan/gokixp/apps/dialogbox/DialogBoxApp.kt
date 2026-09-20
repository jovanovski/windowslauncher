package rocks.gorjan.gokixp.apps.dialogbox

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.winui.WinUi

/**
 * Dialog type for system dialogs
 */
enum class DialogType {
    INFORMATION,
    WARNING,
    ERROR
}

/**
 * System dialog box app for showing information, warnings, and errors
 */
class DialogBoxApp(
    private val context: Context,
    private val theme: AppTheme,
    private val themeManager: ThemeManager,
    private val dialogType: DialogType,
    private val message: String,
    private val onClose: () -> Unit,
    private val onPlaySound: ((Int) -> Unit)? = null,
    private val showCancelButton: Boolean = false,
    private val onCancel: (() -> Unit)? = null
) {

    /**
     * The kit the message box is dressed from.
     *
     * A message box is not a special kind of window - it is a dialog with a static, an icon
     * and one or two push buttons, which is why `MessageBox` lives in user32 beside every
     * other dialog. So its buttons are the kit's push button rather than a style of their
     * own, and they change shell with everything else.
     */
    private val ui = WinUi(context, theme)

    /**
     * Setup the dialog UI
     */
    fun setupDialog(contentView: View): View {
        // Get references to views
        val dialogIcon = contentView.findViewById<ImageView>(R.id.dialog_icon)
        val dialogMessage = contentView.findViewById<TextView>(R.id.dialog_message)
        val okButton = contentView.findViewById<TextView>(R.id.dialog_ok_button)
        val cancelButton = contentView.findViewById<TextView>(R.id.dialog_cancel_button)

        // Set the icon based on dialog type and theme
        val iconResId = getDialogIcon(dialogType, theme)
        dialogIcon.setImageResource(iconResId)

        // Set the message
        dialogMessage.text = message
        dialogMessage.setTextColor(ui.pal.text)
        dialogMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSp)
        ui.applyFont(dialogMessage)

        dressButton(okButton)
        dressButton(cancelButton)

        // Show/hide cancel button based on configuration
        cancelButton.visibility = if (showCancelButton) View.VISIBLE else View.GONE

        // Play sound based on dialog type
        playDialogSound()

        // Set OK button click listener
        okButton.setOnClickListener {
            onClose()
        }

        // Set Cancel button click listener
        cancelButton.setOnClickListener {
            onCancel?.invoke() ?: onClose()
        }

        return contentView
    }

    /**
     * Turns the layout's plain `TextView` into this shell's push button.
     *
     * Padding goes on after the background and not before: Windows 7's button is a
     * nine-patch, and handing a view a nine-patch replaces whatever padding it had with the
     * bitmap's own.
     */
    private fun dressButton(button: TextView) {
        button.background = ui.buttonBackground()
        button.setPadding(ui.dp(6), 0, ui.dp(6), 0)
        button.minHeight = ui.dp(23)
        button.gravity = Gravity.CENTER
        button.setTextColor(ui.pal.text)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSp)
        ui.applyFont(button)
    }

    /**
     * Play the appropriate sound based on dialog type
     */
    private fun playDialogSound() {
        // Windows 7 has its own; every other theme has always used XP's.
        val win7 = theme is AppTheme.Windows7
        val soundResId = when (dialogType) {
            DialogType.ERROR -> if (win7) R.raw.error_win7 else R.raw.error_xp
            DialogType.WARNING -> if (win7) R.raw.warning_win7 else R.raw.warning_xp
            DialogType.INFORMATION -> if (win7) R.raw.information_win7 else R.raw.information_xp
        }
        onPlaySound?.invoke(soundResId)
    }

    /**
     * Get the title for the dialog based on type
     */
    fun getTitle(): String {
        return when (dialogType) {
            DialogType.INFORMATION -> "Information"
            DialogType.WARNING -> "Warning"
            DialogType.ERROR -> "Error"
        }
    }

    /**
     * Get the icon resource ID for the dialog
     */
    fun getIconResId(): Int {
        return getDialogIcon(dialogType, theme)
    }

    /**
     * Get dialog icon based on type and theme
     */
    private fun getDialogIcon(type: DialogType, theme: AppTheme): Int {
        return when (type) {
            DialogType.INFORMATION -> when (theme) {
                AppTheme.WindowsClassic -> R.drawable.dialog_info_98
                AppTheme.WindowsXP -> R.drawable.dialog_info_xp
                AppTheme.WindowsVista -> R.drawable.dialog_info_vista
                AppTheme.Windows7 -> R.drawable.dialog_info_win7
            }
            DialogType.WARNING -> when (theme) {
                AppTheme.WindowsClassic -> R.drawable.dialog_warning_98
                AppTheme.WindowsXP -> R.drawable.dialog_warning_xp
                AppTheme.WindowsVista -> R.drawable.dialog_warning_vista
                AppTheme.Windows7 -> R.drawable.dialog_warning_win7
            }
            DialogType.ERROR -> when (theme) {
                AppTheme.WindowsClassic -> R.drawable.dialog_error_98
                AppTheme.WindowsXP -> R.drawable.dialog_error_xp
                AppTheme.WindowsVista -> R.drawable.dialog_error_vista
                AppTheme.Windows7 -> R.drawable.dialog_error_win7
            }
        }
    }
}
