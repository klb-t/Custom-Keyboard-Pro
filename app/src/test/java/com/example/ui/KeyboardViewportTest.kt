package com.example.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.kb.KeyboardViewport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `actual floating constraints reproduce vanished keys then retain keys through resize`() {
        var budgeted by mutableStateOf(false)
        var panelHeight by mutableStateOf(140f)
        var keyHeight = -1
        var bodyHeight = -1
        val rowHeights = IntArray(5)
        compose.setContent {
            Column(Modifier.width(300.dp).height(panelHeight.dp)) {
                Box(Modifier.height(26.dp))
                if (budgeted) {
                    KeyboardViewport(260f, 5, 42f, 20f, 12f, Modifier.weight(1f).onGloballyPositioned { bodyHeight = it.size.height }) { budget ->
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.height(budget.indicatorDp.dp))
                            repeat(5) { index -> Box(Modifier.height(budget.rowDp.dp).onGloballyPositioned { rowHeights[index] = it.size.height }) }
                            Box(Modifier.height(budget.keysDp.dp).onGloballyPositioned { keyHeight = it.size.height })
                            Box(Modifier.height(budget.bottomDp.dp))
                        }
                    }
                } else {
                    // The former body used screen-budgeted fixed rows inside this smaller parent.
                    Column(Modifier.weight(1f).onGloballyPositioned { bodyHeight = it.size.height }) {
                        Box(Modifier.height(20.dp))
                        repeat(5) { Box(Modifier.height(42.dp)) }
                        Box(Modifier.height(260.dp).onGloballyPositioned { keyHeight = it.size.height })
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(114, bodyHeight)
            assertEquals("Former fixed chrome exhausted the real body constraints", 0, keyHeight)
            budgeted = true
        }
        compose.runOnIdle {
            assertTrue(keyHeight >= 51)
            assertTrue(rowHeights.all { it > 0 })
            panelHeight = 220f
        }
        compose.runOnIdle {
            assertEquals(194, bodyHeight)
            assertTrue(keyHeight >= 87)
            panelHeight = 140f
        }
        compose.runOnIdle { assertTrue(keyHeight >= 51); assertEquals(114, bodyHeight) }
    }
}
