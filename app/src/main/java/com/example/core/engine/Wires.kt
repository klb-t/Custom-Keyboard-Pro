package com.example.core.engine

import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject

/** Where an input comes from, which decides what has to be switched on to hear it. */
enum class InputSource {
    MOTION, PROXIMITY, HARDWARE_KEY, LIFECYCLE,

    /** System broadcasts: screen, power, headset. Cheap; registered only when wired. */
    SYSTEM,

    /** Which app is in front; needs the accessibility service. */
    APPS,

    /** A spoken phrase; needs the microphone, and listens only while a wire needs it. */
    VOICE
}

/** One thing the phone can notice. */
data class InputSpec(
    val id: String,
    val label: String,
    val help: String,
    val source: InputSource
)

/**
 * When a wire is live. Scope is part of the wire rather than of the input, because
 * the same shake can mean "torch" with the phone in hand and nothing at all while
 * typing.
 */
enum class WireScope {
    /** Whenever the engine can hear it: keyboard open, or accessibility running. */
    ANY,

    /** Only while the keyboard is open. */
    KEYBOARD,

    /** Only while the keyboard is closed. */
    CLOSED,

    /** Only while the pocket lock is up — how a spoken password unlocks it. */
    LOCKED,

    /** Only while the pocket lock is not up. */
    UNLOCKED;

    companion object {
        fun parse(raw: String?): WireScope =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

/** What the engine knows about the moment, which decides which wires are live. */
data class EngineState(
    val keyboardOpen: Boolean = false,
    val locked: Boolean = false,
    val pkg: String? = null
)

/**
 * One wire: when this happens (in these apps, at these times), do that.
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
    val enabled: Boolean = true,
    val scope: WireScope = WireScope.ANY,
    /** Packages this wire never fires in. */
    val notIn: List<String> = emptyList(),
    /** For "voice": what has to be said. */
    val phrase: String? = null
) {
    val parsed: KeyAction?
        get() = if (action.trimStart().startsWith("{")) {
            runCatching { LayoutJson.parseAction(JSONObject(action)) }.getOrNull()
        } else LayoutJson.parseAction(action)

    fun appliesIn(pkg: String?): Boolean =
        (apps.isEmpty() || (pkg != null && pkg in apps)) && (pkg == null || pkg !in notIn)

    fun liveIn(state: EngineState): Boolean = enabled && when (scope) {
        WireScope.ANY -> true
        WireScope.KEYBOARD -> state.keyboardOpen
        WireScope.CLOSED -> !state.keyboardOpen
        WireScope.LOCKED -> state.locked
        WireScope.UNLOCKED -> !state.locked
    }
}

/**
 * The inputs half of the engine: every input it knows, and the wires the user drew.
 *
 * Starts where a phone differs from a keyboard — it can be shaken, turned over,
 * tilted, covered, spoken to, and it has two keys on its side — plus what the system
 * announces anyway. Each is an id in a catalogue, like the verbs are, so a new input
 * is one entry and a detector, and is immediately wireable to everything.
 */
object Inputs {

    private fun i(id: String, label: String, help: String, source: InputSource) = InputSpec(id, label, help, source)

    private const val KEY_NOTE = " Taken from the volume only when wired; with the keyboard closed it needs accessibility."

