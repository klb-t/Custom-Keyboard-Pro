package com.example.core

import com.example.core.config.Settings
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.config.SettingsOwner
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHierarchyTest {
    @Test fun `fresh defaults and old saved levels have distinct safe migrations`() {
        assertEquals(SettingsLevel.BASIC, Settings().settingsLevel)
        assertEquals(SettingsLevel.BASIC, SettingsStore.fromJson(JSONObject()).settingsLevel)
        assertEquals(SettingsLevel.ADVANCED, SettingsStore.fromJson(JSONObject().put("expertMode", false)).settingsLevel)
        assertEquals(SettingsLevel.EXPERT, SettingsStore.fromJson(JSONObject().put("expertMode", true)).settingsLevel)
        assertEquals(SettingsLevel.DEBUGGER, SettingsStore.fromJson(JSONObject().put("settingsLevel", "DEBUGGER")).settingsLevel)
        assertEquals(SettingsLevel.BASIC, SettingsStore.fromJson(JSONObject().put("settingsLevel", "unknown")).settingsLevel)
    }
    @Test fun `explicit persisted level is authoritative over contradictory compatibility flag`() {
        val basic = SettingsStore.fromJson(JSONObject().put("settingsLevel", "BASIC").put("expertMode", true))
        assertEquals(SettingsLevel.BASIC, SettingsHierarchy.level(basic))
        assertFalse(basic.expertMode)
        val debugger = SettingsStore.fromJson(JSONObject().put("settingsLevel", "DEBUGGER").put("expertMode", false))
        assertEquals(SettingsLevel.DEBUGGER, SettingsHierarchy.level(debugger))
        assertTrue(debugger.expertMode)
        val promoted = SettingsSchema.withValue(Settings(), "expertMode", true)
        assertEquals(SettingsLevel.EXPERT, promoted.settingsLevel)
        assertTrue(promoted.expertMode)
        assertEquals(promoted, SettingsStore.fromJson(SettingsStore.toJson(promoted)))
    }
    @Test fun `canonical serialization keeps directly constructed legacy expert mode on restart`() {
        val legacy = Settings(expertMode = true, keyGapDp = 7f)
        val encoded = SettingsStore.toJson(legacy)
        assertEquals("EXPERT", encoded.getString("settingsLevel"))
        assertTrue(encoded.getBoolean("expertMode"))
        val restored = SettingsStore.fromJson(encoded)
        assertEquals(SettingsHierarchy.selectLevel(legacy, SettingsLevel.EXPERT), restored)
        assertEquals(SettingsLevel.EXPERT, SettingsHierarchy.level(restored))
    }
    @Test fun `level roundtrip and visibility never reset values`() {
        val base = Settings(autoCapitalize = true, keyGapDp = 7f, aiApiKey = "local-secret")
        SettingsLevel.entries.forEach { level ->
            val selected = SettingsHierarchy.selectLevel(base, level)
            assertEquals(selected, SettingsStore.fromJson(SettingsStore.toJson(selected)))
            assertEquals(base.autoCapitalize, selected.autoCapitalize)
            assertEquals(base.aiApiKey, selected.aiApiKey)
            assertEquals(base.keyGapDp, selected.keyGapDp)
        }
        assertTrue(SettingsHierarchy.visible(base).size < SettingsSchema.all.size / 3)
        assertEquals(SettingsSchema.all.size, SettingsHierarchy.visible(SettingsHierarchy.selectLevel(base, SettingsLevel.EXPERT)).size)
    }
    @Test fun `search reveals that a matching option is hidden without silently raising level`() {
        val base = Settings()
        assertTrue(SettingsHierarchy.visible(base, "pocketBlockMediaKeys").isEmpty())
        assertEquals("pocketBlockMediaKeys", SettingsHierarchy.hidden(base, "pocketBlockMediaKeys").single().key)
        assertEquals(SettingsLevel.BASIC, base.settingsLevel)
    }
    @Test fun `ownership filters and reviewed reset cannot alter another owner`() {
        val base = Settings(keyGapDp = 7f, aiApiKey = "keep-local", mediaShowHidden = true)
        val expert = SettingsHierarchy.selectLevel(base, SettingsLevel.EXPERT)
        val keys = SettingsHierarchy.visible(expert, "keyGapDp", SettingsOwner.KEYBOARD_DEFAULTS).map { it.key }
        assertEquals(listOf("keyGapDp"), keys)
        val reset = SettingsHierarchy.reset(expert, keys).getOrThrow()
        assertEquals(Settings().keyGapDp, reset.keyGapDp)
        assertEquals("keep-local", reset.aiApiKey)
        assertTrue(reset.mediaShowHidden)
        assertTrue(SettingsHierarchy.reset(expert, listOf("keyGapDp", "invalid-key")).isFailure)
        assertEquals(7f, expert.keyGapDp)
    }
    @Test fun `every canonical setting has an owner a level and applicable default scope`() {
        val specs = SettingsSchema.all
        assertEquals(SettingsStore.toJson(Settings()).length(), specs.size)
        specs.forEach { spec ->
            assertNotNull(spec.owner)
            assertNotNull(spec.minimumLevel)
            assertTrue(spec.applicableScopes.contains(com.example.core.config.SettingsScope.KEYBOARD_DEFAULTS))
        }
        SettingsHierarchy.basicKeys.forEach { key -> assertNotNull("Unregistered Basic choice $key", SettingsSchema.spec(key)) }
        assertEquals(SettingsOwner.KEYBOARD_DEFAULTS, SettingsSchema.spec("autoCapitalize")!!.owner)
        assertEquals(SettingsOwner.KEYBOARD_DEFAULTS, SettingsSchema.spec("capitalisationRulesJson")!!.owner)
        assertEquals(SettingsOwner.APPLICATION, SettingsSchema.spec("aiApiKey")!!.owner)
    }
}
