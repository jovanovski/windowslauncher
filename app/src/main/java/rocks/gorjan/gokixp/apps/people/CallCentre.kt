package rocks.gorjan.gokixp.apps.people

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import rocks.gorjan.gokixp.wp81.PeopleStore

/**
 * The calls going on right now.
 *
 * Android hands a phone app its calls through an [InCallService], which the system binds
 * and unbinds as it pleases and which is emphatically not a place to keep a screen. So the
 * service puts what it is given here, the screen reads it from here, and neither has to
 * know whether the other exists - which matters, because they genuinely come and go
 * independently: a call can arrive before there is a screen, and a screen can be rotated
 * out from under a call.
 *
 * A singleton for the same reason the telephone in a hallway was: there is one of it. Two
 * views of the calls in progress that could disagree would be a bug with no upside.
 */
object CallCentre {

    /** What a call is doing, in the words a person would use. */
    enum class Stage { INCOMING, DIALING, ACTIVE, HOLDING, ENDED }

    /** One call, and who the phone thinks is on it. */
    data class Line(
        val call: Call,
        val stage: Stage,
        val number: String,
        /** Their name, once the address book has been asked. The number until then. */
        val name: String,
        val photoUri: String?,
        /** The aggregated contact, where the number belongs to one. */
        val contactId: Long?,
        /** When the call connected, for the timer. Zero while it is still ringing. */
        val connectedAt: Long
    ) {
        val title: String get() = name.ifBlank { number }.ifBlank { "unknown" }
    }

    /**
     * The bound service, for the things that belong to the phone rather than to one call:
     * the mute and the speaker. Null whenever nothing is going on.
     */
    var service: InCallService? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val calls = mutableListOf<Call>()

    /** Who each number turned out to be. Asked once per number, not once per redraw. */
    private val known = mutableMapOf<String, PeopleStore.Contact?>()

