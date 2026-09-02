package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.ContactFeed
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The screen a call is on.
 *
 * Windows Phone gave a call the whole display and almost nothing on it: who it is, in type
 * large enough to read across a room, and the one or two things you would do about it. No
 * card, no rounded panel, no picture of a handset - the page *is* the call.
 *
 * Two faces, and which one is showing is decided entirely by what the call is doing. A
 * ringing call asks a question and gets two answers along the bottom; a call in progress
 * gets the row of things you can do to it and a bar to end it. There is no third face,
 * because a call is never in a state that is neither of those.
 *
 * It reads [CallCentre] and nothing else, so it does not care whether it is inside the
 * launcher or on top of a locked phone.
 */
@SuppressLint("ViewConstructor")
class CallScreen(
    context: Context,
    private val palette: WP81Palette,
    /** Called when there is nothing left to show, so the host can get out of the way. */
    private val onFinished: () -> Unit
) : FrameLayout(context) {

    /** The picture, filling the screen behind everything, held well back so type reads. */
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = BACKDROP_ALPHA
        visibility = View.GONE
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP))
    }

    private val status = TextView(context)
    private val name = TextView(context)
    private val detail = TextView(context)
    private val face = FrameLayout(context)
    private val faceInitials = TextView(context)

    /**
     * The silhouette for a caller with neither a picture nor a name.
     *
     * An unknown number has no initials to abbreviate - see PeopleStore.initialsOf, which
     * gives nothing rather than the first two characters of a telephone number - so the
     * square was the accent and nothing else, which reads as a face that failed to load
     * rather than as somebody the phone does not know.
     */
    private val faceGlyph = ImageView(context)
    private val facePhoto = ImageView(context)

    /** Whether the caller has a name to abbreviate. See [showPhoto]. */
    private var hasInitials = false

    /**
     * When the live controls will start answering, in uptime.
     *
     * Answering a call swaps one row of buttons for another *in the same place*, and hang
     * up lands more or less where answer was. A finger still coming down - a second tap, a
     * bounce, a hold that ended a moment late - therefore hung up the call it had just
     * answered. So the controls that arrive are deaf for a moment: long enough to outlast
     * a stray tap, short enough that somebody who meant to hang up straight away can.
     */
    private var guardUntil = 0L

    /** Whether the last draw was of a ringing call, which is what the guard turns on. */
    private var wasRinging = false

    /** The two endings: what a ringing call offers, and what a live one does. */
    private val ringingRow = LinearLayout(context)

    private val liveColumn = object : LinearLayout(context) {
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            // Swallowed rather than passed on: the buttons underneath are the ones being
            // guarded, and a touch that reached any of them would do the thing this exists
            // to prevent. See [guardUntil].
            if (android.os.SystemClock.uptimeMillis() < guardUntil) return true
            return super.dispatchTouchEvent(ev)
        }
    }

    private var speakerButton: CallButton? = null
    private var muteButton: CallButton? = null
    private var holdButton: CallButton? = null

    /** The DTMF pad, while it is up over the call. */
    private var keypad: View? = null

    /** The quick replies, while they are up over a ringing call. */
    private var quickReplies: View? = null

    /** The number typed on that pad, which is only ever shown, never dialled. */
    private val typed = StringBuilder()

    private val ticker = object : Runnable {
        override fun run() {
            paintStatus()
            postDelayed(this, TICK_MS)
        }
    }

    private val onCallsChanged = { bind() }

    /**
     * What the status bar and the gesture bar are covering.
     *
     * The window is edge to edge, which is right for the picture behind everything and
     * wrong for the type in front of it: the elapsed time sat under the clock in the status
     * bar. Held here and added to the page's own margin rather than set once, because the
     * keypad is built later and has to clear the same bars.
     */
    private var barInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    init {
        // Black whatever the theme says, and white on it. A call screen is not part of the
        // shell's light or dark setting - it is the one page that has to be legible in the
        // dark with the phone against your face, and Windows Phone drew it this way under
        // both settings for exactly that reason.
        setBackgroundColor(Color.BLACK)
        addView(backdrop, LayoutParams(MATCH, MATCH))
        addView(column, LayoutParams(MATCH, MATCH))

        status.apply {
            typeface = font(R.font.segoeui_semibold)
            textSize = STATUS_SP
            letterSpacing = STATUS_TRACKING
            setTextColor(palette.accent)
        }
        column.addView(status, wide())

        name.apply {
            typeface = font(R.font.segoeui_light)
            textSize = 40f
            maxLines = 2
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(name, wide())

        detail.apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            maxLines = 1
            setTextColor(SUBTLE)
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(detail, wide())

        buildFace()
        column.addView(face, LinearLayout.LayoutParams(dp(FACE_DP), dp(FACE_DP)).apply {
            topMargin = dp(26)
        })

        // The controls sit at the foot whatever the screen's height, with the space above
        // them - which is the space the name and the face are in.
        column.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))

        ringingRow.orientation = LinearLayout.HORIZONTAL
        column.addView(ringingRow, wide())

        liveColumn.orientation = LinearLayout.VERTICAL
        column.addView(liveColumn, wide())

        buildRinging()
        buildLive()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            barInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            applyInsets()
            insets
        }
    }

    /** Pushes the page in off the bars, leaving the picture behind it running to the edges. */
    private fun applyInsets() {
        val edge = dp(MARGIN_DP)
        for (page in listOfNotNull(column, keypad)) {
            page.setPadding(
                edge + barInsets.left,
                edge + barInsets.top + dp(HEADS_UP_DP),
                edge + barInsets.right,
                edge + barInsets.bottom
            )
        }
    }

    // ---------------------------------------------------------------- who

    private fun buildFace() {
        face.setBackgroundColor(palette.accent)
        faceInitials.apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 44f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        face.addView(faceInitials, LayoutParams(MATCH, MATCH))
        faceGlyph.setImageDrawable(SvgIcon.fromAsset(context, USER_ICON))
        faceGlyph.scaleType = ImageView.ScaleType.FIT_CENTER
        faceGlyph.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        faceGlyph.visibility = View.GONE
        // Inset well inside the square, so it reads as a mark on the tile rather than as a
        // picture of somebody that happens to be a drawing.
        val inset = dp(FACE_DP / 4)
        faceGlyph.setPadding(inset, inset, inset, inset)
        face.addView(faceGlyph, LayoutParams(MATCH, MATCH))
        facePhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        facePhoto.visibility = View.GONE
        face.addView(facePhoto, LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- ringing

    /**
     * Decline and answer across the foot, with a way to say why above them.
     *
     * Answer on the right, which is the side a hand holding a phone reaches first and the
     * side every telephone since the touchscreen has put it on. Decline on the left, and
     * the message button over it rather than beside the two: it belongs to declining - it
     * is declining, with a reason attached - and a third button on that row would make a
     * ringing phone a question with three equal answers.
     *
     * Smaller as well as higher, for the same reason. What it does is the least likely of
     * the three and the only one that is not simply yes or no.
     */
    private fun buildRinging() {
        ringingRow.orientation = LinearLayout.VERTICAL

        // Over the decline button and no wider, so it reads as belonging to it. The empty
        // half on the right is deliberate: nothing may sit over answer.
        val above = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        above.addView(
            wideButton("message", MESSAGE_ICON, fill(), small = true) { showQuickReplies() },
            LinearLayout.LayoutParams(0, dp(SMALL_ACTION_DP), 1f)
        )
        above.addView(View(context), LinearLayout.LayoutParams(0, dp(SMALL_ACTION_DP), 1f))
        ringingRow.addView(above, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = dp(GAP_DP)
        })

        val answers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        answers.addView(
            wideButton("decline", HANGUP_ICON, fill()) { CallCentre.reject() },
            LinearLayout.LayoutParams(0, dp(ACTION_DP), 1f)
        )
        answers.addView(
            wideButton("answer", CALL_ICON, ANSWER_GREEN) {
                // Set here as well as on the swap below, because the two are not the same
                // moment: the call is answered now and the buttons change when Telecom
                // says the call is up, and a tap landing in between should not count.
                guardUntil = android.os.SystemClock.uptimeMillis() + ANSWER_GUARD_MS
                CallCentre.answer()
            },
            LinearLayout.LayoutParams(0, dp(ACTION_DP), 1f).apply {
                marginStart = dp(GAP_DP)
            }
        )
        ringingRow.addView(answers, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /**
     * The four things worth saying to somebody you are not going to answer.
     *
     * Written out rather than typed, because the whole point of them is that they are
     * quicker than answering. Windows Phone shipped a fixed set for exactly this, and a
     * ringing phone is not the moment to compose anything - which is also why there is no
     * "type your own" at the end of the list: that is a keyboard over a call that is still
     * ringing, and by the time it has been used the caller has rung off.
     */
    private fun showQuickReplies() =
        showChoices(QUICK_REPLIES.map { reply ->
            Choice(reply, on = false) { CallCentre.rejectWith(context, reply) }
        })

    /** One line of a command list over the call: what it says, and whether it is in force. */
    private class Choice(val label: String, val on: Boolean, val onPick: () -> Unit)

    /**
     * The shell's command list, over a call.
     *
     * Built here rather than through WP81ContextMenu because this screen is not the
     * launcher's window and does not follow the theme - it is black and white whatever the
     * phone is set to, so that a call looks the same at three in the morning as it does at
     * noon. The behaviour is the app bar's: lines swinging down about their own top edge on
     * a stagger, and anywhere off the list puts it away.
     */
    private fun showChoices(choices: List<Choice>) {
        if (quickReplies != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            isClickable = true
            setBackgroundColor(SCRIM)
            // Anywhere off the list puts it away, which is how every command list in this
            // shell is left.
            setOnClickListener { hideQuickReplies() }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MENU_COLOUR)
            setPadding(0, dp(6), 0, dp(6))
            // Its own click, so a tap on the strip beside a line is not a tap past it.
            isClickable = true
        }
        for (choice in choices) {
            column.addView(TextView(context).apply {
                text = choice.label
                typeface = font(R.font.segoeui_regular)
                textSize = 16f
                // The one in force is in the accent, the way every mode in this shell says
                // it is on. Which is the whole point of the list for the audio: it is read
                // to find out where the sound is going as often as to send it elsewhere.
                setTextColor(if (choice.on) palette.accent else Color.WHITE)
                setPadding(dp(22), dp(14), dp(22), dp(14))
                isClickable = true
                setOnClickListener {
                    Haptics.tap(it)
                    hideQuickReplies()
                    choice.onPick()
                }
                TiltEffect.apply(this)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(column, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = barInsets.bottom
        })

        quickReplies = panel
        addView(panel, LayoutParams(MATCH, MATCH))

        // Each line swings down about its own top edge on a stagger, as the app bar's
        // command list does. It is the same thing one level in.
        for (i in 0 until column.childCount) {
            val row = column.getChildAt(i)
            row.cameraDistance = 8000f * resources.displayMetrics.density
            row.pivotX = 0f
            row.pivotY = 0f
            row.rotationX = -90f
            row.alpha = 0f
            row.animate().rotationX(0f).alpha(1f)
                .setStartDelay(i * REPLY_STAGGER_MS)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Where the sound should go, when there is more than one answer.
     *
     * Two ways out is a switch and the button stays one: tapping it moves between the
     * earpiece and the speaker, which is what it has always done and what a hand reaching
     * for it mid-call expects. Three or more - a headset paired, something plugged in - is
     * a choice, and a switch cannot express a choice, so the same button opens the list
     * instead and marks the one the call is using.
     */
    private fun chooseRoute() {
        val routes = CallCentre.routes()
        if (routes.size <= 2) {
            CallCentre.speaker = !CallCentre.speaker
            return
        }
        showChoices(routes.map { route ->
            Choice(routeName(route), on = route == CallCentre.route) {
                CallCentre.setRoute(route)
            }
        })
    }

    private fun routeName(route: Int): String = when (route) {
        // Their headset's own name where it can be had, and left exactly as its owner
        // typed it - the lowercase in this shell is a rule about the platform's own words,
        // not about somebody's earbuds. See MetroPageHeader.setName, which draws the same
        // line for the same reason.
        android.telecom.CallAudioState.ROUTE_BLUETOOTH ->
            CallCentre.bluetoothName(context) ?: "bluetooth"
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> "headset"
        android.telecom.CallAudioState.ROUTE_SPEAKER -> "speaker"
        // Not "earpiece", which is a word for the part rather than for the thing you do.
        else -> "phone"
    }

    private fun routeIcon(route: Int): String = when (route) {
        android.telecom.CallAudioState.ROUTE_BLUETOOTH -> BLUETOOTH_ICON
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> HEADSET_ICON
        android.telecom.CallAudioState.ROUTE_SPEAKER -> SPEAKER_ICON
        // The handset, for sound going to the part of the phone you hold to your head.
        else -> CALL_ICON
    }

    private fun hideQuickReplies(): Boolean {
        val panel = quickReplies ?: return false
        quickReplies = null
        removeView(panel)
        return true
    }

    // ---------------------------------------------------------------- in call

    private fun buildLive() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        speakerButton = CallButton(SPEAKER_ICON) { chooseRoute() }
        muteButton = CallButton(MUTE_ICON) {
            CallCentre.muted = !CallCentre.muted
        }
        holdButton = CallButton(HOLD_ICON) {
            CallCentre.toggleHold()
        }
        // The keypad is one of the four and is drawn as one: it had no fill behind it,
        // which made it the only glyph on the row floating on the black.
        val keys = CallButton(KEYPAD_ICON) { showKeypad() }
        for ((index, button) in listOf(speakerButton!!, muteButton!!, holdButton!!, keys)
            .withIndex()) {
            row.addView(button.view, LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f).apply {
                if (index > 0) marginStart = dp(GAP_DP)
            })
        }
        liveColumn.addView(row, wide())

        liveColumn.addView(
            wideButton("end call", HANGUP_ICON, palette.accent) { CallCentre.hangUp() },
            LinearLayout.LayoutParams(MATCH, dp(ACTION_DP)).apply { topMargin = dp(GAP_DP) }
        )
    }

    /**
     * A command with its name under it.
     *
     * The in-call buttons are the one place this shell labels its glyphs. Everywhere else a
     * ring on an app bar is enough, because there is time to look; a call is the one screen
     * somebody uses without looking properly, and "hold" written underneath is what stops a
     * hand reaching for mute and hanging up instead.
     */
    private inner class CallButton(icon: String, onTap: () -> Unit) {

        /** Which glyph is on it, so an unchanged one is not decoded again on every tick. */
        private var worn: String = icon

        val view: ImageView = ImageView(context).apply {
            setImageDrawable(SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

        init {
            setOn(false)
        }

        /** Swaps the glyph, for a button that says which of several states it is in. */
        fun setIcon(icon: String) {
            if (icon == worn) return
            worn = icon
            view.setImageDrawable(SvgIcon.fromAsset(context, icon))
        }

        /** Filled with the accent while the mode is on, as the app bar marks its own. */
        fun setOn(on: Boolean) {
            view.setBackgroundColor(if (on) palette.accent else fill())
        }

        fun setEnabled(enabled: Boolean) {
            view.isClickable = enabled
            view.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    private fun wideButton(
        label: String,
        icon: String,
        colour: Int,
        small: Boolean = false,
        onTap: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(colour)
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
        val glyphDp = if (small) SMALL_ACTION_GLYPH_DP else ACTION_GLYPH_DP
        addView(ImageView(context).apply {
            setImageDrawable(SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(glyphDp), dp(glyphDp)))
        addView(TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = if (small) 14f else 17f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    // ---------------------------------------------------------------- keypad

    /**
     * The tones, for the machine at the other end.
     *
     * Over the call rather than instead of it: what is typed here is not a number being
     * dialled, it is a menu being answered, and the call it belongs to has to stay on
     * screen behind it. Closed by the same key that opened it.
     */
    private fun showKeypad() {
        if (keypad != null) return
        typed.setLength(0)
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setPadding(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP))
        }
        val readout = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 34f
            maxLines = 1
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.START
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(14))
        }
        page.addView(readout, wide())

        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (row in DTMF_KEYS) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (digit in row) {
                line.addView(
                    dtmfKey(digit) {
                        CallCentre.dtmf(digit)
                        typed.append(digit)
                        readout.text = typed.toString()
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        setMargins(dp(GAP_DP) / 2, dp(GAP_DP) / 2, dp(GAP_DP) / 2, dp(GAP_DP) / 2)
                    }
                )
            }
            keys.addView(line, wide())
        }
        page.addView(keys, LinearLayout.LayoutParams(MATCH, 0, 1f))

        page.addView(
            wideButton("hide keypad", KEYPAD_ICON, fill()) { hideKeypad() },
            LinearLayout.LayoutParams(MATCH, dp(ACTION_DP))
        )
        page.addView(
            wideButton("end call", HANGUP_ICON, palette.accent) { CallCentre.hangUp() },
            LinearLayout.LayoutParams(MATCH, dp(ACTION_DP)).apply { topMargin = dp(GAP_DP) }
        )

        keypad = page
        addView(page, LayoutParams(MATCH, MATCH))
        applyInsets()
    }

    /**
     * Closes whatever is open over the call, innermost first.
     *
     * What the back key asks. Neither of these is a way out of the call - they are things
     * opened on top of it - so each one is a press of its own and the call itself is never
     * what back ends.
     */
    fun handleBack(): Boolean = hideQuickReplies() || hideKeypad()

    /** Puts the pad away, and says whether there was one. The back key asks this. */
    fun hideKeypad(): Boolean {
        val page = keypad ?: return false
        keypad = null
        removeView(page)
        return true
    }

    private fun dtmfKey(digit: Char, onTap: () -> Unit): View = TextView(context).apply {
        text = digit.toString()
        typeface = font(R.font.segoeui_semilight)
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(fill())
        setPadding(0, dp(14), 0, dp(14))
        isClickable = true
        setOnClickListener {
            Haptics.key(it)
            // Sounded here as well as sent: what CallCentre.dtmf does goes to the network,
            // and without this the caller hears whatever the far end makes of it, which on
            // a menu that is thinking about it is nothing at all.
            DialTones.press(digit)
            onTap()
        }
        TiltEffect.apply(this)
    }

    // ---------------------------------------------------------------- binding

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CallCentre.listen(onCallsChanged)
        bind()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        CallCentre.forget(onCallsChanged)
        removeCallbacks(ticker)
    }

    /** Redraws the screen from whatever the calls are doing now. */
    fun bind() {
        // Nothing left to be about. The service takes a call off the list the moment it
        // disconnects, so this is how a screen learns the call is over.
        val line = CallCentre.primary()
        if (line == null) {
            removeCallbacks(ticker)
            onFinished()
            return
        }
        name.text = line.title
        val initials = PeopleStore.initialsOf(line.name)
        faceInitials.text = initials
        hasInitials = initials.isNotEmpty()
        showPhoto(line.photoUri)

        val ringing = line.stage == CallCentre.Stage.INCOMING
        // The moment the swap happens, which is the moment the guard is for.
        val answered = wasRinging && !ringing
        if (answered) {
            guardUntil = android.os.SystemClock.uptimeMillis() + ANSWER_GUARD_MS
            // Faded in rather than simply appearing, so a row of buttons does not
            // materialise instantly under a finger that is still on its way down.
            liveColumn.alpha = 0f
            liveColumn.animate().alpha(1f).setDuration(ANSWER_FADE_MS).start()
        }
        wasRinging = ringing
        ringingRow.visibility = if (ringing) View.VISIBLE else View.GONE
        liveColumn.visibility = if (ringing) View.GONE else View.VISIBLE

        // The number under the name, unless the name *is* the number - in which case there
        // is nothing to say twice.
        detail.text = if (line.name.isBlank()) "" else line.number
        detail.visibility = if (detail.text.isBlank()) View.GONE else View.VISIBLE

        // The button wears whichever way out the sound is taking, and is filled whenever
        // that is not the earpiece - which is the one route that needs no announcing,
        // because it is the phone held to a head in the ordinary way.
        speakerButton?.setIcon(routeIcon(CallCentre.route))
        speakerButton?.setOn(CallCentre.route != android.telecom.CallAudioState.ROUTE_EARPIECE)
        muteButton?.setOn(CallCentre.muted)
        holdButton?.setOn(line.stage == CallCentre.Stage.HOLDING)
        holdButton?.setEnabled(CallCentre.canHold())

        removeCallbacks(ticker)
        paintStatus()
        // The elapsed time only moves while a call is up, so the clock only runs then.
        if (line.stage == CallCentre.Stage.ACTIVE) postDelayed(ticker, TICK_MS)
        // A pad hanging over a call that has been answered elsewhere, or ended, is a pad
        // over nothing - and replies belong to a call that is still ringing, so they go the
        // moment it stops.
        //
        // On the *transition* rather than on every draw. The command list is shared with
        // the audio routes now, and this runs again for anything the call does - a mute, a
        // route change, a hold - so hiding it unconditionally would shut the list of
        // outputs a moment after opening it.
        if (ringing) hideKeypad() else if (answered) hideQuickReplies()
    }

    /** The line above the name: what the call is doing, or how long it has been doing it. */
    private fun paintStatus() {
        val line = CallCentre.primary() ?: return
        status.text = when (line.stage) {
            CallCentre.Stage.INCOMING -> "incoming call"
            CallCentre.Stage.DIALING -> "calling"
            CallCentre.Stage.HOLDING -> "on hold"
            CallCentre.Stage.ACTIVE -> elapsed(line.connectedAt)
            CallCentre.Stage.ENDED -> "call ended"
        }
    }

    private fun elapsed(since: Long): String {
        if (since <= 0L) return "connected"
        val seconds = ((System.currentTimeMillis() - since) / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60
        return if (minutes >= 60) {
            String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }

    private fun showPhoto(uri: String?) {
        if (uri.isNullOrBlank()) {
            facePhoto.visibility = View.GONE
            // Their letters if the phone knows what to call them, the silhouette if not.
            faceInitials.visibility = if (hasInitials) View.VISIBLE else View.GONE
            faceGlyph.visibility = if (hasInitials) View.GONE else View.VISIBLE
            backdrop.visibility = View.GONE
            return
        }
        ContactFeed.load(context, uri) { bitmap ->
            if (bitmap == null) return@load
            facePhoto.setImageBitmap(bitmap)
            facePhoto.visibility = View.VISIBLE
            faceInitials.visibility = View.GONE
            faceGlyph.visibility = View.GONE
            backdrop.setImageBitmap(bitmap)
            backdrop.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The grey the secondary buttons are drawn on. The calculator's key, over black. */
    private fun fill(): Int = ColorUtils.blendARGB(Color.BLACK, Color.WHITE, FILL_ALPHA)

    private fun font(res: Int) = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val ICON_DIR = "custom_icons_8"
        const val CALL_ICON = "$ICON_DIR/appbar.phone.svg"
        const val HANGUP_ICON = "$ICON_DIR/appbar.phone.hangup.svg"
        const val SPEAKER_ICON = "$ICON_DIR/appbar.speakerphone.svg"

        /** The other ways a call's sound can leave the phone. See routeIcon. */
        const val BLUETOOTH_ICON = "$ICON_DIR/appbar.hardware.headphones.bluetooth.svg"
        const val HEADSET_ICON = "$ICON_DIR/appbar.hardware.headphones.svg"
        const val MUTE_ICON = "$ICON_DIR/appbar.microphone.svg"
        const val HOLD_ICON = "$ICON_DIR/appbar.control.pause.svg"
        const val KEYPAD_ICON = "$ICON_DIR/appbar.dial.svg"
        const val MESSAGE_ICON = "$ICON_DIR/appbar.message.svg"

        const val MARGIN_DP = 24

        /**
         * How far down the page starts, on top of the status bar.
         *
         * The call's own notification arrives as a heads-up - it has to, since that is what
         * gets a ringing phone in front of somebody - and it comes down over the top of
         * this screen. Which put it squarely over the name and the timer, the two things
         * the screen exists to show. There is no way to ask how tall it is, so this is the
         * room a full call notification takes with its buttons out, and the page begins
         * under it.
         */
        const val HEADS_UP_DP = 128
        const val GAP_DP = 8
        /**
         * How long the live controls ignore touches after a call is answered.
         *
         * A second: past the end of any tap that was already happening, and short enough
         * that hanging up immediately - which people do, on a wrong number - still works.
         */
        const val ANSWER_GUARD_MS = 1000L

        /** How long the arriving controls take to fade in. */
        const val ANSWER_FADE_MS = 220L

        /**
         * The line above the name: what the call is doing, and how long it has been doing
         * it. One size for both - a clock that grew while the words around it stayed small
         * was a line that changed shape as a call was answered.
         */
        const val STATUS_SP = 24f

        /**
         * Set close. The old tracking was there to open up a caption at half this size;
         * at this one there is room already, and spaced digits read as a countdown.
         */
        const val STATUS_TRACKING = 0.02f

        const val FACE_DP = 168

        /** What stands in for a caller the phone has never heard of. */
        const val USER_ICON = "$ICON_DIR/appbar.user.svg"
        const val BUTTON_DP = 62
        const val ACTION_DP = 62
        const val ACTION_GLYPH_DP = 26

        /** The message button: shorter than the two answers, and over one of them. */
        const val SMALL_ACTION_DP = 46
        const val SMALL_ACTION_GLYPH_DP = 20

        /** The strip the quick replies are listed on. The app bar's own near-black. */
        val MENU_COLOUR = rocks.gorjan.gokixp.wp81.MetroAppBar.BAR_COLOUR

        /** What the rest of the screen is dimmed to while they are up. */
        val SCRIM = Color.argb(170, 0, 0, 0)

        const val REPLY_STAGGER_MS = 30L

        /** What can be said without typing. See showQuickReplies. */
        val QUICK_REPLIES = listOf(
            "Call you right back",
            "Can't talk now, text me",
            "Can't talk now, call me later",
            "Can't talk now"
        )

        /**
         * Answering is green, whatever the phone's accent is.
         *
         * The one place in this shell where a colour is not the user's choice. Every other
         * button here says "this is the command in force" and takes the accent to say it;
         * this one says "this is the one that takes the call", against another button an
         * inch away that ends it - and green has meant that on a telephone for longer than
         * any of this has existed. A phone whose accent happened to be red would otherwise
         * put a red button under "answer".
         *
         * Windows Phone's own Green, from the twenty the theme picker offered, so it still
         * belongs to the palette even where it is not the palette's choice. See
         * ThemeManager.WP81_ACCENTS.
         */
        const val ANSWER_GREEN = 0xFF60A917.toInt()

        /** Grey on black for anything that is not the one thing the screen is asking. */
        const val FILL_ALPHA = 0.16f
        const val DISABLED_ALPHA = 0.35f

        /** White at three-fifths, for the second line. Not the palette's - this page is black. */
        val SUBTLE = Color.argb(153, 255, 255, 255)

        /** How far back the caller's own picture is held so white type still reads over it. */
        const val BACKDROP_ALPHA = 0.28f

        const val TICK_MS = 500L

        val DTMF_KEYS = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf('*', '0', '#')
        )
    }
}
