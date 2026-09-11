package com.example.core.suggest

import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTasks
import com.example.core.config.Settings
import com.example.core.data.KeyboardRepository
import com.example.core.text.TextOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SuggestionSource { SHORTCUT, DICTIONARY, NEXT_WORD, AI, CORRECTION }

data class Suggestion(
    val text: String,
    val source: SuggestionSource,
    /** Replace the word under the cursor, rather than appending after it. */
    val replacesWord: Boolean,
    val score: Float = 0f,
    /** Shown instead of [text] when the completion is only a fragment. */
    val display: String = text
)

/**
 * Produces the contents of the suggestion strip.
 *
 * Local sources answer instantly and always run; the model, when the user has enabled
 * one, answers later and is merged in when it arrives. That ordering matters: the
 * strip must never wait on a network round trip, so a slow or absent model degrades to
 * a plain word-completion keyboard rather than to an empty strip.
 */
class SuggestionEngine(
    private val scope: CoroutineScope,
    private val repository: KeyboardRepository,
    private val settings: () -> Settings
) {

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var localJob: Job? = null
    private var aiJob: Job? = null

    /** Bumped on every request so a late response for stale text is discarded. */
    private var generation = 0L

    fun clear() {
        generation++
        localJob?.cancel()
        aiJob?.cancel()
        _suggestions.value = emptyList()
        _aiBusy.value = false
    }

    /**
     * Recomputes suggestions for the text before the cursor.
     *
     * [sensitive] is set for password fields and for editors that asked not to be
     * learned from. Nothing is suggested, stored or sent when it is true — that check
     * lives here rather than at each call site so it cannot be forgotten at one of them.
     */
    fun update(textBeforeCursor: CharSequence, sensitive: Boolean, locale: String) {
        val s = settings()
        if (!s.suggestionsEnabled || sensitive) {
            clear()
            return
        }

        generation++
        val myGeneration = generation
        localJob?.cancel()
        aiJob?.cancel()
        _aiBusy.value = false

        val word = TextOps.currentWord(textBeforeCursor)
        val precedingText = textBeforeCursor.dropLast(word.length)
        val previousWord = TextOps.currentWord(precedingText.trimEnd())

        localJob = scope.launch {
            val local = collectLocal(word, previousWord, locale, s)
            if (myGeneration == generation) _suggestions.value = local
        }

        if (s.aiEnabled && s.aiCompletionEnabled && textBeforeCursor.length >= s.aiCompletionMinChars) {
            aiJob = scope.launch {
                delay(s.aiCompletionDebounceMs)
                if (myGeneration != generation) return@launch
                _aiBusy.value = true
                val context = textBeforeCursor.takeLast(s.aiContextChars).toString()
                val result = AiClient.complete(
                    config = AiConfig.from(s, maxTokens = 24),
                    systemPrompt = AiTasks.COMPLETION_SYSTEM,
                    userPrompt = context,
                    fast = true
                )
                if (myGeneration != generation) return@launch
                _aiBusy.value = false
                result.onSuccess { raw ->
                    val completion = cleanCompletion(raw, context)
                    if (completion.isNotBlank()) {
                        _lastError.value = null
                        _suggestions.value = mergeAi(_suggestions.value, completion, word)
                    }
                }.onFailure { error ->
                    _lastError.value = error.message
                }
            }
        }
    }

    private suspend fun collectLocal(
        word: String,
        previousWord: String,
        locale: String,
        s: Settings
    ): List<Suggestion> {
        val out = LinkedHashMap<String, Suggestion>()

        // A text shortcut that matches exactly wins: the user asked for it by name.
        if (word.isNotEmpty()) {
            repository.findShortcut(word)?.let { shortcut ->
                out[shortcut.expansion] = Suggestion(
                    text = shortcut.expansion,
                    source = SuggestionSource.SHORTCUT,
                    replacesWord = true,
                    score = 1000f,
                    display = shortcut.expansion.take(24)
                )
            }
        }

        if (s.personalDictionary) {
            if (word.isNotEmpty()) {
                // Words that continue what is being typed.
                repository.wordsStartingWith(word, s.suggestionCount * 3).forEach { entity ->
                    if (entity.word != word) {
                        out.getOrPut(entity.word) {
                            Suggestion(entity.word, SuggestionSource.DICTIONARY, true, entity.count.toFloat())
                        }
                    }
                }
                // Words the user has typed after this one before, filtered by prefix.
                repository.nextWords(previousWord, word, s.suggestionCount).forEach { bigram ->
                    out.getOrPut(bigram.next) {
                        Suggestion(bigram.next, SuggestionSource.NEXT_WORD, true, bigram.count * 10f)
                    }
                }
            } else if (previousWord.isNotEmpty()) {
                // Nothing typed yet: offer what usually comes next.
                repository.nextWords(previousWord, "", s.suggestionCount * 2).forEach { bigram ->
                    out.getOrPut(bigram.next) {
                        Suggestion(bigram.next, SuggestionSource.NEXT_WORD, false, bigram.count.toFloat())
                    }
                }
            }
        }

        return out.values.sortedByDescending { it.score }.take(s.suggestionCount * 2)
    }

    private fun mergeAi(current: List<Suggestion>, completion: String, word: String): List<Suggestion> {
        val aiSuggestion = Suggestion(
            text = completion,
            source = SuggestionSource.AI,
            // The model continues from the cursor; it does not rewrite the current word.
            replacesWord = false,
            score = 500f,
            display = completion.trim().take(28)
        )
        // The model's guess goes first, because the user opted into having it.
        return (listOf(aiSuggestion) + current.filter { it.text != completion })
            .distinctBy { it.text }
    }

    /**
     * Models add quotes, restate the prompt and wrap things in markdown. Strip all of
     * that, and drop the answer entirely if it just echoes the input.
     */
    private fun cleanCompletion(raw: String, context: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBefore("```").trim()
        }
        if (text.length >= 2 &&
            ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'")))
        ) {
            text = text.substring(1, text.length - 1)
        }
        // Some models repeat the prompt before continuing it.
        val tail = context.takeLast(40)
        if (tail.isNotEmpty() && text.startsWith(tail)) text = text.removePrefix(tail)
        text = text.substringBefore('\n').trim()
        if (text.equals(context.takeLast(text.length), ignoreCase = true)) return ""
        return text.take(80)
    }

    /** Records a committed word so the next-word model improves. */
    fun learn(previousWord: String?, word: String, sensitive: Boolean, locale: String) {
        val s = settings()
        if (!s.learnFromTyping || sensitive || word.isBlank()) return
        scope.launch { repository.learn(previousWord, word, locale) }
    }

    fun block(word: String) {
        scope.launch {
            repository.blockWord(word)
            _suggestions.value = _suggestions.value.filterNot { it.text == word }
        }
    }
}
