package com.example.core.discovery

import android.content.Context
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutJson
import com.example.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The request shapes the app speaks in code. This is the invariant; the providers are not.
 *
 * Three of them are chat formats, which earn hand-written code because streaming,
 * message arrays and tool calls are not a template. The fourth is dictation's
 * multipart shape, which nearly every transcription endpoint copied from OpenAI and
 * which therefore covers more providers than any description of it would.
 *
 * Everything else — making pictures, making video, reading text out of one, any
 * provider nobody has heard of yet — is described by a [CallSpec] instead of coded.
 */
object AiWire {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"

    /** Multipart POST to an OpenAI-shaped `/audio/transcriptions`. */
    const val OPENAI_AUDIO = "openai_audio"

    /** Android's own recogniser. No network, no key, no provider. */
    const val ON_DEVICE = "on_device"

    /** Described by a [CallSpec] rather than by code. */
    const val DESCRIBED = "described"

    val CHAT = listOf(OPENAI, ANTHROPIC, GEMINI)

    val ALL = listOf(OPENAI, ANTHROPIC, GEMINI, OPENAI_AUDIO, ON_DEVICE, DESCRIBED)
}

/**
 * What happens to text sent to a provider.
 *
 * A fact about the provider, not an opinion about it, which is why it is a field and
 * not a sentence in the note: the advisor has to be able to rank on it, and a user who
 * says "nothing leaves my phone" has to be able to filter on it.
 */
object Privacy {
    /** Runs on hardware the user controls. Nothing is sent anywhere. */
    const val ON_DEVICE = "on_device"

    /** Sent, but the provider states it does not train on it. */
    const val NO_TRAINING = "no_training"

    /** Sent, and used for training unless the user opts out. */
    const val TRAINS_BY_DEFAULT = "trains_by_default"

    /** Not established. Shown as such rather than guessed at. */
    const val UNKNOWN = "unknown"

    val ALL = listOf(ON_DEVICE, NO_TRAINING, TRAINS_BY_DEFAULT, UNKNOWN)

    fun label(id: String): String = when (id) {
        ON_DEVICE -> "Nothing leaves the device"
        NO_TRAINING -> "Sent, not trained on"
        TRAINS_BY_DEFAULT -> "Sent, and trained on unless you opt out"
        else -> "Not established"
    }
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
    /**
     * Which request shape this speaks. Defaults to the OpenAI one because the
     * overwhelming majority do, and because [fromJson] already defaults a blank
     * wire to exactly that — the two ways of building a spec disagreeing about it
     * was a real inconsistency, and the compiler found it.
     */
    val wire: String = AiWire.OPENAI,
    val baseUrl: String,
    /** Path appended to [baseUrl] to list models. Empty means the provider has none. */
    val modelsPath: String = "/models",
    val defaultModel: String = "",
    val needsKey: Boolean = true,
    /** Runs on the user's own machine, so an empty key is normal and nothing leaves. */
    val local: Boolean = false,
    val docsUrl: String = "",
    /**
     * Everything this provider can do beyond chat, keyed by [AiCapability].
     *
     * Absent means "chat only", which is what every entry written before capabilities
     * existed meant — so nothing that already worked has to change to keep working.
     * A provider serving four capabilities is one entry with four endpoints rather
     * than four entries in four separate lists, which is the whole reason this is a
     * map and not another top-level list.
     */
    val capabilities: Map<String, CapabilitySpec> = emptyMap(),
    /** A gateway in front of other providers rather than a provider of its own. */
    val router: Boolean = false,
    /** Shown when choosing: why someone would pick this one. */
    val note: String = "",

    // --- what the setup advisor needs in order to weigh one against another ---
    //
    // These are facts about the world, so they live in the catalogue rather than in
    // the advisor. The advisor cannot ask a model which provider to recommend: it is
    // advising someone who has no account yet, which is the whole reason it exists.
    // So it reasons over these, and adding a provider teaches it something new
    // without anyone touching the ranking code.

