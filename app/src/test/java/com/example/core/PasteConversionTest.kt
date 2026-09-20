package com.example.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.convert.Convertible
import com.example.core.convert.PasteConversion
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfile
import com.example.core.discovery.ProviderProfiles
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
 * Deciding whether a paste is a paste or a conversion.
 *
 * Everything worth testing here is the decision, not the request: whether this is a
 * picture, whether anybody is set up to read one, and above all whether the answer is
 * *no* in every case where saying yes would put somebody's photographed document on a
 * provider's server without being asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasteConversionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun loadTheCatalogue() {
        ProviderCatalog.init(context)
    }

    private fun configured(vararg p: ProviderProfile) =
        ProviderProfiles.toJson(p.associateBy { it.id })

    /**
     * A clip carrying a file of a given type, the way another app hands one over.
     *
     * Built with an explicit description rather than through `ClipData.newUri`, which
     * asks the content resolver what the type is and gets nothing back for an authority
     * that does not exist. The description is also the real fallback on a device, for
     * providers that decline to answer `getType`.
     */
    private fun fileClip(mime: String): ClipData {
        val uri = android.net.Uri.parse("content://com.example.test/thing")
        return ClipData(
            android.content.ClipDescription("file", arrayOf(mime)),
            ClipData.Item(uri)
        )
    }

    private fun onWith(vararg p: ProviderProfile) = Settings(
        pasteConvert = true,
        providerProfilesJson = configured(*p)
    )

    // -----------------------------------------------------------------------
    // What is on the clipboard
    // -----------------------------------------------------------------------

    @Test
    fun `text is text, even when a clip carries something else too`() {
        val clip = ClipData.newPlainText("label", "just some words")
        assertEquals(Convertible.NONE, PasteConversion.classify(context, clip))
    }

    @Test
    fun `an empty clipboard converts to nothing`() {
        assertEquals(Convertible.NONE, PasteConversion.classify(context, null))
    }

    @Test
    fun `a picture is a picture and a recording is a recording`() {
        assertEquals(Convertible.PICTURE, PasteConversion.classify(context, fileClip("image/jpeg")))
        assertEquals(Convertible.RECORDING, PasteConversion.classify(context, fileClip("audio/mpeg")))
    }

    @Test
    fun `something nobody here can read is left alone`() {
        assertEquals(Convertible.NONE, PasteConversion.classify(context, fileClip("application/pdf")))
        assertEquals(Convertible.NONE, PasteConversion.classify(context, fileClip("application/zip")))
    }

    @Test
    fun `each kind knows which capability would do it`() {
        assertEquals("ocr", Convertible.PICTURE.capability)
        assertEquals("transcribe", Convertible.RECORDING.capability)
        assertNull(Convertible.NONE.capability)
    }

    // -----------------------------------------------------------------------
    // Who would do it
    // -----------------------------------------------------------------------

    @Test
    fun `a provider with a key is picked without being named`() {
        // The feature appearing the moment somebody sets a provider up for something
        // else is the whole point: nobody should have to find a second setup screen.
        val s = onWith(ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30)))
        assertEquals("anthropic", PasteConversion.providerFor(Convertible.PICTURE, s)?.id)
    }

    @Test
    fun `a provider without a key is not picked, however capable`() {
        // Picking one would produce a 401 at the moment somebody pasted a document,
        // which reads as the feature being broken rather than unconfigured.
        val s = Settings(pasteConvert = true)
        assertNull(PasteConversion.providerFor(Convertible.PICTURE, s))
    }

    @Test
    fun `a named provider wins over the search`() {
        val s = Settings(
            pasteConvert = true,
            ocrProvider = "gemini",
            providerProfilesJson = configured(
                ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30)),
                ProviderProfile("gemini", apiKey = "AIza" + "b".repeat(32))
            )
        )
        assertEquals("gemini", PasteConversion.providerFor(Convertible.PICTURE, s)?.id)
    }

    @Test
    fun `a named provider that cannot do it is not used anyway`() {
        // Naming Groq for pictures does not make Groq read pictures. Falling through to
        // one that can beats failing at the moment of paste.
        val s = Settings(
            pasteConvert = true,
            ocrProvider = "groq",
            providerProfilesJson = configured(
                ProviderProfile("groq", apiKey = "gsk_" + "c".repeat(30)),
                ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30))
            )
        )
        assertEquals("anthropic", PasteConversion.providerFor(Convertible.PICTURE, s)?.id)
    }

    // -----------------------------------------------------------------------
    // When nothing may happen, which is the half that matters
    // -----------------------------------------------------------------------

    @Test
    fun `off means off, however well configured everything else is`() {
        val s = Settings(
            pasteConvert = false,
            providerProfilesJson = configured(ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30)))
        )
        assertFalse(PasteConversion.canConvert(context, fileClip("image/jpeg"), s))
    }

    @Test
    fun `on by default would be a keyboard that uploads your documents`() {
        // Stated as a test because it is a promise rather than a preference, and a
        // promise nobody has written down is one somebody flips later by accident.
        assertFalse(Settings().pasteConvert)
    }

    @Test
    fun `on, but with nothing set up, is still nothing happening`() {
        assertFalse(PasteConversion.canConvert(context, fileClip("image/jpeg"), Settings(pasteConvert = true)))
    }

    @Test
    fun `pasting text is never a conversion`() {
        val s = onWith(ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30)))
        assertFalse(PasteConversion.canConvert(context, ClipData.newPlainText("l", "words"), s))
    }

    @Test
    fun `a picture with a reader set up is the one case that converts`() {
        val s = onWith(ProviderProfile("anthropic", apiKey = "sk-ant-" + "a".repeat(30)))
        assertTrue(PasteConversion.canConvert(context, fileClip("image/jpeg"), s))
    }

    @Test
    fun `converting nothing reports why rather than returning an empty string`() = kotlinx.coroutines.test.runTest {
        // The caller has to tell "no words in this picture" from "the request failed",
        // because only the second one falls back to an ordinary paste.
        val result = PasteConversion.convert(context, null, Settings(pasteConvert = true))
        assertTrue(result.isFailure)
    }

    @Test
    fun `a clipboard manager with nothing in it does not crash the classifier`() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(Convertible.NONE, PasteConversion.classify(context, manager.primaryClip))
    }
}
