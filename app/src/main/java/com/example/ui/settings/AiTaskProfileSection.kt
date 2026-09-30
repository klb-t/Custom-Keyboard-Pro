package com.example.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.core.ai.*
import com.example.core.config.*
import com.example.core.discovery.AiCapability
import com.example.core.discovery.ProviderProfiles

/** Contextual controls edit exactly the same application-owned Expert setting. */
@Composable
fun AiTaskProfileSection(settings: Settings) {
    if (!SettingsHierarchy.level(settings).includes(SettingsLevel.EXPERT)) return
    var task by remember { mutableStateOf(AiRequestTask.GOAL_PLAN) }
    val parsed = remember(settings.aiTaskProfilesJson) { runCatching { AiTaskProfiles.parse(settings.aiTaskProfilesJson) } }
    SettingsSection("AI task profiles", "Separate limits for each AI operation. These apply throughout the app.") {
        ChoiceRow("Task", options = AiRequestTask.entries, selected = task,
            optionLabel = { it.label }, onSelect = { task = it })
        if (parsed.isFailure) {
            InfoRow("Invalid task profiles. AI requests are blocked until corrected in Expert settings or reset here.")
            ActionRow("Reset task profiles", "Removes task overrides; provider and writing defaults remain.") {
                SettingsStore.update { it.copy(aiTaskProfilesJson = "") }
            }
        } else {
            val local = parsed.getOrThrow()[task] ?: AiTaskParameters()
            val provider = ProviderProfiles.paramsFor(settings.aiProvider, AiCapability.CHAT, settings)
            val effective = AiTaskProfiles.resolve(settings, task, provider)
            fun change(transform: (AiTaskParameters) -> AiTaskParameters) {
                SettingsStore.update { current ->
                    // Resolve against the current value so a concurrent settings edit is preserved.
                    runCatching {
                        val previous = AiTaskProfiles.parse(current.aiTaskProfilesJson)[task] ?: AiTaskParameters()
                        current.copy(aiTaskProfilesJson = AiTaskProfiles.update(current.aiTaskProfilesJson, task, transform(previous)))
                    }.getOrDefault(current)
                }
            }
            InfoRow("Token source: ${effective.tokenSource}. Temperature source: ${effective.temperatureSource}.")
            SliderRow("Response token limit", "Larger limits may cost more. A connection probe keeps its own small limit.",
                effective.maxTokens.toFloat(), 8f..8192f, format = { it.toInt().toString() },
                onChange = { value -> change { it.copy(maxTokens = value.toInt()) } })
            if (local.maxTokens != null) ActionRow("Use default token limit", "Remove this override; the task preset or inherited writing limit applies.") {
                change { it.copy(maxTokens = null) }
            }
            SliderRow("Temperature", "The provider may impose additional model-specific restrictions.",
                effective.temperature, 0f..2f, onChange = { value -> change { it.copy(temperature = value) } })
            if (local.temperature != null) ActionRow("Inherit temperature", "Remove this task's temperature override.") {
                change { it.copy(temperature = null) }
            }
        }
    }
}
