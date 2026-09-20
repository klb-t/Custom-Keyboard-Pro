package com.example.core.convert

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.asr.Transcription
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.AiCapability
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfiles
import com.example.core.discovery.ProviderSpec
import com.example.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a clipboard entry turns out to be, and therefore what could be done with it. */
enum class Convertible {
    /** A picture: a photographed document, a screenshot, a scan. */
    PICTURE,

    /** A recording: a voice note, a call, an interview. */
    RECORDING,

    /** Nothing to convert — text, or something nobody here can read. */
    NONE;

    val capability: String?
        get() = when (this) {
            PICTURE -> AiCapability.OCR
            RECORDING -> AiCapability.TRANSCRIBE
            NONE -> null
        }
}

/**
 * Pasting a picture into a text field and getting its text.
 *
 * The keyboard already knew how to read a picture and how to transcribe a recording:
 * both are capabilities in the provider catalogue and both have working call paths.
 * What was missing was the one place a person naturally asks for either — the moment
 * they paste. Somebody photographs a document, copies it, taps paste into a message,
 * and at that point "convert this" is not a feature they have to go and find. It is
 * the only thing they could have meant.
 *
 * Off by default, and this is the line worth being exact about. The rule in this app
 * is not "nothing useful by default", it is *nothing leaves the device unasked*.
 * Reading a photographed document means sending that photograph to a provider, and a
 * photographed document is a payslip, a prescription, a contract. A switch is one tap;
 * a keyboard that uploaded your documents because you pasted one is not something
 * anybody can take back.
 *
 * There is no on-device path to offer instead, which is the honest reason this cannot
 * simply be on. Android has no text-recognition API of its own, and its speech
 * recogniser listens to a microphone rather than reading a file. Both would mean
 * shipping a model — worth doing, and not this change.
 */
object PasteConversion {

    /** What is on the clipboard, judged by what the provider of it says it is. */
    fun classify(context: Context, clip: ClipData?): Convertible {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return Convertible.NONE
        // Text wins outright: an entry that carries text is text, whatever else is in it.
        if (!item.text.isNullOrBlank()) return Convertible.NONE
        val uri = item.uri ?: return Convertible.NONE
        val mime = mimeOf(context, clip, uri) ?: return Convertible.NONE
        return when {
            mime.startsWith("image/") -> Convertible.PICTURE
            mime.startsWith("audio/") || mime.startsWith("video/") -> Convertible.RECORDING
            else -> Convertible.NONE
        }
    }

