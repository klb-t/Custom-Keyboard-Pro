package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.MainActivity
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import com.example.core.panels.PanelGenerator
import com.example.util.AppLogger

/**
 * The first screen.
 *
 * Two buttons to make the keyboard usable, a field to try it in, and the way into
 * everything else. The expert switch at the bottom is the app's central promise: the
 * defaults are complete, and nothing is hidden from anyone who wants it.
 */
@Composable
fun HomeScreen(
    settings: Settings,
    onNavigate: (String) -> Unit,
    onEnableKeyboard: () -> Unit,
    onChooseKeyboard: () -> Unit
) {
    var testText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Get started",
            subtitle = "Two steps. Everything after this is optional."
        ) {
            Column(Modifier.padding(16.dp)) {
                Button(onClick = onEnableKeyboard, modifier = Modifier.fillMaxWidth()) {
                    Text("1 · Turn the keyboard on in system settings")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onChooseKeyboard, modifier = Modifier.fillMaxWidth()) {
                    Text("2 · Switch to it")
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text("Try it here") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        }

        SettingsSection("Settings") {
            ActionRow(
                "Size, shape & theme",
                "Height, one-handed and split modes, colours, haptics",
                onClick = { onNavigate(MainActivity.ROUTE_APPEARANCE) }
            )
            Divider()
            ActionRow(
                "Typing",
                "Capitalisation, punctuation, gestures, suggestions, clipboard",
                onClick = { onNavigate(MainActivity.ROUTE_TYPING) }
            )
            Divider()
            ActionRow(
                "Layouts",
                "Choose, edit, build from a picture, or describe one in words",
                trailing = "${settings.enabledLayoutIds.size}",
                onClick = { onNavigate(MainActivity.ROUTE_LAYOUTS) }
            )
            Divider()
            ActionRow(
                "AI",
                if (settings.aiEnabled) "On · ${settings.aiModel.ifBlank { "no model set" }}" else "Off",
                onClick = { onNavigate(MainActivity.ROUTE_AI) }
            )
            Divider()
            ActionRow(
                "Dictation",
                "Speech to text, with alternatives you pick from",
                onClick = { onNavigate(MainActivity.ROUTE_VOICE) }
            )
            Divider()
            ActionRow(
                "Dictionary & shortcuts",
                "What the keyboard has learned, and text expansions",
                onClick = { onNavigate(MainActivity.ROUTE_DICTIONARY) }
            )
            Divider()
            ActionRow(
                "About & help",
                "Compose sequences, key reference, import and export",
                onClick = { onNavigate(MainActivity.ROUTE_ABOUT) }
            )
            Divider()
            ActionRow(
                "Theme editor",
                "Every colour, with opacity — including keys you can see through",
                onClick = { onNavigate(MainActivity.ROUTE_THEME_EDITOR) }
            )
            Divider()
            ActionRow(
                "Diagnostics",
                "The keyboard's own log — for when something goes wrong and there is " +
                    "no computer to plug it into",
                trailing = AppLogger.logs.count { it.contains(" CRASH:") }
                    .let { if (it > 0) "$it crash" + (if (it == 1) "" else "es") else null },
                onClick = { onNavigate(MainActivity.ROUTE_DIAGNOSTICS) }
            )
        }

        SettingsSection(
            title = "Anything not on a screen yet",
            subtitle = "The settings above are arranged by hand. These two reach the " +
                "rest: all of them at once, or a panel built for what you actually " +
                "want to change."
        ) {
            ActionRow(
                "Ask for a panel",
                "Say what you want to change; a model builds the panel for it",
                onClick = { onNavigate(MainActivity.ROUTE_REQUEST_PANEL) }
            )
            Divider()
            ActionRow(
                "Every setting",
                "All ${SettingsSchema.all.size} of them, searchable, nothing hidden",
                trailing = "${SettingsSchema.all.size}",
                onClick = { onNavigate(MainActivity.ROUTE_ALL_SETTINGS) }
            )
            val panels = remember(settings.generatedPanelsJson) { PanelGenerator.saved(settings) }
            panels.forEach { panel ->
                Divider()
                ActionRow(
                    panel.title,
                    panel.description ?: "Built for: ${panel.request.take(60)}",
                    trailing = "${panel.controls.size}",
                    onClick = { onNavigate(MainActivity.ROUTE_PANEL_PREFIX + panel.id) }
                )
            }
        }

        SettingsSection(
            title = "Expert mode",
            subtitle = "Off, every screen shows the settings most people change. On, it " +
                "shows all of them — including the ones that can make the keyboard behave " +
                "oddly. Nothing is removed either way."
        ) {
            SwitchRow(
                label = "Show every setting",
                description = if (settings.expertMode) "Everything is visible." else null,
                checked = settings.expertMode,
                onChange = { on -> SettingsStore.update { it.copy(expertMode = on) } }
            )
            Divider()
            ActionRow(
                "Reset everything to defaults",
                "Layouts, dictionary and clipboard are kept",
                onClick = { SettingsStore.resetToDefaults() }
            )
        }

        Text(
            "Nothing here is required. The keyboard works fully on its defaults; the " +
                "settings exist so it can work the way you want instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
