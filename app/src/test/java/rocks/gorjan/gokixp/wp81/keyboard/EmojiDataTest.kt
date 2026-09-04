package rocks.gorjan.gokixp.wp81.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmojiData] parsing and search, checked without a device.
 *
 * A malformed line here does not crash the keyboard - [EmojiData.parse] drops what it cannot
 * read - which means a mistake in it fails silently: an emoji quietly missing from its
 * category, or landing in the wrong one, and nothing in a screenshot to notice it by. These
 * are what would catch that, and they run as plain JUnit because [EmojiData.parse] and
 * [EmojiData.search] were written to need nothing an Android device provides - see the class
 * comment on [EmojiData] for why.
 */
class EmojiDataTest {

    private val fixture = listOf(
        "😀\t0\tgrinning face",
        "😃\t0\tgrinning face with big eyes",
        "🤖\t6\trobot",
        "🍕\t3\tpizza",
        "🍽️\t3\tfork and knife with plate",
        "🇵🇷\t8\tflag: puerto rico"
    ).joinToString("\n")

    // ---------------------------------------------------------------- parse

    @Test
    fun everyEntryLandsInItsOwnCategoryBucket() {
        val data = EmojiData.parse(fixture)
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.categories[EmojiCategories.SMILEYS_EMOTION].map { it.name })
        assertEquals(listOf("pizza", "fork and knife with plate"),
            data.categories[EmojiCategories.FOOD_DRINK].map { it.name })
        assertEquals(listOf("robot"), data.categories[EmojiCategories.OBJECTS].map { it.name })
        assertEquals(listOf("flag: puerto rico"), data.categories[EmojiCategories.FLAGS].map { it.name })
    }

    /** Nine buckets always come back, even the ones nothing in the fixture landed in. */
    @Test
    fun thereAreAlwaysNineBuckets() {
        val data = EmojiData.parse(fixture)
        assertEquals(EmojiCategories.COUNT, data.categories.size)
        assertTrue("People & Body should be empty in this fixture",
            data.categories[EmojiCategories.PEOPLE_BODY].isEmpty())
    }

    /** A category keeps the order its lines were written in - it is the picker's own order. */
    @Test
    fun aCategoryKeepsAssetOrder() {
        val data = EmojiData.parse(fixture)
        val smileys = data.categories[EmojiCategories.SMILEYS_EMOTION]
        assertEquals("grinning face", smileys[0].name)
        assertEquals("grinning face with big eyes", smileys[1].name)
    }

    @Test
    fun theGlyphIsEverythingBeforeTheFirstTab() {
        val data = EmojiData.parse(fixture)
        assertEquals("😀", data.categories[EmojiCategories.SMILEYS_EMOTION][0].glyph)
    }

    /**
     * A line that does not parse costs one emoji, not the whole file.
     *
     * Covers the ways a generator or a hand edit could break a line: no tabs at all, only
     * one, a category that is not a number, and a category number outside the nine that
     * exist - which a future tenth group, added to the asset but not to [EmojiCategories],
     * would produce.
     */
    @Test
    fun malformedLinesAreDroppedNotThrown() {
        val broken = listOf(
            "no tabs in this line at all",
            "😀\tonly one tab",
            "😀\tnotanumber\tsomething",
            "😀\t9\toutside the nine categories",
            "😀\t-1\tnegative category",
            "",
            "😃\t0\tgrinning face with big eyes"
        ).joinToString("\n")

        val data = EmojiData.parse(broken)
        val all = data.categories.flatten()
        assertEquals("only the one well-formed line should have survived", 1, all.size)
        assertEquals("grinning face with big eyes", all.single().name)
    }

    // ---------------------------------------------------------------- search

    @Test
    fun searchMatchesAnywhereInTheNameCaseInsensitively() {
        val data = EmojiData.parse(fixture)
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.search("FACE").map { it.name })
        assertEquals(listOf("flag: puerto rico"), data.search("Rico").map { it.name })
    }

    @Test
    fun blankQueryFindsNothing() {
        val data = EmojiData.parse(fixture)
        assertTrue(data.search("").isEmpty())
        assertTrue(data.search("   ").isEmpty())
    }

    @Test
    fun aQueryNothingMatchesFindsNothing() {
        val data = EmojiData.parse(fixture)
        assertTrue(data.search("xyzzy").isEmpty())
    }

    @Test
    fun searchReachesAcrossEveryCategoryAtOnce() {
        val data = EmojiData.parse(fixture)
        // "pizza" and "robot" are worlds apart in category, "and" only shares the substring.
        assertEquals(listOf("fork and knife with plate"), data.search("and").map { it.name })
    }

    // ---------------------------------------------------------------- filtered

    /** Only what [canDraw] rejects disappears; everything else, and its order, is untouched. */
    @Test
    fun filteredDropsOnlyWhatCannotBeDrawn() {
        val rejected = setOf("🤖", "🇵🇷")
        val data = EmojiData.filtered(fixture) { it !in rejected }

        assertTrue("robot should have been filtered out",
            data.categories[EmojiCategories.OBJECTS].isEmpty())
        assertTrue("the flag should have been filtered out",
            data.categories[EmojiCategories.FLAGS].isEmpty())
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.categories[EmojiCategories.SMILEYS_EMOTION].map { it.name })
    }

    /** A glyph the filter removed cannot be found by search either - it is one grid, one list. */
    @Test
    fun aFilteredOutGlyphCannotBeFoundBySearch() {
        val data = EmojiData.filtered(fixture) { it != "🤖" }
        assertTrue(data.search("robot").isEmpty())
    }

    @Test
    fun keepingEverythingFilteredIsTheSameAsParse() {
        val filtered = EmojiData.filtered(fixture) { true }
        val parsed = EmojiData.parse(fixture)
        assertEquals(parsed.categories, filtered.categories)
    }

    // ---------------------------------------------------------------- find

    /** What recents are rebuilt from - see [EmojiData.find]. */
    @Test
    fun findLooksUpAnEmojiByItsGlyph() {
        val data = EmojiData.parse(fixture)
        assertEquals("pizza", data.find("🍕")?.name)
        assertEquals(EmojiCategories.FLAGS, data.find("🇵🇷")?.category)
    }

    @Test
    fun findAnswersNullForAGlyphThatWasFilteredOut() {
        val data = EmojiData.filtered(fixture) { it != "🍕" }
        assertEquals(null, data.find("🍕"))
    }
}
