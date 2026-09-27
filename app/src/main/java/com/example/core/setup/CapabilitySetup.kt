package com.example.core.setup

import com.example.core.config.AsrEngines
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.*

/** A concrete model via a concrete provider; a model's author is not its routing provider. */
data class SetupRoute(
    val provider: ProviderSpec,
    val capability: String,
    val model: String,
    val evidence: String,
    val fact: ModelFact? = null
)

object CapabilitySetup {
    fun routes(
        providers: List<ProviderSpec>, capability: String, query: String = "",
        facts: List<ModelFact> = emptyList()
    ): List<SetupRoute> {
        val declared = providers.flatMap { provider ->
            val spec = provider.capability(capability) ?: return@flatMap emptyList()
            (spec.models + spec.defaultModel).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("") }.map { model ->
                SetupRoute(provider, capability, model, if (spec.verifiedOn.isNotBlank())
                    "Documentation checked ${spec.verifiedOn}" else "Bundled/custom catalogue; availability not verified")
            }
        }
        val live = facts.filter { it.capability == capability }.mapNotNull { fact ->
            providers.firstOrNull { it.id == fact.routeProvider && it.can(capability) }?.let {
                SetupRoute(it, capability, fact.id, "Listed by ${fact.source}, fetched ${java.util.Date(fact.fetchedAt)}; account access not tested", fact)
            }
        }
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return (live + declared).distinctBy { it.provider.id to it.model }.filter { route ->
            val haystack = "${route.provider.label} ${route.model} ${route.fact?.label.orEmpty()}".lowercase()
            terms.all { it in haystack }
        }
    }

    /** Saves a route without enabling background requests or changing unrelated capabilities. */
    fun select(settings: Settings, route: SetupRoute, params: Map<String, String> = emptyMap()): Settings {
        val profiles = ProviderProfiles.all(settings).toMutableMap()
        if (settings.aiApiKey.isNotBlank() && profiles[settings.aiProvider] == null) {
            profiles[settings.aiProvider] = ProviderProfile(settings.aiProvider, apiKey = settings.aiApiKey,
                baseUrl = settings.aiBaseUrl, model = settings.aiModel)
        }
        profiles.replaceAll { _, profile ->
            profile.copy(capabilities = profile.capabilities.mapValues { (capability, value) ->
                if (capability == route.capability) value.copy(selected = false) else value
            })
        }
        val previous = profiles[route.provider.id] ?: ProviderProfile(route.provider.id)
        val oldCapability = previous.capabilities[route.capability] ?: CapabilityProfile()
        profiles[route.provider.id] = previous.copy(capabilities = previous.capabilities +
            (route.capability to oldCapability.copy(model = route.model, params = params,
                selected = true, verifiedAt = if (oldCapability.model == route.model && oldCapability.params == params)
                    oldCapability.verifiedAt else 0L)))
        val saved = settings.copy(providerProfilesJson = ProviderProfiles.toJson(profiles))
        return when (route.capability) {
            AiCapability.CHAT -> {
                val profile = profiles[route.provider.id] ?: previous
                profiles[route.provider.id] = profile.copy(model = route.model)
                saved.copy(aiProvider = route.provider.id, aiBaseUrl = profile.baseUrl,
                    aiModel = route.model, aiApiKey = "", providerProfilesJson = ProviderProfiles.toJson(profiles))
            }
            AiCapability.COMPLETE -> saved.copy(completionProvider = route.provider.id, completionModel = route.model,
                completionApiKey = "")
            AiCapability.TRANSCRIBE -> saved.copy(asrProvider = route.provider.id, asrModel = route.model,
                asrApiKey = "", asrRemoteUrl = "", asrEngine = AsrEngines.PROVIDER)
            AiCapability.OCR -> saved.copy(ocrProvider = route.provider.id, ocrModel = route.model)
            AiCapability.SPEECH -> saved.copy(ttsProvider = route.provider.id, ttsModel = route.model,
                ttsCloudVoice = params["voice"] ?: route.provider.capability(AiCapability.SPEECH)?.defaults?.get("voice").orEmpty())
            else -> saved
        }
    }

    fun selected(capability: String, settings: Settings = SettingsStore.current): Pair<ProviderSpec, CapabilityProfile>? {
        val profile = ProviderProfiles.all(settings).values.firstOrNull { it.capabilities[capability]?.selected == true }
            ?: return null
        val provider = ProviderCatalog.byId(profile.id, settings) ?: return null
        return provider.copy(baseUrl = ProviderProfiles.baseUrlFor(profile.id, settings)) to profile.capabilities.getValue(capability)
    }

    /** Verify the payload, including any dimensionality the request explicitly asked for. */
    internal fun validateEmbedding(text: String, expectedDimensions: Int? = null): Int {
        val vector = runCatching { org.json.JSONArray(text) }.getOrNull()
            ?: error("The endpoint did not return an embedding array.")
        require(vector.length() > 0 && (0 until vector.length()).all {
            (vector.opt(it) as? Number)?.toDouble()?.isFinite() == true
        }) { "The endpoint did not return a non-empty finite numeric embedding vector." }
        require(expectedDimensions == null || vector.length() == expectedDimensions) {
            "Expected $expectedDimensions dimensions, received ${vector.length()}. The chosen dimensionality was not verified."
        }
        return vector.length()
    }

    /** Shared execution path for saved non-chat routes, including embeddings. */
    suspend fun runSelected(capability: String, input: CallInput, settings: Settings = SettingsStore.current): Result<CallResult> {
        val (provider, profile) = selected(capability, settings)
            ?: return Result.failure(IllegalStateException("Choose a route for ${AiCapability.label(capability)} first."))
        return CallEngine.run(provider, capability, input.copy(model = input.model.ifBlank { profile.model },
            params = ProviderProfiles.paramsFor(provider.id, capability, settings) + input.params), ProviderProfiles.keyFor(provider.id, settings))
    }
}
