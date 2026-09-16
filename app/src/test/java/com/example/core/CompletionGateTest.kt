package com.example.core

import com.example.core.config.Settings
import com.example.core.discovery.AiCapability
import com.example.core.discovery.AiWire
import com.example.core.discovery.CapabilitySpec
import com.example.core.discovery.ProviderSpec
import com.example.core.predict.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one place in this keyboard where what somebody types leaves the device.
 *
 * Everything else here is a preference: get it wrong and a suggestion is unhelpful.
 * Get this wrong and a password is in somebody's request log. So the decision is a
 * pure function with no network in it, which is the only reason it can be checked at
 * all on a machine with no phone attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompletionGateTest {

    private val completer = ProviderSpec(
        id = "test",
        label = "Test",
        baseUrl = "https://example.invalid",
        capabilities = mapOf(
            AiCapability.COMPLETE to CapabilitySpec(wire = AiWire.OPENAI, defaultModel = "m")
        )
    )

    /** Everything configured and permitted: the case every refusal is measured against. */
    private val ready = Settings(completionEnabled = true, completionProvider = "test")

    private val enough = "a long enough piece of text"

    @Test
    fun `a working configuration is not refused`() {
        assertNull(Refusal.of(enough, false, ready) { completer })
    }

    @Test
    fun `a sensitive field is refused whatever else is true`() {
        // The point of the test: not that it refuses, but that it refuses *first*.
        // Everything else here is in order, so nothing but the field itself can be
        // the reason — and no amount of later configuration can talk it round.
        assertEquals(
            Refusal.SENSITIVE,
            Refusal.of(enough, true, ready) { completer }
        )
    }

    @Test
    fun `the feature is off until it is turned on`() {
        assertEquals(
            Refusal.DISABLED,
            Refusal.of(enough, false, Settings(completionProvider = "test")) { completer }
        )
        // And the default settings are that state: nothing leaves a fresh install.
        assertEquals(
            Refusal.DISABLED,
            Refusal.of(enough, false, Settings()) { completer }
        )
    }

    @Test
    fun `too little text is not worth an inference`() {
        assertEquals(
            Refusal.TOO_SHORT,
            Refusal.of("hi", false, ready) { completer }
        )
        assertEquals(
            Refusal.TOO_SHORT,
            Refusal.of("", false, ready) { completer }
        )
    }

    @Test
    fun `a provider that cannot complete is not asked to`() {
        // A chat provider is not a completion provider. Sending a prompt to an
        // endpoint that answers questions produces "Sure! Here is the rest of your
        // sentence:" in the middle of somebody's message.
        val chatOnly = completer.copy(
            capabilities = mapOf(AiCapability.CHAT to CapabilitySpec(wire = AiWire.OPENAI))
        )
        assertEquals(Refusal.NO_PROVIDER, Refusal.of(enough, false, ready) { chatOnly })
        assertEquals(Refusal.NO_PROVIDER, Refusal.of(enough, false, ready) { null })
    }

    @Test
    fun `a sensitive field costs nothing to refuse`() {
        // The catalogue is parsed from JSON on every lookup, and this runs on every
        // keystroke. Resolving a provider in order to decide something already decided
        // would be work done for no reason, on the battery, forever.
        var resolved = 0
        Refusal.of(enough, true, ready) { resolved++; completer }
        assertEquals(0, resolved)
    }
}
