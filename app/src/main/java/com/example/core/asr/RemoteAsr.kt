package com.example.core.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.example.core.discovery.AiCapability
import com.example.core.discovery.ProviderSpec
import java.io.File

/**
 * Dictation that records to a file and hands it to somebody else to read.
 *
 * The recording half is the same whoever transcribes it — open the microphone, write
 * AAC into the cache, delete it the moment the answer comes back — so it lives here
 * once and *what to do with the file* is a parameter. That is what lets the same
 * class serve a hand-typed Whisper URL, a provider out of the catalogue, and a
 * provider nobody has written code for, without the recording logic being copied
 * three times and diverging on the fourth.
 *
 * These endpoints return a single transcript rather than an N-best list, so
 * alternatives come from [VoiceController] asking the configured model to propose
 * other readings — only when the user has turned that on.
 */
class RemoteAsr(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Null when dictation can run; otherwise the reason it cannot, for the user. */
    private val unavailable: () -> String?,
    /** Given the recorded file and the requested language, produce a transcript. */
    private val transcribe: suspend (file: File, language: String) -> Result<String>
) : AsrEngine {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var listener: ((AsrState) -> Unit)? = null
    private var language: String = ""


    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isAvailable(): Boolean = hasPermission() && unavailable() == null

    override fun start(language: String, maxAlternatives: Int, onState: (AsrState) -> Unit) {
        listener = onState
        this.language = language

        if (!hasPermission()) {
            onState(AsrState.Error("Microphone permission has not been granted.", needsPermission = true))
            return
        }
        unavailable()?.let { reason ->
            onState(AsrState.Error(reason))
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
            val result = transcribe(file, language)
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

    companion object {

        /**
         * The original behaviour: a URL the user typed, spoken to in the OpenAI
         * multipart shape. Kept exactly as it was, because it is what anyone
         * running their own Whisper server already has configured.
         */
        fun forEndpoint(
            context: Context,
            scope: CoroutineScope,
            endpoint: () -> String,
            apiKey: () -> String,
            model: () -> String
        ): RemoteAsr = RemoteAsr(
            context = context,
            scope = scope,
            unavailable = {
                if (endpoint().isBlank()) "No transcription endpoint is configured." else null
            },
            transcribe = { file, language ->
                Transcription.openAiAudio(
                    baseUrl = endpoint(),
                    apiKey = apiKey(),
                    model = model().ifBlank { "whisper-1" },
                    file = file,
                    language = language
                )
            }
        )

        /**
         * Anywhere in the catalogue that says it can take dictation.
         *
         * The provider decides how: a wire the app implements, or a call it merely
         * describes. Neither is visible from here, which is the point — adding a
         * transcription provider is a catalogue entry, not a branch in this file.
         */
        fun forProvider(
            context: Context,
            scope: CoroutineScope,
            provider: () -> ProviderSpec?,
            apiKey: () -> String,
            model: () -> String
        ): RemoteAsr = RemoteAsr(
            context = context,
            scope = scope,
            unavailable = {
                val p = provider()
                when {
                    p == null -> "That dictation provider is no longer in the catalogue."
                    !p.can(AiCapability.TRANSCRIBE) -> "${p.label} does not take dictation."
                    p.needsKey && apiKey().isBlank() -> "${p.label} needs an API key."
                    else -> null
                }
            },
            transcribe = { file, language ->
                val p = provider()
                if (p == null) Result.failure(IllegalStateException("No dictation provider."))
                else Transcription.via(p, model(), apiKey(), file, language)
            }
        )
    }
}
