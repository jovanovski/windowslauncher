package rocks.gorjan.gokixp.wp81.keyboard

/**
 * What a keyboard is made of.
 *
 * Kotlin data rather than an XML resource, which is the call the rest of the phone shell
 * makes: there is not a single layout file behind any of it, and a keyboard is the last
 * place to start, because half of what a key needs to say - what it commits as opposed to
 * what it shows, what its hold produces, how wide it is in units of nothing in particular -
 * has no natural spelling in a resource file and would end up as a pile of string arrays
 * that have to be kept in step by hand.
 *
 * The one thing worth knowing before reading further: **nothing here is in pixels or dp.**
 * A key's width is in *units*, where a unit is one plain letter key, and the grid divides
 * whatever width it is given by the layout's column count to find out what a unit is worth
 * today. That is how the same layout lands correctly on a phone, on the outer screen of a
 * folded foldable and on a tablet, and it is the same trick the calculator plays.
 */
data class Key(

    /** What the key puts in the text box. Empty for the keys that do something instead. */
    val output: String = "",

    /**
     * What the key shows, when that is not simply what it commits.
     *
     * The space bar says the language's name, `&123` says `&123` and commits nothing at
     * all, and a symbol key's face is often narrower than the string behind it.
     */
    val label: String = output,

    /**
     * What the key commits when shift is on. Null means "whatever uppercasing gives", which
     * is right for every letter in both alphabets shipped and wrong for the punctuation
     * keys, which have to say so.
     */
    val shifted: String? = null,

    /**
     * The small grey character in the key's top-left corner.
     *
     * The number row that the phone did not have room for. The top row of letters carries
     * `1`-`0` here and produces them on a hold, which is the whole of why the phone could
     * get away with ten keys across and no eleventh row.
     */
    val hint: String? = null,

    /**
     * What a hold on this key offers, one character per entry, in the order shown.
     *
     * The hint, when there is one, is not repeated here - the grid prepends it - so a top
     * row key with accents lists only the accents.
     */
    val alternates: String = "",

    /** How many unit widths the key occupies. Fractional: the phone's enter key is 1.4. */
    val span: Float = 1f,

    val style: Style = Style.LETTER,

    /** What the key does instead of committing [output]. */
    val action: Action? = null,

    /**
     * What a hold on this key does, when a hold should do something rather than type
     * something.
     *
     * How the comma carries the emoji panel. The phone had a key each, but a keyboard has ten
     * columns and every one spent on a panel is a column not spent on the space bar - and
     * reaching for emoji is a deliberate act that can afford a hold, where reaching for a
     * comma is not. So they share: tap for the comma, hold for the panel.
     */
    val holdAction: Action? = null,

    /**
     * Whether this key changes with the field being typed into.
     *
     * The comma, and only it. The full stop on the other side of the space bar stays a full
     * stop - every kind of writing ends sentences - but the key to the left of it is the most
     * valuable spare column on the keyboard, and what belongs there depends entirely on the
     * field: an `@` in an email address, a `/` in a web address. It is one of those details
     * nobody notices until they are typing an address and reaching for `&123` every time.
     */
    val contextual: Boolean = false,

    /**
     * Extra touchable area above this key, as a fraction of a key's height, which is **not**
     * painted.
     *
     * The space bar's, and the reason it exists: people reaching for the space bar hit the
     * `n` above it. That is not clumsiness and it is not particular to this keyboard - it is
     * one of the oldest known problems with typing on glass. A thumb reaching down flattens
     * against the screen, and the touch point Android reports is the centre of that contact
     * patch, which sits *above* where the person believes they are pointing. So the aim is
     * low and the report is high, and the row above catches it.
     *
     * The fix everywhere is the same: the key's hit box stops matching its picture. AOSP's
     * keyboard ships a table of per-row touch corrections measured from user studies; this
     * does the targeted version of the same thing, letting the space bar claim some of the
     * gap and the bottom of the row above without moving anything anybody can see.
     */
    val overhangTop: Float = 0f
)

/**
 * How a key is painted.
 *
 * Not what it does - shift and `&123` do entirely different things and are the same colour,
 * because on the phone the darker fill meant "this key is about the keyboard rather than
 * about your sentence", which is a statement about the face and not about the behaviour.
 */
