package com.example.core

import com.example.core.layout.BuiltinLayouts
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.LanguageKeys
import com.example.core.layout.LayerTransforms
import com.example.core.layout.LayoutDef
import com.example.core.layout.ScienceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A language applied to whatever board is on screen.
 *
 * The case that forced this: the scientific layout orders its alternates for a
 * physicist, so holding "e" offers `é` first and `ę` sixth. Hold-and-release gives the
 * first one — which means a Polish speaker on that board cannot type their own
 * language without looking. The wrong fix is a Polish copy of the scientific layout,
 * and then of every other layout. Which letters belong to a language is a fact about
 * the language, so it is applied *to* a board rather than baked into one.
 */
class LanguageKeysTest {

    private fun base(id: String) = when (id) {
        "science" -> ScienceLayout.SCIENCE.layer(LayoutDef.BASE_LAYER)!!
        else -> BuiltinLayouts.QWERTY_PL.layer(LayoutDef.BASE_LAYER)!!
    }

    private fun keyTyping(layer: com.example.core.layout.LayerDef, letter: String): KeyDef? =
        layer.rows.flatMap { it.keys }.firstOrNull { LanguageKeys.typedBy(it) == letter }

    // -----------------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------------

    @Test
    fun `the language's own letter is the one a hold-and-release gives`() {
        val localised = LayerTransforms.localised(base("science"), "pl")
        mapOf("e" to "ę", "a" to "ą", "o" to "ó", "s" to "ś", "c" to "ć", "n" to "ń", "l" to "ł")
            .forEach { (letter, expected) ->
                val key = keyTyping(localised, letter)
                assertNotNull("the scientific board has no '$letter'", key)
                assertEquals(
                    "holding '$letter' should offer '$expected' first",
                    expected, key!!.popup.firstOrNull()
                )
            }
    }

    @Test
    fun `z keeps ż and x gains ź, as on a Polish keyboard`() {
        // The one placement nobody derives from the letter: ź lives on x because z is
        // taken by ż. No scientific or international board has a reason to put a Polish
        // letter on x, so the language has to add it.
        val localised = LayerTransforms.localised(base("science"), "pl")
        assertEquals("ż", keyTyping(localised, "z")?.popup?.firstOrNull())
        assertEquals("ź", keyTyping(localised, "x")?.popup?.firstOrNull())
    }

    @Test
    fun `nothing is taken away, only reordered`() {
        // The physicist's Greek and operators are still there, one slide further along.
        val key = keyTyping(base("science"), "e")!!
        val before = (key.popup.ifEmpty { key.popupGroups.first().items }).toSet()
        val after = keyTyping(LayerTransforms.localised(base("science"), "pl"), "e")!!.popup.toSet()
        assertTrue("localising dropped alternates: ${before - after}", before.all { it in after })
    }

    @Test
    fun `a key whose accents are behind a board gets a strip for them`() {
        // The case that forced the whole thing. On the scientific layout "e" keeps its
        // accents in the first tab of a tabbed board, so the quick strip is empty and a
        // long press opens the board — "ę" costs a board, a tab and a tap. Promoting
        // the language's own letters into the strip makes it one hold, and the board is
        // still there for anyone who keeps holding.
        val plain = keyTyping(base("science"), "e")!!
        assertTrue("this test is about a key with no strip of its own", plain.popup.isEmpty())
        assertTrue(plain.popupGroups.isNotEmpty())

        val localised = keyTyping(LayerTransforms.localised(base("science"), "pl"), "e")!!
        assertEquals("ę", localised.popup.firstOrNull())
        assertTrue("the board was taken away", localised.popupGroups.isNotEmpty())
    }

    @Test
    fun `a board key with nothing of this language keeps having no strip`() {
        // Otherwise every tabbed key on the scientific board would sprout a strip and
        // the board would stop opening on a plain long press.
        val localised = LayerTransforms.localised(base("science"), "pl")
        val d = keyTyping(localised, "d")
        assertNotNull(d)
        assertTrue(
            "'d' carries no Polish letter and should still open its board",
            d!!.popup.isEmpty()
        )
    }

