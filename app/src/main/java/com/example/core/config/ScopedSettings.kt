package com.example.core.config

import com.example.core.layout.ElementDef
import com.example.core.layout.ElementPlacement
import com.example.core.layout.KeyDef
import com.example.core.layout.LayerDef
import com.example.core.layout.LayoutDef
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Layout elements are panels; the keys inside them are the leaf elements. */
enum class SettingsScope(val title: String) { KEYBOARD_DEFAULTS("Keyboard defaults"), LAYOUT("Layout"), PANEL("Panel"), KEY("Key") }
enum class SettingsOverridePolicy { CASCADE, LAYOUT_ONLY, DEFAULTS_ONLY }

/** An address names an instance, never the active layout at the time a delayed write runs. */
data class SettingsAddress(val layoutId: String, val panelId: String? = null, val layerName: String? = null, val keyId: String? = null) {
    init {
        require(layoutId.isNotBlank())
        require(panelId == null || panelId.isNotBlank())
        require(keyId == null || (keyId.isNotBlank() && !layerName.isNullOrBlank())) { "A key needs an exact layer." }
    }
    val scope: SettingsScope get() = if (keyId != null) SettingsScope.KEY else if (panelId != null) SettingsScope.PANEL else SettingsScope.LAYOUT
}

/** Immutable typed values; no mutable JSONObject escapes into the layout snapshot. */
sealed interface SettingOverrideValue {
    fun raw(): Any
    data class BooleanValue(val value: Boolean) : SettingOverrideValue { override fun raw(): Any = value }
    data class IntValue(val value: Int) : SettingOverrideValue { override fun raw(): Any = value }
    data class LongValue(val value: Long) : SettingOverrideValue { override fun raw(): Any = value }
    data class DecimalValue(val value: Double) : SettingOverrideValue { override fun raw(): Any = value }
    data class TextValue(val value: String) : SettingOverrideValue { override fun raw(): Any = value }
    data class ListValue(val value: List<String>) : SettingOverrideValue { override fun raw(): Any = JSONArray(value) }
}

class SettingsOverrides private constructor(values: Map<String, SettingOverrideValue>) {
    private val entries: Map<String, SettingOverrideValue> = Collections.unmodifiableMap(values.mapValues { (_, value) ->
        if (value is SettingOverrideValue.ListValue) SettingOverrideValue.ListValue(Collections.unmodifiableList(value.value.toList())) else value
    })
    val keys: Set<String> get() = entries.keys
    val isEmpty: Boolean get() = entries.isEmpty()
    operator fun get(key: String): SettingOverrideValue? = entries[key]
    fun without(key: String): SettingsOverrides = SettingsOverrides(entries - key)
    fun with(scope: SettingsScope, key: String, raw: Any?): Result<SettingsOverrides> = runCatching {
        if (raw == null || raw == JSONObject.NULL) return@runCatching without(key)
        SettingsOverrides(entries + (key to validate(scope, key, raw)))
    }
    fun toJson(): JSONObject = JSONObject().also { out -> entries.toSortedMap().forEach { (key, value) -> out.put(key, value.raw()) } }
    override fun equals(other: Any?): Boolean = other is SettingsOverrides && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = toJson().toString()

    companion object {
        val EMPTY = SettingsOverrides(emptyMap())
        fun fromJson(scope: SettingsScope, json: JSONObject?): SettingsOverrides {
            if (json == null) return EMPTY
            require(json.length() <= SettingsSchema.all.size) { "Too many scoped settings" }
            return SettingsOverrides(json.keys().asSequence().associateWith { validate(scope, it, json.get(it)) })
        }
        private fun validate(scope: SettingsScope, key: String, raw: Any?): SettingOverrideValue {
            val spec = SettingsSchema.spec(key) ?: error("Unknown setting override: $key")
            require(scope != SettingsScope.KEYBOARD_DEFAULTS && scope in spec.applicableScopes && !spec.secret) {
                "$key cannot be overridden in ${scope.title}."
            }
            val value = SettingsSchema.validatedValue(key, raw).getOrThrow()
            return when (spec.kind) {
                SettingKind.BOOL -> SettingOverrideValue.BooleanValue(value as Boolean)
                SettingKind.INT -> SettingOverrideValue.IntValue((value as Number).toInt())
                SettingKind.LONG, SettingKind.COLOR -> SettingOverrideValue.LongValue((value as Number).toLong())
                SettingKind.FLOAT -> SettingOverrideValue.DecimalValue((value as Number).toDouble())
                SettingKind.STRING_LIST -> SettingOverrideValue.ListValue((value as JSONArray).let { array ->
                    (0 until array.length()).map { array.getString(it) }.toList()
                })
                else -> SettingOverrideValue.TextValue(value.toString())
            }
        }
    }
}

