package com.example.core

import com.example.core.text.TextOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cover the behaviours the previous keyboard got wrong, so a regression here
 * would be a return to a bug people actually hit.
 */
class TextOpsTest {

    @Test
    fun `backspace removes a whole emoji, not half of one`() {
        // A single emoji is two Java chars; deleting one leaves a broken surrogate.
        assertEquals(2, TextOps.lastGraphemeLength("ok \uD83D\uDE00"))
    }

    @Test
    fun `backspace removes an emoji with a skin tone modifier in one press`() {
        // U+1F44B WAVING HAND followed by U+1F3FD EMOJI MODIFIER FITZPATRICK TYPE-4.
        val waveWithTone = "\uD83D\uDC4B\uD83C\uDFFD"
        assertEquals(waveWithTone.length, TextOps.lastGraphemeLength(waveWithTone))
    }

    @Test
    fun `backspace removes a combining accent together with its letter`() {
        val decomposed = "é" // e + combining acute
        assertEquals(2, TextOps.lastGraphemeLength("caf$decomposed"))
    }

    @Test
    fun `backspace on plain text removes one character`() {
        assertEquals(1, TextOps.lastGraphemeLength("abc"))
        assertEquals(0, TextOps.lastGraphemeLength(""))
    }

    @Test
    fun `word delete takes the word and the space before it`() {
        assertEquals(5, TextOps.backwardWordLength("hello world"))
        assertEquals(6, TextOps.backwardWordLength("hello "))
        assertEquals(3, TextOps.backwardWordLength("   "))
    }

    @Test
    fun `forward word delete takes leading space and the word`() {
        assertEquals(6, TextOps.forwardWordLength(" world rest"))
    }

    @Test
    fun `line boundaries ignore text on other lines`() {
        assertEquals(3, TextOps.toLineStartLength("first\nabc"))
        assertEquals(3, TextOps.toLineEndLength("abc\nrest"))
    }

    @Test
    fun `current word stops at punctuation`() {
        assertEquals("world", TextOps.currentWord("hello world"))
        assertEquals("", TextOps.currentWord("hello "))
        assertEquals("don't", TextOps.currentWord("well don't"))
    }

    @Test
    fun `capitalises at the start and after a sentence`() {
        assertTrue(TextOps.shouldCapitalise(""))
        assertTrue(TextOps.shouldCapitalise("Done. "))
        assertTrue(TextOps.shouldCapitalise("Line one\n"))
    }

    @Test
    fun `does not capitalise mid-sentence or after an abbreviation`() {
        assertFalse(TextOps.shouldCapitalise("hello "))
        assertFalse(TextOps.shouldCapitalise("in the "))
        assertFalse(TextOps.shouldCapitalise("e.g. "))
    }

    @Test
    fun `double space becomes a full stop only after something to end`() {
        assertEquals(". ", TextOps.doubleSpaceReplacement("word "))
        assertNull(TextOps.doubleSpaceReplacement("word  "))
        assertNull(TextOps.doubleSpaceReplacement(" "))
    }

    @Test
    fun `smart quotes open and close by position`() {
        assertEquals("\u201C", TextOps.smartQuote('"', "he said "))
        assertEquals("\u201D", TextOps.smartQuote('"', "he said \u201Cyes"))
    }

    @Test
    fun `dead keys produce a precomposed character`() {
        // Combining marks in, single code points out.
        assertEquals("\u00E9", TextOps.applyDeadKey("\u0301", "e"))   // e + acute -> é
        assertEquals("\u0105", TextOps.applyDeadKey("\u0328", "a"))   // a + ogonek -> ą
        assertEquals("\u00D6", TextOps.applyDeadKey("\u0308", "O"))   // O + diaeresis -> Ö
        assertEquals(1, TextOps.applyDeadKey("\u0301", "e").length)
    }

    @Test
    fun `stroke is handled where there is no combining form`() {
        // There is no precomposed "l + combining stroke", so these are mapped directly.
        assertEquals("\u0142", TextOps.applyStroke("l"))  // ł
        assertEquals("\u00D8", TextOps.applyStroke("O"))  // Ø
    }

    @Test
    fun `hex unicode entry accepts the usual prefixes`() {
        assertEquals("\u2192", TextOps.codePointFromHex("2192"))
        assertEquals("\u2192", TextOps.codePointFromHex("U+2192"))
        assertEquals("\uD83D\uDE00", TextOps.codePointFromHex("1F600"))
        assertNull(TextOps.codePointFromHex("zzzz"))
        assertNull(TextOps.codePointFromHex("110000"))
    }

    @Test
    fun `words splits on punctuation but keeps apostrophes`() {
        assertEquals(listOf("don't", "stop", "me", "now"), TextOps.words("Don't stop me now!").map { it.lowercase() })
    }
}
