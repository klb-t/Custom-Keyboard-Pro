package com.example.core.asr

import android.content.Context
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.config.AsrEngines
import com.example.core.discovery.AiCapability
import com.example.core.discovery.AiWire
import com.example.core.discovery.ProviderCatalog
import com.example.util.AppLogger
import com.example.core.config.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Dictation, with the choice of what was actually said left to the user.
 *
 * Most keyboards take the recogniser's top hypothesis and type it. The recogniser
 * usually returns several, ranked, and the second one is often the right one —
 * especially for names, code and anything not in the language model. So the
 * alternatives are shown and the user picks; auto-committing the best guess is
 * available but off by default.
 *
 * When the user has configured a model, it can additionally be asked to propose
 * readings the recogniser did not return at all, which helps most in exactly the cases
 * where a single-transcript engine gives you nothing to choose between.
 */
class VoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> Settings
) {

    private val _state = MutableStateFlow<AsrState>(AsrState.Idle)
    val state: StateFlow<AsrState> = _state.asStateFlow()

    private var engine: AsrEngine? = null

    val isActive: Boolean
        get() = _state.value is AsrState.Listening || _state.value is AsrState.Processing

    fun toggle() {
        if (isActive) stop() else start()
    }

    fun start() {
        val s = settings()
        release()
        val created = engineFor(s)
        engine = created
        _state.value = AsrState.Listening()
        created.start(s.asrLanguage, s.asrAlternativeCount) { state ->
            when (state) {
                is AsrState.Results -> onResults(state.alternatives)
                else -> _state.value = state
            }
        }
    }

    /**
     * Which engine takes this dictation.
     *
     * The catalogue answers first when it has been asked to, and one of its answers
     * is "this phone" — a provider whose transcription wire is the on-device
     * recogniser. That is deliberate: it keeps "where does my voice go" a single
     * question with one list of answers, instead of a checkbox that means "not the
     * cloud" sitting next to a dropdown that means "which cloud".
     */
    private fun engineFor(s: Settings): AsrEngine {
        if (s.asrEngine == AsrEngines.PROVIDER && s.asrProvider.isNotBlank()) {
            val spec = ProviderCatalog.byId(s.asrProvider, s)
            val wire = spec?.capability(AiCapability.TRANSCRIBE)?.wire
            if (wire == AiWire.ON_DEVICE) return AndroidAsr(context)
            if (spec != null) {
                return RemoteAsr.forProvider(
                    context = context,
                    scope = scope,
                    provider = { ProviderCatalog.byId(settings().asrProvider, settings()) },
                    apiKey = { settings().asrApiKey },
                    model = { settings().asrModel }
                )
            }
            AppLogger.e(
                "Voice",
                "dictation provider '${s.asrProvider}' is not in the catalogue; using the phone"
            )
        }
        if (s.asrEngine == AsrEngines.REMOTE) {
            return RemoteAsr.forEndpoint(
                context = context,
                scope = scope,
                endpoint = { settings().asrRemoteUrl },
                apiKey = { settings().asrApiKey },
                model = { settings().asrModel }
            )
        }
        return AndroidAsr(context)
    }

    fun stop() {
        engine?.stop()
    }

    fun cancel() {
        engine?.cancel()
        _state.value = AsrState.Idle
    }

    fun dismiss() {
        _state.value = AsrState.Idle
    }

    fun release() {
        engine?.release()
        engine = null
    }

    private fun onResults(alternatives: List<AsrAlternative>) {
        val s = settings()
        _state.value = AsrState.Results(alternatives)

        val wantsMore = s.asrShowAlternatives &&
            s.aiEnabled &&
            alternatives.size < 2 &&
            alternatives.isNotEmpty()

        if (!wantsMore) return

        scope.launch {
            val best = alternatives.first().text
            val result = AiClient.complete(
                config = AiConfig.from(s, maxTokens = 200),
                systemPrompt = "You propose alternative readings of a speech transcript. " +
                    "Reply with a JSON array of strings and nothing else. Each entry is a " +
                    "plausible alternative for what was actually said — different word " +
                    "boundaries, homophones, proper nouns, punctuation. Keep the same " +
                    "language. Do not include the original.",
                userPrompt = best,
                fast = true
            )
            result.onSuccess { raw ->
                val extra = parseAlternatives(raw)
                if (extra.isNotEmpty() && _state.value is AsrState.Results) {
                    _state.value = AsrState.Results(
                        (alternatives + extra.map { AsrAlternative(it) }).distinctBy { it.text }
                    )
                }
            }
        }
    }

    private fun parseAlternatives(raw: String): List<String> = try {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) emptyList()
        else {
            val arr = JSONArray(raw.substring(start, end + 1))
            (0 until arr.length()).map { arr.optString(it) }
                .filter { it.isNotBlank() }
                .take(6)
        }
    } catch (e: Exception) {
        emptyList()
    }
}
