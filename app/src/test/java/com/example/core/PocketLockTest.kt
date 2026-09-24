package com.example.core

import com.example.core.io.HoldGesture
import com.example.core.io.KeySequence
import com.example.core.io.PocketMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ways out of the pocket lock, which matter more than the way in. */
class PocketLockTest {

    private fun seq(vararg keys: String, gap: Long = 300, pattern: String = "up, down, up"): Boolean {
        val s = KeySequence(KeySequence.parse(pattern), 3000)
        var t = 0L
        var done = false
        keys.forEach { k ->
            done = s.press(k, t)
            t += gap
        }
        return done
    }

    @Test
    fun `the sequence unlocks, in order and in time`() {
        assertTrue(seq("up", "down", "up"))
        assertFalse(seq("up", "up", "down"))
        assertFalse("too slow", seq("up", "down", "up", gap = 2000))
    }

    @Test
    fun `a stray press first does not spoil the sequence after it`() {
        assertTrue(seq("up", "up", "down", "up"))
        assertTrue(seq("down", "down", "up", "down", "up"))
    }

    @Test
    fun `plus and minus mean the volume keys`() {
        assertEquals(listOf("up", "down", "up"), KeySequence.parse("+ - +"))
        assertEquals(listOf("up", "down"), KeySequence.parse("vol+, vol-"))
        assertTrue(KeySequence(KeySequence.parse("")).isEmpty)
        assertFalse(KeySequence(emptyList()).press("up", 0))
    }

    @Test
    fun `two still fingers held long enough unlock`() {
        val h = HoldGesture(fingers = 2, holdMs = 2000, slopPx = 40f)
        assertFalse(h.update(2, 100f, 100f, 0))
        assertFalse(h.update(2, 105f, 102f, 1000))
        assertTrue(h.update(2, 104f, 101f, 2000))
    }

    @Test
    fun `one finger, or fingers that wander, never do`() {
        val one = HoldGesture(fingers = 2, holdMs = 2000, slopPx = 40f)
        assertFalse(one.update(1, 100f, 100f, 0))
        assertFalse(one.update(1, 100f, 100f, 5000))

        val wandering = HoldGesture(fingers = 2, holdMs = 2000, slopPx = 40f)
        assertFalse(wandering.update(2, 100f, 100f, 0))
        assertFalse(wandering.update(2, 300f, 100f, 1500))
        // The clock restarted where they moved to.
        assertFalse(wandering.update(2, 300f, 100f, 3000))
        assertTrue(wandering.update(2, 300f, 100f, 3500))
    }

    @Test
    fun `modes are read case blind`() {
        assertEquals(PocketMode.SCREEN, PocketMode.parse("Screen"))
        assertEquals(null, PocketMode.parse("nope"))
    }
}
