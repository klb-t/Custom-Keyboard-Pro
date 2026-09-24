package com.example.core

import com.example.core.layout.ControlDeckLayout
import com.example.core.layout.ControlDef
import com.example.core.layout.ControlKind
import com.example.core.layout.Controls
import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sliders, knobs, pads and switches: the numbers under the drawing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlsTest {

    private val speed = ControlDef(ControlKind.KNOB, min = 0.5, max = 2.5, step = 0.05, value = 1.0, action = "do:set ttsRate {v}")

    @Test
    fun `values snap to the step and stay in range`() {
        assertEquals(1.05, Controls.quantize(1.037, speed), 1e-9)
        assertEquals(2.5, Controls.quantize(9.0, speed), 1e-9)
        assertEquals(0.5, Controls.quantize(-3.0, speed), 1e-9)
    }

    @Test
    fun `a position along the control is a value, and back`() {
        val fader = ControlDef(min = 0.0, max = 1.0)
        assertEquals(0.25, Controls.fromFraction(0.25, fader), 1e-9)
        assertEquals(0.5, Controls.fraction(1.5, speed), 1e-9)
    }

    @Test
    fun `a knob turns up with a drag up or right, over its whole range in one travel`() {
        val up = Controls.knobAfterDrag(1.5, 0f, -100f, 200f, speed)
        assertEquals(2.5, up, 1e-9)
        val down = Controls.knobAfterDrag(1.5, -50f, 0f, 200f, speed)
        assertEquals(1.0, down, 1e-9)
    }

    @Test
    fun `the value goes into the action, in every form it is asked for`() {
        assertEquals("do:set ttsRate 1.25", Controls.fill(speed.action, 1.25, speed))
        val sw = ControlDef(ControlKind.TOGGLE, min = 0.0, max = 1.0, decimals = 0)
        assertEquals("do:torch on", Controls.fill("do:torch {on}", 1.0, sw))
        assertEquals("do:torch off", Controls.fill("do:torch {on}", 0.0, sw))
        val pad = ControlDef(ControlKind.XY)
        // A pad's percentage is of its across value.
        assertEquals("x=0.20 y=0.80 p=20", Controls.fill("x={x} y={y} p={pct}", 0.2, pad, 0.8))
        val filled = LayoutJson.parseAction(Controls.fill(speed.action, 1.25, speed))
        assertTrue(filled is KeyAction.Do)
        assertEquals("1.25", (filled as KeyAction.Do).command.arg("value"))
    }

    @Test
    fun `a trembling finger inside a step is not a change`() {
        assertFalse(Controls.changed(1.0, 1.01, speed))
        assertTrue(Controls.changed(1.0, 1.05, speed))
        val fader = ControlDef()
        assertFalse(Controls.changed(0.5, 0.505, fader))
        assertTrue(Controls.changed(0.5, 0.52, fader))
    }

    @Test
    fun `controls survive the layout codec`() {
        val original = ControlDeckLayout.CONTROL_DECK
        val back = LayoutJson.parse(LayoutJson.writeString(original))
        assertEquals(original.elements, back.elements)
    }

    @Test
    fun `where a control was left survives being saved`() {
        val values = mapOf("control_deck/volume" to (0.3 to 0.3), "l/pad" to (0.1 to 0.9))
        assertEquals(values, Controls.parseValues(Controls.writeValues(values)))
        assertEquals(emptyMap<String, Pair<Double, Double>>(), Controls.parseValues("junk"))
    }
}
