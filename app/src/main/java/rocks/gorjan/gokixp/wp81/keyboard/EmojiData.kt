package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.graphics.Paint
import androidx.core.graphics.PaintCompat

/** One emoji: the glyph itself, which group it belongs to, and the words search finds it by. */
data class Emoji(
    val glyph: String,
    val category: Int,
    val name: String
)

/**
 * The nine groups the picker sorts emoji into.
 *
 * These are Unicode's own `emoji-test.txt` groups, in the file's CLDR order, with
 * `Component` left out - it holds bare skin tones and hair, in isolation, which are not
 * emoji anyone picks on their own. See `tools/emojibuild/build_emoji.py`, which built the
 * asset this reads and whose `CATEGORIES` list this must keep matching: an emoji's
 * `categoryIndex` in the asset is a plain index into that list, and if the two ever
 * disagreed about the order every emoji generated before the disagreement would land in the
 * wrong bucket rather than fail to load.
 */
object EmojiCategories {
    const val SMILEYS_EMOTION = 0
    const val PEOPLE_BODY = 1
    const val ANIMALS_NATURE = 2
    const val FOOD_DRINK = 3
    const val TRAVEL_PLACES = 4
    const val ACTIVITIES = 5
    const val OBJECTS = 6
    const val SYMBOLS = 7
    const val FLAGS = 8

    /** Section-header text, in category order. Index it with an emoji's own `category`. */
    val LABELS = listOf(
        "Smileys & Emotion",
        "People & Body",
        "Animals & Nature",
        "Food & Drink",
        "Travel & Places",
        "Activities",
        "Objects",
        "Symbols",
        "Flags"
    )

    const val COUNT = 9
}

/**
 * The emoji asset, parsed - and, once loaded for real, filtered to what this phone can draw.
 *
 * Split from [EmojiPanel] the way [rocks.gorjan.gokixp.wp81.keyboard.text.Suggester] is split
 * from the keyboard that calls it. [parse] is arithmetic over a string: tab-separated fields
 * in, buckets and a flat list out, nothing it touches comes from Android. That is deliberate
 * and is what lets [EmojiDataTest] build one of these in a plain JUnit test with a few lines
 * of fixture text and no Context, no AssetManager and no device to run on. Reading the real
 * asset off disk, and deciding which of its 3700-odd entries this particular phone's font can
 * actually render, are both genuine I/O and both live in [load] instead, where nothing has to
 * pretend they are pure.
 */
class EmojiData private constructor(
    /** Every kept emoji, bucketed by [EmojiCategories] index - `categories[8]` is Flags. */
    val categories: List<List<Emoji>>
) {

    /** The buckets flattened once, in asset order, for [search] and [find] to scan. */
    private val all: List<Emoji> by lazy { categories.flatten() }

    private val byGlyph: Map<String, Emoji> by lazy { all.associateBy { it.glyph } }

    /**
     * The kept emoji drawn as [glyph], or null.
     *
     * What recents are rebuilt from: the store on disk keeps only the glyph string a user
     * typed, and turning that back into a full [Emoji] - for its category, or simply to
     * confirm it is still one of the ones this device can draw - means looking it up here
     * rather than carrying a second, richer record of "what was recently typed".
     */
    fun find(glyph: String): Emoji? = byGlyph[glyph]

    /**
     * Every kept emoji whose name contains [query], in the asset's own order.
     *
     * A substring match on purpose, not a prefix match: the asset's names are Unicode's own
     * short descriptions - "grinning face with big eyes", "flag: Puerto Rico" - and a search
     * for "face" or for "rico" is exactly the kind of search a prefix match would refuse to
     * answer. Blank answers empty rather than everything, which is what lets [EmojiPanel]
     * decide "nothing typed yet" and "typed something nothing matches" without asking twice.
     */
    fun search(query: String): List<Emoji> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return all.filter { it.name.contains(needle) }
    }

    companion object {

        /**
         * Parses the asset's own tab-separated format: `glyph<TAB>categoryIndex<TAB>name`.
         *
         * Split by hand rather than with `String.split('\t')` because a handful of the
         * flag glyphs late in the file are themselves built from tag characters that some
         * naive splitters have been known to trip over; indexing the two separators
         * explicitly and slicing between them makes no assumption about what can appear in
         * a glyph beyond "not a tab".
         *
         * Anything that does not parse - the wrong number of fields, a category that is not
         * a number, a category number outside the nine that exist - is dropped rather than
         * thrown on. A single bad line in a 3700-line generated file should cost one emoji,
         * not the whole keyboard's emoji key.
         */
        fun parse(text: String): EmojiData {
            val buckets = Array(EmojiCategories.COUNT) { mutableListOf<Emoji>() }
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val tab1 = line.indexOf('\t')
                if (tab1 < 0) continue
                val tab2 = line.indexOf('\t', tab1 + 1)
                if (tab2 < 0) continue
                val category = line.substring(tab1 + 1, tab2).toIntOrNull() ?: continue
                if (category !in buckets.indices) continue
                buckets[category].add(
                    Emoji(
                        glyph = line.substring(0, tab1),
                        category = category,
                        name = line.substring(tab2 + 1)
                    )
                )
            }
            return EmojiData(buckets.map { it.toList() })
        }

        /**
         * [parse], with every entry [canDraw] rejects removed from every category.
         *
         * A parameter rather than a hard-coded call to
         * [androidx.core.graphics.PaintCompat.hasGlyph] for the same reason [parse] takes no
         * Context: a test can hand this a fake that rejects a chosen few glyphs and check
         * that exactly those, and nothing else, are gone - which is a question about this
         * file's filtering, not about what a real device's font happens to support today.
         */
        fun filtered(text: String, canDraw: (String) -> Boolean): EmojiData {
            val full = parse(text)
            return EmojiData(full.categories.map { bucket -> bucket.filter { canDraw(it.glyph) } })
        }

        /** Where the generated asset lives. See `tools/emojibuild/build_emoji.py`. */
        private const val ASSET_PATH = "keyboard/emoji.txt"

        /**
         * Kept across every [EmojiPanel] the process builds, filtered once.
         *
         * The filtering pass in [load] runs [androidx.core.graphics.PaintCompat.hasGlyph]
         * against something like 3700 glyphs, which is cheap once and is exactly the sort of
         * thing that must not happen again on every tap of the emoji key. Nothing here ever
         * invalidates it: the asset ships inside the APK and a device's installed font does
         * not change under a process that is still running, so a second answer would never
         * differ from the first.
         */
        @Volatile
        private var cached: EmojiData? = null

        /**
         * Reads `assets/keyboard/emoji.txt`, drops what this phone cannot draw, and answers.
         *
         * The reason there is a filter at all: on API 29-30 the system emoji font is part of
         * the OS image and cannot be updated the way it can from Android 13 on, so an entry
         * from a Unicode release newer than the device would draw as a plain tofu box - a
         * rectangle with a hex code in it - if it were offered at all. [PaintCompat.hasGlyph]
         * is asked once per glyph rather than trusted to some Unicode-version cutoff, because
         * "cannot render" is a fact about *this device's font*, which varies by manufacturer
         * and OS update far more than it varies by the Unicode version the AOSP emoji font
         * happened to ship with.
         */
        fun load(context: Context): EmojiData {
            cached?.let { return it }
            val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val paint = Paint()
            val data = filtered(text) { glyph -> PaintCompat.hasGlyph(paint, glyph) }
            cached = data
            return data
        }
    }
}
