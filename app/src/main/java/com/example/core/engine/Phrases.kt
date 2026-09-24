package com.example.core.engine

import java.text.Normalizer

/**
 * Whether what was heard contains the phrase a wire is waiting for.
 *
 * Speech recognition is loose in exactly the ways that matter here: it drops
 * diacritics, splits and joins words, mishears one word in five, and wraps the phrase
 * in whatever else was said. So the phrase's words are looked for in order inside
 * what was heard, each allowed a small misspelling, and the share found is compared
 * with how exact the user asked it to be.
 */
object Phrases {

    /** Lower case, no diacritics, no punctuation, single spaces. "Otwórz, Sezamie!" → "otworz sezamie". */
    fun normalise(text: String): List<String> {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ł', 'l')
        return stripped.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    /** Share of the phrase's words found, in order, in [heard]: 0 to 1. */
    fun score(phrase: String, heard: String): Double {
        val want = normalise(phrase)
        if (want.isEmpty()) return 0.0
        val got = normalise(heard)
        var at = 0
        var found = 0
        for (w in want) {
            val hit = (at until got.size).firstOrNull { close(w, got[it]) }
            if (hit != null) {
                found++
                at = hit + 1
            }
        }
        return found.toDouble() / want.size
    }

    fun matches(phrase: String, heard: String, exactness: Double): Boolean =
        score(phrase, heard) >= exactness - 1e-9

    /** Same word, allowing one slip in a word of four letters or more. */
    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        return com.example.core.suggest.Correction.distance(a, b, 1) <= 1
    }
}
