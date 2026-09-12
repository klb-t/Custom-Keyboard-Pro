package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.layout.PopupGroup

/**
 * The long-press board for keys that carry more than a row's worth of symbols.
 *
 * Two things make it different from the slide-to-pick popup, and both come from the
 * same observation: sixty targets is not a gesture, it is a screen.
 *
 * It **stays open** after the finger lifts, so symbols are tapped rather than dragged
 * to. And it has **tabs**, because sixty symbols are never one list — they are
 * lowercase accents, uppercase accents, Greek, operators, sets, arrows, and which of
 * those you want is a different question from which symbol you want.
 *
 * The selected tab is remembered, and more than one can be shown at once: writing an
 * equation inside Polish prose means wanting maths and Polish diacritics together,
 * not a toggle between them. That is a real workflow, not a hypothetical, so it is
 * not hidden behind a preference — pinning a tab is one tap on the board itself.
 */
@Composable
fun SymbolBoard(
    groups: List<PopupGroup>,
    selectedGroupId: String,
    pinnedGroupIds: Set<String>,
    theme: KeyboardTheme,
    columns: Int,
    onSelectGroup: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return
    val selected = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()
    // Pinned groups are shown alongside, never instead: the selected one stays put so
    // the board does not reshuffle under the finger when a pin is added.
    val shown = buildList {
        add(selected)
        groups.forEach { if (it.id != selected.id && it.id in pinnedGroupIds) add(it) }
    }

    Box(
        modifier
            .fillMaxSize()
            // Anywhere outside the board closes it. An overlay you cannot dismiss by
            // missing it is a trap.
            .background(theme.background.copy(alpha = 0.88f))
            .clickable(onClick = onClose)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            TabStrip(
                groups = groups,
                selectedId = selected.id,
                pinned = pinnedGroupIds,
                theme = theme,
                onSelect = onSelectGroup,
                onTogglePin = onTogglePin,
                onClose = onClose
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                shown.forEach { group ->
                    if (shown.size > 1) {
                        Text(
                            group.title ?: group.label,
                            color = theme.keyHintText,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp)
                        )
                    }
                    SymbolGrid(
                        items = group.items,
                        columns = columns,
                        theme = theme,
                        onPick = onPick
                    )
                }
            }
        }
    }
}

@Composable
private fun TabStrip(
    groups: List<PopupGroup>,
    selectedId: String,
    pinned: Set<String>,
    theme: KeyboardTheme,
    onSelect: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            groups.forEach { group ->
                val isSelected = group.id == selectedId
                val isPinned = group.id in pinned
                Box(
                    Modifier
                        .background(
                            if (isSelected) theme.keyActiveBackground else theme.keySpecialBackground,
                            RoundedCornerShape(8.dp)
                        )
                        .then(
                            if (isPinned && !isSelected)
                                Modifier.border(1.dp, theme.indicatorOn, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        // Tap selects; a second tap on the already-selected tab pins it,
                        // which keeps "show me two at once" reachable without a menu.
                        .clickable {
                            if (isSelected) onTogglePin(group.id) else onSelect(group.id)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        group.label,
                        color = if (isSelected) theme.keyText else theme.keySpecialText,
                        fontSize = 15.sp
                    )
                }
            }
        }
        Box(
            Modifier
                .size(34.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = theme.keyHintText, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SymbolGrid(
    items: List<String>,
    columns: Int,
    theme: KeyboardTheme,
    onPick: (String) -> Unit
) {
    val perRow = columns.coerceAtLeast(4)
    items.chunked(perRow).forEach { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            row.forEach { item ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(theme.keyBackground, RoundedCornerShape(6.dp))
                        .clickable { onPick(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item,
                        color = theme.keyText,
                        fontSize = if (item.length > 1) 13.sp else 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            // Keep the last row's cells the same width as every other row's.
            repeat(perRow - row.size) {
                Box(Modifier.weight(1f).height(40.dp))
            }
        }
    }
}
