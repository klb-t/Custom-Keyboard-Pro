package com.example.core

import com.example.core.layout.Binding
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyTrigger
import com.example.core.layout.LayerDef
import com.example.core.layout.LayerTransforms
import com.example.core.layout.RowDef
import com.example.core.layout.SwipeDirection
import com.example.core.layout.TextUnit
import com.example.core.suggest.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionDistanceTest {

    @Test
    fun `identical words are zero apart`() {
        assertEquals(0, Correction.distance("keyboard", "keyboard", 2))
    }

    @Test
    fun `one substitution, insertion or deletion is one`() {
        assertEquals(1, Correction.distance("keyboard", "keybosrd", 2))
        assertEquals(1, Correction.distance("keyboard", "keyboardd", 2))
        assertEquals(1, Correction.distance("keyboard", "keybord", 2))
    }

    @Test
    fun `a transposition counts as one, not two`() {
        // On a keyboard "teh" is one slip. Plain Levenshtein would score this 2.
        assertEquals(1, Correction.distance("teh", "the", 2))
        assertEquals(1, Correction.distance("recieve", "receive", 2))
    }

    @Test
    fun `distance stops early rather than computing a big number`() {
        // Anything past the limit only has to be reported as past the limit.
        assertTrue(Correction.distance("abc", "wxyz", 1) > 1)
        assertTrue(Correction.distance("short", "considerably longer", 2) > 2)
    }

    @Test
    fun `empty strings behave`() {
        assertEquals(0, Correction.distance("", "", 2))
        assertEquals(2, Correction.distance("", "ab", 2))
        assertEquals(2, Correction.distance("ab", "", 2))
    }
}

class PreferenceTransformTest {

    private fun letter(id: String) = KeyDef(
        id = id,
        label = id,
        hint = "1",
        bindings = listOf(
            Binding(KeyTrigger.Tap, KeyAction.Text(id)),
            Binding(KeyTrigger.Swipe(SwipeDirection.UP), KeyAction.Text("1"))
        )
    )

    private val backspace = KeyDef(
        id = "backspace",
        icon = "backspace",
        repeatable = true,
        bindings = listOf(
            Binding(KeyTrigger.Tap, KeyAction.Backspace(TextUnit.CHARACTER)),
            Binding(KeyTrigger.Swipe(SwipeDirection.LEFT), KeyAction.Backspace(TextUnit.WORD))
        )
    )

    private val layer = LayerDef("base", rows = listOf(RowDef(listOf(letter("q"), backspace))))

    @Test
    fun `with both preferences on, nothing is touched`() {
        val result = LayerTransforms.applyPreferences(layer, allowFlick = true, allowBackspaceWordSwipe = true)
        assertEquals(layer, result)
    }

    @Test
    fun `turning flicks off removes the swipe and its hint, not the tap`() {
        val result = LayerTransforms.applyPreferences(layer, allowFlick = false, allowBackspaceWordSwipe = true)
        val q = result.rows[0].keys.first { it.id == "q" }
        assertNull(q.actionFor(KeyTrigger.Swipe(SwipeDirection.UP)))
        assertEquals(KeyAction.Text("q"), q.tapAction)
        // The hint advertised the gesture; with the gesture gone it would be a lie.
        assertNull(q.hint)
    }

    @Test
    fun `turning off backspace word-swipe leaves the character backspace alone`() {
        val result = LayerTransforms.applyPreferences(layer, allowFlick = true, allowBackspaceWordSwipe = false)
        val key = result.rows[0].keys.first { it.id == "backspace" }
        assertNull(key.actionFor(KeyTrigger.Swipe(SwipeDirection.LEFT)))
        assertEquals(KeyAction.Backspace(TextUnit.CHARACTER), key.tapAction)
        assertTrue(key.repeatable)
    }

    @Test
    fun `turning flicks off does not remove a swipe bound to something else`() {
        val spaceLike = KeyDef(
            id = "space",
            bindings = listOf(
                Binding(KeyTrigger.Tap, KeyAction.Space),
                Binding(
                    KeyTrigger.Swipe(SwipeDirection.LEFT),
                    KeyAction.MoveCursor(com.example.core.layout.CursorDirection.LEFT)
                )
            )
        )
        val result = LayerTransforms.applyPreferences(
            LayerDef("base", rows = listOf(RowDef(listOf(spaceLike)))),
            allowFlick = false,
            allowBackspaceWordSwipe = false
        )
        assertNotNull(result.rows[0].keys[0].actionFor(KeyTrigger.Swipe(SwipeDirection.LEFT)))
    }
}
