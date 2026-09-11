package com.example.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.math.roundToInt

/**
 * Setting controls, written once.
 *
 * Every one takes a description as well as a label. A setting whose effect cannot be
 * explained in a line is a setting the user cannot make an informed choice about, and
 * this app has enough of them that guessing is not an option.
 */

@Composable
fun SettingsSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(
    label: String,
    description: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge)
        }
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun <T> ChoiceRow(
    label: String,
    description: String? = null,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text(optionLabel(selected), maxLines = 2) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TextRow(
    label: String,
    description: String? = null,
    value: String,
    placeholder: String = "",
    secret: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    numeric -> KeyboardType.Number
                    secret -> KeyboardType.Password
                    else -> KeyboardType.Text
                }
            ),
            trailingIcon = {
                if (secret) {
                    TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        description?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ActionRow(
    label: String,
    description: String? = null,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun Divider() = HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun InfoRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/** Reorderable, checkable list: used for the layout rotation. */
@Composable
fun OrderedChoiceList(
    all: List<Pair<String, String>>,
    enabled: List<String>,
    onChange: (List<String>) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        enabled.forEachIndexed { index, id ->
            val label = all.firstOrNull { it.first == id }?.second ?: id
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}.", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(label, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (index > 0) {
                            val next = enabled.toMutableList()
                            next[index] = next[index - 1].also { next[index - 1] = next[index] }
                            onChange(next)
                        }
                    },
                    enabled = index > 0
                ) { Text("↑") }
                TextButton(
                    onClick = {
                        if (index < enabled.size - 1) {
                            val next = enabled.toMutableList()
                            next[index] = next[index + 1].also { next[index + 1] = next[index] }
                            onChange(next)
                        }
                    },
                    enabled = index < enabled.size - 1
                ) { Text("↓") }
                TextButton(onClick = { onChange(enabled - id) }) { Text("Remove") }
            }
        }
        val available = all.filter { it.first !in enabled }
        if (available.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = { expanded = true }) { Text("Add a layout…") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    available.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onChange(enabled + id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

fun Float.asPercent(): String = "${(this * 100).roundToInt()}%"
