package rocks.gorjan.gokixp.wp81.keyboard

/**
 * The keyboards themselves.
 *
 * Two alphabets and two pages of symbols. The symbol pages are shared: the phone did not
 * give Cyrillic its own punctuation, and neither does this.
 *
 * The row-building helpers at the bottom are what keep these readable - a row of plain
 * letters is a string, and only the keys that are unusual in some way are spelled out.
 */
object Layouts {

    // ------------------------------------------------------------------ pieces
    //
    // These come first because a Kotlin object initialises its properties in the order they
    // are written, and every layout below is built out of them. Declared after, they would
    // all still be null at the moment the first layout asked for them.


    /**
     * The bottom row, which is the same on every layout including the symbol pages.
     *
     * `&123` carries an ellipsis in its top-left corner on the phone, which is the same
     * corner and the same grey the top row's numbers use - so it is set as a hint rather
     * than invented as a second kind of mark.
     */
    /**
     * The two shoulder keys either end of a row, and why they are not round numbers.
     *
     * Measured off the phone: `&123` and shift come out 99 pixels wide against a 61 pixel
     * letter, and backspace and enter come out 94. In units that is 1.55 and 1.45, and the
     * pair matters more than either does alone - a row of seven letters between them has to
     * come to exactly ten, because a row adding up to 9.9 leaves a tenth of a key of dead
     * ground against the right edge that is obvious the moment you look for it.
     */
    private const val SIDE_KEY = 1.55f
    private const val ENTER_KEY = 1.45f

    /**
     * The shoulders on a row that has only five or seven keys between them.
     *
     * Macedonian's third row and both symbol pages' put fewer keys in the middle than the
     * row has columns, so the two ends grow to take up the slack rather than the row being
     * left short. Two and a half units each is what ten columns less five symbols, and
     * twelve less seven letters, both come to.
     */
    private const val WIDE_KEY = 2.5f

    /** How tall the bottom row is against the letters. See [Row.heightScale]. */
    private const val BOTTOM_ROW_SCALE = 0.75f

    /**
     * How far the space bar's touch area reaches up into the row above. See [Key.overhangTop].
     *
     * A sixth of a key's height. It was a third, which was too much and had to come down:
     * the bottom row is already only three quarters of a key tall, so a third on top of that
     * put nearly a third of the space bar's target above its own face, and presses genuinely
     * meant for `n` started coming out as spaces. Trading one wrong key for the opposite
     * wrong key is not a fix.
     *
     * It can afford to be small now because it is no longer the only thing catching this. A
     * thumb that lands on the `n` between two words is caught afterwards instead, by the
     * split correction - see `Suggester.splits` and the letters it reads off the geometry
     * above this key. A hit box can only ever guess before the fact; the dictionary gets to
     * look at what was actually typed. So the hit box handles the presses that are plainly
     * short, and the far more ambiguous ones are left to be a real `n` unless the sentence
     * says otherwise.
     */
    private const val SPACE_OVERHANG = 0.18f

    /** Everything on the bottom row that is not the space bar, added up. */
    private const val FIXED_BOTTOM = SIDE_KEY + 1f + 1f + ENTER_KEY

    /** What the top row of letters carries in its corners, in order. */
    private const val HINTS = "1234567890"

    /** What the middle row carries, and the bottom row - matching the `&123` page. */
    private const val HOME_HINTS = "@#\$_&-+()"
    private const val BOTTOM_HINTS = "*\"':;!?"

    /**
     * How much of a row's spare width goes to the leading side.
     *
     * The measured pair, as a proportion rather than a pair of widths, so that it answers for
     * a row of any width - which is what lets a twelve-key Cyrillic row and a ten-key Latin
     * one share one builder. Derived from [SIDE_KEY] and [ENTER_KEY] rather than written out
     * beside them because the ten-column case has to come back to *exactly* those two: the
     * symbol page states them literally, and a shoulder that came out a thousandth of a key
     * wider would put every symbol a hair off the letter that holds it.
     */
    private const val LEADING_SHARE = SIDE_KEY / (SIDE_KEY + ENTER_KEY)

