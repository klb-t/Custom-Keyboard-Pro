package com.example.core

import com.example.core.text.CapitalHow
import com.example.core.text.CapitalMoment
import com.example.core.text.CapitalRule
import com.example.core.text.CapitalWhen
import com.example.core.text.Capitalisation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capitalisation as four questions rather than one switch.
 *
 * "Capitalise sentences" hides: when does a capital belong, what should the keyboard
 * do about it, at which moment is it asked, and how do rules combine. A boolean
 * answers all four and shows none of them. These tests hold each answer separately,
 * and in particular hold the one that used to be a hardcoded exception — that a
 * sentence boundary found on *opening* a field is not the same event as one produced
 * by typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapitalisationTest {

    private fun at(before: String, startOfField: Boolean = false) =
        Capitalisation.situations(before, startOfField)

    // -----------------------------------------------------------------------
    // When a capital is implied
    // -----------------------------------------------------------------------

    @Test
    fun `an empty field is the start of one, but only when that is known`() {
        assertTrue(CapitalWhen.FIELD_START in at("", startOfField = true))
        // The same emptiness with nothing to vouch for it is not a claim. An unread
        // field looks exactly like an empty one from here, and treating the two alike
        // is what made the keyboard capitalise in the middle of sentences.
        assertFalse(CapitalWhen.FIELD_START in at("", startOfField = false))
    }

    @Test
    fun `a finished sentence implies one`() {
        assertTrue(CapitalWhen.SENTENCE_END in at("Done. "))
        assertTrue(CapitalWhen.SENTENCE_END in at("Really? "))
        assertTrue(CapitalWhen.SENTENCE_END in at("Stop! "))
        assertTrue(CapitalWhen.SENTENCE_END in at("Well… "))
    }

    @Test
    fun `an abbreviation does not`() {
        assertFalse(CapitalWhen.SENTENCE_END in at("e.g. "))
        assertFalse(CapitalWhen.SENTENCE_END in at("i.e. "))
        // But a real one-letter sentence does, because the letter is preceded by a
        // space rather than by another letter's dot.
        assertTrue(CapitalWhen.SENTENCE_END in at("I said no. "))
    }

    @Test
    fun `a full stop with nothing after it is not a boundary yet`() {
        // The space is what ends the sentence for typing purposes. Without it the
        // cursor is still touching the dot.
        assertFalse(CapitalWhen.SENTENCE_END in at("Done."))
    }

    @Test
    fun `mid-sentence implies nothing`() {
        assertFalse(CapitalWhen.SENTENCE_END in at("hello "))
        assertFalse(CapitalWhen.SENTENCE_END in at("in the "))
        assertFalse(CapitalWhen.SENTENCE_END in at("halfwa"))
    }

    @Test
    fun `a line break is its own situation`() {
        assertTrue(CapitalWhen.LINE_START in at("Line one\n"))
        assertTrue(CapitalWhen.LINE_START in at("Line one\r\n"))
        // And not a sentence end, so somebody can capitalise one and not the other.
        assertFalse(CapitalWhen.SENTENCE_END in at("Line one.\n"))
    }

    @Test
    fun `a bullet or a number opens a list item`() {
        assertTrue(CapitalWhen.LIST_ITEM in at("- "))
        assertTrue(CapitalWhen.LIST_ITEM in at("shopping\n* "))
        assertTrue(CapitalWhen.LIST_ITEM in at("steps\n1. "))
        assertTrue(CapitalWhen.LIST_ITEM in at("steps\n  2) "))
        // Only while the line is still just the bullet. Once there are words it is an
        // ordinary line and capitalising mid-item would be wrong.
        assertFalse(CapitalWhen.LIST_ITEM in at("- milk and "))
    }

    @Test
    fun `a colon is offered because some people and some languages want it`() {
        assertTrue(CapitalWhen.AFTER_COLON in at("Note: "))
        assertFalse(CapitalWhen.AFTER_COLON in at("Note:"))
    }

    @Test
    fun `the start of any word is a situation, which is how Title Case is possible`() {
        assertTrue(CapitalWhen.WORD_START in at("hello "))
        assertTrue(CapitalWhen.WORD_START in at("", startOfField = true))
        assertFalse(CapitalWhen.WORD_START in at("halfwa"))
    }

    // -----------------------------------------------------------------------
    // What is done about it, and when it is asked
    // -----------------------------------------------------------------------

    @Test
    fun `the same text answers differently depending on the moment`() {
        // The whole reason the moment is a field on a rule rather than a branch in the
        // code. Typing "Done. " and getting a capital next is right; opening a field
        // that already contains "Done. " and being given one is presumptuous, because
        // a field is opened to be edited far more often than to be continued.
        val situations = at("Done. ")
        assertEquals(
            CapitalHow.SHIFT,
            Capitalisation.action(Capitalisation.DEFAULT, situations, CapitalMoment.TYPING)
        )
        assertEquals(
            CapitalHow.NOTHING,
            Capitalisation.action(Capitalisation.DEFAULT, situations, CapitalMoment.OPENING)
        )
    }

    @Test
    fun `an empty field is capitalised at either moment`() {
        val situations = at("", startOfField = true)
        listOf(CapitalMoment.OPENING, CapitalMoment.TYPING).forEach { moment ->
            assertEquals(
                CapitalHow.SHIFT,
                Capitalisation.action(Capitalisation.DEFAULT, situations, moment)
            )
        }
    }

    @Test
    fun `a rule that says nothing cannot be outbid`() {
        // A veto anything can outbid is not a veto. This is how somebody says "not
        // after a colon" while keeping everything else, and it has to hold even when
        // another matching rule is enthusiastic.
        val rules = Capitalisation.DEFAULT + listOf(
            CapitalRule(CapitalWhen.WORD_START, CapitalHow.SHIFT),
            CapitalRule(CapitalWhen.SENTENCE_END, CapitalHow.NOTHING)
        )
        assertEquals(
            CapitalHow.NOTHING,
            Capitalisation.action(rules, at("Done. "), CapitalMoment.TYPING)
        )
    }

    @Test
    fun `pressing shift is preferred to rewriting what was typed`() {
        // Both mechanisms apply; the one that does not touch existing text wins. Same
        // principle as the two lanes: adding is free, changing is not.
        val rules = listOf(
            CapitalRule(CapitalWhen.WORD_START, CapitalHow.FIX_AFTER_WORD),
            CapitalRule(CapitalWhen.SENTENCE_END, CapitalHow.SHIFT)
        )
        assertEquals(
            CapitalHow.SHIFT,
            Capitalisation.action(rules, at("Done. "), CapitalMoment.TYPING)
        )
    }

    @Test
    fun `Title Case is one rule`() {
        // The point of the decomposition: a behaviour nobody wrote code for falls out
        // of the pieces. Useful in a name field, and previously impossible to ask for.
        val rules = listOf(CapitalRule(CapitalWhen.WORD_START, CapitalHow.SHIFT))
        assertEquals(
            CapitalHow.SHIFT,
            Capitalisation.action(rules, at("john "), CapitalMoment.TYPING)
        )
    }

    @Test
    fun `typing freely and being tidied up afterwards is another`() {
        val rules = listOf(
            CapitalRule(CapitalWhen.SENTENCE_END, CapitalHow.FIX_AFTER_WORD, setOf(CapitalMoment.TYPING))
        )
        assertEquals(
            CapitalHow.FIX_AFTER_WORD,
            Capitalisation.action(rules, at("Done. "), CapitalMoment.TYPING)
        )
    }

    @Test
    fun `no rules means nothing happens`() {
        assertEquals(
            CapitalHow.NOTHING,
            Capitalisation.action(emptyList(), at("Done. "), CapitalMoment.TYPING)
        )
    }

    // -----------------------------------------------------------------------
    // Rules are data, so they have to survive being written down
    // -----------------------------------------------------------------------

    @Test
    fun `rules survive a round trip`() {
        val rules = listOf(
            CapitalRule(CapitalWhen.FIELD_START, CapitalHow.SHIFT),
            CapitalRule(CapitalWhen.SENTENCE_END, CapitalHow.FIX_AFTER_WORD, setOf(CapitalMoment.TYPING)),
            CapitalRule(CapitalWhen.AFTER_COLON, CapitalHow.NOTHING, emptySet())
        )
        assertEquals(rules, Capitalisation.fromJson(Capitalisation.toJson(rules)))
    }

    @Test
    fun `the built-in set is what blank means, and an empty list is not blank`() {
        assertEquals(Capitalisation.DEFAULT, Capitalisation.fromJson(""))
        assertEquals(Capitalisation.DEFAULT, Capitalisation.fromJson("   "))
        // Explicitly asking for no rules is a different thing from not having said.
        assertEquals(emptyList<CapitalRule>(), Capitalisation.fromJson("[]"))
    }

    @Test
    fun `something unreadable falls back rather than leaving the keyboard ruleless`() {
        assertEquals(Capitalisation.DEFAULT, Capitalisation.fromJson("not json"))
        // A rule naming a situation this build does not know is dropped, and the rest
        // still work — so a newer rules file does not disable capitalisation wholesale.
        val mixed = """[{"on":"from_the_future","does":"shift"},{"on":"field_start","does":"shift"}]"""
        assertEquals(
            listOf(CapitalRule(CapitalWhen.FIELD_START, CapitalHow.SHIFT)),
            Capitalisation.fromJson(mixed)
        )
    }

    @Test
    fun `a rule written without a moment applies at both`() {
        val parsed = Capitalisation.fromJson("""[{"on":"line_start","does":"shift"}]""")
        assertEquals(setOf(CapitalMoment.OPENING, CapitalMoment.TYPING), parsed.single().at)
    }

    @Test
    fun `capitalising leaves alone what does not need it`() {
        assertEquals("John", Capitalisation.capitalise("john"))
        assertEquals("Ósmy", Capitalisation.capitalise("ósmy"))
        assertEquals("John", Capitalisation.capitalise("John"))
        assertEquals("123", Capitalisation.capitalise("123"))
        assertEquals("", Capitalisation.capitalise(""))
    }
}
