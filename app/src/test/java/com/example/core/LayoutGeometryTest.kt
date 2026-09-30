package com.example.core

import com.example.core.layout.*
import org.junit.Assert.*
import org.junit.Test

class LayoutGeometryTest {
    private fun key(id: String, width: Float = 1f) = KeyDef(id, label = id, widthWeight = width,
        bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text(id)), Binding(KeyTrigger.LongPress, KeyAction.SendKey(61))),
        popup = listOf("$id-extra"), touchWeight = 1.4f, style = "accent")
    private val flow = LayerDef("exact-layer", rows = listOf(
        RowDef(listOf(key("a"), key("b", 2f), key("c")), padStart = 0.25f, heightWeight = 2f),
        RowDef(listOf(key("d"), key("e")), heightWeight = 1f)
    ))

    @Test fun `row drag crosses rows without changing key actions or layer identity`() {
        val result = LayoutGeometry.dropInRows(flow, "b", 0.9f, 0.9f)
        assertEquals("exact-layer", result.name)
        assertEquals(listOf("a", "c"), result.rows[0].keys.map { it.id })
        assertEquals(listOf("d", "e", "b"), result.rows[1].keys.map { it.id })
        assertEquals(flow.allKeys.single { it.id == "b" }, result.allKeys.single { it.id == "b" })
        assertEquals(flow.allKeys.toSet(), result.allKeys.toSet())
    }

    @Test fun `row drag within same row excludes its old slot`() {
        val result = LayoutGeometry.dropInRows(flow, "a", 0.99f, 0.2f)
        assertEquals(listOf("b", "c", "a"), result.rows[0].keys.map { it.id })
    }

    @Test fun `free conversion freezes weighted staggered geometry and preserves all bindings`() {
        val before = LayoutGeometry.rectangles(flow)
        val result = LayoutGeometry.toFree(flow)
        assertTrue(result.rows.isEmpty())
        result.freeKeys.forEach { changed ->
            assertEquals(before.getValue(changed.id), changed.bounds)
            assertEquals(flow.allKeys.single { it.id == changed.id }, changed.copy(bounds = null))
        }
    }

    @Test fun `group movement clamps at edges without compressing spacing or changing size`() {
        val free = LayerDef("free", freeKeys = listOf(
            key("a").copy(bounds = NormRect(0.1f, 0.1f, 0.2f, 0.2f)),
            key("b").copy(bounds = NormRect(0.4f, 0.3f, 0.6f, 0.5f)),
            key("c").copy(bounds = NormRect(0.7f, 0.7f, 0.8f, 0.8f))
        ))
        val moved = LayoutGeometry.moveFree(free, setOf("a", "b"), 2f, -2f)
        val a = moved.freeKeys[0].bounds!!; val b = moved.freeKeys[1].bounds!!
        assertEquals(1f, b.right, 0.00001f)
        assertEquals(0f, a.top, 0.00001f)
        assertEquals(0.3f, b.left - a.left, 0.00001f)
        assertEquals(0.2f, b.top - a.top, 0.00001f)
        assertEquals(free.freeKeys[2], moved.freeKeys[2])
        assertEquals(free.freeKeys[0].bindings, moved.freeKeys[0].bindings)
    }

    @Test fun `alignment preserves size and distribution uses equal clear gaps`() {
        val free = LayerDef("free", freeKeys = listOf(
            key("a").copy(bounds = NormRect(0.1f, 0.1f, 0.2f, 0.2f)),
            key("b").copy(bounds = NormRect(0.3f, 0.3f, 0.5f, 0.5f)),
            key("c").copy(bounds = NormRect(0.8f, 0.6f, 0.9f, 0.7f))
        ))
        val aligned = LayoutGeometry.align(free, setOf("a", "b", "c"), KeyAlignment.TOP)
        assertTrue(aligned.freeKeys.all { kotlin.math.abs(it.bounds!!.top - 0.1f) < 0.00001f })
        val spaced = LayoutGeometry.space(aligned, setOf("a", "b", "c"), KeySpacing.HORIZONTAL)
        val (a, b, c) = spaced.freeKeys.map { it.bounds!! }
        assertEquals(b.left - a.right, c.left - b.right, 0.00001f)
        assertEquals(0.2f, b.width, 0.00001f)
        assertEquals(0.1f, a.left, 0.00001f)
        assertEquals(0.9f, c.right, 0.00001f)
    }

    @Test fun `ambiguous ids and impossible spacing are refused without changing data`() {
        assertTrue(runCatching { LayoutGeometry.rectangles(flow.copy(freeKeys = listOf(key("a")))) }.isFailure)
        val crowded = LayerDef("free", freeKeys = listOf(
            key("a").copy(bounds = NormRect(0f, 0f, 0.5f, 0.2f)),
            key("b").copy(bounds = NormRect(0.1f, 0f, 0.6f, 0.2f)),
            key("c").copy(bounds = NormRect(0.2f, 0f, 0.7f, 0.2f))
        ))
        assertTrue(runCatching { LayoutGeometry.space(crowded, setOf("a", "b", "c"), KeySpacing.HORIZONTAL) }.isFailure)
    }
}
