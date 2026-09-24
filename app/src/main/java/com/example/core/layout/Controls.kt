package com.example.core.layout

import org.json.JSONObject
import kotlin.math.roundToLong

/** What kind of continuous control a piece of a layout is. */
enum class ControlKind {
    /** A track and a thumb: one value along a line. */
    SLIDER,

    /** A dial turned by dragging: one value, compact, with fine control. */
    KNOB,

    /** A pad: two values at once, across and down. */
    XY,

    /** Two states, flipped by a tap: the value is min or max. */
    TOGGLE;

    companion object {
        fun parse(raw: String?): ControlKind =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SLIDER
    }
}

/**
 * A control rather than a key: a slider, a knob, a pad, a switch.
 *
 * A key says "now"; a control says "how much". Its [action] is written the way any
 * key action is, with the value put in where it says `{v}` (and `{x}`, `{y}` for a
 * pad, `{pct}` for a percentage) — so "do:volume_set {v}", "do:set ttsRate {v}" or
 * "do:brightness {v}" make it a volume fader, a reading-speed knob or a brightness
 * slider, and anything that takes a number is one line away. It is also an input of
 * the engine (`control:<element id>`), so wires can listen to it too.
 */
data class ControlDef(
    val kind: ControlKind = ControlKind.SLIDER,
    val min: Double = 0.0,
    val max: Double = 1.0,
    /** Values snap to multiples of this from [min]; 0 is continuous. */
    val step: Double = 0.0,
    /** Where it starts, before the user has moved it. */
    val value: Double = 0.5,
    /** The second value, for [ControlKind.XY]. */
    val valueY: Double = 0.5,
    val action: String = "",
    /** Sliders only: runs bottom to top rather than left to right. */
    val vertical: Boolean = false,
    /** True sends every change while dragging; false only where the finger lifts. */
    val live: Boolean = true,
    val label: String = "",
    /** Decimals shown on the control and put into the action. */
    val decimals: Int = 2
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name.lowercase())
        put("min", min)
        put("max", max)
        if (step != 0.0) put("step", step)
        put("value", value)
        put("valueY", valueY)
        if (action.isNotBlank()) put("do", action)
        if (vertical) put("vertical", true)
        if (!live) put("live", false)
        if (label.isNotBlank()) put("label", label)
        if (decimals != 2) put("decimals", decimals)
    }

    companion object {
        fun fromJson(o: JSONObject): ControlDef {
            val min = o.optDouble("min", 0.0)
            val max = o.optDouble("max", 1.0).let { if (it == min) min + 1.0 else it }
            return ControlDef(
                kind = ControlKind.parse(o.optString("kind")),
                min = min,
                max = max,
                step = o.optDouble("step", 0.0).coerceAtLeast(0.0),
                value = o.optDouble("value", (min + max) / 2),
                valueY = o.optDouble("valueY", (min + max) / 2),
                action = o.optString("do", o.optString("action", "")),
                vertical = o.optBoolean("vertical", false),
                live = o.optBoolean("live", true),
                label = o.optString("label", ""),
                decimals = o.optInt("decimals", 2).coerceIn(0, 6)
            )
        }
    }
}

/** The arithmetic of controls, apart from any drawing so it can be tested. */
object Controls {

    /** Snapped to [ControlDef.step] and kept inside the range, whichever way round it runs. */
    fun quantize(v: Double, def: ControlDef): Double {
        val lo = minOf(def.min, def.max)
        val hi = maxOf(def.min, def.max)
        var x = v.coerceIn(lo, hi)
        if (def.step > 0) x = (def.min + ((x - def.min) / def.step).roundToLong() * def.step).coerceIn(lo, hi)
        return x
    }

    /** From a position along the control (0 at the start, 1 at the end) to a value. */
    fun fromFraction(f: Double, def: ControlDef): Double =
        quantize(def.min + f.coerceIn(0.0, 1.0) * (def.max - def.min), def)

    /** Where a value sits along the control, 0 to 1. */
    fun fraction(v: Double, def: ControlDef): Double =
        if (def.max == def.min) 0.0 else ((v - def.min) / (def.max - def.min)).coerceIn(0.0, 1.0)

    /**
     * A knob turned by dragging: up and right turn it up. [travel] is how far a drag
     * must go for the full range, so fine adjustment needs no fine fingers.
     */
    fun knobAfterDrag(v: Double, dx: Float, dy: Float, travel: Float, def: ControlDef): Double {
        val delta = (dx - dy) / travel.coerceAtLeast(1f)
        return fromFraction(fraction(v, def) + delta, def)
    }

    fun format(v: Double, def: ControlDef): String =
        if (def.decimals == 0) v.roundToLong().toString() else "%.${def.decimals}f".format(java.util.Locale.ROOT, v)

    /** The action with the value put in: {v}, {x}, {y}, {pct}, and {on} (on/off, for switches). */
    fun fill(template: String, v: Double, def: ControlDef, y: Double? = null): String {
        val pct = (fraction(v, def) * 100).roundToLong().toString()
        return template
            .replace("{v}", format(v, def))
            .replace("{x}", format(v, def))
            .replace("{y}", format(y ?: v, def))
            .replace("{pct}", pct)
            .replace("{on}", if (v >= (def.min + def.max) / 2) "on" else "off")
    }

    /**
     * Whether a new value is worth acting on: a real change, not one produced by the
     * finger trembling within a step. Continuous controls act on hundredths.
     */
    fun changed(old: Double, new: Double, def: ControlDef): Boolean {
        val grain = if (def.step > 0) def.step / 2 else (def.max - def.min).let { kotlin.math.abs(it) } / 100.0
        return kotlin.math.abs(new - old) >= grain
    }

    /** Stored per layout and element, like where pieces were moved. */
    fun parseValues(raw: String): Map<String, Pair<Double, Double>> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { k ->
                    val a = o.optJSONArray(k) ?: return@forEach
                    put(k, a.optDouble(0) to a.optDouble(1, a.optDouble(0)))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun writeValues(values: Map<String, Pair<Double, Double>>): String = JSONObject().apply {
        values.forEach { (k, v) -> put(k, org.json.JSONArray(listOf(v.first, v.second))) }
    }.toString()
}
