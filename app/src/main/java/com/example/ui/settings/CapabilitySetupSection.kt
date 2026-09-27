package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.*
import com.example.core.setup.CapabilitySetup
import com.example.core.setup.SetupRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray

/** Shared by first-run and regular settings: start with the intended operation/model. */
@Composable
fun CapabilitySetupSection(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember(settings.customProvidersJson, settings.fetchedProvidersJson) { ProviderCatalog.all(settings).filter(ProviderCatalog::runtimeSupported) }
    var capability by rememberSaveable { mutableStateOf(AiCapability.CHAT) }
    var query by rememberSaveable { mutableStateOf("") }
    var operationChosen by rememberSaveable { mutableStateOf(false) }
    var selectedProvider by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var facts by remember { mutableStateOf<List<ModelFact>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var publicChecked by remember { mutableStateOf(false) }
    var sample by rememberSaveable { mutableStateOf("IO Matrix connection test") }
    val sources = remember {
        runCatching { FactSource.parseList(context.assets.open("factsources.json").bufferedReader().use { it.readText() }) }
            .getOrDefault(emptyList())
    }
    val discoveryRequested = query.isNotBlank() || operationChosen
    LaunchedEffect(discoveryRequested) {
        if (discoveryRequested && !publicChecked) {
            delay(600)
            if (!busy) {
                busy = true
                try {
                    val result = FactsFetcher.gather(sources)
                    facts = result.items
                    publicChecked = true
                    status = "Public listings checked ${java.util.Date()}: ${facts.size} records. " +
                        result.failures.entries.joinToString { "${it.key}: ${it.value}" }
                } finally { busy = false }
            }
        }
    }
    val routes = remember(providers, capability, query, facts) {
        // A named model can reveal the operation; the user need not classify it first.
        (if (query.isBlank()) listOf(capability) else AiCapability.ALL).flatMap {
            CapabilitySetup.routes(providers, it, query, facts)
        }.sortedBy { if (it.capability == capability) 0 else 1 }
    }
    var resultLimit by remember(query, capability) { mutableStateOf(5) }
    val provider = providers.firstOrNull { it.id == selectedProvider && it.can(capability) }
    val spec = provider?.capability(capability)
    val profile = provider?.let { ProviderProfiles.of(it.id, settings) }
    val configured = profile?.capabilities?.get(capability)
    var parameterDraft by remember(provider?.id, capability) { mutableStateOf(JSONObject(configured?.params.orEmpty()).toString()) }
    val open: (String) -> Unit = { url ->
        if (url.startsWith("https://")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { status = "Could not open the page: ${it.message}" }
        } else status = "This account link is not HTTPS. Check the provider profile."
    }

    SettingsSection("Find a model or capability", "Search a model or choose an operation. Public listings refresh without sending your search text.") {
        ChoiceRow(label = "Operation", options = AiCapability.ALL, selected = capability,
            optionLabel = { AiCapability.label(it) }, onSelect = {
                capability = it; operationChosen = true; selectedProvider = ""; selectedModel = ""; status = ""
            })
        TextRow(label = "Model or provider", value = query, placeholder = "Google Embedding 2",
            onChange = { query = it; selectedProvider = ""; selectedModel = "" })
        val available = providers.filter { it.can(capability) }
        if (available.isNotEmpty()) ChoiceRow(label = "Or choose a provider directly", options = available,
            selected = provider ?: available.first(), optionLabel = { it.label }, onSelect = {
                selectedProvider = it.id; selectedModel = it.capability(capability)?.defaultModel.orEmpty(); status = ""
            })
        ActionRow(if (busy) "Refreshing…" else "Refresh public model listings",
            "Contacts ${sources.filter { it.enabled && !it.needsKey }.joinToString { it.label }}. No key or typed content is sent.",
            onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val result = FactsFetcher.gather(sources)
                            facts = result.items
                            status = "Read ${facts.size} model records. " +
                                result.failures.entries.joinToString { "${it.key}: ${it.value}" }
                        } finally { busy = false }
                    }
                }
            })
        if (provider != null) {
            ActionRow("Selected: ${selectedModel.ifBlank { AiCapability.label(capability) }} · ${provider.label}",
                "Change route", onClick = { selectedProvider = ""; selectedModel = "" })
        } else {
            InfoRow("Known sources, not every provider. A model listing does not guarantee account access.")
            if (routes.isEmpty()) InfoRow("No matching route. Try a shorter name, refresh, or add a provider profile in expert settings.")
            routes.take(resultLimit).forEach { route ->
                ActionRow("${route.model.ifBlank { AiCapability.label(capability) }} · ${route.provider.label}",
                    "${AiCapability.label(route.capability)} · ${route.evidence}${route.fact?.describe()?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                    onClick = { capability = route.capability; selectedProvider = route.provider.id; selectedModel = route.model; status = "" })
            }
            if (routes.size > resultLimit) ActionRow("Show more routes", "Showing $resultLimit of ${routes.size}; searching narrows the list.",
                onClick = { resultLimit += 10 })
        }
    }

    if (provider != null && spec != null && profile != null) {
        SettingsSection("Set up ${provider.label}", "${AiCapability.label(capability)} via ${selectedModel.ifBlank { "a model you choose" }}") {
            val steps = provider.setupSteps.ifEmpty { listOf(
                ProviderSetupStep("Open the provider’s setup page", "Create an account if needed and obtain a dedicated API key.", provider.signupUrl.ifBlank { provider.docsUrl })
            ) }
            steps.forEachIndexed { i, step ->
                if (step.url.isNotBlank()) ActionRow("${i + 1}. ${step.title}", step.detail, onClick = { open(step.url) })
                else InfoRow("${i + 1}. ${step.title}\n${step.detail}")
            }
            if (spec.docsUrl.isNotBlank()) ActionRow("Model documentation", "${spec.verifiedOn.ifBlank { "Date not established" }} · ${spec.docsUrl}", onClick = { open(spec.docsUrl) })
            if (spec.notes.isNotBlank()) InfoRow(spec.notes)
            if (provider.needsKey) TextRow(label = "API key", value = profile.apiKey, secret = true,
                description = "Stored for ${provider.label}. Account creation and payment stay on the provider’s own site.",
                onChange = { value -> ProviderProfiles.update(provider.id) { it.copy(apiKey = value.trim(), verifiedAt = 0L) } })
            TextRow(label = "Base URL", value = profile.baseUrl, placeholder = provider.baseUrl,
                onChange = { value -> ProviderProfiles.update(provider.id) { it.copy(baseUrl = value.trim(), verifiedAt = 0L) } })
            TextRow(label = "Model", value = selectedModel, placeholder = spec.defaultModel,
                onChange = { selectedModel = it })
            ActionRow("Refresh models for this operation", "Uses this provider’s key and endpoint. Chat models are not offered as embeddings.", onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val effective = provider.copy(baseUrl = profile.baseUrl.ifBlank { provider.baseUrl },
                                modelsPath = spec.modelsPath.ifBlank { provider.modelsPath })
                            ModelDiscovery.fetch(effective, ProviderProfiles.keyFor(provider.id, settings)).fold(
                                onSuccess = { live ->
                                    val declared = (spec.models + spec.defaultModel).filter { it.isNotBlank() }
                                        .map { ModelInfo(it, provider.id) }
                                    val relevant = ModelDiscovery.selectForCapability(live, declared, capability, spec.modelListIsScoped)
                                    facts = facts.filterNot { it.routeProvider == provider.id && it.capability == capability } + relevant.map {
                                        ModelFact(it.id, it.label, routeProvider = provider.id, capability = capability,
                                            source = provider.label, fetchedAt = System.currentTimeMillis())
                                    }
                                    status = "${relevant.size} relevant catalogue/listed models. Run the sample test to confirm inference access."
                                }, onFailure = { status = "Model discovery failed: ${it.message}. Your saved route remains available." })
                        } finally { busy = false }
                    }
                }
            })
            val params = configured?.params.orEmpty()
            val parameterNames = if (capability == AiCapability.CHAT) listOf("temperature", "maxTokens")
                else (spec.defaults.keys + spec.choices.keys + params.keys).distinct()
            parameterNames.forEach { name ->
                val current = params[name] ?: spec.defaults[name] ?: when {
                    capability == AiCapability.CHAT && name == "temperature" -> settings.aiTemperature.toString()
                    capability == AiCapability.CHAT && name == "maxTokens" -> settings.aiMaxTokens.toString()
                    else -> ""
                }
                val choices = spec.choices[name].orEmpty()
                if (choices.isNotEmpty()) ChoiceRow(label = name, options = (choices + current).distinct(), selected = current,
                    optionLabel = { it.ifEmpty { "None" } }, onSelect = { value ->
                        ProviderProfiles.update(provider.id) {
                            val c = it.capabilities[capability] ?: CapabilityProfile()
                            it.copy(capabilities = it.capabilities + (capability to c.copy(params = c.params + (name to value), verifiedAt = 0L)))
                        }
                    })
                TextRow(label = if (choices.isEmpty()) name else "$name — custom value", value = current,
                    onChange = { value -> ProviderProfiles.update(provider.id) {
                        val c = it.capabilities[capability] ?: CapabilityProfile()
                        it.copy(capabilities = it.capabilities + (capability to c.copy(params = c.params + (name to value), verifiedAt = 0L)))
                    } })
            }
            if (settings.expertMode && (spec.call != null || spec.wire == AiWire.OPENAI_AUDIO)) TextRow(
                label = "All route parameters (JSON)", value = parameterDraft, singleLine = false, minLines = 3,
                description = "String values for this capability’s request-template placeholders. Unsupported fields require a matching provider call profile.",
                onChange = { raw ->
                    parameterDraft = raw
                    runCatching {
                        val obj = JSONObject(raw)
                        obj.keys().asSequence().associateWith { obj.getString(it) }
                    }.fold(onSuccess = { values ->
                        ProviderProfiles.update(provider.id) {
                            val c = it.capabilities[capability] ?: CapabilityProfile()
                            it.copy(capabilities = it.capabilities + (capability to c.copy(params = values, verifiedAt = 0L)))
                        }
                        status = "Route parameters updated."
                    }, onFailure = { status = "Parameters not saved: use a JSON object with string values." })
                }
            )
            ActionRow("Save this route", "Only ${AiCapability.label(capability).lowercase()} changes. Background sending stays at its current setting.", onClick = {
                val route = SetupRoute(provider, capability, selectedModel.ifBlank { spec.defaultModel }, "User selected")
                SettingsStore.update { CapabilitySetup.select(it, route, params) }
                status = "Saved ${route.model} for ${AiCapability.label(capability).lowercase()}."
            })
            if (capability == AiCapability.EMBED && spec.call != null) {
                TextRow(label = "Sample text for an embedding test", value = sample, onChange = { sample = it })
                val readyToTest = configured?.selected == true && configured.model == selectedModel.ifBlank { spec.defaultModel }
                if (!readyToTest) InfoRow("Save this route above before testing the selected model.")
                if (readyToTest) ActionRow(if (busy) "Testing…" else "Test saved embedding route", "Sends only the sample above. The provider may charge for this request.", onClick = {
                    if (!busy && sample.isNotBlank()) {
                        busy = true
                        val testedCapability = capability
                        val testedModel = selectedModel.ifBlank { spec.defaultModel }
                        val testedCapabilityParams = params.toMap()
                        val testedProviderParams = profile.params.toMap()
                        val testedParams = testedProviderParams + testedCapabilityParams
                        val testedProvider = provider.copy(baseUrl = profile.baseUrl.ifBlank { provider.baseUrl })
                        val testedKey = ProviderProfiles.keyFor(provider.id, settings)
                        val testedSample = sample
                        val expectedDimensions = (spec.defaults + ProviderProfiles.paramsFor(provider.id, capability, settings) + testedParams)["dimensions"]?.toIntOrNull()?.takeIf { it > 0 }
                        scope.launch {
                            try {
                                val result = CallEngine.run(testedProvider, testedCapability,
                                    CallInput(model = testedModel, prompt = testedSample, params = testedParams), testedKey)
                                status = result.fold(onSuccess = { answer ->
                                    val check = runCatching { CapabilitySetup.validateEmbedding(answer.text.orEmpty(), expectedDimensions) }
                                    if (check.isSuccess) {
                                        ProviderProfiles.update(provider.id) {
                                            val c = it.capabilities[testedCapability] ?: CapabilityProfile()
                                            val currentBase = it.baseUrl.ifBlank { provider.baseUrl }
                                            if (c.model == testedModel && c.params == testedCapabilityParams && it.params == testedProviderParams &&
                                                it.apiKey == testedKey && currentBase == testedProvider.baseUrl)
                                                it.copy(capabilities = it.capabilities + (testedCapability to c.copy(verifiedAt = System.currentTimeMillis())))
                                            else it
                                        }
                                        "Worked: ${check.getOrThrow()} numeric dimensions. Verification is recorded only if the saved route is unchanged."
                                    } else "Test failed: ${check.exceptionOrNull()?.message}"
                                }, onFailure = { "Test failed: ${it.message}" })
                            } finally { busy = false }
                        }
                    }
                })
            }
            if (configured?.selected == true) InfoRow("Selected route: ${configured.model}" +
                if (configured.verifiedAt > 0L) " · last successful sample ${java.util.Date(configured.verifiedAt)}" else " · inference not tested")
        }
    }
    if (status.isNotBlank()) SettingsSection("Setup status") { InfoRow(status) }
}