    private fun spaceRow(columns: Float) = Row(
        listOf(
            // The ellipsis in this key's corner has meant "there is more behind this than the
            // page it takes you to" since the phone, and this is what it means here: the
            // keyboard's own settings are behind the hold.
            Key(
                label = "&123", hint = "\u2026", span = SIDE_KEY, style = Style.FUNCTION,
                action = Action.SYMBOLS, holdAction = Action.SETTINGS
            ),
            // Comma and emoji on one key, marked with a smiley in the corner the way the
            // number row marks its digits. See [Key.holdAction].
            Key(output = ",", holdAction = Action.EMOJI, contextual = true),
            // Space takes whatever the row has left, so the same bottom row fills a ten
            // column layout and a twelve column one without either being written out twice.
            Key(
                output = " ", span = columns - FIXED_BOTTOM, action = Action.SPACE,
                overhangTop = SPACE_OVERHANG
            ),
            Key(output = "."),
            Key(span = ENTER_KEY, style = Style.FUNCTION, action = Action.ENTER)
        ),
        heightScale = BOTTOM_ROW_SCALE
    )


    private val SHIFT = Key(span = SIDE_KEY, style = Style.FUNCTION, action = Action.SHIFT)
    private val BACKSPACE = Key(span = ENTER_KEY, style = Style.FUNCTION, action = Action.BACKSPACE)

    /**
     * A row of letters, each with the symbol it holds written in its corner.
     *
     * [hints] is one character per key, in the same order, and may run out early - the
     * Macedonian rows are twelve keys against ten numbers, and the last two simply carry
     * nothing. A key's hint is also the first thing its hold produces, so writing it here is
     * both what marks the key and what arms it; see [Key.hint].
     */
    private fun letterRow(chars: String, hints: String, alternates: List<String>) = Row(
        chars.mapIndexed { i, c ->
            Key(
                output = c.toString(),
                hint = hints.getOrNull(i)?.toString(),
                alternates = alternates.getOrElse(i) { "" }
            )
        }
    )

    /** Shift, the letters, backspace - the shape of every third row on a ten-column layout. */
    private fun bottomLetterRow(chars: String, hints: String, alternates: List<String>) =
        Row(listOf(SHIFT) + letterRow(chars, hints, alternates).keys + listOf(BACKSPACE))

    /**
     * Punctuation keys.
     *
     * [Key.shifted] is set to the character itself rather than left null, because the default
     * is "whatever uppercasing gives" and uppercasing a bracket quietly gives the bracket
     * back while uppercasing a letter-like symbol does not.
     */
    private fun symbols(chars: String) =
        chars.map { Key(output = it.toString(), shifted = it.toString()) }

    // ------------------------------------------------------------------ letters

    /**
     * A whole alphabet, from three rows of it.
     *
     * Every letter layout in the world is the same shape - three rows, the middle one held in
     * from both edges, shift and backspace on the shoulders of the third - and differs only in
     * which letters are in it and how many. So it is described rather than drawn: the rows,
     * and the accented forms that hang behind particular keys.
     *
     * The column count comes from the longest row, which is what makes a twelve-key Cyrillic
     * row and a ten-key Latin one both come out right without either being a special case.
     * The shoulders and the middle row's indents are then whatever is left over, split in the
     * proportion measured off the phone.
     */
    private fun alphabet(
        id: String,
        name: String,
        language: String,
        top: String,
        home: String,
        bottom: String,
        alternates: Map<Char, String> = emptyMap()
    ): KeyboardLayout {
        val columns = maxOf(top.length, home.length, bottom.length + 3).toFloat()

        fun row(chars: String, hints: String) = chars.mapIndexed { i, c ->
            Key(
                output = c.toString(),
                hint = hints.getOrNull(i)?.toString(),
                alternates = alternates[c].orEmpty()
            )
        }

        // Whatever the row does not use, split between the two sides. The proportion is the
        // phone's: `&123` and shift come out a little wider than backspace and enter.
        // A row of letters held in from both edges by whatever it does not use. The top and
        // middle rows are indented; the bottom row spends the same slack on its shoulders,
        // which is the only difference between them.
        fun indented(chars: String, hints: String): Row {
            val slack = columns - chars.length
            val lead = slack * LEADING_SHARE
            return Row(row(chars, hints), indentStart = lead, indentEnd = slack - lead)
        }

        // Shift and backspace take the third row's slack instead. Seven letters in ten
        // columns leaves three, which comes back as exactly the measured 1.55 and 1.45.
        val bottomSlack = columns - bottom.length
        val shiftSpan = bottomSlack * LEADING_SHARE

        return KeyboardLayout(
            id = id,
            name = name,
            language = language,
            columns = columns,
            rows = listOf(
                indented(top, HINTS),
                indented(home, HOME_HINTS),
                Row(
                    listOf(SHIFT.copy(span = shiftSpan)) +
                        row(bottom, BOTTOM_HINTS) +
                        listOf(BACKSPACE.copy(span = bottomSlack - shiftSpan))
                ),
                spaceRow(columns)
            )
        )
    }

