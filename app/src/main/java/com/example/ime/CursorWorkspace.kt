package com.example.ime

import android.graphics.RectF
import android.os.Build
import android.view.inputmethod.CursorAnchorInfo
import kotlin.math.abs

/** All rectangles crossing this boundary are SCREEN coordinates, not editor-local. */
internal object CursorWorkspace {
    fun focus(info: CursorAnchorInfo, maxEditorHeight: Float): RectF? {
        val x = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val bottom = info.insertionMarkerBottom
        val marker = if (listOf(x, top, bottom).all { it.isFinite() } && bottom >= top) {
            RectF(x - 1f, top, x + 1f, maxOf(bottom, top + 1f)).also { info.matrix.mapRect(it) }
        } else null
        val editor = if (Build.VERSION.SDK_INT >= 33) info.editorBoundsInfo?.editorBounds?.let { RectF(it) } else null
        editor?.let { info.matrix.mapRect(it) }
        val validEditor = editor?.takeIf { valid(it) && it.height() <= maxEditorHeight }
        return when {
            marker != null && valid(marker) -> marker.apply { validEditor?.let { union(it) } }
            validEditor != null -> validEditor
            else -> null
        }
    }

    fun valid(rect: RectF): Boolean = listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } &&
        rect.width() > 0f && rect.height() > 0f

    /** Return a signed upward translation. Null means neither side has enough room. */
    fun shift(panel: RectF, focus: RectF, workspace: RectF, margin: Float, obstacles: List<RectF> = emptyList()): Float? {
        val guarded = RectF(focus).apply { inset(-margin.coerceAtLeast(0f), -margin.coerceAtLeast(0f)) }
        if (!RectF.intersects(panel, guarded)) return 0f
        val above = panel.bottom - guarded.top
        val below = panel.top - guarded.bottom
        val choices = listOf(above, below).filter { shift ->
            panel.top - shift >= workspace.top && panel.bottom - shift <= workspace.bottom &&
                obstacles.none { RectF.intersects(RectF(panel).apply { offset(0f, -shift) }, it) }
        }
        return choices.minByOrNull { abs(it) }
    }
}
