package com.example.core.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * A situation in which a capital is conventionally implied.
 *
 * Separate from what the keyboard *does* about it, because they are independent
 * questions and a single switch marked "capitalise sentences" answers neither. Two
 * people can agree completely about when a capital belongs and still want opposite
 * behaviour from the keyboard — one wants shift pressed for them, the other wants to
 * type freely and be tidied up afterwards.
 */
enum class CapitalWhen {
    /** Nothing has been written in this field yet. */
    FIELD_START,

    /** After a full stop, question mark or exclamation mark and a space. */
    SENTENCE_END,

    /** After a line break. */
    LINE_START,

    /** Just past a bullet or a number that opens a list item. */
    LIST_ITEM,

    /** After a colon and a space, which some people and some languages capitalise. */
    AFTER_COLON,

    /** At the start of any word at all — which is how you get Title Case. */
    WORD_START,

    /**
     * The word just finished is one this person writes with a capital — a name, a
     * place, a brand ([ProperNouns]). A fact about the word rather than about where
     * it stands, so [situations] never reports it; the caller asks at a word's end.
     */
    PROPER_NOUN;

    companion object {
        fun from(name: String): CapitalWhen? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * What the keyboard does when a capital is implied.
 *
 * The important distinction is the one that runs through the whole app: [SHIFT] adds
 * nothing to text that exists, while [FIX_AFTER_WORD] reaches back and changes a word
 * the user already typed. That is the difference between the prediction lane and the
 * correction lane, and it is why a fix is registered as an auto-correction — so
 * backspace undoes it, which is the only thing that makes changing somebody's text
 * acceptable.
 */
enum class CapitalHow {
    /** Notice the situation and leave it alone. Present so a rule can say "not here". */
    NOTHING,

    /** Turn shift on and let the user type the capital themselves. */
    SHIFT,

    /** Let them type whatever they like and capitalise the word once it ends. */
    FIX_AFTER_WORD;

    companion object {
        fun from(name: String): CapitalHow? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * When the question is being asked.
 *
 * The same situation deserves different answers at different moments, and conflating
 * them is a real bug rather than a nicety: a field opened with "…done. " before the
 * cursor is a field somebody is *editing*, and pressing shift there is presumptuous,
 * while typing "…done. " and getting a capital next is exactly right. One axis, two
 * answers, no special case in the code.
 */
enum class CapitalMoment {
    /** The keyboard has just been shown on a field. */
    OPENING,

    /** A word or a sentence has just been finished by typing. */
    TYPING;

    companion object {
        fun from(name: String): CapitalMoment? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** One line of policy: in this situation, at these moments, do this. */
data class CapitalRule(
    val on: CapitalWhen,
    val does: CapitalHow,
    val at: Set<CapitalMoment> = setOf(CapitalMoment.OPENING, CapitalMoment.TYPING)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("on", on.name.lowercase())
        .put("does", does.name.lowercase())
        .put("at", JSONArray(at.map { it.name.lowercase() }))

    companion object {
        fun fromJson(o: JSONObject): CapitalRule? {
            val on = CapitalWhen.from(o.optString("on")) ?: return null
            val does = CapitalHow.from(o.optString("does")) ?: return null
            val moments = o.optJSONArray("at")
                ?.let { arr -> (0 until arr.length()).mapNotNull { CapitalMoment.from(arr.optString(it)) } }
                ?.toSet()
                // An absent list means both, so a rule can be written in two fields.
                // An empty one means neither, and is honoured: it is how a rule is
                // parked without being deleted.
                ?: CapitalMoment.entries.toSet()
            return CapitalRule(on, does, moments)
        }
    }
}

/**
 * Capitalisation as a set of rules rather than a switch.
 *
 * "Capitalise sentences" is four questions wearing one name: when does a capital
 * belong, what should the keyboard do about it, at which moment, and how sure does it
 * have to be. Answering them with a boolean picks one answer to each and hides the
 * other three, which is the opposite of what this app is for. Here the questions are
 * separate and every answer is data.
 *
 * The one thing that is *not* data is how the rules combine, and that follows the same
 * shape as [com.example.core.predict.ModifierStack]: a rule that says [CapitalHow.NOTHING]
 * is a veto and nothing outbids it, because a veto anything can outbid is not a veto.
 * Below that, [CapitalHow.SHIFT] wins over [CapitalHow.FIX_AFTER_WORD] — the mechanism
 * that touches nobody's existing text is preferred when both apply.
 */
object Capitalisation {

    /**
     * What today's behaviour looks like written down.
     *
     * Worth reading as the worked example it is. A sentence that has just been ended
     * by typing gets a capital next; the *same* text found in a field the keyboard has
     * only just opened does not, because opening a field with text already in it means
     * editing it, and editing happens mid-sentence. That used to be a hardcoded
     * exception with a boolean bolted to it. It is now one word in one rule.
     */
    val DEFAULT: List<CapitalRule> = listOf(
        CapitalRule(CapitalWhen.FIELD_START, CapitalHow.SHIFT),
        CapitalRule(CapitalWhen.SENTENCE_END, CapitalHow.SHIFT, setOf(CapitalMoment.TYPING)),
        CapitalRule(CapitalWhen.LINE_START, CapitalHow.SHIFT, setOf(CapitalMoment.TYPING)),
        // Nothing to press shift for: a name is only known once it has been typed.
        CapitalRule(CapitalWhen.PROPER_NOUN, CapitalHow.FIX_AFTER_WORD, setOf(CapitalMoment.TYPING))
    )

    /** The situations that imply a capital by position, which say nothing about a word. */
    val POSITIONAL: Set<CapitalWhen> = setOf(
        CapitalWhen.FIELD_START, CapitalWhen.SENTENCE_END, CapitalWhen.LINE_START,
        CapitalWhen.LIST_ITEM, CapitalWhen.AFTER_COLON
    )

    fun toJson(rules: List<CapitalRule>): String =
        JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

    /** Blank or unreadable gives [DEFAULT]; an explicit empty list gives no rules at all. */
    fun fromJson(text: String): List<CapitalRule> {
        if (text.isBlank()) return DEFAULT
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return DEFAULT
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { CapitalRule.fromJson(it) }
        }
    }

    /**
     * Every situation that holds at the cursor.
     *
     * [atStartOfField] is asked rather than inferred, because "no text before the
     * cursor" and "the text before the cursor has not been read yet" look identical
     * from here, and reading the second as the first is what made the keyboard
     * capitalise in the middle of sentences.
     */
    fun situations(before: CharSequence, atStartOfField: Boolean): Set<CapitalWhen> {
        val found = mutableSetOf<CapitalWhen>()

        var i = before.length
        var sawNewline = false
        var sawSpace = false
        while (i > 0 && before[i - 1].isWhitespace()) {
            if (before[i - 1] == '\n' || before[i - 1] == '\r') sawNewline = true else sawSpace = true
            i--
        }

        if (atStartOfField && i == 0) found += CapitalWhen.FIELD_START
        if (sawNewline) found += CapitalWhen.LINE_START
        // The start of a word is where anything has to be, including the very start.
        if (sawNewline || sawSpace || i == 0) found += CapitalWhen.WORD_START

        if (i > 0 && !sawNewline) {
            val prev = before[i - 1]
            if (sawSpace && prev in SENTENCE_END && !looksLikeAbbreviation(before, i)) {
                found += CapitalWhen.SENTENCE_END
            }
            if (sawSpace && prev == ':') found += CapitalWhen.AFTER_COLON
        }

        if (openedListItem(before)) found += CapitalWhen.LIST_ITEM

        return found
    }

    /** What to do, given the rules, the situations, and which moment this is. */
    fun action(
        rules: List<CapitalRule>,
        situations: Set<CapitalWhen>,
        moment: CapitalMoment
    ): CapitalHow {
        val matching = rules.filter { it.on in situations && moment in it.at }
        if (matching.isEmpty()) return CapitalHow.NOTHING
        // A veto anything can outbid is not a veto.
        if (matching.any { it.does == CapitalHow.NOTHING }) return CapitalHow.NOTHING
        return if (matching.any { it.does == CapitalHow.SHIFT }) CapitalHow.SHIFT
        else CapitalHow.FIX_AFTER_WORD
    }

    /** Convenience for the two call sites: situations and decision in one step. */
    fun decide(
        rules: List<CapitalRule>,
        before: CharSequence,
        atStartOfField: Boolean,
        moment: CapitalMoment
    ): CapitalHow = action(rules, situations(before, atStartOfField), moment)

    /** The capitalised form of a word, or the word itself when there is nothing to do. */
    fun capitalise(word: String): String {
        if (word.isEmpty()) return word
        val first = word[0]
        if (!first.isLetter() || first.isUpperCase()) return word
        return first.uppercase() + word.substring(1)
    }

    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    /**
     * "e.g. " and "i.e. " rather than the end of a sentence.
     *
     * A single letter immediately before the dot, itself not preceded by a letter, is
     * almost always an abbreviation. Getting this wrong is cheap in one direction and
     * irritating in the other, which is why it is worth the six lines.
     */
    private fun looksLikeAbbreviation(before: CharSequence, dotIndex: Int): Boolean {
        if (before[dotIndex - 1] != '.') return false
        if (dotIndex < 2 || !before[dotIndex - 2].isLetter()) return false
        val twoBack = if (dotIndex >= 3) before[dotIndex - 3] else ' '
        return !twoBack.isLetter()
    }

    private val LIST_OPENER = Regex("""(^|\n)[ \t]*([-*•‣–]|\(?\d{1,3}[.)])[ \t]+$""")

    /** True when everything since the last line break is a bullet and its space. */
    private fun openedListItem(before: CharSequence): Boolean =
        LIST_OPENER.containsMatchIn(before)
}