    /**
     * The languages that ship, in the order the settings list shows them.
     *
     * Latin first and alphabetically, then the Cyrillic ones, then Greek - which is roughly
     * how anybody scanning the list will look for their own.
     *
     * Adding one is adding a line. What it does *not* bring with it is a dictionary: those are
     * two to three megabytes each and are fetched per language rather than shipped, so a
     * language turned on before its dictionary has arrived types perfectly well and simply
     * makes no suggestions.
     */
    val EN_QWERTY = alphabet(
        // The id is stored in settings, so it must not be renamed once it has shipped.
        "en_qwerty", "English", "en",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        mapOf(
            'e' to "èéêë", 'y' to "ýÿ", 'u' to "ùúûü", 'i' to "ìíîï", 'o' to "òóôõöø",
            'a' to "àáâãäåæ", 's' to "ß", 'd' to "ð", 'c' to "ç", 'n' to "ñ"
        )
    )

    val DE_QWERTZ = alphabet(
        "de_qwertz", "Deutsch", "de",
        "qwertzuiopü", "asdfghjklöä", "yxcvbnm",
        mapOf('s' to "ß", 'e' to "éè", 'a' to "àá", 'u' to "ùú", 'o' to "óò")
    )

    val FR_AZERTY = alphabet(
        "fr_azerty", "Français", "fr",
        "azertyuiop", "qsdfghjklm", "wxcvbn",
        mapOf(
            'a' to "àâæ", 'e' to "éèêë", 'i' to "îï", 'o' to "ôœ", 'u' to "ùûü",
            'c' to "ç", 'y' to "ÿ"
        )
    )

