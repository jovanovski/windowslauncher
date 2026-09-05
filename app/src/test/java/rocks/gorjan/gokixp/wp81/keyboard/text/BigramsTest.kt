package rocks.gorjan.gokixp.wp81.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rocks.gorjan.gokixp.wp81.keyboard.Layouts
import java.io.File

/**
 * The shipped next-word table, read the way the keyboard reads it.
 *
 * Against the real asset rather than a fixture, for the reason [SuggesterTest] gives: the
 * failures worth catching here are failures at scale. A binary search that is off by one
 * finds twenty-five thousand words correctly and misses the first and the last; a table
 * written with the wrong offsets round-trips whatever the test built and nothing else.
 */
class BigramsTest {

    @Test
    fun theTableLoads() {
        assertNotNull("no table - is app/src/main/assets/keyboard/en.bigrams missing?", english)
    }

    /**
     * The predictions a person would actually notice being right.
     *
     * These are what the feature is *for*. Every one of them is a phrase somebody types
     * several times a week, and if the table stops answering them it has stopped being worth
     * its size whatever else still passes.
     */
    @Test
    fun theObviousPairsArePredicted() {
        assertPredicts("thank", "you")
        assertPredicts("good", "morning", "night", "luck")
        assertPredicts("how", "do", "much", "are")
        assertPredicts("see", "you")
        assertPredicts("i", "don't", "have", "know", "was")
    }

    /** Both ends of the sorted pool are reachable, which is where a binary search fails. */
    @Test
    fun theEndsOfTheTableAreReachable() {
        val table = english!!
        var found = 0
        for (word in listOf("a", "i", "the", "you", "zero", "yourself", "young", "able")) {
            if (table.following(word).isNotEmpty()) found++
        }
        assertTrue("only $found of eight ordinary words had any followers", found >= 7)
    }

    /** A word the table has never heard of is not an error, and not a wrong answer either. */
    @Test
    fun anUnknownWordPredictsNothing() {
        assertTrue(english!!.following("qqqqqqzz").isEmpty())
        assertTrue(english!!.following("").isEmpty())
    }

    /** Case is not a difference: the table was counted lowercased and is asked lowercased. */
    @Test
    fun capitalisationIsIgnored() {
        assertEquals(english!!.following("thank"), english!!.following("Thank"))
        assertEquals(english!!.following("i"), english!!.following("I"))
    }

    /** Weights are on the scale everything else in the keyboard uses, and are ordered. */
    @Test
    fun weightsAreOrderedAndInRange() {
        for (word in listOf("thank", "good", "how", "i", "the")) {
            val entries = english!!.following(word)
            assertTrue("$word has no followers", entries.isNotEmpty())
            for ((next, weight) in entries) {
                assertTrue("$word -> $next weighs $weight", weight in 1..255)
            }
            val weights = entries.map { it.second }
            assertEquals("$word's followers are not best-first", weights.sortedDescending(), weights)
        }
    }

    /** An empty field has something to say, and it is the openers of the language. */
    @Test
    fun sentencesHaveStarters() {
        val starters = english!!.starters().map { it.first }
        assertTrue("only ${starters.size} starters", starters.size >= 4)
        assertTrue("expected 'i' among $starters", "i" in starters)
    }

    /** Every word offered is a word the dictionary believes in, so nothing is corrected away. */
    @Test
    fun everyPredictionIsAWordTheDictionaryKnows() {
        val dictionary = trie ?: return
        for (word in listOf("thank", "good", "how", "see", "i", "we", "please", "what")) {
            for ((next, _) in english!!.following(word)) {
                assertTrue(
                    "'$next' is predicted after '$word' but is not in the dictionary",
                    dictionary.frequencyOf(next) > 0
                )
            }
        }
    }

