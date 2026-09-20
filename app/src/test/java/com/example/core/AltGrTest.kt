package com.example.core

import com.example.core.config.Settings
import com.example.core.layout.BuiltinLayouts
import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutDef
import com.example.core.layout.ModifierKind
import com.example.core.layout.ModifierMode
import com.example.ime.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AltGr as a layer, the way a PC layout has one.
 *
 * The point of making it a layer rather than a rule in the key handler is that which
 * character AltGr produces is a fact about a language, not about a keyboard — so it
 * has to be data, and a layout that arrives as data must be able to bring its own.
 * These tests hold the Polish one to what a Polish PC does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltGrTest {

    private val pl = BuiltinLayouts.QWERTY_PL
    private val en = BuiltinLayouts.QWERTY_EN

    private fun typed(layerName: String, keyId: String): String? {
        val layer = pl.layer(layerName) ?: return null
        val key = layer.rows.flatMap { it.keys }.firstOrNull { it.id == keyId } ?: return null
        return (key.tapAction as? KeyAction.Text)?.text
    }

    @Test
    fun `the Polish layout carries the programmer's layout everyone already knows`() {
        // Exactly the nine of a Polish PC. ź sits on x because z is taken by ż — the
        // one placement that is not derivable from the letter, and the one somebody
        // reimplementing this from memory would get wrong.
        mapOf(
            "a" to "ą", "c" to "ć", "e" to "ę", "l" to "ł", "n" to "ń",
            "o" to "ó", "s" to "ś", "x" to "ź", "z" to "ż"
        ).forEach { (key, expected) ->
            assertEquals("AltGr over '$key'", expected, typed(LayoutDef.ALTGR_LAYER, key))
        }
    }

    @Test
    fun `shift and AltGr gives the capital`() {
        assertEquals("Ó", typed(LayoutDef.ALTGR_SHIFT_LAYER, "o"))
        assertEquals("Ź", typed(LayoutDef.ALTGR_SHIFT_LAYER, "x"))
    }

    @Test
    fun `every other key keeps doing what it did`() {
        // The level replaces nine letters and leaves the board alone, so it still
        // reads as the same keyboard. A layer that blanked everything AltGr says
        // nothing about would be unusable for the space bar alone.
        listOf("q", "w", "r", "t", "y", "u", "i", "p", "d", "f", "g").forEach { key ->
            assertEquals(key, typed(LayoutDef.ALTGR_LAYER, key))
        }
    }

    @Test
    fun `a layout with nothing to put there has no such layer`() {
        // Not an empty one. renderLayer would switch to a board identical to the base,
        // which looks like the key is broken rather than like it does nothing.
        assertNull(en.layer(LayoutDef.ALTGR_LAYER))
        assertNull(en.layer(LayoutDef.ALTGR_SHIFT_LAYER))
    }

    @Test
    fun `there is a way in from the soft keyboard`() {
        // The level is worthless if nothing can reach it without a hardware keyboard.
        val symbols = pl.layer(LayoutDef.BASE_LAYER)!!.rows
            .flatMap { it.keys }
            .firstOrNull { it.id.startsWith("to_") && it.label == "?123" }
        assertTrue("the Polish board has no ?123 key to hang AltGr on", symbols != null)
        val altGr = symbols!!.bindings.map { it.action }
            .filterIsInstance<KeyAction.Modifier>()
            .firstOrNull { it.kind == ModifierKind.ALT_GR }
        assertTrue("no way to reach AltGr from the soft board", altGr != null)
        // One-shot, so it reads as it does on a PC: AltGr, then the letter.
        assertEquals(ModifierMode.ONE_SHOT, altGr!!.mode)
        assertEquals("AltGr", symbols.hint)
    }

    @Test
    fun `the key that already switches layout keeps doing that`() {
        // Fixing one complaint by quietly causing another is not fixing it. This key's
        // long press was already taken, which is why AltGr went on a swipe.
        val symbols = pl.layer(LayoutDef.BASE_LAYER)!!.rows
            .flatMap { it.keys }
            .first { it.label == "?123" }
        val longPress = symbols.bindings
            .firstOrNull { it.trigger == com.example.core.layout.KeyTrigger.LongPress }
        assertTrue("the long press that switches layout was taken away", longPress != null)
        assertTrue(longPress!!.action is KeyAction.SwitchLayout)
    }

    // -----------------------------------------------------------------------
    // Choosing the layer
    // -----------------------------------------------------------------------

    private fun state() = KeyboardState { Settings() }

    @Test
    fun `holding AltGr shows the AltGr level`() {
        val s = state()
        assertEquals(LayoutDef.BASE_LAYER, s.renderLayer(pl))
        s.setModifier(ModifierKind.ALT_GR, active = true)
        assertEquals(LayoutDef.ALTGR_LAYER, s.renderLayer(pl))
    }

    @Test
    fun `AltGr and shift together ask for the capitals`() {
        val s = state()
        s.setModifier(ModifierKind.ALT_GR, active = true)
        s.setModifier(ModifierKind.SHIFT, active = true)
        assertEquals(LayoutDef.ALTGR_SHIFT_LAYER, s.renderLayer(pl))
    }

    @Test
    fun `AltGr on a layout without one falls back rather than blanking`() {
        val s = state()
        s.setModifier(ModifierKind.ALT_GR, active = true)
        assertEquals(LayoutDef.BASE_LAYER, s.renderLayer(en))
        s.setModifier(ModifierKind.SHIFT, active = true)
        assertEquals(LayoutDef.SHIFT_LAYER, s.renderLayer(en))
    }

    @Test
    fun `AltGr does not turn letters into key events`() {
        // Ctrl and Alt do, so that Ctrl+C reaches the app. AltGr must not: it types a
        // character, and sending it as a key event would deliver a bare 'o'.
        val s = state()
        s.setModifier(ModifierKind.ALT_GR, active = true)
        assertFalse(s.wantsRawKeyEvents)
    }
}