data class SettingsSource(val scope: SettingsScope, val instanceId: String? = null) {
    val label: String get() = scope.title + (instanceId?.let { " · $it" } ?: "")
}
data class EffectiveSetting(val value: Any?, val source: SettingsSource, val inherited: Boolean, val suppressedSources: List<SettingsSource> = emptyList())
data class ResolvedSettings(val snapshot: Settings, val provenance: Map<String, SettingsSource>, val suppressed: Map<String, List<SettingsSource>> = emptyMap())

object ScopedSettingsResolver {
    /** Matches the renderer: the first visible docked panel follows active layer switching. */
    fun panelShowsLayer(layout: LayoutDef, panel: ElementDef, layerName: String): Boolean = panel.layer == layerName ||
        (panel.placement == ElementPlacement.DOCKED &&
            layout.elements.firstOrNull { it.visible && it.placement == ElementPlacement.DOCKED }?.id == panel.id &&
            layerName in layout.layers)
    /** Resolves whole snapshots for consumers. UI level never participates in this cascade. */
    fun resolve(base: Settings, layout: LayoutDef, panelId: String? = null, layerName: String? = null, key: KeyDef? = null): ResolvedSettings {
        val panel = panelId?.let { id -> layout.elements.singleOrNull { it.id == id } ?: error("Unknown or ambiguous panel: $id") }
        if (panel != null && layerName != null) require(panelShowsLayer(layout, panel, layerName)) { "Panel does not contain this layer." }
        if (key != null) {
            require(!layerName.isNullOrBlank()) { "Resolve a key with its exact layer." }
            require(layout.layers[layerName]?.allKeys?.count { it.id == key.id } == 1) { "Unknown or ambiguous key: ${key.id}" }
        }
        val values = mutableMapOf<String, Any>()
        val sources = mutableMapOf<String, SettingsSource>()
        val suppressed = mutableMapOf<String, List<SettingsSource>>()
        fun apply(overrides: SettingsOverrides, source: SettingsSource) {
            overrides.keys.forEach { setting ->
                val raw = overrides[setting]!!.raw()
                // Validate scope again when an override container is attached to a different owner.
                val spec = SettingsSchema.spec(setting) ?: error("Unknown setting: $setting")
                require(source.scope in spec.applicableScopes && !spec.secret) { "$setting cannot be overridden here." }
                val validated = SettingsSchema.validatedValue(setting, raw).getOrThrow()
                val allowed = when (base.localSettingsPolicy) {
                    SettingsOverridePolicy.CASCADE -> true
                    SettingsOverridePolicy.LAYOUT_ONLY -> source.scope == SettingsScope.LAYOUT
                    SettingsOverridePolicy.DEFAULTS_ONLY -> false
                }
                if (!allowed) {
                    suppressed[setting] = suppressed[setting].orEmpty() + source
                    return@forEach
                }
                values[setting] = validated
                sources[setting] = source
            }
        }
        apply(layout.settingsOverrides, SettingsSource(SettingsScope.LAYOUT, layout.id))
        panel?.let { apply(it.settingsOverrides, SettingsSource(SettingsScope.PANEL, it.id)) }
        key?.let { apply(it.settingsOverrides, SettingsSource(SettingsScope.KEY, it.id)) }
        // One codec pass per resolved context, rather than serializing every option separately.
        val snapshot = if (values.isEmpty()) base else SettingsStore.fromJson(SettingsStore.toJson(base).also { json ->
            values.forEach { (setting, raw) -> json.put(setting, raw) }
        })
        return ResolvedSettings(snapshot, Collections.unmodifiableMap(sources.toMap()),
            Collections.unmodifiableMap(suppressed.mapValues { (_, value) -> Collections.unmodifiableList(value.toList()) }))
    }

