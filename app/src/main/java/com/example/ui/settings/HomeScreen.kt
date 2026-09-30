package com.example.ui.settings

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.core.config.Settings
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.config.SettingsSchema
import com.example.core.layout.LayoutRepository
import com.example.core.panels.PanelGenerator

/** Basic is a standard keyboard surface; deeper levels add tools without changing behavior. */
@Composable
fun HomeScreen(settings: Settings, onNavigate: (String) -> Unit, onEnableKeyboard: () -> Unit, onChooseKeyboard: () -> Unit) {
    var testText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val level = SettingsHierarchy.level(settings)
    val advanced = level.includes(SettingsLevel.ADVANCED)
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
    val enabled = remember(imm) { imm?.enabledInputMethodList.orEmpty().any { it.packageName == context.packageName } }
    val selected = remember(imm, enabled) { runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(context.packageName) == true
    }.getOrDefault(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SettingsSection(if (enabled && selected) "Ready" else "Get started") {
            StatusRow("Enabled in system settings", enabled)
            StatusRow("Currently the active keyboard", selected)
            StatusRow("Layout: ${LayoutRepository.byId(settings.activeLayoutId)?.name ?: settings.activeLayoutId}", true)
            Column(Modifier.padding(16.dp)) {
                if (!enabled) {
                    Button(onClick = onEnableKeyboard, modifier = Modifier.fillMaxWidth()) { Text("Enable the keyboard") }
                    Spacer(Modifier.height(8.dp))
                }
                if (!selected) {
                    Button(onClick = onChooseKeyboard, modifier = Modifier.fillMaxWidth()) { Text("Choose this keyboard") }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(value = testText, onValueChange = { testText = it }, label = { Text("Try it here") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
        SettingsSection("Settings level", "Levels change visibility. Saved values, local overrides and permissions keep their meaning.") {
            SettingsLevelSelector(settings, onNavigate)
        }
        SettingsSection("Keyboard") {
            ActionRow("Size, shape & theme", "Shared keyboard defaults: size, theme and feedback",
                onClick = { onNavigate(MainActivity.ROUTE_APPEARANCE) })
            Divider()
            ActionRow("Typing", "Capitalisation, punctuation, correction and suggestions",
                onClick = { onNavigate(MainActivity.ROUTE_TYPING) })
            Divider()
            ActionRow("Layouts", if (advanced) "Choose a layout; edit its panels, keys and local inheritance" else "Choose the layouts you use",
                onClick = { onNavigate(MainActivity.ROUTE_LAYOUTS) })
            Divider()
            ActionRow("Dictation", "Speech input and language", onClick = { onNavigate(MainActivity.ROUTE_VOICE) })
            Divider()
            ActionRow("Dictionary & shortcuts", "Personal words and text expansions", onClick = { onNavigate(MainActivity.ROUTE_DICTIONARY) })
        }
        if (advanced) {
            SettingsSection("Phone tools") {
                ActionRow("Goal assistant", "Plan and review phone actions from a spoken or written goal",
                    onClick = { context.startActivity(android.content.Intent(context, com.example.assistant.GoalAssistantActivity::class.java)) })
                Divider()
                ActionRow("Device sync", "Encrypted clipboard, images and portable settings",
                    onClick = { context.startActivity(android.content.Intent(context, com.example.sync.DeviceSyncActivity::class.java)) })
                Divider()
                ActionRow("Files & cloud media", "Local files and connected storage providers", onClick = { onNavigate(MainActivity.ROUTE_MEDIA) })
                Divider()
                ActionRow("Passwords & cards", "Authenticated vault and autofill", onClick = { onNavigate(MainActivity.ROUTE_VAULT) })
                Divider()
                ActionRow("Phone capabilities", "Availability, required access and missing adapters", onClick = { onNavigate(MainActivity.ROUTE_CAPABILITIES) })
            }
            SettingsSection("Configuration tools") {
                ActionRow("AI", "Capability/model-first provider setup", onClick = { onNavigate(MainActivity.ROUTE_AI) })
                Divider()
                ActionRow("Set up AI, dictation and the rest", "The optional setup guide", onClick = { onNavigate(MainActivity.ROUTE_SETUP) })
                Divider()
                ActionRow("Theme editor", "Edit the theme profile used by the keyboard defaults", onClick = { onNavigate(MainActivity.ROUTE_THEME_EDITOR) })
                Divider()
                ActionRow("Ask for a panel", "Build a projection of existing settings for what you want to change", onClick = { onNavigate(MainActivity.ROUTE_REQUEST_PANEL) })
                PanelGenerator.saved(settings).forEach { panel ->
                    Divider()
                    ActionRow(panel.title, "Settings view · ${panel.controls.size} controls. This view does not own their values.",
                        onClick = { onNavigate(MainActivity.ROUTE_PANEL_PREFIX + panel.id) })
                }
            }
        }
        SettingsSection("Find an option") {
            ActionRow("Settings explorer", "Search all ${SettingsSchema.all.size} canonical settings; matching options at higher levels are indicated",
                onClick = { onNavigate(MainActivity.ROUTE_ALL_SETTINGS) })
            Divider()
            ActionRow("About & help", "Key reference, profiles and product scope", onClick = { onNavigate(MainActivity.ROUTE_ABOUT) })
            if (advanced) {
                Divider()
                ActionRow("Diagnostics", "Keyboard log and storage diagnostics", onClick = { onNavigate(MainActivity.ROUTE_DIAGNOSTICS) })
            }
        }
    }
}
