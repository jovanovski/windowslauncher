package rocks.gorjan.gokixp

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.DesktopChrome

/**
 * The wall the launcher puts up when it is still the phone's phone or messaging app under
 * a theme that has neither.
 *
 * Only Windows Phone 8.1 has a People app, a call screen and a conversation view, so it is
 * the only theme under which holding those roles means anything. Held under a desktop
 * theme they are worse than useless: every call and every text message on the device is
 * routed to a shell with nowhere to put it, and a message delivered to this app is a
 * message no other app is given - so one arriving now is one nobody ever sees. See
 * SmsDeliverReceiver. That is not a thing to mention in a notification and let the user
 * walk past, so this covers the launcher entirely and stays until the roles are somewhere
 * they can be answered.
 *
 * Deliberately a plain view rather than a [WindowsDialog]: a window can be moved, closed
 * and minimised, and every one of those is a way past something that is not meant to be
 * got past. It borrows the chrome instead - the same per-theme dialog layout every other
 * window is built from, lifted out of its overlay and with the title bar's three buttons
 * taken out - so it still looks like this launcher's own dialog under XP, Vista and
 * Classic alike.
 *
 * [update] decides what it says; the roles are read from the system rather than
 * remembered, so the answer is always the phone's own. See MainActivity's
 * `enforceDefaultAppRoles`, which owns this view's whole life.
 */
class DefaultAppsGate(
    context: Context,
    private val theme: AppTheme,
    private val onChoosePhoneApp: () -> Unit,
    private val onChooseMessagingApp: () -> Unit,
    private val onReturnToPhoneTheme: () -> Unit
) : FrameLayout(context) {

    private val windowFrame: LinearLayout
    private val body: TextView
    private val dialerButton: TextView
    private val smsButton: TextView
    private val problem: TextView

    init {
        // Opaque enough that the desktop underneath reads as out of reach rather than as
        // something to try tapping through.
        setBackgroundColor(Color.parseColor("#E6000000"))
        isClickable = true
        isFocusable = true

        val density = resources.displayMetrics.density
        val margin = (16 * density).toInt()
        setPadding(0, margin, 0, margin)

        val themeManager = (context as? MainActivity)?.themeManager
        val chromeRes = themeManager?.getDialogLayoutRes(theme) ?: when (theme.chrome) {
            DesktopChrome.CLASSIC -> R.layout.windows_dialog_content_98
            DesktopChrome.XP -> R.layout.windows_dialog_content_xp
            DesktopChrome.VISTA -> R.layout.windows_dialog_content_vista
        }

        // The chrome comes wrapped in a full-screen overlay that a floating window is
        // positioned inside by hand. This one never moves, so the window is lifted out of
        // that overlay and centred by a scroller instead - which is also what keeps the
        // whole of it reachable on a short screen, where the words and three buttons are
        // taller than there is room for.
        val overlay = LayoutInflater.from(context)
            .inflate(chromeRes, this, false) as ViewGroup
        windowFrame = overlay.findViewById(R.id.window_frame)
        overlay.removeView(windowFrame)

        val scroller = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        addView(
            scroller,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        scroller.addView(
            windowFrame,
            FrameLayout.LayoutParams(
                windowWidthFor(resources.displayMetrics.widthPixels),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )

        // The three ways out of a window, taken away. Gone rather than disabled: a close
        // button that does nothing is a bug report waiting to happen.
        windowFrame.findViewById<View>(R.id.dialog_close_button)?.visibility = View.GONE
        windowFrame.findViewById<View>(R.id.dialog_minimize_button)?.visibility = View.GONE
        windowFrame.findViewById<View>(R.id.dialog_maximize_button)?.visibility = View.GONE
        windowFrame.findViewById<View>(R.id.dialog_window_icon)?.visibility = View.GONE
        windowFrame.findViewById<TextView>(R.id.dialog_title_text)?.text = "Default apps"

        val content = windowFrame.findViewById<LinearLayout>(R.id.dialog_content_area)
        LayoutInflater.from(context).inflate(R.layout.default_apps_gate, content, true)

        content.findViewById<ImageView>(R.id.gate_icon).setImageResource(
            when (theme.chrome) {
                DesktopChrome.CLASSIC -> R.drawable.dialog_warning_98
                DesktopChrome.XP -> R.drawable.dialog_warning_xp
                DesktopChrome.VISTA -> R.drawable.dialog_warning_vista
            }
        )

        body = content.findViewById(R.id.gate_body)
        dialerButton = content.findViewById(R.id.gate_dialer_button)
        smsButton = content.findViewById(R.id.gate_sms_button)
        problem = content.findViewById(R.id.gate_problem)

        dialerButton.setOnClickListener {
            problem.visibility = View.GONE
            onChoosePhoneApp()
        }
        smsButton.setOnClickListener {
            problem.visibility = View.GONE
            onChooseMessagingApp()
        }
        content.findViewById<TextView>(R.id.gate_theme_button)
            .setOnClickListener { onReturnToPhoneTheme() }
    }

    /**
     * Says which of the two roles is still here, and offers a way to each.
     *
     * Both buttons are shown only when both roles are held: a phone that has already been
     * handed its dialler back should not be told to go and do it again, and the row that
     * is left is then the whole of what is being asked for.
     */
    fun update(dialerHeld: Boolean, smsHeld: Boolean) {
        val what = when {
            dialerHeld && smsHeld -> "this phone's phone app and its messaging app"
            dialerHeld -> "this phone's phone app"
            else -> "this phone's messaging app"
        }
        val them = if (dialerHeld && smsHeld) "them" else "it"
        body.text = "Windows Launcher is still $what, and $theme has no screen to answer a " +
            "call on or to read a text message in. Calls and messages arriving now reach an " +
            "app that cannot show them.\n\n" +
            "Give $them back to another app, or go back to Windows Phone 8, which has both. " +
            "Until then there is nothing here to use."
        dialerButton.visibility = if (dialerHeld) View.VISIBLE else View.GONE
        smsButton.visibility = if (smsHeld) View.VISIBLE else View.GONE
    }

    /**
     * Says so when a button could not do what it offered.
     *
     * Said here rather than in a notification bubble, which is a view of the launcher's -
     * and the launcher is behind this one, where nothing it puts up can be seen.
     */
    fun showProblem(message: String) {
        problem.text = message
        problem.visibility = View.VISIBLE
    }

    /**
     * Re-fits the window to the screen it is actually on.
     *
     * The launcher handles rotation itself rather than being recreated for it - see
     * MainActivity's configChanges - so a window sized once on the way up would still be
     * landscape-wide after the phone was turned upright.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val wanted = windowWidthFor(w)
        val params = windowFrame.layoutParams
        if (params.width == wanted) return
        params.width = wanted
        windowFrame.layoutParams = params
    }

    /** Wide enough to read, narrow enough to leave the screen a margin either side. */
    private fun windowWidthFor(available: Int): Int = minOf(
        (available * 0.92f).toInt(),
        (400 * resources.displayMetrics.density).toInt()
    )

    /**
     * Swallows everything, including the taps that land beside the window.
     *
     * The window in the middle is the only thing on screen meant to answer a touch, and it
     * has its own listeners; anything reaching this far is aimed at the launcher behind.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}

