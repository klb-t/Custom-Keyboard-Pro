package com.example.core.layout

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the user dragged individual keys to, in normalised screen coordinates.
 *
 * Kept as its own small format rather than by editing the layout, because a pin is
 * about *this screen* and the layout is about the keyboard everywhere: dragging one
 * key in free mode should not silently fork the layout the user is also using docked,
 * on another device, or in another app.
 */
object FreeKeyPins {

    fun parse(raw: String): Map<String, NormRect> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(LayoutJson.stripCodeFence(raw))
            buildMap {
                root.keys().forEach { id ->
                    val arr = root.optJSONArray(id) ?: return@forEach
                    if (arr.length() >= 4) {
                        put(
                            id,
                            NormRect(
                                arr.optDouble(0).toFloat(),
                                arr.optDouble(1).toFloat(),
                                arr.optDouble(2).toFloat(),
                                arr.optDouble(3).toFloat()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun write(pins: Map<String, NormRect>): String =
        JSONObject().apply {
            pins.forEach { (id, r) ->
                put(id, JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            }
        }.toString()

    /** Moves one key, keeping its size, and clamping it to stay reachable on screen. */
    fun moved(pins: Map<String, NormRect>, id: String, from: NormRect, dx: Float, dy: Float): Map<String, NormRect> {
        val base = pins[id] ?: from
        val w = base.width
        val h = base.height
        // Half the key may hang off the edge; all of it may not, or it becomes a key
        // that exists but cannot be pressed.
        val left = (base.left + dx).coerceIn(-w / 2f, 1f - w / 2f)
        val top = (base.top + dy).coerceIn(-h / 2f, 1f - h / 2f)
        return pins + (id to NormRect(left, top, left + w, top + h))
    }
}
