package com.example.core

import com.example.core.speech.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cutting text for the speech engine, and finding where to pick up again. */
class SpeechTextTest {

    @Test
    fun `short text is one piece`() {
        val c = SpeechText.chunks("Ala ma kota.", 100)
        assertEquals(listOf(SpeechText.Chunk(0, "Ala ma kota.")), c)
    }

    @Test
    fun `long text is cut at sentence ends and every piece knows where it starts`() {
        val sentence = "To jest zdanie numer jeden. "
        val text = sentence.repeat(20)
        val chunks = SpeechText.chunks(text, 100)
        assertTrue(chunks.size > 1)
        chunks.forEach { c ->
            assertTrue("too long: ${c.text.length}", c.text.length <= 100)
            assertEquals(c.text, text.substring(c.start, c.start + c.text.length))
            assertTrue("cut mid-sentence: '${c.text.takeLast(10)}'", c.text.trimEnd().endsWith("."))
        }
        // Nothing is lost between the pieces.
        assertEquals(text.replace(" ", ""), chunks.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun `a sentence with no end is cut at a space, never mid-word`() {
        val text = "słowo ".repeat(50)
        SpeechText.chunks(text, 40).forEach { c ->
            val endsCleanly = c.start + c.text.length >= text.length || text[c.start + c.text.length - 1] == ' '
            assertTrue("cut mid-word at ${c.start}", endsCleanly)
        }
    }

    @Test
    fun `resuming starts at the beginning of the word`() {
        val text = "czytam ten tekst"
        assertEquals(7, SpeechText.wordStart(text, 9))
        assertEquals(0, SpeechText.wordStart(text, 3))
        assertEquals(7, SpeechText.wordStart(text, 7))
    }

    @Test
    fun `the last sentence and the last word are what was just written`() {
        assertEquals("Drugie zdanie.", SpeechText.lastSentence("Pierwsze zdanie. Drugie zdanie."))
        assertEquals("A teraz trzecie", SpeechText.lastSentence("Raz. A teraz trzecie"))
        assertEquals("kota", SpeechText.lastWord("Ala ma kota, "))
    }

    @Test
    fun `a page scrolled under the reader is not read twice`() {
        val seen = mutableSetOf<String>()
        assertEquals(listOf("a", "b", "c"), SpeechText.unread(listOf("a", "b", "c"), seen))
        assertEquals(listOf("d"), SpeechText.unread(listOf("b", "c", "d"), seen))
    }

    @Test
    fun `echo modes are read case blind`() {
        assertEquals(SpeechText.Echo.WORDS, SpeechText.Echo.parse("words"))
        assertEquals(SpeechText.Echo.OFF, SpeechText.Echo.parse("nonsense"))
    }
}