    val ALL: List<InputSpec> = listOf(
        i("shake", "Shake", "A short, firm shake of the phone.", InputSource.MOTION),
        i("face_down", "Turned face down", "Laid on its screen, and left there a moment.", InputSource.MOTION),
        i("face_up", "Turned face up", "Picked up again after lying face down.", InputSource.MOTION),
        i("tilt_left", "Tilted left", "Rolled to the left and held briefly.", InputSource.MOTION),
        i("tilt_right", "Tilted right", "Rolled to the right and held briefly.", InputSource.MOTION),
        i("tilt_forward", "Tilted forward", "Top edge dipped away from you and held briefly.", InputSource.MOTION),
        i("tilt_back", "Tilted back", "Top edge raised towards you and held briefly.", InputSource.MOTION),
        i("cover", "Sensor covered", "Something close over the top of the screen — a hand wave.", InputSource.PROXIMITY),
        i("uncover", "Sensor uncovered", "The hand taken away again.", InputSource.PROXIMITY),
        i("in_pocket", "Put in a pocket", "Covered and staying covered.", InputSource.PROXIMITY),
        i("out_of_pocket", "Taken out of a pocket", "Uncovered after having been in one.", InputSource.PROXIMITY),
        i("volume_up", "Volume up key", "Each press.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("volume_down", "Volume down key", "Each press.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("volume_up_double", "Volume up, twice", "Two quick presses; a single press still changes the volume.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("volume_down_double", "Volume down, twice", "Two quick presses; a single press still changes the volume.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("volume_up_long", "Volume up, held", "Held down; a short press still changes the volume.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("volume_down_long", "Volume down, held", "Held down; a short press still changes the volume.$KEY_NOTE", InputSource.HARDWARE_KEY),
        i("voice", "A spoken phrase", "The wire's \"phrase\", said aloud. Listens only while such a wire is live — " +
            "best limited to the pocket lock with \"when\": \"locked\".", InputSource.VOICE),
        i("screen_on", "Screen turned on", "Any way it happens.", InputSource.SYSTEM),
        i("screen_off", "Screen turned off", "Any way it happens.", InputSource.SYSTEM),
        i("power_connected", "Charger connected", "Cable or wireless.", InputSource.SYSTEM),
        i("power_disconnected", "Charger disconnected", "", InputSource.SYSTEM),
        i("headset_in", "Headphones connected", "Wired headphones; Bluetooth ones announce themselves differently.", InputSource.SYSTEM),
        i("headset_out", "Headphones disconnected", "", InputSource.SYSTEM),
        i("app_opened", "An app came to the front", "Name the apps with \"in\". Needs accessibility.", InputSource.APPS),
        i("pocket_locked", "Pocket lock went up", "However it was started.", InputSource.LIFECYCLE),
        i("pocket_unlocked", "Pocket lock came down", "However it was unlocked.", InputSource.LIFECYCLE),
        i("keyboard_shown", "Keyboard opened", "Any field, or only the apps the wire names.", InputSource.LIFECYCLE),
        i("keyboard_hidden", "Keyboard closed", "When it goes away.", InputSource.LIFECYCLE)
    )

    private val index = ALL.associateBy { it.id }

    fun byId(id: String): InputSpec? = index[id.trim().lowercase()]

    private fun strings(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: o.optString(key).takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()

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
            Wire(
                on = on,
                action = action,
                apps = strings(o, "in").filter { it.isNotBlank() },
                enabled = o.optBoolean("enabled", true),
                scope = WireScope.parse(o.optString("when")),
                notIn = strings(o, "not_in").filter { it.isNotBlank() },
                phrase = o.optString("phrase").takeIf { it.isNotBlank() }
            )
        }
    }

    fun write(wires: List<Wire>): String = JSONArray().apply {
        wires.forEach { w ->
            put(JSONObject().apply {
                put("on", w.on)
                put("do", w.action)
                if (w.apps.isNotEmpty()) put("in", JSONArray(w.apps))
                if (!w.enabled) put("enabled", false)
                if (w.scope != WireScope.ANY) put("when", w.scope.name.lowercase())
                if (w.notIn.isNotEmpty()) put("not_in", JSONArray(w.notIn))
                w.phrase?.let { put("phrase", it) }
            })
        }
    }.toString()

    /** The wires that fire for [input] now, in the order they were written. */
    fun firing(wires: List<Wire>, input: String, state: EngineState): List<Wire> =
        wires.filter { it.on == input && it.liveIn(state) && it.appliesIn(state.pkg) }

    /** Kept for callers that only know the app: everything else treated as "any". */
    fun firing(wires: List<Wire>, input: String, pkg: String?): List<Wire> =
        firing(wires, input, EngineState(keyboardOpen = true, pkg = pkg))

    /** Which sources have to be listened to now — nothing is switched on for no wire. */
    fun sourcesNeeded(wires: List<Wire>, state: EngineState): Set<InputSource> =
        wires.filter { it.liveIn(state) && (it.appliesIn(state.pkg) || byId(it.on)?.source == InputSource.APPS) }
            .mapNotNull { byId(it.on)?.source }.toSet()

    fun sourcesNeeded(wires: List<Wire>): Set<InputSource> =
        wires.filter { it.enabled }.mapNotNull { byId(it.on)?.source }.toSet()

    /** The live wires on the volume keys, which decide whether a press is ours at all. */
    fun volumeInputsLive(wires: List<Wire>, state: EngineState): Set<String> =
        wires.filter { it.liveIn(state) && it.appliesIn(state.pkg) && it.on.startsWith("volume_") }
            .map { it.on }.toSet()
}
