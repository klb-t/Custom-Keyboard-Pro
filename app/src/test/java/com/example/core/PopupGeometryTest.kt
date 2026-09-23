package com.example.core

import com.example.core.layout.KeyDef
import com.example.core.layout.PlacedKey
import com.example.ui.kb.PopupState
import com.example.ui.kb.popupGrowsLeft
import com.example.ui.kb.popupIndexAt
import com.example.ui.kb.popupOriginX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which alternate a held finger is actually on.
 *
 * Worth a test of its own because the failure was silent and looked like bad luck:
 * hold "o" on a Polish board, let go, and out comes something that is not "ó" — with
 * nothing the user could do differently.
 *
 * The first attempt at fixing it anchored the strip's first item under the finger and
 * read the index off that position. These tests rejected it, and were right to: a
 * strip of eight alternates is wider than the space to the right of a key near the
 * edge of the board, so it gets clamped back on screen and the finger that opened it
 * ends up over the seventh item. Selection is now measured as displacement from where
 * the strip opened, which is a fact about the gesture rather than about the screen.
 */
class PopupGeometryTest {

    private val alternates = listOf("ó", "ö", "ô", "ò", "õ", "ø", "ō", "œ")

    private fun popup(centerX: Float, items: List<String> = alternates) = PopupState(
        anchor = PlacedKey(
            key = KeyDef(id = "o"),
            left = centerX - 50f, top = 0f, right = centerX + 50f, bottom = 100f
        ),
        items = items,
        selected = 0
    )

    private val cell = 100f
    private val surface = 1080f

    @Test
    fun `holding still gives the first alternate wherever the key is`() {
        // Including the corners, which is the case that broke. A Polish "o" is the
        // ninth key of ten: eight alternates do not fit to the right of it, the strip
        // is pushed back on screen, and reading the index off its drawn position hands
        // back whatever now sits under the finger. Displacement does not care.
        listOf(60f, 400f, 540f, 900f, 1020f).forEach { centerX ->
            val p = popup(centerX)
            assertEquals(
                "a key centred at $centerX should still commit its first alternate",
                0, popupIndexAt(p, centerX, centerX, cell)
            )
        }
    }

    @Test
    fun `a finger that has not really moved stays on the first one`() {
        // A real finger jitters, and a jitter produced a move event. Anything short of
        // a whole key's width is not a slide.
        val p = popup(centerX = 900f)
        listOf(-99f, -40f, -1f, 0f, 1f, 40f, 99f).forEach { wobble ->
            assertEquals(
                "a wobble of $wobble should not change the selection",
                0, popupIndexAt(p, 900f + wobble, 900f, cell)
            )
        }
    }

    @Test
    fun `sliding right walks the list one key at a time`() {
        val p = popup(centerX = 400f)
        assertEquals(1, popupIndexAt(p, 500f, 400f, cell))
        assertEquals(2, popupIndexAt(p, 600f, 400f, cell))
        assertEquals(7, popupIndexAt(p, 1100f, 400f, cell))
    }

    @Test
    fun `sliding past the end stays on the last one`() {
        val p = popup(centerX = 400f)
        assertEquals(7, popupIndexAt(p, 4000f, 400f, cell))
    }

    @Test
    fun `sliding back left returns to the first rather than wrapping`() {
        // A truncating divide turns -0.5 into 0 but -1.5 into -1, so without flooring
        // and clamping a finger dragged back past the start jumped to the far end.
        val p = popup(centerX = 400f)
        assertEquals(0, popupIndexAt(p, 300f, 400f, cell))
        assertEquals(0, popupIndexAt(p, 0f, 400f, cell))
        assertEquals(0, popupIndexAt(p, -500f, 400f, cell))
    }

    @Test
    fun `a single alternate is always the answer`() {
        val p = popup(centerX = 400f, items = listOf("ł"))
        listOf(-100f, 400f, 2000f).forEach {
            assertEquals(0, popupIndexAt(p, it, 400f, cell))
        }
    }

    @Test
    fun `a zero-width key does not divide by zero`() {
        val p = popup(centerX = 400f)
        assertEquals(0, popupIndexAt(p, 400f, 400f, 0f))
    }

    // -----------------------------------------------------------------------
    // Drawing, which is a separate question and is allowed to be clamped
    // -----------------------------------------------------------------------

    @Test
    fun `near the right edge the strip grows the other way instead of being pushed back`() {
        // The bug this replaced: pushing the strip back on screen moved every item out
        // from under the finger, so the keyboard highlighted one letter and typed
        // another. Growing leftwards keeps the first item on the key that opened it.
        val atEdge = popup(centerX = 1040f)
        assertTrue(popupGrowsLeft(atEdge, surface, cell))
        // The first item still sits on the key; the rest run away to the left.
        assertEquals(
            1040f - cell / 2f - cell * (alternates.size - 1),
            popupOriginX(atEdge, surface, cell), 0.01f
        )
    }

    @Test
    fun `with room to the right it grows right, as normal`() {
        val roomy = popup(centerX = 60f)
        assertFalse(popupGrowsLeft(roomy, surface, cell))
        assertEquals(60f - cell / 2f, popupOriginX(roomy, surface, cell), 0.01f)
    }

    @Test
    fun `a strip that grew leftwards is walked leftwards`() {
        // The gesture mirrors with the drawing, so what is highlighted is what is
        // committed at either edge of the board.
        val atEdge = popup(centerX = 1040f)
        assertTrue(popupGrowsLeft(atEdge, surface, cell))
        assertEquals(0, popupIndexAt(atEdge, 1040f, 1040f, cell, growsLeft = true))
        assertEquals(1, popupIndexAt(atEdge, 940f, 1040f, cell, growsLeft = true))
        assertEquals(7, popupIndexAt(atEdge, 300f, 1040f, cell, growsLeft = true))
        // Sliding the wrong way stays on the first, rather than wrapping.
        assertEquals(0, popupIndexAt(atEdge, 1070f, 1040f, cell, growsLeft = true))
    }

    @Test
    fun `a strip wider than the screen still starts on screen`() {
        // Sixty symbols on a narrow phone. The origin must not go negative and push the
        // first items off the left edge where nothing can reach them.
        val many = popup(centerX = 540f, items = (1..60).map { it.toString() })
        assertTrue(popupOriginX(many, surface, cell) >= 0f)
    }
}
