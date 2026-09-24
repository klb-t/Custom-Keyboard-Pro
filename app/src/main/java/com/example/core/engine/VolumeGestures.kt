package com.example.core.engine

/**
 * Single, double and held presses of the volume keys, without taking the volume away.
 *
 * The problem with wiring a side key: it already does something people need. So a
 * key is only touched when a wire uses it, and when only its double press or its long
 * press is wired, an ordinary press must still change the volume. That means a press
 * cannot be judged when it lands — a first press might be the start of a double — so
 * it is held back for the double-press window and then either fired as part of a
 * gesture or handed back as a plain volume step ([Out.passVolume]).
 *
 * Pure: time comes in as arguments, and when something has to be checked later the
 * answer says when ([Out.checkAt]); the caller runs a timer and calls [due].
 */
class VolumeGestures(private val doubleMs: Long = 350, private val longMs: Long = 600) {

    /** What to do about one event. */
    data class Out(
        /** The event is ours; the system must not also act on it. */
        val consume: Boolean,
        /** Inputs to fire, in order. */
        val fire: List<String> = emptyList(),
        /** Keys ("up"/"down") to apply as an ordinary volume step. */
        val passVolume: List<String> = emptyList(),
        /** Call [due] at this time, if set. */
        val checkAt: Long? = null
    )

    private class KeyState {
        var down = false
        var downAt = -1L
        var presses = 0
        var lastUpAt = -1L
        var longFired = false
        var pendingSingleAt = -1L
    }

    private val keys = mapOf("up" to KeyState(), "down" to KeyState())

    private fun wiredFor(key: String, wired: Set<String>): Triple<Boolean, Boolean, Boolean> = Triple(
        "volume_$key" in wired, "volume_${key}_double" in wired, "volume_${key}_long" in wired
    )

    fun onDown(key: String, repeat: Boolean, now: Long, wired: Set<String>): Out {
        val s = keys[key] ?: return Out(false)
        val (single, double, long) = wiredFor(key, wired)
        if (!single && !double && !long) return Out(false)
        // Only the single press wired: every press, repeats included, is the input —
        // held, it repeats like an arrow key.
        if (!double && !long) return Out(true, fire = listOf("volume_$key"))
        if (repeat) return Out(true)
        s.down = true
        s.downAt = now
        s.longFired = false
        s.presses = if (s.lastUpAt >= 0 && now - s.lastUpAt <= doubleMs) s.presses + 1 else 1
        s.pendingSingleAt = -1L
        return Out(true, checkAt = if (long) now + longMs else null)
    }

    fun onUp(key: String, now: Long, wired: Set<String>): Out {
        val s = keys[key] ?: return Out(false)
        val (single, double, long) = wiredFor(key, wired)
        if (!single && !double && !long) return Out(false)
        if (!double && !long) return Out(true)
        s.down = false
        s.lastUpAt = now
        if (s.longFired) {
            s.presses = 0
            return Out(true)
        }
        if (double && s.presses >= 2) {
            s.presses = 0
            return Out(true, fire = listOf("volume_${key}_double"))
        }
        if (double) {
            // Might be the first of two: decide once the window has passed.
            s.pendingSingleAt = now + doubleMs
            return Out(true, checkAt = s.pendingSingleAt)
        }
        s.presses = 0
        return single(key, single)
    }

    /** Timers: a long press reached, or a double-press window closed on one press. */
    fun due(now: Long, wired: Set<String>): Out {
        val fire = mutableListOf<String>()
        val pass = mutableListOf<String>()
        keys.forEach { (key, s) ->
            val (single, _, long) = wiredFor(key, wired)
            if (long && s.down && !s.longFired && s.downAt >= 0 && now - s.downAt >= longMs) {
                s.longFired = true
                s.presses = 0
                fire += "volume_${key}_long"
            }
            if (!s.down && s.pendingSingleAt in 0..now) {
                s.pendingSingleAt = -1L
                s.presses = 0
                if (single) fire += "volume_$key" else pass += key
            }
        }
        return Out(true, fire, pass)
    }

    private fun single(key: String, singleWired: Boolean): Out =
        if (singleWired) Out(true, fire = listOf("volume_$key")) else Out(true, passVolume = listOf(key))
}
