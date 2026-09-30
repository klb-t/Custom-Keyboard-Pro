package com.example.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.debug.RuntimeValue
import com.example.core.debug.RuntimeVariables
import com.example.core.debug.VariableRule
import kotlinx.coroutines.delay

/** Volatile runtime values are a separate surface from persisted user settings. */
@Composable
fun RuntimeDebuggerScreen(settings: Settings, allowed: Boolean) {
    var values by remember { mutableStateOf(emptyList<RuntimeValue>()) }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RuntimeValue?>(null) }
    var next by remember { mutableStateOf("") }
    LaunchedEffect(allowed) {
        while (allowed) {
            values = RuntimeVariables.registry.snapshot()
            delay(500)
        }
        values = emptyList()
        editing = null
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Runtime debugger", style = MaterialTheme.typography.titleLarge)
        Text("Live registered variables, refreshed twice per second. Changes are temporary and apply only to the displayed host/session. Text, clipboard contents and credentials are excluded.")
        if (!allowed) {
            Text("Select Debugger in settings level to inspect runtime state.")
        } else {
            if (values.isEmpty()) Text("No runtime host is connected. Activate IO Matrix keyboard, then return here.")
            values.groupBy { it.owner }.forEach { (owner, entries) ->
                Text(owner, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                entries.forEach { value ->
                    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value.label, style = MaterialTheme.typography.titleSmall)
                            Text("${value.id} = ${value.value}")
                            Text(value.description, style = MaterialTheme.typography.bodySmall)
                            if (value.writable) TextButton(onClick = { editing = value; next = value.value }) { Text("Edit runtime value") }
                            else Text("Read-only", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status)
    }
    editing?.let { value ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(value.label) }, text = {
            Column {
                Text(value.description)
                when (val rule = value.rule) {
                    VariableRule.BooleanValue -> Row {
                        listOf("true", "false").forEach { option -> TextButton(onClick = { next = option }) { Text(if (next == option) "✓ $option" else option) } }
                    }
                    is VariableRule.Choice -> rule.values.sorted().forEach { option -> TextButton(onClick = { next = option }) { Text(if (next == option) "✓ $option" else option) } }
                    else -> OutlinedTextField(value = next, onValueChange = { next = it.take(256) }, label = { Text("Value") }, singleLine = true)
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                // The caller passes current level eligibility, never a forged editable descriptor.
                status = RuntimeVariables.registry.write(value, next,
                    allowed && SettingsHierarchy.level(SettingsStore.current) == SettingsLevel.DEBUGGER).fold(
                    onSuccess = { "Runtime value updated. It is not a saved setting." },
                    onFailure = { it.message ?: "Runtime change refused." })
                editing = null
            }) { Text("Apply to this session") }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
