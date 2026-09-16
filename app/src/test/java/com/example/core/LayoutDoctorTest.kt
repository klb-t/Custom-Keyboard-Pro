package com.example.core

import com.example.core.layout.Binding
import com.example.core.layout.BuiltinLayouts
import com.example.core.layout.ElementDef
import com.example.core.layout.ElementPlacement
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyTrigger
import com.example.core.layout.LayerDef
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutDoctor
import com.example.core.layout.NormRect
import com.example.core.layout.PopupGroup
import com.example.core.layout.RowDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The doctor's job is to survive data written by something that does not have to
 * compile, so the tests are all "hand it something wrong and see what it says".
 */
class LayoutDoctorTest {

    private fun key(id: String, text: String = id) = KeyDef(
        id = id, label = text,
        bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text(text)))
    )

    private fun layout(
        layers: Map<String, LayerDef> = mapOf(
            "base" to LayerDef("base", listOf(RowDef(listOf(key("a"), key("b")))))
        ),
        elements: List<ElementDef> = emptyList(),
        defaultLayer: String = "base"
    ) = LayoutDef(id = "t", name = "T", layers = layers, elements = elements, defaultLayer = defaultLayer)

    @Test
    fun `a sound layout has nothing to report`() {
        assertEquals(emptyList<LayoutDoctor.Finding>(), LayoutDoctor.check(layout()))
    }

    @Test
    fun `every built-in layout is healthy`() {
        BuiltinLayouts.ALL.forEach { built ->
            assertEquals(
                "built-in layout '${built.id}' has problems",
                emptyList<LayoutDoctor.Finding>(), LayoutDoctor.check(built)
            )
        }
    }

    @Test
    fun `repairing a healthy layout changes nothing`() {
        BuiltinLayouts.ALL.forEach { built ->
            assertEquals("repair touched '${built.id}'", built, LayoutDoctor.repair(built))
        }
    }

    @Test
    fun `an element pointing at a missing layer is caught and redirected`() {
        val broken = layout(elements = listOf(ElementDef(id = "e", layer = "nope")))
        val finding = LayoutDoctor.check(broken).single()
        assertEquals(LayoutDoctor.Severity.BROKEN, finding.severity)
        assertTrue(finding.message.contains("nope"))

        val fixed = LayoutDoctor.repair(broken)
        assertEquals("base", fixed.elements.single().layer)
        assertTrue(LayoutDoctor.check(fixed).isEmpty())
    }

    @Test
    fun `a missing default layer falls back rather than opening on nothing`() {
        val broken = layout(defaultLayer = "ghost")
        assertEquals(LayoutDoctor.Severity.BROKEN, LayoutDoctor.check(broken).single().severity)
        assertEquals("base", LayoutDoctor.repair(broken).defaultLayer)
    }

    @Test
    fun `a floating element with no bounds is given somewhere visible`() {
        val broken = layout(
            elements = listOf(ElementDef(id = "e", placement = ElementPlacement.FLOATING))
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("where it sits") })

        val bounds = LayoutDoctor.repair(broken).elements.single().bounds
        assertNotNull(bounds)
        bounds!!
        assertTrue(bounds.width > 0f && bounds.height > 0f)
        assertTrue(bounds.left >= 0f && bounds.right <= 1f)
    }

    @Test
    fun `an element placed off screen is brought back`() {
        val broken = layout(
            elements = listOf(
                ElementDef(
                    id = "e", placement = ElementPlacement.FREE,
                    bounds = NormRect(1.4f, 1.4f, 1.6f, 1.5f)
                )
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("off screen") })
        val fixed = LayoutDoctor.repair(broken)
        assertTrue(fixed.elements.single().bounds!!.left < 1f)
        assertTrue(LayoutDoctor.check(fixed).isEmpty())
    }

    @Test
    fun `a zero-sized element is caught`() {
        val broken = layout(
            elements = listOf(
                ElementDef(
                    id = "e", placement = ElementPlacement.FLOATING,
                    bounds = NormRect(0.2f, 0.2f, 0.2f, 0.5f)
                )
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("no size") })
        assertTrue(LayoutDoctor.check(LayoutDoctor.repair(broken)).isEmpty())
    }

    @Test
    fun `two keys with one id are renamed rather than dropped`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef("base", listOf(RowDef(listOf(key("a"), key("a", "A")))))
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("share the id") })

        val fixed = LayoutDoctor.repair(broken)
        val keys = fixed.layers.getValue("base").rows.single().keys
        // Both survive: a key the user asked for that ends up oddly named is a
        // complaint; a key that vanished is a bug report with nothing to go on.
        assertEquals(2, keys.size)
        assertEquals(listOf("a", "a_1"), keys.map { it.id })
        assertEquals(listOf("a", "A"), keys.map { it.label })
        assertTrue(LayoutDoctor.check(fixed).isEmpty())
    }

    @Test
    fun `the same id on two different layers is fine`() {
        val two = layout(
            layers = mapOf(
                "base" to LayerDef("base", listOf(RowDef(listOf(key("enter"))))),
                "shift" to LayerDef("shift", listOf(RowDef(listOf(key("enter")))))
            )
        )
        assertTrue(LayoutDoctor.check(two).none { it.message.contains("share the id") })
    }

    @Test
    fun `an empty popup tab is reported and removed`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef(
                    "base",
                    listOf(
                        RowDef(
                            listOf(
                                key("a").copy(
                                    popupGroups = listOf(
                                        PopupGroup("greek", "α", listOf("α", "β")),
                                        PopupGroup("empty", "?", emptyList())
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("nothing in it") })
        val fixed = LayoutDoctor.repair(broken)
        assertEquals(listOf("greek"), fixed.layers.getValue("base").rows[0].keys[0].popupGroups.map { it.id })
    }

    @Test
    fun `two popup tabs with one id keep the first`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef(
                    "base",
                    listOf(
                        RowDef(
                            listOf(
                                key("a").copy(
                                    popupGroups = listOf(
                                        PopupGroup("g", "α", listOf("α")),
                                        PopupGroup("g", "β", listOf("β"))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("two popup tabs") })
        val groups = LayoutDoctor.repair(broken).layers.getValue("base").rows[0].keys[0].popupGroups
        assertEquals(1, groups.size)
        assertEquals("α", groups.single().label)
    }

    @Test
    fun `a key switching to a layer that does not exist is reported but not guessed at`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef(
                    "base",
                    listOf(
                        RowDef(
                            listOf(
                                KeyDef(
                                    id = "sym", label = "?123",
                                    bindings = listOf(
                                        Binding(KeyTrigger.Tap, KeyAction.Layer("symbols"))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val finding = LayoutDoctor.check(broken).single { it.message.contains("switches to layer") }
        // Nothing here can be repaired without inventing a layer full of keys nobody
        // asked for, so the doctor says so and stops.
        assertFalse(finding.repairable)
        assertEquals(broken, LayoutDoctor.repair(broken))
    }

    @Test
    fun `a layout with no layers is reported and left alone`() {
        val empty = LayoutDef(id = "t", name = "T", layers = emptyMap())
        val finding = LayoutDoctor.check(empty).single()
        assertEquals(LayoutDoctor.Severity.BROKEN, finding.severity)
        assertFalse(finding.repairable)
        assertEquals(empty, LayoutDoctor.repair(empty))
    }

    @Test
    fun `a duplicated element is reported and collapsed`() {
        val broken = layout(
            elements = listOf(ElementDef(id = "e"), ElementDef(id = "e", opacity = 0.5f))
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("more than once") })
        assertEquals(1, LayoutDoctor.repair(broken).elements.size)
        assertEquals(1f, LayoutDoctor.repair(broken).elements.single().opacity, 0.0001f)
    }

    @Test
    fun `renaming does not collide with an id that already exists`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef(
                    "base",
                    listOf(RowDef(listOf(key("a"), key("a"), key("a_1"))))
                )
            )
        )
        val ids = LayoutDoctor.repair(broken).layers.getValue("base").rows.single().keys.map { it.id }
        assertEquals(3, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(LayoutDoctor.check(LayoutDoctor.repair(broken)).isEmpty())
    }

    @Test
    fun `repair is idempotent`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef("base", listOf(RowDef(listOf(key("a"), key("a"), key("a")))))
            ),
            elements = listOf(ElementDef(id = "e", layer = "gone", placement = ElementPlacement.FREE)),
            defaultLayer = "gone"
        )
        val once = LayoutDoctor.repair(broken)
        assertEquals(once, LayoutDoctor.repair(once))
        assertTrue(LayoutDoctor.check(once).isEmpty())
    }

    @Test
    fun `a key that draws but does nothing is called out`() {
        val broken = layout(
            layers = mapOf(
                "base" to LayerDef("base", listOf(RowDef(listOf(KeyDef(id = "dead", label = "x")))))
            )
        )
        assertTrue(LayoutDoctor.check(broken).any { it.message.contains("does nothing") })
    }
}
