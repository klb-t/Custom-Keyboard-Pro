package com.example.core.layout

import kotlin.math.abs
import kotlin.math.round

enum class KeyAlignment(val title: String) {
    LEFT("Left"), RIGHT("Right"), TOP("Top"), BOTTOM("Bottom"), CENTER_X("Horizontal centres"), CENTER_Y("Vertical centres")
}
enum class KeySpacing(val title: String) { HORIZONTAL("Horizontal spacing"), VERTICAL("Vertical spacing") }

/** Geometry operations retain the complete key instance and only edit its position/order. */
object LayoutGeometry {
    const val DEFAULT_SNAP = 0.01f

    fun rectangles(layer: LayerDef): Map<String, NormRect> {
        require(layer.allKeys.map { it.id }.distinct().size == layer.allKeys.size) { "Key ids in this layer must be unique" }
        return KeyPlacement.place(layer, 1f, 1f).associate { it.key.id to NormRect(it.left, it.top, it.right, it.bottom) }
    }

    /** A deliberate conversion freezes the current row geometry, including its weights/padding. */
    fun toFree(layer: LayerDef): LayerDef {
        val geometry = rectangles(layer)
        return layer.copy(rows = emptyList(), freeKeys = layer.allKeys.map { key ->
            key.copy(bounds = bounded(geometry[key.id] ?: key.bounds ?: NormRect(0f, 0f, 0.1f, 0.1f)))
        })
    }

    /** Drop a row key before/after the target centre; the dragged key is excluded from the index. */
    fun dropInRows(layer: LayerDef, keyId: String, x: Float, y: Float): LayerDef {
        require(x.isFinite() && y.isFinite()) { "Invalid drop point" }
        val source = layer.rows.flatMap { it.keys }.singleOrNull { it.id == keyId }
            ?: error("Choose one row key to move")
        val geometry = rectangles(layer)
        val total = layer.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        require(total > 0f) { "Rows need positive height" }
        var top = 0f
        val targetRow = layer.rows.indexOfFirst { row ->
            top += row.heightWeight / total
            y.coerceIn(0f, 0.999999f) < top
        }.coerceAtLeast(0)
        val stripped = layer.rows.map { row -> row.copy(keys = row.keys.filterNot { it.id == keyId }) }.toMutableList()
        val target = stripped[targetRow]
        val index = target.keys.count { key -> (geometry[key.id]?.centerX ?: 0f) < x.coerceIn(0f, 1f) }
        stripped[targetRow] = target.copy(keys = target.keys.toMutableList().also { it.add(index, source) })
        return layer.copy(rows = stripped)
    }

    /** Snap the group anchor, then clamp the group as a whole so distances are preserved. */
    fun moveFree(layer: LayerDef, selected: Set<String>, dx: Float, dy: Float, snap: Float = DEFAULT_SNAP): LayerDef {
        require(dx.isFinite() && dy.isFinite() && snap.isFinite() && snap >= 0f) { "Invalid movement" }
        val keys = selectedFree(layer, selected)
        if (keys.isEmpty()) return layer
        val union = union(keys.map { it.bounds!! })
        val desiredX = snapped(union.left + dx, snap) - union.left
        val desiredY = snapped(union.top + dy, snap) - union.top
        val shiftX = desiredX.coerceIn(-union.left, 1f - union.right)
        val shiftY = desiredY.coerceIn(-union.top, 1f - union.bottom)
        return update(layer, selected) { b -> NormRect(b.left + shiftX, b.top + shiftY, b.right + shiftX, b.bottom + shiftY) }
    }

    fun align(layer: LayerDef, selected: Set<String>, alignment: KeyAlignment): LayerDef {
        val keys = selectedFree(layer, selected)
        require(keys.size >= 2) { "Select at least two freely positioned keys" }
        val target = union(keys.map { it.bounds!! })
        return update(layer, selected) { b ->
            val x = when (alignment) {
                KeyAlignment.LEFT -> target.left
                KeyAlignment.RIGHT -> target.right - b.width
                KeyAlignment.CENTER_X -> target.centerX - b.width / 2f
                else -> b.left
            }
            val y = when (alignment) {
                KeyAlignment.TOP -> target.top
                KeyAlignment.BOTTOM -> target.bottom - b.height
                KeyAlignment.CENTER_Y -> target.centerY - b.height / 2f
                else -> b.top
            }
            NormRect(x, y, x + b.width, y + b.height)
        }
    }

    /** Equal clear gaps, rather than equal centres, keep differently sized keys evenly spaced. */
    fun space(layer: LayerDef, selected: Set<String>, direction: KeySpacing): LayerDef {
        val keys = selectedFree(layer, selected)
        require(keys.size >= 3) { "Select at least three freely positioned keys" }
        val horizontal = direction == KeySpacing.HORIZONTAL
        val sorted = keys.sortedWith(compareBy<KeyDef> { if (horizontal) it.bounds!!.centerX else it.bounds!!.centerY }.thenBy { it.id })
        val first = sorted.first().bounds!!
        val last = sorted.last().bounds!!
        val start = if (horizontal) first.left else first.top
        val end = if (horizontal) last.right else last.bottom
        val totalSize = sorted.sumOf { (if (horizontal) it.bounds!!.width else it.bounds!!.height).toDouble() }.toFloat()
        val gap = (end - start - totalSize) / (sorted.size - 1)
        require(gap >= -0.00001f) { "There is not enough space between the outer keys" }
        var position = start
        val replacements = sorted.associate { key ->
            val b = key.bounds!!
            val next = if (horizontal) NormRect(position, b.top, position + b.width, b.bottom)
                else NormRect(b.left, position, b.right, position + b.height)
            position += (if (horizontal) b.width else b.height) + gap.coerceAtLeast(0f)
            key.id to next
        }
        return layer.copy(freeKeys = layer.freeKeys.map { key -> replacements[key.id]?.let { key.copy(bounds = bounded(it)) } ?: key })
    }

    private fun selectedFree(layer: LayerDef, ids: Set<String>): List<KeyDef> {
        rectangles(layer)
        val selected = layer.freeKeys.filter { it.id in ids }
        require(selected.size == ids.size) { "Switch to free positioning before aligning or moving these keys" }
        selected.forEach { key ->
            val bounds = key.bounds ?: error("A free key needs bounds")
            require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() } && bounds.width > 0f && bounds.height > 0f) { "Invalid key bounds" }
            require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 1f && bounds.bottom <= 1f) { "Keep keys inside the surface" }
        }
        return selected
    }

    private fun update(layer: LayerDef, ids: Set<String>, transform: (NormRect) -> NormRect): LayerDef =
        layer.copy(freeKeys = layer.freeKeys.map { key -> if (key.id in ids) key.copy(bounds = bounded(transform(key.bounds!!))) else key })

    private fun union(rects: List<NormRect>) = NormRect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
    private fun snapped(value: Float, snap: Float): Float = if (snap <= 0f) value else round(value / snap) * snap
    private fun bounded(rect: NormRect): NormRect {
        require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() }) { "Invalid key bounds" }
        val width = rect.width.coerceIn(0.001f, 1f)
        val height = rect.height.coerceIn(0.001f, 1f)
        val x = rect.left.coerceIn(0f, 1f - width)
        val y = rect.top.coerceIn(0f, 1f - height)
        return NormRect(x, y, x + width, y + height)
    }
}