    private fun mimeOf(context: Context, clip: ClipData, uri: Uri): String? =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: clip.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)

    /**
     * Which provider would do this, or null.
     *
     * An explicit choice wins; otherwise the first provider that both serves the
     * capability and has a key. Falling back that way is what makes the feature appear
     * the moment somebody configures a provider for something else, rather than being
     * a second setup nobody knew was waiting for them.
     */
    fun providerFor(what: Convertible, settings: Settings = SettingsStore.current): ProviderSpec? {
        val capability = what.capability ?: return null
        val chosen = when (what) {
            Convertible.PICTURE -> settings.ocrProvider
            Convertible.RECORDING -> settings.asrProvider
            Convertible.NONE -> ""
        }
        ProviderCatalog.byId(chosen, settings)
            ?.takeIf { it.can(capability) }
            ?.let { return it }
        return ProviderCatalog.serving(capability, settings).firstOrNull { spec ->
            !spec.needsKey || ProviderProfiles.keyFor(spec.id, settings).isNotBlank()
        }
    }

    /** True when pasting this would produce text, with everything set up to do it. */
    fun canConvert(
        context: Context,
        clip: ClipData?,
        settings: Settings = SettingsStore.current
    ): Boolean {
        if (!settings.pasteConvert) return false
        val what = classify(context, clip)
        return what != Convertible.NONE && providerFor(what, settings) != null
    }

    /**
     * Reads the clipboard entry and returns the text in it.
     *
     * Failure is a [Result] rather than an empty string on purpose: the caller has to
     * tell "this picture has no words in it" from "the request did not go through",
     * because the second means falling back to an ordinary paste and the first does not.
     */
    suspend fun convert(
        context: Context,
        clip: ClipData?,
        settings: Settings = SettingsStore.current,
        language: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val what = classify(context, clip)
            if (what == Convertible.NONE) error("There is nothing on the clipboard to read.")
            val uri = clip!!.getItemAt(0).uri ?: error("That clipboard entry has no file in it.")
            val provider = providerFor(what, settings)
                ?: error("Nothing is set up that can do that yet.")
            val key = ProviderProfiles.keyFor(provider.id, settings)
            if (provider.needsKey && key.isBlank()) error("${provider.label} needs a key first.")

            val limitMb = settings.clipboardMaxFileMb.coerceAtLeast(1)
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val read = stream.readBytes()
                if (read.size > limitMb * 1024L * 1024L) {
                    error("That file is over the $limitMb MB limit.")
                }
                read
            } ?: error("That file could not be read.")

            val mime = mimeOf(context, clip, uri)
            when (what) {
                Convertible.PICTURE ->
                    readPicture(provider, key, bytes, mime ?: "image/jpeg", settings)
                Convertible.RECORDING ->
                    readRecording(context, provider, key, bytes, mime ?: "audio/mpeg", settings, language)
                Convertible.NONE -> error("Nothing to read.")
            }
        }.also { result ->
            result.onFailure { AppLogger.e("Paste", "could not convert the clipboard entry: ${it.message}") }
        }
    }

    /**
     * Two shapes, and which one is used is a fact about the provider rather than a
     * branch anybody has to maintain. A described call — Mistral's OCR endpoint,
     * OCR.space — goes through the engine. A coded wire means the provider reads
     * pictures with the same model it writes with, so it is asked in words.
     */
    private suspend fun readPicture(
        provider: ProviderSpec,
        key: String,
        bytes: ByteArray,
        mime: String,
        settings: Settings
    ): String {
        val capability = provider.capability(AiCapability.OCR)
            ?: error("${provider.label} does not read pictures.")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val model = settings.ocrModel.ifBlank { capability.defaultModel }

        if (capability.call != null) {
            return CallEngine.run(
                provider = provider,
                capability = AiCapability.OCR,
                input = CallInput(model = model, imageBase64 = base64, imageMime = mime),
                apiKey = key
            ).getOrThrow().text
                // Null is "the endpoint answered with no text at that path", which for
                // a picture with no words in it is the right answer and not a failure.
                .orEmpty()
        }

        return AiClient.complete(
            config = AiConfig.from(settings, maxTokens = OCR_TOKENS).copy(
                provider = provider.id,
                baseUrl = ProviderProfiles.baseUrlFor(provider.id, settings),
                apiKey = key,
                model = model.ifBlank { provider.defaultModel },
                wire = capability.wire.ifBlank { provider.wire }
            ),
            systemPrompt = OCR_PROMPT,
            userPrompt = "",
            imageBase64 = base64
        ).getOrThrow()
    }

    private suspend fun readRecording(
        context: Context,
        provider: ProviderSpec,
        key: String,
        bytes: ByteArray,
        mime: String,
        settings: Settings,
        language: String
    ): String {
        val suffix = mime.substringAfter('/').substringBefore(';').ifBlank { "audio" }
        val temporary = File.createTempFile("paste", ".$suffix", context.cacheDir)
        return try {
            temporary.writeBytes(bytes)
            Transcription.via(
                provider = provider,
                model = settings.asrModel,
                apiKey = key,
                file = temporary,
                language = language.ifBlank { settings.asrLanguage }
            ).getOrThrow()
        } finally {
            // The recording was somebody's conversation. It does not stay behind in a
            // cache directory once the text is out of it.
            temporary.delete()
        }
    }

    /**
     * Asking for the words and nothing else.
     *
     * A vision model told merely to "read this" answers with a description, a preamble
     * and an offer to help, none of which belongs in the middle of the message
     * somebody was writing.
     */
    private const val OCR_PROMPT =
        "Transcribe every word visible in this image, in reading order. Preserve line " +
            "breaks and the original language. Reply with the transcription alone: no " +
            "preamble, no description, no commentary, no code fence. If there is no " +
            "text in the image, reply with nothing at all."

    /** A photographed page of dense text runs long; cutting it in half costs more than the tokens. */
    private const val OCR_TOKENS = 4096
}
