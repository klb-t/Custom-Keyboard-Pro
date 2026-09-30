package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.ScopedSettingsResolver
import com.example.core.config.Settings
import com.example.core.config.SettingsAddress
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsScope
import com.example.core.config.SettingsProfiles
import com.example.core.config.SettingsProfile
import com.example.core.layout.LayoutDef
import kotlinx.coroutines.launch

/** All edits stay in this layout draft until Save; global defaults are never mutated. */
@Composable
fun LayoutInstanceSettingsDialog(layout: LayoutDef, settings: Settings, onDismiss: () -> Unit, onSave: (LayoutDef) -> Result<Unit>) {
    var draft by remember(layout.id) { mutableStateOf(layout) }
    var scope by remember(layout.id) { mutableStateOf(SettingsScope.LAYOUT) }
    var panelId by remember(layout.id) { mutableStateOf("") }
    var layerName by remember(layout.id) { mutableStateOf(layout.defaultLayer) }
    var keyId by remember(layout.id) { mutableStateOf("") }
    var query by remember(layout.id) { mutableStateOf("") }
    var error by remember(layout.id) { mutableStateOf<String?>(null) }
    var profileName by remember(layout.id) { mutableStateOf("") }
    var profilePreview by remember(layout.id) { mutableStateOf<SettingsProfile?>(null) }
    var profilesOpen by remember(layout.id) { mutableStateOf(false) }
    var profileRevision by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val layer = draft.layers[layerName] ?: draft.base
    val address = when (scope) {
        SettingsScope.LAYOUT -> SettingsAddress(draft.id)
        SettingsScope.PANEL -> panelId.takeIf { id -> draft.elements.any { it.id == id } }?.let { SettingsAddress(draft.id, it) }
        SettingsScope.KEY -> keyId.takeIf { id -> layer.allKeys.count { it.id == id } == 1 }?.let {
            SettingsAddress(draft.id, panelId.takeIf(String::isNotBlank), layer.name, it)
        }
        SettingsScope.KEYBOARD_DEFAULTS -> null
    }
    val applicable = SettingsSchema.search(query).filter { scope in it.applicableScopes }
    val visible = applicable.filter { SettingsHierarchy.level(settings).includes(it.minimumLevel) }
    val hiddenCount = applicable.size - visible.size

    AlertDialog(onDismissRequest = onDismiss, title = { Text("${layout.name} · local settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            InfoRow("Priority: key > panel > layout > keyboard defaults. Inherit removes this instance's value. Saved overrides remain when changing settings level.")
            InfoRow("Policy: ${prettyOption(settings.localSettingsPolicy.name)}. Suppressed overrides are retained and shown below.")
            SettingsLevelSelector(settings)
            ChoiceRow("Edit scope", options = listOf(SettingsScope.LAYOUT.name, SettingsScope.PANEL.name, SettingsScope.KEY.name), selected = scope.name,
                optionLabel = { SettingsScope.valueOf(it).title }, onSelect = { scope = SettingsScope.valueOf(it); error = null })
            if (scope != SettingsScope.LAYOUT && draft.elements.isNotEmpty()) ChoiceRow("Panel instance",
                description = "Keys inherit from this selected panel. Choose no panel to inspect layout inheritance only.",
                options = listOf("") + draft.elements.map { it.id }, selected = panelId,
                optionLabel = { if (it.isBlank()) "No panel" else it }, onSelect = { id ->
                    panelId = id
                    draft.elements.firstOrNull { it.id == id }?.let { layerName = it.layer; keyId = "" }
                })
            if (scope == SettingsScope.PANEL && draft.elements.isEmpty()) InfoRow("This layout is one implicit panel. Its options belong to the Layout scope. Add explicit elements in the layout editor to give panels their own settings.")
            if (scope == SettingsScope.KEY) {
                if (panelId.isBlank()) ChoiceRow("Layer", options = draft.layers.keys.toList(), selected = layerName,
                    optionLabel = { it },
                    onSelect = { layerName = it; keyId = "" })
                ChoiceRow("Key instance", options = layer.allKeys.map { it.id }.distinct(), selected = keyId,
                    optionLabel = { id -> layer.allKeys.firstOrNull { it.id == id }?.let { "${it.effectiveLabel.ifBlank { it.id }} · ${it.id}" } ?: id },
                    onSelect = { keyId = it })
            }
            TextRow("Search applicable options", value = query, onChange = { query = it })
            if (hiddenCount > 0) InfoRow("$hiddenCount matching options require a higher settings level. Expert exposes every applicable option.")
            if (address == null) InfoRow("Select an explicit ${scope.title.lowercase()} instance to edit it.")
            else {
                TextButton(onClick = { profilesOpen = !profilesOpen; profilePreview = null }) { Text(if (profilesOpen) "Hide local profiles" else "Local profiles") }
                if (profilesOpen) {
                    val profiles = remember(profileRevision, address.scope) { SettingsProfiles.all().filter { profile ->
                        profile.values.length() > 0 && profile.values.keys().asSequence().all { key -> SettingsSchema.spec(key)?.applicableScopes?.contains(address.scope) == true }
                    } }
                    InfoRow("Profiles use the same canonical options. Applying here creates local overrides in this draft only.")
                    profiles.forEach { profile -> ActionRow(profile.name, "${profile.values.length()} applicable values", onClick = { profilePreview = profile }) }
                    profilePreview?.let { profile ->
                        InfoRow("Preview ${profile.name} for ${address.scope.title} ${address.keyId ?: address.panelId ?: address.layoutId}: ${profile.values.keys().asSequence().joinToString()}")
                        Row {
                            TextButton(onClick = {
                                ScopedSettingsResolver.applyProfile(draft, address, profile).onSuccess { draft = it; profilePreview = null }.onFailure { error = it.message }
                            }) { Text("Apply to selected instance") }
                            TextButton(onClick = { profilePreview = null }) { Text("Cancel") }
                        }
                    }
                    TextRow("Local profile name", value = profileName, onChange = { profileName = it })
                    TextButton(onClick = {
                        val capturedAddress = address
                        val capturedDraft = draft
                        val capturedName = profileName
                        coroutineScope.launch {
                            runCatching {
                                val own = ScopedSettingsResolver.overrides(capturedDraft, capturedAddress)
                                val resolved = ScopedSettingsResolver.resolve(settings, capturedDraft, capturedAddress.panelId, capturedAddress.layerName,
                                    if (capturedAddress.scope == SettingsScope.KEY) capturedDraft.layers[capturedAddress.layerName]?.allKeys?.singleOrNull { it.id == capturedAddress.keyId } else null)
                                val stored = own.keys.fold(resolved.snapshot) { current, key -> SettingsSchema.withValue(current, key, own[key]!!.raw()) }
                                SettingsProfiles.save(SettingsProfiles.capture(capturedName, stored, own.keys))
                            }.onSuccess { profileRevision++; profileName = "" }.onFailure { error = it.message }
                        }
                    }) { Text("Save this instance's overrides as profile") }
                }
                val resolved = ScopedSettingsResolver.resolve(settings, draft, address.panelId, address.layerName,
                    if (address.scope == SettingsScope.KEY) draft.layers[address.layerName]?.allKeys?.singleOrNull { it.id == address.keyId } else null)
                val effectiveJson = com.example.core.config.SettingsStore.toJson(resolved.snapshot)
                val ownOverrides = ScopedSettingsResolver.overrides(draft, address)
                visible.forEach { spec ->
                    // Draft buffers belong to an exact address; switching keys discards unapplied input.
                    key(address, spec.key) {
                        val own = ownOverrides[spec.key]
                        val source = resolved.provenance[spec.key] ?: com.example.core.config.SettingsSource(SettingsScope.KEYBOARD_DEFAULTS)
                        val suppressed = resolved.suppressed[spec.key].orEmpty()
                        val controlSnapshot = if (own == null) resolved.snapshot else SettingsSchema.withValue(resolved.snapshot, spec.key, own.raw())
                        InfoRow("${spec.label} · effective: ${effectiveJson.opt(spec.key)} · source: ${source.label}" +
                            if (suppressed.isNotEmpty()) " · ignored by policy: ${suppressed.joinToString { it.label }}" else "")
                        SettingControl(spec, controlSnapshot, helpOverride = (spec.help.orEmpty() + if (own != null) " This control edits the stored local override; the effective result is shown above." else " Changing this creates a local override."), onWrite = { raw ->
                            ScopedSettingsResolver.withOverride(draft, address, spec.key, raw).map { draft = it; error = null }
                        })
                        if (own != null) TextButton(onClick = {
                            ScopedSettingsResolver.withOverride(draft, address, spec.key, null).onSuccess { draft = it }.onFailure { error = it.message }
                        }) { Text("Inherit ${spec.label}") }
                        else InfoRow("Inherited. Changing this control creates an override only for the selected ${scope.title.lowercase()}.")
                        Divider()
                    }
                }
            }
            error?.let { InfoRow(it) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft).onSuccess { onDismiss() }.onFailure { error = it.message } }) { Text("Save local settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Discard") } })
}
