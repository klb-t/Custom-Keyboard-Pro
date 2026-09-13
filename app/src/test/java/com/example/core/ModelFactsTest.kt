package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.discovery.FactSource
import com.example.core.discovery.FactsFetcher
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
 * Reading facts about models out of somewhere that does not ask who is calling.
 *
 * The parsing is what is tested, not the network: a source that changes its shape is
 * the failure this has to survive, and that shows up as a reply that parses to
 * nothing rather than as a request that fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelFactsTest {

    private val bundled: List<FactSource> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FactSource.parseList(
            context.assets.open("factsources.json").bufferedReader().use { it.readText() }
        )
    }

    private val openRouterShaped = """
        {"data":[
          {"id":"anthropic/claude-haiku-4.5","name":"Claude Haiku 4.5",
           "context_length":200000,
           "pricing":{"prompt":"0.000001","completion":"0.000005"},
           "architecture":{"input_modalities":["text","image"]}},
          {"id":"some/free-model","name":"Free Model","context_length":32768,
           "pricing":{"prompt":"0","completion":"0"},
           "architecture":{"input_modalities":["text"]}},
          {"name":"no id at all"}
        ]}
    """.trimIndent()

    @Test
    fun `the bundled sources are readable and at least one is on`() {
        assertTrue("no fact sources shipped", bundled.isNotEmpty())
        assertTrue("every fact source is switched off", bundled.any { it.enabled })
        bundled.forEach {
            assertTrue("${it.id} is not https", it.call.path.startsWith("https://"))
            assertFalse("${it.id} has no label", it.label.isBlank())
        }
        // The point of these is that they work with no account.
        assertTrue("nothing usable without a key", bundled.any { it.enabled && !it.needsKey })
    }

    @Test
    fun `a model list is read into facts`() {
        val source = bundled.first { it.id == "openrouter_models" }
        val facts = FactsFetcher.parse(openRouterShaped, source)

        // The entry with no id is dropped rather than becoming a blank row.
        assertEquals(2, facts.size)

        val haiku = facts.first()
        assertEquals("anthropic/claude-haiku-4.5", haiku.id)
        assertEquals("Claude Haiku 4.5", haiku.label)
        // Read off the id, because the list does not carry it separately.
        assertEquals("anthropic", haiku.provider)
        assertEquals(200_000L, haiku.contextTokens)
        assertEquals(listOf("text", "image"), haiku.modalities)
        // Sources quote per-token; nobody reads per-token.
        assertEquals(1.0, haiku.promptPrice!!, 0.0001)
        assertEquals(5.0, haiku.completionPrice!!, 0.0001)
        assertEquals(false, haiku.isFree)
    }

    @Test
    fun `free is distinguished from unknown`() {
        val source = bundled.first { it.id == "openrouter_models" }
        val facts = FactsFetcher.parse(openRouterShaped, source)
        // "0" has to survive as zero rather than as "no price known" — the two mean
        // opposite things to somebody choosing without a card.
        assertEquals(true, facts[1].isFree)

        val priceless = FactsFetcher.parse("""{"data":[{"id":"x","name":"X"}]}""", source)
        assertNull(priceless.single().promptPrice)
        assertNull("a missing price must not read as free", priceless.single().isFree)
        assertNull(priceless.single().contextTokens)
    }

    @Test
    fun `a field the source does not publish reads as absent, not as everything`() {
        // An empty path means "the whole document" to JsonPath, correctly and on
        // purpose. Passing one through for a field a source never mapped therefore
        // does not read nothing — it reads the entire row, and the field comes back
        // holding a model's complete JSON description.
        val source = bundled.first { it.id == "openrouter_models" }
        assertFalse("'provider' is not mapped by this source", source.fields.containsKey("provider"))

        val fact = FactsFetcher.parse(openRouterShaped, source).first()
        assertEquals("anthropic", fact.provider)
        assertFalse("the whole row leaked into a field", fact.provider.contains("{"))
    }

    @Test
    fun `a source that changed shape reads as nothing, not as nonsense`() {
        val source = bundled.first { it.id == "openrouter_models" }
        assertTrue(FactsFetcher.parse("""{"models":[{"id":"x"}]}""", source).isEmpty())
        assertTrue(FactsFetcher.parse("""{"data":"not an array"}""", source).isEmpty())
        assertTrue(FactsFetcher.parse("""{}""", source).isEmpty())
    }

    @Test
    fun `a description is readable without knowing the numbers`() {
        val source = bundled.first { it.id == "openrouter_models" }
        val facts = FactsFetcher.parse(openRouterShaped, source)
        assertTrue(facts[0].describe().contains("200k context"))
        assertTrue(facts[0].describe().contains("text+image"))
        assertTrue(facts[1].describe().contains("free"))
    }

    @Test
    fun `a parked source stays described rather than deleted`() {
        // Turning one off without removing it is how a source that breaks gets
        // repaired later instead of being forgotten.
        val parked = bundled.filter { !it.enabled }
        parked.forEach { assertFalse(it.note.isBlank()) }
    }

    @Test
    fun `nonsense in the source list is skipped`() {
        assertEquals(
            1,
            FactSource.parseList(
                """[{"label":"no id"},{"id":"ok","call":{"path":"https://x"}},{"id":"no call"}]"""
            ).size
        )
        assertTrue(FactSource.parseList("not json").isEmpty())
    }
}
