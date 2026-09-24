package com.example.core.io

/**
 * Locking the phone's touch and keys while an app keeps running — so a talking app
 * can go in a pocket.
 *
 * What can and cannot be done, because the difference decides the design:
 *
 *  - Touch can be taken: a full-screen window above every app, which swallows it.
 *  - The screen cannot be switched off with the app still in front, but it can be
 *    made black at the lowest brightness and kept awake — on an OLED panel a black
 *    pixel is very nearly off, and it is apps that stop talking when the screen
 *    sleeps that need this at all.
 *  - Volume and most other keys can be taken. **Power cannot**: the system handles it
 *    before anything else sees it, and that is not a gap to work around.
 *  - Gesture navigation may still get through, because the system watches edge
 *    swipes above every window. So if another app comes to the front while locked,
 *    the locked one is brought back.
 *
 * Unlocking is the other half, and has to be something a pocket never does: a
 * sequence of volume keys, or holding the screen with several fingers — both refused
 * while the proximity sensor says the phone is covered.
 */
enum class PocketMode {
    /** Touches are blocked; the screen stays as it is. */
    TOUCH,

    /** Touches are blocked and the screen goes black at the lowest brightness. */
    SCREEN;

    companion object {
        fun parse(raw: String?): PocketMode? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * A sequence of keys pressed in order within a time, like "up, down, up".
 *
 * Presses of other keys, or ones too slow, start the sequence again — but a press
 * that could itself begin the sequence is counted as that beginning, so "up, up,
 * down, up" still unlocks for "up, down, up".
 */
class KeySequence(private val pattern: List<String>, private val windowMs: Long = 3000) {
    private var matched = 0
    private var startedAt = -1L

    val isEmpty: Boolean get() = pattern.isEmpty()

    /** True when this press completes the sequence. */
    fun press(key: String, atMs: Long): Boolean {
        if (pattern.isEmpty()) return false
        if (matched > 0 && atMs - startedAt > windowMs) matched = 0
        if (pattern[matched] == key) {
            if (matched == 0) startedAt = atMs
            matched++
        } else {
            matched = if (pattern[0] == key) 1 else 0
            if (matched == 1) startedAt = atMs
        }
        if (matched == pattern.size) {
            matched = 0
            return true
        }
        return false
    }

    companion object {
        /** "up, down, up" or "+ - +": what a person would write for volume keys. */
        fun parse(raw: String): List<String> = raw.split(',', ' ', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map {
                when (it) {
                    "+", "vol+", "volume_up", "volup" -> "up"
                    "-", "−", "vol-", "volume_down", "voldown" -> "down"
                    else -> it
                }
            }
    }
}

/**
 * Several fingers held still on the screen for a while.
 *
 * Counted from the moment the required number of fingers is down, and reset if they
 * move far or one is lifted — fabric brushing a screen neither holds still nor stays
 * put with two contacts for two seconds.
 */
class HoldGesture(private val fingers: Int, private val holdMs: Long, private val slopPx: Float) {
    private var since = -1L
    private var anchorX = 0f
    private var anchorY = 0f

    /** Feed each touch update; true once the hold is complete. */
    fun update(pointerCount: Int, centroidX: Float, centroidY: Float, atMs: Long): Boolean {
        if (pointerCount < fingers) {
            since = -1L
            return false
        }
        if (since < 0) {
            since = atMs
            anchorX = centroidX
            anchorY = centroidY
            return false
        }
        val dx = centroidX - anchorX
        val dy = centroidY - anchorY
        if (dx * dx + dy * dy > slopPx * slopPx) {
            since = atMs
            anchorX = centroidX
            anchorY = centroidY
            return false
        }
        if (atMs - since >= holdMs) {
            since = -1L
            return true
        }
        return false
    }

    fun reset() {
        since = -1L
    }
}