    val ES_QWERTY = alphabet(
        "es_qwerty", "Español", "es",
        "qwertyuiop", "asdfghjklñ", "zxcvbnm",
        mapOf('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "úü", 'n' to "ñ")
    )

    val IT_QWERTY = alphabet(
        "it_qwerty", "Italiano", "it",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        mapOf('a' to "àá", 'e' to "èé", 'i' to "ìí", 'o' to "òó", 'u' to "ùú")
    )

    val PT_QWERTY = alphabet(
        "pt_qwerty", "Português", "pt",
        "qwertyuiop", "asdfghjklç", "zxcvbnm",
        mapOf(
            'a' to "áàâãä", 'e' to "éêè", 'i' to "íî", 'o' to "óôõò", 'u' to "úü", 'c' to "ç"
        )
    )

    val NL_QWERTY = alphabet(
        "nl_qwerty", "Nederlands", "nl",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        mapOf('e' to "éëè", 'i' to "ïí", 'o' to "óö", 'u' to "üú", 'a' to "áä")
    )

    val PL_QWERTY = alphabet(
        "pl_qwerty", "Polski", "pl",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        mapOf(
            'a' to "ą", 'c' to "ć", 'e' to "ę", 'l' to "ł", 'n' to "ń",
            'o' to "ó", 's' to "ś", 'z' to "żź", 'x' to "ź"
        )
    )

    val CS_QWERTZ = alphabet(
        "cs_qwertz", "Čeština", "cs",
        "qwertzuiop", "asdfghjkl", "yxcvbnm",
        mapOf(
            'e' to "ěé", 'r' to "ř", 't' to "ť", 'z' to "ž", 'u' to "úů", 'i' to "í",
            'o' to "ó", 'a' to "á", 's' to "š", 'd' to "ď", 'c' to "čć", 'n' to "ňń", 'y' to "ý"
        )
    )

    val SK_QWERTZ = alphabet(
        "sk_qwertz", "Slovenčina", "sk",
        "qwertzuiop", "asdfghjkl", "yxcvbnm",
        mapOf(
            'a' to "áä", 'e' to "é", 'i' to "í", 'o' to "óô", 'u' to "ú", 'y' to "ý",
            'c' to "č", 's' to "š", 'z' to "ž", 'd' to "ď", 't' to "ť", 'n' to "ň", 'l' to "ĺľ"
        )
    )

    val RO_QWERTY = alphabet(
        "ro_qwerty", "Română", "ro",
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        mapOf('a' to "ăâ", 'i' to "î", 's' to "ș", 't' to "ț")
    )

    val HU_QWERTZ = alphabet(
        "hu_qwertz", "Magyar", "hu",
        "qwertzuiopőú", "asdfghjkléáű", "yxcvbnm",
        mapOf('o' to "óö", 'u' to "üű", 'e' to "é", 'a' to "á", 'i' to "í")
    )

    val TR_QWERTY = alphabet(
        "tr_qwerty", "Türkçe", "tr",
        "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç",
        mapOf('c' to "ç", 's' to "ş", 'g' to "ğ", 'o' to "ö", 'u' to "ü", 'i' to "ı")
    )

    val SV_QWERTY = alphabet(
        "sv_qwerty", "Svenska", "sv",
        "qwertyuiopå", "asdfghjklöä", "zxcvbnm",
        mapOf('a' to "áàâ", 'e' to "éè", 'o' to "óò")
    )

    val DA_QWERTY = alphabet(
        "da_qwerty", "Dansk", "da",
        "qwertyuiopå", "asdfghjklæø", "zxcvbnm",
        mapOf('a' to "áà", 'e' to "éè", 'o' to "óò")
    )

    val NB_QWERTY = alphabet(
        "nb_qwerty", "Norsk", "nb",
        "qwertyuiopå", "asdfghjkløæ", "zxcvbnm",
        mapOf('a' to "áà", 'e' to "éè", 'o' to "óò")
    )

    val FI_QWERTY = alphabet(
        "fi_qwerty", "Suomi", "fi",
        "qwertyuiopå", "asdfghjklöä", "zxcvbnm",
        mapOf('a' to "áà", 'e' to "éè", 'o' to "óò")
    )

    val EL_GREEK = alphabet(
        "el_greek", "Ελληνικά", "el",
        "ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ",
        mapOf(
            'α' to "ά", 'ε' to "έ", 'η' to "ή", 'ι' to "ίϊΐ", 'ο' to "ό",
            'υ' to "ύϋΰ", 'ω' to "ώ", 'σ' to "ς"
        )
    )

    val RU_CYRILLIC = alphabet(
        "ru_cyrillic", "Русский", "ru",
        "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю",
        mapOf('е' to "ё", 'ь' to "ъ", 'и' to "й")
    )

    val UK_CYRILLIC = alphabet(
        "uk_cyrillic", "Українська", "uk",
        "йцукенгшщзхї", "фівапролджє", "ячсмитьбю",
        mapOf('и' to "і", 'г' to "ґ", 'е' to "є")
    )

    val SR_CYRILLIC = alphabet(
        "sr_cyrillic", "Српски", "sr",
        "љњертзуиопшђ", "асдфгхјклчћж", "ѕџцвбнм"
    )

    val MK_CYRILLIC = alphabet(
        // Shipped before the others, so the id is the original one.
        "mk_cyrillic", "Македонски", "mk",
        "љњертѕуиопшѓ", "асдфгхјклчќж", "зџцвбнм"
    )

    // ------------------------------------------------------------------ symbols

    /**
     * `&123`, first page - and deliberately the same shape as the letters underneath it.
     *
     * Every symbol sits on the key whose hold produces it: `@` where `a` is, `?` where `m` is,
     * the digits where the top row is. That correspondence is the point. A hold is the fast
     * way to one symbol and this page is the fast way to several, and if the two disagreed
     * about where `?` lives then learning either would teach you nothing about the other.
     *
     * Ten columns whatever the letters have, because the symbols are the same in every
     * language - only the alphabets differ.
     */
    val SYMBOLS_1 = KeyboardLayout(
        id = "symbols_1",
        name = "&123",
        columns = 10f,
        rows = listOf(
            Row(symbols("1234567890")),
            Row(symbols("@#\$_&-+()/")),
            Row(
                listOf(Key(label = "=\\<", span = SIDE_KEY, style = Style.FUNCTION, action = Action.SYMBOLS_PAGE)) +
                    symbols("*\"':;!?") +
                    listOf(BACKSPACE)
            ),
            spaceRow(10f)
        )
    )

    /**
     * `&123`, second page: everything that would not fit on the first.
     *
     * No correspondence to keep here - nothing on this page is behind a letter - so it is
     * simply the overflow, in the order Gboard has it.
     */
    val SYMBOLS_2 = KeyboardLayout(
        id = "symbols_2",
        name = "&123",
        columns = 10f,
        rows = listOf(
            Row(symbols("~`|•√π÷×¶∆")),
            Row(symbols("£¢€¥^°={}\\")),
            Row(
                listOf(Key(label = "&123", span = SIDE_KEY, style = Style.FUNCTION, action = Action.SYMBOLS_PAGE)) +
                    symbols("©®™✓[]§") +
                    listOf(BACKSPACE)
            ),
            spaceRow(10f)
        )
    )

    // ------------------------------------------------------------------ number pads

    /**
     * A field that only takes numbers gets a keypad, not a keyboard.
     *
     * Four columns rather than ten, which the grid handles by making the keys wider and
     * leaving the rows exactly where they were - so the keyboard does not change height and
     * the app above it does not reflow when the field changes. The digits are in telephone
     * order, top-left to bottom-right, because that is the order they are on every keypad
     * anybody has ever used and the calculator's is the odd one out.
     *
     * There is no way back to the letters from here on purpose: the field will not accept
     * them, so a key offering them would be a key that does nothing.
     */
    val NUMBER_PAD = KeyboardLayout(
        id = "number_pad",
        name = "123",
        columns = 4f,
        rows = listOf(
            Row(symbols("123") + listOf(BACKSPACE.copy(span = 1f))),
            Row(symbols("456-")),
            Row(symbols("789,")),
            Row(
                symbols("+0.") +
                    listOf(Key(span = 1f, style = Style.FUNCTION, action = Action.ENTER))
            )
        )
    )

    /** The same, for a telephone number, where the useful characters are different. */
    val PHONE_PAD = KeyboardLayout(
        id = "phone_pad",
        name = "123",
        columns = 4f,
        rows = listOf(
            Row(symbols("123") + listOf(BACKSPACE.copy(span = 1f))),
            Row(symbols("456+")),
            Row(symbols("789;")),
            Row(
                symbols("*0#") +
                    listOf(Key(span = 1f, style = Style.FUNCTION, action = Action.ENTER))
            )
        )
    )

    /** Every letter layout that ships, in the order the language list shows them. */
    val ALL_LANGUAGES = listOf(
        EN_QWERTY, CS_QWERTZ, DA_QWERTY, DE_QWERTZ, ES_QWERTY, FI_QWERTY, FR_AZERTY,
        HU_QWERTZ, IT_QWERTY, NB_QWERTY, NL_QWERTY, PL_QWERTY, PT_QWERTY, RO_QWERTY,
        SK_QWERTZ, SV_QWERTY, TR_QWERTY,
        EL_GREEK, MK_CYRILLIC, RU_CYRILLIC, SR_CYRILLIC, UK_CYRILLIC
    )

    fun byId(id: String): KeyboardLayout = ALL_LANGUAGES.firstOrNull { it.id == id } ?: EN_QWERTY

    /**
     * The layout for whichever subtype the system says the keyboard currently is.
     *
     * [tag] is a BCP 47 language tag off an `InputMethodSubtype` - `en-US`, `mk`, sometimes
     * the older `en_US`. Only the language part is used, because a layout is a property of
     * the language and not of the country: `en-US` and `en-GB` are the same twenty-six keys.
     *
     * Falls back to English rather than throwing. A subtype that names a language nothing
     * here can spell is a keyboard the user cannot type on at all, and a Latin layout is a
     * better answer to that than a blank one.
     */
    fun forLanguageTag(tag: String?): KeyboardLayout {
        val base = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return ALL_LANGUAGES.firstOrNull { it.language == base } ?: EN_QWERTY
    }
}
