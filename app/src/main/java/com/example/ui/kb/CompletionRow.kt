package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.predict.CompletionState
import com.example.core.predict.Slice

/**
 * The second row: what could come next, at several lengths.
 *
 * Deliberately not the same row as the corrections. The first row changes text that
 * already exists; this one adds text that only enters on a deliberate tap. Mixing them
 * would put "replace what you wrote" and "add what you have not written" side by side,
 * looking identical and meaning opposite things — and a mis-tap would then be a silent
 * substitution, which is the exact failure the two-lane split exists to prevent.
 *
 * Each offer is a prefix of the same continuation, shortest first, so moving right is
 * simply taking more of the one thing the model proposed. They cannot disagree with
 * each other, because there is only one of them.
 */
@Composable
fun CompletionRow(
    state: CompletionState,
    theme: KeyboardTheme,
    height: Dp,
    onAccept: (Slice) -> Unit,
    onDismiss: () -> Unit
) {
    // Not a `return`. The caller has already decided this row's height, and giving
    // the space back mid-composition is how the keys below it move between one
    // keystroke and the next. Empty, it is a bar of the strip's own colour — which is
    // what the space above the keys looks like anyway.
    if (state.isEmpty && !state.running) {
        Box(Modifier.fillMaxWidth().height(height).background(theme.stripBackground))
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(theme.stripBackground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Marks the row as the one that adds rather than replaces. Small, constant,
        // and in the same place every time: the user should never have to read the
        // contents to know which row they are looking at.
        Box(
            Modifier.width(26.dp).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Text("›", color = theme.keyHintText, fontSize = 16.sp)
        }

        Row(
            Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.slices.forEach { slice ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = 4.dp, horizontal = 3.dp)
                        .background(theme.keyBackground, RoundedCornerShape(6.dp))
                        .clickable { onAccept(slice) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = slice.text.replace('\n', ' ').trim(),
                            color = theme.keyText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                        // How much this one takes. Without it the offers look like
                        // four different guesses instead of four depths of one.
                        Text(
                            "  ${slice.scope.label()}",
                            color = theme.keyHintText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
            }

            if (state.running) {
                Box(
                    Modifier.fillMaxHeight().padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("…", color = theme.keyHintText, fontSize = 15.sp)
                }
            }
        }

        // Said rather than hidden. "It stopped because you had been waiting" is news,
        // and a continuation that was cut short looks like a bad model otherwise.
        state.stoppedBecause?.takeIf { state.slices.isNotEmpty() }?.let { reason ->
            Box(
                Modifier.fillMaxHeight().padding(end = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(reason, color = theme.keyHintText, fontSize = 9.sp, maxLines = 1)
            }
        }

        state.error?.let {
            Box(
                Modifier.fillMaxHeight().padding(end = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("!", color = theme.indicatorOff, fontSize = 14.sp)
            }
        }

        Box(
            Modifier.width(30.dp).fillMaxHeight().clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = theme.keyHintText, fontSize = 13.sp)
        }
    }
}
