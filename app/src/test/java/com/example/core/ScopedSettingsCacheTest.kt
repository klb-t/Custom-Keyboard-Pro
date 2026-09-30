package com.example.core

import com.example.core.config.*
import com.example.core.layout.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ScopedSettingsCacheTest {
    private fun fixture(): LayoutDef = LayoutDef("one", "One", linkedMapOf(
        "base" to LayerDef("base", listOf(RowDef(listOf(KeyDef("a"))))),
        "shift" to LayerDef("shift", listOf(RowDef(listOf(KeyDef("a")))))
    ))

    @Test fun transformedDisplayKeyCannotReplaceTheStoredPolicyOrCrossLayers() {
        val layout = ScopedSettingsResolver.withOverride(fixture(), SettingsAddress("one", layerName = "base", keyId = "a"),
            "autoCapitalize", true).getOrThrow()
        val displayKey = layout.base.allKeys.first().copy(label = "A", settingsOverrides =
            SettingsOverrides.EMPTY.with(SettingsScope.KEY, "autoCapitalize", false).getOrThrow())
        val cache = ScopedSettingsCache(1)
        assertTrue(cache.resolve(Settings(), layout, layerName = "base", key = displayKey).snapshot.autoCapitalize)
        assertFalse(cache.resolve(Settings(), layout, layerName = "shift", key = displayKey).snapshot.autoCapitalize)
        assertTrue(runCatching { cache.resolve(Settings(), layout, layerName = "gone", key = displayKey) }.isFailure)
    }

    @Test fun replacingDefaultsOrLayoutInvalidatesTheEffectivePolicy() {
        val cache = ScopedSettingsCache()
        val original = fixture()
        val defaults = Settings(autoCapitalize = false)
        assertFalse(cache.resolve(defaults, original).snapshot.autoCapitalize)
        assertTrue(cache.resolve(defaults.copy(autoCapitalize = true), original).snapshot.autoCapitalize)
        val changed = ScopedSettingsResolver.withOverride(original, SettingsAddress("one"), "autoCapitalize", true).getOrThrow()
        assertTrue(cache.resolve(defaults, changed).snapshot.autoCapitalize)
        assertFalse(cache.resolve(defaults.copy(localSettingsPolicy = SettingsOverridePolicy.DEFAULTS_ONLY), changed).snapshot.autoCapitalize)
    }
}
