package com.example.ui.settings

import androidx.compose.runtime.Composable
import com.example.core.config.Settings
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.config.SettingsStore

/** Hand-authored screens are projections of shared settings, never owners of local instances. */
@Composable
fun SharedSettingsScopeNotice(settings: Settings, mixedApplicationOptions: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    InfoRow(if (mixedApplicationOptions) "These controls edit shared configuration. Applicable typing/feedback options are keyboard defaults; clipboard/privacy services remain application-wide."
        else "These controls edit shared keyboard defaults. Local layout, panel and key properties are edited on an explicitly selected instance.")
    ActionRow("Local properties · ${com.example.core.layout.LayoutRepository.byId(settings.activeLayoutId)?.name ?: settings.activeLayoutId}",
        "Inspect inheritance and edit a selected layout, panel or key", onClick = { SettingsNavigation.open(context, "layouts") })
}

@Composable
fun SettingsLevelSelector(settings: Settings, onNavigate: ((String) -> Unit)? = null) {
    val current = SettingsHierarchy.level(settings)
    ChoiceRow("Settings level", description = current.explanation,
        options = SettingsLevel.entries.map { it.name }, selected = current.name,
        optionLabel = { raw -> SettingsLevel.valueOf(raw).title },
        onSelect = { raw -> SettingsStore.update { SettingsHierarchy.selectLevel(it, SettingsLevel.valueOf(raw)) } })
    if (current == SettingsLevel.DEBUGGER && onNavigate != null) ActionRow("Runtime debugger", "Inspect published runtime state separately from saved configuration",
        onClick = { onNavigate("debugger") })
}

/** Standard keyboard settings contain only the explicitly curated Basic projection. */
@Composable
fun BasicKeyboardSettingsScreen(settings: Settings, domain: String) {
    val keys = if (domain == "appearance") listOf("themeId", "presentation", "heightPortrait", "heightLandscape", "keyTextScale", "hapticEnabled", "soundEnabled", "soundVolume")
        else listOf("autoCapitalize", "doubleSpacePeriod", "autoCorrect", "suggestionsEnabled", "learnFromTyping", "clipboardEnabled", "showOnHardwareKeyboard")
    androidx.compose.foundation.lazy.LazyColumn {
        item { InfoRow(if (domain == "appearance") "Keyboard defaults · shared size, theme and feedback. A selected layout's local properties are edited in Layouts."
            else "Everyday typing · these defaults apply unless a selected layout, panel or key overrides an applicable option.") }
        items(keys.size) { index -> com.example.core.config.SettingsSchema.spec(keys[index])?.let { SettingControl(it, settings); Divider() } }
        item { SettingsLevelSelector(settings) }
    }
}
