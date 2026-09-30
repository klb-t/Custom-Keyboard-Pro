package com.example.core

import com.example.core.layout.*
import org.junit.Assert.*
import org.junit.Test

class ScienceLayoutBehaviorTest {
    @Test fun `quick hold release offers standard shifted punctuation first without losing mathematics`() {
        val expected = mapOf("slash" to "?", "equals" to "+", "minus" to "_", "semicolon" to ":",
            "quote" to "\"", "backslash" to "|", "comma" to "<", "period" to ">", "lbracket" to "{", "rbracket" to "}")
        expected.forEach { (id, text) ->
            val base = ScienceLayout.SCIENCE.base.allKeys.single { it.id == id }
            val shifted = ScienceLayout.SCIENCE.layers.getValue(LayoutDef.SHIFT_LAYER).allKeys.single { it.id == id }
            assertEquals("Quick hold of $id", text, base.popup.first())
            assertEquals("Shift of $id", KeyAction.Text(text), shifted.tapAction)
        }
        val minus = ScienceLayout.SCIENCE.base.allKeys.single { it.id == "minus" }
        assertTrue(minus.popupGroups.flatMap { it.items }.containsAll(listOf("∫", "∇", "⊗", "≈")))
        val digit = ScienceLayout.SCIENCE.base.allKeys.single { it.id == "d_1" }
        assertEquals("!", digit.popup.first())
        assertTrue(digit.popup.containsAll(listOf("¹", "₁", "½", "⅛")))
    }

    @Test fun `local capitalization can decline automatic Shift while preserving manual Shift and authored capitals`() {
        fun letter(text: String) = KeyDef("letter", label = text, bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text(text))))
        val base = letter("a"); val shifted = letter("A")
        assertEquals(KeyAction.Text("a"), AutomaticShift.present(base, shifted, true, false).tapAction)
        assertEquals(KeyAction.Text("A"), AutomaticShift.present(base, shifted, false, false).tapAction)
        assertEquals(KeyAction.Text("A"), AutomaticShift.present(base, shifted, true, true).tapAction)
        assertEquals(KeyAction.Text("API"), AutomaticShift.present(letter("API"), letter("API"), true, false).tapAction)
    }
}
