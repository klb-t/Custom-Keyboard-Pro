package com.example.core

import com.example.core.config.*
import com.example.core.layout.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewportExpertSettingsTest {
    private val knobs = listOf(Knobs.VIEWPORT_MINIMUM_KEYS_DP, Knobs.VIEWPORT_MINIMUM_KEYS_FRACTION,
        Knobs.TOOLBAR_ROW_HEIGHT_DP, Knobs.KEYBOARD_MAX_SCREEN_FRACTION)

    @Test fun `viewport policies have native bounded Expert controls and survive canonical persistence`() {
        val values = listOf(120.0, 0.6, 54.0, 0.75)
        var changed = Settings()
        knobs.zip(values).forEach { (knob, value) ->
            val spec = SettingsSchema.spec(knob.key)!!
            assertEquals(SettingKind.FLOAT, spec.kind)
            assertEquals(SettingsLevel.EXPERT, spec.minimumLevel)
            assertEquals(SettingsOwner.KEYBOARD_DEFAULTS, spec.owner)
            assertEquals(setOf(SettingsScope.KEYBOARD_DEFAULTS, SettingsScope.LAYOUT, SettingsScope.PANEL), spec.applicableScopes)
            assertFalse(SettingsHierarchy.visible(Settings()).any { it.key == knob.key })
            assertTrue(SettingsHierarchy.visible(SettingsHierarchy.selectLevel(Settings(), SettingsLevel.EXPERT)).any { it.key == knob.key })
            assertTrue(SettingsSchema.validatedValue(knob.key, knob.min - 1.0).isFailure)
            assertTrue(SettingsSchema.validatedValue(knob.key, knob.max + 1.0).isFailure)
            changed = SettingsSchema.withValue(changed, knob.key, value)
        }
        val restored = SettingsStore.fromJson(SettingsStore.toJson(changed))
        knobs.zip(values).forEach { (knob, value) -> assertEquals(value, restored.knob(knob), 0.000001) }
    }

    @Test fun `resolved layout and main panel policies drive the measured viewport and obey suppression`() {
        val baseLayer = LayerDef("base", rows = listOf(RowDef(listOf(KeyDef("a", label = "a")))))
        var layout = LayoutDef("viewport", "Viewport", mapOf("base" to baseLayer), elements = listOf(ElementDef("main")))
        layout = ScopedSettingsResolver.withOverride(layout, SettingsAddress(layout.id), Knobs.VIEWPORT_MINIMUM_KEYS_FRACTION.key, 0.6).getOrThrow()
        val address = SettingsAddress(layout.id, "main")
        listOf(Knobs.VIEWPORT_MINIMUM_KEYS_DP to 120.0, Knobs.TOOLBAR_ROW_HEIGHT_DP to 54.0,
            Knobs.KEYBOARD_MAX_SCREEN_FRACTION to 0.75).forEach { (knob, value) ->
            layout = ScopedSettingsResolver.withOverride(layout, address, knob.key, value).getOrThrow()
        }
        val settings = ScopedSettingsResolver.resolve(Settings(), layout, "main").snapshot
        val budget = KeyboardViewportBudget.fit(114f, 260f, 5, settings.knobFloat(Knobs.TOOLBAR_ROW_HEIGHT_DP), 20f, 12f,
            settings.knobFloat(Knobs.VIEWPORT_MINIMUM_KEYS_DP), settings.knobFloat(Knobs.VIEWPORT_MINIMUM_KEYS_FRACTION))
        assertEquals(68.4f, budget.keysDp, 0.001f)
        assertTrue(budget.totalDp <= 114f + 0.001f)
        assertEquals(0.75f, settings.knobFloat(Knobs.KEYBOARD_MAX_SCREEN_FRACTION), 0.0001f)
        val suppressed = ScopedSettingsResolver.resolve(Settings(localSettingsPolicy = SettingsOverridePolicy.DEFAULTS_ONLY), layout, "main").snapshot
        knobs.forEach { assertEquals(it.default, suppressed.knob(it), 0.000001) }
    }
}
