package com.example.core

import com.example.ime.ComposeSequences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeSequencesTest {

    @Test
    fun `known sequences resolve`() {
        assertEquals("→", ComposeSequences.resolve("->"))
        assertEquals("©", ComposeSequences.resolve("oc"))
        assertEquals("—", ComposeSequences.resolve("---"))
        assertEquals("€", ComposeSequences.resolve("e="))
    }

    @Test
    fun `an unknown sequence resolves to nothing`() {
        assertNull(ComposeSequences.resolve("qq"))
    }

    @Test
    fun `a prefix of a longer sequence keeps the buffer open`() {
        // "--" is itself a sequence, but "---" is longer, so typing must continue.
        assertTrue(ComposeSequences.isPrefix("--"))
        assertFalse(ComposeSequences.isPrefix("zz"))
    }

    @Test
    fun `the table has no empty entries`() {
        ComposeSequences.all().forEach { (sequence, result) ->
            assertTrue(sequence.isNotEmpty())
            assertTrue(result.isNotEmpty())
        }
    }
}
