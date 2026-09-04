package rocks.gorjan.gokixp.wp81.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.keyboard.text.Composer
import rocks.gorjan.gokixp.wp81.keyboard.text.Dictionary
import rocks.gorjan.gokixp.wp81.keyboard.text.Suggester
import rocks.gorjan.gokixp.wp81.keyboard.text.UserDictionary
import java.io.File
import java.util.concurrent.Executors

/**
 * The Windows Phone keyboard.
 *
 * The shell goes to real trouble making a text box look like Windows Phone - the caret, the
 * band behind a selection, the three grips either end of it - and then the keyboard that
 * comes up under it is whatever the phone happens to have. This is the other half.
 *
 * It is a keyboard for the whole phone rather than for this app: an input method belongs to
 * the system, so once it is turned on it appears in every text box on the device. That is
 * the point, and it is also why almost everything in here is written defensively. A crash in
 * an ordinary activity loses that activity; a crash in here loses text entry in whatever the
 * user happened to be doing, everywhere, with the way out buried in system settings.
 *
 * The palette is re-read rather than pushed. `WP81Shell.applyPalette` fans a colour change
 * out to the shell's own children, and an input method is in a different window and usually
 * a different task, so it is not among them. Instead the preferences are watched directly,
 * and the palette is read again every time the keyboard is shown - the listener keeps a
 * visible keyboard current, and the re-read covers the case where it was not visible when
 * the change happened.
 */
class WP81KeyboardService : InputMethodService(), KeyView.Listener {

    private lateinit var themeManager: ThemeManager
    private lateinit var palette: WP81Palette
    private var host: KeyboardHost? = null
    private val keyboard: KeyboardView? get() = host?.keyboard

    /** The word being typed, and what the keyboard last did to it. */
    private val composer = Composer()

    /** Learned words and pairs. Kept out of the cloud backup - see [UserDictionary]. */
    private var learned: UserDictionary? = null

    /** One per language, built on first use: loading a dictionary is three megabytes of I/O. */
    private val suggesters = HashMap<String, Suggester>()

    /** What the bar is currently offering. Index 0 is always the literal text. */
    private var offered: List<String> = emptyList()

    /**
     * The typed text [offered] was worked out for.
     *
     * Because the search runs off the main thread, what the bar is showing can be a keystroke
     * behind what has been typed. That is fine for suggestions - they catch up in a few
     * milliseconds - and not fine for deciding an automatic correction, which must never act
     * on a guess made about a different word. See [finishWord].
     */
    private var offeredFor: String = ""

