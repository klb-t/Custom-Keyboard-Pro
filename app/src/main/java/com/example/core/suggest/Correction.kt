package com.example.core.suggest

import android.content.Context
import com.example.core.data.BundledDictionary
import com.example.core.data.KeyboardRepository

/**
 * Deciding whether a typed word was a mistake.
 *
 * Auto-correction is off by default and this is why: replacing what someone typed is
 * only right when the evidence is strong, and "strong" has to be defined somewhere.
 * The rules here are deliberately timid — one edit away, a word the user has typed
 * several times, and a word they have never typed themselves — because a correction
 * that fires when it should not is far more annoying than one that does not fire.
 */
object Correction {

    /** Words shorter than this are left alone; too many of them are deliberate. */
    private const val MIN_LENGTH = 4

    /** The candidate must have been typed at least this often to overrule the user. */
    private const val MIN_CANDIDATE_COUNT = 4

    /**
     * Optimal string alignment distance (Damerau-Levenshtein restricted to adjacent
     * transpositions), stopping early once it exceeds [limit].
     *
     * Transpositions matter here: on a keyboard, "teh" for "the" is one slip, not two.
     */
    fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        var beforePrevious = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, beforePrevious[j - 2] + 1)
                }
                current[j] = value
                if (value < best) best = value
            }
            if (best > limit) return limit + 1
            beforePrevious = previous.copyOf()
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }

    /**
     * A better spelling of [word], or null to leave it alone.
     *
     * Returns null when the word is already known, when nothing is close enough, or
     * when the closest candidate is not used much more often than the word itself.
     */
    suspend fun suggest(
        context: Context,
        repository: KeyboardRepository,
        word: String,
        locale: String
    ): String? {
        if (word.length < MIN_LENGTH) return null
        if (word.any { !it.isLetter() }) return null
        if (word != word.lowercase()) return null  // Names and acronyms are not typos.
        if (repository.knows(word)) return null
        if (BundledDictionary.knows(context, locale, word)) return null

        val limit = if (word.length >= 7) 2 else 1
        var best: String? = null
        var bestWeight = 0
        var bestDistance = limit + 1

        fun consider(candidate: String, weight: Int) {
            if (candidate == word) return
            val d = distance(word, candidate, limit)
            if (d > limit) return
            // Prefer the closest; break ties by how common the word is.
            if (d < bestDistance || (d == bestDistance && weight > bestWeight)) {
                best = candidate
                bestWeight = weight
                bestDistance = d
            }
        }

        repository.wordsStartingWith(word.take(1), 120).forEach { entry ->
            if (!entry.blocked && entry.count >= MIN_CANDIDATE_COUNT) consider(entry.word, entry.count)
        }
        // The bundled list is what makes correction work before the user has typed
        // enough for their own dictionary to be any use. A common word outranks a rare
        // one, which is what the rank encodes.
        BundledDictionary.sharingFirstLetter(context, locale, word, 400).forEach { entry ->
            consider(entry.word, MIN_CANDIDATE_COUNT + (400 - entry.rank).coerceAtLeast(1))
        }
        return best
    }
}
