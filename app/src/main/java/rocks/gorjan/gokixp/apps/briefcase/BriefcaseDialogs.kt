package rocks.gorjan.gokixp.apps.briefcase

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.WindowsDialog

/**
 * The two dialogs the Briefcase needs that no other part of the shell has: the one that
 * connects a phone to a computer, and the one that explains what the folder is for.
 *
 * Built in code rather than in a layout because they have to look right under all four
 * shells, which is what [MainActivity.createThemedWindowsDialog] and the themed button
 * background are for.
 */
object BriefcaseDialogs {

    /**
     * Section 1.2, and section 1.3 behind a link.
     *
     * The code is the way in that is meant to be used: nothing typed into this app opens
     * anything else on the computer. The name and password are there for when there is no
     * computer to hand, and the password is exchanged for a token and forgotten.
     */
    fun showConnect(activity: MainActivity, withPassword: Boolean = false, onConnected: () -> Unit = {}) {
        val briefcase = Briefcase.get(activity)
        val dialog = activity.createThemedWindowsDialog()
        dialog.setTitle("Connect a Phone")
        dialog.setTaskbarIcon(activity.themeManager.getBriefcaseIcon())

        val content = column(activity)

        content.addView(
            hint(
                activity,
                if (withPassword) {
                    "Type the name and password of the account on the computer."
                } else {
                    "On the computer, open My Briefcase and choose Briefcase ▸ Connect a Phone… " +
                        "It shows an address and a code."
                }
            )
        )

        val address = field(activity, briefcase.store.lastHost, "gorjan.rocks")
        address.inputType = InputType.TYPE_TEXT_VARIATION_URI
        content.addView(label(activity, "Address of the computer:"))
        content.addView(address)

        val second: EditText
        val third: EditText?
        if (withPassword) {
            second = field(activity, "", "name")
            content.addView(label(activity, "User name:"))
            content.addView(second)

            third = field(activity, "", "password")
            third.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            content.addView(label(activity, "Password:"))
            content.addView(third)
        } else {
            second = field(activity, "", "B2A9-YKG5")
            second.filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(9))
            content.addView(label(activity, "Connection code:"))
            content.addView(second)
            third = null
        }

        val message = hint(activity, "")
        message.visibility = View.GONE
        content.addView(message)

        val connectButton = button(activity, "Connect")
        val cancelButton = button(activity, "Cancel")
        content.addView(buttons(activity, connectButton, cancelButton))

        val swap = TextView(activity).apply {
            text = if (withPassword) "Use a connection code instead" else "Use a name and password instead"
            setTextColor(Color.parseColor("#0000AA"))
            textSize = 11f
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            typeface = font(activity)
            setPadding(0, dp(activity, 10), 0, 0)
            setOnClickListener {
                activity.playClickSound()
                activity.floatingWindowManager.removeWindow(dialog)
                showConnect(activity, withPassword = !withPassword, onConnected = onConnected)
            }
        }
        content.addView(swap)

        connectButton.setOnClickListener {
            activity.playClickSound()
            val host = address.text.toString().trim()
            if (host.isEmpty()) {
                say(message, "Type the address the computer is showing.")
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            connectButton.alpha = 0.6f
            say(message, "Connecting…")

            val done: (Result<String>) -> Unit = { result ->
                connectButton.isEnabled = true
                connectButton.alpha = 1f
                result.onSuccess { site ->
                    activity.floatingWindowManager.removeWindow(dialog)
                    activity.showBriefcaseConnected(site)
                    onConnected()
                }
                result.onFailure { error -> say(message, error.message ?: "Could not connect.") }
            }

            if (withPassword) {
                briefcase.connectWithPassword(host, second.text.toString().trim(), third?.text?.toString() ?: "", done)
            } else {
                briefcase.connectWithCode(host, second.text.toString(), done)
            }
        }

        cancelButton.setOnClickListener {
            activity.playClickSound()
            activity.floatingWindowManager.removeWindow(dialog)
        }

        dialog.setContentView(content)
        dialog.setWindowSize(290, null)
        dialog.setMinimizable(false)
        activity.contextMenuView()?.let { dialog.setContextMenuView(it) }
        activity.floatingWindowManager.showWindow(dialog)
    }

    /**
     * What the desktop shows the first time somebody opens My Briefcase: what the folder is
     * for, before anything is in it.
     */
    fun showWelcome(activity: MainActivity, onConnect: () -> Unit) {
        val dialog = activity.createThemedWindowsDialog()
        dialog.setTitle("Welcome to the Windows Briefcase")
        dialog.setTaskbarIcon(activity.themeManager.getBriefcaseIcon())

        val content = column(activity)

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(
            ImageView(activity).apply {
                setImageResource(activity.themeManager.getBriefcaseIcon())
                layoutParams = LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)).apply {
                    marginEnd = dp(activity, 10)
                }
            }
        )
        heading.addView(
            TextView(activity).apply {
                text = "My Briefcase keeps files on this phone and on your computer the same."
                setTextColor(Color.BLACK)
                textSize = 12f
                typeface = font(activity)
            }
        )
        content.addView(heading)

        content.addView(
            hint(
                activity,
                "\nA file you put in here turns up in My Briefcase on the computer a moment later, " +
                    "and a file dropped in there turns up here. Share anything to this launcher and it " +
                    "goes straight into the Briefcase.\n\n" +
                    "Connect it to a computer to start."
            )
        )

        val connectButton = button(activity, "Connect…")
        val laterButton = button(activity, "Later")
        content.addView(buttons(activity, connectButton, laterButton))

        connectButton.setOnClickListener {
            activity.playClickSound()
            activity.floatingWindowManager.removeWindow(dialog)
            onConnect()
        }
        laterButton.setOnClickListener {
            activity.playClickSound()
            activity.floatingWindowManager.removeWindow(dialog)
        }

        dialog.setContentView(content)
        dialog.setWindowSize(300, null)
        dialog.setMinimizable(false)
        activity.contextMenuView()?.let { dialog.setContextMenuView(it) }
        activity.floatingWindowManager.showWindow(dialog)
    }

    // ---- The pieces these two are made of ---------------------------------------------------

    private fun column(activity: MainActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
    }

    private fun label(activity: MainActivity, text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 11f
        typeface = font(activity)
        setPadding(0, dp(activity, 8), 0, dp(activity, 3))
    }

    private fun hint(activity: MainActivity, text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 11f
        typeface = font(activity)
    }

    private fun field(activity: MainActivity, value: String, hint: String) = EditText(activity).apply {
        setText(value)
        setHint(hint)
        setTextColor(Color.BLACK)
        setHintTextColor(Color.GRAY)
        highlightColor = Color.parseColor("#7a94f4")
        textSize = 12f
        isSingleLine = true
        setBackgroundResource(R.drawable.win98_edit_text_border)
        setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun button(activity: MainActivity, text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 12f
        typeface = font(activity)
        gravity = Gravity.CENTER
        setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 4))
        background = ContextCompat.getDrawable(activity, activity.getThemedButtonBackground())
        backgroundTintList = null
        isClickable = true
        isFocusable = true
    }

    private fun buttons(activity: MainActivity, vararg views: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(activity, 12), 0, 0)
        views.forEachIndexed { index, view ->
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index < views.size - 1) marginEnd = dp(activity, 8) }
            addView(view)
        }
    }

    private fun say(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    private fun font(activity: MainActivity) =
        ResourcesCompat.getFont(activity, activity.themeManager.getPrimaryFontRes(activity.themeManager.getSelectedTheme()))

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