    /**
     * A pair the user has typed outranks the corpus, and one they have not does not.
     *
     * The whole policy of shipped data against learned data, and the only place it is
     * actually visible. See `Suggester.followers`.
     */
    @Test
    fun whatIsLearnedWinsOverWhatShipped() {
        val file = File.createTempFile("bigrams", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val engine = Suggester(trie, Layouts.EN_QWERTY, learned, english)

        // Straight out of the box, with nothing learned at all.
        assertEquals("you", engine.following("thank").first().word)

        // Once, which is evidence but not much. This is the case that was actually wrong: a
        // single mistyped pair - the phone this was tested on had `thank fod` in it - used to
        // land above everything the corpus knew, because learned pairs were weighed on the
        // scale meant for learned words. It should sit below a strong shipped pair and above
        // a thin one.
        learned.learnPair("thank", "gorjan")
        assertEquals("you", engine.following("thank").first().word)
        val once = engine.following("thank")
        assertTrue(
            "one sighting should not beat 'god', which the corpus is surer of: $once",
            once.indexOfFirst { it.word == "god" } < once.indexOfFirst { it.word == "gorjan" }
        )

        // Six times is somebody's actual habit, and it wins - against `thank` -> `you` at 188,
        // which is about the strongest pair the corpus has to offer. Five is enough for
        // almost anything else in the table; this one is the hardest case in the language.
        repeat(5) { learned.learnPair("thank", "gorjan") }
        assertEquals("gorjan", engine.following("thank").first().word)
        file.delete()
    }

    /**
     * The previous word settles a completion that frequency cannot.
     *
     * `thank y` has three ordinary continuations and only one of them is ever meant. This is
     * the shipped table reaching into the spelling half of the keyboard, which is the part of
     * this that the bar being empty never showed.
     */
    @Test
    fun thePreviousWordDecidesACompletion() {
        val engine = Suggester(trie, Layouts.EN_QWERTY, null, english)
        assertEquals("you", engine.candidates("y", "thank").first().word)
        assertEquals("morning", engine.candidates("mor", "good").first().word)
    }

    /** And with no previous word to go on, nothing about it changes. */
    @Test
    fun withoutAPreviousWordCompletionIsUnchanged() {
        val plain = Suggester(trie, Layouts.EN_QWERTY)
        val withTable = Suggester(trie, Layouts.EN_QWERTY, null, english)
        assertEquals(
            plain.candidates("hel").map { it.word },
            withTable.candidates("hel").map { it.word }
        )
    }

    /** A language with no table falls back on what it has learned, rather than breaking. */
    @Test
    fun aLanguageWithNoTableStillPredicts() {
        val file = File.createTempFile("notable", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val engine = Suggester(null, Layouts.DE_QWERTZ, learned, null)

        assertTrue(engine.following("danke").isEmpty())
        assertTrue(engine.starting().isEmpty())
        learned.learnPair("danke", "schön")
        assertEquals(listOf("schön"), engine.following("danke").map { it.word })
        file.delete()
    }

    /** A file that is not one is refused rather than misread. */
    @Test
    fun rubbishIsRejected() {
        assertNull(Bigrams.parse(ByteArray(0)))
        assertNull(Bigrams.parse(ByteArray(64)))
        assertNull(Bigrams.parse("not a bigram table, but long enough to look like one".toByteArray()))
    }

    /**
     * A prediction thrown in the bin does not come back - not even from the other source.
     *
     * The case the bin exists for, and the one a delete alone would get wrong: removing the
     * learned pair `thank fod` is not enough on its own if the shipped table has an opinion
     * about the same two words, so what the gesture leaves behind is a block rather than an
     * absence. `thank` -> `you` is the strongest pair in the table, which makes it the
     * hardest thing to suppress and therefore the right thing to test with.
     */
    @Test
    fun aBinnedPredictionDoesNotComeBack() {
        val file = File.createTempFile("binned", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val engine = Suggester(trie, Layouts.EN_QWERTY, learned, english)

        assertEquals("you", engine.following("thank").first().word)
        learned.forgetPair("thank", "you")
        assertTrue(
            "'you' came back after 'thank'",
            engine.following("thank").none { it.word == "you" }
        )
        // And only after that word. The complaint was about the pairing, not the word.
        assertTrue(engine.following("see").any { it.word == "you" })
        assertTrue(engine.candidates("yo").any { it.word == "you" })
        file.delete()
    }

    /** A word thrown away is gone from completions and corrections as well as predictions. */
    @Test
    fun aBinnedWordIsGoneEverywhere() {
        val file = File.createTempFile("binned", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val engine = Suggester(trie, Layouts.EN_QWERTY, learned, english)

        assertTrue(engine.candidates("morn").any { it.word == "morning" })
        learned.forgetWord("morning")
        assertTrue(
            "'morning' survived being binned",
            engine.candidates("morn").none { it.word == "morning" }
        )
        assertTrue(engine.following("good").none { it.word == "morning" })
        assertTrue(engine.starting().none { it.word == "morning" })
        file.delete()
    }

    /** And it is still gone after the keyboard has been shut and opened again. */
    @Test
    fun theBinSurvivesARestart() {
        val file = File.createTempFile("binned", ".txt")
        file.delete()
        val first = UserDictionary.openAt(file)
        first.learnPair("thank", "fod")
        first.forgetPair("thank", "fod")
        first.forgetWord("fod")
        first.flush()
        // The write is on its own thread; give it the moment it needs.
        Thread.sleep(500)

        val second = UserDictionary.openAt(file)
        val engine = Suggester(trie, Layouts.EN_QWERTY, second, english)
        assertTrue("the block did not survive the file", second.isBlocked("fod"))
        assertTrue(second.isBlockedAfter("thank", "fod"))
        assertTrue(engine.following("thank").none { it.word == "fod" })
        file.delete()
    }

    // ---------------------------------------------------------------- helpers

    private fun assertPredicts(previous: String, vararg acceptable: String) {
        val entries = english!!.following(previous).map { it.first }
        assertTrue("'$previous' predicted nothing", entries.isNotEmpty())
        assertTrue(
            "'$previous' predicted $entries, expected one of ${acceptable.toList()} first",
            entries.first() in acceptable
        )
    }

    companion object {
        private var english: Bigrams? = null
        private var trie: Dictionary? = null

        @BeforeClass
        @JvmStatic
        fun loadOnce() {
            // Unit tests run with the module root as the working directory.
            val table = File("src/main/assets/keyboard/en.bigrams")
            english = if (table.exists()) Bigrams.parse(table.readBytes()) else null
            val words = File("src/main/assets/keyboard/en.trie")
            trie = if (words.exists()) Dictionary.parse(words.readBytes()) else null
        }
    }
}
