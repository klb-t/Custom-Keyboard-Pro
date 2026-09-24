package com.example.ui.kb

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something the keyboard has to tell the user, and possibly a thing to do about it. */
data class Notice(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

/**
 * One line of news, shown where the suggestions are, for a few seconds.
 *
 * Needed once keys can do things outside the field: "Home" pressed without the
 * access it needs must say so, and say where to grant it, rather than doing nothing.
 * One at a time, newest wins — a queue of stale explanations is worse than none.
 */
class NoticeBoard {
    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var next = 0L

    fun post(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val notice = Notice(++next, text, actionLabel, action)
        _current.value = notice
        // Longer when there is something to tap: reading and then reaching takes time.
        val ms = if (action != null && actionLabel != null) 7000L else 3500L
        handler.postDelayed({ if (_current.value?.id == notice.id) _current.value = null }, ms)
    }

    fun dismiss() {
        _current.value = null
    }
}

@Composable
fun NoticeBar(notice: Notice, theme: KeyboardTheme, height: Dp, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(theme.stripBackground)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            notice.text,
            color = theme.keyText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (notice.action != null && notice.actionLabel != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .background(theme.keyBackground, RoundedCornerShape(6.dp))
                    .clickable {
                        notice.action.invoke()
                        onDismiss()
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(notice.actionLabel, color = theme.keyText, fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(
            Modifier.width(34.dp).fillMaxHeight().clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = theme.keyHintText, fontSize = 13.sp)
        }
    }
}
