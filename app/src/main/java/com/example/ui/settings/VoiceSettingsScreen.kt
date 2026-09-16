package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.config.AsrEngines
import com.example.core.discovery.AiCapability
import com.example.core.discovery.ProviderCatalog
import com.example.core.config.Settings
import com.example.core.config.SettingsStore

@Composable
fun VoiceSettingsScreen(
    settings: Settings,
    micGranted: Boolean,
    onRequestMic: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Microphone",
            subtitle = "An input method cannot ask for permissions itself, so this is the " +
                "one thing dictation needs you to do here."
        ) {
            ActionRow(
                label = if (micGranted) "Microphone access granted" else "Grant microphone access",
                description = if (micGranted) {
                    "Dictation is ready."
                } else {
                    "Without it the microphone key will tell you it cannot listen."
                },
                trailing = if (micGranted) "✓" else null,
                onClick = { if (!micGranted) onRequestMic() }
            )
        }

        SettingsSection("Engine") {
            ChoiceRow(
                label = "Where dictation goes",
                description = "This phone needs no account and on most devices runs on " +
                    "the device itself, so the audio never leaves it. Anything else sends " +
                    "the recording to whatever you point it at — usually more accurate, " +
                    "and better with languages the phone does not know.",
                options = AsrEngines.ALL,
                selected = settings.asrEngine,
                optionLabel = { AsrEngines.label(it) },
                onSelect = { engine -> SettingsStore.update { it.copy(asrEngine = engine) } }
            )

            if (settings.asrEngine == AsrEngines.PROVIDER) {
                // Asked of the catalogue, never listed here: a provider the user adds,
                // or one a model writes for them, shows up in this list on its own.
                val providers = remember(settings.customProvidersJson) {
                    ProviderCatalog.serving(AiCapability.TRANSCRIBE, settings)
                }
                if (providers.isEmpty()) {
                    InfoRow("Nothing in the catalogue takes dictation. That should not happen.")
                } else {
                    ChoiceRow(
                        label = "Provider",
                        description = "Everything that can take dictation, including this " +
                            "phone's own recogniser.",
                        options = providers.map { it.id },
                        selected = settings.asrProvider.ifBlank { providers.first().id },
                        optionLabel = { id ->
                            providers.firstOrNull { it.id == id }?.let { p ->
                                p.label + if (p.local) " · stays on the device" else ""
                            } ?: id
                        },
                        onSelect = { id ->
                            val chosen = providers.firstOrNull { it.id == id }
                            SettingsStore.update { s ->
                                s.copy(
                                    asrProvider = id,
                                    // A model id from the previous provider is worse
                                    // than none: it is rejected rather than ignored.
                                    asrModel = chosen?.capability(AiCapability.TRANSCRIBE)
                                        ?.defaultModel.orEmpty()
                                )
                            }
                        }
                    )
                    val chosen = providers.firstOrNull { it.id == settings.asrProvider }
                    if (chosen != null) {
                        val models = chosen.capability(AiCapability.TRANSCRIBE)?.models.orEmpty()
                        if (models.isNotEmpty()) {
                            ChoiceRow(
                                label = "Model",
                                options = models,
                                selected = settings.asrModel.ifBlank { models.first() },
                                optionLabel = { it },
                                onSelect = { m -> SettingsStore.update { it.copy(asrModel = m) } }
                            )
                        } else {
                            TextRow(
                                label = "Model",
                                value = settings.asrModel,
                                placeholder = chosen.capability(AiCapability.TRANSCRIBE)
                                    ?.defaultModel.orEmpty(),
                                onChange = { v -> SettingsStore.update { it.copy(asrModel = v) } }
                            )
                        }
                        if (chosen.needsKey) {
                            TextRow(
                                label = "API key",
                                description = chosen.docsUrl.ifBlank { null },
                                value = settings.asrApiKey,
                                secret = true,
                                onChange = { v -> SettingsStore.update { it.copy(asrApiKey = v) } }
                            )
                        }
                        if (chosen.note.isNotBlank()) InfoRow(chosen.note)
                    }
                }
            }

            if (settings.asrEngine == AsrEngines.REMOTE) {
                TextRow(
                    label = "Transcription endpoint",
                    description = "An OpenAI-compatible /audio/transcriptions URL. A local " +
                        "whisper.cpp or faster-whisper server works.",
                    value = settings.asrRemoteUrl,
                    placeholder = "https://api.openai.com/v1",
                    onChange = { v -> SettingsStore.update { it.copy(asrRemoteUrl = v) } }
                )
                TextRow(
                    label = "API key",
                    value = settings.asrApiKey,
                    secret = true,
                    onChange = { v -> SettingsStore.update { it.copy(asrApiKey = v) } }
                )
                TextRow(
                    label = "Model",
                    value = settings.asrModel,
                    placeholder = "whisper-1",
                    onChange = { v -> SettingsStore.update { it.copy(asrModel = v) } }
                )
            }
            TextRow(
                label = "Language",
                description = "A BCP-47 tag such as pl-PL or en-GB. Leave empty to follow " +
                    "the system language.",
                value = settings.asrLanguage,
                placeholder = "follow the system",
                onChange = { v -> SettingsStore.update { it.copy(asrLanguage = v) } }
            )
        }

        SettingsSection(
            title = "Alternatives",
            subtitle = "The recogniser returns a ranked list and the second entry is often " +
                "the right one, particularly for names and technical words."
        ) {
            SwitchRow(
                label = "Show alternatives",
                checked = settings.asrShowAlternatives,
                onChange = { on -> SettingsStore.update { it.copy(asrShowAlternatives = on) } }
            )
            Divider()
            SwitchRow(
                label = "Insert the best guess immediately",
                description = "Off by default: the transcription is shown and you commit it. " +
                    "On, it is typed as soon as it arrives, like other keyboards.",
                checked = settings.asrAutoCommitBest,
                onChange = { on -> SettingsStore.update { it.copy(asrAutoCommitBest = on) } }
            )
            if (settings.expertMode) {
                Divider()
                SliderRow(
                    label = "How many alternatives to ask for",
                    value = settings.asrAlternativeCount.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    format = { it.toInt().toString() },
                    onChange = { v -> SettingsStore.update { it.copy(asrAlternativeCount = v.toInt()) } }
                )
            }
            if (settings.aiEnabled) {
                InfoRow(
                    "Because AI is on, a single-transcript engine can also be asked for " +
                        "other plausible readings — different word boundaries, homophones, " +
                        "proper nouns."
                )
            }
        }
    }
}