    /** Usable without paying anything, for at least light use. */
    val freeTier: Boolean = false,
    /** Wants payment details before it will answer at all. */
    val needsCard: Boolean = false,
    /** Where to get a key, when that is a different page from [docsUrl]. */
    val signupUrl: String = "",
    /** What becomes of the text sent here. See [Privacy]. */
    val privacy: String = Privacy.UNKNOWN,
    /** Short tags the advisor matches against what the user says they want. */
    val strengths: List<String> = emptyList(),
    /**
     * What this provider's keys look like, as a regular expression.
     *
     * Optional and expected to be absent for most: prefixes change and nobody updates
     * a keyboard when they do, so [ApiKeys] falls back to the shape of the thing. It
     * earns its place where it disambiguates — telling an OpenRouter key from an
     * Anthropic one when both have been on the clipboard.
     */
    val keyPattern: String = ""
) : Discoverable {

    /**
     * Chat is described by the top-level fields rather than by an entry in the map,
     * because that is what every provider written before capabilities existed said.
     *
     * The fallback is conditional on the wire actually being a chat format: a
     * provider that only makes pictures has a base URL too, and reading that as
     * "it can also chat" would offer the user an endpoint that answers 404.
     */
    fun capability(id: String): CapabilitySpec? = when {
        id == AiCapability.CHAT && !capabilities.containsKey(id) ->
            if (baseUrl.isBlank() || wire !in AiWire.CHAT) null
            else CapabilitySpec(wire = wire, defaultModel = defaultModel, modelsPath = modelsPath)
        else -> capabilities[id]
    }

    fun can(id: String): Boolean = capability(id) != null

    val abilities: List<String> get() = AiCapability.ALL.filter { can(it) }

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
        if (router) put("router", true)
        if (note.isNotBlank()) put("note", note)
        if (freeTier) put("freeTier", true)
        if (needsCard) put("needsCard", true)
        if (signupUrl.isNotBlank()) put("signupUrl", signupUrl)
        if (privacy != Privacy.UNKNOWN) put("privacy", privacy)
        if (strengths.isNotEmpty()) put("strengths", JSONArray(strengths))
        if (keyPattern.isNotBlank()) put("keyPattern", keyPattern)
        if (capabilities.isNotEmpty()) {
            put("capabilities", JSONObject().apply {
                capabilities.forEach { (id, spec) -> put(id, spec.toJson()) }
            })
        }
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
                docsUrl = o.optString("docsUrl"),
                capabilities = o.optJSONObject("capabilities")?.let { caps ->
                    buildMap {
                        caps.keys().forEach { key ->
                            caps.optJSONObject(key)?.let { put(key, CapabilitySpec.fromJson(it)) }
                        }
                    }
                } ?: emptyMap(),
                router = o.optBoolean("router", false),
                note = o.optString("note"),
                freeTier = o.optBoolean("freeTier", false),
                needsCard = o.optBoolean("needsCard", false),
                signupUrl = o.optString("signupUrl"),
                privacy = o.optString("privacy").ifBlank { Privacy.UNKNOWN }.lowercase(),
                keyPattern = o.optString("keyPattern"),
                strengths = o.optJSONArray("strengths").toStringList()
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
        val arr = JSONArray(LayoutJson.stripCodeFenceArray(raw))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ProviderSpec.fromJson(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    fun custom(settings: Settings = SettingsStore.current): List<ProviderSpec> =
        parseList(settings.customProvidersJson)

    /** Downloaded from the catalogue address, if one is set. */
    fun fetched(settings: Settings = SettingsStore.current): List<ProviderSpec> =
        parseList(settings.fetchedProvidersJson)

    /**
     * Everything the app knows about, in order of how much it should be trusted.
     *
     * The user's own entries first, so a base URL somebody typed on purpose always
     * wins. Then anything downloaded, which is how a provider that appeared after
     * this build shipped becomes usable without one. Then the bundled file, then the
     * three ids the app shipped with before any of this existed.
     */
    fun all(settings: Settings = SettingsStore.current): List<ProviderSpec> {
        val merged = LinkedHashMap<String, ProviderSpec>()
        (custom(settings) + fetched(settings) + bundled + LEGACY)
            .forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    fun allIds(settings: Settings = SettingsStore.current): List<String> = all(settings).map { it.id }

    fun byId(id: String, settings: Settings = SettingsStore.current): ProviderSpec? =
        all(settings).firstOrNull { it.id == id }

    /**
     * Every provider that can do one particular thing.
     *
     * This is what a settings screen asks when it offers a choice of somewhere to
     * send dictation, or a picture, or a request for a picture. It never has to know
     * which providers those are — only what it needs done.
     */
    fun serving(
        capability: String,
        settings: Settings = SettingsStore.current
    ): List<ProviderSpec> = all(settings).filter { it.can(capability) }

    /** Every capability at least one known provider serves, in a stable order. */
    fun capabilitiesOffered(settings: Settings = SettingsStore.current): List<String> =
        AiCapability.ALL.filter { cap -> all(settings).any { it.can(cap) } }

    /** Gateways in front of other providers — one key, many models. */
    fun routers(settings: Settings = SettingsStore.current): List<ProviderSpec> =
        all(settings).filter { it.router }

    /** Providers of their own models, as opposed to [routers] and local servers. */
    fun direct(settings: Settings = SettingsStore.current): List<ProviderSpec> =
        all(settings).filter { !it.router && !it.local && it.id != "openai_compatible" }

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
