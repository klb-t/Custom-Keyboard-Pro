package com.example.core

import com.example.core.io.Command
import com.example.core.io.MacroRecorder
import com.example.core.io.Macros
import com.example.core.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroTest {

    @Test
    fun `typing is replayed as fast as it likes`() {
        val r = MacroRecorder("m")
        r.record(KeyAction.Text("a"), 0)
        r.record(KeyAction.Text("b"), 900)
        assertEquals(listOf(KeyAction.Text("a"), KeyAction.Text("b")), r.steps())
    }

    @Test
    fun `a pause around an action on another app is kept, and capped`() {
        val r = MacroRecorder("m")
        r.record(KeyAction.Do(Command("tap", listOf("0.5", "0.5"))), 0)
        r.record(KeyAction.Text("x"), 10_000)
        val steps = r.steps()
        assertEquals(3, steps.size)
        assertEquals(MacroRecorder.MAX_KEPT_PAUSE, Macros.waitOf(steps[1]))
    }

    @Test
    fun `recording and playing are not part of what is recorded`() {
        val r = MacroRecorder("m")
        r.record(KeyAction.Do(Command("record", listOf("start"))), 0)
        r.record(KeyAction.Text("x"), 10)
        r.record(KeyAction.Do(Command("play", listOf("m"))), 20)
        assertEquals(listOf(KeyAction.Text("x")), r.steps())
    }

    @Test
    fun `macros survive being saved`() {
        val macros = mapOf(
            "select" to listOf(
                KeyAction.Do(Command("long_click", named = mapOf("text" to "First post"))),
                KeyAction.Do(Command("wait", listOf("400"))),
                KeyAction.Text("ok")
            )
        )
        assertEquals(macros, Macros.parse(Macros.write(macros)))
        assertEquals(emptyMap<String, List<KeyAction>>(), Macros.parse("nope"))
    }

    @Test
    fun `only a wait says how long to wait`() {
        assertEquals(500L, Macros.waitOf(KeyAction.Do(Command("wait", listOf("500")))))
        assertNull(Macros.waitOf(KeyAction.Text("wait")))
        assertNull(Macros.waitOf(KeyAction.Do(Command("back"))))
    }
}
