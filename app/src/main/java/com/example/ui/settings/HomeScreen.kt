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
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalContext
import com.example.MainActivity
import com.example.core.layout.LayoutRepository
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

        // What state is this actually in? Two naked buttons could not say, and the
        // answer decides which of them is worth pressing.
        val context = LocalContext.current
        val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
        val enabled = remember(imm) {
            imm?.enabledInputMethodList.orEmpty().any { it.packageName == context.packageName }
        }
        val selected = remember(imm, enabled) {
            runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
                )?.startsWith(context.packageName) == true
            }.getOrDefault(false)
        }

        SettingsSection(
            title = if (enabled && selected) "Ready" else "Get started",
            subtitle = when {
                !enabled -> "Not switched on in the system yet. That is step one."
                !selected -> "Switched on, but another keyboard is still the active one."
                else -> "Switched on and in use. Everything below is optional."
            }
        ) {
            StatusRow("Enabled in system settings", enabled)
            StatusRow("Currently the active keyboard", selected)
            Divider()
            StatusRow(
                "Layout: ${LayoutRepository.byId(settings.activeLayoutId)?.name ?: settings.activeLayoutId}",
                true
            )
            StatusRow(
                if (settings.aiEnabled) "AI on · ${settings.aiModel.ifBlank { "no model set" }}" else "AI off",
                settings.aiEnabled
            )

            Column(Modifier.padding(16.dp)) {
                if (!enabled) {
                    Button(onClick = onEnableKeyboard, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn the keyboard on in system settings")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!selected) {
                    Button(onClick = onChooseKeyboard, modifier = Modifier.fillMaxWidth()) {
                        Text(if (enabled) "Switch to it" else "…then switch to it")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text("Try it here") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        }

        SettingsSection(
            title = "How it looks",
            subtitle = "Tap to change size, transparency, colours."
        ) {
            KeyboardPreview(settings, heightDp = 130)
            ActionRow(
                "Size, shape & theme",
                "Height, one-handed and split modes, see-through keys, haptics",
                onClick = { onNavigate(MainActivity.ROUTE_APPEARANCE) }
            )
        }

        SettingsSection("Settings") {
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
                "Set up AI, dictation and the rest",
                "The guide that runs on a fresh install. Safe to run again — it changes " +
                    "nothing until you pick something.",
                onClick = { onNavigate(MainActivity.ROUTE_SETUP) }
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
