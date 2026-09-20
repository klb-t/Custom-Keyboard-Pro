package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfile
import com.example.core.discovery.ProviderProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A key belongs to a provider, not to the app.
 *
 * The bug being fixed is worth stating because it is invisible from the inside:
 * with one key for everything, switching provider sent the *previous* provider's
 * credential to the new one. That comes back 401, which is indistinguishable from a
 * bad key, so the honest conclusion from the user's chair is that the app does not
 * work. Anybody comparing two providers hit it on the second one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderProfileTest {

    @Before
    fun loadTheCatalogue() {
        // Falling back to a catalogue base URL only means something once the bundled
        // catalogue has been read.
        ProviderCatalog.init(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun withProfiles(vararg p: ProviderProfile): String =
        ProviderProfiles.toJson(p.associateBy { it.id })

    @Test
    fun `each provider answers with its own key`() {
        val s = Settings(
            aiProvider = "openrouter",
            providerProfilesJson = withProfiles(
                ProviderProfile("openrouter", apiKey = "sk-or-aaa"),
                ProviderProfile("groq", apiKey = "gsk_bbb"),
                ProviderProfile("anthropic", apiKey = "sk-ant-ccc")
            )
        )
        assertEquals("sk-or-aaa", ProviderProfiles.keyFor("openrouter", s))
        assertEquals("gsk_bbb", ProviderProfiles.keyFor("groq", s))
        assertEquals("sk-ant-ccc", ProviderProfiles.keyFor("anthropic", s))
    }

    @Test
    fun `a provider with no key of its own gets nothing, not somebody else's`() {
        // The whole bug in one assertion. Returning the selected provider's key here
        // is what sent an OpenRouter credential to Groq.
        val s = Settings(
            aiProvider = "openrouter",
            aiApiKey = "sk-or-loose",
            providerProfilesJson = withProfiles(ProviderProfile("openrouter", apiKey = "sk-or-aaa"))
        )
        assertEquals("", ProviderProfiles.keyFor("groq", s))
        assertEquals("", ProviderProfiles.keyFor("anthropic", s))
    }

    @Test
    fun `a key set before profiles existed still works, for its own provider only`() {
        // Nothing may break for somebody who already had this working.
        val s = Settings(aiProvider = "groq", aiApiKey = "gsk_old")
        assertEquals("gsk_old", ProviderProfiles.keyFor("groq", s))
        assertEquals("", ProviderProfiles.keyFor("openrouter", s))
    }

    @Test
    fun `a profile of its own wins over the loose key`() {
        // Otherwise the old value would shadow the new one and editing the key would
        // appear to do nothing at all.
        val s = Settings(
            aiProvider = "groq",
            aiApiKey = "gsk_old",
            providerProfilesJson = withProfiles(ProviderProfile("groq", apiKey = "gsk_new"))
        )
        assertEquals("gsk_new", ProviderProfiles.keyFor("groq", s))
    }

    @Test
    fun `base URL and model follow the provider too`() {
        val s = Settings(
            aiProvider = "lmstudio",
            providerProfilesJson = withProfiles(
                ProviderProfile("lmstudio", baseUrl = "http://192.168.1.10:1234/v1", model = "local-7b"),
                ProviderProfile("groq", apiKey = "gsk_bbb", model = "llama-3.3-70b-versatile")
            )
        )
        assertEquals("http://192.168.1.10:1234/v1", ProviderProfiles.baseUrlFor("lmstudio", s))
        assertEquals("local-7b", ProviderProfiles.modelFor("lmstudio", s))
        assertEquals("llama-3.3-70b-versatile", ProviderProfiles.modelFor("groq", s))
    }

    @Test
    fun `an unset base URL falls back to what the catalogue says`() {
        val s = Settings(aiProvider = "groq")
        assertTrue(
            "the catalogue should supply a base URL for a provider that is in it",
            ProviderProfiles.baseUrlFor("groq", s).startsWith("https://")
        )
    }

    @Test
    fun `profiles survive a round trip, keys and parameters and all`() {
        val profiles = mapOf(
            "openrouter" to ProviderProfile(
                "openrouter", apiKey = "sk-or-aaa", model = "anthropic/claude-haiku-4.5",
                params = mapOf("temperature" to "0.2", "top_p" to "0.9"),
                verifiedAt = 1_700_000_000_000L
            ),
            "groq" to ProviderProfile("groq", apiKey = "gsk_bbb", problem = "no credit")
        )
        val read = ProviderProfiles.all(Settings(providerProfilesJson = ProviderProfiles.toJson(profiles)))
        assertEquals(profiles, read)
    }

    @Test
    fun `nothing stored reads as nothing rather than throwing`() {
        assertEquals(emptyMap<String, ProviderProfile>(), ProviderProfiles.all(Settings()))
        assertEquals(
            emptyMap<String, ProviderProfile>(),
            ProviderProfiles.all(Settings(providerProfilesJson = "not json"))
        )
    }

    @Test
    fun `the set-up list is the ones with a key`() {
        val s = Settings(
            providerProfilesJson = withProfiles(
                ProviderProfile("openrouter", apiKey = "sk-or-aaa"),
                ProviderProfile("groq", apiKey = "gsk_bbb"),
                // Configured with a URL but no key: a local server, which needs none.
                ProviderProfile("ollama", baseUrl = "http://localhost:11434/v1")
            )
        )
        assertEquals(listOf("groq", "openrouter"), ProviderProfiles.configured(s).map { it.id })
        assertFalse(ProviderProfiles.of("ollama", s).hasKey)
    }
}
