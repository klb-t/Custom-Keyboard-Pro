package com.example.core.hitmap

import android.content.Context
import com.example.core.layout.PlacedKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns where a user's fingers actually land.
 *
 * People do not press the centre of a key. They press consistently low, or left, or
 * both, and the bias differs per key with how the hand is held. This watches for the
 * one unambiguous signal that a press went to the wrong key — type, delete, retype
 * something else in roughly the same spot — and nudges that key's effective centre
 * towards where the finger really was.
 *
 * It only affects anything while the probabilistic touch model is on, and only learns
 * while learning is on; both are off by default. The step is small (15% of the error)
 * and clamped to a fraction of the key, so a run of unrelated backspaces cannot drag
 * a key somewhere absurd.
 *
 * Offsets are stored as fractions of the key's own size, so they survive the keyboard
 * being resized or rotated rather than being silently wrong afterwards.
 */
class TouchLearner(private val context: Context?) {

    private data class Tap(val keyId: String, val x: Float, val y: Float, val atMillis: Long)

    /** Per key, as a fraction of that key's width and height. */
    private val offsets = HashMap<String, Pair<Float, Float>>()

    private var lastTap: Tap? = null
    private var pendingCorrection: Tap? = null
    private var layoutId: String = ""

    /** Offsets scaled to pixels for the placement currently on screen. */
    fun offsetsFor(placement: List<PlacedKey>): Map<String, Pair<Float, Float>> {
        if (offsets.isEmpty()) return emptyMap()
        val out = HashMap<String, Pair<Float, Float>>(offsets.size)
        placement.forEach { placed ->
            val fraction = offsets[placed.key.id] ?: return@forEach
            out[placed.key.id] = Pair(fraction.first * placed.width, fraction.second * placed.height)
        }
        return out
    }

    fun onTap(placed: PlacedKey, x: Float, y: Float, learn: Boolean) {
        val now = System.currentTimeMillis()
        val correction = pendingCorrection
        if (learn && correction != null &&
            now - correction.atMillis < CORRECTION_WINDOW_MS &&
            correction.keyId != placed.key.id
        ) {
            // The earlier press was meant for this key: move this key towards it.
            nudge(placed, correction.x, correction.y)
        }
        pendingCorrection = null
        lastTap = Tap(placed.key.id, x, y, now)
    }

    /** Called on backspace: a tap it closely follows becomes a suspect. */
    fun onBackspace() {
        val tap = lastTap ?: return
        if (System.currentTimeMillis() - tap.atMillis < CORRECTION_WINDOW_MS) pendingCorrection = tap
        lastTap = null
    }

    private fun nudge(placed: PlacedKey, towardsX: Float, towardsY: Float) {
        if (placed.width <= 0f || placed.height <= 0f) return
        val targetX = (towardsX - placed.centerX) / placed.width
        val targetY = (towardsY - placed.centerY) / placed.height
        val current = offsets[placed.key.id] ?: (0f to 0f)
        offsets[placed.key.id] = Pair(
            (current.first + RATE * (targetX - current.first)).coerceIn(-MAX_FRACTION, MAX_FRACTION),
            (current.second + RATE * (targetY - current.second)).coerceIn(-MAX_FRACTION, MAX_FRACTION)
        )
        persist()
    }

    fun load(id: String) {
        if (layoutId == id) return
        layoutId = id
        offsets.clear()
        val raw = prefs()?.getString(id, null) ?: return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val pair = json.optJSONArray(key) ?: return@forEach
                offsets[key] = Pair(pair.optDouble(0).toFloat(), pair.optDouble(1).toFloat())
            }
        } catch (e: Exception) {
            offsets.clear()
        }
    }

    fun forget() {
        offsets.clear()
        lastTap = null
        pendingCorrection = null
        persist()
    }

    fun learnedKeyCount(): Int = offsets.size

    private fun persist() {
        val store = prefs() ?: return
        if (layoutId.isEmpty()) return
        val json = JSONObject()
        offsets.forEach { (key, offset) ->
            json.put(key, JSONArray().put(offset.first.toDouble()).put(offset.second.toDouble()))
        }
        store.edit().putString(layoutId, json.toString()).apply()
    }

    private fun prefs() = context?.getSharedPreferences("touch_model", Context.MODE_PRIVATE)

    companion object {
        /** Clears what has been learned for every layout. */
        fun forgetAll(context: Context) {
            context.getSharedPreferences("touch_model", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        private const val CORRECTION_WINDOW_MS = 2500L
        private const val RATE = 0.15f
        private const val MAX_FRACTION = 0.45f
    }
}
