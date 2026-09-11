package com.example.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.layout.Binding
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyTrigger
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutJson
import com.example.ui.kb.KeyIcons
import org.json.JSONObject

/**
 * Editing a key without writing JSON.
 *
 * This is what makes the bitmap route usable: after tracing a picture you have a
 * layout full of regions that do nothing, and assigning behaviour to each one should
 * not require learning a file format first. The JSON editor is still there for
 * everything this does not cover — and this writes through the same parser, so the two
 * cannot disagree about what a key means.
 */

/** The action types offered, with what their payload means. */
private data class ActionKind(
    val type: String,
    val label: String,
    val payloadField: String?,
    val payloadHint: String
)

private val ACTION_KINDS = listOf(
    ActionKind("none", "Nothing", null, ""),
    ActionKind("text", "Type text", "text", "What to type — a letter, a word, an emoji"),
    ActionKind("key", "Send a key event", "code", "KEYCODE_TAB, KEYCODE_F5, KEYCODE_ESCAPE…"),
    ActionKind("modifier", "Modifier", "kind", "shift, ctrl, alt, meta, fn, alt_gr"),
    ActionKind("layer", "Switch layer", "layer", "base, shift, symbols, or your own"),
    ActionKind("layout", "Next layout", null, ""),
    ActionKind("language", "Next language", null, ""),
    ActionKind("backspace", "Backspace", "unit", "character, word or line"),
    ActionKind("delete", "Forward delete", "unit", "character or word"),
    ActionKind("enter", "Enter", null, ""),
    ActionKind("space", "Space", null, ""),
    ActionKind("cursor", "Move the cursor", "direction", "left, right, up, down, line_start, line_end"),
    ActionKind("select", "Select", "unit", "word, line or all"),
    ActionKind("clipboard", "Clipboard", "op", "copy, cut, paste or history"),
    ActionKind("undo", "Undo", null, ""),
    ActionKind("redo", "Redo", null, ""),
    ActionKind("panel", "Open a panel", "panel", "emoji, clipboard, voice, ai_tools, cursor"),
    ActionKind("voice", "Dictation", null, ""),
    ActionKind("ai", "Run an AI task", "task", "fix, formal, shorten, translate_en…"),
    ActionKind("dead", "Dead key (accent)", "combining", "A combining mark, e.g. ́ for acute"),
    ActionKind("compose", "Compose sequence", null, ""),
    ActionKind("unicode", "Unicode by code point", null, ""),
    ActionKind("presentation", "Change shape", "mode", "one_handed_right, split, floating, cycle"),
    ActionKind("hide", "Hide the keyboard", null, ""),
    ActionKind("settings", "Open settings", null, ""),
    ActionKind("switch_ime", "Switch keyboard app", null, "")
)

/** Best-effort reverse mapping, so opening a key shows what it already does. */
private fun describe(action: KeyAction?): Pair<ActionKind, String> {
    val json = action?.let { LayoutJson.writeAction(it) as? JSONObject }
        ?: return ACTION_KINDS.first() to ""
    val type = json.optString("type", "none")
    val kind = ACTION_KINDS.firstOrNull { it.type == type } ?: ACTION_KINDS.first()
    val payload = kind.payloadField?.let { json.optString(it) }.orEmpty()
    return kind to payload
}

private fun build(kind: ActionKind, payload: String): KeyAction? {
    if (kind.type == "none") return null
    val json = JSONObject().put("type", kind.type)
    kind.payloadField?.let { json.put(it, payload) }
    return LayoutJson.parseAction(json)
}

@Composable
private fun ActionPicker(
    title: String,
    action: KeyAction?,
    onChange: (KeyAction?) -> Unit
) {
    val initial = remember(action) { describe(action) }
    var kind by remember(action) { mutableStateOf(initial.first) }
    var payload by remember(action) { mutableStateOf(initial.second) }

    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
        ChoiceRow(
            label = "Does",
            options = ACTION_KINDS,
            selected = kind,
            optionLabel = { it.label },
            onSelect = {
                kind = it
                payload = ""
                onChange(build(it, ""))
            }
        )
        kind.payloadField?.let {
            TextRow(
                label = "Value",
                description = kind.payloadHint,
                value = payload,
                onChange = { value ->
                    payload = value
                    onChange(build(kind, value))
                }
            )
        }
    }
}

/**
 * The list of every key in a layout, as a way in to editing one.
 *
 * Absolutely-placed keys — the ones a colour mask produces — are listed with their
 * position, because after tracing a picture that is the only thing distinguishing them.
 */
