package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingSpec
import com.example.core.config.Settings
import com.example.core.config.SettingsProfile
import com.example.core.config.SettingsProfiles
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.config.SettingsOwner

/** Every persisted control, including new knobs, remains accessible without a UI registry. */
@Composable
fun AllSettingsScreen(settings: Settings, initialQuery: String = "", onNavigate: ((String) -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navigate = onNavigate ?: { route: String -> SettingsNavigation.open(context, route) }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var onlyChanged by rememberSaveable { mutableStateOf(false) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var resetKeys by remember { mutableStateOf<List<String>?>(null) }
    var ownerName by rememberSaveable { mutableStateOf("ALL") }
    var error by remember { mutableStateOf<String?>(null) }
    val json = remember(settings) { SettingsStore.toJson(settings) }
    val owner = SettingsOwner.entries.firstOrNull { it.name == ownerName }
    val matches = remember(query, onlyChanged, settings, ownerName) {
        SettingsHierarchy.visible(settings, query, owner).filter { !onlyChanged || !SettingsProfiles.equivalent(json.opt(it.key), it.default) }
    }
    val hidden = SettingsHierarchy.hidden(settings, query, owner).filter { !onlyChanged || !SettingsProfiles.equivalent(json.opt(it.key), it.default) }

    LazyColumn {
        item("search") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search every setting") },
                    placeholder = { Text("pocket, opacity, repeat…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${matches.size} visible settings · ${SettingsHierarchy.level(settings).title} · ${hidden.size} at higher levels",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Row {
                    TextButton(onClick = { onlyChanged = !onlyChanged }) { Text(if (onlyChanged) "Show all" else "Only modified") }
                    TextButton(onClick = { showProfiles = !showProfiles }) { Text(if (showProfiles) "Hide profiles" else "Profiles & import") }
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                }
            }
            SettingsLevelSelector(settings, navigate)
            ChoiceRow("Owner", description = "Browsing and resets apply to this selected owner. Layout/panel/key overrides are edited in Layouts.",
                options = listOf("ALL") + SettingsOwner.entries.map { it.name }, selected = ownerName,
                optionLabel = { name -> SettingsOwner.entries.firstOrNull { it.name == name }?.title ?: "All owners" }, onSelect = { ownerName = it })
            ActionRow("Selected layout, panel or key", "Edit local properties and inheritance of an explicit instance", onClick = { navigate("layouts") })
            if (query.isNotBlank() && hidden.isNotEmpty()) ActionRow("Show ${hidden.size} additional matches in Expert",
                "${hidden.take(3).joinToString { it.label }}. Existing values remain intact.", onClick = {
                    SettingsStore.update { SettingsHierarchy.selectLevel(it, SettingsLevel.EXPERT) }
                })
        }
        if (showProfiles) item("profiles") { SettingsProfilesPanel(settings, matches.map { it.key }) }
        if (matches.isEmpty()) item("empty") { InfoRow("No matching settings. Try a shorter name, such as lock, cursor or voice.") }
        matches.groupBy { it.group }.forEach { (group, specs) ->
            item("group:$group") { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
            items(specs, key = { "setting:${it.key}" }) { spec ->
                ExpertSetting(spec, settings)
                Divider()
            }
        }
        item("reset") {
            ActionRow("Reset visible selection", "Reset exactly these ${matches.size} shared settings. Local layout/panel/key overrides, dictionary and clipboard are kept.",
                onClick = { resetKeys = matches.map { it.key }.filterNot { it == "settingsLevel" || it == "expertMode" } })
            error?.let { InfoRow(it) }
        }
    }
    resetKeys?.let { keys -> AlertDialog(
        onDismissRequest = { resetKeys = null }, title = { Text("Reset ${keys.size} shared settings?") },
        text = { Text("The reviewed selection is fixed: ${keys.take(8).joinToString()}${if (keys.size > 8) ", …" else ""}. Included credentials are cleared. Local overrides, profiles, layouts, dictionary and clipboard remain.") },
        confirmButton = { TextButton(onClick = {
            SettingsStore.update { SettingsHierarchy.reset(it, keys).getOrThrow() }.onFailure { error = it.message }
            resetKeys = null
        }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { resetKeys = null }) { Text("Cancel") } }
    ) }
}

@Composable
private fun ExpertSetting(spec: SettingSpec, settings: Settings) {
    InfoRow("${spec.owner.title} · " + if (spec.applicableScopes.size > 1) "shared default; local overrides: ${spec.applicableScopes.filter { it != com.example.core.config.SettingsScope.KEYBOARD_DEFAULTS }.joinToString { it.title }}"
        else spec.owner.explanation)
    SettingControl(spec, settings)
    var error by remember(spec.key) { mutableStateOf<String?>(null) }
    val modified = !SettingsProfiles.equivalent(SettingsSchema.valueOf(settings, spec.key), spec.default)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(spec.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (modified) TextButton(onClick = { runCatching { SettingsStore.resetKey(spec.key).getOrThrow() }.onFailure { error = it.message } }) { Text("Reset") }
    }
    error?.let { InfoRow(it) }
}

@Composable
private fun SettingsProfilesPanel(settings: Settings, visibleKeys: List<String>) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val profiles = remember(revision) { SettingsProfiles.all() }
    var name by rememberSaveable { mutableStateOf("") }
    var importText by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<SettingsProfile?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Settings?>(null) }
    var appliedSnapshot by remember { mutableStateOf<Settings?>(null) }
    SettingsSection("Settings profiles", "Profiles change only their listed options. Review the changes before applying.") {
        SettingsProfiles.loadWarnings.forEach { InfoRow(it) }
        profiles.forEach { profile ->
            ActionRow(profile.name, profile.description, onClick = { preview = profile; message = null })
        }
        TextRow("Profile name", value = name, onChange = { name = it })
        ActionRow("Save current selection as profile", "Stores the ${visibleKeys.size} settings currently found by search; credentials and private payloads are omitted.", onClick = {
            scope.launch {
                runCatching { SettingsProfiles.save(SettingsProfiles.capture(name, settings, visibleKeys)) }
                    .onSuccess { revision++; name = ""; message = "Profile saved." }.onFailure { message = it.message }
            }
        })
        ActionRow("Export current selection", "Credentials, account definitions, private text and automation payloads stay on this device.", onClick = {
            runCatching { SettingsProfiles.encode(SettingsProfiles.capture(name.ifBlank { "My settings" }, settings, visibleKeys)).toString(2) }
                .onSuccess { exported = it }.onFailure { message = it.message }
        })
        exported?.let { TextRow("Portable profile JSON", value = it, singleLine = false, minLines = 6, onChange = {}) }
        TextRow("Paste profile JSON", value = importText, singleLine = false, minLines = 3, onChange = { importText = it })
        ActionRow("Preview import", "Unknown keys, invalid values and credential fields are rejected before any setting changes.", onClick = {
            SettingsProfiles.parseImport(importText).onSuccess { preview = it; message = null }.onFailure { message = it.message }
        })
        preview?.let { profile ->
            val changed = SettingsProfiles.changedKeys(settings, profile)
            InfoRow("${profile.name}: ${changed.size} changes, ${profile.values.length()} options in scope.")
            changed.forEach { key ->
                val spec = SettingsSchema.spec(key)
                val before = SettingsSchema.valueOf(settings, key)?.toString().orEmpty().take(100)
                val after = profile.values.opt(key)?.toString().orEmpty().take(100)
                InfoRow("${spec?.label ?: key}: $before → $after")
            }
            Row {
                TextButton(onClick = {
                    scope.launch {
                        var applied = false
                        runCatching {
                            val before = SettingsStore.current
                            SettingsStore.update { current -> SettingsProfiles.apply(current, profile).getOrThrow() }.getOrThrow()
                            applied = true
                            undo = before
                            appliedSnapshot = SettingsStore.current
                            SettingsStore.awaitPersistence().getOrThrow()
                        }.onSuccess {
                            preview = null
                            message = "Applied and saved ${profile.name}."
                        }.onFailure {
                            message = if (applied) "Applied in memory; not saved: ${it.message}" else "Nothing applied: ${it.message}"
                        }
                    }
                }) { Text("Apply profile") }
                if (!profile.builtIn) TextButton(onClick = {
                    scope.launch {
                        runCatching { SettingsProfiles.save(profile) }.onSuccess { revision++; message = "Saved." }.onFailure { message = it.message }
                    }
                }) { Text("Save") }
                if (!profile.builtIn && profiles.any { it.id == profile.id }) TextButton(onClick = {
                    scope.launch {
                        runCatching { SettingsProfiles.delete(profile.id) }
                            .onSuccess { revision++; preview = null }.onFailure { message = it.message }
                    }
                }) { Text("Delete") }
                TextButton(onClick = { preview = null }) { Text("Cancel") }
            }
        }
        if (undo != null && settings == appliedSnapshot) ActionRow("Undo last profile", onClick = {
            runCatching { SettingsStore.replaceAll(undo!!).getOrThrow() }.onSuccess { undo = null; appliedSnapshot = null; message = "Profile undone." }
                .onFailure { message = it.message }
        })
        message?.let { InfoRow(it) }
    }
}
