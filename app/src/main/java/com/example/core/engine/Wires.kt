package com.example.core.engine

import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject

/** Where an input comes from, which decides what has to be switched on to hear it. */
enum class InputSource { MOTION, PROXIMITY, HARDWARE_KEY, LIFECYCLE }

/** One thing the phone can notice. */
data class InputSpec(
    val id: String,
    val label: String,
    val help: String,
    val source: InputSource
)

/**
 * One wire: when this happens (in these apps), do that.
 *
 * [action] is anything a key can do — a character, a layout switch, a macro, and
 * through `do:` every verb in [com.example.core.io.Verbs] — written the way layouts
 * write it. So the engine adds inputs and nothing else: every output it can reach
 * was already reachable from a key, and every output added later is reachable from
 * every input at once.
 */
data class Wire(
    val on: String,
    val action: String,
    /** Package names this wire is limited to; empty means everywhere. */
    val apps: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    val parsed: KeyAction?
        get() = if (action.trimStart().startsWith("{")) {
            runCatching { LayoutJson.parseAction(JSONObject(action)) }.getOrNull()
        } else LayoutJson.parseAction(action)

    fun appliesIn(pkg: String?): Boolean = apps.isEmpty() || (pkg != null && pkg in apps)
}

/**
 * The inputs half of the engine: every input it knows, and the wires the user drew.
 *
 * Starts where a phone differs from a keyboard — it can be shaken, turned over,
 * tilted, covered, and it has two keys on its side — plus the keyboard's own comings
 * and goings. Each is an id in a catalogue, like the verbs are, so a new input is one
 * entry and a detector, and is immediately wireable to everything.
 */
object Inputs {

    val ALL: List<InputSpec> = listOf(
        InputSpec("shake", "Shake", "A short, firm shake of the phone.", InputSource.MOTION),
        InputSpec("face_down", "Turned face down", "Laid on its screen, and left there a moment.", InputSource.MOTION),
        InputSpec("face_up", "Turned face up", "Picked up again after lying face down.", InputSource.MOTION),
        InputSpec("tilt_left", "Tilted left", "Rolled to the left and held briefly.", InputSource.MOTION),
        InputSpec("tilt_right", "Tilted right", "Rolled to the right and held briefly.", InputSource.MOTION),
        InputSpec("tilt_forward", "Tilted forward", "Top edge dipped away from you and held briefly.", InputSource.MOTION),
        InputSpec("tilt_back", "Tilted back", "Top edge raised towards you and held briefly.", InputSource.MOTION),
        InputSpec("cover", "Sensor covered", "Something close over the top of the screen — a hand wave.", InputSource.PROXIMITY),
        InputSpec("uncover", "Sensor uncovered", "The hand taken away again.", InputSource.PROXIMITY),
        InputSpec(
            "volume_up", "Volume up key",
            "While the keyboard is open. Taken from the volume only when a wire uses it.",
            InputSource.HARDWARE_KEY
        ),
        InputSpec(
            "volume_down", "Volume down key",
            "While the keyboard is open. Taken from the volume only when a wire uses it.",
            InputSource.HARDWARE_KEY
        ),
        InputSpec("keyboard_shown", "Keyboard opened", "Any field, or only the apps the wire names.", InputSource.LIFECYCLE),
        InputSpec("keyboard_hidden", "Keyboard closed", "When it goes away.", InputSource.LIFECYCLE)
    )

    private val index = ALL.associateBy { it.id }

    fun byId(id: String): InputSpec? = index[id.trim().lowercase()]

    /**
     * The wires as written. Unreadable text gives none rather than a guess — a wire
     * that fires something nobody asked for is the failure to avoid.
     */
    fun parse(raw: String): List<Wire> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(LayoutJson.stripCodeFenceArray(raw)) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val on = o.optString("on").trim().lowercase()
            val action = when (val a = o.opt("do")) {
                is String -> a
                is JSONObject -> a.toString()
                else -> ""
            }
            if (on.isEmpty() || action.isBlank()) return@mapNotNull null
            val apps = o.optJSONArray("in")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?: o.optString("in").takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()
            Wire(on, action, apps.filter { it.isNotBlank() }, o.optBoolean("enabled", true))
        }
    }

    fun write(wires: List<Wire>): String = JSONArray().apply {
        wires.forEach { w ->
            put(JSONObject().apply {
                put("on", w.on)
                put("do", w.action)
                if (w.apps.isNotEmpty()) put("in", JSONArray(w.apps))
                if (!w.enabled) put("enabled", false)
            })
        }
    }.toString()

    /** The wires that fire for [input] in [pkg], in the order they were written. */
    fun firing(wires: List<Wire>, input: String, pkg: String?): List<Wire> =
        wires.filter { it.enabled && it.on == input && it.appliesIn(pkg) }

    /** Which sources have to be listened to at all — nothing is switched on for no wire. */
    fun sourcesNeeded(wires: List<Wire>): Set<InputSource> =
        wires.filter { it.enabled }.mapNotNull { byId(it.on)?.source }.toSet()
}
