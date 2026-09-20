package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTasks
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.ModelDiscovery
import com.example.core.discovery.ModelInfo
import com.example.core.discovery.ApiKeys
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfile
import com.example.core.discovery.ProviderProfiles
import com.example.core.setup.ProviderProbe
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
    var discovering by remember { mutableStateOf(false) }
    var discoveryResult by remember { mutableStateOf<String?>(null) }

    // A key set before profiles existed is moved into the selected provider's profile
    // the first time this screen is opened. Without it somebody who had a working
    // setup would find the box empty, and "my key vanished" is a worse failure than
    // the one profiles exist to fix.
    LaunchedEffect(Unit) { ProviderProfiles.adoptLooseKey() }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    val profiles = remember(settings.providerProfilesJson) { ProviderProfiles.all(settings) }
    val profile = profiles[settings.aiProvider] ?: ProviderProfile(settings.aiProvider)

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

        SettingsSection(
            title = "Provider",
            subtitle = "The catalogue is a bundled data file, not a list in the code — " +
                "so a provider that appears next year is an entry, not a release."
        ) {
            val providers = remember(settings.customProvidersJson) { ProviderCatalog.all(settings) }
            ChoiceRow(
                label = "Provider",
                description = "Anything speaking the OpenAI chat API works, including a " +
                    "model on your own machine through Ollama, LM Studio, llama.cpp or vLLM.",
                options = providers,
                selected = providers.firstOrNull { it.id == settings.aiProvider } ?: providers.first(),
                optionLabel = { it.label },
                onSelect = { spec ->
                    SettingsStore.update { current ->
                        val previous = ProviderCatalog.byId(current.aiProvider, current)
                        // Only replace a URL or model the user did not choose themselves.
                        current.copy(
                            aiProvider = spec.id,
                            aiBaseUrl = if (current.aiBaseUrl.isBlank() ||
                                current.aiBaseUrl == previous?.baseUrl
                            ) spec.baseUrl else current.aiBaseUrl,
                            aiModel = if (current.aiModel.isBlank() ||
                                current.aiModel == previous?.defaultModel
                            ) spec.defaultModel else current.aiModel
                        )
                    }
                }
            )
            val spec = providers.firstOrNull { it.id == settings.aiProvider }
            spec?.let {
                InfoRow(
                    buildString {
                        append(if (it.local) "Runs on your own machine; nothing leaves your network. " else "")
                        append(if (it.needsKey) "Needs an API key. " else "Usually needs no key. ")
                        if (it.docsUrl.isNotBlank()) append("Keys: ${it.docsUrl}")
                    }
                )
            }
            TextRow(
                label = "Base URL",
                description = "For a local server this is usually something like " +
                    "http://192.168.1.10:11434/v1 — the phone has to be able to reach it.",
                value = profile.baseUrl.ifBlank { settings.aiBaseUrl },
                placeholder = spec?.baseUrl.orEmpty(),
                onChange = { v -> ProviderProfiles.update(settings.aiProvider) { it.copy(baseUrl = v) } }
            )
            TextRow(
                label = "API key for ${spec?.label ?: settings.aiProvider}",
                description = "Kept against this provider, so switching provider switches " +
                    "key with it and nothing has to be retyped. On this device only; an " +
                    "exported settings file carries it.",
                value = profile.apiKey.ifBlank {
                    if (settings.aiApiKey.isNotBlank()) settings.aiApiKey else ""
                },
                secret = true,
                onChange = { v -> ProviderProfiles.update(settings.aiProvider) { it.copy(apiKey = v) } }
            )

            // Getting a key means leaving the app, signing in, pressing "create" and
            // copying a long opaque string. Sending somebody to a home page to find
            // that themselves is the difference between two taps and ten minutes.
            val keyPage = spec?.signupUrl?.ifBlank { spec.docsUrl }.orEmpty()
            if (keyPage.isNotBlank() && spec?.needsKey == true) {
                ActionRow(
                    label = "Get a key from ${spec.label}",
                    description = keyPage,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(keyPage))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }

            // Copying the key is the last thing that happens over there, so by the time
            // somebody is back here it is already on the clipboard. Asking them to
            // paste it is asking them to do the one step the app could do itself.
            val pasted = clipboard.getText()?.text?.trim().orEmpty()
            if (pasted.isNotBlank() && pasted != profile.apiKey && ApiKeys.looksLikeKey(pasted)) {
                val owner = ApiKeys.whoseKey(pasted, providers)
                val mine = owner == null || owner.id == settings.aiProvider
                if (mine) {
                    ActionRow(
                        label = "Use the key you just copied (${ApiKeys.masked(pasted)})",
                        description = "Read from the clipboard, and only while this screen " +
                            "is open. Nothing is stored until you tap.",
                        onClick = {
                            ProviderProfiles.update(settings.aiProvider) { it.copy(apiKey = pasted, problem = "") }
                            checkResult = null
                        }
                    )
                } else {
                    // A key pasted into the wrong provider's box is the failure that
                    // started all of this. It costs nothing to notice.
                    InfoRow(
                        "The key on your clipboard looks like a ${owner.label} key, not a " +
                            "${spec?.label ?: settings.aiProvider} one. Switch provider above to use it."
                    )
                }
            }

            ActionRow(
                label = if (checking) "Checking…" else "Check this key",
                description = "Asks the provider for its model list — the cheapest question " +
                    "there is — and says exactly what came back.",
                onClick = {
                    if (!checking && spec != null) {
                        checking = true
                        checkResult = null
                        scope.launch {
                            val probe = ProviderProbe.probe(spec, ProviderProfiles.keyFor(spec.id, settings))
                            checkResult = when {
                                probe.usable ->
                                    "Works. ${spec.label} answered" +
                                        (if (probe.models.isNotEmpty()) " and listed ${probe.models.size} models." else ".")
                                probe.problem != null -> probe.problem
                                else -> "No answer from ${spec.label}."
                            }
                            ProviderProfiles.update(spec.id) {
                                it.copy(
                                    verifiedAt = if (probe.usable) System.currentTimeMillis() else it.verifiedAt,
                                    problem = if (probe.usable) "" else probe.problem.orEmpty()
                                )
                            }
                            // The probe already asked for the model list, so the answer
                            // is in hand: filling the picker here saves a second
                            // identical request the user would otherwise have to make.
                            if (probe.usable && probe.models.isNotEmpty()) {
                                ModelDiscovery.cache(
                                    spec.id,
                                    probe.models.map { ModelInfo(id = it, provider = spec.id) }
                                )
                            }
                            checking = false
                        }
                    }
                }
            )
            checkResult?.let { InfoRow(it) }

            // The point of profiles, made visible: what is already set up and can be
            // switched to without typing anything.
            val configured = profiles.values.filter { it.hasKey }
            if (configured.isNotEmpty()) {
                InfoRow(
                    "Keys stored for " + configured
                        .sortedBy { it.id }
                        .joinToString(", ") { p ->
                            ProviderCatalog.byId(p.id, settings)?.label ?: p.id
                        } + ". Switching provider uses that provider's key."
                )
            }

            val discovered = remember(settings.discoveredModelsJson, settings.aiProvider) {
                ModelDiscovery.cachedModels(settings)
            }
            if (discovered.isEmpty()) {
                TextRow(
                    label = "Model",
                    value = profile.model.ifBlank { settings.aiModel },
                    placeholder = spec?.defaultModel.orEmpty(),
                    onChange = { v -> ProviderProfiles.update(settings.aiProvider) { it.copy(model = v) } }
                )
            } else {
                ChoiceRow(
                    label = "Model",
                    description = "${discovered.size} models this provider says it serves.",
                    options = discovered,
                    selected = profile.model.ifBlank { settings.aiModel }.ifBlank { discovered.first() },
                    optionLabel = { it },
                    onSelect = { m -> ProviderProfiles.update(settings.aiProvider) { it.copy(model = m) } }
                )
                TextRow(
                    label = "…or type one",
                    value = profile.model.ifBlank { settings.aiModel },
                    onChange = { v -> ProviderProfiles.update(settings.aiProvider) { it.copy(model = v) } }
                )
            }
            ActionRow(
                label = if (discovering) "Asking…" else "Ask the provider what models it has",
                description = "Fills the list above. Cached, so it survives going offline.",
                onClick = {
                    if (!discovering && spec != null) {
                        discovering = true
                        discoveryResult = null
                        scope.launch {
                            ModelDiscovery.fetch(spec, ProviderProfiles.keyFor(spec.id, settings)).fold(
                                onSuccess = { models ->
                                    ModelDiscovery.cache(spec.id, models)
                                    discoveryResult = if (models.isEmpty()) {
                                        "The provider answered, but listed no models."
                                    } else {
                                        "Found ${models.size}."
                                    }
                                },
                                onFailure = { discoveryResult = "Could not ask: ${it.message}" }
                            )
                            discovering = false
                        }
                    }
                }
            )
            discoveryResult?.let { InfoRow(it) }
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
