package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.panels.PanelGenerator
import com.example.core.panels.PanelSpec
import kotlinx.coroutines.launch

/**
 * The settings screen that did not exist until someone asked for it.
 *
 * The user says what they want to be able to change; a model picks the settings that
 * answer it and arranges them; the renderer here — ordinary, hardcoded, the same one
 * every other screen uses — draws the result. The app grows a screen without growing
 * any code.
 *
 * The part worth being strict about is what happens when the answer is "you can't".
 * A generated panel is only allowed to name settings this build actually has; ones it
 * invents are dropped before rendering and reported to the user by name. A panel that
 * looks real and does nothing would be worse than no panel at all.
 */
@Composable
fun RequestPanelScreen(settings: Settings) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PanelGenerator.Outcome?>(null) }
    val saved = remember(settings.generatedPanelsJson) { PanelGenerator.saved(settings) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Ask for a panel",
            subtitle = "Describe what you want to be able to change. A model picks the " +
                "settings that answer it and builds you a panel for exactly those."
        ) {
            TextRow(
                label = "What would you like to change?",
                placeholder = "e.g. make the keyboard see-through over photos, " +
                    "or everything about how long a long press takes",
                value = request,
                singleLine = false,
                minLines = 3,
                onChange = { request = it }
            )
            if (!settings.aiEnabled || settings.aiApiKey.isBlank()) {
                InfoRow(
                    "This needs a model configured — AI screen, your own provider and key. " +
                        "Nothing is sent anywhere until you set one up."
                )
            }
            Button(
                onClick = {
                    busy = true
                    error = null
                    preview = null
                    scope.launch {
                        PanelGenerator.generate(request, settings).fold(
                            onSuccess = { preview = it },
                            onFailure = { error = it.message ?: "The model could not be reached." }
                        )
                        busy = false
                    }
                },
                enabled = !busy && request.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(if (busy) "Building…" else "Build the panel")
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            error?.let { InfoRow("Could not build it: $it") }
        }

        preview?.let { outcome ->
            val panel = outcome.panel
            SettingsSection(
                title = "Preview · ${panel.title}",
                subtitle = panel.description
            ) {
                if (panel.controls.isEmpty()) {
                    InfoRow(
                        panel.note
                            ?: "The model found no setting in this build that answers that. " +
                            "Nothing was built, which is the honest outcome — a panel of " +
                            "loosely related controls would not have done what you asked."
                    )
                } else {
                    panel.controls.forEachIndexed { index, control ->
                        if (index > 0) Divider()
                        SettingsSchema.spec(control.key)?.let { spec ->
                            SettingControl(
                                spec = spec,
                                settings = settings,
                                labelOverride = control.label,
                                helpOverride = control.help
                            )
                        }
                    }
                    panel.note?.let {
                        Divider()
                        InfoRow("The model's own note: $it")
                    }
                }
                if (outcome.droppedKeys.isNotEmpty()) {
                    Divider()
                    InfoRow(
                        "Dropped, because this build has no such setting: " +
                            outcome.droppedKeys.joinToString(", ") +
                            ". The controls above are the ones that really work."
                    )
                }
                if (panel.controls.isNotEmpty()) {
                    Divider()
                    ActionRow(
                        "Keep this panel",
                        "It gets its own entry on the home screen",
                        onClick = {
                            PanelGenerator.save(panel)
                            preview = null
                            request = ""
                        }
                    )
                }
            }
        }

        if (saved.isNotEmpty()) {
            SettingsSection(
                title = "Your panels",
                subtitle = "Open them from the home screen, where they sit alongside " +
                    "the hand-built ones."
            ) {
                saved.forEach { panel ->
                    InfoRow("${panel.title} — ${panel.controls.size} settings · ${panel.request.take(70)}")
                }
            }
        }

        SettingsSection("How this works, and what it cannot do") {
            InfoRow(
                "The model is given the complete list of settings this build has — " +
                    "${SettingsSchema.all.size} of them, with their types, ranges and " +
                    "current values — and asked which ones answer your request. It " +
                    "arranges them; it does not invent them.\n\n" +
                    "So a panel can reach anything the keyboard can already do, including " +
                    "things no hand-written screen puts together. What it cannot do is add " +
                    "behaviour that does not exist yet: if you ask for something with no " +
                    "setting behind it, you get told so by name rather than given a control " +
                    "that quietly does nothing."
            )
        }
    }
}

/** One saved panel, rendered on its own. */
@Composable
fun GeneratedPanelScreen(panel: PanelSpec, settings: Settings, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SettingsSection(title = panel.title, subtitle = panel.description) {
            panel.controls.forEachIndexed { index, control ->
                if (index > 0) Divider()
                SettingsSchema.spec(control.key)?.let { spec ->
                    SettingControl(
                        spec = spec,
                        settings = settings,
                        labelOverride = control.label,
                        helpOverride = control.help
                    )
                }
            }
        }
        panel.note?.let {
            SettingsSection("Note from the model that built this") { InfoRow(it) }
        }
        SettingsSection("This panel") {
            InfoRow("Asked for: \"${panel.request}\"")
            Divider()
            Text(
                "Built from settings that already existed — deleting it removes the " +
                    "panel, never the settings or their values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ActionRow("Delete this panel", onClick = onDelete)
        }
    }
}