    /**
     * Suggestions are worked out here, not on the thread drawing the keyboard.
     *
     * This is what fixes typing that falls further and further behind during a fast run of
     * keys. A search takes a few milliseconds and every keystroke asked for one *on the main
     * thread*, so once the interval between keys dropped below the cost of a search the work
     * queued up behind itself and the lag grew for as long as the burst lasted.
     *
     * One thread, so the searches cannot overlap - [Suggester] keeps its working state in
     * fields and is emphatically not safe to call from two places at once - and a generation
     * counter, so that a result which arrives after the user has typed on is discarded rather
     * than briefly replacing the right answer with an older one.
     */
    private val searches = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Raised from the default, which is the priority of background work nobody is
            // waiting for. Somebody is waiting for this: it is on the path between a key
            // going down and a word appearing, and a phone with anything else going on will
            // otherwise schedule it whenever it gets round to it.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, "wp81-keyboard-suggest").apply { isDaemon = true }
    }
    /**
     * Reading dictionaries, which is slow, rare, and must never hold up a search.
     *
     * A thread of its own rather than a second use of [searches]. See [warmSuggester].
     */
    private val loads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wp81-keyboard-load").apply { isDaemon = true }
    }

    private val onMain = Handler(Looper.getMainLooper())
    // Read on the search thread and written on the main one, so that a search can find out
    // it has been overtaken and stop rather than finish work nobody will look at.
    @Volatile
    private var searchGeneration = 0

    /** The word before the one being typed, for next-word prediction. */
    private var previousWord: String? = null

    /**
     * The emoji panel, built on first use and kept afterwards.
     *
     * Kept because it holds three and a half thousand emoji and a scroll position, and
     * rebuilding that every time the key is pressed would be a visible stutter on opening.
     */
    private var emoji: EmojiPanel? = null

    /**
     * Whether keystrokes are being fed to the emoji panel's search box rather than the app.
     *
     * An input method cannot type into a text field of its own - it has no focus to give
     * itself - so the panel's search bar is a drawing, and while this is set the letter keys
     * build up a query for it instead of a word for the field being edited.
     */
    private var searchingEmoji = false



    /** Which letter layout is in use. The symbol pages are a detour from it, not a choice. */
    private var language: KeyboardLayout = Layouts.EN_QWERTY

    private var shiftState = ShiftState.OFF

    /** When shift was last tapped, for spotting the double-tap that locks it. */
    private var lastShiftTap = 0L

    /** When space was last committed, for the double-space that makes a full stop. */
    private var lastSpace = 0L

    /** Set for password fields and the like: no learning, and later no suggestions. */
    private var privateField = false

    private enum class ShiftState { OFF, ONCE, LOCKED }

    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            ThemeManager.KEY_WP81_ACCENT, ThemeManager.KEY_WP81_DARK -> refreshPalette()
            ThemeManager.KEY_WP81_KB_HOLD_MS,
            ThemeManager.KEY_WP81_KB_AUTOCORRECT,
            ThemeManager.KEY_WP81_KB_AUTOCAPS,
            ThemeManager.KEY_WP81_KB_OFFLINE_VOICE,
            ThemeManager.KEY_WP81_KB_SHORT_BOTTOM -> applySettings()
            // Changed from the settings page, which is a different window: the system's own
            // list has to be brought back into step before the globe is next used.
            ThemeManager.KEY_WP81_KB_LANGUAGES -> {
                KeyboardLanguages.applyToSystem(this, themeManager)
                applySettings()
            }
        }
    }

    /**
     * Reads the keyboard's own settings and applies them.
     *
     * Called when the keyboard is shown as well as when a setting changes, because the
     * settings page is a different window and may well have been used while this was hidden.
     */
    private fun applySettings() {
        autocorrect = themeManager.getWP81KeyboardAutocorrect()
        autoCapitalise = themeManager.getWP81KeyboardAutoCapitalise()
        offlineVoice = themeManager.getWP81KeyboardOfflineVoice()
        keyboard?.holdMillis = themeManager.getWP81KeyboardHoldMs().toLong()
        keyboard?.shortBottomRow = themeManager.getWP81KeyboardShortBottomRow()
        // Survives a trip to the symbol pages, which have a space bar and no language.
        keyboard?.setSpaceLabel(language.name)
        // Started now, so the dictionary is parsed and waiting before the first key is
        // pressed rather than during it. See [warmSuggester].
        warmSuggester()
        // Read here rather than on the touch path: these fire under a finger, and a
        // preference read per keystroke is exactly the sort of thing that turns into a lag
        // nobody can account for. See [KeyboardHaptics.strength].
        KeyboardHaptics.refresh(this, themeManager)
    }

    /**
     * Whether a space may replace a word by itself.
     *
     * Off unless asked for. Suggestions are shown and tappable either way; this is only about
     * whether the keyboard acts on one without being told to.
     */
    private var autocorrect = false

    /** Whether shift comes on by itself at the start of a sentence. Off unless asked for. */
    private var autoCapitalise = false

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        themeManager = ThemeManager(this)
        palette = WP81Palette.from(themeManager)
        prefs.registerOnSharedPreferenceChangeListener(prefsWatcher)
        learned = UserDictionary.open(this)
        KeyboardLanguages.applyToSystem(this, themeManager)

        // The dictionary starts loading here, which is as early as it can: the service is
        // created before the keyboard is ever shown, so by the time anybody taps a text box
        // the reading and parsing is usually long finished. Doing it when the view appears
        // was already off the main thread, but a couple of megabytes still take a moment, and
        // somebody who taps a field and starts typing immediately would beat it and see the
        // first word or two go by with nothing offered.
        language = KeyboardLanguages.enabled(themeManager).first()
        warmSuggester()
    }

    override fun onCreateInputView(): View {
        palette = WP81Palette.from(themeManager)
        val view = KeyboardHost(this, palette)
        view.keyboard.listener = this
        // Whatever was last in use, unless it has since been turned off in the settings.
        if (KeyboardLanguages.enabled(themeManager).none { it.id == language.id }) {
            language = KeyboardLanguages.enabled(themeManager).first()
        }
        view.keyboard.setLayout(language)
        view.bar.onWordPicked = { index -> takeSuggestion(index) }
        view.bar.onVoice = { toggleVoice() }
        view.bar.onClipboard = { showClipboardHistory() }
        host = view
        return view
    }

    /**
     * The suggester for whichever language is up, built once and kept.
     *
     * Lazily, because a dictionary is two megabytes to read and parse and the second language
     * may never be used at all - and kept, because doing it per keystroke would be absurd and
     * doing it per showing would be a visible pause every time the keyboard opens.
     *
     * **A missing dictionary is not a missing suggester.** Twenty-two languages ship a layout
     * and two of them ship a word list, so for most of them `load` finds nothing - and the
     * suggester is built anyway, around a null dictionary, because it also holds the *learned*
     * words and pairs and those are not language data that shipped, they are what this person
     * has typed. A German layout with no word list still ought to finish a name it has seen
     * five times. Caching the empty case matters as much: without it every keystroke in every
     * one of those languages opens an asset that is not there and pays for the exception.
     */
    private fun suggester(): Suggester? = suggesters[language.id]

    /**
     * Builds the suggester for the current language, off the main thread, once.
     *
     * A dictionary is a couple of megabytes read out of the assets - which are compressed, so
     * reading them is inflating them - and then parsed. That used to happen inside the first
     * call to `suggester()`, which is to say **on the main thread, on the first keystroke**,
     * where it is a stall at exactly the moment the keyboard is being judged.
     *
     * Started when the keyboard appears instead, so by the time a key is pressed the work is
     * usually already done. When it is not, the keystroke is not made to wait for it: the bar
     * shows what was typed, as it always does, and refreshes itself when the dictionary
     * arrives a moment later.
     */
    private fun warmSuggester() {
        val id = language.id
        if (suggesters.containsKey(id) || warming == id) return
        warming = id
        val layout = language
        // Its own thread, not the search queue. They shared one, and that was the stall: a
        // dictionary takes a moment to read and parse, and while it did, every keystroke's
        // search sat in the queue behind it - so the keyboard went quiet for as long as the
        // load took, at exactly the moment somebody had started typing. Searching and loading
        // are not the same kind of work and should not wait for each other.
        loads.execute {
            val dictionary = Dictionary.load(this, layout.language)
            val built = Suggester(dictionary, layout, learned)
            onMain.post {
                suggesters[id] = built
                warming = null
                // The keystrokes that arrived while this was loading were answered with the
                // literal alone. Now there is something to say about them.
                refreshCandidates()
            }
        }
    }

    /** The language whose suggester is being built, so it is not built twice at once. */
    private var warming: String? = null

    /**
     * Windows Phone never gave the keyboard the whole screen.
     *
     * Android's fullscreen mode replaces the app with a text box of the system's own when
     * the screen is short - which is to say in landscape on most phones - and it looks
     * nothing like anything in this shell. The phone simply kept the keyboard at the foot of
     * the screen and let the app have the rest, so that is what happens here.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInput() {
        // Dictation belongs to the field it was started in. Left running, it would carry on
        // hearing and start typing into whatever came next.
        stopVoice()
        // The word in progress is abandoned rather than committed: the field is going away and
        // whatever is half-typed in it is not the keyboard's to finish.
        composer.reset()
        previousWord = null
        offered = emptyList()
        learned?.flush()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // A row left open would still be on screen the next time the keyboard is shown,
        // offering alternates for a key that is no longer being held.
        keyboard?.hideAlternates()
        clipboardHistory?.dismiss()
        watchClipboard(false)
        super.onFinishInputView(finishingInput)
    }

    // ---------------------------------------------------------------- clipboard

    /** The history list, built on the first hold and kept afterwards. */
    private var clipboardHistory: ClipboardPopup? = null

    /**
     * Whether the clipboard is being watched right now.
     *
     * Only while the keyboard is on screen, which is the only time a read would be allowed
     * anyway - Android refuses the clipboard to an input method that is not the active one,
     * and a listener firing outside that window is a refusal logged for nothing. The one
     * thing it catches that [onStartInputView] does not is a copy made while the keyboard is
     * already up, which is what selecting a word in the field you are typing in and hitting
     * copy does.
     */
    private var watchingClipboard = false

    private val clipWatcher = ClipboardManager.OnPrimaryClipChangedListener {
        ClipboardStore.refresh(this)
        refreshCandidates()
    }

    private fun watchClipboard(on: Boolean) {
        if (on == watchingClipboard) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        try {
            if (on) manager.addPrimaryClipChangedListener(clipWatcher)
            else manager.removePrimaryClipChangedListener(clipWatcher)
            watchingClipboard = on
        } catch (e: Exception) {
            // Not worth taking the keyboard down over; the paste offer simply goes stale.
        }
    }

    /** A clip was chosen from the list. */
    private fun paste(clip: Clip) {
        if (clip.text.isEmpty()) return
        commit(clip.text)
        // Pasted text is not a word this keyboard typed, so it teaches it nothing and must
        // not be treated as the word a following-suggestion would follow.
        previousWord = null
        composer.reset()
        refreshCandidates()
    }

    /** The mark was tapped: everything still inside the window, to choose from. */
    private fun showClipboardHistory() {
        val stack = host ?: return
        val clips = ClipboardStore.history(this)
        if (clips.isEmpty()) {
            // Said rather than shown as a dead button. The bar is the one surface the
            // keyboard owns that the user is already looking at, and it is where the rest of
            // what this has to say goes - see [CandidateBar.setMessage].
            clipboardHistory?.dismiss()
            stack.bar.setMessage(NOTHING_COPIED)
            onMain.postDelayed({
                host?.bar?.setMessage(null)
                refreshCandidates()
            }, MESSAGE_MS)
            return
        }
        val popup = clipboardHistory ?: ClipboardPopup(this, palette).also {
            it.onPicked = { clip -> paste(clip) }
            clipboardHistory = it
        }
        popup.applyPalette(palette)
        popup.toggle(stack.bar, clips, stack.keyboard.unitWidth(), stack.keyboard.height)
    }

    /**
     * A field has been handed over, or the one being edited has been restarted.
     *
     * Overridden as well as [onStartInputView] because the two do not always both happen. An
     * app that clears its box after sending a message calls `restartInput`, and if the
     * keyboard is already on screen the view is not started again - so anything reset only in
     * `onStartInputView` survives into the next message. Which is how the word just sent came
     * back as soon as somebody started typing the next one.
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        forgetTheSentence()
    }

    /**
     * Drops everything about the sentence in progress.
     *
     * The composing region is ended explicitly rather than merely forgotten. It belongs to
     * the field, not to this - so leaving one open when the text has been sent or the field
     * has changed means the next thing typed lands on top of whatever the region still covers
     * instead of after it.
     */
    private fun forgetTheSentence() {
        phantomSpace = false
        try {
            currentInputConnection?.finishComposingText()
        } catch (e: Exception) {
            // The field can already be gone, which is the case this is guarding anyway.
        }
        composer.reset()
        previousWord = null
        offered = emptyList()
        offeredFor = ""
        host?.bar?.clear()
        // Sending a message empties the box, and an empty box is where the paste offer
        // belongs - so the bar is rebuilt rather than simply emptied.
        refreshCandidates()
    }

    /**
     * The cursor, the selection, or the composing region has moved.
     *
     * **This is how the keyboard finds out that the text changed without it.** Nothing else
     * tells it. A chat application's send button is the application's own button, not this
     * keyboard's enter key - so tapping it never runs a line of code here. The app takes the
     * text, sends it, and empties its box, and the keyboard is none the wiser: it still
     * believes it is part-way through composing `hello`, so the next letter typed produces
     * `hellow` and the sent word appears to come back from the dead.
     *
     * The test is whether the composing region is still where this put it. If the app has
     * dropped it, or the cursor has moved outside it - somebody tapped elsewhere in the text,
     * or the box was cleared - then what this thinks is being typed is not what is on screen,
     * and the right thing to do is forget it and start again from what is really there.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // Recorded before anything else, and whatever else happens below: this is the only
        // way an input method ever learns where the caret is, and sliding the space bar needs
        // to know. See [onCursorSlide].
        selStart = newSelStart
        selEnd = newSelEnd
        if (!composer.isComposing) return

        // Still ours if the region exists and the cursor sits at its end - which is exactly
        // the state `setComposingText` leaves behind.
        //
        // Deliberately *not* compared against the length of what is being composed, though
        // that reads like the stronger check. This callback comes from the other app's
        // process and arrives late: delete two characters quickly and the report about the
        // first can land after the composer has already recorded the second, so the lengths
        // disagree over nothing at all and the word is thrown away mid-edit. The three
        // numbers here all come from the same report and are consistent with each other
        // however stale they are, which is what makes the check trustworthy.
        val intact = candidatesStart >= 0 &&
            newSelStart == candidatesEnd &&
            newSelEnd == candidatesEnd
        if (!intact) forgetTheSentence()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.hideAlternates()
        // Now, rather than when the clip was made: an input method may read the clipboard
        // while it is the active one, which it is exactly now and mostly is not. See
        // [ClipboardStore], and [watchingClipboard] for the other half of the same idea.
        ClipboardStore.refresh(this)
        watchClipboard(true)
        composer.reset()
        previousWord = null
        offered = emptyList()
        applySettings()
        host?.bar?.clear()
        searchingEmoji = false
        emoji?.let {
            it.query = ""
            it.searchMode = false
            it.onClosed()
        }
        host?.hidePanel()
        refreshPalette()
        adoptSubtype()
        applyEditorInfo(info)
        // Last, after the bar has been cleared and the field is known: an empty box with
        // something on the clipboard should be offering to paste it the moment it opens,
        // which is the whole point of the offer. Nothing else would call this until the
        // first key was pressed, by which time the box is not empty any more.
        refreshCandidates()
    }

    /**
     * Reads the field being typed into and shapes the keyboard to it.
     *
     * Three separate things come out of one [EditorInfo]: what the enter key should say and
     * do, whether the first letter should be capital, and whether this is a field where a
     * keyboard should keep no record of what was typed.
     */
    private fun applyEditorInfo(info: EditorInfo?) {
        val view = keyboard ?: return
        val type = info?.inputType ?: InputType.TYPE_NULL
        val variation = type and InputType.TYPE_MASK_VARIATION
        val klass = type and InputType.TYPE_MASK_CLASS

        privateField = (klass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) ||
            (klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

        view.setLayout(layoutFor(klass))
        view.setSymbolsLabel(if (view.currentLayout().id.startsWith("symbols")) "abc" else "&123")
        view.setSpaceLabel(language.name)
        view.setContextualKey(contextualKeyFor(klass, variation))
        refreshEnterKey()
        updateAutoCaps(info)
    }

    /**
     * Puts the right word, or the return arrow, on the enter key.
     *
     * Kept apart from [applyEditorInfo] because it has to be redone every time the key grid
     * is rebuilt - switching to the symbol page and back makes a fresh set of key views, and
     * the new enter key knows nothing about the field it is being shown for. Re-running the
     * whole of [applyEditorInfo] would do it, but that also chooses the layout, and choosing
     * the layout again is exactly what must not happen here: on a number field it would put
     * the digits straight back the instant the user asked for letters.
     */
    private fun refreshEnterKey() {
        val view = keyboard ?: return
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        // A field that takes more than one line keeps the return arrow whatever action it
        // also declares, because there the return key's job is to make a new line and the
        // action has somewhere else to be.
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        val label = if (multiLine || noAction) null else when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> null
        }
        // Send is the one action the phone coloured, because it is the one that cannot be
        // taken back once it has been pressed.
        view.setEnterKey(label, accent = action == EditorInfo.IME_ACTION_SEND)
    }

    /**
     * Turns shift on at the places a sentence starts.
     *
     * Only when the field has asked for it - a field of surnames capitalises every word and
     * a field of passwords capitalises nothing, and both say so - and only when shift is not
     * already locked, which is a decision the user has made and the keyboard does not get to
     * overrule.
     */
    private fun updateAutoCaps(info: EditorInfo? = currentInputEditorInfo) {
        // Off unless asked for. A field asking for capitals is not the same as the user
        // wanting them, and shift is one tap away.
        if (!autoCapitalise) return
        if (shiftState == ShiftState.LOCKED) return
        val type = info?.inputType ?: return
        if (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0 &&
            type and InputType.TYPE_TEXT_FLAG_CAP_WORDS == 0 &&
            type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS == 0
        ) {
            return
        }
        val caps = try {
            currentInputConnection?.getCursorCapsMode(type) ?: 0
        } catch (e: Exception) {
            0
        }
        setShift(if (caps != 0) ShiftState.ONCE else ShiftState.OFF)
    }

    /**
     * Which keyboard a field gets before anyone has pressed anything.
     *
     * A field that only accepts a number is the one case where showing letters is not merely
     * unhelpful but wrong - the phone put a keypad up straight away, and so does every
     * keyboard since. Four wide columns of digits in telephone order, at the keyboard's own
     * height so that nothing above it moves when the field changes.
     *
     * The two pads differ only in which characters share the column with the digits: a
     * telephone number wants `+`, `*` and `#`, and an amount of money wants a decimal point
     * and a minus sign.
     */
    private fun layoutFor(inputClass: Int): KeyboardLayout = when (inputClass) {
        InputType.TYPE_CLASS_PHONE -> Layouts.PHONE_PAD
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Layouts.NUMBER_PAD
        else -> language
    }

    /**
     * What belongs on the key beside the space bar, for this field.
     *
     * A comma most of the time, and otherwise the one character this kind of field is going
     * to send somebody to the symbol page for over and over: an address needs `@`, a web
     * address needs `/`. The full stop on the other side of the space bar is left alone -
     * sentences end the same way whatever is being written. The field says which it is; that
     * is what the variation on `inputType` is for.
     */
    private fun contextualKeyFor(inputClass: Int, variation: Int): String = when (variation) {
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
        InputType.TYPE_TEXT_VARIATION_URI -> "/"
        else -> ","
    }

    private fun refreshPalette() {
        palette = WP81Palette.from(themeManager)
        host?.applyPalette(palette)
        emoji?.applyPalette(palette)
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyRepeat(view: KeyView) {
        if (view.key.action == Action.BACKSPACE) backspace(repeating = true)
    }

    /** The tick that answers a hold is [KeyView]'s; only what the hold *does* is here. */
    override fun onKeyLongPress(view: KeyView) {
        // A hold that opens something wins over a hold that offers characters.
        view.key.holdAction?.let { held ->
            when (held) {
                Action.EMOJI -> showEmoji()
                Action.SETTINGS -> showSettings()
                else -> Unit
            }
            return
        }
        when (view.key.action) {
            // A second tap on shift locks it, and so does a hold - the phone offered both,
            // and a hold is the one that works when the first tap was a moment ago.
            Action.SHIFT -> setShift(ShiftState.LOCKED)
            // Backspace has its own answer to a hold: it starts running. Nothing to open.
            Action.BACKSPACE -> Unit
            else -> keyboard?.showAlternates(view)
        }
    }

    /** Reaching along the row a hold opened. */
    override fun onKeyDrag(view: KeyView, x: Float, y: Float) {
        keyboard?.moveAlternates(view, x)
    }

    /**
     * The finger has come off a key that was being held.
     *
     * Takes whatever the row was offering, if a row was open. Nothing otherwise - a held
     * backspace has already done its work, and a held shift has already locked.
     */
    override fun onKeyRelease(view: KeyView) {
        if (view.key.action == Action.BACKSPACE) {
            finishRepeating()
            return
        }
        val chosen = keyboard?.takeAlternate()
        keyboard?.hideAlternates()
        if (chosen != null) {
            // An alternate spends a one-shot shift the same way a letter does: it is the
            // character the user was after, and the next one should not still be capital.
            if (shiftState == ShiftState.ONCE) setShift(ShiftState.OFF)
            // Through [type], not straight to the field. An accent belongs to the word being
            // typed; a symbol ends it. Committing either one directly would replace the word.
            type(chosen.toString())
        }
    }

    /**
     * The keys that change the page do it now, while the finger is still down.
     *
     * Everything else waits for the tap, because a letter must not be typed by a press that
     * turns out to be a hold - but a page change is not a character and nothing is committed
     * by it, so there is nothing to take back if the hold fires afterwards. What it buys is
     * that the new keys are already up and already laid out before the next finger arrives,
     * which during fast typing is a few milliseconds later and sometimes sooner than the
     * release of this one.
     */
    override fun onKeyPress(view: KeyView) {
        when (view.key.action) {
            Action.SYMBOLS -> toggleSymbols()
            Action.SYMBOLS_PAGE -> flipSymbolPage()
            Action.LETTERS -> showLetters()
            else -> Unit
        }
    }

    override fun onKeyUp(view: KeyView) {
        if (routeToEmojiSearch(view)) return
        val key = view.key
        when (key.action) {
            null -> commitLetter(view)
            Action.SPACE -> space()
            Action.BACKSPACE -> backspace()
            Action.ENTER -> enter()
            Action.SHIFT -> tapShift()
            // Already done on the way down - see [onKeyPress]. Doing it again here would
            // switch straight back.
            Action.SYMBOLS, Action.SYMBOLS_PAGE, Action.LETTERS -> Unit
            Action.EMOJI -> showEmoji()
            // Both of these are holds rather than taps - they are reached through
            // Key.holdAction above - so a plain press on the key that carries them does
            // whatever that key normally does and never lands here.
            Action.SETTINGS -> Unit
            // And this one is on no layout at all: it is the emoji panel's own key, answered
            // by the panel. Listed so that adding a key here is a compile error rather than a
            // key that silently does nothing.
            Action.GIF -> Unit
        }
    }

    /**
     * The finger is sliding along the space bar, so the caret moves with it.
     *
     * Moved with `setSelection` and **not** with `KEYCODE_DPAD_LEFT`/`RIGHT`, which is the
     * obvious way to do it and is a trap. A DPAD press is a caret move only while there is
     * somewhere for the caret to go; at the ends of the text it reverts to what it really is,
     * a focus movement, and focus leaving the text box takes the keyboard down with it. So
     * the gesture worked until you slid past the end of what you had written - which is
     * exactly when a long drag runs off the end of the space bar too, and looks for all the
     * world like the keyboard closing because your finger left the key.
     *
     * Naming a position instead means naming one that exists, so the move is clamped: never
     * before the start, and never past what the field says is actually there. Running out of
     * text now stops the caret, which is what it should have done all along.
     *
     * The word in progress is let go first, and **without being corrected**. Moving the caret
     * is not finishing a word - it is going back to look at something - and having the
     * keyboard seize the moment to rewrite what you had just typed would be a nasty surprise
     * from a gesture that is supposed to be about looking rather than changing.
     */
    override fun onCursorSlide(view: KeyView, steps: Int) {
        if (steps == 0) return
        val ic = currentInputConnection ?: return
        if (composer.isComposing) finishWord(appending = "", correcting = false)
        phantomSpace = false

        try {
            // From the near edge of any selection, so the first move out of a selected range
            // collapses it the way an arrow key would rather than jumping from its middle.
            val from = if (steps > 0) maxOf(selStart, selEnd) else minOf(selStart, selEnd)
            val target = if (steps > 0) {
                // Only as far as there is text. Asking for what is there costs one small
                // round-trip and is the whole of the clamp - the field is the only thing that
                // knows how long its own contents are.
                val room = ic.getTextAfterCursor(steps, 0)?.length ?: 0
                from + minOf(steps, room)
            } else {
                maxOf(0, from + steps)
            }
            if (target == selStart && target == selEnd) return
            ic.setSelection(target, target)
            // Locally, because the field's own report of this move arrives after the next
            // step has already been asked for, and a slide that waited for it would crawl.
            selStart = target
            selEnd = target
        } catch (e: Exception) {
            return
        }
        updateAutoCaps()
    }

    private fun commitLetter(view: KeyView) {
        // What the key produces is read *before* shift is spent, and the order is the whole
        // of it: turning a one-shot off flips every key on the board back to lowercase, and
        // this key is one of them. Asking it afterwards asks a key that has already changed
        // its mind, so a single tap on shift capitalised nothing at all.
        val text = view.output()
        // A one-shot shift is spent by the letter it capitalised. A locked one is not.
        if (shiftState == ShiftState.ONCE) setShift(ShiftState.OFF)
        type(text)
    }

    /**
     * Puts [text] into the field, as part of the word in progress or as the end of it.
     *
     * **Everything the user types goes through here**, and it must, because of how composing
     * text works: `commitText` does not append next to a composing word, it *replaces* it. So
     * a character committed directly while a word was in progress deleted that word - typing
     * `bi sakal` and then holding for a `?` left `bi ?`, with `sakal` gone, because the `?`
     * landed on the composing region rather than after it.
     *
     * Letters extend the word. Anything else ends it, and is committed together with it in
     * one operation. A field that wants no suggestions - a password, a code - never composes
     * at all, so nothing provisional is left in it and nothing is learned from it.
     */
    private fun type(text: String) {
        takeBackPhantomSpace(text)
        if (privateField || !composer.extendsWord(text)) {
            finishWord(appending = text)
            return
        }
        composer.append(text)
        showComposing()
    }

    /**
     * Puts the word in progress into the field as *composing* text, and offers suggestions.
     *
     * Composing rather than committed is what makes a correction one operation instead of ten:
     * the app is told this much is provisional, underlines it, and lets it be replaced whole.
     */
    private fun showComposing() {
        val ic = currentInputConnection
        try {
            ic?.setComposingText(composer.typed, 1)
        } catch (e: Exception) {
            // The field can go away between the finger landing and the key being read.
        }
        refreshCandidates()
    }

    /**
     * Works out what to offer for the word in progress.
     *
     * The literal text is always first. That is what makes an automatic correction safe to
     * make at all: whatever the keyboard decides, what the user actually typed is one tap
     * away, and tapping it teaches the keyboard that it was a word.
     */
    private fun refreshCandidates() {
        val bar = host?.bar ?: return
        val typed = composer.typed
        if (typed.isEmpty()) {
            // Nothing typed: offer what tends to follow the last word, if anything has been
            // learned. Empty on a fresh install, and better the more the keyboard is used.
            val next = suggester()?.following(previousWord)?.map { it.word }.orEmpty()
                .take(BAR_SLOTS)
            offered = next
            bar.setWords(next, emphasised = -1)
            return
        }

        // The literal goes up immediately, before anything has been searched for. The bar is
        // never empty and never lags the keys, whatever the dictionary is busy doing.
        offered = listOf(typed)
        offeredFor = typed
        bar.setWords(offered, emphasised = -1)

        val engine = suggester() ?: run {
            // Not ready yet. The bar already shows what was typed; this asks for the
            // dictionary and comes back when it has one.
            warmSuggester()
            return
        }
        val previous = previousWord
        val generation = ++searchGeneration
        searches.execute {
            // Overtaken while it sat in the queue. Typing four letters quickly used to mean
            // four searches run one after another, with the one whose answer anybody wanted
            // waiting behind three that had already been superseded - so the suggestions for
            // the fourth letter arrived three searches late. Only the newest is worth doing.
            if (generation != searchGeneration) return@execute
            val suggestions = try {
                engine.candidates(typed, previous)
            } catch (e: Exception) {
                emptyList()
            }
            onMain.post {
                // Typed on since this was asked for, so the answer is about the wrong word.
                if (generation != searchGeneration) return@post
                val current = host?.bar ?: return@post

                // The literal first, then the two best guesses - three in all, which is what
                // the bar shows. The order is the display order: left is what was typed, the
                // middle is the best guess, the right is the runner-up.
                val words = ArrayList<String>(BAR_SLOTS)
                words.add(typed)
                for (candidate in suggestions) {
                    if (words.size >= BAR_SLOTS) break
                    words.add(candidate.word)
                }
                offered = words
                offeredFor = typed

                // Which word a space would take, marked before it is taken rather than
                // discovered afterwards. Only ever a *correction*: marking the literal when
                // nothing is going to happen put the accent on what the user had just typed,
                // which reads as the keyboard objecting to it.
                val willCorrect = autocorrect &&
                    composer.shouldAutocorrect(words.getOrNull(1), typedIsAWord())
                current.setWords(words, emphasised = if (willCorrect) 1 else -1)
            }
        }
    }

    /** Whether what has been typed is itself a word, which stops it being corrected away. */
    private fun typedIsAWord(): Boolean {
        val dictionary = suggester() ?: return false
        return dictionary.knows(composer.typed)
    }

    /**
     * Whether the space at the cursor is one the keyboard put there rather than one the user
     * pressed.
     *
     * Taking a suggestion appends a space, because the overwhelmingly common next thing is
     * another word. But the next thing is sometimes a full stop, and `hello !` is not what
     * anybody meant - the space was the keyboard guessing, and a guess should give way to
     * what was actually typed. So the space is remembered as provisional, and the punctuation
     * that attaches to a word takes it back. See [ATTACHING].
     */
    private var phantomSpace = false

    /**
     * Where the caret is, as last reported by the field.
     *
     * An input method is not told the contents of the box it is typing into and cannot ask
     * where the cursor is; it is *told*, through [onUpdateSelection], and this is that. Kept
     * because sliding the space bar has to name an absolute position to move the caret to,
     * and because it is then updated locally as the slide runs - the field's own report of
     * each move arrives too late to base the next one on.
     */
    private var selStart = 0
    private var selEnd = 0

    /** A suggestion was tapped. */
    private fun takeSuggestion(index: Int) {
        val word = offered.getOrNull(index) ?: return
        if (composer.isComposing) {
            // Tapping the literal is how a correction is refused, and the honest reading of
            // that is "this is a word" - so it is learned, and stops being corrected from
            // then on. Better than remembering a grudge: it makes the keyboard right.
            composer.replaceWith(word)
            finishWord(appending = " ")
            phantomSpace = true
        } else {
            // A next-word prediction, with nothing being composed.
            val spaced = previousWord != null
            commit(if (spaced) "$word " else word)
            phantomSpace = spaced
            previousWord = word
            learned?.learn(word)
            refreshCandidates()
        }
    }

    /**
     * Backspacing into a word that was already finished picks it back up.
     *
     * A word ends when you press space, and the keyboard stops thinking about it - which is
     * right until you come back and delete the end of it, at which point you are plainly
     * typing that word again and the keyboard is the only one in the room who does not think
     * so. Every suggestion, every correction and the whole dictionary go quiet, over a word
     * that is half-typed in front of it. So the word comes out of the field and back into the
     * composer, one character shorter, exactly as though it had never been finished.
     *
     * Three conditions, and each of them is a way this goes wrong:
     * - **Not while repeating.** Long-press delete runs twenty-five times a second and this
     *   asks the app three questions; two blocking round-trips in that loop is what froze the
     *   phone once already. Held backspace stays the one-way path it was made into.
     * - **Not with a selection.** Backspace over selected text deletes the selection, and
     *   there is no word being edited.
     * - **Not mid-word.** Composing text has to cover the whole word. If the cursor is inside
     *   one, recomposing the part in front of it would leave the rest outside the region, and
     *   taking a suggestion would then insert it into the middle of the word.
     *
     * @return true when the word was resumed and the caller should do nothing further.
     */
    private fun resumeWordBeforeCursor(ic: InputConnection): Boolean {
        // A password field has no suggestions and learns nothing, so there is no word to pick
        // up - only a round-trip asking the field to hand back its own contents, which is
        // exactly the thing not to do there.
        if (privateField) return false
        // Selected text is deleted by backspace, and there is no word being edited then. Read
        // from what the field last reported rather than by asking it: [onUpdateSelection] is
        // told about every selection there ever is, so asking would be paying for an answer
        // already in hand.
        if (selStart != selEnd) return false
        try {
            val around = around(ic) ?: return false
            val before = around.first
            val after = around.second?.firstOrNull()
            if (after != null && composer.extendsWord(after.toString())) return false

            // Two ways the caret can be sitting at the end of a finished word: right after
            // its last letter, or after the space that ended it. The second is by far the
            // ordinary one, because pressing space is *how* a word gets finished - so the
            // first backspace after a word is almost always a backspace over a space, and a
            // version of this that only looked for a letter found nothing and did nothing.
            val endedBySpace = before.endsWith(" ")
            val word = (if (endedBySpace) before.dropLast(1) else before)
                .takeLastWhile { composer.extendsWord(it.toString()) }
            if (word.isEmpty()) return false

            // Deleting the space hands the word back whole - it is a word you have stopped
            // finishing, not one you are shortening. Deleting into the word itself takes the
            // letter. Either way, what is left becomes the word in progress again.
            val kept = if (endedBySpace) word else word.dropLast(1)

            // The word comes out of the field and goes back as composing text, rather than
            // being left where it is: `setComposingText` has no region to replace yet, so it
            // would insert a second copy alongside the first.
            val removing = word.length + if (endedBySpace) 1 else 0

            // As one edit, so the field never draws itself with the word missing.
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(removing, 0)
                composer.resume(kept)
                if (kept.isEmpty()) ic.finishComposingText() else ic.setComposingText(kept, 1)
            } finally {
                ic.endBatchEdit()
            }

            // The word before the one just picked up, so next-word prediction is about the
            // right pair. Without this `previousWord` is still this very word - it was set
            // when the space finished it - and the keyboard would offer what tends to follow
            // the word it is currently in the middle of.
            previousWord = before
                .dropLast(removing)
                .trimEnd()
                .takeLastWhile { composer.extendsWord(it.toString()) }
                .ifEmpty { null }

            refreshCandidates()
            updateAutoCaps()
            return true
        } catch (e: Exception) {
            // The field can go away mid-keystroke. Fall through to the ordinary delete.
            return false
        }
    }

    /**
     * The text on both sides of the caret, in as few round-trips as the platform allows.
     *
     * Every one of these is a blocking call into the *other* app's process, and that app can
     * be doing anything at all - so they are the one thing on the typing path whose cost is
     * not this keyboard's to control. Two blocking calls in the backspace repeat once froze
     * the phone; this is the same lesson applied where a single press pays it.
     *
     * `getSurroundingText` answers both halves at once and has been there since Android 12,
     * which is most phones. Below that it is two calls, as it always was.
     */
    private fun around(ic: InputConnection): Pair<String, CharSequence?>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val text = ic.getSurroundingText(RESUME_LOOKBACK, 1, 0)
            if (text == null) {
                null
            } else {
                val at = text.selectionStart.coerceIn(0, text.text.length)
                text.text.substring(0, at) to text.text.subSequence(at, text.text.length)
            }
        } else {
            ic.getTextBeforeCursor(RESUME_LOOKBACK, 0)?.toString().orEmpty() to
                ic.getTextAfterCursor(1, 0)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Removes the space a suggestion added, when what follows it belongs against the word.
     *
     * The state is checked as well as the flag. A flag alone would be enough almost always,
     * and "almost" is doing real work here: the app can move the cursor underneath the
     * keyboard between the suggestion and the punctuation, and deleting a character that
     * turned out not to be our space would be a bug of exactly the kind that is impossible to
     * report. One round-trip to look, on a punctuation keystroke, is affordable - unlike in
     * the backspace repeat, where two of them once froze the phone.
     */
    private fun takeBackPhantomSpace(text: String) {
        val attaching = phantomSpace && text.length == 1 && text[0] in ATTACHING
        phantomSpace = false
        if (!attaching) return
        try {
            val ic = currentInputConnection ?: return
            if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
        } catch (e: Exception) {
            // The field can go away mid-keystroke. The punctuation still gets typed.
        }
    }

    /**
     * Ends the word in progress, correcting it if the keyboard is confident.
     *
     * @param appending what triggered the end - the space or punctuation that follows the
     *   word - which is committed along with it in one operation so the field never briefly
     *   holds one without the other.
     * @param correcting false to leave the word exactly as typed. For the endings that are
     *   not the user finishing a word: sliding the caret away from one is going back to look
     *   at something, and a keyboard that rewrote the word on the way past would be answering
     *   a question nobody asked.
     */
    private fun finishWord(appending: String, correcting: Boolean = true) {
        val ic = currentInputConnection
        if (!composer.isComposing) {
            if (appending.isNotEmpty()) commit(appending)
            return
        }

        // Only correct on suggestions that are actually about this word. The search runs off
        // the main thread, so on a fast run of keys the last answer to arrive may be about a
        // shorter prefix - and correcting a word using a guess made about half of it is worse
        // than not correcting at all.
        val current = offeredFor == composer.typed
        val correction = if (correcting && autocorrect && current &&
            composer.shouldAutocorrect(offered.getOrNull(1), typedIsAWord())
        ) {
            offered.getOrNull(1)
        } else {
            null
        }
        val finished = composer.finish(correction)

        try {
            ic?.setComposingText(finished, 1)
            ic?.finishComposingText()
            if (appending.isNotEmpty()) ic?.commitText(appending, 1)
        } catch (e: Exception) {
            composer.reset()
            return
        }

        // A correction can be two words - `helloworld` becoming `hello world` - and what
        // gets learned and remembered has to be words, not the string that replaced them.
        // Learning `hello world` as one entry would offer it back as a single word forever.
        val words = finished.split(' ').filter { it.isNotBlank() }
        if (!privateField) {
            var before = previousWord
            for (word in words) {
                learned?.learn(word)
                before?.let { learned?.learnPair(it, word) }
                before = word
            }
        }
        previousWord = words.lastOrNull() ?: finished
        offered = emptyList()
        offeredFor = ""
        refreshCandidates()
    }

    /**
     * Puts text into the field, replacing anything currently composing.
     *
     * The raw operation, and the replacing is the part to be careful about: `commitText`
     * substitutes for the composing region rather than following it. Almost nothing should
     * call this directly - [type] is what handles a character the user has pressed. This is
     * for text that is meant to stand on its own, where nothing is composing by construction.
     */
    private fun commit(text: String) {
        val ic = currentInputConnection ?: return
        try {
            ic.commitText(text, 1)
        } catch (e: Exception) {
            // The field can go away between the finger landing and the key being read.
        }
        if (text != " ") lastSpace = 0L
    }

    /**
     * Space, and the double tap that makes a full stop.
     *
     * Two spaces in quick succession become a full stop and a space, which is what every
     * phone has done since well before the one this keyboard is drawn after.
     *
     * Only after a letter or a digit, so that a second space at the start of a line, or one
     * following punctuation that is already there, stays a space. Backspace currently just
     * deletes into the result; putting the two spaces back instead belongs with the rest of
     * the undo behaviour, once there is something tracking what the keyboard changed.
     */
    private fun space() {
        phantomSpace = false
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection

        // A space is what ends a word, so the correction happens here - and it happens in the
        // same operation as the space itself, so the field never briefly shows one without
        // the other.
        if (composer.isComposing) {
            finishWord(appending = " ")
            lastSpace = now
            updateAutoCaps()
            return
        }

        if (ic != null && now - lastSpace < DOUBLE_SPACE_MS && endsWithLetter(ic)) {
            try {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
            } catch (e: Exception) {
                return
            }
            lastSpace = 0L
            updateAutoCaps()
            return
        }
        commit(" ")
        lastSpace = now
        updateAutoCaps()
        returnFromSymbols()
    }

    /**
     * A space ends the detour to the symbol page.
     *
     * The `&123` page is somewhere you go for one or two characters - a bracket, a currency
     * sign, an ampersand - and then carry on with the sentence. Staying there after a space
     * means every such trip ends with pressing `abc` as well, which is a keystroke spent on
     * telling the keyboard something it could already tell.
     */
    private fun returnFromSymbols() {
        val view = keyboard ?: return
        if (view.currentLayout().id.startsWith("symbols")) showLetters()
    }

    /** True when what is behind the cursor is a space with a letter before it. */
    private fun endsWithLetter(ic: InputConnection): Boolean = try {
        val before = ic.getTextBeforeCursor(2, 0)
        before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
    } catch (e: Exception) {
        false
    }

    /**
     * Backspace.
     *
     * @param repeating true while the key is being held down and firing many times a second.
     *
     * The distinction matters a great deal, and getting it wrong locked the phone up. A
     * single press can afford to ask the application questions: is anything selected, should
     * the next letter be a capital. Those are **synchronous round trips** to the app being
     * typed into - the keyboard sends the question and blocks its own main thread until an
     * answer comes back. Once per press that is nothing. Twenty-five times a second, into an
     * application that may be busy drawing or may not answer promptly, it is the keyboard
     * waiting on somebody else's main thread over and over, which is exactly what a frozen
     * phone looks like.
     *
     * So a held backspace asks nothing and tells: it deletes, and it defers the questions and
     * the dictionary work until the finger comes off. See [finishRepeating].
     */
    private fun backspace(repeating: Boolean = false) {
        phantomSpace = false
        val ic = currentInputConnection ?: return

        // Backspace straight after a correction means "no, I meant what I typed" - so it puts
        // the typed word back rather than nibbling a character off the keyboard's guess. A
        // keyboard that gets this wrong makes every unwanted correction into a fight.
        composer.takeUndo()?.let { (applied, original) ->
            try {
                ic.deleteSurroundingText(applied.length + 1, 0)
                ic.commitText("$original ", 1)
            } catch (e: Exception) {
                return
            }
            // Learned, because the user has now said twice that this is a word.
            if (!privateField) learned?.learn(original)
            previousWord = original
            refreshCandidates()
            return
        }

        // Inside the word being typed, the deletion is of composing text.
        if (composer.backspace()) {
            try {
                ic.setComposingText(composer.typed, 1)
                if (!composer.isComposing) ic.finishComposingText()
            } catch (e: Exception) {
                return
            }
            // Suggestions for every character of a word being torn down are suggestions
            // nobody reads; they are worked out once, when the tearing down stops.
            if (!repeating) refreshCandidates()
            return
        }

        // Nothing was being composed, so the cursor is in text the field already owns. If it
        // is sitting at the end of a word, that word is picked back up rather than having a
        // character quietly shaved off it - see [resumeWordBeforeCursor].
        if (!repeating && resumeWordBeforeCursor(ic)) return

        try {
            if (repeating) {
                // One-way, and no questions. `deleteSurroundingText` is dispatched and
                // forgotten where `getSelectedText` waits for a reply.
                ic.deleteSurroundingText(1, 0)
            } else {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    // Sent as a key event rather than as a deletion so that fields which
                    // watch for the key - a search box that closes on an empty backspace, a
                    // chip field that removes a chip - still see it.
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                } else {
                    ic.commitText("", 1)
                }
            }
        } catch (e: Exception) {
            return
        }
        lastSpace = 0L
        if (!repeating) updateAutoCaps()
    }

    /** The catching up a held backspace put off: done once, when the finger comes off. */
    private fun finishRepeating() {
        refreshCandidates()
        updateAutoCaps()
    }

    private fun enter() {
        phantomSpace = false
        // Enter ends the word, but takes no correction with it: pressing send or search is a
        // commitment to what is on screen, and changing it at that moment would be the worst
        // possible time to be clever.
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val ic = currentInputConnection ?: return
        try {
            if (!multiLine && !noAction && action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                // Nothing left composing when the text goes. An app that clears its box on
                // send does not tell the keyboard, so a region left open here is one the next
                // keystroke would be written into.
                ic.finishComposingText()
                ic.performEditorAction(action)
                // A sent message is the end of what was being said. The next word does not
                // follow the last one, so nothing should be predicted from it.
                composer.reset()
                previousWord = null
                offered = emptyList()
                offeredFor = ""
                host?.bar?.clear()
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        } catch (e: Exception) {
            return
        }
        updateAutoCaps()
    }

    /**
     * Shift, tapped.
     *
     * One tap arms it for a single letter, a second tap soon after locks it on, and a tap
     * while it is on in either form turns it off. The double tap is measured rather than
     * counted so that two deliberate taps a second apart are two decisions rather than one
     * lock, which is the difference between the phone's shift and a fussy one.
     */
    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        val next = when {
            shiftState == ShiftState.ONCE && now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.LOCKED
            shiftState == ShiftState.OFF -> ShiftState.ONCE
            else -> ShiftState.OFF
        }
        lastShiftTap = now
        setShift(next)
    }

    private fun setShift(state: ShiftState) {
        shiftState = state
        keyboard?.setShiftState(
            on = state != ShiftState.OFF,
            locked = state == ShiftState.LOCKED
        )
    }

    // ---------------------------------------------------------------- dictation

    /** The recogniser in use, built on first use and rebuilt when the language changes. */
    private var voice: VoiceInput? = null

    /** Which language [voice] was built for, so a switch does not dictate in the wrong one. */
    private var voiceFor: String? = null

    /** The offline models: what has been downloaded, and fetching what has not. */
    private val voskModels by lazy { VoskModels(this) }

    /** Whether to dictate on the phone rather than through the platform. See the setting. */
    private var offlineVoice = true

    /** How much of the current dictation has been put in the field, so it can be replaced. */
    private var dictated = ""

    /**
     * The microphone, pressed.
     *
     * A toggle rather than a hold: dictating a sentence takes longer than anyone wants to
     * keep a thumb down for, and the button has to be able to say "still listening" - which
     * is what the accent on it means while this is running.
     */
    private fun toggleVoice() {
        val engine = voice
        if (engine != null && engine.isListening) {
            engine.stop()
            return
        }

        // An input method cannot ask for a permission itself. See [VoicePermissionActivity].
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(
                    Intent(this, VoicePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Nothing to be done, and not worth taking the keyboard down over.
            }
            return
        }

        // The word in progress is finished first, without a correction: what is dictated next
        // is a new thought, and leaving a half-typed word composing underneath it would mean
        // the first thing said replaced it.
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }

        dictated = ""
        val listener = object : VoiceInput.Listener {
            override fun onPartial(text: String) = showDictation(text)

            override fun onFinal(text: String) {
                showDictation(text)
                // Committed, so that what was said becomes ordinary text rather than staying
                // provisional, and so a following word starts cleanly.
                try {
                    currentInputConnection?.finishComposingText()
                } catch (e: Exception) {
                    // The field can go away mid-sentence.
                }
                dictated = ""
                previousWord = text.substringAfterLast(' ').ifBlank { null }
            }

            override fun onStopped(error: String?) {
                host?.bar?.listening = false
                dictated = ""
                if (error != null) {
                    // Said on the bar rather than swallowed. A microphone that does nothing
                    // and explains nothing is the worst of the possible outcomes.
                    host?.bar?.setMessage(error)
                    onMain.postDelayed({ host?.bar?.setMessage(null); refreshCandidates() }, MESSAGE_MS)
                } else {
                    refreshCandidates()
                }
            }
        }

        host?.bar?.setMessage(null)
        startListening(listener)
    }

    /**
     * Chooses an engine and starts it, fetching the offline model if that is what is missing.
     *
     * The order is the whole policy. Vosk if it is wanted and ready; the model downloaded if
     * it is wanted and merely absent; and the platform recogniser when Vosk has nothing to
     * offer for this language - which for Macedonian is always, because Vosk publishes no
     * model for it.
     */
    private fun startListening(listener: VoiceInput.Listener) {
        val code = language.language

        if (offlineVoice && voskModels.supports(code)) {
            if (voskModels.isDownloaded(code)) {
                val engine = engineFor(code, offline = true)
                engine.start(listener)
                host?.bar?.listening = engine.isListening
                return
            }
            fetchModelThenListen(code, listener)
            return
        }

        // Either the platform was asked for, or Vosk cannot help with this language.
        val engine = engineFor(code, offline = false)
        engine.start(listener)
        host?.bar?.listening = engine.isListening
    }

    /**
     * Downloads the model, saying so, and starts dictating when it arrives.
     *
     * Forty megabytes takes long enough that doing it silently would look like the microphone
     * being broken again, so the bar carries the progress. It happens once.
     */
    private fun fetchModelThenListen(code: String, listener: VoiceInput.Listener) {
        if (voskModels.isBusy) {
            host?.bar?.setMessage("still downloading the speech model")
            return
        }
        host?.bar?.setMessage("downloading speech model...")
        voskModels.fetch(
            language = code,
            onProgress = { percent -> host?.bar?.setMessage("downloading speech model $percent%") },
            onDone = { directory, error ->
                host?.bar?.setMessage(null)
                if (directory == null) {
                    // Falls back rather than giving up: no network is a reason to use the
                    // other engine, not a reason to have no dictation.
                    val engine = engineFor(code, offline = false)
                    engine.start(listener)
                    host?.bar?.listening = engine.isListening
                    if (error != null) host?.bar?.setMessage(error)
                    return@fetch
                }
                val engine = engineFor(code, offline = true)
                engine.start(listener)
                host?.bar?.listening = engine.isListening
            }
        )
    }

    /** The engine for this language, kept between uses and rebuilt when either changes. */
    private fun engineFor(code: String, offline: Boolean): VoiceInput {
        val wanted = if (offline) "vosk:$code" else "platform:$code"
        voice?.let { if (voiceFor == wanted) return it }
        voice?.destroy()
        val engine: VoiceInput =
            if (offline) VoskVoiceInput(this, voskModels, code) else PlatformVoiceInput(this)
        voice = engine
        voiceFor = wanted
        return engine
    }

    /**
     * Puts what has been heard so far into the field, as composing text.
     *
     * Composing rather than committed for the same reason a typed word is: a recogniser
     * revises what it thought it heard as the sentence goes on, and provisional text can be
     * replaced whole instead of being deleted a character at a time.
     */
    private fun showDictation(text: String) {
        dictated = text
        try {
            currentInputConnection?.setComposingText(text, 1)
        } catch (e: Exception) {
            // The field can go away mid-sentence.
        }
    }

    private fun stopVoice() {
        voice?.let { if (it.isListening) it.stop() }
        host?.bar?.listening = false
    }

    // ---------------------------------------------------------------- emoji

    /**
     * Swaps the keys for the emoji panel.
     *
     * The word in progress is finished first, without a correction and without a trailing
     * space: reaching for an emoji is the end of a word but not the end of a sentence, and a
     * space the user did not ask for would have to be deleted before the emoji went in.
     */
    private fun showEmoji() {
        val stack = host ?: return
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        val panel = emoji ?: EmojiPanel(this, palette).also { built ->
            built.onEmojiPicked = { glyph ->
                // Committed and left open. Emoji arrive in runs, and a panel that closed
                // after each one would have to be reopened for the next.
                commit(glyph)
                previousWord = null
            }
            built.onBackToLetters = { hideEmoji() }
            built.onBackspace = { backspace() }
            built.onSearchTapped = { startEmojiSearch() }
            built.onGifPicked = { sendGif(it) }
            emoji = built
        }
        // Always opens browsing, never in whatever search was left behind. The panel outlives
        // any one use of it - it is built once and kept - so without this, opening emoji after
        // having once searched for something reopened those results instead of the categories.
        searchingEmoji = false
        panel.query = ""
        panel.searchMode = false
        // And on the emoji rather than on whatever the last visit left open, for the same
        // reason: the emoji key was tapped, so the emoji are what it should show.
        panel.mode = EmojiPanel.Mode.EMOJI

        panel.applyPalette(palette)
        val keys = stack.keyboard
        panel.setMetrics(
            keys.unitWidth(), keys.keyHeight(), keys.gutter(),
            keys.bottomReserved(), keys.contentHeight()
        )
        stack.showPanel(panel, withKeys = false)
        // The bar belongs to the letters; the panel has its own control row.
        host?.bar?.clear()
    }

    /**
     * Opens the keyboard's settings, which are a page of their own.
     *
     * A whole screen rather than a panel down here - see [KeyboardSettingsActivity]. An input
     * method has no task of its own to start an activity in, so it needs `NEW_TASK`; and the
     * keyboard is dismissed first, because leaving it up over the page it just opened would
     * cover the thing the user went there to change.
     */
    private fun showSettings() {
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        requestHideSelf(0)
        try {
            startActivity(
                Intent(this, KeyboardSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Nothing to be done, and not worth taking the keyboard down over.
        }
    }

    private fun hideEmoji() {
        searchingEmoji = false
        emoji?.let {
            it.query = ""
            it.searchMode = false
            // Writes the recents and re-reads them, so the row is up to date the next time
            // the panel opens rather than shifting about while it is in use.
            it.onClosed()
        }
        host?.hidePanel()
        refreshCandidates()
    }

    /**
     * Puts a GIF into whatever is being typed into.
     *
     * Two paths, and both are needed. A field that says it takes pictures - a messaging app's
     * box, most of the time - is handed the file itself, through the app's own [FileProvider],
     * with a read grant that Android hands over with it and takes back afterwards. Everything
     * else, which is every plain text box on the phone, is handed the address: it is what the
     * GIF actually is, it pastes, and at the other end most things unfurl it into the picture.
     *
     * The address is also the fallback for both of the ways the first path can fail - the
     * fetch not completing, and the receiving app refusing the content after saying it would
     * take it - because a tap that produces nothing at all reads as the keyboard being broken.
     */
    private fun sendGif(gif: Gif) {
        val info = currentInputEditorInfo
        val accepts = info != null && EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(GIF_MIME, it)
        }
        if (!accepts) {
            commit(gif.sendUrl)
            previousWord = null
            return
        }
        GifSearch.download(this, gif) { file ->
            if (file == null || !commitGif(file)) commit(gif.sendUrl)
            previousWord = null
        }
    }

    /** @return whether the field took it. */
    private fun commitGif(file: File): Boolean {
        val ic = currentInputConnection ?: return false
        val info = currentInputEditorInfo ?: return false
        return try {
            InputConnectionCompat.commitContent(
                ic,
                info,
                InputContentInfoCompat(
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file),
                    ClipDescription(file.name, arrayOf(GIF_MIME)),
                    null
                ),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (e: Exception) {
            // A provider that cannot serve the file, or a field that has gone away since the
            // GIF was tapped. Either way the caller falls back to the address.
            false
        }
    }

    /** The search bar was tapped: from here, letters build a query rather than a word. */
    private fun startEmojiSearch() {
        val panel = emoji ?: return
        searchingEmoji = true
        panel.query = ""
        // The keys come back underneath, and the panel shrinks to leave room for them. An
        // input method cannot type into a text box of its own, so without this the search bar
        // is a picture of a search bar.
        panel.searchMode = true
        val keys = host?.keyboard ?: return
        panel.setMetrics(
            keys.unitWidth(), keys.keyHeight(), keys.gutter(),
            keys.bottomReserved(), keys.contentHeight()
        )
        host?.showPanel(panel, withKeys = true)
    }

    /**
     * A key was pressed while the emoji panel's search box was active.
     *
     * @return true when the keystroke was consumed by the search rather than the text field.
     */
    private fun routeToEmojiSearch(view: KeyView): Boolean {
        if (!searchingEmoji) return false
        val panel = emoji ?: return false
        when (view.key.action) {
            null -> panel.query += view.output()
            Action.SPACE -> panel.query += " "
            Action.BACKSPACE -> {
                if (panel.query.isEmpty()) {
                    // Backspacing out of an empty search is how one leaves it, rather than it
                    // being a dead key with nothing to delete. The panel goes back to its
                    // full height and the keys go away with it.
                    searchingEmoji = false
                    panel.searchMode = false
                    host?.showPanel(panel, withKeys = false)
                } else {
                    panel.query = panel.query.dropLast(1)
                }
            }
            // Enter, or anything that changes the page, ends the search rather than being
            // swallowed by it.
            else -> {
                searchingEmoji = false
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- pages

    private fun toggleSymbols() {
        val view = keyboard ?: return
        if (view.currentLayout().id.startsWith("symbols")) showLetters() else showSymbols()
    }

    private fun showSymbols() {
        val view = keyboard ?: return
        view.setLayout(Layouts.SYMBOLS_1)
        view.setSymbolsLabel("abc")
        refreshEnterKey()
    }

    private fun flipSymbolPage() {
        val view = keyboard ?: return
        val next = if (view.currentLayout().id == Layouts.SYMBOLS_1.id) Layouts.SYMBOLS_2 else Layouts.SYMBOLS_1
        view.setLayout(next)
        view.setSymbolsLabel("abc")
        refreshEnterKey()
    }

    private fun showLetters() {
        val view = keyboard ?: return
        view.setLayout(language)
        view.setSymbolsLabel("&123")
        // The grid was rebuilt, so the enter key's label and the shift state are sitting on
        // the old set of key views and have to be put back onto the new one.
        refreshEnterKey()
        setShift(shiftState)
    }

    /**
     * The system has switched which language this keyboard currently is.
     *
     * Android's model for a keyboard that speaks several languages is that each is a
     * *subtype* of the one input method, and the globe in the navigation bar - the system's
     * own, which belongs to no keyboard - is what moves between them. So there is no
     * language key here and there should not be: the keyboard is never asked to choose, it
     * is told, and its whole job is to lay itself out accordingly.
     *
     * The subtypes themselves are declared in `res/xml/method.xml`; this is the other half.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        adoptSubtype(newSubtype)
    }

    /**
     * Reads the current subtype and becomes that language.
     *
     * Called on every showing as well as on the change itself, because a subtype can be
     * switched while the keyboard is hidden - the switch is made from the navigation bar,
     * and the keyboard is not necessarily up when it happens.
     */
    private fun adoptSubtype(subtype: InputMethodSubtype? = currentSubtype()) {
        val tag = subtype?.let {
            @Suppress("DEPRECATION")
            it.languageTag.ifEmpty { it.locale }
        }
        // What the system says, held to what the user has actually turned on. Disabling the
        // language you are currently typing in is allowed, and has to land somewhere.
        val allowed = KeyboardLanguages.enabled(themeManager)
        val fromSystem = Layouts.forLanguageTag(tag)
        val next = if (allowed.any { it.id == fromSystem.id }) fromSystem else allowed.first()
        if (next.id == language.id) return
        language = next
        // The space bar names the language, so it changes here too - including when the
        // symbol page is up and the keys themselves are left alone.
        keyboard?.setSpaceLabel(next.name)
        // A different language is a different dictionary, and the first word typed after
        // reaching for the globe should not be the one that pays for loading it.
        warmSuggester()
        // Only redraw if letters are what is on screen. Switching language underneath the
        // symbol page would throw away the page the user is looking at.
        if (keyboard?.currentLayout()?.language?.isNotEmpty() == true) showLetters()
    }

    private fun currentSubtype(): InputMethodSubtype? = try {
        getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
    } catch (e: Exception) {
        null
    }

    /**
     * Internal rather than private: [ClipboardStore] keeps its history in the same
     * device-local file this names, and one name shared beats two spellings of it.
     */
    internal companion object {

        /** What the bar says when the clipboard mark is tapped and there is nothing on it. */
        const val NOTHING_COPIED = "Nothing on the clipboard"

        /** What a GIF is, for the field that is asked whether it takes one. See [sendGif]. */
        const val GIF_MIME = "image/gif"

        /**
         * Bookkeeping that belongs to this device and must not travel to another.
         *
         * A separate file from the shell's, because `PrefsBackup` serialises that one to
         * Drive wholesale and this is a record of something done to *this* phone's settings.
         */
        const val KEYBOARD_PREFS = "wp81_keyboard"
        const val KEY_SUBTYPES_ENABLED_FOR = "subtypes_enabled_for"

        /** How many the bar shows. See [CandidateBar]. */
        const val BAR_SLOTS = 3

        /** How long a message stays on the bar before the suggestions come back. */
        const val MESSAGE_MS = 2_500L

        const val DOUBLE_TAP_MS = 400L
        /**
         * Punctuation that belongs against the word before it, with no space between.
         *
         * Closing marks and sentence enders only. An opening bracket is deliberately absent -
         * `he said (quietly)` wants its space - and so is the hyphen, which is a word joiner
         * as often as it is a dash and guessing wrong either way is worse than leaving it.
         * `@` and `/` are here because the contextual key produces them in address fields,
         * where a space in front of one is never right.
         */
        /**
         * How far back to look for a word when backspacing into one.
         *
         * Longer than any word anybody is typing, and short enough that it is not a request
         * for the paragraph. The dictionary builder caps words at 32 characters.
         */
        const val RESUME_LOOKBACK = 48

        const val ATTACHING = ".,!?:;)]}%\u2026\"'\u2019@/"

        const val DOUBLE_SPACE_MS = 900L
    }
}
