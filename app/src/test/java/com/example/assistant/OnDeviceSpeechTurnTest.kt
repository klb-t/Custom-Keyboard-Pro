package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Deterministic lifecycle adapter checks. This does not substitute for a real recognition Binder. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class OnDeviceSpeechTurnTest {
    private class FakeEngine : OnDeviceSpeechTurn.Engine {
        lateinit var callback: RecognitionListener
        var starts = 0; var stops = 0; var cancels = 0; var destroyed = 0
        var onCancel: (() -> Unit)? = null
        var request: Intent? = null
        override fun listener(value: RecognitionListener) { callback = value }
        override fun start(intent: Intent) { starts++; request = intent }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; onCancel?.invoke() }
        override fun destroy() { destroyed++ }
    }
    private class Output : RecognitionListener {
        var errors = mutableListOf<Int>(); var results = mutableListOf<String>(); var buffers = 0; var events = 0
        var onFinal: (() -> Unit)? = null
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) { buffers++ }
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) { errors += error }
        override fun onResults(results: Bundle?) {
            onFinal?.invoke()
            this.results += results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) { events++ }
    }
    private fun words(value: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value))
    }
    @Test fun unavailableEngineNeverCreatesOrStartsACloudFallback() {
        val output = Output(); var created = 0
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { false }, create = { created++; FakeEngine() })
        turn.start(); turn.start()
        check(created == 0 && output.errors == listOf(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }
    @Test fun fileOrSegmentedRequestNeverFallsBackToOpeningTheMic() {
        listOf(RecognizerIntent.EXTRA_AUDIO_SOURCE, RecognizerIntent.EXTRA_SEGMENTED_SESSION).forEach { extra ->
            val output = Output(); var created = 0
            val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
                available = { true }, create = { created++; FakeEngine() })
            turn.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(extra, "supplied source"))
            check(created == 0 && output.errors == listOf(SpeechRecognizer.ERROR_CLIENT))
        }
    }
    @Test fun cancellationDestroysExactlyOnceAndRejectsLateOrSynchronousCallbacks() {
        val output = Output(); val engine = FakeEngine()
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { engine })
        turn.start()
        engine.onCancel = { engine.callback.onError(SpeechRecognizer.ERROR_CLIENT) }
        turn.close(); turn.close()
        engine.callback.onResults(words("Do something"))
        check(engine.cancels == 1 && engine.destroyed == 1 && output.results.isEmpty() && output.errors.isEmpty())
    }
    @Test fun oneTurnCannotRestartOrCreateConcurrentRecording() {
        val output = Output(); val engine = FakeEngine(); var created = 0
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { created++; engine })
        turn.start(); turn.start()
        engine.callback.onResults(words("Transcript")); turn.start()
        check(created == 1 && engine.starts == 1 && output.results == listOf("Transcript"))
    }
    @Test fun finalTranscriptIsDeliveredOnlyAfterDelegateRelease() {
        val output = Output(); val engine = FakeEngine()
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { engine })
        output.onFinal = { check(engine.destroyed == 1 && engine.cancels == 1) }
        turn.start(); engine.callback.onResults(words("User editable goal"))
        check(output.results == listOf("User editable goal"))
    }
    @Test fun hiddenHostDropsTheTranscriptAndClosesTheDelegate() {
        val output = Output(); val engine = FakeEngine(); var visible = true; var discarded = 0
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { visible }, output,
            available = { true }, discarded = { discarded++ }, create = { engine })
        turn.start(); visible = false
        engine.callback.onResults(words("Late hidden goal")); engine.callback.onError(SpeechRecognizer.ERROR_CLIENT)
        check(engine.destroyed == 1 && discarded == 1 && output.results.isEmpty() && output.errors.isEmpty())
    }
    @Test fun timeoutReleasesTheMicAndReportsOneTerminalError() {
        val output = Output(); val engine = FakeEngine()
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { engine })
        turn.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OnDeviceSpeechTurn.MAX_TURN_MS))
        engine.callback.onResults(words("Too late"))
        check(engine.destroyed == 1 && output.errors == listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && output.results.isEmpty())
    }
    @Test fun stopRequestsFinalRecognitionAndRetainsTheBoundedDeadline() {
        val output = Output(); val engine = FakeEngine()
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { engine })
        turn.start(); turn.stop()
        check(engine.stops == 1 && engine.destroyed == 0)
        engine.callback.onResults(words("Stopped transcript"))
        check(output.results == listOf("Stopped transcript") && engine.destroyed == 1)
    }
    @Test fun rawAudioAndProviderEventBundlesDoNotCrossTheTranscriptBoundary() {
        val output = Output(); val engine = FakeEngine()
        val turn = OnDeviceSpeechTurn(ApplicationProvider.getApplicationContext<Context>(), { true }, output,
            available = { true }, create = { engine })
        turn.start(); engine.callback.onBufferReceived(byteArrayOf(1)); engine.callback.onEvent(7, words("untyped"))
        turn.close()
        check(output.buffers == 0 && output.events == 0)
    }
}
