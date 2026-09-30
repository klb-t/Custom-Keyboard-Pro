package com.example.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.layout.*
import com.example.ime.KeyboardState
import com.example.ui.kb.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual multi-pointer events across Compose recomposition, rather than a model of the pointer code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeySurfaceGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val keyboardState = KeyboardState()
    private val performed = mutableListOf<Triple<String, String, KeyAction>>()
    private var settings by mutableStateOf(Settings(keyPreviewPopup = false))
    private var definition by mutableStateOf(layout())

    private fun key(id: String, action: KeyAction) = KeyDef(id, label = id, bindings = listOf(Binding(KeyTrigger.Tap, action)))
    private fun layout(momentary: Boolean = false): LayoutDef {
        val base = LayerDef("base", rows = listOf(RowDef(listOf(
            key("shift", KeyAction.Modifier(ModifierKind.SHIFT, if (momentary) ModifierMode.MOMENTARY else ModifierMode.ONE_SHOT)),
            key("ctrl", KeyAction.Modifier(ModifierKind.CTRL, ModifierMode.ONE_SHOT)),
            key("a", KeyAction.Text("a"))
        ))))
        val shifted = base.copy(name = "shift", rows = base.rows.map { row -> row.copy(keys = row.keys.map {
            if (it.id == "a") it.copy(label = "A", bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text("A")))) else it
        }) })
        return LayoutDef("gestures", "Gestures", mapOf("base" to base, "shift" to shifted))
    }
    private val host = object : KeyboardHost {
        override val state get() = keyboardState
        override val layout get() = definition
        override val availableLayouts get() = listOf(definition)
        override val editor: com.example.ime.EditorController get() = error("Unused")
        override val suggestions: com.example.core.suggest.SuggestionEngine get() = error("Unused")
        override val completions: com.example.core.predict.CompletionEngine get() = error("Unused")
        override val voice: com.example.core.asr.VoiceController get() = error("Unused")
        override val repository: com.example.core.data.KeyboardRepository get() = error("Unused")
        override val touchLearner: com.example.core.hitmap.TouchLearner get() = error("Unused")
        override val avoidance = AvoidanceState()
        override val notices: NoticeBoard get() = error("Unused")
        override val openPanelId: PanelId? = null
        override fun perform(action: KeyAction) = Unit
        override fun putOnClipboard(clip: android.content.ClipData) = Unit
        override fun feedback(key: KeyDef?) = Unit
        override fun performSurfaceGesture(direction: SwipeDirection) = false
        override fun openPanel(panel: PanelId?) = Unit
        override fun selectLayout(id: String) = Unit
        override fun openApp(route: String?) = Unit
        override fun requestMicrophonePermission() = Unit
        override fun hideKeyboard() = Unit
        override fun switchIme() = Unit
        override fun textForAi() = ""
        override fun reportKeyRects(sourceId: String, rects: List<android.graphics.Rect>) = Unit
        override fun reportPanelRect(sourceId: String, rect: android.graphics.Rect?, reservesContent: Boolean) = Unit
    }

    private fun render() {
        compose.setContent {
            val layer = keyboardState.renderLayer(definition)
            CompositionLocalProvider(LocalKeyboardHost provides host) {
                KeySurface(definition, layer, keyboardState, settings, BuiltinThemes.LIGHT, null,
                    onAction = { key, action ->
                        performed += Triple(layer, key.id, action)
                        if (action is KeyAction.Modifier) keyboardState.pressModifier(action.kind, action.mode)
                    }, onKeyDown = {}, onCursorNudge = {}, gestureIdentity = definition,
                    modifier = Modifier.size(300.dp, 120.dp).testTag("keys"))
            }
        }
    }

    @Test fun `Shift recomposition does not eat the held bottom row Ctrl release`() {
        render()
        compose.onNodeWithTag("keys").performTouchInput {
            down(0, Offset(150f, 60f)); down(1, Offset(50f, 60f)); up(1)
        }
        compose.runOnIdle { assertTrue(keyboardState.isActive(ModifierKind.SHIFT)) }
        compose.onNodeWithTag("keys").performTouchInput { up(0) }
        compose.runOnIdle {
            assertTrue(keyboardState.isActive(ModifierKind.CTRL))
            assertEquals("base", performed.single { it.second == "ctrl" }.first)
        }
    }

    @Test fun `a held character retains original binding and policy when layer and settings change`() {
        render()
        compose.onNodeWithTag("keys").performTouchInput {
            down(0, Offset(250f, 60f)); down(1, Offset(50f, 60f)); up(1)
        }
        compose.runOnIdle { settings = settings.copy(longPressMs = settings.longPressMs + 10) }
        compose.onNodeWithTag("keys").performTouchInput { up(0) }
        compose.runOnIdle {
            val character = performed.single { it.second == "a" }
            assertEquals("base", character.first)
            assertEquals(KeyAction.Text("a"), character.third)
        }
    }

    @Test fun `real definition replacement releases a momentary modifier and cancels its old finger`() {
        definition = layout(momentary = true)
        render()
        compose.onNodeWithTag("keys").performTouchInput { down(0, Offset(50f, 60f)) }
        compose.runOnIdle {
            assertTrue(keyboardState.modifier(ModifierKind.SHIFT).held)
            definition = definition.copy(name = "Replaced definition")
        }
        compose.runOnIdle { assertFalse(keyboardState.isActive(ModifierKind.SHIFT)) }
        compose.onNodeWithTag("keys").performTouchInput { up(0) }
        compose.runOnIdle { assertEquals(1, performed.size) }
    }
}
