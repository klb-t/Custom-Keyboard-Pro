package com.example.core.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * Dictation through any OpenAI-compatible `/audio/transcriptions` endpoint.
 *
 * That covers the hosted Whisper API, Groq, and a Whisper server running on the
 * user's own machine — so someone who wants better accuracy than the system
 * recogniser, or a language it does not support, can have it without the app picking a
 * vendor for them.
 *
 * Audio is captured to AAC in the app's cache and deleted as soon as the response
 * comes back. This endpoint returns a single transcript rather than an N-best list,
 * so alternatives come from [com.example.core.asr.VoiceController] asking the
 * configured model to propose other readings — only when the user has turned that on.
 */
class RemoteAsr(
    private val context: Context,
    private val scope: CoroutineScope,
    private val endpoint: () -> String,
    private val apiKey: () -> String,
    private val model: () -> String
) : AsrEngine {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var listener: ((AsrState) -> Unit)? = null
    private var language: String = ""

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isAvailable(): Boolean = hasPermission() && endpoint().isNotBlank()

    override fun start(language: String, maxAlternatives: Int, onState: (AsrState) -> Unit) {
        listener = onState
        this.language = language

        if (!hasPermission()) {
            onState(AsrState.Error("Microphone permission has not been granted.", needsPermission = true))
            return
        }
        if (endpoint().isBlank()) {
            onState(AsrState.Error("No transcription endpoint is configured."))
            return
        }

        try {
            val file = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
            outputFile = file
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            onState(AsrState.Listening())
        } catch (e: Exception) {
            cleanup()
            onState(AsrState.Error(e.message ?: "Could not start recording."))
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    override fun stop() {
        val file = outputFile
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // A recording stopped before any audio arrived throws; treat it as empty.
            cleanup()
            listener?.invoke(AsrState.Error("The recording was too short."))
            return
        } finally {
            try {
                recorder?.release()
            } catch (e: Exception) {
                // Nothing useful to do.
            }
            recorder = null
        }

        if (file == null || !file.exists() || file.length() < 1024) {
            file?.delete()
            listener?.invoke(AsrState.Error("The recording was too short."))
            return
        }

        listener?.invoke(AsrState.Processing)
        scope.launch {
            val result = transcribe(file)
            file.delete()
            result.onSuccess { text ->
                listener?.invoke(
                    if (text.isBlank()) AsrState.Error("Nothing was transcribed.")
                    else AsrState.Results(listOf(AsrAlternative(text)))
                )
            }.onFailure { error ->
                listener?.invoke(AsrState.Error(error.message ?: "Transcription failed."))
            }
        }
    }

    override fun cancel() {
        cleanup()
        listener?.invoke(AsrState.Idle)
    }

    override fun release() = cleanup()

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            // Nothing useful to do.
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    private suspend fun transcribe(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = endpoint().trimEnd('/').let {
                if (it.endsWith("/audio/transcriptions")) it else "$it/audio/transcriptions"
            }
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/m4a".toMediaType()))
                .addFormDataPart("model", model().ifBlank { "whisper-1" })
                .addFormDataPart("response_format", "json")
            if (language.isNotBlank()) bodyBuilder.addFormDataPart("language", language.take(2))

            val request = Request.Builder().url(url).post(bodyBuilder.build()).apply {
                if (apiKey().isNotBlank()) addHeader("Authorization", "Bearer ${apiKey()}")
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
