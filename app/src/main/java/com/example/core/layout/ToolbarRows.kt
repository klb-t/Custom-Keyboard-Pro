package com.example.core.layout

import org.json.JSONArray
import org.json.JSONObject

enum class ToolbarSource(val wire: String, val title: String) {
    SUGGESTIONS("suggestions", "Suggestions"), TOOLS("tools", "Tools"), CONTEXTUAL("contextual", "Context actions")
}
enum class ToolbarVisibility(val wire: String, val title: String) {
    ALWAYS("always", "Always"), CONTENT("content", "When there is content"), CONTEXTUAL("contextual", "When context actions exist")
}
data class ToolbarRow(
    val id: String,
    val sources: List<ToolbarSource>,
    val visibility: ToolbarVisibility = ToolbarVisibility.ALWAYS,
    /** Empty content does not move the keys underneath a travelling finger. */
    val reserveSpace: Boolean = true
)
data class ToolbarConfiguration(val rows: List<ToolbarRow>, val legacy: Boolean = false)
data class ToolbarEnvironment(
    val toolsEnabled: Boolean, val suggestionsEnabled: Boolean, val panelOpen: Boolean,
    val hasSuggestions: Boolean, val hasContextual: Boolean, val busy: Boolean = false
)
data class ToolbarSlot(val row: ToolbarRow, val showContent: Boolean, val legacy: Boolean)
data class ToolbarProfile(val id: String, val title: String, val rows: List<ToolbarRow>)
data class ToolbarTool(val panel: PanelId, val glyph: String, val title: String)

/** A row is a composition of sources. Examples are profiles of this same mechanism. */
object ToolbarRows {
    const val MAX_ROWS = 4
    /** Compound layouts share toolbar chrome owned by the first visible docked panel. */
    fun ownerPanelId(layout: LayoutDef): String? = layout.elements.firstOrNull {
        it.visible && it.placement == ElementPlacement.DOCKED
    }?.id
    private val allSources = ToolbarSource.entries.toList()
    val tools = listOf(
        ToolbarTool(PanelId.EMOJI, "☺", "Emoji"), ToolbarTool(PanelId.CLIPBOARD, "▤", "Clipboard"),
        ToolbarTool(PanelId.VOICE, "🎤", "Voice"), ToolbarTool(PanelId.AI_TOOLS, "✦", "AI tools"),
        ToolbarTool(PanelId.CURSOR, "⇄", "Cursor"), ToolbarTool(PanelId.LAYOUT_PICKER, "⌨", "Layouts"),
        ToolbarTool(PanelId.IO, "⌖", "Actions beyond the field")
    )
    val profiles = listOf(
        ToolbarProfile("mixed", "One mixed row", listOf(ToolbarRow("mixed", allSources))),
        ToolbarProfile("suggestions-tools", "Suggestions + tools", listOf(
            ToolbarRow("suggestions", listOf(ToolbarSource.SUGGESTIONS, ToolbarSource.CONTEXTUAL)),
            ToolbarRow("tools", listOf(ToolbarSource.TOOLS))
        )),
        ToolbarProfile("repeated-mixed", "Two mixed rows", listOf(
            ToolbarRow("mixed-1", allSources), ToolbarRow("mixed-2", allSources)
        ))
    )

    /** Blank retains the previous single adaptive strip, including its visibility switches. */
    fun parse(raw: String): Result<ToolbarConfiguration> = runCatching {
        if (raw.isBlank()) return@runCatching ToolbarConfiguration(listOf(ToolbarRow("legacy", allSources)), legacy = true)
        require(raw.length <= 16_384) { "Toolbar rows are too large" }
        val json = JSONObject(raw)
        require(json.optInt("version", 1) == 1) { "Unsupported toolbar version" }
        require(json.keys().asSequence().all { it in setOf("version", "rows") }) { "Unknown toolbar field" }
        val rows = json.getJSONArray("rows")
        require(rows.length() <= MAX_ROWS) { "Use at most $MAX_ROWS toolbar rows" }
        val parsed = (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index)
            require(item.keys().asSequence().all { it in setOf("id", "sources", "visibility", "reserveSpace") }) { "Unknown row field" }
            val id = item.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Each toolbar row needs a stable id" }
            val sourceJson = item.getJSONArray("sources")
            require(sourceJson.length() in 1..ToolbarSource.entries.size) { "Choose one or more row sources" }
            val sources = (0 until sourceJson.length()).map { sourceIndex ->
                ToolbarSource.entries.firstOrNull { it.wire == sourceJson.getString(sourceIndex) }
                    ?: error("Unknown toolbar source")
            }
            require(sources.distinct().size == sources.size) { "A source may appear once in each row" }
            val visibility = ToolbarVisibility.entries.firstOrNull { it.wire == item.optString("visibility", "always") }
                ?: error("Unknown row visibility")
            require(!item.has("reserveSpace") || item.get("reserveSpace") is Boolean) { "reserveSpace must be a boolean" }
            ToolbarRow(id, sources, visibility, item.optBoolean("reserveSpace", true))
        }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "Toolbar row ids must be unique" }
        ToolbarConfiguration(parsed)
    }

    fun write(rows: List<ToolbarRow>): String {
        val json = JSONObject().put("version", 1).put("rows", JSONArray().also { array ->
            rows.forEach { row -> array.put(JSONObject().put("id", row.id)
                .put("sources", JSONArray(row.sources.map { it.wire }))
                .put("visibility", row.visibility.wire).put("reserveSpace", row.reserveSpace)) }
        }).toString()
        parse(json).getOrThrow()
        return json
    }

    /** Invalid imported data uses the safe compatible strip; editors surface parse errors. */
    fun configuration(raw: String): ToolbarConfiguration = parse(raw).getOrElse { parse("").getOrThrow() }

    fun slots(configuration: ToolbarConfiguration, environment: ToolbarEnvironment): List<ToolbarSlot> {
        if (configuration.legacy && !environment.toolsEnabled && !environment.suggestionsEnabled && !environment.panelOpen) return emptyList()
        return configuration.rows.mapNotNull { row ->
            val hasContent = row.sources.any { source -> when (source) {
                ToolbarSource.TOOLS -> environment.toolsEnabled || environment.panelOpen
                ToolbarSource.SUGGESTIONS -> environment.suggestionsEnabled && !environment.panelOpen && (environment.hasSuggestions || environment.busy)
                ToolbarSource.CONTEXTUAL -> !environment.panelOpen && environment.hasContextual
            } }
            val visible = when (row.visibility) {
                ToolbarVisibility.ALWAYS -> true
                ToolbarVisibility.CONTENT -> hasContent
                ToolbarVisibility.CONTEXTUAL -> environment.hasContextual && !environment.panelOpen
            }
            if (visible || row.reserveSpace) ToolbarSlot(row, visible, configuration.legacy) else null
        }
    }
}
