package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.discovery.AiCapability
import com.example.core.discovery.AiWire
import com.example.core.discovery.AuthStyle
import com.example.core.discovery.BodyKind
import com.example.core.discovery.CallSpec
import com.example.core.discovery.CapabilitySpec
import com.example.core.discovery.Privacy
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderSpec
import com.example.core.layout.LayoutJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalogue is data, and data that is wrong is wrong silently: a provider with a
 * mistyped auth style does not fail to compile, it fails on someone's phone with a
 * 401 and no explanation. So the bundled file is read here and held to the rules the
 * code assumes when it uses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCatalogTest {

    /** Read the way the app reads it, so packaging is covered too. */
    private val bundled: List<ProviderSpec> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open("providers.json").bufferedReader().use { it.readText() }
        ProviderCatalog.parseList(text)
            .also { assertTrue("the bundled catalogue did not parse", it.isNotEmpty()) }
    }

    @Test
    fun `the catalogue holds what the user asked it to hold`() {
        val direct = bundled.filter { !it.router && !it.local && it.id != "openai_compatible" }
        val routers = bundled.filter { it.router }
        assertTrue("expected at least 10 direct providers, found ${direct.size}", direct.size >= 10)
        assertTrue("expected 3 to 8 routers, found ${routers.size}", routers.size in 3..8)
        // The one that was named explicitly.
        assertTrue("OpenRouter must be in the catalogue", routers.any { it.id == "openrouter" })
    }

    @Test
    fun `every capability the app knows about has somewhere to send it`() {
        listOf(
            AiCapability.CHAT, AiCapability.TRANSCRIBE, AiCapability.OCR,
            AiCapability.IMAGE, AiCapability.VIDEO, AiCapability.SPEECH, AiCapability.EMBED
        ).forEach { capability ->
            val serving = bundled.filter { it.can(capability) }
            assertTrue(
                "nothing in the catalogue can do '$capability'",
                serving.isNotEmpty()
            )
        }
    }

    @Test
    fun `every dictation provider is one the code can actually reach`() {
        // Three ways dictation can be served, and no fourth: the phone's own
        // recogniser, the multipart shape written out in Transcription, or a call the
        // catalogue describes. A provider served by none of them is a dropdown entry
        // that fails at the moment the user presses the microphone.
        bundled.filter { it.can(AiCapability.TRANSCRIBE) }
            .also { assertTrue("nothing takes dictation", it.isNotEmpty()) }
            .forEach { provider ->
                val spec = provider.capability(AiCapability.TRANSCRIBE)!!
                val reachable = spec.wire == AiWire.OPENAI_AUDIO ||
                    spec.wire == AiWire.ON_DEVICE ||
                    spec.call != null
                assertTrue(
                    "'${provider.id}' offers dictation in a way nothing can carry out " +
                        "(wire='${spec.wire}', call=${spec.call != null})",
                    reachable
                )
                if (spec.wire == AiWire.OPENAI_AUDIO) {
                    assertFalse(
                        "'${provider.id}' speaks the audio shape but has no base URL",
                        provider.baseUrl.isBlank()
                    )
                }
            }
    }

    @Test
    fun `the phone itself is offered as a dictation provider`() {
        // "Where does my voice go" should be one question with one list of answers,
        // rather than a checkbox meaning "not the cloud" beside a dropdown meaning
        // "which cloud".
        val phone = bundled.firstOrNull { it.can(AiCapability.TRANSCRIBE) && it.local }
        assertNotNull("nothing on-device takes dictation", phone)
        assertEquals(
            AiWire.ON_DEVICE,
            bundled.first { it.id == "android_speech" }
                .capability(AiCapability.TRANSCRIBE)?.wire
        )
        assertFalse(bundled.first { it.id == "android_speech" }.needsKey)
    }

    @Test
    fun `anything that streams says where the token is`() {
        // The streaming fields are the whole reason a completion is described rather
        // than coded. A provider that declares stream and then does not say where the
        // token sits produces an empty suggestion strip and no error.
        bundled.forEach { provider ->
            provider.capabilities.forEach { (id, spec) ->
                val call = spec.call ?: return@forEach
                if (!call.stream) return@forEach
                assertFalse(
                    "'${provider.id}' streams '$id' without saying where the token is",
                    call.streamTextPath.isBlank()
                )
                assertFalse(
                    "'${provider.id}' streams '$id' with no end marker",
                    call.streamDone.isBlank()
                )
                assertTrue(
                    "'${provider.id}' streams '$id' but its body never asks for a stream",
                    call.bodyTemplate.contains("\"stream\"")
                )
            }
        }
    }

    @Test
    fun `finishing a sentence is offered by something, and by something free`() {
        val completing = bundled.filter { it.can(AiCapability.COMPLETE) }
        assertTrue("nothing can finish a sentence", completing.isNotEmpty())
        assertTrue(
            "no way to finish a sentence without paying",
            completing.any { it.freeTier }
        )
        assertTrue(
            "no way to finish a sentence without sending it anywhere",
            completing.any { it.privacy == Privacy.ON_DEVICE }
        )
    }

    @Test
    fun `ids are unique`() {
        val ids = bundled.map { it.id }
        val repeated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate provider ids: $repeated", repeated.isEmpty())
    }

    @Test
    fun `every provider is usable as written`() {
        bundled.forEach { p ->
            assertFalse("provider '${p.id}' has a blank label", p.label.isBlank())
            if (!p.local && p.baseUrl.isNotBlank()) {
                assertTrue(
                    "provider '${p.id}' has a base URL that is not https: ${p.baseUrl}",
                    p.baseUrl.startsWith("https://")
                )
            }
            assertTrue(
                "provider '${p.id}' has an unknown wire '${p.wire}'",
                p.wire in AiWire.ALL
            )
            assertFalse(
                "provider '${p.id}' has a trailing slash on its base URL",
                p.baseUrl.endsWith("/")
            )
        }
    }

    @Test
    fun `a provider that only makes pictures is not offered as a chat provider`() {
        // The first version of the fallback read "has a base URL" as "can chat",
        // which offered the user endpoints that answer 404.
        val fal = bundled.firstOrNull { it.id == "fal" }
        assertNotNull(fal)
        assertFalse("fal.ai does not serve chat", fal!!.can(AiCapability.CHAT))
        assertTrue("fal.ai does make pictures", fal.can(AiCapability.IMAGE))
    }

    @Test
    fun `a plain chat provider still reports chat without saying so`() {
        val deepseek = bundled.first { it.id == "deepseek" }
        assertTrue(deepseek.can(AiCapability.CHAT))
        assertEquals("deepseek-chat", deepseek.capability(AiCapability.CHAT)?.defaultModel)
        assertFalse(deepseek.can(AiCapability.IMAGE))
    }

    @Test
    fun `every declared capability says how to reach it`() {
        bundled.forEach { provider ->
            provider.capabilities.forEach { (id, spec) ->
                assertTrue(
                    "provider '${provider.id}' declares '$id' with neither a wire nor a call",
                    spec.wire.isNotBlank() || spec.call != null
                )
                if (spec.wire.isNotBlank()) {
                    assertTrue(
                        "provider '${provider.id}', capability '$id': unknown wire '${spec.wire}'",
                        spec.wire in AiWire.ALL
                    )
                }
                spec.call?.let { call ->
                    assertTrue(
                        "provider '${provider.id}', capability '$id': unknown auth '${call.auth}'",
                        call.auth in listOf(
                            AuthStyle.BEARER, AuthStyle.HEADER, AuthStyle.QUERY,
                            AuthStyle.PREFIXED, AuthStyle.NONE
                        )
                    )
                    assertTrue(
                        "provider '${provider.id}', capability '$id': unknown body kind '${call.bodyKind}'",
                        call.bodyKind in listOf(
                            BodyKind.JSON, BodyKind.MULTIPART, BodyKind.BINARY, BodyKind.NONE
                        )
                    )
                    if (call.auth == AuthStyle.HEADER || call.auth == AuthStyle.QUERY ||
                        call.auth == AuthStyle.PREFIXED
                    ) {
                        assertFalse(
                            "provider '${provider.id}', capability '$id': ${call.auth} auth " +
                                "needs a name to put the key under",
                            call.authName.isBlank()
                        )
                    }
                    if (call.auth == AuthStyle.PREFIXED) {
                        assertFalse(
                            "provider '${provider.id}', capability '$id': prefixed auth " +
                                "needs the word that goes before the key",
                            call.authPrefix.isBlank()
                        )
                    }
                    if (call.bodyKind == BodyKind.JSON && call.method == "POST") {
                        assertFalse(
                            "provider '${provider.id}', capability '$id': a JSON POST with " +
                                "no body template sends nothing",
                            call.bodyTemplate.isBlank()
                        )
                    }
                    if (call.isAsync) {
                        assertFalse(
                            "provider '${provider.id}', capability '$id': polls but never " +
                                "says what finished looks like",
                            call.pollDoneValues.isEmpty()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a placeholder in a template is one the caller can actually supply`() {
        val known = setOf(
            "model", "prompt", "language", "imageBase64", "imageMime", "key", "size",
            "audioMime", "voice", "quality", "seconds",
            // Model parameters, supplied from CallInput.params.
            "maxTokens", "temperature", "topP", "topK", "seed"
        )
        val placeholder = Regex("\\{\\{(\\w+)}}")
        bundled.forEach { provider ->
            provider.capabilities.forEach { (id, spec) ->
                val call = spec.call ?: return@forEach
                val text = call.path + call.bodyTemplate +
                    call.fields.values.joinToString() + call.headers.values.joinToString()
                placeholder.findAll(text).map { it.groupValues[1] }.forEach { name ->
                    assertTrue(
                        "provider '${provider.id}', capability '$id': template asks for " +
                            "{{$name}}, which nothing supplies",
                        name in known
                    )
                }
            }
        }
    }

    @Test
    fun `a provider survives a round trip through its own json`() {
        bundled.forEach { original ->
            val back = ProviderSpec.fromJson(original.toJson())
            assertEquals("provider '${original.id}' changed across a round trip", original, back)
        }
    }

    @Test
    fun `a described call survives a round trip`() {
        val call = CallSpec(
            method = "POST", path = "/listen?model={{model}}",
            auth = AuthStyle.PREFIXED, authName = "Authorization", authPrefix = "Token",
            headers = mapOf("x-thing" to "1"),
            bodyKind = BodyKind.BINARY,
            fields = mapOf("language" to "{{language}}"),
            resultPath = "results.channels[0].alternatives[0].transcript",
            pollUrlPath = "urls.get", pollStatusPath = "status",
            pollDoneValues = listOf("succeeded"), pollFailedValues = listOf("failed"),
            pollIntervalMs = 500L, pollTimeoutMs = 1000L
        )
        assertEquals(call, CallSpec.fromJson(call.toJson()))
        val spec = CapabilitySpec(call = call, defaultModel = "m", models = listOf("m", "n"))
        assertEquals(spec, CapabilitySpec.fromJson(spec.toJson()))
    }

    @Test
    fun `a body written as an object becomes the template`() {
        val call = CallSpec.fromJson(
            org.json.JSONObject("""{"path":"/x","body":{"model":"{{model}}"}}""")
        )
        assertTrue(call.bodyTemplate.contains("{{model}}"))
        assertEquals("POST", call.method)
    }

    @Test
    fun `nonsense in the catalogue is skipped rather than fatal`() {
        val parsed = ProviderCatalog.parseList(
            """[{"label":"no id here"},{"id":"fine","label":"Fine","baseUrl":"https://x/"}]"""
        )
        assertEquals(1, parsed.size)
        assertEquals("fine", parsed.single().id)
        // The trailing slash is trimmed on the way in, so nothing downstream has to.
        assertEquals("https://x", parsed.single().baseUrl)
        assertTrue(ProviderCatalog.parseList("not json").isEmpty())
    }

    @Test
    fun `an array survives being unwrapped, which it did not before`() {
        val raw = """[{"id":"a","label":"A"},{"id":"b","label":"B"}]"""
        // The object-shaped unwrapper narrowed this to `{"id":"a",...},{"id":"b",...}`
        // by cutting at the outermost braces, which parses as nothing. Every reader of
        // an array — this catalogue, custom themes, generated panels — then returned an
        // empty list and said nothing, because they all catch and fall back.
        assertEquals(raw, LayoutJson.stripCodeFenceArray(raw))
        assertEquals(2, ProviderCatalog.parseList(raw).size)
    }

    @Test
    fun `an array still survives a model wrapping it in prose and a fence`() {
        val fenced = "Sure, here they are:\n```json\n" +
            """[{"id":"a","label":"A"}]""" + "\n```\nHope that helps."
        assertEquals(1, ProviderCatalog.parseList(fenced).size)
    }

    @Test
    fun `unwrapping an object is unchanged`() {
        assertEquals("""{"a":1}""", LayoutJson.stripCodeFence("""prose {"a":1} more"""))
        assertEquals(
            """{"a":[1,2]}""",
            LayoutJson.stripCodeFence("```json\n" + """{"a":[1,2]}""" + "\n```")
        )
    }

    @Test
    fun `a capability a provider does not have is absent, not empty`() {
        val p = ProviderSpec(id = "x", label = "X", wire = AiWire.OPENAI, baseUrl = "https://x")
        assertNull(p.capability(AiCapability.IMAGE))
        assertNotNull(p.capability(AiCapability.CHAT))
        assertEquals(listOf(AiCapability.CHAT), p.abilities)
    }
}
