package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** The activity and RecognitionService share one bounded, local-only recognition mechanism. */
internal class OnDeviceSpeechTurn(
    private val context: Context,
    private val visible: () -> Boolean,
    private val output: RecognitionListener,
    private val available: () -> Boolean = {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    },
    private val discarded: () -> Unit = {},
    private val create: () -> Engine = {
        if (Build.VERSION.SDK_INT >= 31) AndroidEngine(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
        else throw UnsupportedOperationException("On-device recognition requires API 31")
    },
    private val main: Handler = Handler(Looper.getMainLooper())
) : AutoCloseable {
    internal interface Engine {
        fun listener(value: RecognitionListener)
        fun start(intent: Intent)
        fun stop()
        fun cancel()
        fun destroy()
    }
    private class AndroidEngine(private val recognizer: SpeechRecognizer) : Engine {
        override fun listener(value: RecognitionListener) = recognizer.setRecognitionListener(value)
        override fun start(intent: Intent) = recognizer.startListening(intent)
        override fun stop() = recognizer.stopListening()
        override fun cancel() = recognizer.cancel()
        override fun destroy() = recognizer.destroy()
    }
    private var engine: Engine? = null
    private var started = false
    private var closed = false
    private val timeout = Runnable { fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
    private fun live(): Boolean {
        if (closed) return false
        if (!visible()) { close(); discarded(); return false }
        return true
    }
    fun start(intent: Intent = GoalSpeechInput.request()) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed) return
        started = true
        if (!live()) return
        try {
            // Do not silently replace a supplied file/stream or continuous-session request with a mic.
            if (!GoalSpeechInput.permitsMicrophone(intent)) { fail(SpeechRecognizer.ERROR_CLIENT); return }
            if (!available()) { fail(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE); return }
            val value = create()
            engine = value
            value.listener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (live()) output.onReadyForSpeech(Bundle()) }
                override fun onBeginningOfSpeech() { if (live()) output.onBeginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) { if (live() && rmsdB.isFinite()) output.onRmsChanged(rmsdB) }
                // Raw audio buffers and untyped provider events are not part of the transcript bridge.
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (live()) output.onEndOfSpeech() }
                override fun onError(error: Int) = fail(error)
                override fun onResults(results: Bundle?) {
                    if (!live()) return
                    val bounded = GoalSpeechInput.results(results)
                    close() // Release/cancel the delegate before delivering its final transcript.
                    output.onResults(bounded)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (live()) output.onPartialResults(GoalSpeechInput.results(partialResults))
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            main.postDelayed(timeout, MAX_TURN_MS)
            value.start(GoalSpeechInput.request(intent))
        } catch (_: SecurityException) { fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        catch (_: Exception) { fail(SpeechRecognizer.ERROR_CLIENT) }
    }
    fun stop() {
        if (live()) try { engine?.stop() } catch (_: Exception) { fail(SpeechRecognizer.ERROR_CLIENT) }
    }
    private fun fail(error: Int) {
        if (!live()) return
        close()
        output.onError(error)
    }
    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        val value = engine; engine = null
        value?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
    }
    companion object { const val MAX_TURN_MS = 60_000L }
}

/** External intents supply bounded recognition preferences, never routing, audio, or authority. */
internal object GoalSpeechInput {
    const val MAX_WORDS = 8000
    private const val MAX_RESULTS = 3
    @Suppress("DEPRECATION")
    fun permitsMicrophone(source: Intent): Boolean = runCatching {
        source.action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH &&
            !source.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE) &&
            !source.hasExtra(RecognizerIntent.EXTRA_AUDIO_INJECT_SOURCE) &&
            !source.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION)
    }.getOrDefault(false)
    fun request(source: Intent? = null): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        val model = runCatching { source?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL) }.getOrNull()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, when (model) {
            RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH -> model
            else -> RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        })
        val language = runCatching { source?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) }.getOrNull()
        if (language != null && language.length <= 80 && language.matches(Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,
            runCatching { source?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) ?: false }.getOrDefault(false))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,
            runCatching { source?.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS) ?: MAX_RESULTS }.getOrDefault(MAX_RESULTS).coerceIn(1, MAX_RESULTS))
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, false)
    }
    fun results(source: Bundle?): Bundle = Bundle().apply {
        val words = runCatching {
            source?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.take(MAX_RESULTS)?.map { it.take(MAX_WORDS) }.orEmpty()
        }.getOrDefault(emptyList())
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(words))
        val scores = runCatching { source?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) }.getOrNull()
        if (scores != null && scores.size >= words.size)
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, scores.take(words.size).map {
                if (it.isFinite() && it in 0f..1f) it else -1f
            }.toFloatArray())
    }
}
