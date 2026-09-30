package com.example.core.layout

data class KeyboardViewportBudget(val keysDp: Float, val rowDp: Float, val indicatorDp: Float, val bottomDp: Float, val rowCount: Int) {
    val totalDp: Float get() = keysDp + rowDp * rowCount + indicatorDp + bottomDp

    companion object {
        /** Budget the actual body below a floating grip, not the device screen. */
        fun fit(availableDp: Float, preferredKeysDp: Float, rowCount: Int, preferredRowDp: Float,
            preferredIndicatorDp: Float, preferredBottomDp: Float, minimumKeysDp: Float = 96f,
            minimumKeysFraction: Float = 0.45f): KeyboardViewportBudget {
            require(listOf(availableDp, preferredKeysDp, preferredRowDp, preferredIndicatorDp, preferredBottomDp,
                minimumKeysDp, minimumKeysFraction).all { it.isFinite() })
            require(minimumKeysDp >= 0f && minimumKeysFraction in 0f..1f)
            require(rowCount in 0..ToolbarRows.MAX_ROWS + 1)
            val available = availableDp.coerceAtLeast(0f)
            val minimumKeys = minOf(minimumKeysDp, available * minimumKeysFraction)
            val row = preferredRowDp.coerceAtLeast(0f)
            val indicator = preferredIndicatorDp.coerceAtLeast(0f)
            val bottom = preferredBottomDp.coerceAtLeast(0f)
            val chrome = row * rowCount + indicator + bottom
            val scale = if (chrome <= 0f) 1f else minOf(1f, (available - minimumKeys) / chrome)
            val remaining = (available - chrome * scale).coerceAtLeast(0f)
            val keys = preferredKeysDp.coerceIn(minOf(minimumKeys, remaining), remaining)
            return KeyboardViewportBudget(keys, row * scale, indicator * scale, bottom * scale, rowCount)
        }
    }
}
