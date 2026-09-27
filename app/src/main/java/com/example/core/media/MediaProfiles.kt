package com.example.core.media

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A grant selected by the user, not a guessed cloud endpoint or a storage path. */
data class MediaSourceProfile(
    val uri: String,
    val label: String,
    val kind: MediaSourceKind,
    val enabled: Boolean = true
)

enum class MediaSourceKind { FOLDER, DOCUMENT }
enum class MediaSort { NAME, NEWEST, LARGEST }

data class MediaEntry(
    val uri: String,
    val sourceUri: String,
    val sourceLabel: String,
    val name: String,
    val mime: String,
    val directory: Boolean,
    val virtual: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
)

/** File kinds are instances of one MIME filter, including user-created profiles. */
data class MediaViewProfile(
    val id: String,
    val label: String,
    val mimePatterns: List<String> = listOf("*/*"),
    val showHidden: Boolean = false,
    val showFolders: Boolean = true,
    val sort: MediaSort = MediaSort.NAME
) {
    fun apply(entries: List<MediaEntry>, query: String = ""): List<MediaEntry> {
        val words = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val filtered = entries.filter { entry ->
            (showHidden || !entry.name.startsWith('.')) &&
                (if (entry.directory) showFolders else mimePatterns.any { matchesMime(it, entry.mime) }) &&
                words.all { word ->
                    word in entry.name.lowercase(Locale.ROOT) ||
                        word in entry.mime.lowercase(Locale.ROOT) ||
                        word in entry.sourceLabel.lowercase(Locale.ROOT)
                }
        }
        val nameOrder = compareBy<MediaEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.uri }
        val order = when (sort) {
            MediaSort.NAME -> nameOrder
            MediaSort.NEWEST -> compareByDescending<MediaEntry> { it.modified ?: Long.MIN_VALUE }.then(nameOrder)
            MediaSort.LARGEST -> compareByDescending<MediaEntry> { it.size ?: Long.MIN_VALUE }.then(nameOrder)
        }
        return filtered.sortedWith(compareByDescending<MediaEntry> { it.directory }.then(order))
    }

    companion object {
        /** Minimal fallback; actual presets are loaded from media_view_profiles.json. */
        val defaults = listOf(MediaViewProfile("all", "All files"))

        fun matchesMime(pattern: String, mime: String): Boolean {
            val p = pattern.trim().lowercase(Locale.ROOT)
            val m = mime.lowercase(Locale.ROOT)
            return p == "*/*" || p == m || (p.endsWith("/*") && m.startsWith(p.dropLast(1)))
        }
    }
}

/** Versioned profile data. URI grants are Android state and are never importable. */
object MediaProfileCodec {
    fun sourcesToJson(sources: List<MediaSourceProfile>): String = JSONObject().put("version", 1)
        .put("sources", JSONArray().apply {
            sources.forEach { source -> put(JSONObject().put("uri", source.uri).put("label", source.label)
                .put("kind", source.kind.name).put("enabled", source.enabled)) }
        }).toString()

    fun sourcesFromJson(json: String): List<MediaSourceProfile> {
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        require(root.optInt("version", 1) == 1) { "Unsupported media source profile version" }
        val entries = root.optJSONArray("sources") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val item = entries.optJSONObject(i) ?: return@mapNotNull null
            val uri = item.optString("uri")
            val kind = runCatching { MediaSourceKind.valueOf(item.optString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            if (!uri.startsWith("content://")) return@mapNotNull null
            MediaSourceProfile(uri, item.optString("label").ifBlank { "Source" }, kind,
                item.optBoolean("enabled", true))
        }.distinctBy { it.uri }
    }

    fun viewToJson(view: MediaViewProfile): String = JSONObject().put("id", view.id).put("label", view.label)
        .put("mimePatterns", JSONArray(view.mimePatterns)).put("showHidden", view.showHidden)
        .put("showFolders", view.showFolders).put("sort", view.sort.name).toString()

    fun viewFromJson(json: String): MediaViewProfile {
        val root = JSONObject(json)
        val patterns = root.optJSONArray("mimePatterns")
        return MediaViewProfile(
            id = root.optString("id", "custom"), label = root.optString("label", "Custom"),
            mimePatterns = patterns?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: listOf("*/*"),
            showHidden = root.optBoolean("showHidden", false),
            showFolders = root.optBoolean("showFolders", true),
            sort = runCatching { MediaSort.valueOf(root.optString("sort")) }.getOrDefault(MediaSort.NAME)
        )
    }
}
