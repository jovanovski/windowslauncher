package rocks.gorjan.gokixp.apps.dialogbox

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager

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
     * Play the appropriate sound based on dialog type
     */
    private fun playDialogSound() {
        val soundResId = when (dialogType) {
            DialogType.ERROR -> R.raw.error_xp
            DialogType.WARNING -> R.raw.warning_xp
            DialogType.INFORMATION -> R.raw.information_xp
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
            }
            DialogType.WARNING -> when (theme) {
                AppTheme.WindowsClassic -> R.drawable.dialog_warning_98
                AppTheme.WindowsXP -> R.drawable.dialog_warning_xp
                AppTheme.WindowsVista -> R.drawable.dialog_warning_vista
            }
            DialogType.ERROR -> when (theme) {
                AppTheme.WindowsClassic -> R.drawable.dialog_error_98
                AppTheme.WindowsXP -> R.drawable.dialog_error_xp
                AppTheme.WindowsVista -> R.drawable.dialog_error_vista
            }
        }
    }
}
