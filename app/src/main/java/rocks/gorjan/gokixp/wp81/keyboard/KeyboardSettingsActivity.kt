package rocks.gorjan.gokixp.wp81.keyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.ThemeManager
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroSlider
import rocks.gorjan.gokixp.wp81.MetroToggle
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/**
 * The keyboard's settings, as a page of the phone's own Settings.
 *
 * A whole screen rather than a panel inside the keyboard. The first attempt put these on a
 * page that replaced the keys, reached by holding `&123`, on the reasoning that a setting you
 * notice while typing should be adjustable without leaving what you are typing in. That is
 * true and it was still wrong: Windows Phone's settings were pages - a big lowercase title, a
 * column of rows, the back key to leave - and a settings screen that is a keyboard-shaped
 * rectangle at the bottom of somebody else's app is not that. It also had nowhere to grow, and
 * there will be more of these.
 *
 * So it is an Activity, styled like every other page in the shell, and reached two ways: by
 * holding `&123` on the keyboard, and from Android's own keyboard settings, because it is
 * declared as this input method's `settingsActivity` in `res/xml/method.xml`.
 *
 * Built from the shell's own furniture - [MetroPageHeader], [MetroToggle], [MetroSlider] -
 * rather than new controls or anything from Material, for the same reason as everywhere else:
 * a page that invented its own switch would look like a different application.
 */
class KeyboardSettingsActivity : Activity() {

    private lateinit var palette: WP81Palette
    private lateinit var themeManager: ThemeManager

    private lateinit var holdValue: TextView
    private lateinit var vibrationValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = ThemeManager(this)
        palette = WP81Palette.from(themeManager)

        // The page is the background, so the system bars are painted to match rather than
        // sitting as two strips of a different black at either end.
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        page.addView(
            MetroPageHeader(this, palette).apply {
                setTitle("keyboard")
                onBack = { finish() }
            },
            wide()
        )

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(section("languages"))
        column.addView(languagesNote())
        column.addView(languageList())
        column.addView(section("typing"))
        column.addView(autocorrectRow())
        column.addView(autoCapsRow())
        column.addView(holdRow())
        column.addView(vibrationRow())
        column.addView(shortBottomRow())
        column.addView(section("gifs"))
        column.addView(giphyRow())
        column.addView(section("dictation"))
        column.addView(offlineVoiceRow())
        column.addView(section("try it"))
        column.addView(tryItNote())
        for (field in TEST_FIELDS) column.addView(testField(field.first, field.second))
        column.addView(section("about"))
        column.addView(about())

        page.addView(ScrollView(this).apply { addView(column, wide()) }, wide())

