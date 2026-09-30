package com.example.core

import com.example.core.layout.KeyboardViewportBudget
import org.junit.Assert.*
import org.junit.Test

class KeyboardViewportBudgetTest {
    @Test fun `four toolbar rows plus completion reserve key area inside a resized floating body`() {
        val budget = KeyboardViewportBudget.fit(114f, 260f, 5, 42f, 20f, 12f)
        assertTrue(budget.keysDp >= 114f * 0.45f - 0.001f)
        assertTrue(budget.rowDp > 0f && budget.rowDp < 42f)
        assertEquals(5, budget.rowCount)
        assertTrue(budget.totalDp <= 114f + 0.001f)
        assertEquals(budget.rowDp / 42f, budget.indicatorDp / 20f, 0.00001f)
    }

    @Test fun `ample space retains requested metrics and tiny constraints remain bounded`() {
        val full = KeyboardViewportBudget.fit(500f, 250f, 2, 42f, 20f, 8f)
        assertEquals(250f, full.keysDp, 0.001f)
        assertEquals(42f, full.rowDp, 0.001f)
        assertEquals(20f, full.indicatorDp, 0.001f)
        val empty = KeyboardViewportBudget.fit(0f, 250f, 5, 42f, 20f, 8f)
        assertEquals(0f, empty.totalDp, 0.001f)
        assertTrue(empty.keysDp >= 0f && empty.rowDp >= 0f && empty.indicatorDp >= 0f)
    }
}
