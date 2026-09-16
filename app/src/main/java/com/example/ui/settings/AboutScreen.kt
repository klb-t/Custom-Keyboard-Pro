package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingsStore
import com.example.ime.ComposeSequences
import com.example.core.text.TextOps

/**
 * Reference and escape hatches.
 *
 * The compose-sequence table is here because a feature nobody can discover is a
 * feature nobody has; the settings export is here because configuration you cannot
 * take with you is configuration you are renting.
 */
@Composable
fun AboutScreen() {
    var exported by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }
    var showCompose by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection("What this keyboard does that others do not") {
            InfoRow(
                "• Layouts are data, not code. Anything a built-in layout does, a layout " +
                    "you make can do.\n" +
                    "• Keys have eight swipe directions, double-tap, long-press, hold-repeat " +
                    "and chords — all bindable to anything.\n" +
                    "• Modifiers are real: momentary, one-shot, toggle or locked, with lamps " +
                    "that show which.\n" +
                    "• Raw key events, so Ctrl+C, Tab, Esc and the function row reach the app.\n" +
                    "• Dead keys, X11 compose sequences and hex Unicode entry.\n" +
                    "• A layout can be traced over a bitmap, with keys of any shape anywhere.\n" +
                    "• Dictation shows the alternatives and lets you choose.\n" +
                    "• The model, if you configure one, is yours: any OpenAI-compatible " +
                    "endpoint, Anthropic, Gemini, or a server on your own machine."
            )
        }

        SettingsSection(
            title = "Compose sequences",
            subtitle = "Bind a key to the compose action, then press it followed by two " +
                "ordinary keys."
        ) {
            ActionRow(
                if (showCompose) "Hide the table" else "Show the table",
                "${ComposeSequences.all().size} sequences",
                onClick = { showCompose = !showCompose }
            )
            if (showCompose) {
                Text(
                    ComposeSequences.all().joinToString("   ") { "${it.first} → ${it.second}" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        SettingsSection(
            title = "Dead keys",
            subtitle = "Bind one to a key, press it, then press a letter."
        ) {
            Text(
                TextOps.DEAD_KEYS.joinToString("   ") { it.first },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            InfoRow("Acute, grave, circumflex, diaeresis, tilde, caron, breve, ring, " +
                "cedilla, ogonek, macron, dot above and stroke.")
        }

        SettingsSection(
            title = "Settings backup",
            subtitle = "Everything on every screen, as JSON. Layouts, the dictionary and " +
                "the clipboard are stored separately and are not included."
        ) {
            ActionRow(
                "Export settings",
                "Shows the JSON so you can copy it",
                onClick = { exported = SettingsStore.exportJson() }
            )
            exported?.let {
                TextRow(
                    label = "Exported settings",
                    value = it,
                    singleLine = false,
                    minLines = 8,
                    onChange = { }
                )
            }
            Divider()
            TextRow(
                label = "Paste settings to import",
                value = importText,
                singleLine = false,
                minLines = 4,
                onChange = {
                    importText = it
                    importResult = null
                }
            )
            ActionRow(
                "Import",
                "Replaces every setting",
                onClick = {
                    importResult = SettingsStore.importJson(importText).fold(
                        onSuccess = { "Imported." },
                        onFailure = { "Could not read that: ${it.message}" }
                    )
                }
            )
            importResult?.let { InfoRow(it) }
        }

        SettingsSection("Privacy") {
            InfoRow(
                "Nothing leaves this device unless you configure a provider and switch " +
                    "the feature on. The dictionary, the clipboard history and your " +
                    "settings are stored in the app's own storage and are never uploaded.\n\n" +
                    "In a password field — or any field whose app asks not to be " +
                    "personalised — the keyboard makes no suggestions, learns nothing, " +
                    "records no clipboard entries and sends nothing to a model, whatever " +
                    "else is switched on."
            )
        }
    }
}
