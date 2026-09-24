package com.example.core.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * Words this person writes with a capital where no sentence rule would put one.
 *
 * Names, surnames, places, brands: a dictionary cannot know that "kowalski" is a
 * surname in one person's messages and a common noun in nobody's, but the person's
 * own typing says so — every time they capitalise it in the middle of a sentence. So
 * that is what is counted, against the times they wrote it in lower case, and a word
 * becomes a proper noun only when the capitals clearly win. The form kept is the one
 * they actually wrote, so "iPhone" and "McDonald" come back as written, not as
 * "Iphone".
 *
 * Words declared by hand are proper nouns from the first time, whatever the counts say.
 */
class ProperNouns(
    /** lower-case word → [capitalised count, lower-case count, preferred form] */
    private val counts: MutableMap<String, Entry> = mutableMapOf(),
    private val declared: MutableMap<String, String> = mutableMapOf()
) {
    data class Entry(var capital: Int = 0, var lower: Int = 0, var form: String = "")

    /**
     * One finished word, and whether a capital was implied where it started (the
     * start of a sentence says nothing about the word).
     */
    fun observe(word: String, capitalImplied: Boolean) {
        if (word.length < 2 || !word.first().isLetter()) return
        if (word.all { !it.isLetter() || it.isUpperCase() }) return // acronyms and shouting
        val key = word.lowercase()
        if (word.first().isUpperCase()) {
            if (capitalImplied) return
            val e = counts.getOrPut(key) { Entry() }
            e.capital++
            e.form = word
        } else if (word == key) {
            // Only words already seen with a capital are worth counting in lower case:
            // tracking every common word would crowd out the names.
            counts[key]?.let { it.lower++ }
        }
        if (counts.size > MAX_TRACKED) trim()
    }

    /** The form to write [word] in, or null when it is not a proper noun here. */
    fun formFor(word: String): String? {
        val key = word.lowercase()
        declared[key]?.let { return it }
        val e = counts[key] ?: return null
        if (e.capital >= MIN_CAPITALS && e.capital > e.lower * 2 && e.form.isNotEmpty()) return e.form
        return null
    }

    fun declare(form: String) {
        if (form.isNotBlank()) declared[form.trim().lowercase()] = form.trim()
    }

    fun forget(word: String) {
        val key = word.lowercase()
        declared.remove(key)
        counts.remove(key)
    }

    /** Forgets the least used first, so the list keeps the names that matter. */
    private fun trim() {
        val drop = counts.entries.sortedBy { it.value.capital + it.value.lower }
            .take(counts.size - MAX_TRACKED * 9 / 10)
            .map { it.key }
        drop.forEach { counts.remove(it) }
    }

    fun toJson(): String = JSONObject().apply {
        put("counts", JSONObject().apply {
            counts.forEach { (k, e) -> put(k, JSONArray(listOf(e.capital, e.lower, e.form))) }
        })
        put("declared", JSONArray(declared.values.toList()))
    }.toString()

    companion object {
        const val MIN_CAPITALS = 2
        const val MAX_TRACKED = 3000

        fun fromJson(raw: String?): ProperNouns {
            if (raw.isNullOrBlank()) return ProperNouns()
            return runCatching {
                val o = JSONObject(raw)
                val counts = mutableMapOf<String, Entry>()
                o.optJSONObject("counts")?.let { c ->
                    c.keys().forEach { k ->
                        val a = c.optJSONArray(k) ?: return@forEach
                        counts[k] = Entry(a.optInt(0), a.optInt(1), a.optString(2, ""))
                    }
                }
                val declared = mutableMapOf<String, String>()
                o.optJSONArray("declared")?.let { d ->
                    (0 until d.length()).map { d.optString(it) }.filter { it.isNotBlank() }
                        .forEach { declared[it.lowercase()] = it }
                }
                ProperNouns(counts, declared)
            }.getOrElse { ProperNouns() }
        }
    }
}
