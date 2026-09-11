package com.example.core.layout

import kotlin.math.exp
import kotlin.math.hypot

/** A key with the pixel rectangle it occupies on the key surface. */
data class PlacedKey(
    val key: KeyDef,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Distance from (x, y) to the nearest point of this key; 0 when inside. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return hypot(dx, dy)
    }
}

/**
 * Turns a layer into rectangles, and turns a touch back into a key.
 *
 * Geometry is computed here rather than read back from the view tree so there is one
 * authority for where a key is: the renderer draws from it, the hit test reads it, and
 * long-press popups position against it. The keyboard this replaced hit-tested with a
 * Gaussian over key centres *after* Compose had already dispatched the click to a key,
 * so a press could report one key and type another.
 */
object KeyPlacement {

    fun place(layer: LayerDef, width: Float, height: Float): List<PlacedKey> {
        if (width <= 0f || height <= 0f) return emptyList()
        val out = mutableListOf<PlacedKey>()

        val totalRowWeight = layer.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        if (totalRowWeight > 0f) {
            var y = 0f
            layer.rows.forEach { row ->
                val rowHeight = height * (row.heightWeight / totalRowWeight)
                val totalKeyWeight = row.padStart + row.padEnd +
                    row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                if (totalKeyWeight > 0f) {
                    val unit = width / totalKeyWeight
                    var x = row.padStart * unit
                    row.keys.forEach { key ->
                        val keyWidth = key.widthWeight * unit
                        out += PlacedKey(key, x, y, x + keyWidth, y + rowHeight)
                        x += keyWidth
                    }
                }
                y += rowHeight
            }
        }

        layer.freeKeys.forEach { key ->
            val b = key.bounds ?: return@forEach
            out += PlacedKey(key, b.left * width, b.top * height, b.right * width, b.bottom * height)
        }

        return out
    }

    /** Plain rectangle hit test, with a fallback to the nearest key for edge touches. */
    fun hitTest(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? {
        keys.firstOrNull { it.contains(x, y) }?.let { return it }
        return keys.minByOrNull { it.distanceTo(x, y) }?.takeIf { it.distanceTo(x, y) < it.height }
    }

    /**
     * Hit test that weighs neighbouring keys by distance instead of using a hard edge.
     *
     * Off by default. It genuinely helps people who consistently land low-left of the
     * key they meant, and [learnedOffsets] lets it adapt to that; but it can also turn
     * a deliberate press near a boundary into the wrong letter, which is worse than a
     * miss the user can see coming. So the user opts in.
     *
     * [sigma] is the standard deviation in pixels: larger means more willing to
     * override the rectangle the finger actually landed in.
     */
    fun probableKey(
        keys: List<PlacedKey>,
        x: Float,
        y: Float,
        sigma: Float,
        learnedOffsets: Map<String, Pair<Float, Float>> = emptyMap()
    ): PlacedKey? {
        if (keys.isEmpty()) return null
        if (sigma <= 0f) return hitTest(keys, x, y)

        val variance = 2f * sigma * sigma
        var best: PlacedKey? = null
        var bestScore = 0f

        keys.forEach { placed ->
            val offset = learnedOffsets[placed.key.id]
            val cx = placed.centerX + (offset?.first ?: 0f)
            val cy = placed.centerY + (offset?.second ?: 0f)

            // Distance is measured to the key's rectangle, not its centre, so a wide
            // space bar is not penalised for being wide.
            val dx = (x - cx).let { d ->
                val half = placed.width / 2f
                if (kotlin.math.abs(d) <= half) 0f else kotlin.math.abs(d) - half
            }
            val dy = (y - cy).let { d ->
                val half = placed.height / 2f
                if (kotlin.math.abs(d) <= half) 0f else kotlin.math.abs(d) - half
            }

            val score = exp(-((dx * dx + dy * dy) / variance).toDouble()).toFloat() *
                placed.key.touchWeight
            if (score > bestScore) {
                bestScore = score
                best = placed
            }
        }
        return best ?: hitTest(keys, x, y)
    }
}
