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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTasks
import com.example.core.config.AiProviders
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import kotlinx.coroutines.launch

/**
 * AI configuration.
 *
 * The app ships no key and no default endpoint on purpose: the user brings their own
 * provider, so they decide who sees their text and what it costs. Everything here
 * starts switched off, and the screen says plainly what turning it on means.
 */
@Composable
fun AiSettingsScreen(settings: Settings) {
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "AI features",
            subtitle = "Off by default. When on, the text around your cursor is sent to " +
                "the provider you configure below. Password fields are never sent."
        ) {
            SwitchRow(
                label = "Enable AI",
                checked = settings.aiEnabled,
                onChange = { on -> SettingsStore.update { it.copy(aiEnabled = on) } }
            )
            Divider()
            SwitchRow(
                label = "Inline completion",
                description = "Offers a continuation in the suggestion strip as you type. " +
                    "This sends text on a timer while you write, so it is the setting with " +
                    "the most traffic and the most exposure.",
                checked = settings.aiCompletionEnabled,
                enabled = settings.aiEnabled,
                onChange = { on -> SettingsStore.update { it.copy(aiCompletionEnabled = on) } }
            )
            InfoRow(
                "Rewriting, translating and the rest are on demand only: they run when you " +
                    "press the AI key or open the AI panel."
            )
        }

        SettingsSection("Provider") {
            ChoiceRow(
                label = "Provider",
                description = "Anything that speaks the OpenAI chat API works, including " +
                    "Groq, OpenRouter, Together, and a model running on your own machine " +
                    "through Ollama, LM Studio, llama.cpp or vLLM.",
                options = AiProviders.ALL,
                selected = settings.aiProvider,
                optionLabel = { AiProviders.label(it) },
                onSelect = { provider ->
                    SettingsStore.update {
                        it.copy(
                            aiProvider = provider,
                            aiBaseUrl = if (it.aiBaseUrl.isBlank() ||
                                it.aiBaseUrl == AiProviders.defaultBaseUrl(it.aiProvider)
                            ) AiProviders.defaultBaseUrl(provider) else it.aiBaseUrl,
                            aiModel = if (it.aiModel.isBlank() ||
                                it.aiModel == AiProviders.defaultModel(it.aiProvider)
                            ) AiProviders.defaultModel(provider) else it.aiModel
                        )
                    }
                }
            )
            TextRow(
                label = "Base URL",
                description = "For a local server this is usually something like " +
                    "http://192.168.1.10:11434/v1 — the phone has to be able to reach it.",
                value = settings.aiBaseUrl,
                placeholder = AiProviders.defaultBaseUrl(settings.aiProvider),
                onChange = { v -> SettingsStore.update { it.copy(aiBaseUrl = v) } }
            )
            TextRow(
                label = "API key",
                description = "Stored on this device only. A local server usually needs no key.",
                value = settings.aiApiKey,
                secret = true,
                onChange = { v -> SettingsStore.update { it.copy(aiApiKey = v) } }
            )
            TextRow(
                label = "Model",
                value = settings.aiModel,
                placeholder = AiProviders.defaultModel(settings.aiProvider),
                onChange = { v -> SettingsStore.update { it.copy(aiModel = v) } }
            )
            ActionRow(
                label = if (testing) "Testing…" else "Test the connection",
                description = "Sends one short request and shows exactly what comes back.",
                onClick = {
                    if (!testing) {
                    testing = true
                    testResult = null
                    scope.launch {
                        val result = AiClient.complete(
                            config = AiConfig.from(settings, maxTokens = 24),
                            systemPrompt = "Reply with exactly: ok",
                            userPrompt = "Say ok."
                        )
                        testing = false
                        testResult = result.fold(
                            onSuccess = { "Worked. The model replied: ${it.trim().take(120)}" },
                            onFailure = { "Failed: ${it.message}" }
                        )
                    }
                    }
                }
            )
            testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (settings.expertMode) {
            SettingsSection("Completion tuning") {
                SliderRow(
                    label = "Start after this many characters",
                    value = settings.aiCompletionMinChars.toFloat(),
                    range = 1f..40f,
                    format = { it.toInt().toString() },
                    onChange = { v -> SettingsStore.update { it.copy(aiCompletionMinChars = v.toInt()) } }
                )
                SliderRow(
                    label = "Wait after you stop typing",
                    description = "Longer means fewer requests and less cost; shorter means " +
                        "the suggestion arrives sooner.",
                    value = settings.aiCompletionDebounceMs.toFloat(),
                    range = 150f..3000f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(aiCompletionDebounceMs = v.toLong()) } }
                )
                SliderRow(
                    label = "How much context to send",
                    description = "Characters of text before the cursor.",
                    value = settings.aiContextChars.toFloat(),
                    range = 100f..4000f,
                    format = { "${it.toInt()} chars" },
                    onChange = { v -> SettingsStore.update { it.copy(aiContextChars = v.toInt()) } }
                )
                SliderRow(
                    label = "Temperature",
                    value = settings.aiTemperature,
                    range = 0f..1.5f,
                    format = { "%.2f".format(it) },
                    onChange = { v -> SettingsStore.update { it.copy(aiTemperature = v) } }
                )
                SliderRow(
                    label = "Maximum reply length",
                    value = settings.aiMaxTokens.toFloat(),
                    range = 16f..1024f,
                    format = { "${it.toInt()} tokens" },
                    onChange = { v -> SettingsStore.update { it.copy(aiMaxTokens = v.toInt()) } }
                )
            }

            SettingsSection(
                title = "Your own tasks",
                subtitle = "A JSON array. Each entry becomes a button in the AI panel and " +
                    "can be bound to a key. {text} is replaced with your selection."
            ) {
                TextRow(
                    label = "Custom tasks (JSON)",
                    value = settings.aiCustomTasksJson,
                    placeholder = SAMPLE_TASKS,
                    singleLine = false,
                    minLines = 6,
                    onChange = { v -> SettingsStore.update { it.copy(aiCustomTasksJson = v) } }
                )
                ActionRow(
                    "Insert an example",
                    "Replaces whatever is in the box above",
                    onClick = { SettingsStore.update { it.copy(aiCustomTasksJson = SAMPLE_TASKS) } }
                )
                InfoRow(
                    "Built in already: " +
                        AiTasks.BUILT_IN.joinToString(", ") { it.label.lowercase() } + "."
                )
            }
        }
    }
}

private const val SAMPLE_TASKS = """[
  {
    "id": "polish_formal_email",
    "label": "Formal e-mail (PL)",
    "system": "Odpowiadaj wyłącznie przepisanym tekstem, bez komentarza.",
    "prompt": "Przepisz to jako uprzejmy, formalny e-mail po polsku:\n\n{text}",
    "replace": true
  },
  {
    "id": "commit_message",
    "label": "Commit message",
    "system": "Reply with a git commit message only.",
    "prompt": "Write a concise commit message for this change:\n\n{text}",
    "replace": false
  }
]"""
