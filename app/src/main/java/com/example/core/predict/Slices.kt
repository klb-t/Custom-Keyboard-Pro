package com.example.core.predict

import org.json.JSONArray
import org.json.JSONObject

/**
 * How far to let a generation run.
 *
 * Every field is a reason to stop, and they are data because the right one differs by
 * what is being written: prose ends at a full stop, code ends when the brackets close,
 * and on a phone both end when the user has been waiting too long.
 *
 * A probability floor alone is not enough, which is the non-obvious part. One
 * genuinely uncertain token in the middle of a confident run — a name, a number — cuts
 * a good continuation in half. A budget of total surprise keeps going through it and
 * stops when the generation as a whole has drifted, which is the thing actually worth
 * detecting.
 */
data class StopCondition(
    /** Cut when a single token falls below this log-probability. 0 disables it. */
    val tokenFloor: Double = 0.0,
    /** Cut when accumulated surprise exceeds this. Survives one bad token; catches drift. */
    val surpriseBudget: Double = 0.0,
    /** Cut at the first of these strings. */
    val stopStrings: List<String> = emptyList(),
    /** Hard cap. Always set, because every other condition can fail to fire. */
    val maxChars: Int = 2000,
    /** Cut once this long has passed, because latency is a stopping condition too. */
    val maxMillis: Long = 4000L,
    /** Cut when brackets opened during the generation have all closed. */
    val untilBalanced: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (tokenFloor != 0.0) put("tokenFloor", tokenFloor)
        if (surpriseBudget != 0.0) put("surpriseBudget", surpriseBudget)
        if (stopStrings.isNotEmpty()) put("stopStrings", JSONArray(stopStrings))
        put("maxChars", maxChars)
        put("maxMillis", maxMillis)
        if (untilBalanced) put("untilBalanced", true)
    }

    companion object {
        fun fromJson(o: JSONObject): StopCondition = StopCondition(
            tokenFloor = o.optDouble("tokenFloor", 0.0),
            surpriseBudget = o.optDouble("surpriseBudget", 0.0),
            stopStrings = o.optJSONArray("stopStrings")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                // Not isNotBlank: "\n\n" is entirely whitespace and is also the
                // commonest stop string there is — "stop at the end of the
                // paragraph". Filtering blanks silently threw it away.
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            maxChars = o.optInt("maxChars", 2000),
            maxMillis = o.optLong("maxMillis", 4000L),
            untilBalanced = o.optBoolean("untilBalanced", false)
        )
    }
}

/** How much of a continuation one tap accepts. */
enum class Scope {
    WORD, CLAUSE, SENTENCE, PARAGRAPH, ALL;

    fun label(): String = when (this) {
        WORD -> "word"
        CLAUSE -> "phrase"
        SENTENCE -> "sentence"
        PARAGRAPH -> "paragraph"
        ALL -> "all of it"
    }
}

/** One offer: a prefix of a generation, and how much of it this is. */
data class Slice(val scope: Scope, val text: String) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Cutting one generation into the several things a user might want to accept.
 *
 * The point is that these are *prefixes of the same continuation* rather than separate
 * requests. One inference, four offers — far cheaper than four generations, and they
 * cannot contradict each other, which four separate ones routinely would.
 *
 * Descending the suggestion tree is then the same operation seen from the other side:
 * taking a longer slice, or regenerating from the one just accepted.
 */
object Slicer {

    fun slices(generated: String, scopes: List<Scope> = Scope.entries): List<Slice> =
        scopes.map { Slice(it, cut(generated, it)) }
            .filterNot { it.isEmpty }
            // Two scopes landing on the same text are one offer, not two: a
            // single-sentence continuation has nothing to say at paragraph scope.
            .distinctBy { it.text }

    fun cut(generated: String, scope: Scope): String {
        if (generated.isBlank()) return ""
        return when (scope) {
            Scope.WORD -> upToWord(generated)
            Scope.CLAUSE -> upToAny(generated, CLAUSE_ENDS)
                ?: upToAny(generated, SENTENCE_ENDS)
                ?: generated
            Scope.SENTENCE -> upToAny(generated, SENTENCE_ENDS) ?: generated
            Scope.PARAGRAPH -> generated.substringBefore("\n\n", generated)
            Scope.ALL -> generated
        }.trimEnd()
    }