    @Test
    fun `a language with nothing to say leaves the board alone`() {
        val untouched = LayerTransforms.localised(base("science"), "ja")
        assertEquals(keyTyping(base("science"), "e")?.popup, keyTyping(untouched, "e")?.popup)
        assertEquals(base("science"), LayerTransforms.localised(base("science"), null))
        assertEquals(base("science"), LayerTransforms.localised(base("science"), ""))
    }

    @Test
    fun `it works on the plain board too, not only the scientific one`() {
        // The point of being a transform: every layout gets it, including ones the app
        // never shipped.
        val localised = LayerTransforms.localised(base("qwerty"), "pl")
        assertEquals("ą", keyTyping(localised, "a")?.popup?.firstOrNull())
        assertEquals("ź", keyTyping(localised, "x")?.popup?.firstOrNull())
    }

    // -----------------------------------------------------------------------
    // AltGr
    // -----------------------------------------------------------------------

    @Test
    fun `AltGr types the programmer's layout on any board`() {
        val expected = mapOf(
            "a" to "ą", "c" to "ć", "e" to "ę", "l" to "ł", "n" to "ń",
            "o" to "ó", "s" to "ś", "x" to "ź", "z" to "ż"
        )
        listOf("science", "qwerty").forEach { id ->
            val level = LayerTransforms.altGr(base(id), LanguageKeys.altGrFor("pl"))
            expected.forEach { (letter, polish) ->
                val key = level.rows.flatMap { it.keys }.firstOrNull { it.id == keyTyping(base(id), letter)?.id }
                assertEquals(
                    "AltGr over '$letter' on '$id'",
                    polish, (key?.tapAction as? KeyAction.Text)?.text
                )
            }
        }
    }

    @Test
    fun `AltGr and shift gives the capital`() {
        val level = LayerTransforms.altGr(base("science"), LanguageKeys.altGrFor("pl"), upper = true)
        val o = level.rows.flatMap { it.keys }.firstOrNull { it.id == keyTyping(base("science"), "o")?.id }
        assertEquals("Ó", (o?.tapAction as? KeyAction.Text)?.text)
    }

    @Test
    fun `keys AltGr says nothing about are untouched`() {
        // Otherwise the board goes blank except for nine letters, which is unusable
        // for the space bar alone.
        val level = LayerTransforms.altGr(base("science"), LanguageKeys.altGrFor("pl"))
        listOf("q", "w", "r", "t", "y", "u", "i", "p", "d", "f", "g").forEach { letter ->
            val key = level.rows.flatMap { it.keys }.firstOrNull { it.id == keyTyping(base("science"), letter)?.id }
            assertEquals(letter, (key?.tapAction as? KeyAction.Text)?.text)
        }
    }

    @Test
    fun `a language without an AltGr level gets no transform at all`() {
        assertFalse(LanguageKeys.hasAltGr("ja"))
        assertTrue(LanguageKeys.altGrFor("ja").isEmpty())
        assertEquals(base("science"), LayerTransforms.altGr(base("science"), emptyMap()))
    }

    @Test
    fun `a locale is read down to its language`() {
        assertEquals("pl", LanguageKeys.tag("pl-PL"))
        assertEquals("pl", LanguageKeys.tag("pl_PL"))
        assertEquals("pl", LanguageKeys.tag("PL"))
        assertEquals("", LanguageKeys.tag(null))
        assertEquals("", LanguageKeys.tag("x"))
    }

    @Test
    fun `only single letters are matched, not whole words`() {
        // A key that types "ok" or a symbol must never be mistaken for a letter key.
        assertEquals(null, LanguageKeys.typedBy(KeyDef(id = "x", bindings = listOf(
            com.example.core.layout.Binding(com.example.core.layout.KeyTrigger.Tap, KeyAction.Text("ok"))
        ))))
        assertEquals(null, LanguageKeys.typedBy(KeyDef(id = "y", bindings = listOf(
            com.example.core.layout.Binding(com.example.core.layout.KeyTrigger.Tap, KeyAction.Text("7"))
        ))))
        assertEquals("o", LanguageKeys.typedBy(KeyDef(id = "z", bindings = listOf(
            com.example.core.layout.Binding(com.example.core.layout.KeyTrigger.Tap, KeyAction.Text("O"))
        ))))
    }
}
