package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.discovery.*
import com.example.core.setup.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupJourneyTest {
    private val catalogue by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProviderCatalog.parseList(context.assets.open("providers.json").bufferedReader().use { it.readText() })
    }

    @Test fun `embedding recommendation survives navigation without changing writing provider`() {
        val provider = catalogue.first { it.id == "gemini" }
        val wants = SetupWants(capabilities = setOf(AiCapability.EMBED))
        val journey = CapabilitySetup.recommendationJourney(provider, wants)!!
        val restored = SetupJourney.fromJson(journey.toJson())!!
        assertEquals(journey, restored)
        assertEquals(SetupTarget(AiCapability.EMBED, "gemini", "gemini-embedding-2"), restored.target)
        val route = CapabilitySetup.resolveTarget(restored.target, catalogue, restored.wants)!!
        assertTrue(route.provider.setupSteps.any { it.url.contains("aistudio.google.com") })
        val before = Settings()
        val after = CapabilitySetup.select(before, route)
        assertEquals(before.aiProvider, after.aiProvider)
        assertEquals(before.aiModel, after.aiModel)
        assertFalse(after.aiEnabled)
        assertFalse(ProviderProfiles.of("gemini", after).hasKey)
        assertTrue(ProviderProfiles.of("gemini", after).capabilities.getValue(AiCapability.EMBED).selected)
    }

    @Test fun `routes and recommendations apply the same hard policy filters`() {
        val good = ProviderSpec("good", "Good", baseUrl = "https://good.test", freeTier = true,
            privacy = Privacy.NO_TRAINING)
        val bad = good.copy(id = "bad", label = "Bad", freeTier = false, needsCard = true,
            privacy = Privacy.UNKNOWN)
        val providers = listOf(good, bad)
        listOf(SetupWants(freeOnly = true), SetupWants(noCard = true), SetupWants(avoidTraining = true)).forEach { wants ->
            assertEquals(listOf("good"), CapabilitySetup.allowedProviders(providers, wants).map { it.id })
            assertEquals(listOf("good"), SetupAdvisor.recommend(providers, wants).map { it.provider.id })
            assertNull(CapabilitySetup.resolveTarget(SetupTarget(AiCapability.CHAT, "bad", "model"), providers, wants))
            assertNull(CapabilitySetup.recommendationJourney(bad, wants))
        }
    }

    @Test fun `stale hosted target is rejected when policy becomes on device only`() {
        val target = SetupTarget(AiCapability.EMBED, "gemini", "gemini-embedding-2")
        val wants = SetupWants(capabilities = setOf(AiCapability.EMBED))
        assertNotNull(CapabilitySetup.resolveTarget(target, catalogue, wants))
        assertNull(CapabilitySetup.resolveTarget(target, catalogue, wants.copy(mustStayOnDevice = true)))
        assertNull(CapabilitySetup.resolveTarget(target, catalogue, wants.copy(avoidTraining = true)))
        assertNull(CapabilitySetup.resolveTarget(target, emptyList(), wants))
    }

    @Test fun `local label never admits LAN or remote endpoint to phone only setup`() {
        val local = ProviderSpec("self", "Self hosted", local = true, needsKey = false,
            privacy = Privacy.ON_DEVICE, baseUrl = "http://127.0.0.1:11434/v1")
        val wants = SetupWants(mustStayOnDevice = true)
        assertEquals(listOf(local), CapabilitySetup.allowedProviders(listOf(local), wants))
        listOf("http://192.168.1.9:11434/v1", "https://remote.test/v1").forEach { endpoint ->
            val overridden = local.copy(baseUrl = endpoint)
            assertTrue(CapabilitySetup.allowedProviders(listOf(overridden), wants).isEmpty())
            assertTrue(SetupAdvisor.recommend(listOf(overridden), wants).isEmpty())
        }
    }

    @Test fun `navigation codec retains policy but no credentials or free text`() {
        val journey = SetupJourney(SetupTarget(AiCapability.EMBED, "route", "exact-model"),
            SetupWants(capabilities = setOf(AiCapability.CHAT, AiCapability.EMBED), mustStayOnDevice = true,
                noCard = true, freeOnly = true, avoidTraining = true, preferOneAccount = true, notes = "private request"))
        val json = journey.toJson()
        assertFalse(json.contains("private request"))
        assertFalse(JSONObject(json).has("apiKey"))
        val restored = SetupJourney.fromJson(json)!!
        assertEquals(journey.target, restored.target)
        assertEquals(journey.wants.copy(notes = ""), restored.wants)
    }

    @Test fun `navigation codec rejects malformed unknown oversized and mistyped state`() {
        val valid = SetupJourney(SetupTarget(AiCapability.EMBED, "route", "model"), SetupWants()).toJson()
        listOf(null, "broken", "{}", "x".repeat(16_001),
            JSONObject(valid).put("capability", "unknown").toString(),
            JSONObject(valid).put("providerId", "x".repeat(513)).toString(),
            JSONObject(valid).put("model", "x".repeat(4001)).toString(),
            JSONObject(valid).put("mustStayOnDevice", "true").toString(),
            JSONObject(valid).put("capabilities", org.json.JSONArray().put("unknown")).toString()
        ).forEach { assertNull(SetupJourney.fromJson(it)) }
    }

    @Test fun `a displayed compromise cannot be selected until user changes its constraint`() {
        val paid = ProviderSpec("paid", "Paid", baseUrl = "https://paid.test", needsCard = true,
            privacy = Privacy.NO_TRAINING)
        val wants = SetupWants(freeOnly = true)
        assertNotNull(SetupAdvisor.fallback(listOf(paid), wants))
        assertNull(CapabilitySetup.recommendationJourney(paid, wants))
        assertNotNull(CapabilitySetup.recommendationJourney(paid, wants.copy(freeOnly = false)))
    }
}
