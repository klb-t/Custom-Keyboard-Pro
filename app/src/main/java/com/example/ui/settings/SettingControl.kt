package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingKind
import com.example.core.config.SettingSpec
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import kotlin.math.roundToInt

/**
 * One setting, rendered from its description rather than from code written for it.
 *
 * Every screen that shows settings goes through here, which is what makes "expose
 * everything" tractable: adding a field to [Settings] gives it a control everywhere,
 * and a panel a model generated at runtime gets exactly the same controls as a
 * hand-built screen, because there is only one implementation of each.
 */
@Composable
fun SettingControl(
    spec: SettingSpec,
    settings: Settings,
    labelOverride: String? = null,
    helpOverride: String? = null,
    onWrite: ((Any?) -> Result<Unit>)? = null
) {
    val label = labelOverride ?: spec.label
    val help = helpOverride ?: spec.help
    val value = SettingsSchema.valueOf(settings, spec.key)
    var writeError by remember(spec.key) { mutableStateOf<String?>(null) }
    fun write(raw: Any?) {
        SettingsSchema.validatedValue(spec.key, raw).mapCatching {
            (onWrite?.invoke(it) ?: SettingsStore.setByKey(spec.key, it)).getOrThrow()
        }.fold(onSuccess = { writeError = null }, onFailure = { writeError = it.message })
    }

    Column {
    if (spec.key == "toolbarRowsJson") ToolbarRowsEditor(value?.toString().orEmpty(), onChange = { write(it) })
    else when (spec.kind) {
        SettingKind.BOOL -> SwitchRow(
            label = label,
            description = help,
            checked = value as? Boolean ?: false,
            onChange = { write(it) }
        )

        SettingKind.ENUM -> {
            val options = spec.liveOptions
            if (options.isEmpty()) {
                TextRow(
                    label = label,
                    description = help,
                    value = value?.toString().orEmpty(),
                    onChange = { write(it) }
                )
            } else {
                ChoiceRow(
                    label = label,
                    description = help,
                    options = options,
                    selected = value?.toString().orEmpty().ifEmpty { options.first() },
                    optionLabel = { prettyOption(it) },
                    onSelect = { write(it) }
                )
            }
        }

        SettingKind.FLOAT, SettingKind.INT, SettingKind.LONG -> {
            val number = (value as? Number)?.toFloat() ?: 0f
            val min = spec.min
            val max = spec.max
            if (min != null && max != null) {
                val whole = spec.kind != SettingKind.FLOAT
                SliderRow(
                    label = label,
                    description = help,
                    value = number.coerceIn(min, max),
                    range = min..max,
                    format = { if (whole) it.roundToInt().toString() else "%.2f".format(it) },
                    onChange = { raw ->
                        write(if (whole) raw.roundToInt() else raw)
                    }
                )
                var exact by remember(spec.key) { mutableStateOf(false) }
                TextButton(onClick = { exact = !exact }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (exact) "Hide exact value" else "Enter exact value")
                }
                if (exact) ValidatedDraftRow(spec, "Exact value", null, value?.toString().orEmpty(), ::write)
            } else {
                // No range means no honest slider: a text field says "type what you
                // mean" instead of inventing bounds the setting does not have.
                ValidatedDraftRow(spec, label, help, value?.toString().orEmpty(), ::write)
            }
        }

        SettingKind.COLOR -> ColorRow(
            label = label,
            description = help,
            argb = (value as? Number)?.toLong() ?: 0xFF000000L,
            onChange = { write(it) }
        )

        SettingKind.STRING_LIST -> {
            val items = (value as? org.json.JSONArray)?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty()
            TextRow(
                label = label,
                description = (help?.plus("  ") ?: "") + "Comma separated.",
                value = items.joinToString(", "),
                onChange = { write(it) }
            )
        }

        SettingKind.JSON, SettingKind.ACTION -> ValidatedDraftRow(
            spec = spec, label = label, help = help,
            value = value?.toString().orEmpty(), onApply = ::write
        )

        SettingKind.STRING -> TextRow(
            label = label,
            description = help,
            value = value?.toString().orEmpty(),
            secret = spec.secret,
            onChange = { write(it) }
        )
    }
    writeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
    }
}

/** Drafts can be temporarily incomplete; only a valid, explicit Apply reaches live settings. */
@Composable
private fun ValidatedDraftRow(spec: SettingSpec, label: String, help: String?, value: String, onApply: (Any?) -> Unit) {
    var draft by remember(spec.key, value) { mutableStateOf(value) }
    val validation = remember(spec.key, draft) { SettingsSchema.validatedValue(spec.key, draft) }
    TextRow(
        label = label, description = help, value = draft, secret = spec.secret,
        numeric = spec.kind in listOf(SettingKind.FLOAT, SettingKind.INT, SettingKind.LONG),
        singleLine = spec.kind !in listOf(SettingKind.JSON, SettingKind.ACTION),
        minLines = if (spec.kind == SettingKind.JSON) 4 else 1,
        onChange = { draft = it }
    )
    if (draft != value) {
        validation.exceptionOrNull()?.let {
            Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { onApply(validation.getOrThrow()) }, enabled = validation.isSuccess) { Text("Apply") }
            TextButton(onClick = { draft = value }) { Text("Discard") }
        }
    }
}

/** "ONE_HANDED_LEFT" -> "One handed left". Enum constants are not written for people. */
fun prettyOption(raw: String): String =
    if (raw.any { it.isLowerCase() }) raw
    else raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * A colour with an alpha channel, which is the part that matters here: transparency
 * is a colour property in this app, so anything that edits a colour must be able to
 * reach it, or "transparent keys" becomes unreachable for no good reason.
 */
@Composable
fun ColorRow(
    label: String,
    description: String? = null,
    argb: Long,
    onChange: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()

    fun emit(na: Int, nr: Int, ng: Int, nb: Int) {
        val packed = ((na.toLong() and 0xFF) shl 24) or
            ((nr.toLong() and 0xFF) shl 16) or
            ((ng.toLong() and 0xFF) shl 8) or
            (nb.toLong() and 0xFF)
        onChange(packed)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.width(12.dp))
            // A checkerboard behind the swatch, so "half transparent" reads as
            // transparency rather than as a slightly different colour.
            Box(
                Modifier
                    .size(34.dp)
                    .background(Color(0xFFBBBBBB), RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            ) {
                Box(
                    Modifier
                        .size(17.dp)
                        .align(Alignment.TopEnd)
                        .background(Color(0xFF888888))
                )
                Box(
                    Modifier
                        .size(17.dp)
                        .align(Alignment.BottomStart)
                        .background(Color(0xFF888888))
                )
                Box(Modifier.size(34.dp).background(Color(argb)))
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Done" else "Edit") }
        }
        if (expanded) {
            ChannelSlider("Opacity", a) { emit(it, r, g, b) }
            ChannelSlider("Red", r) { emit(a, it, g, b) }
            ChannelSlider("Green", g) { emit(a, r, it, b) }
            ChannelSlider("Blue", b) { emit(a, r, g, it) }
            TextRow(
                label = "Hex (AARRGGBB)",
                value = "%08X".format(argb),
                onChange = { typed ->
                    typed.trim().removePrefix("#").removePrefix("0x").toLongOrNull(16)?.let(onChange)
                }
            )
        }
    }
}

@Composable
private fun ChannelSlider(name: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
    }
}

/** A spacer that keeps a group of controls visually apart without a card each. */
@Composable
fun GroupSpacer() = Spacer(Modifier.height(8.dp))
