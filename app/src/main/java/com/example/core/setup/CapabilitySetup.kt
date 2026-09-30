package com.example.core.setup

import com.example.core.config.AsrEngines
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.*
import org.json.JSONArray
import org.json.JSONObject

/** A concrete model via a concrete provider; a model's author is not its routing provider. */
data class SetupRoute(
    val provider: ProviderSpec,
    val capability: String,
    val model: String,
    val evidence: String,
    val fact: ModelFact? = null
)

/** Navigation carries a setup target independently of the app's writing route. */
data class SetupTarget(val capability: String, val providerId: String, val model: String)

data class SetupJourney(val target: SetupTarget, val wants: SetupWants) {
    /** Local navigation state only: provider/model identities and policy, never credentials. */
    fun toJson(): String = JSONObject().apply {
        put("capability", target.capability)
        put("providerId", target.providerId)
        put("model", target.model)
        put("capabilities", JSONArray(wants.capabilities.sorted()))
        put("mustStayOnDevice", wants.mustStayOnDevice)
        put("noCard", wants.noCard)
        put("freeOnly", wants.freeOnly)
        put("avoidTraining", wants.avoidTraining)
        put("preferOneAccount", wants.preferOneAccount)
    }.toString()

    companion object {
        fun fromJson(raw: String?): SetupJourney? = runCatching {
            require(raw != null && raw.length <= 16_000)
            val o = JSONObject(raw)
            require(listOf("capability", "providerId", "model").all { o.get(it) is String })
            require(listOf("mustStayOnDevice", "noCard", "freeOnly", "avoidTraining", "preferOneAccount").all {
                o.get(it) is Boolean
            })
            val capability = o.getString("capability")
            val providerId = o.getString("providerId")
            val model = o.getString("model")
            require(capability in AiCapability.ALL && providerId.isNotBlank() && providerId.length <= 512 && model.length <= 4000)
            val list = o.getJSONArray("capabilities")
            require(list.length() <= AiCapability.ALL.size)
            require((0 until list.length()).all { list.get(it) is String })
            val capabilities = (0 until list.length()).map { list.getString(it) }.toSet()
            require(capabilities.all { it in AiCapability.ALL })
            SetupJourney(SetupTarget(capability, providerId, model), SetupWants(
                capabilities = capabilities,
                mustStayOnDevice = o.getBoolean("mustStayOnDevice"),
                noCard = o.getBoolean("noCard"),
                freeOnly = o.getBoolean("freeOnly"),
                avoidTraining = o.getBoolean("avoidTraining"),
                preferOneAccount = o.getBoolean("preferOneAccount")
            ))
        }.getOrNull()
    }
}

object CapabilitySetup {
    fun allowedProviders(providers: List<ProviderSpec>, wants: SetupWants?): List<ProviderSpec> =
        if (wants == null) providers else providers.filter { SetupAdvisor.allowsPolicy(it, wants) }

    /** Resolve again when policy, profiles or catalogue change; a stale target grants no access. */
    fun resolveTarget(target: SetupTarget, providers: List<ProviderSpec>, wants: SetupWants? = null): SetupRoute? {
        if (target.capability !in AiCapability.ALL) return null
        val provider = allowedProviders(providers, wants).firstOrNull {
            it.id == target.providerId && it.can(target.capability)
        } ?: return null
        return SetupRoute(provider, target.capability, target.model, "User selected")
    }

    fun recommendationJourney(provider: ProviderSpec, wants: SetupWants): SetupJourney? {
        if (!SetupAdvisor.allowsPolicy(provider, wants) || !wants.capabilities.all { provider.can(it) }) return null
        val capabilities = AiCapability.ALL.filter { it in wants.capabilities && provider.can(it) }
            .ifEmpty { provider.abilities }
        val capability = capabilities.firstOrNull { it != AiCapability.CHAT }
            ?: capabilities.firstOrNull() ?: return null
        return SetupJourney(SetupTarget(capability, provider.id, provider.capability(capability)?.defaultModel.orEmpty()),
            wants.copy(capabilities = wants.capabilities.toSet(), notes = ""))
    }

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
