package com.example.core.discovery

import com.example.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What is known about one model, as opposed to what is known about its provider.
 *
 * Every field is optional because every source publishes a different subset, and a
 * fact that is absent has to read as absent rather than as zero — "context length 0"
 * and "free" are both lies that a missing value would tell.
 */
data class ModelFact(
    override val id: String,
    val label: String = id,
    val provider: String = "",
    val contextTokens: Long? = null,
    /** Currency per million input tokens, as the source states it. */
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
    /** "text", "image", "audio" — what it will accept. */
    val modalities: List<String> = emptyList(),
    val source: String = ""
) : Discoverable {

    /** Free to call, as distinct from unknown. */
    val isFree: Boolean? get() = when {
        promptPrice == null && completionPrice == null -> null
        else -> (promptPrice ?: 0.0) == 0.0 && (completionPrice ?: 0.0) == 0.0
    }

    fun describe(): String = buildList {
        contextTokens?.let { add("${it / 1000}k context") }
        when (isFree) {
            true -> add("free")
            false -> promptPrice?.let { add("%.2f/M in".format(it)) }
            null -> Unit
        }
        if (modalities.size > 1) add(modalities.joinToString("+"))
    }.joinToString(" · ")
}

/**
 * A place that publishes facts about models, described rather than coded.
 *
 * These matter for one specific reason: the setup advisor runs when the user has no
 * account anywhere, so anything it wants to know has to come from somewhere that does
 * not ask who is calling. Such places exist for *facts* — prices, context lengths,
 * what a model accepts — even though they essentially do not exist for inference.
 *
 * Which is the useful half. The advisor was never going to ask a model what to
 * recommend; it needs the numbers so it can weigh them itself.
 *
 * Described with the same [CallSpec] as everything else, so a source that changes its
 * shape, or a better one that appears next year, is an edited entry rather than a
 * release.
 */
data class FactSource(
    override val id: String,
    val label: String,
    val call: CallSpec,
    /** Where the array of models sits in the reply. */
    val itemPath: String = "data",
    /** Our field name to where that field sits inside one item. */
    val fields: Map<String, String> = emptyMap(),
    val needsKey: Boolean = false,
    val note: String = "",
    /** Turned off without being deleted, so a source that breaks can be parked. */
    val enabled: Boolean = true
) : Discoverable {

    companion object {
        fun fromJson(o: JSONObject): FactSource? {
            val id = o.optString("id").ifBlank { return null }
            val call = o.optJSONObject("call")?.let { CallSpec.fromJson(it) } ?: return null
            return FactSource(
                id = id,
                label = o.optString("label").ifBlank { id },
                call = call,
                itemPath = o.optString("itemPath", "data"),
                fields = o.optJSONObject("fields").toStringMap(),
                needsKey = o.optBoolean("needsKey", false),
                note = o.optString("note"),
                enabled = o.optBoolean("enabled", true)
            )
        }

        fun parseList(raw: String): List<FactSource> = try {
            val arr = JSONArray(com.example.core.layout.LayoutJson.stripCodeFenceArray(raw))
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Fetching model facts from somewhere that does not ask for a key.
 *
 * Deliberately separate from [ModelDiscovery], which asks a provider what it serves
 * and therefore needs that provider's key. This asks a third party what exists, which
 * is the only version of the question a user with no accounts can get answered.
 */
object FactsFetcher {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(source: FactSource, apiKey: String = ""): Result<List<ModelFact>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!source.enabled) error("${source.label} is turned off.")
                if (source.needsKey && apiKey.isBlank()) error("${source.label} needs a key.")

                val url = source.call.path
                if (!url.startsWith("https://")) error("A fact source must be fetched over https.")

                val request = Request.Builder().url(url).get()
                when (source.call.auth) {
                    AuthStyle.BEARER -> if (apiKey.isNotBlank()) {
                        request.addHeader("Authorization", "Bearer $apiKey")
                    }
                    AuthStyle.HEADER -> if (apiKey.isNotBlank() && source.call.authName.isNotBlank()) {
                        request.addHeader(source.call.authName, apiKey)
                    }
                    else -> Unit
                }
                source.call.headers.forEach { (name, value) -> request.addHeader(name, value) }

                client.newCall(request.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("${response.code} ${response.message}: ${body.take(200)}")
                    }
                    parse(body, source)
                }
            }.onFailure {
                AppLogger.d("Facts", "${source.id} did not answer: ${it.message}")
            }
        }

    internal fun parse(body: String, source: FactSource): List<ModelFact> {
        val root = JSONObject(body)
        val items = JsonPath.get(root, source.itemPath) as? JSONArray ?: return emptyList()
        val map = source.fields
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = text(item, map["id"], "id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ModelFact(
                id = id,
                label = text(item, map["label"], "name")?.ifBlank { null } ?: id,
                // "anthropic/claude-x" carries its provider in front of the slash,
                // which is worth reading rather than leaving the field empty.
                provider = text(item, map["provider"], null)?.ifBlank { null }
                    ?: id.substringBefore('/', ""),
                contextTokens = number(item, map["contextTokens"])?.toLong(),
                promptPrice = perMillion(number(item, map["promptPrice"])),
                completionPrice = perMillion(number(item, map["completionPrice"])),
                modalities = strings(item, map["modalities"]),
                source = source.id
            )
        }
    }

    /**
     * One field of one row, or null when the source does not publish it.
     *
     * The guard is the whole point. An empty path means "the whole document" to
     * [JsonPath] — correctly, and deliberately — so passing one through for a field
     * the source never mapped does not read nothing, it reads *everything*, and the
     * field comes back holding the entire JSON row. That is how "provider" ended up
     * containing a model's complete description.
     */
    private fun text(item: JSONObject, path: String?, fallback: String?): String? {
        val effective = path?.takeIf { it.isNotBlank() } ?: fallback ?: return null
        return JsonPath.string(item, effective)
    }

    private fun number(item: JSONObject, path: String?): Double? {
        if (path.isNullOrBlank()) return null
        return when (val value = JsonPath.get(item, path)) {
            is Number -> value.toDouble()
            // Several sources publish prices as strings, and "0" must survive as 0.0
            // rather than as "no price known".
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    /** Sources quote per-token; nobody reads per-token. */
    private fun perMillion(value: Double?): Double? = value?.let { it * 1_000_000 }

    private fun strings(item: JSONObject, path: String?): List<String> {
        if (path.isNullOrBlank()) return emptyList()
        return when (val value = JsonPath.get(item, path)) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
            is String -> listOf(value)
            else -> emptyList()
        }
    }

    /** Every enabled source, merged, with failures reported rather than thrown. */
    suspend fun gather(sources: List<FactSource>): DiscoveryOutcome<ModelFact> =
        Discovery.merge(
            sources.filter { it.enabled && !it.needsKey }.map { source ->
                object : DiscoverySource<ModelFact> {
                    override val id = source.id
                    override val label = source.label
                    override suspend fun discover(): Result<List<ModelFact>> = fetch(source)
                }
            }
        )
}
