package com.example.core.data

import android.content.Context
import java.io.File

/**
 * The word lists the keyboard starts from, before it has learned anything.
 *
 * Without one, a fresh install has nothing to suggest and nothing to check spelling
 * against until the user has typed enough for their own dictionary to be useful — so
 * the strip and auto-correction do nothing on the day somebody installs it, and they
 * reasonably conclude the features are broken. The first version of this shipped 575
 * Polish words, which is not a dictionary: it is a list of pronouns and conjunctions,
 * and it produced suggestions that looked arbitrary because they very nearly were.
 *
 * Two sources, in order. A list downloaded into the app's files directory wins; the
 * one packaged in the APK is the fallback. That ordering is the whole point of the
 * split: the bundled list means it works offline on day one, and the downloaded one
 * means nobody is stuck with my choice of words, my language, or my idea of how big a
 * list should be. A user with their own terminology imports it and it simply wins.
 *
 * Kept apart from what the user has typed. The personal dictionary still holds only
 * their own words, and "forget everything learned" does not touch these.
 */
object BundledDictionary {

    private const val ASSET_DIRECTORY = "dictionaries"
    const val FILES_DIRECTORY = "dictionaries"

    /**
     * A word and how common it is. [rank] is its position in the list, so lower is
     * more common; it is not a probability and is not claimed to be one.
     */
    data class Entry(val word: String, val rank: Int, val heldBack: Boolean = false)

    /**
     * One language, indexed for lookup rather than scanned.
     *
     * Sorted alphabetically with the rank carried alongside, so a prefix is a binary
     * search and a short walk rather than fifty thousand comparisons per keystroke.
     * The previous linear scan was fine over 575 words and would not have been over
     * these.
     */
    private class Index(entries: List<Entry>) {
        val sorted: List<Entry> = entries.sortedBy { it.word }
        val size: Int get() = sorted.size

        /** First position whose word is >= [prefix]. */
        private fun lowerBound(prefix: String): Int {
            var low = 0
            var high = sorted.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (sorted[mid].word < prefix) low = mid + 1 else high = mid
            }
            return low
        }

        fun startingWith(prefix: String): Sequence<Entry> = sequence {
            var i = lowerBound(prefix)
            while (i < sorted.size && sorted[i].word.startsWith(prefix)) {
                yield(sorted[i])
                i++
            }
        }

        fun contains(word: String): Boolean {
            val i = lowerBound(word)
            return i < sorted.size && sorted[i].word == word
        }
    }

    private val cache = HashMap<String, Index>()

    /** Languages with a list packaged in the APK. Downloaded ones are not limited to these. */
    val BUNDLED = listOf("en", "pl")

    /** Forgets a language so a freshly downloaded list is picked up without a restart. */
    @Synchronized
    fun invalidate(language: String? = null) {
        if (language == null) cache.clear() else cache.remove(language)
    }

    @Synchronized
    private fun index(context: Context, language: String): Index {
        cache[language]?.let { return it }
        val text = downloaded(context, language)?.takeIf { it.isNotBlank() }
            ?: asset(context, language)
        val built = Index(parse(text))
        cache[language] = built
        return built
    }

    fun downloadedFile(context: Context, language: String): File =
        File(File(context.filesDir, FILES_DIRECTORY), "$language.txt")

    private fun downloaded(context: Context, language: String): String? = try {
        downloadedFile(context, language).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    private fun asset(context: Context, language: String): String = try {
        context.assets.open("$ASSET_DIRECTORY/$language.txt").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    /**
     * Reads the list format: one word per line, most common first, `#` for a comment.
     *
     * A leading `!` means known but never offered unprompted. That is not censorship
     * and is deliberately not a separate blocklist: the word stays in the dictionary,
     * so it is spelled correctly, is never auto-corrected into something else, and can
     * always be typed. It is only kept from being put in front of somebody who did not
     * ask for it — which is exactly the [com.example.core.predict.Power.PROTECTIVE]
     * distinction the prediction core already makes, applied to a word list.
     */
    fun parse(text: String): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        var rank = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val heldBack = line.startsWith("!")
            val body = if (heldBack) line.substring(1) else line
            // Only the first column. Published frequency lists are "word count" pairs
            // and the app's own files are one word per line; reading the whole line
            // would turn a downloaded list into fifty thousand words each ending in a
            // number, which parses cleanly and suggests nothing.
            val word = body.substringBefore(' ')
                .substringBefore('\t')
                .substringBefore(',')
                .trim()
                .lowercase()
            if (word.isEmpty() || word.length > 40) return@forEach
            if (!seen.add(word)) return@forEach
            out.add(Entry(word, rank++, heldBack))
        }
        return out
    }

    /** "pl-PL", "pl_PL" and "pl" alike become "pl". Null when there is nothing to use. */
    private fun resolve(context: Context, locale: String?): String? {
        val tag = locale?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 } ?: return null
        if (tag in BUNDLED) return tag
        // A downloaded list can be for a language the APK never shipped, which is the
        // point of being able to download one.
        return tag.takeIf { downloadedFile(context, it).isFile }
    }

    /**
     * Completions for [prefix], most common first.
     *
     * Held-back words are not offered here. Single characters are skipped because a
     * completion no shorter than what was typed is not a completion.
     */
    fun startingWith(context: Context, locale: String?, prefix: String, limit: Int): List<Entry> {
        val language = resolve(context, locale) ?: return emptyList()
        if (prefix.isEmpty()) return emptyList()
        val needle = prefix.lowercase()
        return index(context, language).startingWith(needle)
            .filter { it.word.length > needle.length && !it.heldBack }
            .sortedBy { it.rank }
            .take(limit)
            .toList()
    }

    /** Whether the list has this word at all, held back or not. Used for spelling. */
    fun knows(context: Context, locale: String?, word: String): Boolean {
        val language = resolve(context, locale) ?: return false
        return index(context, language).contains(word.lowercase())
    }

    /**
     * Correction candidates: words beginning with the same letter, commonest first.
     *
     * Held back words are included, because a word somebody actually typed must never
     * be corrected into something else on the grounds that it is impolite.
     */
    fun sharingFirstLetter(context: Context, locale: String?, word: String, limit: Int): List<Entry> {
        val language = resolve(context, locale) ?: return emptyList()
        if (word.isEmpty()) return emptyList()
        val first = word.first().lowercaseChar().toString()
        return index(context, language).startingWith(first)
            .sortedBy { it.rank }
            .take(limit)
            .toList()
    }

    fun size(context: Context, locale: String?): Int {
        val language = resolve(context, locale) ?: return 0
        return index(context, language).size
    }
}
