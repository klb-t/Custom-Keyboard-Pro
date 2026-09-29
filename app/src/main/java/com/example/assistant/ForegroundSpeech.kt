package com.example.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** No default-recognizer loop or silent cloud fallback after changing assistant role. */
class ForegroundSpeech(private val activity: Activity, private val visible: () -> Boolean,
                       private val result: (String) -> Unit, private val notice: (String) -> Unit) : AutoCloseable {
    private var recognizer: SpeechRecognizer? = null
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (!closed) { close(); notice("Speech session timed out. No action was run.") } }
    fun start() {
        if (closed || !visible()) return
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            notice("No on-device recognizer is available here. Type the goal or use the keyboard's already configured dictation; no audio was sent to a fallback provider.")
            close(); return
        }
        try {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (!closed) notice("Listening on device. Stop or leave this panel to cancel.") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (!closed) notice("Finishing transcript…") }
                override fun onError(error: Int) { if (!closed) { close(); notice("On-device speech failed (code $error). No action was run.") } }
                override fun onResults(results: Bundle?) {
                    if (closed) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.take(8000)
                    val deliver = visible(); close()
                    if (deliver && !text.isNullOrBlank()) result(text)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3))
            main.postDelayed(timeout, 60_000)
        } catch (_: Exception) { close(); notice("Cannot start on-device speech. Check microphone access and the installed recognition service.") }
    }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(timeout)
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }; recognizer = null
    }
}
