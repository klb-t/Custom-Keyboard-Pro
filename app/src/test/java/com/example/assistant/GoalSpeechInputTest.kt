package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GoalSpeechInputTest {
    @Test fun externalIntentCannotAddAudioRoutingContextOrExecutionAuthority() {
        val incoming = Intent("RUN_ANYTHING").setPackage("malicious.app").apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Int.MAX_VALUE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.AUDIO_SOURCE", "content://secret/audio")
            putExtra("execute", true); putExtra("approved", true); putExtra("context", "private screen")
        }
        val output = GoalSpeechInput.request(incoming)
        check(output.action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH && output.`package` == null && output.component == null)
        check(output.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) == "pl-PL")
        check(output.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0) == 3)
        check(output.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        check(output.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        check(!output.hasExtra("execute") && !output.hasExtra("approved") && !output.hasExtra("context") &&
            !output.hasExtra("android.speech.extra.AUDIO_SOURCE"))
    }
    @Test fun malformedScalarPreferencesHaveSafeDefaults() {
        val incoming = Intent().apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "file:///private")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, "execute")
        }
        val output = GoalSpeechInput.request(incoming)
        check(!output.hasExtra(RecognizerIntent.EXTRA_LANGUAGE))
        check(output.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0) == 1)
        check(output.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL) == RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    @Test fun resultCountTextAndConfidenceAreBoundedAndUntypedFieldsAreDropped() {
        val source = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("x".repeat(9000), "b", "c", "d"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(Float.NaN, 0.5f, 100f, 1f))
            putString("private_context", "secret"); putBoolean("approved", true)
        }
        val output = GoalSpeechInput.results(source)
        check(output.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.length } == listOf(8000, 1, 1))
        check(output.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)!!.contentEquals(floatArrayOf(-1f, 0.5f, -1f)))
        check(output.keySet() == setOf(SpeechRecognizer.RESULTS_RECOGNITION, SpeechRecognizer.CONFIDENCE_SCORES))
    }
}
