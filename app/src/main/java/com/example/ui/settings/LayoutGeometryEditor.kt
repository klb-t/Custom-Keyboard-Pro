package com.example.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.layout.KeyAlignment
import com.example.core.layout.KeySpacing
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutGeometry
import com.example.core.layout.NormRect
import kotlin.math.roundToInt

/** Edits a draft; opening, cancelling and undoing never writes the repository. */
@Composable
fun LayoutGeometryEditor(layout: LayoutDef, onChange: (LayoutDef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = layout.layers.isNotEmpty()) { Text("Visual geometry editor") }
    if (!open) return

    var draft by remember(layout) { mutableStateOf(layout) }
    var layerName by remember(layout) { mutableStateOf(layout.defaultLayer.takeIf { it in layout.layers } ?: layout.layers.keys.first()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var multiple by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(true) }
    var undo by remember { mutableStateOf(emptyList<LayoutDef>()) }
    var redo by remember { mutableStateOf(emptyList<LayoutDef>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf<LayoutDef?>(null) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val currentLayer = draft.layers.getValue(layerName)
    val liveDraft by rememberUpdatedState(draft)
    val liveSelected by rememberUpdatedState(selected)
    val liveMultiple by rememberUpdatedState(multiple)
    val liveSnap by rememberUpdatedState(snap)
    val colors = MaterialTheme.colorScheme

    fun commit(next: LayoutDef) {
        if (next == draft) return
        undo = (undo + draft).takeLast(50)
        redo = emptyList()
        draft = next
        error = null
    }
    fun edit(transform: (com.example.core.layout.LayerDef) -> com.example.core.layout.LayerDef) {
        runCatching { transform(draft.layers.getValue(layerName)) }
            .onSuccess { next -> commit(draft.copy(layers = draft.layers + (layerName to next))) }
            .onFailure { error = it.message ?: "Cannot change this geometry" }
    }

    Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(8.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp),
            shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${layout.name} · geometry", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    draft.layers.keys.forEach { name ->
                        TextButton(onClick = { layerName = name; selected = emptySet(); error = null }) {
                            Text(if (name == layerName) "[$name]" else name)
                        }
                    }
                }
                Text(if (currentLayer.rows.isEmpty()) "Drag selected keys to move them together. Positions stay inside this surface."
                    else "Drag a key to reorder it within a row or drop it into another row.", style = MaterialTheme.typography.bodySmall)

                val rectangles = remember(currentLayer) { runCatching { LayoutGeometry.rectangles(currentLayer) }.getOrDefault(emptyMap()) }
                val keys = remember(currentLayer) { currentLayer.allKeys.associateBy { it.id } }
                val liveRectangles by rememberUpdatedState(rectangles)
                BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp).clipToBounds().testTag("geometry-canvas")
                    .background(colors.surfaceVariant, RoundedCornerShape(6.dp)).border(1.dp, colors.outline, RoundedCornerShape(6.dp))) {
                    val density = LocalDensity.current
                    val w = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                    fun hit(point: Offset): String? = liveRectangles.entries.lastOrNull { (_, b) -> b.contains(point.x / w, point.y / h) }?.key
                    Box(Modifier.fillMaxSize()
                        .pointerInput(layerName, w, h) {
                            detectTapGestures { point ->
                                val id = hit(point)
                                selected = when {
                                    id == null -> emptySet()
                                    liveMultiple -> if (id in liveSelected) liveSelected - id else liveSelected + id
                                    else -> setOf(id)
                                }
                            }
                        }
                        .pointerInput(layerName, w, h) {
                            detectDragGestures(
                                onDragStart = { point ->
                                    dragKey = hit(point)
                                    dragStart = liveDraft
                                    dragOrigin = point
                                    dragDelta = Offset.Zero
                                    dragKey?.let { id ->
                                        if (id !in liveSelected) selected = if (liveMultiple) liveSelected + id else setOf(id)
                                    }
                                },
                                onDragCancel = {
                                    dragStart?.let { draft = it }
                                    dragStart = null; dragKey = null; dragDelta = Offset.Zero
                                },
                                onDragEnd = {
                                    val before = dragStart
                                    val id = dragKey
                                    if (before != null && id != null) {
                                        val initial = before.layers.getValue(layerName)
                                        if (initial.rows.any { row -> row.keys.any { it.id == id } }) {
                                            runCatching { LayoutGeometry.dropInRows(initial, id,
                                                (dragOrigin.x + dragDelta.x) / w, (dragOrigin.y + dragDelta.y) / h) }
                                                .onSuccess { next -> commit(before.copy(layers = before.layers + (layerName to next))) }
                                                .onFailure { error = it.message }
                                        } else if (draft != before) {
                                            undo = (undo + before).takeLast(50); redo = emptyList(); error = null
                                        }
                                    }
                                    dragStart = null; dragKey = null; dragDelta = Offset.Zero
                                }
                            ) { change, amount ->
                                change.consume()
                                dragDelta += amount
                                val before = dragStart ?: return@detectDragGestures
                                val id = dragKey ?: return@detectDragGestures
                                val initial = before.layers.getValue(layerName)
                                if (initial.freeKeys.any { it.id == id }) {
                                    runCatching { LayoutGeometry.moveFree(initial, liveSelected,
                                        dragDelta.x / w, dragDelta.y / h, if (liveSnap) LayoutGeometry.DEFAULT_SNAP else 0f) }
                                        .onSuccess { next -> draft = before.copy(layers = before.layers + (layerName to next)) }
                                        .onFailure { error = it.message }
                                }
                            }
                        }) {
                        Canvas(Modifier.fillMaxSize()) {
                            for (step in 1..9) {
                                val fraction = step / 10f
                                drawLine(colors.outline.copy(alpha = 0.15f), Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
                                drawLine(colors.outline.copy(alpha = 0.15f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                            }
                        }
                        rectangles.forEach { (id, b) ->
                            val chosen = id in selected
                            val rowDrag = dragKey == id && currentLayer.rows.any { row -> row.keys.any { it.id == id } }
                            val dx = if (rowDrag) dragDelta.x else 0f
                            val dy = if (rowDrag) dragDelta.y else 0f
                            Box(Modifier.offset { IntOffset((b.left * w + dx).roundToInt(), (b.top * h + dy).roundToInt()) }
                                .size(with(density) { (b.width * w).toDp() }, with(density) { (b.height * h).toDp() })
                                .padding(1.dp).background(if (chosen) colors.primaryContainer else colors.surface, RoundedCornerShape(4.dp))
                                .border(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outline, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(keys[id]?.effectiveLabel?.ifEmpty { keys[id]?.icon ?: id } ?: id,
                                    color = if (chosen) colors.onPrimaryContainer else colors.onSurface, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
                Text("${selected.size} selected · ${if (currentLayer.rows.isEmpty()) "free positions" else "row flow"}", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Multi-select", Modifier.weight(1f)); Switch(multiple, { multiple = it })
                    Text("Snap 1%", Modifier.padding(start = 12.dp)); Switch(snap, { snap = it })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { edit(LayoutGeometry::toFree) }, enabled = currentLayer.rows.isNotEmpty()) { Text("Use free positioning") }
                    TextButton(onClick = { selected = currentLayer.allKeys.map { it.id }.toSet() }) { Text("Select all") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
                }
                if (currentLayer.rows.isNotEmpty()) Text("Free positioning freezes this layer's current rectangles. Undo restores its rows.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    KeyAlignment.entries.forEach { alignment ->
                        TextButton(onClick = { edit { LayoutGeometry.align(it, selected, alignment) } }, enabled = selected.size >= 2 && currentLayer.rows.isEmpty()) { Text(alignment.title) }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    KeySpacing.entries.forEach { spacing ->
                        TextButton(onClick = { edit { LayoutGeometry.space(it, selected, spacing) } }, enabled = selected.size >= 3 && currentLayer.rows.isEmpty()) { Text(spacing.title) }
                    }
                }
                error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val previous = undo.last(); undo = undo.dropLast(1); redo = (redo + draft).takeLast(50); draft = previous; error = null
                    }, enabled = undo.isNotEmpty()) { Text("Undo") }
                    TextButton(onClick = {
                        val next = redo.last(); redo = redo.dropLast(1); undo = (undo + draft).takeLast(50); draft = next; error = null
                    }, enabled = redo.isNotEmpty()) { Text("Redo") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                    TextButton(onClick = { onChange(draft); open = false }, enabled = draft != layout) { Text("Apply geometry") }
                }
            }
        }
    }
}
