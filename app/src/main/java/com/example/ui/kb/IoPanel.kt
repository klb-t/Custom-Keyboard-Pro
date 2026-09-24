package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.io.Command
import com.example.core.io.Verbs
import com.example.core.layout.CursorDirection
import com.example.core.layout.KeyAction
import com.example.core.layout.TextUnit as EditUnit
import com.example.io.IoAccessibilityService
import kotlin.math.abs

/** A ready-made line, so the common actions are one tap away before anyone binds a key. */
private data class Preset(val line: String, val label: String) {
    val command: Command = Command.parse(line) ?: Command(line)
}

/**
 * The first of these a user sees. Ordered by how often a phone needs them from a
 * keyboard: the system buttons, then reading and selecting, then media and the rest.
 * Every one of them is also bindable to any key as `do:<line>`.
 */
private val PRESETS = listOf(
    Preset("back", "Back"),
    Preset("home", "Home"),
    Preset("recents", "Recent apps"),
    Preset("notifications", "Notifications"),
    Preset("quick_settings", "Quick settings"),
    Preset("screenshot", "Screenshot"),
    Preset("scroll down", "Scroll down"),
    Preset("scroll up", "Scroll up"),
    Preset("read_screen to=clipboard", "Copy the screen"),
    Preset("read_screen to=ai", "Screen → AI"),
    Preset("sweep mode=checkboxes pages=1", "Tick all"),
    Preset("sweep mode=longpress pages=3", "Select 3 pages"),
    Preset("pointer toggle", "Pointer"),
    Preset("media play_pause", "Play / pause"),
    Preset("media previous", "Previous"),
    Preset("media next", "Next"),
    Preset("volume down", "Volume −"),
    Preset("volume up", "Volume +"),
    Preset("torch toggle", "Torch"),
    Preset("search", "Search selection"),
    Preset("share", "Share selection"),
    Preset("split_screen", "Split screen"),
    Preset("lock", "Lock"),
    Preset("system_settings wifi", "Wi-Fi settings")
)

/**
 * Actions beyond the text field, and a trackpad.
 *
 * Here so the new outputs can be used the day they exist, before anybody has drawn a
 * layout with them. What needs the accessibility service is shown dimmed while it is
 * off — still pressable, because pressing it is how you find out what it needs.
 */
@Composable
fun IoPanel(theme: KeyboardTheme, onClose: () -> Unit) {
    val context = LocalContext.current
    val running by IoAccessibilityService.running.collectAsState()
    var tab by remember { mutableStateOf(0) }

    PanelFrame(
        title = if (tab == 0) "Actions" else "Trackpad",
        theme = theme,
        onClose = onClose,
        actions = {
            listOf("Actions", "Trackpad").forEachIndexed { i, name ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .background(if (tab == i) theme.keyBackground else theme.stripBackground)
                        .clickable { tab = i }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PanelText(name, theme.stripText, 12.sp)
                }
            }
            if (!running) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clickable {
                            runCatching { context.startActivity(IoAccessibilityService.settingsIntent()) }
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PanelText("Access: off", theme.indicatorOff, 12.sp)
                }
            }
        }
    ) {
        if (tab == 0) ActionGrid(theme, running) else Trackpad(theme, running)
    }
}

@Composable
private fun ActionGrid(theme: KeyboardTheme, running: Boolean) {
    val host = LocalKeyboardHost.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(78.dp),
        modifier = Modifier.fillMaxSize().padding(4.dp)
    ) {
        items(PRESETS) { preset ->
            val spec = Verbs.byId(preset.command.verb)
            val dim = spec?.needsAccessibility == true && !running
            Column(
                Modifier
                    .padding(3.dp)
                    .height(58.dp)
                    .background(theme.keyBackground, RoundedCornerShape(8.dp))
                    .clickable { host.perform(KeyAction.Do(preset.command)) }
                    .alpha(if (dim) 0.45f else 1f)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PanelText(Verbs.glyphFor(preset.command), theme.keyText, 18.sp)
                androidx.compose.material3.Text(
                    preset.label,
                    color = theme.keyHintText,
                    fontSize = 10.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One finger moves, a tap clicks, two fingers scroll.
 *
 * With the accessibility service running, that drives the on-screen pointer; without
 * it, the same finger moves the text cursor instead — a trackpad for the one thing a
 * keyboard can always reach.
 */
@Composable
private fun Trackpad(theme: KeyboardTheme, running: Boolean) {
    val host = LocalKeyboardHost.current
    val density = LocalDensity.current
    val live by rememberUpdatedState(running)
    val stepX = with(density) { 14.dp.toPx() }
    val stepY = with(density) { 22.dp.toPx() }
    val slop = with(density) { 6.dp.toPx() }

    fun pointer() = IoAccessibilityService.instance?.pointer

    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(theme.keyBackground, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var travelled = 0f
                        var fingers = 1
                        var cursor = Offset.Zero
                        var scroll = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            fingers = maxOf(fingers, down.size)
                            var delta = Offset.Zero
                            down.forEach { delta += it.position - it.previousPosition }
                            delta /= down.size.toFloat()
                            travelled += delta.getDistance()
                            event.changes.forEach { it.consume() }

                            if (fingers >= 2) {
                                scroll += delta
                                if (scroll.getDistance() > 48f) {
                                    // A page follows the fingers, as on a laptop.
                                    pointer()?.scrollBy(scroll.x * 3f, scroll.y * 3f)
                                        ?: host.perform(
                                            KeyAction.Do(Command("scroll", listOf(if (scroll.y < 0) "down" else "up")))
                                        )
                                    scroll = Offset.Zero
                                }
                            } else if (live) {
                                // Faster the faster the finger moves: fine control when
                                // slow, the whole screen in one stroke when quick.
                                val gain = 1.4f + (delta.getDistance() / 18f).coerceAtMost(2.2f)
                                pointer()?.moveBy(delta.x * gain, delta.y * gain)
                            } else {
                                cursor += delta
                                while (abs(cursor.x) >= stepX) {
                                    val right = cursor.x > 0
                                    host.perform(
                                        KeyAction.MoveCursor(
                                            if (right) CursorDirection.RIGHT else CursorDirection.LEFT,
                                            EditUnit.CHARACTER
                                        )
                                    )
                                    cursor = cursor.copy(x = cursor.x - if (right) stepX else -stepX)
                                }
                                while (abs(cursor.y) >= stepY) {
                                    val downward = cursor.y > 0
                                    host.perform(
                                        KeyAction.MoveCursor(
                                            if (downward) CursorDirection.DOWN else CursorDirection.UP,
                                            EditUnit.CHARACTER
                                        )
                                    )
                                    cursor = cursor.copy(y = cursor.y - if (downward) stepY else -stepY)
                                }
                            }
                        }
                        if (fingers == 1 && travelled < slop && live) {
                            pointer()?.click()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            PanelText(
                if (running) "Drag to move · tap to click · two fingers to scroll"
                else "Drag to move the text cursor · switch Access on for a pointer",
                theme.keyHintText, 12.sp
            )
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Click" to { pointer()?.click(); Unit },
                "Hold" to { pointer()?.longClick(); Unit },
                "Pointer" to { host.perform(KeyAction.Do(Command("pointer", listOf("toggle")))) },
                "Back" to { host.perform(KeyAction.Do(Command("back"))) }
            ).forEach { (label, action) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(theme.keyBackground, RoundedCornerShape(8.dp))
                        .clickable(onClick = action),
                    contentAlignment = Alignment.Center
                ) {
                    PanelText(label, theme.keyText, 13.sp)
                }
            }
        }
    }
}
