package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.config.SettingsProfile
import com.example.core.config.SettingsProfiles
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsSearch
import com.example.core.config.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsProfilesTest {
    private fun profile(values: JSONObject) = SettingsProfile("test", "Test", "", values)

    @Test fun `every bundled profile validates and applies without changing unrelated settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val list = JSONArray(context.assets.open("settings_profiles.json").bufferedReader().use { it.readText() })
        assertTrue(list.length() >= 10)
        val base = Settings(aiApiKey = "must-stay", netToken = "network-secret", keyCornerDp = 11f)
        val baseJson = SettingsStore.toJson(base)
        for (i in 0 until list.length()) {
            val p = SettingsProfiles.parseImport(list.getJSONObject(i).toString()).getOrThrow()
            val result = SettingsProfiles.apply(base, p).getOrThrow()
            val after = SettingsStore.toJson(result)
            baseJson.keys().forEach { key ->
                if (!p.values.has(key)) assertEquals("${p.id} changed unrelated $key", baseJson.opt(key)?.toString(), after.opt(key)?.toString())
            }
        }
    }

    @Test fun `exports exclude flat credentials nested credentials and opaque action data`() {
        val settings = Settings(aiApiKey = "TOP_SECRET", asrApiKey = "VOICE_SECRET", netToken = "NETWORK_SECRET",
            providerProfilesJson = """{"openai":{"key":"NESTED_SECRET"}}""",
            customProvidersJson = """[{"url":"https://example.test?token=URL_SECRET"}]""",
            engineStreamsJson = """[{"to":["http AUTH_SECRET"]}]""")
        val exported = SettingsStore.exportJson(settings)
        listOf("TOP_SECRET", "VOICE_SECRET", "NETWORK_SECRET", "NESTED_SECRET", "URL_SECRET", "AUTH_SECRET")
            .forEach { assertFalse("export leaked $it", exported.contains(it)) }
        val described = SettingsSchema.describeForModel(settings)
        assertFalse(described.contains("NESTED_SECRET"))
        assertFalse(described.contains("URL_SECRET"))
        assertFalse(described.contains("AUTH_SECRET"))
    }

    @Test fun `profile merge preserves credentials and arbitrary unlisted values`() {
        val base = Settings(aiApiKey = "keep", keyGapDp = 6f, keyboardKeepVisible = false)
        val result = SettingsProfiles.apply(base, profile(JSONObject().put("keyboardKeepVisible", true))).getOrThrow()
        assertEquals("keep", result.aiApiKey)
        assertEquals(6f, result.keyGapDp)
        assertTrue(result.keyboardKeepVisible)
    }

    @Test fun `profile import rejects credentials unknown settings invalid ranges and types atomically`() {
        val base = Settings(keyGapDp = 7f)
        val invalid = listOf(
            JSONObject().put("aiApiKey", "injected"),
            JSONObject().put("providerProfilesJson", "{}"),
            JSONObject().put("notASetting", true),
            JSONObject().put("keyGapDp", -5),
            JSONObject().put("pocketBlockKeys", "false-ish"),
            JSONObject().put("longPressMs", 140.5),
            JSONObject().put("customThemesJson", "{broken"),
            JSONObject().put("mediaSort", "random")
        )
        invalid.forEach { values ->
            values.put("keyCornerDp", 3)
            assertTrue(values.toString(), SettingsProfiles.apply(base, profile(values)).isFailure)
        }
        assertEquals(7f, base.keyGapDp)
    }

    @Test fun `unsupported profile version is refused without silent migration`() {
        val json = SettingsProfiles.encode(profile(JSONObject().put("keyGapDp", 4)))
        json.put("version", 999)
        assertTrue(SettingsProfiles.parseImport(json.toString()).isFailure)
    }

    @Test fun `saving only selected keys creates a partial reusable profile`() {
        val p = SettingsProfiles.capture("Lock", Settings(pocketBlockKeys = false), listOf("pocketBlockKeys", "aiApiKey"))
        assertEquals(setOf("pocketBlockKeys"), p.values.keys().asSequence().toSet())
        assertFalse(p.values.getBoolean("pocketBlockKeys"))
        val back = SettingsProfiles.parseImport(SettingsProfiles.encode(p).toString()).getOrThrow()
        assertEquals(p.id, back.id)
        assertEquals(p.values.toString(), back.values.toString())
    }

    @Test fun `invalid single edits leave settings intact and complete json is required`() {
        val base = Settings()
        listOf("NaN", "Infinity", "-Infinity", "-10").forEach {
            assertEquals(base, SettingsSchema.withValue(base, "keyGapDp", it))
        }
        listOf("[] trailing", "{", "true", "123").forEach {
            assertTrue(SettingsSchema.validatedValue("engineWiresJson", it).isFailure)
        }
        assertTrue(SettingsSchema.validatedValue("engineWiresJson", "[{\"on\":\"shake\"}]").isSuccess)
    }

    @Test fun `legacy unlocked hardware policy stays unlocked after migration`() {
        val legacy = JSONObject().put("pocketBlockKeys", false)
        val result = SettingsStore.fromJson(legacy)
        assertFalse(result.pocketBlockKeys)
        assertFalse(result.pocketBlockNavigation)
        assertFalse(result.pocketBlockOtherKeys)
    }

    @Test fun `local polish context search finds persistence without a model or account`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsSearch.init(context)
        val matches = SettingsSearch.matches("Chcę trzymać klawiaturę na wierzchu")
        assertTrue(matches.any { it.key == "keyboardKeepVisible" })
        assertTrue(SettingsSearch.matches("blokada przycisków fizycznych").any { it.key == "pocketBlockOtherKeys" })
    }
}
