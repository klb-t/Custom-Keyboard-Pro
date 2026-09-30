package com.example.assistant

import android.app.Activity
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/** No default-recognizer loop or silent cloud fallback after changing assistant role. */
class ForegroundSpeech(activity: Activity, visible: () -> Boolean,
                       result: (String) -> Unit, notice: (String) -> Unit) : AutoCloseable {
    private val turn = OnDeviceSpeechTurn(activity, visible, object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = notice("Listening on device. Stop or leave this panel to cancel.")
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = notice("Finishing transcript…")
        override fun onError(error: Int) = notice(when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "No on-device recognizer or downloaded language is available here. Type the goal or use the keyboard's configured dictation. No audio was sent to a fallback provider."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access was denied. No action was run."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech session timed out. No action was run."
            else -> "On-device speech failed (code $error). No action was run."
        })
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) result(text)
            else notice("No transcript was recognized. Nothing was planned or executed.")
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    fun start() = turn.start()
    override fun close() = turn.close()
}