    private var audio: CallAudioState? = null

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Watches every call for a change of state.
     *
     * One callback for all of them: what any of them does changes the same screen, and the
     * screen reads the whole set each time rather than being told which one moved.
     */
    private val watcher = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = announce()
        override fun onDetailsChanged(call: Call, details: Call.Details) = announce()
    }

    // ---------------------------------------------------------------- the calls

    fun add(context: Context, call: Call) {
        if (calls.contains(call)) return
        calls.add(call)
        call.registerCallback(watcher)
        identify(context, call)
        announce()
    }

    fun remove(call: Call) {
        if (!calls.remove(call)) return
        try {
            call.unregisterCallback(watcher)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop watching a call that has ended", e)
        }
        announce()
    }

    fun isEmpty(): Boolean = calls.isEmpty()

    /**
     * The call the screen is about.
     *
     * A ringing call outranks everything: it is the one thing on this phone that is asking
     * a question, and it has to be the one on screen whatever else is going on. After that
     * whatever is live, and a held call only if there is nothing else.
     */
    fun primary(): Line? {
        val ordered = calls.sortedBy {
            when (stageOf(it)) {
                Stage.INCOMING -> 0
                Stage.DIALING -> 1
                Stage.ACTIVE -> 2
                Stage.HOLDING -> 3
                Stage.ENDED -> 4
            }
        }
        return ordered.firstOrNull()?.let { lineFor(it) }
    }

    fun lines(): List<Line> = calls.map { lineFor(it) }

    private fun lineFor(call: Call): Line {
        val number = numberOf(call)
        val contact = known[number]
        return Line(
            call = call,
            stage = stageOf(call),
            number = number,
            // The provider's own idea of who is calling is the fallback, not the first
            // choice: it is whatever the network sent, and the address book is what the
            // person actually calls them.
            name = contact?.name ?: call.details?.callerDisplayName.orEmpty(),
            photoUri = contact?.photoUri,
            contactId = contact?.id,
            connectedAt = call.details?.connectTimeMillis ?: 0L
        )
    }

    private fun numberOf(call: Call): String {
        val handle = call.details?.handle ?: return ""
        return handle.schemeSpecificPart.orEmpty()
    }

    private fun stageOf(call: Call): Stage {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details?.state ?: Call.STATE_NEW
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        return when (state) {
            Call.STATE_RINGING -> Stage.INCOMING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> Stage.DIALING
            Call.STATE_ACTIVE -> Stage.ACTIVE
            Call.STATE_HOLDING -> Stage.HOLDING
            else -> Stage.ENDED
        }
    }

    /** Asks the address book who a number belongs to, and says so when it answers. */
    private fun identify(context: Context, call: Call) {
        val number = numberOf(call)
        if (number.isBlank() || known.containsKey(number)) return
        // Marked as asked before the answer arrives, so a call whose details change twice
        // does not send two lookups after the same number.
        known[number] = null
        PeopleStore.lookup(context, number) { contact ->
            known[number] = contact
            announce()
        }
    }

    // ---------------------------------------------------------------- commands

    fun answer() {
        primary()?.call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    /** Turns a ringing call away. Not the same as [hangUp], which ends one in progress. */
    fun reject() {
        primary()?.call?.reject(false, null)
    }

    /**
     * Turns a call away and says why, in a text message.
     *
     * The platform's own "respond via message": the call is rejected *with* the words, and
     * whatever is carrying the call sends them. Not sent by this app, which is the point -
     * nothing here needs to be allowed to send an SMS, and the message goes out through the
     * same subscription the call came in on rather than through a guess about which one.
     *
     * Not every call can do it - a wi-fi call, some carriers - and the capability says so.
     * Where it cannot, the call is still turned away and the words are handed to whatever
     * app sends messages, with the number already filled in: one tap instead of none, which
     * is a good deal better than a reply that silently never went.
     */
    fun rejectWith(context: Context, text: String) {
        val line = primary() ?: return
        val canText = line.call.details?.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT) == true
        if (canText) {
            line.call.reject(true, text)
            return
        }
        line.call.reject(false, null)
        if (line.number.isBlank()) return
        try {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("smsto:" + android.net.Uri.encode(line.number))
                ).putExtra("sms_body", text)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Nothing on this phone sends messages", e)
        }
    }

    fun hangUp() {
        primary()?.call?.disconnect()
    }

    fun toggleHold() {
        val line = primary() ?: return
        if (line.stage == Stage.HOLDING) line.call.unhold() else line.call.hold()
    }

    fun canHold(): Boolean = primary()?.call?.details
        ?.can(Call.Details.CAPABILITY_HOLD) == true

    var muted: Boolean
        get() = audio?.isMuted == true
        set(value) {
            service?.setMuted(value)
        }

    /**
     * Whether the call is coming out of the earpiece.
     *
     * Which is the only route that means the phone is against somebody's head - a speaker,
     * a headset or a car is a call being held at arm's length or not held at all. See
     * GokiInCallService, which turns the screen off on the strength of it.
     */
    val onEarpiece: Boolean
        // Unknown counts as the earpiece, which is where a voice call starts. Telecom
        // reports the route in a callback of its own, and until the first one arrives this
        // read said "not the earpiece" - so a call answered and held to an ear before the
        // phone had got round to mentioning its audio route kept its screen on.
        get() = audio?.route?.let { it == CallAudioState.ROUTE_EARPIECE } ?: true

    /**
     * Where the call's sound is going now.
     *
     * The earpiece until Telecom says otherwise, which is where a voice call starts.
     */
    val route: Int
        get() = audio?.route ?: CallAudioState.ROUTE_EARPIECE

    /**
     * Every way out this call has, in the order they are worth offering.
     *
     * Read from Telecom's own mask rather than guessed at: what is available changes as
     * things are plugged in and paired, and a phone with a headset on it usually loses the
     * earpiece from the list entirely. The order is fixed - nearest the head first, loudest
     * last - so the list does not reshuffle itself under somebody mid-call.
     */
    fun routes(): List<Int> {
        val mask = audio?.supportedRouteMask ?: return listOf(CallAudioState.ROUTE_EARPIECE)
        return ROUTE_ORDER.filter { mask and it != 0 }
    }

    /** Sends the sound somewhere else. One of [routes]. */
    fun setRoute(route: Int) {
        service?.setAudioRoute(route)
    }

    /**
     * What the bluetooth headset is called, if this app is allowed to know.
     *
     * "bluetooth" is a protocol; the thing in somebody's ears has a name, and on a phone
     * with a car and a pair of earbuds both paired it is the only way to tell which is
     * which.
     *
     * Reading it needs BLUETOOTH_CONNECT from Android 12 - see the manifest, and
     * MainActivity.ensureCallPermissions, which asks for it when People is made the phone.
     * Null whenever that is missing, refused or simply unknown, and the list falls back to
     * the word. Nothing else changes: choosing the route never needed the permission.
     */
    fun bluetoothName(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val state = audio ?: return null
        return try {
            val device = state.activeBluetoothDevice
                ?: state.supportedBluetoothDevices.firstOrNull()
                ?: return null
            device.name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: SecurityException) {
            Log.d(TAG, "Not allowed to read the bluetooth device's name", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the bluetooth device's name", e)
            null
        }
    }

    var speaker: Boolean
        get() = audio?.route == CallAudioState.ROUTE_SPEAKER
        set(value) {
            service?.setAudioRoute(
                if (value) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
            )
        }

    /**
     * A key pressed on the in-call keypad, sent down the line.
     *
     * Held for a moment before it is let go. Telecom sends the tone for as long as the key
     * is down, and stopping it in the same breath as starting it produces a burst too
     * short for some switches to hear - which comes out as a menu that ignores every
     * second press. A tenth of a second is what a finger on a real key would have given
     * it, and it is the length the keypad's own sound uses too.
     */
    fun dtmf(digit: Char) {
        val call = primary()?.call ?: return
        call.playDtmfTone(digit)
        main.postDelayed({
            try {
                call.stopDtmfTone()
            } catch (e: Exception) {
                // The call can end between the press and the release, and a tone on a call
                // that is over is not worth reporting.
                Log.d(TAG, "Could not stop a tone on a call that has ended", e)
            }
        }, DTMF_MS)
    }

    fun onAudioChanged(state: CallAudioState?) {
        audio = state
        announce()
    }

    // ---------------------------------------------------------------- listeners

    fun listen(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun forget(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Tells whoever is watching that something moved.
     *
     * Over a copy of the list: a listener may well stop listening in response to what it
     * hears - the call screen does exactly that when the last call ends - and taking that
     * out of the list being walked is how it would fail.
     */
    private fun announce() {
        for (listener in listeners.toList()) {
            try {
                listener()
            } catch (e: Exception) {
                Log.w(TAG, "A call listener threw", e)
            }
        }
    }

    /** How long a sent tone is held down. See [dtmf]. */
    private const val DTMF_MS = 150L

    private const val TAG = "WP81Phone"

    /** Nearest the head first, loudest last. See [routes]. */
    private val ROUTE_ORDER = listOf(
        CallAudioState.ROUTE_EARPIECE,
        CallAudioState.ROUTE_BLUETOOTH,
        CallAudioState.ROUTE_WIRED_HEADSET,
        CallAudioState.ROUTE_SPEAKER
    )
}
