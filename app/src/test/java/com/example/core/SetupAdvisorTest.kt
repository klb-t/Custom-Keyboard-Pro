package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.discovery.AiCapability
import com.example.core.discovery.Privacy
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderSpec
import com.example.core.setup.SetupAdvisor
import com.example.core.setup.SetupWants
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
 * The advice given to somebody who has no accounts anywhere.
 *
 * Worth testing hard, because it is the one piece of reasoning in the app that cannot
 * be checked by asking a model — it runs precisely when no model is configured — and
 * because a wrong recommendation here is the difference between a keyboard that gets
 * set up and one that gets uninstalled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupAdvisorTest {

    private val catalogue: List<ProviderSpec> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProviderCatalog.parseList(
            context.assets.open("providers.json").bufferedReader().use { it.readText() }
        ).also { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun `somebody who will not pay is still given something that works`() {
        val advice = SetupAdvisor.recommend(catalogue, SetupWants(noCard = true, freeOnly = true))
        assertTrue("nothing offered to a user who will not pay", advice.isNotEmpty())
        advice.forEach {
            assertTrue("${it.provider.id} wants a card", !it.provider.needsCard)
            assertTrue("${it.provider.id} has no free tier", it.provider.freeTier)
        }
    }

    @Test
    fun `nothing may leave the device means nothing may leave the device`() {
        val advice = SetupAdvisor.recommend(
            catalogue,
            SetupWants(capabilities = setOf(AiCapability.TRANSCRIBE), mustStayOnDevice = true)
        )
        assertTrue("no private way to dictate was offered", advice.isNotEmpty())
        advice.forEach {
            // A hard rule, not a preference: one hosted provider slipping into this
            // list is a promise broken, whatever its score.
            assertEquals(
                "${it.provider.id} would send audio away",
                Privacy.ON_DEVICE, it.provider.privacy
            )
        }
        assertTrue(advice.any { it.provider.id == "android_speech" })
    }

    @Test
    fun `the phone itself wins when the user wants privacy and no account`() {
        val advice = SetupAdvisor.recommend(
            catalogue,
            SetupWants(
                capabilities = setOf(AiCapability.TRANSCRIBE),
                noCard = true, freeOnly = true, avoidTraining = true
            )
        )
        assertEquals("android_speech", advice.first().provider.id)
        assertTrue(advice.first().reasons.any { it.contains("nothing is sent", ignoreCase = true) })
    }

    @Test
    fun `a router is preferred by somebody who wants one account`() {
        val one = SetupAdvisor.recommend(catalogue, SetupWants(preferOneAccount = true), limit = 3)
        assertTrue("no router offered to a user who asked for one account", one.any { it.provider.router })
    }

    @Test
    fun `asking for several things favours a provider that does all of them`() {
        val wants = SetupWants(
            capabilities = setOf(AiCapability.CHAT, AiCapability.TRANSCRIBE, AiCapability.OCR)
        )
        val advice = SetupAdvisor.recommend(catalogue, wants)
        assertTrue(advice.isNotEmpty())
        // The filter is "all of them", so every answer must genuinely do all three —
        // two accounts covering half each is where people give up.
        advice.forEach { recommendation ->
            wants.capabilities.forEach { capability ->
                assertTrue(
                    "${recommendation.provider.id} cannot $capability",
                    recommendation.provider.can(capability)
                )
            }
        }
    }

    @Test
    fun `every recommendation says why, in words`() {
        SetupAdvisor.recommend(catalogue, SetupWants()).forEach {
            assertTrue(
                "${it.provider.id} was recommended without a reason",
                it.reasons.isNotEmpty()
            )
            it.reasons.forEach { reason -> assertFalse(reason.isBlank()) }
        }
    }

    @Test
    fun `what is not established is said, not guessed`() {
        val unknown = catalogue.first { it.privacy == Privacy.UNKNOWN && !it.needsCard || it.privacy == Privacy.UNKNOWN }
        val scored = SetupAdvisor.recommend(listOf(unknown), SetupWants(capabilities = emptySet()))
        assertTrue(scored.isNotEmpty())
        assertTrue(
            "an unknown privacy policy was passed over in silence",
            scored.first().caveats.any { it.contains("not established") }
        )
    }

    @Test
    fun `what the user typed is matched against what providers are good at`() {
        val advice = SetupAdvisor.recommend(
            catalogue,
            SetupWants(notes = "I dictate a lot and I need it fast"),
            limit = 5
        )
        assertTrue(
            "nothing matched a plain description of dictation",
            advice.any { r -> r.reasons.any { it.startsWith("Good at what you described") } }
        )
    }

    @Test
    fun `a strength is matched both ways round`() {
        val spec = ProviderSpec(
            id = "x", label = "X", baseUrl = "https://x",
            strengths = listOf("dictation", "one-key-many-models")
        )
        assertEquals(listOf("dictation"), SetupAdvisor.matchedStrengths(spec, "I dictate constantly"))
        assertTrue(SetupAdvisor.matchedStrengths(spec, "one key many models").isNotEmpty())
        assertTrue(SetupAdvisor.matchedStrengths(spec, "nothing relevant here").isEmpty())
        assertTrue(SetupAdvisor.matchedStrengths(spec, "").isEmpty())
    }

    @Test
    fun `impossible wants produce an explained compromise, never an empty list`() {
        // Everything at once, on device, free, private: nothing can do this.
        val impossible = SetupWants(
            capabilities = setOf(AiCapability.VIDEO, AiCapability.OCR),
            mustStayOnDevice = true
        )
        assertTrue(SetupAdvisor.recommend(catalogue, impossible).isEmpty())

        val (compromise, explanation) = SetupAdvisor.fallback(catalogue, impossible)
            ?: error("the advisor gave up instead of proposing a compromise")
        assertTrue(compromise.isNotEmpty())
        assertFalse("the compromise was not explained", explanation.isBlank())
    }

    @Test
    fun `a provider the app has never heard of is ranked on its own entry`() {
        // The point of keeping the knowledge in the catalogue: this entry did not
        // exist when the ranking was written, and needs no change to it.
        val newcomer = ProviderSpec(
            id = "brand_new", label = "Brand New", baseUrl = "https://api.brand.new",
            freeTier = true, needsCard = false, privacy = Privacy.NO_TRAINING,
            strengths = listOf("dictation"),
            capabilities = mapOf(
                AiCapability.TRANSCRIBE to com.example.core.discovery.CapabilitySpec(
                    wire = com.example.core.discovery.AiWire.OPENAI_AUDIO, defaultModel = "m"
                )
            )
        )
        val advice = SetupAdvisor.recommend(
            listOf(newcomer),
            SetupWants(capabilities = setOf(AiCapability.TRANSCRIBE), notes = "dictation"),
            limit = 1
        )
        assertEquals("brand_new", advice.single().provider.id)
        assertTrue(advice.single().reasons.any { it.contains("Free to start") })
        assertTrue(advice.single().reasons.any { it.contains("dictation") })
    }

    @Test
    fun `the generic escape hatch is never recommended`() {
        // "Anything else that speaks the OpenAI shape" is an answer to a question the
        // user has already answered, so it would be a strange thing to suggest.
        assertTrue(
            SetupAdvisor.recommend(catalogue, SetupWants(), limit = 40)
                .none { it.provider.id == "openai_compatible" }
        )
    }
}
