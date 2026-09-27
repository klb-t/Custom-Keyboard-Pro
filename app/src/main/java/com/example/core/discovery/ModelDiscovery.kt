package com.example.core.discovery

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A model id a provider says it serves. */
data class ModelInfo(
    override val id: String,
    val provider: String = "",
    val label: String = id,
    val capabilities: Set<String> = emptySet()
) : Discoverable

/**
 * Asking a provider what it can do, instead of making the user type a model name.
 *
 * Every provider worth using publishes this, and all three wire formats put the list
 * somewhere slightly different in slightly different JSON — so the reading is three
 * small functions and the asking is one. The result is cached into settings, because
 * the useful moment for a model list is often the moment the network is worst.
 */
object ModelDiscovery {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * What a provider serves for one particular capability.
     *
     * Three answers, in order of how much they are worth: what the provider says when
     * asked, what the catalogue records for that capability, and its default. The
     * order matters because a live list is right and a written one is merely current
     * — but a written one is still better than an empty dropdown, which is what the
     * user sees when the network is down or the provider publishes no list at all.
     */
    suspend fun forCapability(
        provider: ProviderSpec,
        capability: String,
        apiKey: String
    ): List<ModelInfo> {
        val spec = provider.capability(capability) ?: return emptyList()
        val declared = (spec.models + spec.defaultModel)
            .filter { it.isNotBlank() }
            .distinct()
            .map { ModelInfo(id = it, provider = provider.id) }

        val path = spec.modelsPath.ifBlank { provider.modelsPath }
        if (path.isBlank()) return declared

        val live = fetch(provider.copy(modelsPath = path), apiKey).getOrNull().orEmpty()
        if (live.isEmpty()) return declared

        return selectForCapability(live, declared, capability, spec.modelListIsScoped)
    }

    internal fun selectForCapability(
        live: List<ModelInfo>, declared: List<ModelInfo>, capability: String, scoped: Boolean
    ): List<ModelInfo> {
        val declaredIds = declared.map { it.id }.toSet()
        val relevant = live.filter {
            scoped || capability in it.capabilities || it.id in declaredIds ||
                (capability == AiCapability.CHAT && it.capabilities.isEmpty())
        }
        // A catalogue entry remains available offline, visibly as a declaration rather than a test.
        return (relevant + declared).distinctBy { it.id }
    }

    /** A live source for one provider, usable anywhere [Discovery] is. */
    fun source(provider: ProviderSpec, apiKey: String): DiscoverySource<ModelInfo> =
        object : DiscoverySource<ModelInfo> {
            override val id = "models:${provider.id}"
            override val label = provider.label
            override suspend fun discover(): Result<List<ModelInfo>> = fetch(provider, apiKey)
        }

    suspend fun fetch(provider: ProviderSpec, apiKey: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = provider.baseUrl.trimEnd('/')
                if (base.isEmpty()) error("This provider has no base URL set.")
                if (provider.modelsPath.isBlank()) error("This provider does not publish a model list.")

                val url = "$base${provider.modelsPath}"
                val builder = Request.Builder().url(url).get()
                when (provider.wire) {
                    AiWire.ANTHROPIC -> {
                        builder.addHeader("x-api-key", apiKey)
                        builder.addHeader("anthropic-version", "2023-06-01")
                    }
                    AiWire.GEMINI -> if (apiKey.isNotBlank()) builder.addHeader("x-goog-api-key", apiKey)
                    else -> if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
                }

                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("${response.code} ${response.message}: ${body.take(300)}")
                    }
                    parse(body, provider).sortedBy { it.id }
                }
            }
        }

    internal fun parse(body: String, provider: ProviderSpec): List<ModelInfo> {
        val root = JSONObject(body)
        // OpenAI and Anthropic both use "data"; Gemini uses "models". Accepting any of
        // them means a provider that copied one shape but not the other still works.
        val array: JSONArray = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { i ->
            val item = array.opt(i)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("name") }
                    .ifBlank { item.optString("model") }
                else -> ""
            }
            if (id.isBlank()) null
            else ModelInfo(
                // Gemini returns "models/gemini-2.5-flash"; the request wants the bare id.
                id = id.removePrefix("models/"),
                provider = provider.id,
                label = (item as? JSONObject)?.let {
                    it.optString("display_name").ifBlank { it.optString("displayName") }
                }?.ifBlank { null } ?: id,
                capabilities = (item as? JSONObject)?.let { obj ->
                    buildSet {
                        val methods = obj.optJSONArray("supportedGenerationMethods").toStringList()
                        if ("embedContent" in methods || "batchEmbedContents" in methods) add(AiCapability.EMBED)
                        if ("generateContent" in methods) add(AiCapability.CHAT)
                        val outputs = obj.optJSONObject("architecture")?.optJSONArray("output_modalities").toStringList()
                        if ("embeddings" in outputs) add(AiCapability.EMBED)
                        if ("text" in outputs) add(AiCapability.CHAT)
                    }
                } ?: emptySet()
            )
        }
    }

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    fun cachedModels(settings: Settings = SettingsStore.current, provider: String = settings.aiProvider): List<String> =
        try {
            val root = JSONObject(settings.discoveredModelsJson.ifBlank { "{}" })
            val arr = root.optJSONArray(provider) ?: return emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }

    fun cache(provider: String, models: List<ModelInfo>) {
        SettingsStore.update { s ->
            val root = try {
                JSONObject(s.discoveredModelsJson.ifBlank { "{}" })
            } catch (e: Exception) {
                JSONObject()
            }
            root.put(provider, JSONArray(models.map { it.id }))
            s.copy(discoveredModelsJson = root.toString())
        }
    }
}
