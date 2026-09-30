package com.example.core

import com.example.core.layout.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolbarRowsTest {
    private fun environment(words: Boolean = false, context: Boolean = false) = ToolbarEnvironment(true, true, false, words, context)

    @Test fun `profiles roundtrip as data and rows may repeat the same roles`() {
        ToolbarRows.profiles.forEach { profile -> assertEquals(profile.rows, ToolbarRows.parse(ToolbarRows.write(profile.rows)).getOrThrow().rows) }
        assertEquals(2, ToolbarRows.profiles.last().rows.size)
        assertEquals(ToolbarRows.profiles.last().rows[0].sources, ToolbarRows.profiles.last().rows[1].sources)
    }

    @Test fun `reserved context rows do not jump as content changes but explicit unreserved rows do`() {
        val row = ToolbarRow("context", listOf(ToolbarSource.CONTEXTUAL), ToolbarVisibility.CONTEXTUAL)
        val stable = ToolbarConfiguration(listOf(row))
        assertEquals(1, ToolbarRows.slots(stable, environment()).size)
        assertFalse(ToolbarRows.slots(stable, environment()).single().showContent)
        assertTrue(ToolbarRows.slots(stable, environment(context = true)).single().showContent)
        val dynamic = ToolbarConfiguration(listOf(row.copy(reserveSpace = false)))
        assertTrue(ToolbarRows.slots(dynamic, environment()).isEmpty())
        assertEquals(1, ToolbarRows.slots(dynamic, environment(context = true)).size)
    }

    @Test fun `blank retains legacy visibility while explicit zero rows remains zero`() {
        val legacy = ToolbarRows.parse("").getOrThrow()
        assertTrue(legacy.legacy)
        assertTrue(ToolbarRows.slots(legacy, ToolbarEnvironment(false, false, false, false, false)).isEmpty())
        assertEquals(1, ToolbarRows.slots(legacy, ToolbarEnvironment(false, false, true, false, false)).size)
        assertTrue(ToolbarRows.parse("{\"rows\":[]}").getOrThrow().rows.isEmpty())
    }

    @Test fun `invalid rows fail validation and renderer has safe compatibility fallback`() {
        val bad = listOf(
            "{\"rows\":[{\"id\":\"same\",\"sources\":[\"tools\"]},{\"id\":\"same\",\"sources\":[\"tools\"]}]}",
            "{\"rows\":[{\"id\":\"r\",\"sources\":[\"tools\",\"tools\"]}]}",
            "{\"rows\":[{\"id\":\"r\",\"sources\":[\"unknown\"]}]}",
            "{\"rows\":[{\"id\":\"r\",\"sources\":[\"tools\"],\"reserveSpace\":\"yes\"}]}"
        )
        bad.forEach { assertTrue(ToolbarRows.parse(it).isFailure); assertTrue(ToolbarRows.configuration(it).legacy) }
        val tooMany = (1..5).joinToString(",") { "{\"id\":\"row-$it\",\"sources\":[\"tools\"]}" }
        assertTrue(ToolbarRows.parse("{\"rows\":[$tooMany]}").isFailure)
    }
}
