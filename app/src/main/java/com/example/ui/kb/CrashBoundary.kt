package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.util.AppLogger

/**
 * What the keyboard shows instead of nothing.
 *
 * Without this, a throw during the keyboard's first composition — a corrupt custom
 * layout, a bad settings value, anything the rest of the app failed to validate —
 * kills the whole input method process before its first frame. There is no crash
 * dialog for an IME: the system just falls back to whichever keyboard showed before,
 * and from the user's side "the keyboard tried to draw, then wasn't there" is
 * indistinguishable from "nothing happened at all".
 *
 * This turns that into a visible, readable failure instead: the exception is logged
 * with a full stack trace through [AppLogger], and this composable's own fallback UI
 * — plain enough to not need anything [content] depends on — takes the frame instead
 * of leaving it blank, with the message right there so it doesn't need Logcat to
 * read.
 *
 * This only catches what happens *during* [content]'s own composition. A throw from
 * a `LaunchedEffect` or another coroutine runs on its own stack later and is not
 * something a `try`/`catch` around a composable call can intercept; those still reach
 * [AppLogger]'s uncaught-exception handler and are readable from the Diagnostics
 * screen after the fact, just not recoverably in place.
 */
@Composable
fun CrashBoundary(tag: String, content: @Composable () -> Unit) {
    var caught by remember { mutableStateOf<Throwable?>(null) }
    val error = caught
    if (error != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A0E0E))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Keyboard crashed while drawing",
                color = Color(0xFFFFB4A9),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Open the app → Diagnostics to copy the full error.",
                color = Color(0xFFFFDAD4),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Text(
                error.stackTraceToString(),
                color = Color(0xFFFFDAD4),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    try {
        content()
    } catch (crash: Throwable) {
        AppLogger.e(tag, "caught during composition, showing the fallback screen", crash)
        caught = crash
    }
}
