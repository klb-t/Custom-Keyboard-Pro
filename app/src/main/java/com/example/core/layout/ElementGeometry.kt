package com.example.core.layout

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a floating or free piece of a layout actually lands, in pixels.
 *
 * The first version read an element's [NormRect] as fractions of whatever the input
 * view happened to be, which is right in exactly one orientation. Turn the phone and
 * a block drawn 40% wide and 30% tall becomes a wide flat strip, a corner Escape
 * becomes a bar, and a numeric block that sat politely above the keyboard lands on
 * top of it — because in landscape the keyboard is most of the screen.
 *
 * So the two halves of a rectangle are read differently, because they mean different
 * things:
 *
 *  - **Size** is physical. Bounds are authored against a portrait screen, so width is
 *    a fraction of the short side and height of the long side, in either orientation.
 *    A key stays the size of a fingertip when the phone turns.
 *  - **Position** is relational. What the author meant by "left 0.02" is "against the
 *    left edge", and by "0.58 … 0.99" is "over on the right". Both are kept as the
 *    share of free space on each side, so a corner stays a corner and a centred block
 *    stays centred, whatever the screen's proportions.
 *
 * Then the docked panel is avoided unless the element says otherwise: a piece that
 * does not fit above it is scaled down, keeping its shape, rather than laid over the
 * keys it was meant to sit beside.
 */
object ElementGeometry {

    /** A resolved rectangle, in the same units as the area it was resolved in. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Where the user moved or resized an element to, stored apart from the layout.
     *
     * [fx] and [fy] are the share of free space to the left of and above the element,
     * which is what survives rotation; [scale] multiplies the authored size.
     */
    data class Pose(val fx: Float, val fy: Float, val scale: Float = 1f)

    /** The smallest a piece is ever squeezed to before it is allowed to overlap. */
    const val MIN_FIT = 0.35f
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 4f

    /** The position an authored rectangle implies, as shares of free space. */
    fun poseOf(bounds: NormRect): Pose = Pose(
        fx = share(bounds.left, 1f - bounds.right),
        fy = share(bounds.top, 1f - bounds.bottom)
    )

    private fun share(before: Float, after: Float): Float {
        val b = before.coerceAtLeast(0f)
        val a = after.coerceAtLeast(0f)
        return if (b + a <= 0f) 0.5f else b / (b + a)
    }

    /**
     * Resolves [bounds] (optionally moved by [pose]) inside an area of [areaWidth] by
     * [areaHeight].
     *
     * [ceiling] is how far down the element may reach — the top of the docked panel —
     * or null when it may go anywhere. [shortSide] and [longSide] are the screen's,
     * for the physical size; they default to the area's own.
     */
    fun resolve(
        bounds: NormRect,
        pose: Pose?,
        areaWidth: Float,
        areaHeight: Float,
        ceiling: Float? = null,
        shortSide: Float = minOf(areaWidth, areaHeight),
        longSide: Float = maxOf(areaWidth, areaHeight)
    ): Box {
        val scale = (pose?.scale ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        var w = bounds.width.coerceAtLeast(0.02f) * shortSide * scale
        var h = bounds.height.coerceAtLeast(0.02f) * longSide * scale

        // Too big for where it has to go: shrink, keeping its shape. Down to a point —
        // past that a numeric block of unpressable keys is worse than one that
        // overlaps, so the floor is where overlap is accepted instead.
        val room = (ceiling ?: areaHeight).coerceAtLeast(0f)
        val fit = minOf(1f, areaWidth / w, if (h > 0f) room / h else 1f)
        val applied = fit.coerceAtLeast(MIN_FIT)
        w *= applied
        h *= applied
        w = w.coerceAtMost(areaWidth)
        h = h.coerceAtMost(areaHeight)

        val at = pose ?: poseOf(bounds)
        val left = (at.fx.coerceIn(0f, 1f) * (areaWidth - w)).coerceIn(0f, (areaWidth - w).coerceAtLeast(0f))
        var top = at.fy.coerceIn(0f, 1f) * (areaHeight - h)
        if (ceiling != null) top = top.coerceAtMost(ceiling - h)
        top = top.coerceIn(0f, (areaHeight - h).coerceAtLeast(0f))
        return Box(left, top, left + w, top + h)
    }

    /**
     * The pose that puts [current] at its position after a drag of [dx], [dy].
     *
     * Clamped to the same [ceiling] [resolve] obeys: otherwise dragging past the
     * docked panel would keep storing "further down" while the piece stayed put, and
     * the way back up would start with a stretch of dragging that moves nothing.
     */
    fun dragged(
        current: Box,
        dx: Float,
        dy: Float,
        areaWidth: Float,
        areaHeight: Float,
        scale: Float,
        ceiling: Float? = null
    ): Pose {
        val freeX = areaWidth - current.width
        val freeY = areaHeight - current.height
        val lowest = if (ceiling != null) minOf(freeY, ceiling - current.height) else freeY
        val x = (current.left + dx).coerceIn(0f, freeX.coerceAtLeast(0f))
        val y = (current.top + dy).coerceIn(0f, lowest.coerceAtLeast(0f))
        return Pose(
            fx = if (freeX > 0f) x / freeX else 0.5f,
            fy = if (freeY > 0f) y / freeY else 0.5f,
            scale = scale
        )
    }

    /** Resizing by dragging a corner: the width the finger asks for decides the scale. */
    fun resized(pose: Pose, current: Box, dx: Float): Pose {
        if (current.width <= 0f) return pose
        val factor = ((current.width + dx) / current.width).coerceAtLeast(0.05f)
        return pose.copy(scale = (pose.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE))
    }

    /** Stored per layout and element, since the same id can mean different pieces. */
    fun key(layoutId: String, elementId: String) = "$layoutId/$elementId"

    fun parse(raw: String): Map<String, Pose> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(LayoutJson.stripCodeFence(raw))
            buildMap {
                root.keys().forEach { id ->
                    val arr = root.optJSONArray(id) ?: return@forEach
                    if (arr.length() >= 2) {
                        put(
                            id,
                            Pose(
                                fx = arr.optDouble(0, 0.5).toFloat(),
                                fy = arr.optDouble(1, 0.5).toFloat(),
                                scale = arr.optDouble(2, 1.0).toFloat()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun write(poses: Map<String, Pose>): String =
        JSONObject().apply {
            poses.forEach { (id, p) -> put(id, JSONArray(listOf(p.fx, p.fy, p.scale))) }
        }.toString()
}
