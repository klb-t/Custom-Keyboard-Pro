package com.example.core.config

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A versioned, partial settings patch. Applying a profile never resets unrelated settings. */
data class SettingsProfile(
    val id: String,
    val name: String,
    val description: String,
    val values: JSONObject,
    val builtIn: Boolean = false
)

object SettingsProfiles {
    const val FORMAT = "io.matrix.settings-profile"
    const val VERSION = 1
    private const val FILE = "settings_profiles"
    private const val KEY = "profiles"
    private var context: Context? = null
    private var bundled: List<SettingsProfile> = emptyList()
    private var personal: List<SettingsProfile> = emptyList()
    private var personalReadError: String? = null
    var loadWarnings: List<String> = emptyList()
        private set

    // Arbitrary code, endpoints and private data can embed credentials without a field
    // called "password". Portable snapshots omit these instead of guessing by regex.
    private val localOnlyKeys = setOf(
        "syncClipboard", "syncSettings", "syncImages", "syncMaxItems", "syncMaxImageMb",
        "providerProfilesJson", "customProvidersJson", "fetchedProvidersJson", "discoveredModelsJson",
        "engineWiresJson", "engineStreamsJson", "macrosJson", "ioActionProfilesJson",
        "wordListSourcesJson", "aiCustomTasksJson", "generatedPanelsJson", "properNouns",
        "aiBaseUrl", "asrRemoteUrl", "catalogUrl", "pocketUnlockPhrase", "pocketUnlockKeys",
        "gestureSwipeUp", "gestureSwipeDown", "gestureSwipeLeft", "gestureSwipeRight"
    )

    fun isPortable(spec: SettingSpec): Boolean = !spec.secret && spec.key !in localOnlyKeys
    fun excludedKeys(): List<String> = SettingsSchema.all.filterNot(::isPortable).map { it.key }

    @Synchronized
    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        bundled = runCatching {
            val entries = JSONArray(ctx.assets.open("settings_profiles.json").bufferedReader().use { it.readText() })
            (0 until entries.length()).map { decode(entries.getJSONObject(it), true) }
        }.getOrElse {
            loadWarnings = loadWarnings + "Bundled profiles could not be loaded: ${it.message}"
            emptyList()
        }
        personal = runCatching {
            val file = profileFile(ctx)
            val raw = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                file.openRead().bufferedReader().use { it.readText() }
            } else ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]")
            val entries = JSONArray(raw)
            (0 until entries.length()).map { decode(entries.getJSONObject(it), false) }
        }.getOrElse {
            personalReadError = "Saved profiles could not be opened; existing data was preserved: ${it.message}"
            loadWarnings = loadWarnings + personalReadError!!
            emptyList()
        }
    }

    @Synchronized fun all(): List<SettingsProfile> = bundled + personal

    /** Captures only the requested scope, so a pocket profile does not change your theme. */
    fun capture(name: String, settings: Settings, keys: Collection<String> = SettingsSchema.all.map { it.key }): SettingsProfile {
        require(name.isNotBlank()) { "Give this profile a name" }
        val json = portableValues(settings, keys)
        require(json.length() > 0) { "This selection contains no portable settings" }
        return SettingsProfile(UUID.randomUUID().toString(), name.trim().take(100), "Saved on this device", json)
    }

    fun portableValues(settings: Settings, keys: Collection<String> = SettingsSchema.all.map { it.key }): JSONObject {
        val current = SettingsStore.toJson(settings)
        return JSONObject().apply {
            keys.forEach { key ->
                val spec = SettingsSchema.spec(key)
                if (spec != null && isPortable(spec)) put(key, current.get(key))
            }
        }
    }

    /** Fully validate before applying; no successful prefix of a broken patch is written. */
    fun apply(base: Settings, profile: SettingsProfile): Result<Settings> = runCatching {
        val validated = validate(profile.values)
        val merged = SettingsStore.toJson(base)
        validated.keys().forEach { key -> merged.put(key, validated.get(key)) }
        SettingsStore.fromJson(merged)
    }

    fun validate(values: JSONObject): JSONObject {
        require(values.length() <= SettingsSchema.all.size) { "Too many settings in this profile" }
        return JSONObject().apply {
            values.keys().forEach { key ->
                val spec = SettingsSchema.spec(key) ?: error("Unknown setting: $key. Nothing was applied.")
                require(isPortable(spec)) { "$key contains local credentials or private data; edit it on this device instead." }
                put(key, SettingsSchema.validatedValue(key, values.get(key)).getOrThrow())
            }
        }
    }

    fun parseImport(raw: String): Result<SettingsProfile> = runCatching {
        require(raw.length <= 2_000_000) { "Settings profile is too large" }
        val json = JSONObject(LayoutJson.stripCodeFence(raw))
        if (json.has("format") || json.has("values")) decode(json, false)
        else SettingsProfile(UUID.randomUUID().toString(), "Imported settings", "Legacy settings patch", validate(json))
    }

    fun encode(profile: SettingsProfile): JSONObject = JSONObject()
        .put("format", FORMAT).put("version", VERSION).put("id", profile.id)
        .put("name", profile.name).put("description", profile.description)
        .put("values", validate(profile.values))

    private fun decode(json: JSONObject, builtIn: Boolean): SettingsProfile {
        require(json.optString("format") == FORMAT) { "This is not an IO Matrix settings profile" }
        require(json.optInt("version") == VERSION) { "Unsupported profile version: ${json.opt("version")}" }
        val name = json.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 100) { "Profile name must contain 1–100 characters" }
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return SettingsProfile(id, name, json.optString("description").take(1000), validate(json.getJSONObject("values")), builtIn)
    }

    suspend fun save(profile: SettingsProfile) = withContext(Dispatchers.IO) {
        synchronized(this@SettingsProfiles) {
            check(personalReadError == null) { personalReadError.orEmpty() }
            val clean = profile.copy(id = if (profile.builtIn || bundled.any { it.id == profile.id }) UUID.randomUUID().toString() else profile.id,
                values = validate(profile.values), builtIn = false)
            val next = personal.filterNot { it.id == clean.id } + clean
            persist(next)
            personal = next
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(this@SettingsProfiles) {
            check(personalReadError == null) { personalReadError.orEmpty() }
            val next = personal.filterNot { it.id == id }
            persist(next)
            personal = next
        }
    }

    private fun profileFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "settings-profiles-v1.json"))

    private fun persist(profiles: List<SettingsProfile>) {
        val ctx = context ?: error("Profile storage has not been initialised")
        val file = profileFile(ctx)
        val bytes = JSONArray(profiles.map(::encode)).toString().toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        // The new file is durable before removing the old preferences representation.
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun changedKeys(base: Settings, profile: SettingsProfile): List<String> {
        val json = SettingsStore.toJson(base)
        return profile.values.keys().asSequence().filter { key ->
            !equivalent(json.opt(key), profile.values.opt(key))
        }.toList()
    }

    fun equivalent(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) {
        kotlin.math.abs(a.toDouble() - b.toDouble()) < 0.000001
    } else a?.toString() == b?.toString()
}
