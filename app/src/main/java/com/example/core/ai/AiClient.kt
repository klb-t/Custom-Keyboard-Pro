package com.example.core.ai

import com.example.core.config.AiProviders
import com.example.core.config.Settings
import com.example.core.discovery.AiWire
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Configuration for one AI request, resolved from [Settings] but overridable so the
 * settings screen can test a key without saving it first.
 */
data class AiConfig(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 256,
    /**
     * Which of the three request shapes to speak. Resolved from the provider
     * catalogue rather than from the provider id, so a provider added as data gets a
     * working client without a code change — the wire formats are the invariant here,
     * the list of providers is not.
     */
    val wire: String = AiWire.OPENAI,
    /** Local servers legitimately have no key; requiring one would lock them out. */
    val requiresKey: Boolean = true
) {
    val isUsable: Boolean
        get() = (apiKey.isNotBlank() || !requiresKey) &&
            model.isNotBlank() &&
            effectiveBaseUrl.isNotBlank()

    val effectiveBaseUrl: String
        get() = baseUrl
            .ifBlank { ProviderCatalog.byId(provider)?.baseUrl.orEmpty() }
            .ifBlank { AiProviders.defaultBaseUrl(provider) }
            .trimEnd('/')

    companion object {
        fun from(s: Settings, maxTokens: Int = s.aiMaxTokens): AiConfig {
            val spec = ProviderCatalog.byId(s.aiProvider, s)
            // Read against the provider rather than off the top level, so switching
            // provider switches credential too. The loose top-level key is still
            // honoured for the provider it was entered against — see [ProviderProfiles].
            return AiConfig(
                provider = s.aiProvider,
                baseUrl = ProviderProfiles.baseUrlFor(s.aiProvider, s),
                apiKey = ProviderProfiles.keyFor(s.aiProvider, s),
                model = ProviderProfiles.modelFor(s.aiProvider, s)
                    .ifBlank { spec?.defaultModel.orEmpty() }
                    .ifBlank { AiProviders.defaultModel(s.aiProvider) },
                temperature = s.aiTemperature,
                maxTokens = maxTokens,
                wire = spec?.wire ?: legacyWire(s.aiProvider),
                requiresKey = spec?.needsKey ?: true
            )
        }

        /** For settings written before the catalogue existed. */
        private fun legacyWire(provider: String): String = when (provider) {
            AiProviders.ANTHROPIC -> AiWire.ANTHROPIC
            AiProviders.GEMINI -> AiWire.GEMINI
            else -> AiWire.OPENAI
        }
    }
}

/**
 * One HTTP client for every model provider.
 *
 * Three wire formats are spoken directly — OpenAI-compatible, Anthropic and Gemini —
 * because that covers nearly everything a user might point this at, including local
 * servers (Ollama, LM Studio, llama.cpp, vLLM) which all expose the OpenAI shape.
 * No SDK is used, so adding a fourth is a function, not a dependency.
 */
object AiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** A short client for inline completion, where a slow answer is a useless answer. */
    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    class AiException(message: String) : Exception(message)

    /**
     * One completion.
     *
     * [imageBase64], when present, is sent alongside the prompt in whichever encoding
     * the provider expects — this is what the "recognise a keyboard from a screenshot"
     * flow uses.
     */
    suspend fun complete(
        config: AiConfig,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String? = null,
        fast: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isUsable) {
                throw AiException("The AI provider is not configured. Add a key and a model in settings.")
            }
            val (url, body, headers) = when (config.wire) {
                AiWire.ANTHROPIC -> anthropicRequest(config, systemPrompt, userPrompt, imageBase64)
                AiWire.GEMINI -> geminiRequest(config, systemPrompt, userPrompt, imageBase64)
                else -> openAiRequest(config, systemPrompt, userPrompt, imageBase64)
            }

            val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }

            val call = (if (fast) fastClient else client).newCall(builder.build())
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiException("${response.code} ${response.message}: ${text.take(400)}")
                }
                when (config.wire) {
                    AiWire.ANTHROPIC -> parseAnthropic(text)
                    AiWire.GEMINI -> parseGemini(text)
                    else -> parseOpenAi(text)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // OpenAI-compatible
    // -----------------------------------------------------------------------

    private fun openAiRequest(
        c: AiConfig, system: String, user: String, image: String?
    ): Triple<String, JSONObject, Map<String, String>> {
        val messages = JSONArray()
        if (system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        val content: Any = if (image == null) user else JSONArray()
            .put(JSONObject().put("type", "text").put("text", user))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")
                )
            )
        messages.put(JSONObject().put("role", "user").put("content", content))

        val body = JSONObject()
            .put("model", c.model)
            .put("messages", messages)
            .put("temperature", c.temperature.toDouble())
            .put("max_tokens", c.maxTokens)

        return Triple(
            "${c.effectiveBaseUrl}/chat/completions",
            body,
            mapOf("Authorization" to "Bearer ${c.apiKey}")
        )
    }

    private fun parseOpenAi(raw: String): String {
        val o = JSONObject(raw)
        o.optJSONObject("error")?.let { throw AiException(it.optString("message", raw.take(300))) }
        val choice = o.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiException("No completion in the response.")
        val content = choice.optJSONObject("message")?.optString("content").orEmpty()
            .ifEmpty { choice.optString("text") }
        if (content.isEmpty()) throw AiException("Empty completion.")
        return content
    }

    // -----------------------------------------------------------------------
    // Anthropic
    // -----------------------------------------------------------------------

    private fun anthropicRequest(
        c: AiConfig, system: String, user: String, image: String?
    ): Triple<String, JSONObject, Map<String, String>> {
        val content = JSONArray()
        if (image != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", image)
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", user))

        val body = JSONObject()
            .put("model", c.model)
            .put("max_tokens", c.maxTokens)
            .put("temperature", c.temperature.toDouble())
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (system.isNotBlank()) body.put("system", system)

        return Triple(
            "${c.effectiveBaseUrl}/messages",
            body,
            mapOf(
                "x-api-key" to c.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
    }

    private fun parseAnthropic(raw: String): String {
        val o = JSONObject(raw)
        o.optJSONObject("error")?.let { throw AiException(it.optString("message", raw.take(300))) }
        val blocks = o.optJSONArray("content") ?: throw AiException("No content in the response.")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        if (sb.isEmpty()) throw AiException("Empty completion.")
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Gemini
    // -----------------------------------------------------------------------

    private fun geminiRequest(
        c: AiConfig, system: String, user: String, image: String?
    ): Triple<String, JSONObject, Map<String, String>> {
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (image != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", image)
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", c.temperature.toDouble())
                    .put("maxOutputTokens", c.maxTokens)
            )
        if (system.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        return Triple(
            "${c.effectiveBaseUrl}/models/${c.model}:generateContent",
            body,
            mapOf("x-goog-api-key" to c.apiKey)
        )
    }

    private fun parseGemini(raw: String): String {
        val o = JSONObject(raw)
        o.optJSONObject("error")?.let { throw AiException(it.optString("message", raw.take(300))) }
        val candidate = o.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("No candidates in the response.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException("Empty completion.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (sb.isEmpty()) throw AiException("Empty completion.")
        return sb.toString()
    }
}
