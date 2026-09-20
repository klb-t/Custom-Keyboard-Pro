package com.example.core.discovery

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import org.json.JSONObject

/**
 * What the user has set up for one particular provider.
 *
 * The app used to keep a single API key. That reads as a simplification and is a bug:
 * anybody with accounts at more than one provider — which is anybody comparing them —
 * had to retype a key every time they switched, and the moment they forgot, the
 * request went out to one provider carrying another one's credential and came back
 * 401. There is no way to tell that apart from a bad key, so the obvious conclusion is
 * that the app is broken.
 *
 * A key belongs to a provider, not to the app and not to a feature. Keeping it here
 * means switching provider is switching provider, and dictating through Groq while
 * writing through OpenRouter needs no explanation.
 */
data class ProviderProfile(
    val id: String,
    val apiKey: String = "",
    /** Overrides the catalogue's, for a local server or a proxy in front of a provider. */
    val baseUrl: String = "",
    val model: String = "",
    /** Model parameters the user set for this provider, standard and specific alike. */
    val params: Map<String, String> = emptyMap(),
    /** When a probe last got a usable answer, or 0. Shown, never used as permission. */
    val verifiedAt: Long = 0L,
    /** Why the last check failed, when it did. Kept so the screen can say. */
    val problem: String = ""
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        if (apiKey.isNotBlank()) put("apiKey", apiKey)
        if (baseUrl.isNotBlank()) put("baseUrl", baseUrl)
        if (model.isNotBlank()) put("model", model)
        if (params.isNotEmpty()) put("params", JSONObject(params.toMap()))
        if (verifiedAt != 0L) put("verifiedAt", verifiedAt)
        if (problem.isNotBlank()) put("problem", problem)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderProfile? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val params = o.optJSONObject("params")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } ?: emptyMap()
            return ProviderProfile(
                id = id,
                apiKey = o.optString("apiKey"),
                baseUrl = o.optString("baseUrl"),
                model = o.optString("model"),
                params = params,
                verifiedAt = o.optLong("verifiedAt", 0L),
                problem = o.optString("problem")
            )
        }
    }
}

/**
 * Every provider the user has set up, keyed by provider id.
 *
 * Stored as one JSON field rather than a table, for the same reason the rest of this
 * app's configuration is: it round-trips through export and import with everything
 * else, and a provider that did not exist when this build shipped needs no schema.
 *
 * The keys live here alongside everything else on the device. That is worth being
 * plain about rather than implying more: this is app-private storage, which keeps them
 * from other apps, and it is not a hardware-backed keystore. Anyone who can read the
 * device's app data can read them, and an exported settings file contains them.
 */
object ProviderProfiles {

    fun all(settings: Settings = SettingsStore.current): Map<String, ProviderProfile> {
        val root = runCatching { JSONObject(settings.providerProfilesJson) }.getOrNull()
            ?: return emptyMap()
        return root.keys().asSequence().mapNotNull { key ->
            root.optJSONObject(key)?.let { ProviderProfile.fromJson(it) }?.let { key to it }
        }.toMap()
    }

    fun of(id: String, settings: Settings = SettingsStore.current): ProviderProfile =
        all(settings)[id] ?: ProviderProfile(id)

    /**
     * The key to use for [id], falling back to the app-wide one.
     *
     * The fallback is what keeps a setup made before profiles existed working: a
     * single key that was set for whichever provider was selected still answers for
     * that provider. It is deliberately not consulted once a profile has a key of its
     * own, or switching provider would quietly go on using the previous credential —
     * which is the bug this exists to remove.
     */
    fun keyFor(id: String, settings: Settings = SettingsStore.current): String {
        val profile = all(settings)[id]
        if (profile != null && profile.apiKey.isNotBlank()) return profile.apiKey
        // Only the provider the loose key was entered against may use it.
        return if (id == settings.aiProvider) settings.aiApiKey else ""
    }

    fun baseUrlFor(id: String, settings: Settings = SettingsStore.current): String {
        val profile = all(settings)[id]
        if (profile != null && profile.baseUrl.isNotBlank()) return profile.baseUrl
        if (id == settings.aiProvider && settings.aiBaseUrl.isNotBlank()) return settings.aiBaseUrl
        return ProviderCatalog.byId(id, settings)?.baseUrl.orEmpty()
    }

    fun modelFor(id: String, settings: Settings = SettingsStore.current): String {
        val profile = all(settings)[id]
        if (profile != null && profile.model.isNotBlank()) return profile.model
        if (id == settings.aiProvider && settings.aiModel.isNotBlank()) return settings.aiModel
        return ProviderCatalog.byId(id, settings)?.defaultModel.orEmpty()
    }

    /** Every provider with a key, which is the list worth showing as "set up". */
    fun configured(settings: Settings = SettingsStore.current): List<ProviderProfile> =
        all(settings).values.filter { it.hasKey }.sortedBy { it.id }

    fun toJson(profiles: Map<String, ProviderProfile>): String =
        JSONObject().apply { profiles.forEach { (id, p) -> put(id, p.toJson()) } }.toString()

    fun put(profile: ProviderProfile) {
        SettingsStore.update { s ->
            s.copy(providerProfilesJson = toJson(all(s) + (profile.id to profile)))
        }
    }

    fun update(id: String, change: (ProviderProfile) -> ProviderProfile) {
        SettingsStore.update { s ->
            val current = all(s)
            val updated = change(current[id] ?: ProviderProfile(id))
            s.copy(providerProfilesJson = toJson(current + (id to updated)))
        }
    }

    fun forget(id: String) {
        SettingsStore.update { s ->
            s.copy(providerProfilesJson = toJson(all(s) - id))
        }
    }

    /**
     * Moves a pre-profiles setup into a profile, once.
     *
     * Called when the AI screen opens. Without it, somebody who had a working key
     * before this change would find the key box empty the first time they looked, and
     * "my key disappeared" is a worse failure than the one being fixed.
     */
    fun adoptLooseKey(settings: Settings = SettingsStore.current) {
        val id = settings.aiProvider
        if (id.isBlank() || settings.aiApiKey.isBlank()) return
        if (all(settings)[id]?.apiKey?.isNotBlank() == true) return
        update(id) {
            it.copy(
                apiKey = settings.aiApiKey,
                baseUrl = it.baseUrl.ifBlank { settings.aiBaseUrl },
                model = it.model.ifBlank { settings.aiModel }
            )
        }
    }
}
