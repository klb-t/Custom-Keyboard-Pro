package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.data.BundledDictionary
import com.example.core.data.WordListSource
import com.example.core.data.WordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lists the keyboard starts from.
 *
 * The bundled file is read the way the app reads it, so packaging is covered too: a
 * word list that fails to ship is a keyboard whose suggestions look arbitrary, and
 * that is exactly the failure this replaced — 575 Polish words, which is a list of
 * pronouns rather than a dictionary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordListTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // -----------------------------------------------------------------------
    // The format
    // -----------------------------------------------------------------------

    @Test
    fun `a second column is a frequency count, not part of the word`() {
        // Published frequency lists are "word count" pairs, and every downloadable
        // source in the catalogue is one. Reading the whole line would install fifty
        // thousand words each ending in a number: it parses, it passes a size check,
        // and it suggests nothing at all.
        val parsed = BundledDictionary.parse("nie 8583207\nto 6394833\nsię 5144785")
        assertEquals(listOf("nie", "to", "się"), parsed.map { it.word })
    }

    @Test
    fun `one word per line works too, and the line number is the rank`() {
        val parsed = BundledDictionary.parse("first\nsecond\nthird")
        assertEquals(listOf(0, 1, 2), parsed.map { it.rank })
    }

    @Test
    fun `comments and blank lines are not words`() {
        val parsed = BundledDictionary.parse("# language: pl\n\n  \nreal\n# another\nalso")
        assertEquals(listOf("real", "also"), parsed.map { it.word })
    }

    @Test
    fun `a leading bang means known but not offered`() {
        val parsed = BundledDictionary.parse("ordinary\n!held")
        assertFalse(parsed[0].heldBack)
        assertTrue(parsed[1].heldBack)
        // Still in the list. It is a word, it is spelled correctly, and it can be typed.
        assertEquals("held", parsed[1].word)
    }

    @Test
    fun `the first of two identical words wins, keeping the better rank`() {
        val parsed = BundledDictionary.parse("same 100\nother 50\nsame 1")
        assertEquals(listOf("same", "other"), parsed.map { it.word })
        assertEquals(0, parsed.first().rank)
    }

    // -----------------------------------------------------------------------
    // What actually ships
    // -----------------------------------------------------------------------

    @Test
    fun `both bundled languages are a dictionary rather than a word list`() {
        listOf("pl", "en").forEach { language ->
            val size = BundledDictionary.size(context, language)
            assertTrue(
                "'$language' ships $size words, which is not enough to suggest anything",
                size > 20_000
            )
        }
    }

    @Test
    fun `the commonest words are there and come out first`() {
        val pl = BundledDictionary.startingWith(context, "pl", "nie", 5)
        assertTrue("nothing starting with 'nie' in the Polish list", pl.isNotEmpty())
        assertTrue(BundledDictionary.knows(context, "pl-PL", "jest"))
        assertTrue(BundledDictionary.knows(context, "pl", "dziękuję"))
        assertTrue(BundledDictionary.knows(context, "en-GB", "keyboard"))
        // Ordered by how common the word is, not alphabetically.
        val ranks = BundledDictionary.startingWith(context, "pl", "prz", 10).map { it.rank }
        assertEquals(ranks.sorted(), ranks)
    }

    @Test
    fun `a completion is longer than what was typed`() {
        BundledDictionary.startingWith(context, "pl", "do", 20).forEach {
            assertTrue("'${it.word}' is not a completion of 'do'", it.word.length > 2)
        }
    }

    @Test
    fun `held back words are known, spelled correctly, and never offered`() {
        // A keyboard that cannot type these is broken; one that offers them after two
        // letters is worse. Both halves matter, so both are asserted.
        assertTrue("a word it refuses to suggest is still a word", BundledDictionary.knows(context, "pl", "kurwa"))
        assertFalse(
            "a held-back word was offered unprompted",
            BundledDictionary.startingWith(context, "pl", "kur", 50).any { it.word == "kurwa" }
        )
        // And it is offered as a correction candidate, because a word somebody typed
        // must never be corrected into something else for being impolite.
        assertTrue(
            BundledDictionary.sharingFirstLetter(context, "pl", "kurwa", 4000).any { it.word == "kurwa" }
        )
    }

    @Test
    fun `a language with no list says nothing rather than guessing`() {
        assertEquals(0, BundledDictionary.size(context, "ja"))
        assertEquals(emptyList<BundledDictionary.Entry>(), BundledDictionary.startingWith(context, "ja", "a", 5))
        assertFalse(BundledDictionary.knows(context, null, "anything"))
    }

    // -----------------------------------------------------------------------
    // Where a replacement comes from
    // -----------------------------------------------------------------------

    @Test
    fun `the bundled sources parse and point somewhere plausible`() {
        WordLists.init(context)
        val sources = WordLists.all(Settings())
        assertTrue("no downloadable word lists ship with the app", sources.isNotEmpty())
        sources.forEach { source ->
            assertTrue(
                "'${source.id}' is not fetched over https",
                source.url.startsWith("https://")
            )
            assertTrue("'${source.id}' has no language", source.language.length == 2)
            assertFalse("'${source.id}' has no label", source.label.isBlank())
        }
        // The point of the list is languages the APK does not ship, so there must be one.
        assertTrue(
            "every source is for a language already bundled, which defeats the purpose",
            sources.any { it.language !in BundledDictionary.BUNDLED }
        )
    }

    @Test
    fun `a source the user added wins over one with the same id`() {
        WordLists.init(context)
        val mine = WordListSource(
            id = "freqwords_pl_50k", language = "pl", label = "Mine",
            url = "https://example.invalid/pl.txt"
        )
        val settings = Settings(
            wordListSourcesJson = """[${mine.toJson()}]"""
        )
        assertEquals("Mine", WordLists.all(settings).first { it.id == mine.id }.label)
    }

    @Test
    fun `sources survive a round trip`() {
        val source = WordListSource(
            id = "x", language = "pl", label = "Etykieta", url = "https://example.invalid/w.txt",
            note = "a note", licence = "MIT", words = 50_000
        )
        assertEquals(source, WordListSource.fromJson(source.toJson()))
    }

    @Test
    fun `a source missing what it needs is dropped rather than half-built`() {
        assertNotNull(WordLists.parseList("""[{"id":"a","language":"pl","url":"https://x/y"}]""").firstOrNull())
        assertEquals(emptyList<WordListSource>(), WordLists.parseList("""[{"id":"a","language":"pl"}]"""))
        assertEquals(emptyList<WordListSource>(), WordLists.parseList("not json"))
    }
}
