package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.discovery.*
import com.example.core.setup.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilitySetupTest {
    @Before fun initializeApplicationCatalogue() {
        // Route profiles leave endpoint overrides blank to follow the app's loaded catalogue.
        ProviderCatalog.init(ApplicationProvider.getApplicationContext<Context>())
    }

    private val catalogue: List<ProviderSpec> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProviderCatalog.parseList(context.assets.open("providers.json").bufferedReader().use { it.readText() })
    }

    @Test fun `fresh user finds Google Embedding 2 routes without any account and saves only embedding`() {
        val fresh = Settings()
        val routes = CapabilitySetup.routes(catalogue, AiCapability.EMBED, "Google Embedding 2")
        assertEquals(setOf("gemini", "openrouter"), routes.map { it.provider.id }.toSet())
        val direct = routes.single { it.provider.id == "gemini" }
        assertEquals("gemini-embedding-2", direct.model)
        assertTrue(direct.provider.setupSteps.any { it.url.contains("aistudio.google.com") })
        assertTrue(direct.provider.capability(AiCapability.EMBED)!!.verifiedOn.isNotBlank())
        val saved = CapabilitySetup.select(fresh, direct, mapOf("dimensions" to "768"))
        assertEquals(fresh.aiProvider, saved.aiProvider)
        assertEquals(fresh.aiModel, saved.aiModel)
        assertFalse(saved.aiEnabled)
        val profile = ProviderProfiles.of("gemini", saved)
        assertFalse(profile.hasKey)
        assertTrue(profile.capabilities.getValue(AiCapability.EMBED).selected)
        assertEquals("768", profile.capabilities.getValue(AiCapability.EMBED).params["dimensions"])
        assertEquals(0L, profile.capabilities.getValue(AiCapability.EMBED).verifiedAt)
    }

    @Test fun `switching capability routes preserves other capabilities and credentials`() {
        val routes = CapabilitySetup.routes(catalogue, AiCapability.EMBED, "Google Embedding 2")
        val original = ProviderProfile("gemini", "google-key", model = "chat-model",
            capabilities = mapOf(AiCapability.CHAT to CapabilityProfile("chat-model", selected = true)))
        var saved = Settings(providerProfilesJson = ProviderProfiles.toJson(mapOf("gemini" to original)))
        saved = CapabilitySetup.select(saved, routes.single { it.provider.id == "gemini" })
        saved = CapabilitySetup.select(saved, routes.single { it.provider.id == "openrouter" })
        val profiles = ProviderProfiles.all(saved)
        assertFalse(profiles.getValue("gemini").capabilities.getValue(AiCapability.EMBED).selected)
        assertTrue(profiles.getValue("openrouter").capabilities.getValue(AiCapability.EMBED).selected)
        assertTrue(profiles.getValue("gemini").capabilities.getValue(AiCapability.CHAT).selected)
        assertEquals("google-key", profiles.getValue("gemini").apiKey)
        assertEquals("chat-model", profiles.getValue("gemini").model)
    }

    @Test fun `changing chat providers cannot send old provider key to new endpoint`() {
        val original = Settings(aiProvider = "gemini", aiApiKey = "old-key", aiBaseUrl = "https://old.example", aiModel = "old-chat")
        val target = catalogue.first { it.id == "openrouter" }
        val saved = CapabilitySetup.select(original, SetupRoute(target, AiCapability.CHAT, target.defaultModel, "test"))
        assertEquals("old-key", ProviderProfiles.keyFor("gemini", saved))
        assertEquals("", ProviderProfiles.keyFor("openrouter", saved))
        assertEquals("", saved.aiApiKey)
        assertEquals(target.baseUrl, ProviderProfiles.baseUrlFor("openrouter", saved))
    }

    @Test fun `switching dictation route removes unrelated legacy endpoint and credential`() {
        val original = Settings(asrApiKey = "old-transcription-key", asrRemoteUrl = "https://old-transcriber.example")
        val provider = catalogue.first { it.id == "groq" }
        val model = provider.capability(AiCapability.TRANSCRIBE)!!.defaultModel
        val saved = CapabilitySetup.select(original, SetupRoute(provider, AiCapability.TRANSCRIBE, model, "test"))
        assertEquals("", saved.asrApiKey)
        assertEquals("", saved.asrRemoteUrl)
        assertEquals("groq", saved.asrProvider)
        assertEquals(model, saved.asrModel)
    }

    @Test fun `declared cleared key does not fall back to legacy key`() {
        val s = Settings(aiProvider = "gemini", aiApiKey = "stale-key",
            providerProfilesJson = ProviderProfiles.toJson(mapOf("gemini" to ProviderProfile("gemini"))))
        assertEquals("", ProviderProfiles.keyFor("gemini", s))
    }

    @Test fun `model discovery filters chat out of embedding operation`() {
        val provider = catalogue.first { it.id == "gemini" }
        val models = ModelDiscovery.parse("""{"models":[
            {"name":"models/gemini-chat","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]}
        ]}""", provider)
        assertEquals(listOf("gemini-embedding-2"),
            ModelDiscovery.selectForCapability(models, emptyList(), AiCapability.EMBED, false).map { it.id })
        assertEquals(listOf("gemini-chat"),
            ModelDiscovery.selectForCapability(models, emptyList(), AiCapability.CHAT, false).map { it.id })
    }

    @Test fun `unknown model ids are not assumed to support embeddings`() {
        val unknown = listOf(ModelInfo("arbitrary-new-chat"))
        assertTrue(ModelDiscovery.selectForCapability(unknown, emptyList(), AiCapability.EMBED, false).isEmpty())
        assertEquals(unknown, ModelDiscovery.selectForCapability(unknown, emptyList(), AiCapability.EMBED, true))
    }

    @Test fun `Gemini Embedding 2 body uses task preparation not unsupported taskType`() {
        val capability = catalogue.first { it.id == "gemini" }.capability(AiCapability.EMBED)!!
        val input = CallEngine.prepareInput(capability, CallInput(prompt = "hello",
            params = mapOf("taskPrefix" to "task: search result | query: ", "dimensions" to "768")))
        assertEquals("task: search result | query: hello", input.prompt)
        assertFalse(capability.call!!.bodyTemplate.contains("taskType"))
        assertFalse(capability.call!!.bodyTemplate.contains("task_type"))
        assertEquals(AuthStyle.HEADER, capability.call!!.auth)
        val roundTrip = CapabilitySpec.fromJson(capability.toJson())
        assertEquals(capability, roundTrip)
    }

    @Test fun `numeric and boolean looking sample texts remain strings while dimensions are numeric`() {
        val capability = catalogue.first { it.id == "gemini" }.capability(AiCapability.EMBED)!!
        listOf("123", "true", "false").forEach { prompt ->
            val input = CallEngine.prepareInput(capability, CallInput(prompt = prompt))
            val body = Templates.fillJson(capability.call!!.bodyTemplate, input.values("key"), CallInput.TEXT_INPUTS)
            assertTrue(body.getJSONObject("content").getJSONArray("parts").getJSONObject(0).get("text") is String)
            assertEquals(prompt, body.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
            assertTrue(body.get("outputDimensionality") is Number)
        }
    }

    @Test fun `polling never forwards credentials to another origin`() {
        CallEngine.requireSameOrigin("https://api.example.org/v1/jobs?key=secret", "https://api.example.org/v1/jobs/1")
        listOf("https://attacker.example/jobs", "http://api.example.org/jobs", "https://api.example.org:8443/jobs").forEach {
            assertTrue(runCatching { CallEngine.requireSameOrigin("https://api.example.org/v1/jobs", it) }.isFailure)
        }
    }

    @Test fun `same model through different providers survives fact discovery`() = kotlinx.coroutines.runBlocking {
        val first = ModelFact("same-model", routeProvider = "first", capability = AiCapability.EMBED)
        val second = first.copy(routeProvider = "second")
        val result = Discovery.merge(listOf(
            Discovery.fixed("one", "One", listOf(first)), Discovery.fixed("two", "Two", listOf(second))
        ), identity = { "${it.routeProvider}:${it.capability}:${it.id}" })
        assertEquals(2, result.items.size)
    }

    @Test fun `embedding verification rejects incorrect dimensions and nonnumeric vectors`() {
        assertEquals(3, CapabilitySetup.validateEmbedding("[0.1,0.2,0.3]", 3))
        assertTrue(runCatching { CapabilitySetup.validateEmbedding("[0.1,0.2,0.3]", 768) }.isFailure)
        assertTrue(runCatching { CapabilitySetup.validateEmbedding("[]") }.isFailure)
        assertTrue(runCatching { CapabilitySetup.validateEmbedding("[0.1,\"unexpected\"]") }.isFailure)
    }

    @Test fun `router fact retains routing provider instead of model author`() {
        val fact = ModelFact("google/new-embedding", provider = "google", routeProvider = "openrouter",
            capability = AiCapability.EMBED, source = "test-list")
        val route = CapabilitySetup.routes(catalogue, AiCapability.EMBED, "new embedding", listOf(fact)).single()
        assertEquals("openrouter", route.provider.id)
    }

    @Test fun `provider capability profiles and setup instructions round trip`() {
        val provider = catalogue.first { it.id == "gemini" }
        assertEquals(provider, ProviderSpec.fromJson(provider.toJson()))
        val profile = ProviderProfile("gemini", capabilities = mapOf(AiCapability.EMBED to CapabilityProfile(
            "gemini-embedding-2", mapOf("dimensions" to "1536"), true, 123L)))
        assertEquals(profile, ProviderProfile.fromJson(profile.toJson()))
    }

    @Test fun `remote profile override is excluded from device-only recommendations`() {
        val original = catalogue.first { it.id == "ollama" }
        val settings = Settings(providerProfilesJson = ProviderProfiles.toJson(mapOf("ollama" to
            ProviderProfile("ollama", baseUrl = "https://remote.example.org/v1"))))
        val resolved = ProviderProfiles.resolved(original, settings)
        assertEquals("https://remote.example.org/v1", resolved.baseUrl)
        assertTrue(SetupAdvisor.recommend(listOf(resolved), SetupWants(mustStayOnDevice = true)).isEmpty())
    }

    @Test fun `legacy credentials seed first profile edits but cleared profiles stay cleared`() {
        val legacy = Settings(aiProvider = "gemini", aiApiKey = "legacy-secret", aiBaseUrl = "https://old.example")
        assertEquals("legacy-secret", ProviderProfiles.editableProfile("gemini", legacy).apiKey)
        val cleared = legacy.copy(providerProfilesJson = ProviderProfiles.toJson(mapOf("gemini" to ProviderProfile("gemini"))))
        assertEquals("", ProviderProfiles.editableProfile("gemini", cleared).apiKey)
    }

    @Test fun `chat profile temperature and token controls reach the actual config`() {
        val profile = ProviderProfile("gemini", capabilities = mapOf(AiCapability.CHAT to CapabilityProfile(
            params = mapOf("temperature" to "0.7", "maxTokens" to "512"))))
        val settings = Settings(aiProvider = "gemini", providerProfilesJson = ProviderProfiles.toJson(mapOf("gemini" to profile)))
        val config = com.example.core.ai.AiConfig.from(settings)
        assertEquals(0.7f, config.temperature, 0.001f)
        assertEquals(512, config.maxTokens)
        assertEquals(24, com.example.core.ai.AiConfig.from(settings, maxTokens = 24).maxTokens)
    }

    @Test @Config(sdk = [28])
    fun `offline recognition is unavailable before Android 12 and does not become system recognition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(com.example.core.asr.AndroidAsr.supportsOnDevice(context))
        val offline = catalogue.first { it.id == "android_speech_offline" }
        val system = catalogue.first { it.id == "android_speech" }
        assertEquals(Privacy.UNKNOWN, system.privacy)
        assertEquals(AiWire.ANDROID_SYSTEM, system.capability(AiCapability.TRANSCRIBE)!!.wire)
        val saved = CapabilitySetup.select(Settings(), SetupRoute(offline, AiCapability.TRANSCRIBE, "", "test"))
        assertEquals(com.example.core.config.AsrEngines.PROVIDER, saved.asrEngine)
        assertEquals("android_speech_offline", saved.asrProvider)
    }

    @Test fun `unknown privacy is excluded and local rule never relaxes to cloud`() {
        val unknown = ProviderSpec("unknown", "Unknown", baseUrl = "https://example.org", privacy = Privacy.UNKNOWN)
        assertTrue(SetupAdvisor.recommend(listOf(unknown), SetupWants(avoidTraining = true)).isEmpty())
        assertNull(SetupAdvisor.fallback(listOf(unknown), SetupWants(mustStayOnDevice = true)))
        val remoteSelfHosted = unknown.copy(privacy = Privacy.ON_DEVICE, local = true, baseUrl = "http://192.168.1.5:8000")
        assertTrue(SetupAdvisor.recommend(listOf(remoteSelfHosted), SetupWants(mustStayOnDevice = true)).isEmpty())
    }
}
