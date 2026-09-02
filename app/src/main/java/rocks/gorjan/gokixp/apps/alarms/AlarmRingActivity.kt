package rocks.gorjan.gokixp.apps.alarms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen an alarm goes off on.
 *
 * An activity of its own, for the same reason the call screen is one: an alarm arrives
 * when the launcher is not what anybody is looking at, and usually when the phone is
 * locked and face down. Only an activity can be put in front of that - see
 * [setShowWhenLocked] - and this is the shell's answer to the one screen it cannot draw
 * inside its own window.
 *
 * It holds nothing. [AlarmRingService] is what is ringing; this reads it, shows it, and
 * hands snooze and dismiss straight back. If the service stops for any reason - the
 * notification's own buttons, ten minutes going by with nobody answering - this closes
 * itself, because a dismiss screen for an alarm that is no longer going off is a button
 * that does nothing.
 */
class AlarmRingActivity : Activity() {

    private lateinit var palette: WP81Palette
    private lateinit var clock: TextView
    private lateinit var label: TextView

    /** The snooze key, kept so the countdown - which cannot be snoozed - can drop it. */
    private var snooze: View? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showTime()
            ticker.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Over the lock screen, and it lights the phone to get there. An alarm that waited
        // to be unlocked before it showed anything would be a phone making a noise with no
        // way to stop it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        palette = WP81Palette.from(ThemeManager(this))
        setContentView(build())
        bind()

        AlarmRingService.onChanged = { bind() }
    }

    /**
     * The page: the time it is, what went off, and the two things that can be done about it.
     *
     * Laid out the way this shell lays out a page with one thing on it - the fact large and
     * light in the upper half, the commands along the bottom where a thumb is - rather than
     * as a dialog. It is the whole screen, so it should look like a screen.
     */
    private fun build(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }

        clock = TextView(this).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
            textSize = 82f
            includeFontPadding = false
            setTextColor(palette.foreground)
        }
        page.addView(clock, wide())

        label = TextView(this).apply {
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
            textSize = 15f
            letterSpacing = 0.08f
            setTextColor(palette.accent)
            setPadding(0, dp(10), 0, 0)
        }
        page.addView(label, wide())

        // The gap that pushes the commands to the foot of the screen. A view rather than a
        // gravity, so the two commands sit exactly where a thumb expects them whatever the
        // screen is.
        page.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Side by side, stop on the left. A hand reaching for a phone that is going off
        // is reaching for the button that makes it quiet, and that is the one that should
        // be under the thumb first; snooze is the deliberate second choice beside it.
        val commands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        commands.addView(command("stop") {
            AlarmRingService.send(this, AlarmRingService.ACTION_DISMISS)
            finishAndRemoveTask()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        commands.addView(
            command("snooze") {
                AlarmRingService.send(this, AlarmRingService.ACTION_SNOOZE)
                finishAndRemoveTask()
            }.also { snooze = it },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) }
        )
        page.addView(commands, wide())

        return page
    }

    /**
     * One of the two big keys.
     *
     * The platform's own button: a rectangle of nothing with a two-pixel edge and a
     * lowercase word in it. Full width here because there are two of them and they are the
     * only things on the lower half of the screen - a phone being answered in the dark
     * should not require aim.
     */
    private fun command(text: String, onTap: () -> Unit): View = TextView(this).apply {
        this.text = text
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(palette.foreground)
        setPadding(0, dp(18), 0, dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), palette.foreground)
        }
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
    }

    /** What is ringing, or nothing - in which case there is no reason to be on screen. */
    private fun bind() {
        val ringing = AlarmRingService.current
        if (ringing == null) {
            finishAndRemoveTask()
            return
        }
        label.text = if (ringing.isCountdown) "COUNTDOWN FINISHED" else ringing.label.uppercase()
        // A countdown has run out; there is nothing left to put off for three minutes, so
        // the key goes and stop takes the row to itself.
        snooze?.visibility = if (ringing.isCountdown) View.GONE else View.VISIBLE
        showTime()
    }

    private fun showTime() {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        clock.text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    override fun onResume() {
        super.onResume()
        AlarmRingService.onChanged = { bind() }
        bind()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 1_000L)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /**
     * A volume key stops the alarm.
     *
     * The one thing every hand already knows to do with a phone that is making a noise it
     * wants to stop, and on an alarm screen there is nothing else those keys could
     * usefully mean: turning the alarm down while it is going off is adjusting the volume
     * of the next one, which is not what anybody is reaching for at six in the morning.
     *
     * Taken on the way down and the matching release swallowed with it, so a long press
     * does not fall through to the volume panel of an alarm that has already stopped.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val volume = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
        if (!volume) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && !event.isCanceled) {
            AlarmRingService.send(this, AlarmRingService.ACTION_DISMISS)
            finishAndRemoveTask()
        }
        return true
    }

    /**
     * Back does not dismiss an alarm.
     *
     * The same rule the call screen keeps: an alarm is stopped by saying so, and a stray
     * press of the key at the side of a phone being picked up off a bedside table is not
     * saying so. The screen goes away and the alarm carries on ringing, which is what a
     * phone put back down should do.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tick)
        // The listener holds this activity, and the field it is in is static and outlives
        // it. There is only ever one of these - the activity is singleInstance - so this
        // going away means no screen is up, and the service has nobody to tell.
        AlarmRingService.onChanged = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /**
         * How the service and the notification both open this.
         *
         * Its own task, kept out of recents: an alarm is not somewhere to go back to
         * afterwards, and a dead ring screen sitting in the switcher is litter.
         */
        fun intentFor(context: Context): Intent =
            Intent(context, AlarmRingActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
    }
}
