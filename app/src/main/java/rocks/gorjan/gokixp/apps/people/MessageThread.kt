package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.text.InputType
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MessageStore
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/**
 * One conversation: everything said to and from a number, and the box to say more in.
 *
 * Windows Phone drew this as two columns of bubbles - theirs on the left in the chrome's
 * own grey, yours on the right in the accent - and put the text box at the foot with the
 * app bar under it. The bubbles are square, because everything on this platform is.
 *
 * A page rather than a view inside one: it is pushed over People like a profile or a
 * keypad, and is where back goes first. Unlike them it carries no command strip: a
 * conversation has exactly one thing you do to it, and that is say something - which is
 * the box at the foot and the button beside it. Everything else about the person is
 * reached through their name at the top.
 *
 * It watches the message store while it is on screen, so a message arriving lands in the
 * conversation as it is written down - by whichever app on the phone is the one that does
 * the writing. See [MessageStore] for why that is not this one.
 */
@SuppressLint("ViewConstructor")
class MessageThread(
    context: Context,
    private val palette: WP81Palette,
    /** The number this conversation is with, in whatever form it was reached by. */
    private val address: String,
    private var contact: PeopleStore.Contact?,
    /** Words to open with, from a share, an `sms:` link, or a message that would not go. */
    draft: String?,
    private val onBack: () -> Unit,
    private val onProfile: (Long) -> Unit,
    private val onAddContact: (String) -> Unit,
    private val onRequestPermissions: (Array<String>) -> Unit,
    /**
     * Puts a command list up, anchored to a row.
     *
     * Through the app rather than built here: People holds one context menu for the whole
     * program and brings it to the front as it is shown, and a second one made inside a
     * page would go up behind the page that asked for it. See PeopleApp.showMenu.
     */
    private val onMenu: (List<WP81ContextMenu.Item>, View) -> Unit,
    private val onNotify: (String, String) -> Unit
) : LinearLayout(context) {

    private val header = MetroPageHeader(context, palette)
    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val compose = EditText(context)
    private lateinit var send: ImageView

    /** What the store had, last time it was asked. */
    private var messages: List<MessageStore.Message> = emptyList()

    /**
     * Messages this page has sent that the store has not written down yet.
     *
     * The platform writes a sent message to the store on this app's behalf, but it does it
     * when the network has taken the message rather than when the send button is pressed -
     * a second or two, sometimes longer on a bad signal. A conversation that swallowed
     * what was just typed and produced it again later would look broken every single time,
     * so the words go up straight away and are dropped when the real row appears.
     *
     * One that failed stays, because it is the only record that it was ever typed.
     */
    private val pending = mutableListOf<Pending>()

    private class Pending(val body: String, val at: Long) {
        var problem: String? = null
    }

    /** How many of the newest messages are drawn. The rest are behind one tap. See [bind]. */
    private var shown = FIRST_SHOWN

    private var watch: ContentObserver? = null

    /** Whether the page has drawn once, which decides whether it jumps to the newest. */
    private var settled = false

    /** Whether this session has already asked to be allowed to send. See [trySend]. */
    private var askedToSend = false

    /** Every bubble on the page, so they can be re-measured when the page is. */
    private val bubbles = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        // Anything falling past the bubbles stops here rather than reaching the panorama.
        isClickable = true

        header.setName(title())
        header.onBack = { onBack() }
        // Their name is the way to their card - or, for a number nobody has saved, the
        // offer to make one. A conversation is the place that question comes up, and the
        // name at the top of it is the only thing on the page that is about the person
        // rather than about what was said.
        header.onTitle = {
            val person = contact
            if (person != null) onProfile(person.id) else onAddContact(address)
        }
        addView(header, LayoutParams(MATCH, WRAP))

        column.orientation = VERTICAL
        column.setPadding(dp(EDGE_DP), dp(6), dp(EDGE_DP), dp(10))
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        // The foot of the page, and the whole of it. There is no strip under this: what
        // was on it has gone where it reads better - the card is the name at the top,
        // sending is the button beside the box, ringing them is on their card or on any
        // number in the conversation itself, and refreshing was never a command at all,
        // since the page watches the message store and redraws itself.
        addView(composeRow(), LayoutParams(MATCH, WRAP))
        setSendEnabled(false)

        // A bubble is set against the width of the page it is on, and on the first pass
        // there is no page yet - so the width is applied again when there is one, and
        // whenever the window this app lives in is resized around it.
        column.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) applyBubbleWidths()
        }

        // Who they are, if this was reached by number rather than from a contact - the
        // title, the menu and whether there is a card to open all follow from it.
        if (contact == null) {
            PeopleStore.lookup(context, address) { found ->
                if (found == null) return@lookup
                contact = found
                header.setName(title())
            }
        }
        if (!draft.isNullOrEmpty()) {
            compose.setText(draft)
            compose.setSelection(compose.text.length)
        }
        // Read here rather than by the caller: the page is what knows how much of a
        // conversation it is showing, and it re-reads on its own from now on.
        reload()
        // Opening a conversation is reading it. Both of these are only possible while this
        // app is the phone's messaging app; when it is not, the mark stays where it was
        // set and there is no notification of ours to take down.
        MessageStore.markRead(context, address)
        MessageNotifier.clear(context, address)
    }

    /** Their name if the phone knows it, the number if not. */
    private fun title(): String =
        contact?.name?.takeIf { it.isNotBlank() } ?: address

    // ---------------------------------------------------------------- the box

    /**
     * The text box, and the button that sends what is in it.
     *
     * On the page rather than on the strip. It was drawn as part of the app bar and shared
     * its colour, which made the two one piece of furniture - so anything that changed the
     * bar's height moved the box with it, and a command list opening under it shoved the
     * thing being typed into halfway up the screen. The box belongs to the conversation,
     * not to the strip, and now sits on it.
     */
    private fun composeRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // A little more underneath than above: this is the bottom edge of the page
            // now, rather than something resting on a strip that was the edge.
            setPadding(dp(EDGE_DP), dp(10), dp(EDGE_DP), dp(12))
        }
        compose.apply {
            hint = "type a message"
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = COMPOSE_MAX_LINES
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // The platform's own text box: white, with black in it, under either theme.
            palette.applyToField(this)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // Nothing typed is nothing to send, and a ring that would do nothing
                    // says so rather than waiting to be pressed and refusing.
                    setSendEnabled(!s.isNullOrBlank())
                }
            })
        }
        row.addView(compose, LinearLayout.LayoutParams(0, WRAP, 1f))

        // At the end of the line the words are typed on, which is where the sentence
        // finishes. It was a ring on the strip below, among the commands that belong to
        // the page as a whole - and sending is not one of those: it belongs to what is in
        // the box, and it should be next to it.
        send = sendRing()
        row.addView(
            send,
            LinearLayout.LayoutParams(dp(SEND_DP), dp(SEND_DP)).apply {
                marginStart = dp(10)
            }
        )
        return row
    }

    /**
     * The send button: the app bar's own ring, drawn on the page.
     *
     * The strip is always near-black so its rings are always white; this one sits on the
     * page, which is white under the light setting, so it takes the page's own ink instead.
     */
    private fun sendRing(): ImageView = ImageView(context).apply {
        setBackgroundResource(R.drawable.wp81_appbar_circle)
        setImageDrawable(SvgIcon.fromAsset(context, SEND_ICON))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(SEND_INSET_DP), dp(SEND_INSET_DP), dp(SEND_INSET_DP), dp(SEND_INSET_DP))
        backgroundTintList = ColorStateList.valueOf(palette.foreground)
        imageTintList = ColorStateList.valueOf(palette.foreground)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        isClickable = true
        setOnClickListener { trySend() }
        TiltEffect.apply(this)
    }

    /** Dims the ring when there is nothing to send, and stops it answering. */
    private fun setSendEnabled(on: Boolean) {
        send.isClickable = on
        send.alpha = if (on) 1f else DISABLED_ALPHA
    }

    private fun trySend() {
        val body = compose.text?.toString().orEmpty().trim()
        if (body.isEmpty()) return
        if (!MessageStore.canSend(context)) {
            // Asked for at the moment somebody actually tries to send, which is the moment
            // the request explains itself - and only once, the same way placing a call is.
            if (!askedToSend) {
                askedToSend = true
                onRequestPermissions(MessageStore.sendPermissions())
                return
            }
            onNotify("Messages", "This app has not been allowed to send messages")
            return
        }
        Haptics.tap(compose)
        compose.setText("")
        sendNow(body)
    }

    /**
     * Sends a failed message again, and takes the failed one away.
     *
     * The old row is a record of an attempt, not of anything that was said - see
     * [MessageStore.forget], which can only do this while People is the phone's messaging
     * app. Where it cannot, the attempt stays in the conversation and the new one appears
     * beneath it, which is the truthful version of the same events.
     */
    private fun retry(message: MessageStore.Message) {
        MessageStore.forget(context, message.id)
        sendNow(message.body)
    }

    /** Puts the words up, sends them, and marks them if they do not go. */
    private fun sendNow(body: String) {
        val mine = Pending(body, System.currentTimeMillis())
        pending.add(mine)
        bind(toBottom = true)
        MessageStore.send(context, address, body) { problem ->
            mine.problem = problem
            if (problem != null) onNotify("Messages", problem)
            // The store will have the message by now if it went; if it did not, the bubble
            // has to change to say so. Either way this is the moment to look again.
            reload()
        }
    }

    // ---------------------------------------------------------------- reading

    /** Re-reads the conversation. Called on the way in, on a change, and after a send. */
    fun reload() {
        if (!MessageStore.canRead(context)) {
            bind(toBottom = false)
            return
        }
        MessageStore.conversation(context, address) { found ->
            messages = found
            // Anything the store now has that this page was holding on to is the same
            // message, and holding both would show it twice.
            // Whatever the store has, the store is right about - including that a message
            // failed. Holding a local copy of one it already knows about is how a single
            // message ends up on screen twice, once saying it did not send and once saying
            // the same thing.
            pending.removeAll { mine ->
                found.any {
                    it.outgoing && it.body == mine.body && it.at >= mine.at - SKEW_MS
                }
            }
            bind(toBottom = atBottom())
        }
    }

    /** Whether the reader is at the newest message, and so should be carried along by one. */
    private fun atBottom(): Boolean {
        if (!settled) return true
        val content = scroll.getChildAt(0) ?: return true
        return content.height - (scroll.scrollY + scroll.height) < dp(BOTTOM_SLACK_DP)
    }

    private fun bind(toBottom: Boolean) {
        column.removeAllViews()
        bubbles.clear()

        if (!MessageStore.canRead(context)) {
            column.addView(
                note("no access to your messages.  tap to allow") {
                    onRequestPermissions(MessageStore.readPermissions())
                }, wide())
            return
        }
        if (messages.isEmpty() && pending.isEmpty()) {
            column.addView(note("nothing said yet.  the box below is where it starts"), wide())
            return
        }

        val from = (messages.size - shown).coerceAtLeast(0)
        if (from > 0) {
            column.addView(note("earlier messages") {
                // Whatever is being read stays where it is being read. Messages are added
                // *above* the reader, so the page grows behind them and the same words
                // would otherwise be somewhere else on the screen a moment later - which
                // in a conversation reads as having lost your place.
                val before = column.height
                shown += MORE_SHOWN
                bind(toBottom = false)
                column.viewTreeObserver.addOnPreDrawListener(
                    object : android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            column.viewTreeObserver.removeOnPreDrawListener(this)
                            scroll.scrollBy(0, column.height - before)
                            return true
                        }
                    }
                )
            }, wide())
        }
        for (i in from until messages.size) {
            val message = messages[i]
            val next = messages.getOrNull(i + 1)
            column.addView(
                bubble(
                    body = message.body,
                    at = message.at,
                    outgoing = message.outgoing,
                    // The time under the last of a run rather than under every line: a
                    // burst of five messages happened at one time, and saying so five
                    // times is five lines of type nobody reads.
                    showTime = next == null || next.outgoing != message.outgoing ||
                        next.at - message.at > RUN_MS,
                    footer = when {
                        message.failed -> "not sent  ·  tap to try again"
                        message.sending -> "sending"
                        else -> null
                    },
                    onFooter = if (message.failed) ({ retry(message) }) else null
                ),
                wide()
            )
        }
        for (mine in pending) {
            column.addView(
                bubble(
                    body = mine.body,
                    at = mine.at,
                    outgoing = true,
                    showTime = false,
                    footer = mine.problem?.let { "not sent  ·  tap to try again" } ?: "sending",
                    onFooter = if (mine.problem != null) ({
                        pending.remove(mine)
                        sendNow(mine.body)
                    }) else null
                ),
                wide()
            )
        }
        applyBubbleWidths()
        if (toBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        settled = true
    }

    /**
     * One thing somebody said.
     *
     * Theirs on the left in a raised grey, yours on the right in the accent. Square, and
     * with no tail: Windows Phone's bubbles were rectangles, and the sides they are on is
     * what says who said them.
     */
    private fun bubble(
        body: String,
        at: Long,
        outgoing: Boolean,
        showTime: Boolean,
        footer: String?,
        onFooter: (() -> Unit)?
    ): View {
        val side = if (outgoing) Gravity.END else Gravity.START
        val holder = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = side
            setPadding(0, dp(3), 0, dp(3))
        }

        val text = TextView(context).apply {
            this.text = body
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setPadding(dp(12), dp(9), dp(12), dp(10))
            setBackgroundColor(if (outgoing) palette.accent else theirs())
            setTextColor(if (outgoing) palette.onAccent() else palette.foreground)
            // Whatever is in a message that leads somewhere: addresses, numbers, links.
            // Web links land in this shell's own browser, because the launcher claims them
            // - and a number handed to the phone opens the keypad in this very app.
            Linkify.addLinks(
                this,
                Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
            )
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            // On the accent there is no colour left to be a link, so those are underlined
            // white; on the grey they are the accent, like every other thing in this shell
            // that can be tapped.
            setLinkTextColor(if (outgoing) palette.onAccent() else palette.accent)
            highlightColor = Color.TRANSPARENT
            // Held for what can be done to something already said. Which is one thing -
            // taking a copy of it: a code to paste somewhere, an address, a name spelled
            // out - but it is offered the way everything else in this shell is offered,
            // because a hold that acted on the spot is the one gesture in the app whose
            // result you find out about afterwards.
            setOnLongClickListener { row ->
                onMenu(listOf(WP81ContextMenu.Item("copy") { copy(body) }), row)
                true
            }
        }
        bubbles.add(text)
        holder.addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = side })

        val said = listOfNotNull(
            footer,
            momentOf(context, at).takeIf { showTime && it.isNotBlank() }
        ).joinToString("  ·  ")
        if (said.isNotEmpty()) {
            holder.addView(TextView(context).apply {
                this.text = said
                typeface = font(R.font.segoeui_regular)
                textSize = 11f
                setTextColor(if (onFooter != null) FAILED else palette.foregroundSubtle)
                setPadding(dp(2), dp(3), dp(2), 0)
                if (onFooter != null) {
                    isClickable = true
                    setOnClickListener {
                        Haptics.tap(it)
                        onFooter()
                    }
                    TiltEffect.apply(this)
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = side })
        }
        return holder
    }

    /**
     * Puts one message on the clipboard.
     *
     * Nothing is said about it here. Android shows what was copied itself from 13 onward,
     * and a message of our own on top of that would be the same news twice.
     */
    private fun copy(body: String) {
        if (body.isEmpty()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            if (clipboard == null) {
                onNotify("Messages", "This phone has nowhere to copy to")
                return
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", body))
        } catch (e: Exception) {
            android.util.Log.w("WP81People", "Could not copy a message", e)
            onNotify("Messages", "That could not be copied")
        }
    }

    /**
     * How wide a bubble is allowed to get.
     *
     * Set against the page rather than the display: this app runs in a window that is not
     * always the whole screen, and a message measured against the screen would run out of
     * one that is not.
     */
    private fun applyBubbleWidths() {
        val room = column.width - column.paddingLeft - column.paddingRight
        if (room <= 0) return
        val max = (room * BUBBLE_SHARE).toInt()
        for (bubble in bubbles) bubble.maxWidth = max
    }

    /** The fill their messages sit in: the page, lifted just enough to be a block on it. */
    private fun theirs(): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, THEIRS_LIFT)

    // ---------------------------------------------------------------- lifetime

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Watched only while it is on screen. A page nobody is looking at that re-reads
        // the message store every time anything on the phone writes to it is a page doing
        // work for nobody.
        watch = MessageStore.watch(context) { reload() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MessageStore.unwatch(context, watch)
        watch = null
    }

    // ---------------------------------------------------------------- helpers

    private fun note(message: String, onTap: (() -> Unit)? = null) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 15f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), 0, dp(18))
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }
    }

    private fun font(res: Int) = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wide() = LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT

        const val ICON_DIR = "custom_icons_8"
        const val SEND_ICON = "$ICON_DIR/appbar.message.send.svg"

        /** The panorama's own margin, so a conversation lines up with the pages behind it. */
        const val EDGE_DP = 22

        /** The send ring, at the app bar's own measurements. */
        const val SEND_DP = 44
        const val SEND_INSET_DP = 5

        /** How far a ring with nothing to do is faded. The strip's own figure. */
        const val DISABLED_ALPHA = 0.35f

        /** How much of the width one message may take. The rest is what says which side. */
        const val BUBBLE_SHARE = 0.76f

        /** How far the page is lifted for the other person's bubbles. */
        const val THEIRS_LIFT = 0.16f

        /** A message that did not go. WP8.1's own red, which is not any accent's business. */
        const val FAILED = 0xFFE51400.toInt()

        /** How many of the newest messages are drawn, and how many more each tap adds. */
        const val FIRST_SHOWN = 60
        const val MORE_SHOWN = 120

        /** How far apart two messages have to be before the second one gets its own time. */
        const val RUN_MS = 10L * 60L * 1000L

        /** How close to the end counts as being at it, for deciding whether to follow. */
        const val BOTTOM_SLACK_DP = 120

        /** How far the store's idea of when a message was sent may drift from this page's. */
        const val SKEW_MS = 30_000L

        const val COMPOSE_MAX_LINES = 5
    }
}