    fun overrides(layout: LayoutDef, address: SettingsAddress): SettingsOverrides {
        require(layout.id == address.layoutId) { "This address belongs to another layout." }
        return when (address.scope) {
            SettingsScope.LAYOUT -> layout.settingsOverrides
            SettingsScope.PANEL -> panel(layout, address).settingsOverrides
            SettingsScope.KEY -> key(layout, address).settingsOverrides
            SettingsScope.KEYBOARD_DEFAULTS -> error("Use SettingsStore for keyboard defaults")
        }
    }

    fun effective(base: Settings, layout: LayoutDef, address: SettingsAddress, setting: String): EffectiveSetting {
        val spec = SettingsSchema.spec(setting) ?: error("Unknown setting: $setting")
        require(address.scope in spec.applicableScopes) { "This setting has no ${address.scope.title} override." }
        overrides(layout, address) // check ownership before resolving
        val leaf = if (address.scope == SettingsScope.KEY) key(layout, address) else null
        val resolved = resolve(base, layout, address.panelId, address.layerName, leaf)
        return EffectiveSetting(SettingsSchema.valueOf(resolved.snapshot, setting),
            resolved.provenance[setting] ?: SettingsSource(SettingsScope.KEYBOARD_DEFAULTS),
            setting !in overrides(layout, address).keys || resolved.suppressed[setting]?.any { it.scope == address.scope } == true,
            resolved.suppressed[setting].orEmpty())
    }

    fun withOverride(layout: LayoutDef, address: SettingsAddress, setting: String, raw: Any?): Result<LayoutDef> = runCatching {
        val spec = SettingsSchema.spec(setting) ?: error("Unknown setting: $setting")
        require(address.scope in spec.applicableScopes && !spec.secret) { "${spec.label} is shared by the application." }
        val next = overrides(layout, address).with(address.scope, setting, raw).getOrThrow()
        when (address.scope) {
            SettingsScope.LAYOUT -> layout.copy(settingsOverrides = next)
            SettingsScope.PANEL -> layout.copy(elements = layout.elements.map { if (it.id == address.panelId) it.copy(settingsOverrides = next) else it })
            SettingsScope.KEY -> {
                val original = layout.layers.getValue(address.layerName!!)
                fun replace(item: KeyDef): KeyDef = if (item.id == address.keyId) item.copy(settingsOverrides = next) else item
                val updated = original.copy(rows = original.rows.map { row -> row.copy(keys = row.keys.map(::replace)) },
                    freeKeys = original.freeKeys.map(::replace))
                layout.copy(layers = layout.layers + (address.layerName to updated))
            }
            SettingsScope.KEYBOARD_DEFAULTS -> error("Use SettingsStore for keyboard defaults")
        }
    }

    /** Same partial profile registry, applied only to an explicitly selected instance. */
    fun applyProfile(layout: LayoutDef, address: SettingsAddress, profile: SettingsProfile): Result<LayoutDef> = runCatching {
        val values = SettingsProfiles.validate(profile.values)
        values.keys().asSequence().toList().fold(layout) { current, setting ->
            withOverride(current, address, setting, values.get(setting)).getOrThrow()
        }
    }

    private fun panel(layout: LayoutDef, address: SettingsAddress): ElementDef =
        layout.elements.singleOrNull { it.id == address.panelId } ?: error("Unknown or ambiguous panel: ${address.panelId}")
    private fun key(layout: LayoutDef, address: SettingsAddress): KeyDef {
        address.panelId?.let { require(panelShowsLayer(layout, panel(layout, address), address.layerName!!)) { "Panel does not contain this key layer." } }
        return layout.layers[address.layerName]?.allKeys?.singleOrNull { it.id == address.keyId }
            ?: error("Unknown or ambiguous key: ${address.layerName}/${address.keyId}")
    }
}
