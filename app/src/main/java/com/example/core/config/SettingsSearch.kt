package com.example.core.config

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** Local, deterministic intent hints. Aliases are bundled data, never a remote request. */
object SettingsSearch {
    private var aliases: Map<String, List<String>> = emptyMap()
    private var ignored: Set<String> = emptySet()

    fun init(context: Context) {
        runCatching { load(context.assets.open("settings_search_aliases.json").bufferedReader().use { it.readText() }) }
    }

    internal fun load(raw: String) {
        val json = JSONObject(raw)
        val map = json.getJSONObject("aliases")
        aliases = map.keys().asSequence().associate { phrase ->
            val terms = map.getJSONArray(phrase)
            normalise(phrase) to (0 until terms.length()).map { normalise(terms.getString(it)) }
        }
        val stop = json.getJSONArray("ignored")
        ignored = (0 until stop.length()).map { normalise(stop.getString(it)) }.toSet()
    }

    fun matches(request: String, limit: Int = 12): List<SettingSpec> {
        val query = normalise(request)
        if (query.isBlank()) return emptyList()
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in ignored }.distinct()
        val hints = aliases.filterKeys { it in query }.values.flatten().distinct()
        return SettingsSchema.all.map { spec ->
            val key = normalise(spec.key)
            val title = normalise(spec.label)
            val text = normalise("${spec.key} ${spec.label} ${spec.group} ${spec.help.orEmpty()}")
            val score = (if (key == query) 100 else 0) +
                hints.sumOf { if (it == key) 20 else if (it in text) 5 else 0 } +
                tokens.sumOf { if (it in key || it in title) 3 else if (it in text) 1 else 0 }
            spec to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun normalise(raw: String): String = Normalizer.normalize(raw.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}
