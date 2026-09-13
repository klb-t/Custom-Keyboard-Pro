package com.example.core

import com.example.core.layout.BuiltinLayouts
import com.example.core.layout.ElementPlacement
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutJson
import com.example.core.layout.ScienceLayout
import com.example.core.layout.WorkbenchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules a layout has to obey for the keyboard to be able to draw it at all.
 *
 * These are checks no compiler can make: a layout is data, and data can name a layer
 * that does not exist, or reuse a key id, without anything complaining until the
 * screen comes up wrong on someone's phone. Since the only device this code is ever
 * tested on is the user's, every invariant that can be caught here instead of there
 * is worth the lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutInvariantsTest {

    private fun eachLayout(check: (LayoutDef) -> Unit) = BuiltinLayouts.ALL.forEach(check)

    @Test
    fun `every element names a layer the layout actually has`() = eachLayout { layout ->
        layout.elements.forEach { element ->
            assertNotNull(
                "layout '${layout.id}': element '${element.id}' points at missing layer " +
                    "'${element.layer}' (has: ${layout.layers.keys})",
                layout.layers[element.layer]
            )
        }
    }

    @Test
    fun `element ids are unique within a layout`() = eachLayout { layout ->
        val ids = layout.elements.map { it.id }
        assertEquals("layout '${layout.id}': duplicate element ids", ids.size, ids.distinct().size)
    }

    @Test
    fun `the default layer exists`() = eachLayout { layout ->
        assertNotNull(
            "layout '${layout.id}': defaultLayer '${layout.defaultLayer}' is not one of ${layout.layers.keys}",
            layout.layers[layout.defaultLayer]
        )
    }

    @Test
    fun `key ids are unique within a layer`() = eachLayout { layout ->
        // Across layers an id is deliberately reused — the shift layer's Enter is the
        // same key as the base layer's. Within one layer it may not be: key ids
        // address popup-tab memory, learned touch offsets and the rectangles the
        // keyboard reports for cursor avoidance, and two keys sharing an id crosses
        // all three.
        layout.layers.forEach { (name, layer) ->
            val ids = layer.rows.flatMap { row -> row.keys.map { it.id } } +
                layer.freeKeys.map { it.id }
            val repeated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue(
                "layout '${layout.id}', layer '$name': key ids used more than once: $repeated",
                repeated.isEmpty()
            )
        }
    }

    @Test
    fun `a layer that any key switches to exists`() = eachLayout { layout ->
        val targets = layout.layers.values
            .flatMap { it.rows.flatMap { row -> row.keys } + it.freeKeys }
            .flatMap { key -> key.bindings.map { it.action } }
            .filterIsInstance<com.example.core.layout.KeyAction.Layer>()
            .map { it.layer }
            .distinct()
        targets.forEach { target ->
            assertNotNull(
                "layout '${layout.id}': a key switches to layer '$target', which does not exist",
                layout.layers[target]
            )
        }
    }

    @Test
    fun `non-docked elements are given bounds, because nothing else can place them`() =
        eachLayout { layout ->
            layout.elements
                .filter { it.placement != ElementPlacement.DOCKED }
                .forEach { element ->
                    val bounds = element.bounds
                    assertNotNull(
                        "layout '${layout.id}': ${element.placement} element '${element.id}' has no bounds",
                        bounds
                    )
                    bounds!!
                    assertTrue(
                        "layout '${layout.id}': element '${element.id}' has non-positive size",
                        bounds.width > 0f && bounds.height > 0f
                    )
                    assertTrue(
                        "layout '${layout.id}': element '${element.id}' is entirely off screen",
                        bounds.right > 0f && bounds.bottom > 0f &&
                            bounds.left < 1f && bounds.top < 1f
                    )
                }
        }

    @Test
    fun `popup groups are non-empty and uniquely tabbed`() = eachLayout { layout ->
        layout.layers.values
            .flatMap { it.rows.flatMap { row -> row.keys } + it.freeKeys }
            .forEach { key ->
                val ids = key.popupGroups.map { it.id }
                assertEquals(
                    "layout '${layout.id}', key '${key.id}': two popup tabs share an id",
                    ids.size, ids.distinct().size
                )
                key.popupGroups.forEach { group ->
                    // An empty tab is a tab that can be selected and then shows
                    // nothing, which reads as a broken keyboard rather than an
                    // empty category.
                    assertFalse(
                        "layout '${layout.id}', key '${key.id}': popup tab '${group.id}' is empty",
                        group.items.isEmpty()
                    )
                    assertFalse(
                        "layout '${layout.id}', key '${key.id}': popup tab '${group.id}' has a blank label",
                        group.label.isBlank()
                    )
                }
            }
    }

    @Test
    fun `elements survive the codec, or the workbench layout cannot be exported`() {
        val reparsed = LayoutJson.parse(LayoutJson.writeString(WorkbenchLayout.WORKBENCH))
        assertEquals(WorkbenchLayout.WORKBENCH.elements, reparsed.elements)
    }

    @Test
    fun `popup groups survive the codec`() {
        val original = ScienceLayout.SCIENCE
        val reparsed = LayoutJson.parse(LayoutJson.writeString(original))
        val before = original.layers.values.flatMap { it.rows.flatMap { r -> r.keys } }
            .associate { it.id to it.popupGroups }
        val after = reparsed.layers.values.flatMap { it.rows.flatMap { r -> r.keys } }
            .associate { it.id to it.popupGroups }
        before.forEach { (id, groups) ->
            assertEquals("popup groups of key '$id' changed across a round trip", groups, after[id])
        }
    }

    @Test
    fun `the science layout carries the symbols it promises, where it promises them`() {
        val layout = ScienceLayout.SCIENCE
        fun itemsOn(keyId: String): List<String> =
            layout.key(keyId)?.let { key -> key.popupGroups.flatMap { it.items } + key.popup }
                ?: error("science layout has no key '$keyId'")

        // Placed by meaning, not by category: nabla is an upside-down delta, so it
        // lives on d next to the partial derivative.
        assertTrue("∇ should be reachable from d", "∇" in itemsOn("k_d"))
        assertTrue("∂ should be reachable from d", "∂" in itemsOn("k_d"))
        assertTrue("∫ should be reachable from i", "∫" in itemsOn("k_i"))
        assertTrue("∑ should be reachable from s", "∑" in itemsOn("k_s"))
        assertTrue("∏ should be reachable from p", "∏" in itemsOn("k_p"))
        assertTrue("√ should be reachable from r", "√" in itemsOn("k_r"))
    }

    @Test
    fun `the editing keys a science layout exists for are all present`() {
        // The point of the layout, in the user's words: everything needed for editing,
        // on the board rather than behind a mode switch.
        listOf(
            "esc", "tab", "ctrl", "alt", "altgr", "home", "end",
            "left", "right", "up", "down", "shift_left", "shift_right"
        ).forEach {
            assertNotNull("science layout is missing the '$it' key", ScienceLayout.SCIENCE.key(it))
        }
    }
}
