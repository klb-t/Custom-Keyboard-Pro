package com.example.assistant

import android.app.KeyguardManager
import android.content.ContextParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Real local-only relay for the assistant-role contract; it never invokes the goal executor. */
class GoalRecognitionService : RecognitionService() {
    private var client: Callback? = null
    private var turn: OnDeviceSpeechTurn? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val target = client ?: return
            clear(target); error(target, SpeechRecognizer.ERROR_CLIENT)
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        // One live turn also bounds recursion if a misconfigured platform targets the relay itself.
        if (client != null) { error(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        if (Build.VERSION.SDK_INT < 31) { error(listener, SpeechRecognizer.ERROR_CLIENT); return }
        if (locked()) { error(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        val attributionContext = try {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        } catch (_: Exception) { error(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        client = listener
        val value = OnDeviceSpeechTurn(attributionContext, { client === listener && !locked() }, object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = deliver(listener) { readyForSpeech(Bundle()) }
            override fun onBeginningOfSpeech() = deliver(listener) { beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = deliver(listener) { rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = deliver(listener) { endOfSpeech() }
            override fun onError(error: Int) { clear(listener); error(listener, error) }
            override fun onResults(results: Bundle?) {
                clear(listener)
                runCatching { listener.results(results ?: Bundle()) }
            }
            override fun onPartialResults(partialResults: Bundle?) = deliver(listener) { partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }, discarded = { clear(listener); error(listener, SpeechRecognizer.ERROR_CLIENT) })
        turn = value
        value.start(recognizerIntent)
    }
    private fun locked() = (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != false
    private fun deliver(listener: Callback, action: Callback.() -> Unit) {
        if (client !== listener) return
        runCatching { listener.action() }.onFailure { clear(listener) }
    }
    private fun error(listener: Callback, code: Int) { runCatching { listener.error(code) } }
    private fun clear(listener: Callback? = client) {
        if (client !== listener) return
        client = null
        turn?.close(); turn = null
    }
    override fun onStopListening(listener: Callback) { if (client === listener) turn?.stop() }
    override fun onCancel(listener: Callback) = clear(listener)
    override fun onDestroy() {
        clear(); runCatching { unregisterReceiver(screenOff) }; super.onDestroy()
    }
}
