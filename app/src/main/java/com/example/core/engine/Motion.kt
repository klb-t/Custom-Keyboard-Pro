package com.example.core.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turning raw acceleration into the few things a person does on purpose.
 *
 * The difficulty is never detecting a shake; it is not detecting one while the phone
 * is carried, dropped on a sofa or typed on in a bus. So every gesture here needs
 * both size and persistence — several strong jolts close together, a tilt held
 * rather than passed through — and each has a refractory period, so one gesture is
 * one event. Pure: readings in, events out, time passed in rather than read.
 */
class MotionGestures(
    private val shakeThreshold: Float = 13f,
    private val shakeJolts: Int = 3,
    private val shakeWindowMs: Long = 700,
    private val tiltDegrees: Float = 35f,
    private val holdMs: Long = 350,
    private val refractoryMs: Long = 1200
) {
    private val jolts = ArrayDeque<Long>()
    private val lastFired = mutableMapOf<String, Long>()
    private var faceDown = false
    private var faceDownSince = -1L
    private var tilt: String? = null
    private var tiltSince = -1L
    private var pitchBaseline: Float? = null

    /**
     * One reading of the accelerometer, gravity included, in m/s² — [x] to the right
     * of the screen, [y] to its top, [z] out of it. Returns the gesture it completed,
     * or null.
     */
    fun reading(x: Float, y: Float, z: Float, atMs: Long): String? {
        // Shake: the size of the jolt beyond gravity, counted when it is well above it.
        val magnitude = sqrt(x * x + y * y + z * z)
        if (abs(magnitude - GRAVITY) > shakeThreshold - GRAVITY + 3f) {
            if (jolts.isEmpty() || atMs - jolts.last() > 80) jolts.addLast(atMs)
        }
        while (jolts.isNotEmpty() && atMs - jolts.first() > shakeWindowMs) jolts.removeFirst()
        if (jolts.size >= shakeJolts) {
            jolts.clear()
            return fire("shake", atMs)
        }

        // Only a phone roughly at rest says anything about how it is held.
        if (abs(magnitude - GRAVITY) > 2.5f) return null

        // Face down and face up: the screen pointing at the floor, for a moment.
        val down = z < -GRAVITY * 0.8f
        if (down && !faceDown) {
            if (faceDownSince < 0) faceDownSince = atMs
            if (atMs - faceDownSince >= holdMs * 2) {
                faceDown = true
                return fire("face_down", atMs)
            }
        } else if (!down) {
            faceDownSince = -1L
            if (faceDown && z > GRAVITY * 0.3f) {
                faceDown = false
                return fire("face_up", atMs)
            }
        }
        // Screen towards the floor: about to be face down, or already. How it is
        // tilted says nothing then — every angle from there reads as "tilted back".
        if (faceDown || down) return null

        // Tilts. Sideways is absolute — people hold a phone level across — measured as
        // gravity's angle out of the screen's up-and-out plane, which stays sensible
        // however far the phone is leaned back. Forward and back are relative to how
        // this person happens to hold it, learned slowly while nothing is happening:
        // one reads in bed, another at a desk, and neither should be "tilted".
        val roll = Math.toDegrees(kotlin.math.atan2(x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
        val pitch = Math.toDegrees(kotlin.math.atan2(y.toDouble(), z.toDouble())).toFloat()
        val base = pitchBaseline ?: pitch.also { pitchBaseline = it }
        val lean = pitch - base
        val now = when {
            roll > tiltDegrees -> "tilt_left"
            roll < -tiltDegrees -> "tilt_right"
            lean > tiltDegrees -> "tilt_back"
            lean < -tiltDegrees -> "tilt_forward"
            else -> null
        }
        if (now == null) pitchBaseline = base + (pitch - base) * 0.02f
        if (now != tilt) {
            tilt = now
            tiltSince = atMs
            return null
        }
        if (now != null && tiltSince >= 0 && atMs - tiltSince >= holdMs) {
            tiltSince = -1L // once per hold; returning to level re-arms it
            return fire(now, atMs)
        }
        return null
    }

    private fun fire(id: String, atMs: Long): String? {
        val last = lastFired[id]
        if (last != null && atMs - last < refractoryMs) return null
        lastFired[id] = atMs
        return id
    }

    companion object {
        const val GRAVITY = 9.81f
    }
}

/**
 * Covering and uncovering the proximity sensor — a hand wave over the top of the
 * phone. Most sensors report only near or far; the edge between them is the event.
 */
class ProximityGesture(private val refractoryMs: Long = 600) {
    private var near: Boolean? = null
    private var lastAt = -1L

    fun reading(distance: Float, maxRange: Float, atMs: Long): String? {
        val isNear = distance < maxRange && distance < 3f
        val was = near
        near = isNear
        if (was == null || was == isNear) return null
        if (lastAt >= 0 && atMs - lastAt < refractoryMs) return null
        lastAt = atMs
        return if (isNear) "cover" else "uncover"
    }
}
