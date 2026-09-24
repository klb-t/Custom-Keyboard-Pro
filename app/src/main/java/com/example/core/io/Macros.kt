package com.example.core.io

import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Showing the keyboard something once and having it repeat it.
 *
 * Everything reachable from a key is already an action, so a macro is just a list of
 * them — recorded as they are performed rather than written by hand. The one thing a
 * recording has to add is time: typing can be replayed as fast as it likes, but an
 * app that was tapped needs a moment before the next tap lands on what it opened. So
 * a pause is kept only around steps that act on other apps, where it matters, and
 * capped, so a coffee break in the middle of recording is not replayed.
 */
class MacroRecorder(val name: String) {
    private val steps = mutableListOf<KeyAction>()
    private var lastAt = -1L
    private var lastWasOutside = false

    val size: Int get() = steps.size

    fun record(action: KeyAction, atMs: Long) {
        if (isControl(action)) return
        val outside = action is KeyAction.Do
        if (lastAt >= 0 && (outside || lastWasOutside)) {
            val gap = atMs - lastAt
            if (gap >= MIN_KEPT_PAUSE) {
                steps += KeyAction.Do(Command("wait", listOf(gap.coerceAtMost(MAX_KEPT_PAUSE).toString())))
            }
        }
        steps += action
        lastAt = atMs
        lastWasOutside = outside
    }

    fun steps(): List<KeyAction> = steps.toList()

    companion object {
        const val MIN_KEPT_PAUSE = 250L
        const val MAX_KEPT_PAUSE = 3000L

        /** Recording and playing are about the macro, not part of it. */
        fun isControl(action: KeyAction): Boolean =
            action is KeyAction.Do && (action.command.verb == "record" || action.command.verb == "play")
    }
}

object Macros {

    fun parse(raw: String): Map<String, List<KeyAction>> {
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { JSONObject(LayoutJson.stripCodeFence(raw)) }.getOrNull() ?: return emptyMap()
        return buildMap {
            root.keys().forEach { name ->
                val arr = root.optJSONArray(name) ?: return@forEach
                put(name, (0 until arr.length()).mapNotNull { LayoutJson.parseAction(arr.opt(it)) })
            }
        }
    }

    fun write(macros: Map<String, List<KeyAction>>): String = JSONObject().apply {
        macros.forEach { (name, steps) ->
            put(name, JSONArray().apply { steps.forEach { put(LayoutJson.writeAction(it)) } })
        }
    }.toString()

    /** How long a step asks the player to wait, or null when it is not a wait. */
    fun waitOf(action: KeyAction): Long? =
        (action as? KeyAction.Do)?.command?.takeIf { it.verb == "wait" }?.int("ms")?.toLong()?.coerceIn(0, 60_000)
}
