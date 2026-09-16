package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.layout.IndicatorKeys
import com.example.core.layout.LayoutDef
import com.example.core.layout.ModifierKind

/**
 * A live read-out of what the keyboard thinks is going on, drawn over the keys.
 *
 * Off by default and only reachable in expert mode. It exists because most of what
 * this keyboard does — which modifier is one-shot versus locked, which layer is
 * showing, why a suggestion appeared — is invisible state, and the fastest way to
 * understand a layout you wrote yourself is to watch that state change as you press
 * things.
 */
/** Amber: something is half-typed and waiting for the next key. */
private val PENDING = Color(0xFFFFD400)

@Composable
fun DebugOverlay(
    state: com.example.ime.KeyboardState,
    layout: LayoutDef,
    layerName: String,
    selectionStart: Int,
    selectionEnd: Int,
    suggestionCount: Int,
    theme: KeyboardTheme
) {
    val modifiers = ModifierKind.entries.mapNotNull { kind ->
        val s = state.modifier(kind)
        when {
            s.locked -> "${kind.name.lowercase()}=lock"
            s.oneShot -> "${kind.name.lowercase()}=1shot"
            s.active -> kind.name.lowercase()
            else -> null
        }
    }

    val flags = listOf(
        IndicatorKeys.AI_BUSY to "ai",
        IndicatorKeys.ASR_LISTENING to "mic",
        IndicatorKeys.PASSWORD_FIELD to "password",
        IndicatorKeys.DEAD_KEY to "dead",
        IndicatorKeys.COMPOSING to "compose",
        IndicatorKeys.UNICODE_ENTRY to "unicode"
    ).filter { state.flag(it.first) }.map { it.second }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Column {
            Text(
                text = "${layout.id} · layer=$layerName · keys=${(layout.layer(layerName) ?: layout.base).allKeys.size}",
                color = Color(0xFF9EE7A0),
                fontSize = 10.sp
            )
            Text(
                text = "mods=[${modifiers.joinToString(",").ifEmpty { "-" }}]  " +
                    "sel=$selectionStart..$selectionEnd  " +
                    "suggestions=$suggestionCount" +
                    (if (flags.isEmpty()) "" else "  ${flags.joinToString(",")}"),
                color = Color(0xFFCCCCCC),
                fontSize = 10.sp
            )
            state.pendingDeadKey?.let {
                Text(text = "pending dead key: $it", color = PENDING, fontSize = 10.sp)
            }
            state.composeBuffer?.let {
                Text(text = "compose: $it", color = PENDING, fontSize = 10.sp)
            }
            state.unicodeBuffer?.let {
                Text(text = "unicode: U+$it", color = PENDING, fontSize = 10.sp)
            }
        }
    }
}
