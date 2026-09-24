package com.example.ui.kb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.layout.ControlDef
import com.example.core.layout.ControlKind
import com.example.core.layout.Controls
import kotlin.math.min

/**
 * One slider, knob, pad or switch, drawn and dragged.
 *
 * All arithmetic is in [Controls]; this only turns a finger into a position and a
 * position into a picture. [onValue] is called with every real change while dragging
 * (when the control is live) and once more, marked final, where the finger lifts — the
 * caller decides what to send and what to keep.
 */
@Composable
fun ControlView(
    def: ControlDef,
    start: Pair<Double, Double>?,
    theme: KeyboardTheme,
    onValue: (value: Double, valueY: Double, final: Boolean) -> Unit
) {
    var value by remember(def) { mutableStateOf(Controls.quantize(start?.first ?: def.value, def)) }
    var valueY by remember(def) { mutableStateOf(Controls.quantize(start?.second ?: def.valueY, def)) }
    var sentX by remember(def) { mutableStateOf(value) }
    var sentY by remember(def) { mutableStateOf(valueY) }
    val emit by rememberUpdatedState(onValue)

    fun update(x: Double, y: Double, final: Boolean) {
        value = x
        valueY = y
        val moved = Controls.changed(sentX, x, def) || Controls.changed(sentY, y, def)
        if ((def.live && moved) || final) {
            sentX = x
            sentY = y
            emit(x, y, final)
        }
    }

    fun fromPoint(p: Offset, w: Float, h: Float): Pair<Double, Double> {
        val fx = (p.x / w).toDouble()
        val fy = (1.0 - p.y / h)
        return when (def.kind) {
            ControlKind.SLIDER -> {
                val v = Controls.fromFraction(if (def.vertical) fy else fx, def)
                v to valueY
            }
            ControlKind.XY -> Controls.fromFraction(fx, def) to Controls.fromFraction(fy, def)
            else -> value to valueY
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
            .pointerInput(def) {
                detectTapGestures { p ->
                    when (def.kind) {
                        ControlKind.TOGGLE -> {
                            val mid = (def.min + def.max) / 2
                            val next = if (value >= mid) def.min else def.max
                            update(next, valueY, final = true)
                        }
                        ControlKind.KNOB -> Unit
                        else -> {
                            val (x, y) = fromPoint(p, size.width.toFloat(), size.height.toFloat())
                            update(x, y, final = true)
                        }
                    }
                }
            }
            .pointerInput(def) {
                detectDragGestures(
                    onDragEnd = { update(value, valueY, final = true) },
                    onDragCancel = { update(value, valueY, final = true) }
                ) { change, drag ->
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    when (def.kind) {
                        ControlKind.KNOB -> update(
                            Controls.knobAfterDrag(value, drag.x, drag.y, 2f * min(w, h), def), valueY, final = false
                        )
                        ControlKind.TOGGLE -> Unit
                        else -> {
                            val (x, y) = fromPoint(change.position, w, h)
                            update(x, y, final = false)
                        }
                    }
                }
            }
    ) {
        val track = theme.keyBackground
        val fill = theme.keyActiveBackground
        val ink = theme.keyText
        val f = Controls.fraction(value, def).toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (def.kind) {
                ControlKind.SLIDER -> {
                    val r = CornerRadius(min(w, h) / 2, min(w, h) / 2)
                    drawRoundRect(track, size = Size(w, h), cornerRadius = r)
                    if (def.vertical) {
                        val filled = h * f
                        drawRoundRect(fill, topLeft = Offset(0f, h - filled), size = Size(w, filled), cornerRadius = r)
                    } else {
                        drawRoundRect(fill, size = Size(w * f, h), cornerRadius = r)
                    }
                }
                ControlKind.KNOB -> {
                    val d = min(w, h) * 0.8f
                    val topLeft = Offset((w - d) / 2, (h - d) / 2)
                    val stroke = Stroke(width = d * 0.12f)
                    drawArc(track, 135f, 270f, false, topLeft, Size(d, d), style = stroke)
                    drawArc(fill, 135f, 270f * f, false, topLeft, Size(d, d), style = stroke)
                }
                ControlKind.XY -> {
                    drawRoundRect(track, size = Size(w, h), cornerRadius = CornerRadius(12f, 12f))
                    val fy = Controls.fraction(valueY, def).toFloat()
                    val c = Offset(w * f, h * (1 - fy))
                    drawLine(fill, Offset(c.x, 0f), Offset(c.x, h), strokeWidth = 2f)
                    drawLine(fill, Offset(0f, c.y), Offset(w, c.y), strokeWidth = 2f)
                    drawCircle(ink, radius = min(w, h) * 0.05f, center = c)
                }
                ControlKind.TOGGLE -> {
                    val on = value >= (def.min + def.max) / 2
                    val r = CornerRadius(min(w, h) / 2, min(w, h) / 2)
                    drawRoundRect(if (on) fill else track, size = Size(w, h), cornerRadius = r)
                    val knobR = min(w, h) * 0.4f
                    drawCircle(ink, knobR, Offset(if (on) w - min(w, h) / 2 else min(w, h) / 2, h / 2))
                }
            }
        }
        val shown = when (def.kind) {
            ControlKind.XY -> Controls.format(value, def) + " · " + Controls.format(valueY, def)
            ControlKind.TOGGLE -> if (value >= (def.min + def.max) / 2) "on" else "off"
            else -> Controls.format(value, def)
        }
        Text(
            text = listOf(def.label, shown).filter { it.isNotBlank() }.joinToString("  "),
            color = ink,
            fontSize = 11.sp,
            modifier = Modifier.align(if (def.kind == ControlKind.SLIDER && def.vertical) Alignment.TopCenter else Alignment.Center)
        )
    }
}
