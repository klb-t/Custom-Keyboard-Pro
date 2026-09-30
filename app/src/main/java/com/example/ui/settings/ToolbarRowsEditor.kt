package com.example.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.layout.ToolbarRow
import com.example.core.layout.ToolbarRows
import com.example.core.layout.ToolbarSource
import com.example.core.layout.ToolbarVisibility

/** Row roles and examples edit the same JSON used by global/layout/panel settings. */
@Composable
fun ToolbarRowsEditor(value: String, onChange: (String) -> Unit) {
    val parsed = remember(value) { ToolbarRows.parse(value) }
    val configuration = parsed.getOrElse { ToolbarRows.configuration("") }
    val rows = configuration.rows
    fun update(next: List<ToolbarRow>) { onChange(ToolbarRows.write(next)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Toolbar rows", style = MaterialTheme.typography.titleSmall)
        Text("Sources can mix and repeat across rows. Completion keeps its own append-only row. Reserved empty rows keep the keys still.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { onChange("") }) { Text("Compatible default") }
            ToolbarRows.profiles.forEach { profile -> TextButton(onClick = { update(profile.rows) }) { Text(profile.title) } }
        }
        if (configuration.legacy) Text("Compatible default: one adaptive row, with tools when suggestions are empty.", style = MaterialTheme.typography.bodySmall)
        parsed.exceptionOrNull()?.let { Text(it.message ?: "Invalid toolbar configuration", color = MaterialTheme.colorScheme.error) }
        rows.forEachIndexed { index, row ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Row ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { update(rows.toMutableList().also { it[index - 1] = row; it[index] = rows[index - 1] }) }, enabled = index > 0) { Text("↑") }
                    TextButton(onClick = { update(rows.toMutableList().also { it[index + 1] = row; it[index] = rows[index + 1] }) }, enabled = index < rows.lastIndex) { Text("↓") }
                    TextButton(onClick = { update(rows.filterIndexed { at, _ -> at != index }) }) { Text("Remove") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    ToolbarSource.entries.forEach { source ->
                        Checkbox(source in row.sources, { checked ->
                            val next = if (checked) row.sources + source else row.sources - source
                            if (next.isNotEmpty()) update(rows.mapIndexed { at, item -> if (at == index) item.copy(sources = next) else item })
                        }, enabled = source !in row.sources || row.sources.size > 1)
                        Text(source.title, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ToolbarVisibility.entries.forEach { visibility -> TextButton(onClick = {
                        update(rows.mapIndexed { at, item -> if (at == index) item.copy(visibility = visibility) else item })
                    }) { Text(if (row.visibility == visibility) "[${visibility.title}]" else visibility.title) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.reserveSpace, { reserve -> update(rows.mapIndexed { at, item -> if (at == index) item.copy(reserveSpace = reserve) else item }) })
                    Text("Reserve space while empty", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = {
            val id = (1..100).map { "row-$it" }.first { candidate -> rows.none { it.id == candidate } }
            update(rows + ToolbarRow(id, ToolbarSource.entries.toList()))
        }, enabled = rows.size < ToolbarRows.MAX_ROWS) { Text("Add row (${rows.size}/${ToolbarRows.MAX_ROWS})") }
    }
}
