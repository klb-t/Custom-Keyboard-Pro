package com.example.core.discovery

import android.content.Context
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutJson
import com.example.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/** The three request shapes that exist. This is the invariant; the providers are not. */
object AiWire {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"

    val ALL = listOf(OPENAI, ANTHROPIC, GEMINI)
}

/**
 * One model provider, described rather than coded.
 *
 * Adding a provider used to mean editing a `when` branch. It now means adding an
 * object to a JSON file — or typing one into the app, or letting a model write one —
 * because the only thing the code needs to know is which of the three wire formats to
 * speak, and that is a field.
 */
data class ProviderSpec(
    override val id: String,
    val label: String,
    val wire: String,
    val baseUrl: String,
    /** Path appended to [baseUrl] to list models. Empty means the provider has none. */
    val modelsPath: String = "/models",
    val defaultModel: String = "",
    val needsKey: Boolean = true,
    /** Runs on the user's own machine, so an empty key is normal and nothing leaves. */
    val local: Boolean = false,
    val docsUrl: String = ""
) : Discoverable {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("wire", wire)
        put("baseUrl", baseUrl)
        put("modelsPath", modelsPath)
        put("defaultModel", defaultModel)
        put("needsKey", needsKey)
        put("local", local)
        put("docsUrl", docsUrl)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderSpec? {
            val id = o.optString("id").ifBlank { return null }
            return ProviderSpec(
                id = id,
                label = o.optString("label").ifBlank { id },
                wire = o.optString("wire").ifBlank { AiWire.OPENAI }.lowercase(),
                baseUrl = o.optString("baseUrl").trimEnd('/'),
                modelsPath = o.optString("modelsPath", "/models"),
                defaultModel = o.optString("defaultModel"),
                needsKey = o.optBoolean("needsKey", true),
                local = o.optBoolean("local", false),
                docsUrl = o.optString("docsUrl")
            )
        }
    }
}

/**
 * Every provider the app knows about, from three places at once.
 *
 * The user's own entries win over the bundled catalogue, and the bundled catalogue is
 * an asset rather than a Kotlin file, so it can be replaced, extended or regenerated
 * without a build. The legacy three ids the app shipped with stay valid for anyone
 * whose settings still name them.
 */
object ProviderCatalog {

    private const val ASSET = "providers.json"

    private var bundled: List<ProviderSpec> = emptyList()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        bundled = try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            parseList(text)
        } catch (e: Exception) {
            AppLogger.e("ProviderCatalog", "could not read the bundled provider catalogue", e)
            emptyList()
        }
    }

    fun parseList(raw: String): List<ProviderSpec> = try {
        val arr = JSONArray(LayoutJson.stripCodeFence(raw))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ProviderSpec.fromJson(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    fun custom(settings: Settings = SettingsStore.current): List<ProviderSpec> =
        parseList(settings.customProvidersJson)

    /** User entries first, so a user's own base URL for "openai" overrides the bundled one. */
    fun all(settings: Settings = SettingsStore.current): List<ProviderSpec> {
        val merged = LinkedHashMap<String, ProviderSpec>()
        (custom(settings) + bundled + LEGACY).forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    fun allIds(settings: Settings = SettingsStore.current): List<String> = all(settings).map { it.id }

    fun byId(id: String, settings: Settings = SettingsStore.current): ProviderSpec? =
        all(settings).firstOrNull { it.id == id }

    fun addCustom(spec: ProviderSpec) {
        SettingsStore.update { s ->
            val existing = parseList(s.customProvidersJson).filterNot { it.id == spec.id }
            val arr = JSONArray().apply { (existing + spec).forEach { put(it.toJson()) } }
            s.copy(customProvidersJson = arr.toString())
        }
    }

    fun removeCustom(id: String) {
        SettingsStore.update { s ->
            val kept = parseList(s.customProvidersJson).filterNot { it.id == id }
            val arr = JSONArray().apply { kept.forEach { put(it.toJson()) } }
            s.copy(customProvidersJson = arr.toString())
        }
    }

    /**
     * The ids the app shipped with before the catalogue existed, so settings written
     * by an older build keep working. They are last in the merge, so the catalogue's
     * richer entry for the same id wins when one exists.
     */
    private val LEGACY = listOf(
        ProviderSpec(
            id = "openai_compatible",
            label = "OpenAI-compatible (any server speaking the OpenAI shape)",
            wire = AiWire.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini"
        ),
        ProviderSpec(
            id = "anthropic",
            label = "Anthropic",
            wire = AiWire.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-haiku-4-5-20251001"
        ),
        ProviderSpec(
            id = "gemini",
            label = "Google Gemini",
            wire = AiWire.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-2.5-flash"
        )
    )
}