@Composable
fun KeyListDialog(
    layout: LayoutDef,
    onEdit: (KeyDef) -> Unit,
    onDismiss: () -> Unit
) {
    val keys = remember(layout) {
        layout.layers.values.flatMap { it.allKeys }.distinctBy { it.id }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${layout.name} · ${keys.size} keys") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(keys, key = { it.id }) { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(key) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                key.effectiveLabel.ifEmpty { key.icon ?: key.id },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                buildString {
                                    append(describe(key.tapAction).first.label)
                                    key.bounds?.let {
                                        append(
                                            " · at %.0f%%, %.0f%%".format(
                                                it.left * 100, it.top * 100
                                            )
                                        )
                                    }
                                    if (key.bindings.size > 1) {
                                        append(" · ${key.bindings.size} bindings")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("edit", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun KeyEditorDialog(
    key: KeyDef,
    onSave: (KeyDef) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember(key.id) { mutableStateOf(key.label.orEmpty()) }
    var hint by remember(key.id) { mutableStateOf(key.hint.orEmpty()) }
    var icon by remember(key.id) { mutableStateOf(key.icon.orEmpty()) }
    var width by remember(key.id) { mutableStateOf(key.widthWeight) }
    var style by remember(key.id) { mutableStateOf(key.style ?: "normal") }
    var repeatable by remember(key.id) { mutableStateOf(key.repeatable) }
    var popup by remember(key.id) { mutableStateOf(key.popup.joinToString(" ")) }
    var tap by remember(key.id) { mutableStateOf(key.actionFor(KeyTrigger.Tap)) }
    var longPress by remember(key.id) { mutableStateOf(key.actionFor(KeyTrigger.LongPress)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Key “${key.effectiveLabel.ifEmpty { key.id }}”") },
        text = {
            Column(Modifier.fillMaxWidth().height(440.dp).verticalScroll(rememberScrollState())) {
                TextRow(
                    label = "Label",
                    description = "Drawn on the key. Leave empty to show whatever it types.",
                    value = label,
                    onChange = { label = it }
                )
                TextRow(label = "Corner hint", value = hint, onChange = { hint = it })
                ChoiceRow(
                    label = "Icon",
                    description = "Wins over the label when set.",
                    options = listOf("") + KeyIcons.NAMES,
                    selected = icon,
                    optionLabel = { it.ifEmpty { "None" } },
                    onSelect = { icon = it }
                )
                SliderRow(
                    label = "Width",
                    description = "Relative to the other keys in its row.",
                    value = width,
                    range = 0.4f..8f,
                    format = { "%.1f×".format(it) },
                    onChange = { width = it }
                )
                ChoiceRow(
                    label = "Style",
                    options = listOf("normal", "special", "modifier", "accent"),
                    selected = style,
                    optionLabel = { it },
                    onSelect = { style = it }
                )
                SwitchRow(
                    label = "Repeats while held",
                    description = "For backspace and arrows. A repeating key ignores " +
                        "long-press, because holding it already means something.",
                    checked = repeatable,
                    onChange = { repeatable = it }
                )
                TextRow(
                    label = "Long-press alternates",
                    description = "Separated by spaces, e.g.  ą á à â",
                    value = popup,
                    onChange = { popup = it }
                )
                Divider()
                ActionPicker("On tap", tap) { tap = it }
                Divider()
                ActionPicker("On long press", longPress) { longPress = it }
                Spacer(Modifier.height(8.dp))
                InfoRow(
                    "Swipes, double tap, chords, macros and indicator lamps are set in " +
                        "the layout's JSON; this covers what a key most often needs."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Keep every binding this editor does not cover, so editing a tap action
                // cannot quietly discard a swipe someone set up in the JSON.
                val untouched = key.bindings.filter {
                    it.trigger != KeyTrigger.Tap && it.trigger != KeyTrigger.LongPress
                }
                val rebuilt = untouched +
                    (tap?.let { listOf(Binding(KeyTrigger.Tap, it)) } ?: emptyList()) +
                    (longPress?.let { listOf(Binding(KeyTrigger.LongPress, it)) } ?: emptyList())

                onSave(
                    key.copy(
                        label = label.ifBlank { null },
                        hint = hint.ifBlank { null },
                        icon = icon.ifBlank { null },
                        widthWeight = width,
                        style = style.takeIf { it != "normal" },
                        repeatable = repeatable,
                        popup = popup.split(" ").map { it.trim() }.filter { it.isNotEmpty() },
                        bindings = rebuilt,
                        visible = true
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
