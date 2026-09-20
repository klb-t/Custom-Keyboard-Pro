package com.example.core

import com.example.core.layout.KeyDef
import com.example.core.layout.PlacedKey
import com.example.ui.kb.PopupState
import com.example.ui.kb.popupIndexAt
import com.example.ui.kb.popupOriginX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which alternate a held finger is actually over.
 *
 * Worth a test of its own because the failure was silent and looked like bad luck:
 * hold "o" on a Polish board, let go, and out comes "õ" instead of "ó" — with nothing
 * the user could do differently. The strip was drawn centred on the key while the
 * selection started at item 0, so the two disagreed, and a finger that held perfectly
 * still got the first item while a finger that twitched got whatever was halfway
 * along. Pure arithmetic, so it can be pinned down exactly.
 */
class PopupGeometryTest {

    private val alternates = listOf("ó", "ö", "ô", "ò", "õ", "ø", "ō", "œ")

    /** A key 100 wide whose centre is at [centerX], on a surface [surfaceWidth] across. */
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
    fun `the first alternate is the one under the finger`() {
        // The whole point. The first alternate is first because it is the one people
        // want — on a Polish layout it is the Polish letter — so it belongs where the
        // finger already is, and holding still must produce it.
        val p = popup(centerX = 400f)
        assertEquals(0, popupIndexAt(p, 400f, surface, cell))
    }

    @Test
    fun `a finger that has not moved stays on the first one`() {
        // A real finger jitters, which produced a move event, which recomputed the
        // index from position. With the strip centred that landed halfway along the
        // list; with it anchored on the first item, a few pixels change nothing.
        val p = popup(centerX = 400f)
        listOf(-40f, -12f, -1f, 0f, 1f, 12f, 40f).forEach { wobble ->
            assertEquals(
                "a wobble of $wobble should not change the selection",
                0, popupIndexAt(p, 400f + wobble, surface, cell)
            )
        }
    }

    @Test
    fun `sliding right walks the list one at a time`() {
        val p = popup(centerX = 400f)
        assertEquals(1, popupIndexAt(p, 500f, surface, cell))
        assertEquals(2, popupIndexAt(p, 600f, surface, cell))
        assertEquals(7, popupIndexAt(p, 1100f, surface, cell))
    }

    @Test
    fun `sliding left of the strip stays on the first item`() {
        // Not the last one. A truncating divide turns -0.5 into 0, but -1.5 into -1,
        // so without flooring and clamping a finger dragged left of the strip jumped
        // to the far end of the list.
        val p = popup(centerX = 400f)
        assertEquals(0, popupIndexAt(p, 300f, surface, cell))
        assertEquals(0, popupIndexAt(p, 0f, surface, cell))
        assertEquals(0, popupIndexAt(p, -500f, surface, cell))
    }

    @Test
    fun `a strip that would run off the right edge is pulled back on screen`() {
        val p = popup(centerX = 1040f)
        val origin = popupOriginX(p, surface, cell)
        assertEquals(surface - cell * alternates.size, origin, 0.01f)
        // And the index is then read back through the same geometry rather than
        // assumed to be zero, so the selection still matches what is drawn.
        assertEquals(7, popupIndexAt(p, 1040f, surface, cell))
    }

    @Test
    fun `a strip narrower than the surface is not pushed off the left edge`() {
        val p = popup(centerX = 20f)
        assertEquals(0f, popupOriginX(p, surface, cell), 0.01f)
        assertEquals(0, popupIndexAt(p, 20f, surface, cell))
    }

    @Test
    fun `a single alternate is always the answer`() {
        val p = popup(centerX = 400f, items = listOf("ł"))
        listOf(-100f, 400f, 2000f).forEach {
            assertEquals(0, popupIndexAt(p, it, surface, cell))
        }
    }
}
