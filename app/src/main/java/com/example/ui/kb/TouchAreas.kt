package com.example.ui.kb

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/**
 * Tells the service that a finger has to be able to reach this.
 *
 * Every restricted-touch mode builds its region out of what was reported, so anything
 * pressable that does not report is dead in those modes — which is how the toolbar
 * came to ignore taps while the keys under it worked. Reporting from the composable
 * that draws the thing, in window coordinates, is what keeps the two from disagreeing.
 *
 * Withdrawn when the composable leaves, so a row that is no longer drawn does not keep
 * a rectangle of the app's screen.
 */
@Composable
fun Modifier.touchTarget(id: String): Modifier {
    val host = LocalKeyboardHost.current
    DisposableEffect(host, id) {
        onDispose { host.reportKeyRects(id, emptyList()) }
    }
    return this.onGloballyPositioned { host.reportKeyRects(id, listOf(it.windowRect())) }
}

/**
 * Tells the service where one whole piece of the keyboard is.
 *
 * [reservesContent] asks the app to stay above it, which only a piece along the
 * bottom edge can sensibly do; see [KeyboardHost.reportPanelRect].
 */
@Composable
fun Modifier.panelArea(id: String, reservesContent: Boolean): Modifier {
    val host = LocalKeyboardHost.current
    DisposableEffect(host, id) {
        onDispose { host.reportPanelRect(id, null, false) }
    }
    return this.onGloballyPositioned { host.reportPanelRect(id, it.windowRect(), reservesContent) }
}

internal fun LayoutCoordinates.windowRect(): Rect {
    val b = boundsInWindow()
    return Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
}
