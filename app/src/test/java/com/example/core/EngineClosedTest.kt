package com.example.core

import com.example.core.config.Knobs
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import com.example.core.config.knob
import com.example.core.engine.EngineState
import com.example.core.engine.InputSource
import com.example.core.engine.Inputs
import com.example.core.engine.Phrases
import com.example.core.engine.VolumeGestures
import com.example.core.engine.Wire
import com.example.core.engine.WireScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The engine with the keyboard closed: scopes, side keys, spoken phrases, knobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineClosedTest {

    private val closed = EngineState(keyboardOpen = false, locked = false, pkg = "com.maps")
    private val locked = closed.copy(locked = true)
    private val typing = closed.copy(keyboardOpen = true)

    @Test
    fun `a wire fires only in its scope`() {
        val wires = Inputs.parse(
            """[
              {"on": "shake", "do": "do:torch toggle", "when": "closed"},
              {"on": "shake", "do": "cursor:left", "when": "keyboard"},
              {"on": "voice", "phrase": "otwórz sezamie", "do": "do:pocket_lock off", "when": "locked"}
            ]"""
        )
        assertEquals(1, Inputs.firing(wires, "shake", closed).size)
        assertEquals("cursor:left", Inputs.firing(wires, "shake", typing).single().action)
        assertTrue(Inputs.firing(wires, "voice", closed).isEmpty())
        assertEquals(1, Inputs.firing(wires, "voice", locked).size)
    }

    @Test
    fun `the microphone is wanted only while a voice wire is live`() {
        val wires = listOf(Wire("voice", "do:pocket_lock off", scope = WireScope.LOCKED, phrase = "open"))
        assertFalse(InputSource.VOICE in Inputs.sourcesNeeded(wires, closed))
        assertTrue(InputSource.VOICE in Inputs.sourcesNeeded(wires, locked))
    }

    @Test
    fun `a wire limited to an app switches nothing on elsewhere`() {
        val wires = listOf(Wire("shake", "do:back", apps = listOf("com.maps")))
        assertTrue(InputSource.MOTION in Inputs.sourcesNeeded(wires, closed))
        assertFalse(InputSource.MOTION in Inputs.sourcesNeeded(wires, closed.copy(pkg = "com.other")))
    }

    @Test
    fun `scope and exclusions survive the round trip`() {
        val wires = listOf(
            Wire("voice", "do:pocket_lock off", scope = WireScope.LOCKED, phrase = "open sesame", notIn = listOf("x.y"))
        )
        assertEquals(wires, Inputs.parse(Inputs.write(wires)))
    }

    @Test
    fun `a single press still changes the volume when only the double is wired`() {
        val g = VolumeGestures(doubleMs = 350, longMs = 600)
        val wired = setOf("volume_down_double")
        assertTrue(g.onDown("down", false, 0, wired).consume)
        val up = g.onUp("down", 80, wired)
        assertTrue(up.fire.isEmpty())
        val later = g.due(80 + 351, wired)
        assertEquals(listOf("down"), later.passVolume)
        assertTrue(later.fire.isEmpty())
    }

    @Test
    fun `two quick presses are the double, and nothing else`() {
        val g = VolumeGestures(350, 600)
        val wired = setOf("volume_down_double")
        g.onDown("down", false, 0, wired)
        g.onUp("down", 80, wired)
        g.onDown("down", false, 200, wired)
        val up = g.onUp("down", 260, wired)
        assertEquals(listOf("volume_down_double"), up.fire)
        assertTrue(g.due(1000, wired).passVolume.isEmpty())
    }

    @Test
    fun `held long enough is the long press, and releasing it does not step the volume`() {
        val g = VolumeGestures(350, 600)
        val wired = setOf("volume_up_long")
        val down = g.onDown("up", false, 0, wired)
        assertEquals(600L, down.checkAt)
        assertEquals(listOf("volume_up_long"), g.due(600, wired).fire)
        val up = g.onUp("up", 900, wired)
        assertTrue(up.fire.isEmpty() && up.passVolume.isEmpty())
    }

    @Test
    fun `an unwired key is not ours at all`() {
        val g = VolumeGestures()
        assertFalse(g.onDown("up", false, 0, setOf("volume_down_double")).consume)
    }

    @Test
    fun `a phrase is heard inside other words, without diacritics, with a slip`() {
        assertTrue(Phrases.matches("Otwórz sezamie", "no dobra otworz sezamie proszę", 1.0))
        assertTrue(Phrases.matches("zablokuj telefon teraz", "zablokuj telefom teraz", 1.0))
        assertFalse(Phrases.matches("otwórz sezamie", "zamknij sezamie", 1.0))
        assertTrue(Phrases.matches("otwórz ten sezam proszę", "otwórz sezam proszę", 0.75))
        assertFalse(Phrases.matches("", "anything", 0.5))
    }

    @Test
    fun `every knob is a setting with a sane range, and changing one sticks`() {
        Knobs.ALL.forEach { k ->
            assertTrue("${k.id}: default outside range", k.default in k.min..k.max)
            assertTrue("${k.id} is not a setting", SettingsSchema.spec(k.key) != null)
        }
        assertEquals(Knobs.ALL.size, Knobs.ALL.map { it.id }.toSet().size)
        val changed = SettingsSchema.withValue(Settings(), Knobs.SHAKE_FORCE.key, 20.0)
        assertEquals(20.0, changed.knob(Knobs.SHAKE_FORCE), 1e-9)
        // Unchanged knobs are not stored, so a better default later reaches everyone.
        assertEquals(setOf(Knobs.SHAKE_FORCE.id), SettingsStore.fromJson(SettingsStore.toJson(changed)).knobs.keys)
    }
}
