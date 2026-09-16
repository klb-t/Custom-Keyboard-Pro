package com.example.core.setup

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.AiCapability
import com.example.core.discovery.AiWire
import com.example.core.discovery.Discovery
import com.example.core.discovery.DiscoverySource
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderSpec
import com.example.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Keeping the catalogue current, which a file shipped inside the APK cannot be.
 *
 * Providers appear, endpoints move, free tiers end. The bundled catalogue is a
 * snapshot of the day the app was built, and the whole point of making it data was so
 * that it would not have to stay that way — but nothing was actually refreshing it,
 * which made it data in form and a constant in practice.
 *
 * The URL is a setting with no default. That is deliberate: shipping one would point
 * every install at somewhere of my choosing and make a keyboard that works offline
 * quietly phone home. The user says where, or nowhere.
 */
object CatalogUpdate {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** A [DiscoverySource] so this merges with the bundled and user entries by ordering alone. */
    fun source(url: String): DiscoverySource<ProviderSpec> = object : DiscoverySource<ProviderSpec> {
        override val id = "catalog:$url"
        override val label = "Catalogue at $url"
        override suspend fun discover(): Result<List<ProviderSpec>> = fetch(url)
    }

    suspend fun fetch(url: String): Result<List<ProviderSpec>> = withContext(Dispatchers.IO) {
        runCatching {
            if (url.isBlank()) error("No catalogue address is set.")
            if (!url.startsWith("https://")) error("A catalogue must be fetched over https.")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("${response.code} ${response.message}: ${body.take(200)}")
                }
                val parsed = ProviderCatalog.parseList(body)
                if (parsed.isEmpty()) error("That address returned no providers this app could read.")
                parsed
            }
        }
    }

    /**
     * Fetches and stores, keeping whatever the user wrote themselves.
     *
     * Downloaded entries are held apart from the user's own, so an update can never
     * overwrite a base URL somebody typed on purpose — the merge order in
     * [ProviderCatalog] already puts user entries first, and this keeps that true by
     * not mixing the two lists in the first place.
     */
    suspend fun refresh(settings: Settings = SettingsStore.current): Result<Int> {
        val result = fetch(settings.catalogUrl)
        result.onSuccess { providers ->
            val own = ProviderCatalog.custom(settings).map { it.id }.toSet()
            val fetched = providers.filterNot { it.id in own }
            SettingsStore.update { s ->
                s.copy(
                    fetchedProvidersJson = JSONArray()
                        .apply { fetched.forEach { put(it.toJson()) } }
                        .toString(),
                    catalogFetchedAt = System.currentTimeMillis()
                )
            }
            AppLogger.d(
                "Catalog",
                "fetched ${fetched.size} providers from ${settings.catalogUrl}" +
                    if (own.isNotEmpty()) "; kept ${own.size} of your own" else ""
            )
        }.onFailure {
            AppLogger.e("Catalog", "could not refresh from ${settings.catalogUrl}: ${it.message}")
        }
        return result.map { it.size }
    }
}

/** What a probe found out about one provider. */
data class ProbeResult(
    val provider: String,
    val reachable: Boolean,
    /** Models it named, when it would say. */
    val models: List<String> = emptyList(),
    /** Why not, when it would not answer. */
    val problem: String? = null
) {
    val usable: Boolean get() = reachable && problem == null
}

/**
 * Asking a provider whether it is actually there.
 *
 * Two quite different questions wear the same shape. For something hosted, it is "is
 * this key good" — which cannot be answered without asking. For something local, it is
 * "is anything listening on this port", and that one is worth asking *unprompted*:
 * somebody running Ollama on their own phone should be offered it rather than having
 * to know it is an option.
 */
object ProviderProbe {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Short, because this runs against a list and a dead port must not hold
            // up the ones behind it.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(provider: ProviderSpec, apiKey: String = ""): ProbeResult =
        withContext(Dispatchers.IO) {
            if (provider.wire == AiWire.ON_DEVICE) {
                return@withContext ProbeResult(provider.id, reachable = true)
            }
            val base = provider.baseUrl.trimEnd('/')
            val path = provider.capability(AiCapability.CHAT)?.modelsPath.orEmpty()
                .ifBlank { provider.modelsPath }
            if (base.isBlank() || path.isBlank()) {
                return@withContext ProbeResult(
                    provider.id, reachable = false,
                    problem = "This one publishes no list to ask."
                )
            }
            if (provider.needsKey && apiKey.isBlank()) {
                return@withContext ProbeResult(
                    provider.id, reachable = false, problem = "Needs a key first."
                )
            }

            runCatching {
                val url = if (provider.wire == AiWire.GEMINI) "$base$path?key=$apiKey" else "$base$path"
                val builder = Request.Builder().url(url).get()
                when (provider.wire) {
                    AiWire.ANTHROPIC -> {
                        builder.addHeader("x-api-key", apiKey)
                        builder.addHeader("anthropic-version", "2023-06-01")
                    }
                    AiWire.GEMINI -> Unit
                    else -> if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
                }
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> ProbeResult(
                            provider.id, reachable = true, models = readIds(body)
                        )
                        response.code == 401 || response.code == 403 -> ProbeResult(
                            provider.id, reachable = true,
                            problem = "It answered, but would not accept that key."
                        )
                        else -> ProbeResult(
                            provider.id, reachable = true,
                            problem = "${response.code} ${response.message}"
                        )
                    }
                }
            }.getOrElse { error ->
                ProbeResult(
                    provider.id, reachable = false,
                    problem = error.message ?: "Could not be reached."
                )
            }
        }

    /**
     * Everything on this device or network that answers without being asked for a key.
     *
     * This is the one piece of discovery that can produce a working setup for someone
     * who has no accounts at all, so it runs on its own rather than waiting to be
     * asked.
     */
    suspend fun findLocal(settings: Settings = SettingsStore.current): List<ProbeResult> =
        ProviderCatalog.all(settings)
            .filter { it.local && !it.needsKey }
            .map { probe(it) }
            .filter { it.usable }

    private fun readIds(body: String): List<String> = runCatching {
        val root = org.json.JSONObject(body)
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is String -> item
                is org.json.JSONObject ->
                    item.optString("id").ifBlank { item.optString("name") }.removePrefix("models/")
                else -> null
            }
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}
