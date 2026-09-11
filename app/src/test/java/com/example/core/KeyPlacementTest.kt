package com.example.core

import com.example.core.layout.KeyDef
import com.example.core.layout.KeyPlacement
import com.example.core.layout.LayerDef
import com.example.core.layout.LayerTransforms
import com.example.core.layout.NormRect
import com.example.core.layout.RowDef
import com.example.core.layout.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPlacementTest {

    private fun key(id: String, width: Float = 1f) = KeyDef(id = id, label = id, widthWeight = width)

    private val layer = LayerDef(
        name = "base",
        rows = listOf(
            RowDef(listOf(key("a"), key("b"), key("c"), key("d"))),
            RowDef(listOf(key("shift", 2f), key("space", 4f), key("enter", 2f)))
        )
    )

    @Test
    fun `rows split the height and keys split the width by weight`() {
        val placed = KeyPlacement.place(layer, width = 800f, height = 400f)
        assertEquals(7, placed.size)

        val a = placed.first { it.key.id == "a" }
        assertEquals(0f, a.left, 0.01f)
        assertEquals(200f, a.right, 0.01f)
        assertEquals(0f, a.top, 0.01f)
        assertEquals(200f, a.bottom, 0.01f)

        // Second row: 2 + 4 + 2 = 8 units across 800px, so the space bar is 400px wide.
        val space = placed.first { it.key.id == "space" }
        assertEquals(200f, space.left, 0.01f)
        assertEquals(600f, space.right, 0.01f)
        assertEquals(200f, space.top, 0.01f)
    }

    @Test
    fun `hit test returns the key under the finger`() {
        val placed = KeyPlacement.place(layer, 800f, 400f)
        assertEquals("a", KeyPlacement.hitTest(placed, 10f, 10f)?.key?.id)
        assertEquals("c", KeyPlacement.hitTest(placed, 450f, 100f)?.key?.id)
        assertEquals("space", KeyPlacement.hitTest(placed, 400f, 300f)?.key?.id)
    }

    @Test
    fun `a touch just outside the surface still finds the nearest key`() {
        val placed = KeyPlacement.place(layer, 800f, 400f)
        assertEquals("a", KeyPlacement.hitTest(placed, -5f, 5f)?.key?.id)
    }

    @Test
    fun `the probabilistic model still picks the key that was pressed squarely`() {
        val placed = KeyPlacement.place(layer, 800f, 400f)
        // Dead centre of "b" must resolve to "b" whatever the spread.
        assertEquals("b", KeyPlacement.probableKey(placed, 300f, 100f, sigma = 30f)?.key?.id)
        assertEquals("b", KeyPlacement.probableKey(placed, 300f, 100f, sigma = 4f)?.key?.id)
    }

    @Test
    fun `a zero touch weight key is never chosen by the probabilistic model`() {
        val withSpacer = LayerDef(
            "base",
            rows = listOf(RowDef(listOf(key("a"), key("gap").copy(touchWeight = 0f), key("b"))))
        )
        val placed = KeyPlacement.place(withSpacer, 300f, 100f)
        val hit = KeyPlacement.probableKey(placed, 150f, 50f, sigma = 40f)
        assertTrue(hit?.key?.id != "gap")
    }

    @Test
    fun `absolutely placed keys land where their bounds say`() {
        val free = LayerDef(
            "base",
            rows = emptyList(),
            freeKeys = listOf(key("blob").copy(bounds = NormRect(0.25f, 0.5f, 0.75f, 1f)))
        )
        val placed = KeyPlacement.place(free, 400f, 200f)
        assertEquals(1, placed.size)
        assertEquals(100f, placed[0].left, 0.01f)
        assertEquals(100f, placed[0].top, 0.01f)
        assertEquals(300f, placed[0].right, 0.01f)
        assertEquals(200f, placed[0].bottom, 0.01f)
    }

    @Test
    fun `split inserts a gap that hit testing then sees`() {
        val split = LayerTransforms.split(layer, gapFraction = 0.2f)
        assertEquals(5, split.rows[0].keys.size)
        assertTrue(split.rows[0].keys.any { it.id.startsWith("__split") })

        val placed = KeyPlacement.place(split, 800f, 400f)
        val spacer = placed.first { it.key.id.startsWith("__split") }
        // The gap sits between the halves, not at an edge.
        assertTrue(spacer.left > 0f && spacer.right < 800f)
    }

    @Test
    fun `uppercasing a layer rewrites labels and tap actions together`() {
        val shifted = LayerTransforms.uppercased(
            LayerDef("base", rows = listOf(RowDef(listOf(
                KeyDef(
                    id = "a", label = "a",
                    bindings = listOf(
                        com.example.core.layout.Binding(
                            com.example.core.layout.KeyTrigger.Tap,
                            com.example.core.layout.KeyAction.Text("a")
                        )
                    ),
                    popup = listOf("ą")
                )
            ))))
        )
        val key = shifted.rows[0].keys[0]
        assertEquals("A", key.label)
        assertEquals(
            com.example.core.layout.KeyAction.Text("A"),
            key.tapAction
        )
        assertEquals(listOf("Ą"), key.popup)
    }

    @Test
    fun `swipe direction is eight-way and ignores short movements`() {
        assertNull(SwipeDirection.of(2f, 2f, minDistance = 20f))
        assertEquals(SwipeDirection.UP, SwipeDirection.of(0f, -50f, 20f))
        assertEquals(SwipeDirection.DOWN, SwipeDirection.of(0f, 50f, 20f))
        assertEquals(SwipeDirection.RIGHT, SwipeDirection.of(50f, 0f, 20f))
        assertEquals(SwipeDirection.LEFT, SwipeDirection.of(-50f, 0f, 20f))
        assertEquals(SwipeDirection.UP_RIGHT, SwipeDirection.of(50f, -50f, 20f))
        assertEquals(SwipeDirection.DOWN_LEFT, SwipeDirection.of(-50f, 50f, 20f))
    }

    @Test
    fun `placement of an empty layer is empty rather than a crash`() {
        assertEquals(0, KeyPlacement.place(LayerDef("base"), 100f, 100f).size)
        assertNotNull(KeyPlacement.place(layer, 0f, 0f))
    }
}