    /**
     * Through any leading whitespace and then to the end of one word.
     *
     * The leading space is kept deliberately: a continuation nearly always begins with
     * one, and dropping it produces "thekeyboard" every time somebody accepts a single
     * word.
     */
    private fun upToWord(text: String): String {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return ""
        val rest = text.substring(start)
        val end = rest.indexOfFirst { it.isWhitespace() || it in WORD_ENDS }
        return if (end <= 0) text else text.substring(0, start + end)
    }

    private fun upToAny(text: String, terminators: Set<Char>): String? {
        // Past any leading punctuation, so a continuation that opens with a comma is
        // not cut back to the comma itself.
        val from = text.indexOfFirst { !it.isWhitespace() && it !in terminators }
        if (from < 0) return null
        val index = text.substring(from).indexOfFirst { it in terminators }
        if (index < 0) return null
        return text.substring(0, from + index + 1)
    }

    /**
     * Whether everything opened has been closed.
     *
     * Used by [StopCondition.untilBalanced], and worth having because "generate until
     * the function closes" is a better stopping rule for code than any number of
     * tokens. Anything inside a string is ignored, since an apostrophe in a comment
     * must not leave the whole generation looking unbalanced forever.
     */
    fun isBalanced(text: String): Boolean {
        val stack = ArrayDeque<Char>()
        var inString: Char? = null
        var escaped = false
        text.forEach { c ->
            when {
                escaped -> escaped = false
                c == '\\' && inString != null -> escaped = true
                inString != null -> if (c == inString) inString = null
                c == '"' || c == '\'' -> inString = c
                c == '(' || c == '[' || c == '{' -> stack.addLast(c)
                c == ')' -> if (stack.removeLastOrNull() != '(') return false
                c == ']' -> if (stack.removeLastOrNull() != '[') return false
                c == '}' -> if (stack.removeLastOrNull() != '{') return false
            }
        }
        return stack.isEmpty() && inString == null
    }

    private val WORD_ENDS = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"')
    private val CLAUSE_ENDS = setOf(',', ';', ':')
    private val SENTENCE_ENDS = setOf('.', '!', '?', '\n')
}

/**
 * A running generation, decided upon one token at a time.
 *
 * Separate from the transport so it can be tested without one: hand it tokens and
 * their log-probabilities and it says when to stop and why. The reason is kept because
 * "it stopped" and "it stopped because you had been waiting four seconds" are
 * different things to show somebody.
 */
class GenerationCut(
    private val condition: StopCondition,
    private val startedAt: Long = System.currentTimeMillis()
) {

    private val builder = StringBuilder()
    private var surprise = 0.0
    private var sawOpening = false

    var stoppedBecause: String? = null
        private set

    val text: String get() = builder.toString()

    /** Returns false once the generation should stop. */
    fun accept(token: String, logP: Double? = null, now: Long = System.currentTimeMillis()): Boolean {
        if (stoppedBecause != null) return false

        if (condition.maxMillis > 0 && now - startedAt > condition.maxMillis) {
            stoppedBecause = "you had been waiting"
            return false
        }
        if (logP != null && condition.tokenFloor != 0.0 && logP < condition.tokenFloor) {
            stoppedBecause = "it stopped being sure"
            return false
        }
        if (logP != null && condition.surpriseBudget > 0.0) {
            surprise += -logP
            if (surprise > condition.surpriseBudget) {
                stoppedBecause = "it had drifted too far"
                return false
            }
        }

        builder.append(token)
        // Only a brace counts as having opened something. Parentheses balance
        // constantly mid-expression — "fun x() " is balanced the instant it is
        // written — so counting them would stop every code generation on its first
        // token. "Until the block closes" is what this condition means.
        if (token.contains('{')) sawOpening = true

        condition.stopStrings.forEach { stop ->
            val at = builder.indexOf(stop)
            if (at >= 0) {
                builder.setLength(at)
                stoppedBecause = "it reached a stopping point"
                return false
            }
        }
        if (builder.length >= condition.maxChars) {
            builder.setLength(condition.maxChars)
            stoppedBecause = "it was long enough"
            return false
        }
        if (condition.untilBalanced && sawOpening && Slicer.isBalanced(builder.toString())) {
            stoppedBecause = "everything it opened was closed"
            return false
        }
        return true
    }
}
