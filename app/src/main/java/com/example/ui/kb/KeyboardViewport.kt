package com.example.ui.kb

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.core.layout.KeyboardViewportBudget

/** Shared measured body: resize and window insets feed the same budget as rendering. */
@Composable
internal fun KeyboardViewport(
    preferredKeysDp: Float,
    rowCount: Int,
    preferredRowDp: Float,
    preferredIndicatorDp: Float,
    preferredBottomDp: Float,
    modifier: Modifier = Modifier,
    minimumKeysDp: Float = 96f,
    minimumKeysFraction: Float = 0.45f,
    content: @Composable (KeyboardViewportBudget) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val preferredTotal = preferredKeysDp + rowCount * preferredRowDp + preferredIndicatorDp + preferredBottomDp
        val available = maxHeight.value.takeIf { it.isFinite() } ?: preferredTotal
        content(KeyboardViewportBudget.fit(available, preferredKeysDp, rowCount, preferredRowDp, preferredIndicatorDp,
            preferredBottomDp, minimumKeysDp, minimumKeysFraction))
    }
}
