package com.example.core.asr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * The system recogniser.
 *
 * Chosen as the default because it costs nothing, needs no account, and on most
 * devices runs on-device — so dictation works in aeroplane mode and the audio does not
 * leave the phone. It is also the only engine here that returns a real N-best list,
 * which is what makes "pick a different reading" possible rather than decorative.
 */
class AndroidAsr(private val context: Context) : AsrEngine {

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((AsrState) -> Unit)? = null
    private var lastPartial: String = ""

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isAvailable(): Boolean =
        hasPermission() && SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(language: String, maxAlternatives: Int, onState: (AsrState) -> Unit) {
        listener = onState
        lastPartial = ""

        if (!hasPermission()) {
            onState(AsrState.Error("Microphone permission has not been granted.", needsPermission = true))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState(AsrState.Error("No speech recogniser is installed on this device."))
            return
        }

        // SpeechRecognizer is main-thread-only, including construction.
        main.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives.coerceIn(1, 10))
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (language.isNotBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                    }
                }
                recognizer?.startListening(intent)
                onState(AsrState.Listening())
            } catch (e: Exception) {
                onState(AsrState.Error(e.message ?: "Could not start the recogniser."))
            }
        }
    }

    override fun stop() {
        main.post {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                // Stopping a recogniser that already stopped is not worth reporting.
            }
        }
    }

    override fun cancel() {
        main.post {
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                // Same.
            }
            listener?.invoke(AsrState.Idle)
        }
    }

    override fun release() {
        main.post {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Same.
            }
            recognizer = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.invoke(AsrState.Listening())
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            listener?.invoke(AsrState.Listening(lastPartial, rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listener?.invoke(AsrState.Processing)
        }

        override fun onError(error: Int) {
            listener?.invoke(
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        AsrState.Error("Microphone permission has not been granted.", needsPermission = true)
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        AsrState.Error("Nothing was recognised.")
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        AsrState.Error("No speech was detected.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        AsrState.Error("The recogniser could not reach the network.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        AsrState.Error("The recogniser is busy; try again.")
                    else -> AsrState.Error("Recognition failed (code $error).")
                }
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) {
                lastPartial = text
                listener?.invoke(AsrState.Listening(text))
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val alternatives = texts.mapIndexed { index, text ->
                AsrAlternative(text, scores?.getOrNull(index) ?: -1f)
            }.filter { it.text.isNotBlank() }
            listener?.invoke(
                if (alternatives.isEmpty()) AsrState.Error("Nothing was recognised.")
                else AsrState.Results(alternatives)
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
