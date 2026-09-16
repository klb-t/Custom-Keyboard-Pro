package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore

/**
 * Every setting there is, without a line of code per setting.
 *
 * The hand-built screens are still there and still better for the settings most
 * people actually change — they group things by intent, explain trade-offs, and put
 * a preview next to the slider. This one exists for the other promise: that nothing
 * is hidden, including settings added after this screen was written, and that you
 * can find any of them by typing part of its name.
 */
@Composable
fun AllSettingsScreen(settings: Settings) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { SettingsSchema.search(query) }
    val grouped = matches.groupBy { it.group }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search every setting") },
                placeholder = { Text("opacity, cursor, repeat…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${matches.size} of ${SettingsSchema.all.size} settings" +
                    if (settings.expertMode) "" else " · expert-only ones are marked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (matches.isEmpty()) {
            SettingsSection("Nothing matched") {
                InfoRow(
                    "No setting has that in its name or description. If what you want " +
                        "isn't here at all, the \"Ask for a panel\" screen can build one — " +
                        "and will tell you plainly when the thing you asked for has no " +
                        "setting behind it."
                )
            }
        }

        grouped.forEach { (group, specs) ->
            SettingsSection(
                title = group,
                subtitle = "${specs.size} setting${if (specs.size == 1) "" else "s"}"
            ) {
                specs.forEachIndexed { index, spec ->
                    if (index > 0) Divider()
                    SettingControl(
                        spec = spec,
                        settings = settings,
                        labelOverride = if (spec.expert) "${spec.label}  ·  expert" else null
                    )
                }
            }
        }

        SettingsSection("Reset") {
            ActionRow(
                "Reset everything to defaults",
                "Layouts, dictionary and clipboard are kept",
                onClick = { SettingsStore.resetToDefaults() }
            )
        }
    }
}
