package com.example.core.config

import com.example.core.layout.KeyDef
import com.example.core.layout.LayoutDef

/** Small host-local cache: no JSON validation or snapshot serialization per pointer sample. */
class ScopedSettingsCache(private val capacity: Int = 128) {
    private data class Address(val panel: String?, val layer: String?, val key: String?)
    private var defaults: Settings? = null
    private var definition: LayoutDef? = null
    private val entries = linkedMapOf<Address, ResolvedSettings>()

    fun resolve(base: Settings, layout: LayoutDef, panelId: String? = null, layerName: String? = null, key: KeyDef? = null): ResolvedSettings {
        if (base !== defaults || layout !== definition) {
            entries.clear()
            defaults = base
            definition = layout
        }
        val address = Address(panelId, layerName, key?.id)
        entries[address]?.let { return it }
        // Find the stored instance: transformed display keys may change bindings/labels,
        // but cannot smuggle a different settings override into the same address.
        val stored = key?.let { requested ->
            require(!layerName.isNullOrBlank())
            layout.layers[layerName]?.allKeys?.singleOrNull { it.id == requested.id }
                ?: error("Key no longer belongs to this layout/layer.")
        }
        return ScopedSettingsResolver.resolve(base, layout, panelId, layerName, stored).also {
            if (entries.size >= capacity.coerceAtLeast(1)) entries.remove(entries.keys.first())
            entries[address] = it
        }
    }
}