        // Held clear of the status bar and the gesture handle.
        //
        // Not optional on this target: an app built against Android 15 or later is laid out
        // edge to edge whether it asks to be or not, so a page that does nothing about it
        // draws its title underneath the clock. The colours behind both bars are already the
        // page's own, set above, so this only has to move the content - which is why it is
        // padding on the page rather than a window flag.
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(page)
    }

    // ---------------------------------------------------------------- rows

    /**
     * Whether a space may replace a word by itself.
     *
     * The note underneath matters as much as the switch. Somebody turning this off wants to
     * know they are not turning suggestions off with it, and somebody turning it on wants to
     * know what they are agreeing to.
     */
    private fun autocorrectRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardAutocorrect(), animated = false)
            onChanged = { themeManager.setWP81KeyboardAutocorrect(it) }
        }
        return row(
            "correct words automatically",
            "a space replaces the word with the keyboard's best guess. " +
                "suggestions are offered either way, and the first of them is always " +
                "exactly what you typed.",
            toggle
        )
    }

    /** Whether shift comes on by itself at the start of a sentence. */
    private fun autoCapsRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardAutoCapitalise(), animated = false)
            onChanged = { themeManager.setWP81KeyboardAutoCapitalise(it) }
        }
        return row(
            "capitalise sentences",
            "shift turns itself on after a full stop, and for the first letter of a field.",
            toggle
        )
    }

    private fun languagesNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), 0)
        addView(
            detail(
                "turn on as many as you like. the globe in the navigation bar moves " +
                    "between them, and the space bar says which one you are typing in."
            ),
            wide()
        )
    }

    /**
     * The languages, in a box of their own that scrolls.
     *
     * Twenty-two of them is longer than the rest of this page put together, and a list that
     * long inside the page simply pushes everything else off the bottom - somebody looking
     * for the autocorrect switch would scroll past Greek and Ukrainian to reach it. So the
     * list gets a fixed height and scrolls inside itself, which also makes it *look* like a
     * list of things to choose from rather than like the page having gone on too long.
     *
     * The height is a fraction of the screen rather than a number of rows: rows are as tall
     * as the text in them and that changes with the language, and the point of the box is
     * that the sections after it stay visible, which is a claim about the screen.
     */
    private fun languageList(): View {
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (layout in Layouts.ALL_LANGUAGES) addView(languageRow(layout), wide())
        }

        return object : ScrollView(this) {
            // A scroller inside a scroller, both vertical. Without this the page takes the
            // drag and the inner list never moves - the parent is entitled to intercept, and
            // by default it does. Claiming the gesture on the way down and giving it back at
            // the end means a drag that starts on a language scrolls the languages, and one
            // that starts anywhere else scrolls the page.
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        event.actionMasked != android.view.MotionEvent.ACTION_CANCEL
                )
                return super.onTouchEvent(event)
            }
        }.apply {
            // A ScrollView takes focus when it is laid out, and the page scrolls to whatever
            // has focus - which put the top of this box above the screen and took the heading
            // that names it with it. The box is scrolled by dragging it; it has no business
            // holding focus.
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isVerticalScrollBarEnabled = true
            // The bar is what says "there is more below this". Without it a box that happens
            // to end on a row boundary looks like the whole list.
            isScrollbarFadingEnabled = false
            addView(rows, wide())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * LANGUAGE_LIST_SHARE).toInt()
            )
        }
    }

    /**
     * One language, on or off.
     *
     * The switch writes the setting and then tells Android, which are two different things -
     * declaring a language is not the same as the system offering it. See [KeyboardLanguages].
     *
     * Turning off the last one is refused rather than prevented: the switch springs back, so
     * it is obvious what happened and why, where a switch that simply would not move looks
     * broken.
     */
    private fun languageRow(layout: KeyboardLayout): View {
        val toggle = MetroToggle(this, palette)
        toggle.set(KeyboardLanguages.isEnabled(themeManager, layout), animated = false)
        toggle.onChanged = { on ->
            val applied = KeyboardLanguages.setEnabled(this, themeManager, layout, on)
            if (!applied) toggle.set(true, animated = true)
        }
        return row(layout.name, whatItBrings(layout), toggle)
    }

    /**
     * What turning a language on actually gets you, said plainly.
     *
     * Twenty-two languages ship a layout and only two of them ship a word list, because a
     * word list is two megabytes and putting twenty-two in the app would cost more than
     * everything else in it put together. A language without one types perfectly well and
     * simply makes no suggestions - which is a real difference and one somebody is entitled
     * to know about before they turn it on rather than after they have typed a paragraph
     * waiting for a correction that was never coming.
     *
     * Dictation is the same shape of answer and a different list: Vosk publishes offline
     * models for about half of these and, as ever, not for Macedonian.
     */
    private fun whatItBrings(layout: KeyboardLayout): String {
        val words = hasDictionary(layout.language)
        val size = voskModels.sizeOf(layout.language)

        // Short enough for one line at this width, deliberately: a two-line note fits three
        // languages in the box and turns a list into a wall.
        val suggestions = if (words) "suggestions" else "no word list"
        val dictation =
            if (size > 0) "offline dictation, $size MB"
            else "dictation via the phone"

        return "$suggestions \u00b7 $dictation"
    }

    /** Only ever asked for [VoskModels.sizeOf] here - nothing on this page downloads one. */
    private val voskModels by lazy { VoskModels(this) }

    /** Whether a word list for [language] is in the app. See `Dictionary.load`. */
    private fun hasDictionary(language: String): Boolean = language in shippedDictionaries

    /**
     * The word lists actually in the APK, asked of the assets rather than listed here.
     *
     * A hardcoded list would be a second place to remember when one is added or dropped, and
     * the sort of thing that goes stale silently - the row would promise suggestions that
     * never came. One directory listing on the way into this page settles it.
     */
    private val shippedDictionaries: Set<String> by lazy {
        try {
            assets.list("keyboard").orEmpty()
                .filter { it.endsWith(".trie") }
                .map { it.removeSuffix(".trie") }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Whether a keystroke buzzes, and how hard.
     *
     * The switch says what it says: on means the keys vibrate, and the strength is right
     * underneath it where somebody who has just turned it on will look. Off means off, and
     * there is nothing to set.
     *
     * The far left of the slider is **the phone's own** rather than the weakest explicit
     * setting, and that is a real distinction rather than a label: there it goes through
     * `Haptics` and picks up whatever waveform the manufacturer tuned for a keystroke, and it
     * follows the system's touch-feedback switch. Anywhere else it drives the vibrator
     * directly and follows this slider instead. Left is where it starts, and the phone's tick
     * is a light one, so "further right is firmer" holds across the join.
     *
     * Nothing buzzes while the slider is moving - a control that fired on every pixel of
     * travel would be unusable. It buzzes once, when the finger comes off, which is when you
     * want to feel what you have chosen.
     */
    private fun vibrationRow(): View {
        val setting = themeManager.getWP81KeyboardVibration()
        vibrationValue = detail(vibrationText(setting))

        val slider = MetroSlider(this).apply {
            applyPalette(palette)
            visibility = if (setting == 0) View.GONE else View.VISIBLE
            value = sliderPositionOf(setting)
            onValueChanged = { fraction ->
                val strength = strengthOf(fraction)
                vibrationValue.text = vibrationText(strength)
                themeManager.setWP81KeyboardVibration(strength)
                KeyboardHaptics.refresh(this@KeyboardSettingsActivity, themeManager)
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    KeyboardHaptics.key(view)
                }
                false
            }
        }

        val toggle = MetroToggle(this, palette).apply {
            set(setting != 0, animated = false)
            onChanged = { on ->
                // Turning it back on lands where the slider was left rather than at a
                // default, so switching it off to think and on again is not a reset.
                val next = if (on) strengthOf(slider.value) else 0
                themeManager.setWP81KeyboardVibration(next)
                KeyboardHaptics.refresh(this@KeyboardSettingsActivity, themeManager)
                vibrationValue.text = vibrationText(next)
                slider.visibility = if (on) View.VISIBLE else View.GONE
                if (on) KeyboardHaptics.key(this)
            }
        }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("vibrate on every key"), wide())
            addView(vibrationValue, wide())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad() }
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(top, wide())
            addView(slider, wide())
        }
    }

    /** What the current setting says under the heading. */
    private fun vibrationText(strength: Int): String = when {
        strength == ThemeManager.WP81_KB_VIBRATION_SYSTEM -> "the phone's own"
        strength <= 0 -> "off"
        else -> "$strength%"
    }

    /**
     * Where the slider sits for a setting.
     *
     * Off and "the phone's own" are both the far left: off does not show the slider at all,
     * and when the switch is turned back on the left is exactly where it should land.
     */
    private fun sliderPositionOf(setting: Int): Float =
        if (setting <= 0) 0f else setting / ThemeManager.WP81_KB_VIBRATION_MAX.toFloat()

    /** And what a slider position means. The far left is the phone's own, not the weakest. */
    private fun strengthOf(fraction: Float): Int {
        if (fraction <= 0f) return ThemeManager.WP81_KB_VIBRATION_SYSTEM
        val raw = (fraction * ThemeManager.WP81_KB_VIBRATION_MAX).toInt()
        // In fives, so dragging gives 40 rather than 38.
        return ((raw / STEP_PERCENT) * STEP_PERCENT).coerceAtLeast(STEP_PERCENT)
    }

    /** Whether the bottom row is shorter than the letters above it. */
    private fun shortBottomRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardShortBottomRow(), animated = false)
            onChanged = { themeManager.setWP81KeyboardShortBottomRow(it) }
        }
        return row(
            "shorter bottom row",
            "nothing on it is a letter, so it does not need a letter's height. " +
                "turn this off for four even rows.",
            toggle
        )
    }

    /**
     * Which engine dictates.
     *
     * The note says what the trade actually is, because it is not obvious from the switch and
     * it is the reason the switch exists: one of these keeps your voice on the phone and the
     * other is usually better at understanding it.
     */
    private fun offlineVoiceRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardOfflineVoice(), animated = false)
            onChanged = { themeManager.setWP81KeyboardOfflineVoice(it) }
        }
        return row(
            "dictate on the phone",
            "speech is recognised locally and nothing is sent anywhere. needs a 40 MB " +
                "language model, downloaded once. turn this off to use the phone's own " +
                "recogniser, which is usually more accurate and usually not local. " +
                "languages with no offline model - Macedonian among them - use it either way.",
            toggle
        )
    }

    /** How long a key must be held before it offers the symbol in its corner. */
    private fun holdRow(): View {
        holdValue = detail("${themeManager.getWP81KeyboardHoldMs()} ms")
        val slider = MetroSlider(this).apply {
            applyPalette(palette)
            value = fractionOf(themeManager.getWP81KeyboardHoldMs())
            onValueChanged = { fraction ->
                val millis = millisOf(fraction)
                holdValue.text = "$millis ms"
                themeManager.setWP81KeyboardHoldMs(millis)
            }
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(label("hold to show symbols"), wide())
            addView(holdValue, wide())
            addView(slider, wide())
        }
        return holder
    }

    /**
     * The GIPHY key the emoji panel's `gif` half searches with.
     *
     * A field here rather than a constant in the build, because these are issued per person:
     * one compiled into the app would be one key answering for everybody who installed it,
     * against one rate limit, and it would be sitting in the source for anyone to take. So the
     * app ships without one, and this is where somebody puts theirs.
     *
     * Written on every keystroke rather than behind a save button. There is nothing to
     * validate against - the only test of a key is whether GIPHY answers - and a settings page
     * where one row alone has to be confirmed is a row people will leave unconfirmed.
     */
    private fun giphyRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), pad())
        addView(label("giphy key"), wide())
        addView(
            detail(
                "the gif key beside abc searches giphy, which needs a key of your own. " +
                    "tap here to get one - it is free - then paste it in below."
            ).apply {
                isClickable = true
                setOnClickListener { open(GIPHY_DEVELOPERS) }
            },
            wide()
        )
        addView(
            EditText(this@KeyboardSettingsActivity).apply {
                setText(themeManager.getWP81KeyboardGiphyKey())
                hint = "paste your key here"
                // A key is a run of random characters: nothing to correct, nothing to
                // capitalise, and nothing worth offering to the dictionary that learns words.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
                textSize = LABEL_SP
                typeface = ResourcesCompat.getFont(
                    this@KeyboardSettingsActivity, R.font.segoeui_regular
                )
                includeFontPadding = false
                setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
                palette.applyToField(this)
                doAfterTextChanged {
                    themeManager.setWP81KeyboardGiphyKey(it?.toString().orEmpty())
                }
            },
            wide().apply { topMargin = pad() / 2 }
        )
    }

    /** Opens [url] in whatever the phone browses with, or does nothing if it browses with nothing. */
    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser. Nothing useful to say about it on a settings page.
        }
    }

    private fun tryItNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), 0)
        addView(
            detail(
                "a field tells the keyboard what it is for, and the keyboard answers: the key " +
                    "beside the space bar becomes @ or + or /, the enter key changes what it " +
                    "says, and a number field gets a keypad instead of letters."
            ),
            wide()
        )
    }

    /**
     * One field of a given kind, to type into.
     *
     * Here rather than in a test app because this is the only place all of them can be tried
     * side by side, and because what they demonstrate - the keyboard reshaping itself around
     * the field - is invisible until you see two of them next to each other.
     */
    private fun testField(label: String, inputType: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), pad() / 2)
        addView(detail(label), wide())
        addView(
            EditText(this@KeyboardSettingsActivity).apply {
                this.inputType = inputType
                textSize = LABEL_SP
                typeface = ResourcesCompat.getFont(
                    this@KeyboardSettingsActivity, R.font.segoeui_regular
                )
                includeFontPadding = false
                setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
                // The shell's own text box: white with black in it, under both themes. Every
                // other field in the launcher is styled by this, and a keyboard's own settings
                // page is the last place that should look like something else.
                palette.applyToField(this)
            },
            wide()
        )
    }

    private fun about(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), pad())
        addView(
            detail(
                "word lists from the OpenSubtitles frequency lists (CC BY-SA 3.0) and " +
                    "dwyl/english-words. emoji names from Unicode CLDR."
            ),
            wide()
        )
    }

    private fun row(title: String, note: String, control: View): View {
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title), wide())
            addView(detail(note), wide())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                control,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad() }
            )
        }
    }

    // ---------------------------------------------------------------- furniture

    /** A section heading: small, capitalised by the eye rather than by the text, in the accent. */
    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = SECTION_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_semibold)
        setTextColor(palette.accent)
        includeFontPadding = false
        setPadding(pad(), pad(), pad(), 0)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = LABEL_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_regular)
        setTextColor(palette.foreground)
    }

    private fun detail(text: String) = TextView(this).apply {
        this.text = text
        textSize = DETAIL_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_regular)
        setTextColor(palette.foregroundSubtle)
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun pad() = (PAD_DP * resources.displayMetrics.density).toInt()

    /**
     * How much of the screen the language box takes.
     *
     * A little over a third: enough that four or five languages are visible at once, which is
     * what makes it read as a list, and little enough that the "typing" heading underneath it
     * is on screen at the same time - which is the whole reason the box exists.
     */
    private val LANGUAGE_LIST_SHARE = 0.45f


    /** The slider runs the range the setting allows, so its ends are the real limits. */
    private fun fractionOf(millis: Int): Float =
        (millis - ThemeManager.WP81_KB_HOLD_MIN).toFloat() /
            (ThemeManager.WP81_KB_HOLD_MAX - ThemeManager.WP81_KB_HOLD_MIN)

    private fun millisOf(fraction: Float): Int {
        val span = ThemeManager.WP81_KB_HOLD_MAX - ThemeManager.WP81_KB_HOLD_MIN
        val raw = ThemeManager.WP81_KB_HOLD_MIN + (fraction * span).toInt()
        // Rounded to something a person would recognise, so dragging gives 350, not 347.
        return (raw / STEP_MS) * STEP_MS
    }

    private companion object {

        /**
         * The kinds of field worth having to hand, and what each one is for.
         *
         * Chosen because each makes the keyboard do something different: an address moves `@`
         * next to the space bar, a telephone number brings up a keypad with `+` and `#`, a
         * password turns suggestions and learning off entirely, and a note takes the return
         * arrow rather than an action.
         */
        val TEST_FIELDS = listOf(
            "plain text" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
            "email address" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "web address" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            "telephone number" to InputType.TYPE_CLASS_PHONE,
            "number" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "password" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "several lines" to
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        )

        /** Where a GIPHY key comes from. See [giphyRow]. */
        const val GIPHY_DEVELOPERS = "https://developers.giphy.com/dashboard/"

        const val PAD_DP = 24f
        const val SECTION_SP = 12f
        const val LABEL_SP = 17f
        const val DETAIL_SP = 13f
        const val STEP_MS = 25

        /** The strength slider moves in fives, for the same reason the hold moves in steps. */
        const val STEP_PERCENT = 5
    }
}
