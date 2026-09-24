package com.example.core

import com.example.core.text.CursorMagnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrows' pull towards a typo. What matters is where it refuses to act as much as
 * where it acts: a pull in the wrong place moves the cursor out from under somebody.
 */
class CursorMagnetTest {

    private val known = setOf("the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "test")

    private fun suspects(text: String, fixes: Map<String, String> = emptyMap()) =
        CursorMagnet.words(text)
            .filter { CursorMagnet.worthChecking(it, text) }
            .filter { it.word.lowercase() !in known }
            .map { CursorMagnet.suspect(it, fixes[it.word.lowercase()]) }

    @Test
    fun `the slip is found inside the word, whatever kind it is`() {
        assertEquals(2, CursorMagnet.landing("tast", "test"))   // wrong letter: after it
        assertEquals(1, CursorMagnet.landing("tst", "test"))    // missing letter: at the gap
        assertEquals(4, CursorMagnet.landing("tesst", "test"))  // extra letter: after it
        assertEquals(3, CursorMagnet.landing("teh", "the"))     // swapped pair: after both
        assertEquals(3, CursorMagnet.landing("tes", "test"))    // missing at the end
        assertEquals(4, CursorMagnet.landing("abcd", null))     // nothing to compare: the end
    }

    @Test
    fun `presses that stop near the only typo finish the trip`() {
        // "the quikc brown fox" — typo at 4..9, noticed at the end of the line.
        val text = "the quikc brown fox"
        val s = suspects(text, mapOf("quikc" to "quick"))
        assertEquals(1, s.size)
        val landing = s.single().landing
        assertEquals(4 + 5, landing)
        // Ten presses left from the end land at 9... one short at 10, one over at 8.
        assertEquals(landing, CursorMagnet.aim(s, text.length, 11, -1, reach = 3))
        assertEquals(landing, CursorMagnet.aim(s, text.length, 7, -1, reach = 3))
    }

    @Test
    fun `presses that stop far from it are left alone`() {
        val text = "the quikc brown fox jumps over"
        val s = suspects(text, mapOf("quikc" to "quick"))
        assertNull(CursorMagnet.aim(s, text.length, 20, -1, reach = 3))
    }

    @Test
    fun `a typo behind the run does not pull it back`() {
        // Heading right, away from the typo: whatever is near, it is not the goal.
        val text = "quikc brown fox"
        val s = suspects(text, mapOf("quikc" to "quick"))
        assertNull(CursorMagnet.aim(s, runStart = 6, cursor = 7, direction = 1, reach = 3))
    }

    @Test
    fun `two typos equally near is a guess, and guesses do not move the cursor`() {
        // Stopped among the dots, four characters from each.
        val text = "teh ... fxo brown"
        val s = suspects(text)
        assertEquals(2, s.size)
        assertNull(CursorMagnet.aim(s, text.length, 7, -1, reach = 5))
    }

    @Test
    fun `already standing on the slip, nothing moves`() {
        val text = "the quikc"
        val s = suspects(text, mapOf("quikc" to "quick"))
        assertNull(CursorMagnet.aim(s, text.length, s.single().landing, -1, reach = 3))
    }

    @Test
    fun `a stride goes straight to the nearest typo ahead within reach`() {
        val text = "teh quick brown fox jumps over the lazy dgo."
        val s = suspects(text, mapOf("dgo" to "dog", "teh" to "the"))
        val target = CursorMagnet.stride(s, text.length, -1, stride = 40)
        assertEquals(s.first { it.word == "dgo" }.landing, target)
        assertNull(CursorMagnet.stride(s, text.length, -1, stride = 0))
    }

    @Test
    fun `names and acronyms are not suspects, a capital at a sentence start is`() {
        val text = "I met Kowalski at NASA. Teh end"
        val checked = CursorMagnet.words(text).filter { CursorMagnet.worthChecking(it, text) }.map { it.word }
        assertFalse("a name mid-sentence is not a typo", "Kowalski" in checked)
        assertFalse("an acronym is not a typo", "NASA" in checked)
        assertTrue("a sentence can start with a typo", "Teh" in checked)
    }

    @Test
    fun `words keep their apostrophes and diacritics`() {
        val words = CursorMagnet.words("don't źdźbło, x").map { it.word }
        assertEquals(listOf("don't", "źdźbło", "x"), words)
    }
}
