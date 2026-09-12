package com.example.core.panels

import com.example.core.config.SettingsSchema
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * A settings panel described as data, so one can exist without having been written.
 *
 * The app hardcodes how to *render* a panel and how to *apply* a setting; it does not
 * hardcode which panels exist. That split is what lets a user ask for a screen nobody
 * built — the model writes one of these, the renderer draws it, and the controls
 * drive the same settings every other screen drives.
 *
 * The honesty constraint lives in [PanelControl.key]: a control must name a real
 * setting. A panel cannot invent behaviour, only surface behaviour that exists, and
 * [PanelSpec.validate] drops anything that names a key the app does not have rather
 * than rendering a control that would quietly do nothing.
 */
data class PanelControl(
    val key: String,
    val label: String? = null,
    val help: String? = null,
    val min: Float? = null,
    val max: Float? = null
)

data class PanelSpec(
    val id: String,
    val title: String,
    val description: String? = null,
    /** What the user asked for, kept so the panel can be regenerated or explained. */
    val request: String = "",
    val controls: List<PanelControl> = emptyList(),
    /** The model's own caveat — shown to the user verbatim, never paraphrased. */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Keeps only controls that name a setting this build actually has.
     *
     * Returns the cleaned panel and the keys that were dropped, so the screen can say
     * "the model asked for X and there is no such setting" instead of showing a
     * control that looks real and does nothing.
     */
    fun validate(): Pair<PanelSpec, List<String>> {
        val dropped = controls.map { it.key }.filter { SettingsSchema.spec(it) == null }
        val kept = controls.filter { SettingsSchema.spec(it.key) != null }
        return copy(controls = kept) to dropped
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        description?.let { put("description", it) }
        put("request", request)
        note?.let { put("note", it) }
        put("createdAt", createdAt)
        put("controls", JSONArray().apply {
            controls.forEach { c ->
                put(JSONObject().apply {
                    put("key", c.key)
                    c.label?.let { put("label", it) }
                    c.help?.let { put("help", it) }
                    c.min?.let { put("min", it.toDouble()) }
                    c.max?.let { put("max", it.toDouble()) }
                })
            }
        })
    }

    companion object {
        /**
         * Reads a panel from JSON, tolerantly — the writer is a language model, so
         * this accepts the shapes one plausibly produces: controls as objects, as
         * bare key strings, or the whole panel wrapped in a code fence.
         */
        fun fromJson(o: JSONObject): PanelSpec {
            val controls = mutableListOf<PanelControl>()
            val raw = o.optJSONArray("controls") ?: o.optJSONArray("settings") ?: JSONArray()
            for (i in 0 until raw.length()) {
                when (val item = raw.opt(i)) {
                    is String -> controls += PanelControl(key = item)
                    is JSONObject -> {
                        val key = item.optString("key").ifBlank { item.optString("setting") }
                        if (key.isNotBlank()) {
                            controls += PanelControl(
                                key = key,
                                label = item.optString("label").ifBlank { null },
                                help = item.optString("help").ifBlank { item.optString("description").ifBlank { null } },
                                min = if (item.has("min")) item.optDouble("min").toFloat() else null,
                                max = if (item.has("max")) item.optDouble("max").toFloat() else null
                            )
                        }
                    }
                }
            }
            return PanelSpec(
                id = o.optString("id").ifBlank { "panel_${System.currentTimeMillis()}" },
                title = o.optString("title").ifBlank { "Panel" },
                description = o.optString("description").ifBlank { null },
                request = o.optString("request"),
                controls = controls,
                note = o.optString("note").ifBlank { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        fun parse(raw: String): Result<PanelSpec> = runCatching {
            fromJson(JSONObject(LayoutJson.stripCodeFence(raw)))
        }

        fun listFromJson(raw: String): List<PanelSpec> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(LayoutJson.stripCodeFence(raw))
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listToJson(panels: List<PanelSpec>): String =
            JSONArray().apply { panels.forEach { put(it.toJson()) } }.toString()
    }
}
