package com.example.core.io

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Instances of existing actions, independent of the keyboard surface rendering them. */
data class ActionProfile(
    val id: String,
    val label: String,
    val command: String? = null,
    val route: String? = null,
    val settingsQuery: String = "",
    val glyph: String? = null
)

object ActionProfiles {
    data class Result(val profiles: List<ActionProfile>, val errors: List<String>)

    fun load(context: Context, overrides: String): Result {
        val bundled = context.assets.open("action_profiles.json").bufferedReader().use { it.readText() }
        return merge(bundled, overrides)
    }

    /** Stable ids let a profile replace or hide one example without copying the catalogue. */
    fun merge(bundled: String, overrides: String): Result {
        val profiles = linkedMapOf<String, ActionProfile>()
        val errors = mutableListOf<String>()
        fun read(rows: JSONArray, source: String) {
            val seen = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                try {
                    val row = rows.getJSONObject(i)
                    val id = row.getString("id")
                    require(id.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "invalid id" }
                    require(seen.add(id)) { "duplicate id $id" }
                    if (row.optBoolean("hidden", false)) { profiles.remove(id); continue }
                    val old = profiles[id]
                    val label = row.optString("label", old?.label.orEmpty())
                    require(label.isNotBlank()) { "$id needs a label" }
                    val command = if (row.has("command")) row.getString("command") else if (row.has("route")) null else old?.command
                    val route = if (row.has("route")) row.getString("route") else if (row.has("command")) null else old?.route
                    require((command != null) xor (route != null)) { "$id needs exactly one command or route" }
                    if (command != null) require(Command.parse(command)?.let { Verbs.byId(it.verb) } != null) { "$id has an unknown command" }
                    if (route != null) require(com.example.core.config.SettingsDestinations.isKnown(route)) { "$id has an unknown route" }
                    profiles[id] = ActionProfile(id, label, command, route,
                        row.optString("settingsQuery", old?.settingsQuery ?: "ioActionProfilesJson"),
                        if (row.has("glyph")) row.getString("glyph") else old?.glyph)
                } catch (e: Exception) { errors += "$source row ${i + 1}: ${e.message}" }
            }
        }
        try {
            val root = JSONObject(bundled)
            require(root.getInt("schemaVersion") == 1) { "unsupported schema version" }
            read(root.getJSONArray("profiles"), "Built-in")
        } catch (e: Exception) { errors += "Built-in profiles: ${e.message}" }
        try { read(JSONArray(overrides), "Custom") }
        catch (e: Exception) { errors += "Custom profiles: ${e.message}" }
        return Result(profiles.values.toList(), errors)
    }
}