enum class Style {
    /** The pale fill. Letters, digits, punctuation - anything that types something. */
    LETTER,

    /** The darker fill. Shift, backspace, `&123`, emoji, enter. */
    FUNCTION,

    /**
     * The accent fill, white on colour.
     *
     * Nothing in the built-in layouts uses this: on the phone the keyboard was grey and the
     * accent appeared only under a finger. It exists because the enter key becomes the
     * accent when a field asks for `send`, which is the one place the phone did colour a key.
     */
    ACCENT
}

/** The keys that are not letters. */
enum class Action {
    SHIFT,
    BACKSPACE,
    ENTER,
    SPACE,

    /** To and from the `&123` pages. */
    SYMBOLS,

    /** Between the two `&123` pages. */
    SYMBOLS_PAGE,

    /** Back to letters from a symbol page. */
    LETTERS,

    EMOJI,

    /**
     * The emoji panel's other half: moving between the emoji and the GIFs.
     *
     * It appears on no layout and never reaches the service's key handler - the panel owns
     * the key and answers it itself - but it is an [Action] rather than a bare label so the
     * key is painted as the function key it is, at the same size as the `abc` beside it.
     */
    GIF,

    /** The keyboard's own settings. Behind the hold on `&123` - see its ellipsis. */
    SETTINGS
}

/**
 * One row of keys, and how far it is held off each edge.
 *
 * The indents are what make `asdfghjkl` sit half a key in from the row above rather than
 * being stretched to the full width, which is the single most recognisable thing about the
 * shape of a QWERTY keyboard and the thing a naive grid gets wrong. They are in units, like
 * everything else, so a row need not fill its width and usually does not.
 */
data class Row(
    val keys: List<Key>,
    val indentStart: Float = 0f,
    val indentEnd: Float = 0f,

    /**
     * How tall this row is against an ordinary one.
     *
     * The bottom row is shorter than the letters above it. Nothing on it is a letter - it is
     * the space bar and the keys either side - so it does not need a letter's target, and the
     * height it gives back is height the keyboard is not taking from the app above it. The
     * phone's own bottom row was shorter for the same reason.
     */
    val heightScale: Float = 1f
)

/**
 * A whole keyboard: its rows, and how many unit widths make up its width.
 *
 * [columns] is per-layout and fractional, which is the one place this parts company with the
 * calculator's grid - that one has four columns and always will. English wants ten across
 * and Macedonian wants twelve, and a keyboard that assumed ten would either lose letters or
 * have to stack them.
 */
data class KeyboardLayout(
    /** Stored in settings, so do not rename one that has shipped. */
    val id: String,

    /** What the space bar says, and what the language list calls it. */
    val name: String,

    val columns: Float,

    val rows: List<Row>,

    /**
     * The language this layout is for, as a bare ISO code - `en`, `mk`.
     *
     * What ties a layout to a `<subtype>` in `res/xml/method.xml`. Android's model for a
     * keyboard that speaks more than one language is that each language is a *subtype* of the
     * one input method, and the globe in the navigation bar - the system's, not the
     * keyboard's - is what moves between them. So the keyboard is never asked to choose a
     * language; it is told which one it is currently being, and looks up the layout by this.
     *
     * Empty for the symbol pages, which belong to no language.
     */
    val language: String = ""
) {

    /**
     * Where each key's centre sits, in units, for the correction engine.
     *
     * Autocorrect weighs a substitution by how far apart the two keys actually are - `s` for
     * `a` is a thumb that landed a little left, `p` for `a` is a different word - and that
     * needs the geometry, not the alphabet. Computed here rather than in the view because it
     * is a property of the layout and the engine must not have to wait for a view to exist
     * before it can score anything.
     *
     * Function keys are left out: nothing corrects toward backspace.
     */
    val keyCentres: Map<Char, Pair<Float, Float>> by lazy {
        val centres = mutableMapOf<Char, Pair<Float, Float>>()
        rows.forEachIndexed { rowIndex, row ->
            var x = row.indentStart
            for (key in row.keys) {
                if (key.action == null && key.output.length == 1) {
                    centres[key.output[0]] = (x + key.span / 2f) to (rowIndex + 0.5f)
                }
                x += key.span
            }
        }
        centres
    }
}
