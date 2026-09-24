package com.example.core

import com.example.core.hitmap.LongPressTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTunerTest {

    @Test
    fun `nothing is concluded from a handful of taps`() {
        val t = LongPressTuner(130)
        repeat(5) { t.tap(125) }
        assertNull(t.guard())
        assertEquals(130L, t.threshold)
    }

    @Test
    fun `taps crowding the threshold push it up gently, not all at once`() {
        val t = LongPressTuner(130)
        repeat(30) { t.tap(140) }
        // Guard is 140 + margin; each tap may move it one step at most.
        assertEquals(170L, t.threshold)
        assertTrue(t.threshold <= 140 + LongPressTuner.MARGIN)
    }

    @Test
    fun `an alternate deleted at once makes it a little longer`() {
        val t = LongPressTuner(130)
        assertEquals(130L + LongPressTuner.STEP, t.accidental())
    }

    @Test
    fun `wanting the strip sooner shortens it, but never into the taps`() {
        val t = LongPressTuner(200)
        repeat(30) { t.tap(80) }
        repeat(20) { t.tooSlow() }
        assertEquals(80L + LongPressTuner.MARGIN, t.threshold)
    }

    @Test
    fun `the bounds hold whatever happens`() {
        val t = LongPressTuner(130, min = 90, max = 600)
        repeat(200) { t.accidental() }
        assertEquals(600L, t.threshold)
        val u = LongPressTuner(130, min = 90, max = 600)
        repeat(200) { u.tooSlow() }
        assertEquals(90L, u.threshold)
    }
}
