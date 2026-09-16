package com.example.ui.kb

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.PopupGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which tab a symbol board opens on, and which tabs stay open alongside it.
 *
 * Both are remembered, because both are habits rather than choices. Someone writing a
 * paper reaches for the same tab twenty times an hour; making them find it again each
 * time is the kind of small tax that decides whether a feature gets used at all.
 *
 * The memory is per key — the board on `d` and the board on `-` are different
 * questions — while pins are global, because "always show me Polish accents too"
 * is a statement about the person, not about the key.
 */
object BoardMemory {

    fun selectedGroup(settings: Settings, keyId: String, groups: List<PopupGroup>): String {
        if (groups.isEmpty()) return ""
        val remembered = try {
            JSONObject(settings.popupTabMemoryJson.ifBlank { "{}" }).optString(keyId)
        } catch (e: Exception) {
            ""
        }
        return groups.firstOrNull { it.id == remembered }?.id ?: groups.first().id
    }

    fun rememberGroup(keyId: String, groupId: String) {
        SettingsStore.update { s ->
            val root = try {
                JSONObject(s.popupTabMemoryJson.ifBlank { "{}" })
            } catch (e: Exception) {
                JSONObject()
            }
            root.put(keyId, groupId)
            s.copy(popupTabMemoryJson = root.toString())
        }
    }

    fun pinnedGroups(settings: Settings): Set<String> = try {
        val arr = JSONArray(settings.popupPinnedTabsJson.ifBlank { "[]" })
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun togglePin(groupId: String) {
        SettingsStore.update { s ->
            val current = pinnedGroups(s)
            val next = if (groupId in current) current - groupId else current + groupId
            s.copy(popupPinnedTabsJson = JSONArray(next.toList()).toString())
        }
    }
}
