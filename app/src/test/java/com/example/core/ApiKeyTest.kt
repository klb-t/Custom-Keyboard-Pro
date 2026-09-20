package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.discovery.ApiKeys
import com.example.core.discovery.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recognising a key, so the last step of getting one can be done for the user.
 *
 * The copy is the final thing that happens on the provider's website, so by the time
 * somebody is back in the settings screen the key is already on the clipboard. The
 * risk of offering it is small and the risk of offering it to the *wrong provider* is
 * not: a key pasted into the wrong box comes back 401, which is exactly the failure
 * this whole area exists to stop producing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeyTest {

    @Before
    fun loadTheCatalogue() {
        ProviderCatalog.init(ApplicationProvider.getApplicationContext<Context>())
    }

    private val providers by lazy { ProviderCatalog.all(Settings()) }
    private fun spec(id: String) = ProviderCatalog.byId(id, Settings())

    private val openRouter = "sk-or-v1-" + "a".repeat(40)
    private val anthropic = "sk-ant-api03-" + "b".repeat(40)
    private val openAi = "sk-proj-" + "c1".repeat(25)
    private val groq = "gsk_" + "d2".repeat(20)

    // -----------------------------------------------------------------------
    // Shape, which is the half that keeps working
    // -----------------------------------------------------------------------

    @Test
    fun `a long opaque token looks like a key`() {
        listOf(openRouter, anthropic, openAi, groq, "AIza" + "e3".repeat(18)).forEach {
            assertTrue("'$it' was not recognised", ApiKeys.looksLikeKey(it))
        }
    }

    @Test
    fun `ordinary clipboard contents do not`() {
        // Everything here is something a person plausibly copies while setting this up,
        // and offering to store any of it as a credential would be nonsense.
        listOf(
            "", "   ",
            "https://openrouter.ai/keys",
            "Here is my key, do not share it",
            "keyboard",
            "1234567890123456789012345",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "/data/user/0/com.example/files/key.txt",
            "{\"apiKey\":\"sk-abc\"}",
            "sk-short"
        ).forEach {
            assertFalse("'$it' was taken for a key", ApiKeys.looksLikeKey(it))
        }
    }

    @Test
    fun `whitespace around it is not a reason to refuse`() {
        // Clipboards pick up a trailing newline all the time.
        assertTrue(ApiKeys.looksLikeKey("  $groq\n"))
    }

    // -----------------------------------------------------------------------
    // Whose key is it
    // -----------------------------------------------------------------------

    @Test
    fun `a key is attributed to the provider that issues that shape`() {
        assertEquals("openrouter", ApiKeys.whoseKey(openRouter, providers)?.id)
        assertEquals("anthropic", ApiKeys.whoseKey(anthropic, providers)?.id)
        assertEquals("groq", ApiKeys.whoseKey(groq, providers)?.id)
    }

    @Test
    fun `the more specific prefix wins`() {
        // "sk-" belongs to several. An OpenRouter key must not be read as an OpenAI one
        // just because OpenAI's pattern is checked first — that is the mis-paste this
        // exists to catch, arriving through the thing meant to catch it.
        assertEquals("openrouter", ApiKeys.whoseKey(openRouter, providers)?.id)
        assertEquals("anthropic", ApiKeys.whoseKey(anthropic, providers)?.id)
        assertEquals("openai", ApiKeys.whoseKey(openAi, providers)?.id)
    }

    @Test
    fun `an unrecognised key belongs to nobody in particular`() {
        // Most providers declare no pattern, and a key from one of them must still be
        // usable — it simply cannot be attributed, so nothing is claimed about it.
        val opaque = "f4".repeat(24)
        assertNull(ApiKeys.whoseKey(opaque, providers))
        assertTrue(ApiKeys.looksLikeKey(opaque))
    }

    @Test
    fun `a provider with a pattern is held to it`() {
        assertTrue(ApiKeys.matches(spec("groq"), groq))
        assertFalse(ApiKeys.matches(spec("groq"), openRouter))
        assertFalse(ApiKeys.matches(spec("anthropic"), openAi))
    }

    @Test
    fun `a provider without one accepts anything key-shaped`() {
        // Falling back to refusing would make every provider that has not been
        // catalogued in this detail unusable through the clipboard — which is most of
        // them, and which would make the feature a list to maintain forever.
        val local = spec("ollama")
        assertTrue(ApiKeys.matches(local, "f5".repeat(24)))
        assertFalse(ApiKeys.matches(local, "not a key at all"))
    }

    @Test
    fun `every pattern in the catalogue is a regular expression that compiles`() {
        // A broken pattern is silent: it never matches, the clipboard offer never
        // appears, and nothing says why.
        providers.filter { it.keyPattern.isNotBlank() }.forEach { spec ->
            val compiled = runCatching { Regex(spec.keyPattern) }.getOrNull()
            assertTrue("'${spec.id}' has a pattern that does not compile", compiled != null)
        }
    }

    @Test
    fun `a pattern survives a round trip through the catalogue format`() {
        val original = spec("openrouter")!!
        assertEquals(original.keyPattern, com.example.core.discovery.ProviderSpec.fromJson(original.toJson())?.keyPattern)
        assertTrue(original.keyPattern.isNotBlank())
    }

    // -----------------------------------------------------------------------
    // Showing one without showing it
    // -----------------------------------------------------------------------

    @Test
    fun `a masked key says which one it is without being one`() {
        val masked = ApiKeys.masked(openRouter)
        assertTrue(masked.startsWith("sk-or-"))
        assertFalse("the mask contained the whole key", masked.contains(openRouter))
        assertTrue(masked.length < openRouter.length)
        // Something too short to mask usefully is hidden entirely rather than nearly.
        assertEquals("•••••", ApiKeys.masked("short"))
    }
}
