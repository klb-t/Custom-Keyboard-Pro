package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobLong
import com.example.util.AppLogger

/**
 * Listens for spoken phrases while a wire on "voice" is live, and for nothing else.
 *
 * Deliberately narrow: it hands every transcript to the engine, which compares it
 * with the wires' phrases; nothing heard is kept. It prefers the phone's on-device
 * recogniser where there is one, so a spoken password does not travel anywhere.
 *
 * What it cannot promise, said plainly: whether the system lets an app use the
 * microphone with its keyboard closed is the system's decision, and it differs by
 * version and maker. A refusal is reported once and then retried slowly, rather
 * than spinning or pretending to listen.
 */
class VoiceTrigger(context: Context, private val onHeard: (String) -> Unit, private val onProblem: (String) -> Unit) {

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var failures = 0
    private var reported = false

    val listening: Boolean get() = active

    fun start() {
        if (active) return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Voice phrases need the microphone permission — grant it on the app's Dictation screen")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            report("Voice phrases: this phone has no speech recogniser")
            return
        }
        active = true
        failures = 0
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun create(): SpeechRecognizer {
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun listen() {
        if (!active) return
        val r = recognizer ?: create()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        runCatching { r.startListening(intent) }.onFailure {
            AppLogger.e(TAG, "could not start listening", it)
            again(error = true)
        }
    }

    private fun again(error: Boolean) {
        if (!active) return
        val base = SettingsStore.current.knobLong(Knobs.VOICE_RETRY_MS)
        failures = if (error) failures + 1 else 0
        // Errors back off, up to thirty times the pause: a refused microphone must not
        // become a tight loop.
        val wait = base * (1L shl failures.coerceAtMost(5)).coerceAtMost(30)
        main.postDelayed({ listen() }, wait)
    }

    private fun report(text: String) {
        AppLogger.d(TAG, text)
        if (reported) return
        reported = true
        onProblem(text)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.forEach(onHeard)
            again(error = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.forEach(onHeard)
        }

        override fun onError(error: Int) {
            when (error) {
                // Silence and not-understood are the normal end of a listen.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again(error = false)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_AUDIO -> {
                    report("Voice phrases: the system would not let the app use the microphone now (error $error)")
                    again(error = true)
                }
                else -> again(error = true)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "Engine.voice"
    }
}
