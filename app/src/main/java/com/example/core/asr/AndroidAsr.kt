package com.example.core.asr

import com.example.core.config.knob
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

    /**
     * Bumped whenever a run is superseded, so a callback from a run we have moved on
     * from is dropped instead of surfacing.
     *
     * This is the whole of the "it says it failed, but nothing failed" report. Ending
     * a run — cancelling it, or starting another — makes the old recogniser deliver a
     * final [SpeechRecognizer.ERROR_CLIENT] or a disconnect, arriving *after* the new
     * run has begun. The error was true of a session nobody is in any more, and with
     * nothing to tell the two apart it was shown against the new one.
     */
    private var generation = 0

    /** The generation whose callbacks are the live ones. */
    private var runGeneration = -1

    /** One silent retry per run, for the disconnect that means the service was torn down. */
    private var retried = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isAvailable(): Boolean =
        hasPermission() && SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(language: String, maxAlternatives: Int, onState: (AsrState) -> Unit) {
        listener = onState
        lastPartial = ""
        retried = false
        val mine = ++generation

        if (!hasPermission()) {
            onState(AsrState.Error("Microphone permission has not been granted.", needsPermission = true))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState(AsrState.Error("No speech recogniser is installed on this device."))
            return
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
        lastIntent = intent

        // SpeechRecognizer is main-thread-only, including construction.
        main.post {
            if (mine != generation) return@post
            try {
                // Kept rather than destroyed and rebuilt. destroy() unbinds the
                // recognition service asynchronously; building a new one and starting
                // it in the same breath races that unbind, and the new instance is the
                // one that gets told the server disconnected.
                val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context)
                    .also {
                        it.setRecognitionListener(recognitionListener)
                        recognizer = it
                    }

                // Only cancel something that is actually running, and never start in
                // the same breath as a cancel. cancel() is asynchronous too: it posts
                // to the service and returns, so calling startListening immediately
                // walks into a session that is still closing. That is a busy or a
                // client error on the *first* run — which is how fixing the second run
                // broke the first one.
                if (listening) {
                    active.cancel()
                    listening = false
                    main.post { begin(mine, active, intent, onState) }
                } else {
                    begin(mine, active, intent, onState)
                }
            } catch (e: Exception) {
                onState(AsrState.Error(e.message ?: "Could not start the recogniser."))
            }
        }
    }

    /** True between startListening and whatever ends that run. */
    private var listening = false

    private fun begin(
        mine: Int,
        active: SpeechRecognizer,
        intent: Intent,
        onState: (AsrState) -> Unit
    ) {
        if (mine != generation) return
        runGeneration = mine
        listening = true
        active.startListening(intent)
        onState(AsrState.Listening())
    }

    /** The last request, so a disconnect can be retried without the caller noticing. */
    private var lastIntent: Intent? = null

    /**
     * Rebuilds the recogniser and tries the same request once more.
     *
     * For the one error that means "the thing you were talking to went away": the
     * service was torn down between runs and the instance bound to it is no longer
     * usable. Telling the user to try again would be telling them to do precisely
     * what this does, so it does it.
     */
    private fun retrySilently() {
        val mine = ++generation
        val intent = lastIntent ?: return
        main.post {
            if (mine != generation) return@post
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Destroying something already gone is not worth reporting.
            }
            recognizer = null
            listening = false
            // A beat, so the unbind completes before the rebind. Without it the new
            // instance walks into the same disconnect that caused the retry.
            main.postDelayed({
                if (mine != generation) return@postDelayed
                try {
                    val fresh = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(recognitionListener)
                    }
                    recognizer = fresh
                    runGeneration = mine
                    listening = true
                    fresh.startListening(intent)
                    listener?.invoke(AsrState.Listening())
                } catch (e: Exception) {
                    listener?.invoke(AsrState.Error(e.message ?: "Could not start the recogniser."))
                }
            }, com.example.core.config.SettingsStore.current
                .knob(com.example.core.config.Knobs.ASR_RETRY_MS).toLong())
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
        // Superseded before the call, so the ERROR_CLIENT that cancelling provokes
        // belongs to a generation nobody is listening to. stop() deliberately does
        // not do this: stopping is how a run is asked for its results.
        generation++
        main.post {
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                // Same.
            }
            listening = false
            listener?.invoke(AsrState.Idle)
        }
    }

    override fun release() {
        generation++
        main.post {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                // Same.
            }
            recognizer = null
        }
    }

    /** True while this callback belongs to the run the caller is actually in. */
    private val live: Boolean get() = runGeneration == generation

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!live) return
            listener?.invoke(AsrState.Listening())
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!live) return
            listener?.invoke(AsrState.Listening(lastPartial, rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!live) return
            listener?.invoke(AsrState.Processing)
        }

        override fun onError(error: Int) {
            if (!live) return

            // The disconnect is the one worth trying again rather than reporting: it
            // means the recognition service went away between runs, which is exactly
            // what "start dictating a second time" used to produce. One retry, once
            // per run, and only then does the user hear about it.
            listening = false
            // Only the disconnect. ERROR_CLIENT is what an ordinary cancel produces,
            // so retrying on it turns every stop into a restart — and on the first run
            // of a session, into a failure the user sees.
            if (!retried && error == ERROR_SERVER_DISCONNECTED) {
                retried = true
                retrySilently()
                return
            }

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
                    ERROR_SERVER_DISCONNECTED ->
                        AsrState.Error("The speech service disconnected, twice running.")
                    else -> AsrState.Error("Recognition failed (code $error).")
                }
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
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
            listening = false
            if (!live) return
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

    private companion object {
        /**
         * Named here rather than referenced from [SpeechRecognizer].
         *
         * ERROR_SERVER_DISCONNECTED was added in API 31 and the constant is not on the
         * compile target this app supports; the numeric value is part of the platform's
         * published contract and does not move.
         */
        const val ERROR_SERVER_DISCONNECTED = 11

        /** Long enough for the unbind to finish; short enough not to feel like a stall. */
        const val RETRY_DELAY_MS = 250L
    }
}
