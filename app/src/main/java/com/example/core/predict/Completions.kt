package com.example.core.predict

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.discovery.AiCapability
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderSpec
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the second row is showing, and how far along it is. */
data class CompletionState(
    /** Prefixes of one continuation, shortest first. */
    val slices: List<Slice> = emptyList(),
    /** Arriving; the slices so far are real but may still grow. */
    val running: Boolean = false,
    /** Why it stopped, when it has. Worth showing: "you had been waiting" is news. */
    val stoppedBecause: String? = null,
    val error: String? = null
) {
    val isEmpty: Boolean get() = slices.isEmpty()
}

/**
 * Why a keystroke did not become a request.
 *
 * Named rather than a bare boolean because the reasons are not interchangeable. Three
 * of them mean "not yet" and one of them, [SENSITIVE], means "not ever from here" —
 * and a future reader deciding whether it is safe to relax a check needs to be able to
 * tell which is which without guessing.
 */
enum class Refusal {
    /** A password box, or an incognito session. Text from here never leaves. */
    SENSITIVE,

    /** The feature is off, which is how it ships. */
    DISABLED,

    /** Too little written to continue anything. */
    TOO_SHORT,

    /** Nothing configured that can complete text. */
    NO_PROVIDER;

    companion object {
        /**
         * The whole decision, separated from the machinery that acts on it.
         *
         * Pure and synchronous on purpose: this is the guard standing between what
         * somebody types and somebody else's server, and a guard that can only be
         * exercised by making a network call is a guard nobody tests.
         *
         * [provider] is a lambda because resolving one parses the catalogue, and doing
         * that on every keystroke in a password field would be work done solely to
         * reach a conclusion already reached on the first line. [before] is a
         * CharSequence for the same reason: that is what the editor hands out, and
         * copying the whole surrounding buffer into a String in order to decide
         * whether to look at it is the wrong way round.
         */
        fun of(
            before: CharSequence,
            sensitive: Boolean,
            s: Settings,
            provider: () -> ProviderSpec?
        ): Refusal? = when {
            sensitive -> SENSITIVE
            !s.completionEnabled -> DISABLED
            before.length < s.completionMinChars -> TOO_SHORT
            provider()?.can(AiCapability.COMPLETE) != true -> NO_PROVIDER
            else -> null
        }
    }
}

/**
 * The prediction lane: continuing what is being written, rather than correcting it.
 *
 * Separate from [com.example.core.suggest.SuggestionEngine] on purpose, and the reason
 * is the one that shapes everything here. Corrections change text the user already
 * produced, often without being noticed, so they are hedged, cost-weighted and refuse
 * to act when the alternatives differ in what being wrong would cost. Completions add
 * text that only enters on a deliberate tap, so they may be as bold as they like.
 *
 * Two lanes, two sets of rules, one reason.
 *
 * What arrives is one generation cut at several depths — word, clause, sentence, the
 * lot. They are prefixes of the same continuation rather than four requests, so they
 * cost one inference and cannot contradict each other.
 */
class CompletionEngine(
    private val scope: CoroutineScope,
    private val settings: () -> Settings
) {

    private val _state = MutableStateFlow(CompletionState())
    val state: StateFlow<CompletionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped on every request, so a late stream for text that has moved on is dropped. */
    private var generation = 0L

    fun clear() {
        generation++
        job?.cancel()
        job = null
        _state.value = CompletionState()
    }

    /**
     * Asks for a continuation of [before], after the user stops typing.
     *
     * The debounce is not politeness. Every keystroke starting a request would spend
     * the user's money on text they are still in the middle of changing, and the strip
     * would flicker through continuations of half-words.
     *
     * [sensitive] is checked here rather than trusted to callers. This is the one path
     * in the keyboard that sends what is being typed to somebody else's machine, so
     * the field that must never take it — a password box, an incognito session — is
     * refused at the point of departure. A guard placed at every call site is a guard
     * that will one day be missing from a new call site.
     */
    fun request(before: CharSequence, sensitive: Boolean) {
        val s = settings()
        // Lazy, and looked up at most once: the catalogue is parsed from JSON on
        // every lookup, and this runs on every keystroke. In a password field the
        // decision is made on the first line and the parse never happens at all.
        val resolved by lazy(LazyThreadSafetyMode.NONE) {
            ProviderCatalog.byId(s.completionProvider.ifBlank { s.aiProvider }, s)
        }
        if (Refusal.of(before, sensitive, s) { resolved } != null) {
            clear()
            return
        }
        val provider = resolved ?: return

        val mine = ++generation
        job?.cancel()

        // Emptied here rather than after the debounce, and this is not cosmetic. The
        // offers on screen continue the text as it was; the text has just changed.
        // Leaving them up for the length of the debounce leaves them *tappable* for
        // the length of the debounce, and a tap would insert the continuation of a
        // sentence the user has already edited.
        _state.value = CompletionState()

        job = scope.launch {
            delay(s.completionDebounceMs)
            if (mine != generation) return@launch

            // Now it is running, and says so. Not a moment earlier: during the
            // debounce nothing has been asked of anybody, and a spinner through every
            // keystroke of a paragraph would be reporting work that is not happening.
            _state.value = CompletionState(running = true)
            val condition = StopCondition(
                tokenFloor = s.completionTokenFloor.toDouble(),
                surpriseBudget = s.completionSurpriseBudget.toDouble(),
                stopStrings = listOf("\n\n"),
                maxChars = s.completionMaxChars,
                maxMillis = s.completionMaxMillis
            )

            val builder = StringBuilder()
            val outcome = CallEngine.stream(
                provider = provider,
                capability = AiCapability.COMPLETE,
                input = CallInput(
                    // Blank means "whatever this capability says it uses". Sending an
                    // empty model name is a 400 that reads like a broken key, and a
                    // provider that names its own default should not need the user to
                    // repeat it before the feature works at all.
                    model = s.completionModel.ifBlank {
                        provider.capability(AiCapability.COMPLETE)?.defaultModel.orEmpty()
                            .ifBlank { provider.defaultModel }
                    },
                    prompt = before.takeLast(s.aiContextChars).toString(),
                    params = mapOf(
                        "maxTokens" to s.completionMaxTokens.toString(),
                        "temperature" to s.completionTemperature.toString()
                    )
                ),
                apiKey = s.completionApiKey.ifBlank { s.aiApiKey },
                condition = condition
            ) { token ->
                builder.append(token)
                // Shown as it arrives rather than at the end. A continuation that
                // appears all at once, late, is one nobody waits for — which is the
                // whole reason the engine learned to stream.
                if (mine == generation) {
                    _state.value = CompletionState(
                        slices = Slicer.slices(builder.toString()),
                        running = true
                    )
                }
            }

            if (mine != generation) return@launch
            outcome.fold(
                onSuccess = { result ->
                    _state.value = CompletionState(
                        slices = Slicer.slices(result.text),
                        running = false,
                        stoppedBecause = result.stoppedBecause
                    )
                },
                onFailure = { error ->
                    AppLogger.d("Completion", "did not finish: ${error.message}")
                    // Whatever arrived before it broke is still worth offering: a
                    // stream that failed after three words gave the user three words.
                    _state.value = CompletionState(
                        slices = Slicer.slices(builder.toString()),
                        running = false,
                        error = error.message
                    )
                }
            )
        }
    }

    /** Remembers that a slice was taken, which is the cleanest label this lane produces. */
    fun accepted(slice: Slice) {
        SettingsStore.update { s ->
            s.copy(completionAcceptedScope = slice.scope.name)
        }
        clear()
    }
}
