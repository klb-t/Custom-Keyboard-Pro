package com.example.core.data

import android.content.Context

/**
 * Word lists that ship with the app.
 *
 * Without these the keyboard has nothing to suggest until the user has typed enough
 * for its own dictionary to be useful — which means the suggestion strip and
 * auto-correction do nothing on the day someone installs it, and they conclude the
 * features are broken.
 *
 * These are small, common-word lists in rough frequency order, kept separate from what
 * the user has typed: the personal dictionary still only contains their own words, and
 * "forget everything learned" does not touch these. A user who wants a real word list
 * can import one — see [KeyboardRepository.importWords].
 */
object BundledDictionary {

    private const val DIRECTORY = "dictionaries"

    private val cache = HashMap<String, List<Entry>>()

    /** [rank] is the position in the list; lower means more common. */
    data class Entry(val word: String, val rank: Int)

    /** Language tags with a bundled list. */
    val AVAILABLE = listOf("en", "pl")

    @Synchronized
    private fun load(context: Context, language: String): List<Entry> {
        cache[language]?.let { return it }
        val loaded = try {
            context.assets.open("$DIRECTORY/$language.txt").bufferedReader().use { reader ->
                reader.readText()
                    .split(Regex("\\s+"))
                    .asSequence()
                    .map { it.trim().lowercase() }
                    .filter { it.length > 1 }
                    .distinct()
                    .mapIndexed { index, word -> Entry(word, index) }
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache[language] = loaded
        return loaded
    }

    /** Normalises "pl-PL", "pl_PL" and "Polski" alike down to a bundled list, or null. */
    private fun resolve(locale: String?): String? {
        val tag = locale?.trim()?.lowercase()?.take(2) ?: return null
        return tag.takeIf { it in AVAILABLE }
    }

    fun startingWith(context: Context, locale: String?, prefix: String, limit: Int): List<Entry> {
        val language = resolve(locale) ?: return emptyList()
        if (prefix.isEmpty()) return emptyList()
        val needle = prefix.lowercase()
        return load(context, language)
            .asSequence()
            .filter { it.word.length > needle.length && it.word.startsWith(needle) }
            .take(limit)
            .toList()
    }

    fun knows(context: Context, locale: String?, word: String): Boolean {
        val language = resolve(locale) ?: return false
        val needle = word.lowercase()
        return load(context, language).any { it.word == needle }
    }

    /** Candidates for correction: words that start with the same letter. */
    fun sharingFirstLetter(context: Context, locale: String?, word: String, limit: Int): List<Entry> {
        val language = resolve(locale) ?: return emptyList()
        if (word.isEmpty()) return emptyList()
        val first = word.first().lowercaseChar()
        return load(context, language)
            .asSequence()
            .filter { it.word.firstOrNull() == first }
            .take(limit)
            .toList()
    }

    fun size(context: Context, locale: String?): Int {
        val language = resolve(locale) ?: return 0
        return load(context, language).size
    }
}
