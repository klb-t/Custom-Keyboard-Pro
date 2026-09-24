package com.example.core

import com.example.core.engine.InputSource
import com.example.core.engine.Inputs
import com.example.core.engine.MotionGestures
import com.example.core.engine.ProximityGesture
import com.example.core.engine.Wire
import com.example.core.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine's inputs: wires as written, and gestures that fire when meant and not
 * when the phone is merely being carried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineTest {

    @Test
    fun `wires read the way a person writes them`() {
        val wires = Inputs.parse(
            """[
                {"on": "volume_down", "do": "cursor:left", "in": "com.termux"},
                {"on": "shake", "do": "do:torch toggle"},
                {"on": "face_down", "do": {"type": "do", "command": "media pause"}},
                {"on": "", "do": "x"},
                {"on": "cover", "do": "do:back", "enabled": false}
            ]"""
        )
        assertEquals(4, wires.size)
        assertTrue(wires[0].parsed is KeyAction.MoveCursor)
        assertTrue(wires[1].parsed is KeyAction.Do)
        assertEquals("pause", (wires[2].parsed as KeyAction.Do).command.arg("action"))
        assertEquals(wires, Inputs.parse(Inputs.write(wires)))
    }

    @Test
    fun `a wire limited to an app fires only there, and a disabled one nowhere`() {
        val wires = Inputs.parse(
            """[{"on": "volume_down", "do": "cursor:left", "in": ["com.termux"]},
                {"on": "cover", "do": "do:back", "enabled": false}]"""
        )
        assertEquals(1, Inputs.firing(wires, "volume_down", "com.termux").size)
        assertEquals(0, Inputs.firing(wires, "volume_down", "com.other").size)
        assertEquals(0, Inputs.firing(wires, "cover", "com.termux").size)
    }

    @Test
    fun `nothing is listened to that no wire needs`() {
        assertEquals(emptySet<InputSource>(), Inputs.sourcesNeeded(emptyList()))
        val needed = Inputs.sourcesNeeded(listOf(Wire("shake", "do:back"), Wire("cover", "x", enabled = false)))
        assertEquals(setOf(InputSource.MOTION), needed)
    }

    @Test
    fun `junk wires are none rather than a guess`() {
        assertEquals(emptyList<Wire>(), Inputs.parse("not json"))
        assertEquals(emptyList<Wire>(), Inputs.parse(""))
    }

    @Test
    fun `every input id is known to the catalogue exactly once`() {
        assertEquals(Inputs.ALL.size, Inputs.ALL.map { it.id }.toSet().size)
    }

    private fun still(m: MotionGestures, x: Float, y: Float, z: Float, fromMs: Long, toMs: Long): List<String> {
        val out = mutableListOf<String>()
        var t = fromMs
        while (t <= toMs) {
            m.reading(x, y, z, t)?.let { out += it }
            t += 20
        }
        return out
    }

    @Test
    fun `a firm shake is one shake`() {
        val m = MotionGestures()
        val fired = mutableListOf<String>()
        var t = 0L
        repeat(8) { i ->
            val jolt = if (i % 2 == 0) 25f else -15f
            m.reading(jolt, 9.81f, 0f, t)?.let { fired += it }
            t += 100
        }
        assertEquals(listOf("shake"), fired)
    }

    @Test
    fun `held upright and still, nothing happens`() {
        val m = MotionGestures()
        assertEquals(emptyList<String>(), still(m, 0.3f, 9.7f, 1.0f, 0, 5000))
    }

    @Test
    fun `lying flat and still, nothing happens either`() {
        val m = MotionGestures()
        assertEquals(emptyList<String>(), still(m, 0.1f, 0.2f, 9.8f, 0, 5000))
    }

    @Test
    fun `turned face down and left there, then picked up`() {
        val m = MotionGestures()
        still(m, 0f, 0f, 9.8f, 0, 500)
        assertEquals(listOf("face_down"), still(m, 0f, 0f, -9.8f, 520, 2000))
        assertEquals(listOf("face_up"), still(m, 0f, 0f, 9.8f, 3000, 3500))
    }

    @Test
    fun `a roll to the left held briefly is one tilt`() {
        val m = MotionGestures()
        still(m, 0f, 6.9f, 6.9f, 0, 500)
        // About 45 degrees of roll: x takes a large share of gravity.
        val fired = still(m, 6.9f, 4.9f, 4.9f, 520, 1500)
        assertEquals(listOf("tilt_left"), fired)
    }

    @Test
    fun `a hand over the sensor is a cover and taking it away an uncover`() {
        val p = ProximityGesture()
        assertNull(p.reading(5f, 5f, 0))
        assertEquals("cover", p.reading(0f, 5f, 1000))
        assertEquals("uncover", p.reading(5f, 5f, 2000))
        assertNull("no change, no event", p.reading(5f, 5f, 3000))
    }
}
