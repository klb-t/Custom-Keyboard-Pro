package com.example.core.asr

import com.example.core.discovery.AiCapability
import com.example.core.discovery.AiWire
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turning a recorded file into words, wherever the user chose to send it.
 *
 * Two paths, and the split is the same one drawn everywhere else in this app. The
 * OpenAI multipart shape is written out in code because half the providers in the
 * catalogue speak it — hand-writing it once is cheaper than describing it twenty
 * times, and it is the shape a local Whisper server speaks too. Everything else goes
 * through [CallEngine] and is therefore a catalogue entry rather than a branch here.
 *
 * The only vendor name in this file is in a default model id, and even that is a
 * fallback for a provider that forgot to name one.
 */
object Transcription {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun via(
        provider: ProviderSpec,
        model: String,
        apiKey: String,
        file: File,
        language: String
    ): Result<String> {
        val spec = provider.capability(AiCapability.TRANSCRIBE)
            ?: return Result.failure(IllegalStateException("${provider.label} does not take dictation."))
        val chosen = model.ifBlank { spec.defaultModel }

        return when {
            spec.wire == AiWire.OPENAI_AUDIO ->
                openAiAudio(provider.baseUrl, apiKey, chosen, file, language)

            spec.call != null -> CallEngine.run(
                provider = provider,
                capability = AiCapability.TRANSCRIBE,
                input = CallInput(
                    model = chosen,
                    language = language,
                    file = file,
                    // The recorder writes an MPEG-4 container; providers that read the
                    // body rather than the filename need to be told so.
                    fileMime = "audio/mp4"
                ),
                apiKey = apiKey
            ).mapCatching { result ->
                result.text?.trim().orEmpty().ifBlank {
                    error("${provider.label} returned nothing to transcribe.")
                }
            }

            else -> Result.failure(
                IllegalStateException("${provider.label} does not say how to take dictation.")
            )
        }
    }

    /**
     * The multipart `/audio/transcriptions` shape, unchanged from the version that
     * only ever pointed at one URL.
     */
    suspend fun openAiAudio(
        baseUrl: String,
        apiKey: String,
        model: String,
        file: File,
        language: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseUrl.trimEnd('/').let {
                if (it.endsWith("/audio/transcriptions")) it else "$it/audio/transcriptions"
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/m4a".toMediaType()))
                .addFormDataPart("model", model.ifBlank { "whisper-1" })
                .addFormDataPart("response_format", "json")
                .apply {
                    // A two-letter hint helps; an empty one is read as a language by
                    // more providers than not.
                    if (language.isNotBlank()) addFormDataPart("language", language.take(2))
                }
                .build()

            val request = Request.Builder().url(url).post(body).apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }.build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception("${response.code} ${response.message}: ${raw.take(300)}")
                }
                JSONObject(raw).optString("text").trim()
            }
        }
    }
}
