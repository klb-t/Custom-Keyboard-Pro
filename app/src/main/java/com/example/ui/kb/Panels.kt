package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTask
import com.example.core.ai.AiTasks
import com.example.core.asr.AsrState
import com.example.core.config.Settings
import com.example.core.data.ClipboardEntity
import com.example.core.layout.ClipboardOp
import com.example.core.layout.CursorDirection
import com.example.core.layout.IndicatorKeys
import com.example.core.layout.KeyAction
import com.example.core.layout.ModifierKind
import com.example.core.layout.PanelId
import com.example.core.layout.TextUnit as EditUnit
import com.example.core.suggest.Suggestion
import com.example.core.suggest.SuggestionSource
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Suggestion strip
// ---------------------------------------------------------------------------

/**
 * The row above the keys.
 *
 * It shows suggestions when there are any and a small toolbar when there are not, so
 * the space is never simply blank — and the toolbar is where clipboard, voice and the
 * AI tools live for people who do not want to give up a key to them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SuggestionStrip(
    suggestions: List<Suggestion>,
    settings: Settings,
    theme: KeyboardTheme,
    aiBusy: Boolean,
    onAccept: (Suggestion) -> Unit,
    onReject: (Suggestion) -> Unit,
    onToolbar: (PanelId) -> Unit,
    height: androidx.compose.ui.unit.Dp
) {
    val host = LocalKeyboardHost.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(theme.stripBackground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (suggestions.isEmpty()) {
            ToolbarButton("☺", "Emoji", theme) { onToolbar(PanelId.EMOJI) }
            ToolbarButton("▤", "Clipboard", theme) { onToolbar(PanelId.CLIPBOARD) }
            ToolbarButton("🎤", "Voice", theme) { onToolbar(PanelId.VOICE) }
            ToolbarButton("✦", "AI tools", theme) { onToolbar(PanelId.AI_TOOLS) }
            ToolbarButton("⇄", "Cursor", theme) { onToolbar(PanelId.CURSOR) }
            ToolbarButton("⌨", "Layouts", theme) { onToolbar(PanelId.LAYOUT_PICKER) }
            Spacer(Modifier.weight(1f))
            if (aiBusy) {
                PanelText("…", color = theme.stripAiText, modifier = Modifier.padding(end = 12.dp))
            }
            ToolbarButton("⚙", "Settings", theme) { host.openApp() }
        } else {
            LazyRow(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(suggestions.take(settings.suggestionCount.coerceAtLeast(1) * 2)) { index, suggestion ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (index > 0) {
                            Box(
                                Modifier.width(1.dp).height(height * 0.4f)
                                    .background(theme.stripText.copy(alpha = 0.2f))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .combinedClickable(
                                    onClick = { onAccept(suggestion) },
                                    onLongClick = { onReject(suggestion) }
                                )
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (suggestion.source == SuggestionSource.AI) {
                                    PanelText("✦ ", color = theme.stripAiText, fontSize = 13.sp)
                                }
                                if (suggestion.source == SuggestionSource.SHORTCUT) {
                                    PanelText("⌁ ", color = theme.stripAiText, fontSize = 13.sp)
                                }
                                if (suggestion.source == SuggestionSource.CLIPBOARD) {
                                    PanelText("▤ ", color = theme.stripAiText, fontSize = 13.sp)
                                }
                                PanelText(
                                    text = suggestion.display,
                                    color = if (suggestion.source == SuggestionSource.AI) theme.stripAiText
                                    else theme.stripText,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (aiBusy) PanelText("…", color = theme.stripAiText, modifier = Modifier.padding(end = 8.dp))
            ToolbarButton("▾", "More", theme) { onToolbar(PanelId.CLIPBOARD) }
        }
    }
}

@Composable
private fun ToolbarButton(glyph: String, label: String, theme: KeyboardTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        PanelText(glyph, color = theme.stripText, fontSize = 17.sp)
    }
}

/**
 * Panel text, with the theme colour and size up front.
 *
 * Named rather than shadowing `Text`: a same-package private overload wins resolution
 * over the imported one, which silently changes what a neighbouring file compiles to.
 */
@Composable
private fun PanelText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Text(
        text = text, color = color, fontSize = fontSize, fontWeight = fontWeight,
        maxLines = maxLines, overflow = overflow, modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// Panel chrome
// ---------------------------------------------------------------------------

@Composable
private fun PanelFrame(
    title: String,
    theme: KeyboardTheme,
    onClose: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(theme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).background(theme.stripBackground),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelText(title, theme.stripText, 14.sp, FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
            Spacer(Modifier.weight(1f))
            actions()
            Box(
                Modifier.fillMaxHeight().clickable(onClick = onClose).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText("✕", theme.stripText, 16.sp)
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

// ---------------------------------------------------------------------------
// Emoji
// ---------------------------------------------------------------------------

@Composable
fun EmojiPanel(theme: KeyboardTheme, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    var categoryIndex by remember { mutableStateOf(0) }
    var showKaomoji by remember { mutableStateOf(false) }
    val recents = remember { mutableStateOf(EmojiRecents.load()) }

    PanelFrame(
        title = if (showKaomoji) "Text faces" else EmojiData.CATEGORIES[categoryIndex].name,
        theme = theme,
        onClose = onClose,
        actions = {
            Box(
                Modifier.fillMaxHeight().clickable { showKaomoji = !showKaomoji }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText(if (showKaomoji) "😀" else "¯\\_(ツ)_/¯", theme.stripText, 13.sp)
            }
            Box(
                Modifier.fillMaxHeight().clickable { host.perform(KeyAction.Backspace(EditUnit.CHARACTER)) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText("⌫", theme.stripText, 16.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showKaomoji) {
                LazyColumn(Modifier.weight(1f)) {
                    items(EmojiData.KAOMOJI) { face ->
                        Box(
                            Modifier.fillMaxWidth()
                                .clickable { host.perform(KeyAction.PanelText(face)) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            PanelText(face, theme.keyText, 16.sp)
                        }
                    }
                }
            } else {
                val emoji = if (categoryIndex == 0 && recents.value.isNotEmpty()) {
                    recents.value + EmojiData.CATEGORIES[0].emoji
                } else {
                    EmojiData.CATEGORIES[categoryIndex].emoji
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    gridItems(emoji) { glyph ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    host.perform(KeyAction.PanelText(glyph))
                                    recents.value = EmojiRecents.remember(glyph)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            PanelText(glyph, theme.keyText, 24.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                        .background(theme.stripBackground)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EmojiData.CATEGORIES.forEachIndexed { index, category ->
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clickable { categoryIndex = index }
                                .background(
                                    if (index == categoryIndex) theme.keyActiveBackground.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PanelText(category.icon, theme.stripText, 18.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Recently used emoji, kept in memory for the life of the process. */
object EmojiRecents {
    private val recents = mutableListOf<String>()

    fun load(): List<String> = recents.toList()

    fun remember(glyph: String): List<String> {
        recents.remove(glyph)
        recents.add(0, glyph)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
        return recents.toList()
    }
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

@Composable
fun ClipboardPanel(theme: KeyboardTheme, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val clipFlow = remember(query) {
        if (query.isBlank()) host.repository.observeClipboard()
        else host.repository.searchClipboard(query)
    }
    val clips by clipFlow.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<ClipboardEntity?>(null) }

    PanelFrame(
        title = "Clipboard",
        theme = theme,
        onClose = onClose,
        actions = {
            Box(
                Modifier.fillMaxHeight()
                    .clickable { scope.launch { host.repository.clearClipboard() } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText("Clear", theme.stripText, 13.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchField(query, theme) { query = it }

            val editingItem = editing
            if (editingItem != null) {
                ClipboardEditor(editingItem, theme, onDone = { newText ->
                    scope.launch { host.repository.updateClip(editingItem.id, newText) }
                    editing = null
                }, onCancel = { editing = null })
                return@Column
            }

            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PanelText(
                        if (query.isBlank()) "Nothing copied yet." else "No match.",
                        theme.keyHintText, 14.sp
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(clips, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { host.perform(KeyAction.PanelText(item.content)) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PanelText(
                                text = item.content.replace('\n', ' '),
                                color = theme.keyText,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconAction(if (item.pinned) "★" else "☆", theme) {
                                scope.launch { host.repository.setClipPinned(item.id, !item.pinned) }
                            }
                            IconAction("✎", theme) { editing = item }
                            IconAction("🗑", theme) {
                                scope.launch { host.repository.deleteClip(item.id) }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.keyBorder))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardEditor(
    item: ClipboardEntity,
    theme: KeyboardTheme,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember(item.id) { mutableStateOf(item.content) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = theme.keyText, fontSize = 15.sp),
            cursorBrush = SolidColor(theme.keyText),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(theme.keyBackground, RoundedCornerShape(8.dp))
                .padding(10.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            TextButton("Cancel", theme, onCancel)
            Spacer(Modifier.width(8.dp))
            TextButton("Save", theme) { onDone(text) }
        }
    }
}

@Composable
private fun SearchField(query: String, theme: KeyboardTheme, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .background(theme.keyBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (query.isEmpty()) PanelText("Search…", theme.keyHintText, 14.sp)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = theme.keyText, fontSize = 14.sp),
            cursorBrush = SolidColor(theme.keyText),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun IconAction(glyph: String, theme: KeyboardTheme, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        PanelText(glyph, theme.keyHintText, 15.sp)
    }
}

@Composable
private fun TextButton(label: String, theme: KeyboardTheme, onClick: () -> Unit) {
    Box(
        Modifier
            .background(theme.keyBackground, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PanelText(label, theme.keyText, 14.sp)
    }
}

// ---------------------------------------------------------------------------
// Voice
// ---------------------------------------------------------------------------

/**
 * Dictation with the alternatives visible.
 *
 * The recogniser's ranking is a guess; showing the runners-up costs one row and turns
 * a wrong transcription from "retype it" into "tap the right one".
 */
@Composable
fun VoicePanel(theme: KeyboardTheme, settings: Settings, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    val voiceState by host.voice.state.collectAsState()

    LaunchedEffect(Unit) {
        if (voiceState is AsrState.Idle) host.voice.start()
    }

    PanelFrame(title = "Dictation", theme = theme, onClose = {
        host.voice.cancel()
        onClose()
    }) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val current = voiceState) {
                is AsrState.Idle -> {
                    PanelText("Ready.", theme.keyText, 16.sp)
                    Spacer(Modifier.height(14.dp))
                    TextButton("Start", theme) { host.voice.start() }
                }

                is AsrState.Listening -> {
                    val level = ((current.rms + 2f) / 12f).coerceIn(0.15f, 1f)
                    Box(
                        Modifier
                            .size((48 + 36 * level).dp)
                            .background(theme.keyActiveBackground.copy(alpha = 0.25f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        PanelText("🎤", theme.keyText, 26.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    PanelText(
                        current.partial.ifBlank { "Listening…" },
                        theme.keyText, 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TextButton("Stop", theme) { host.voice.stop() }
                        Spacer(Modifier.width(10.dp))
                        TextButton("Cancel", theme) {
                            host.voice.cancel()
                            onClose()
                        }
                    }
                }

                is AsrState.Processing -> PanelText(
                    "Transcribing via " +
                        com.example.core.config.AsrEngines.label(settings.asrEngine) + "…",
                    theme.keyText, 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )

                is AsrState.Results -> {
                    // Committing the top hypothesis for the user is available but not the
                    // default: the recogniser's ranking is a guess, and the second entry
                    // is right often enough that choosing is worth one tap.
                    LaunchedEffect(current) {
                        if (settings.asrAutoCommitBest) {
                            current.alternatives.firstOrNull()?.let {
                                host.perform(KeyAction.PanelText(it.text))
                            }
                            host.voice.dismiss()
                            onClose()
                        }
                    }
                    val shown =
                        if (settings.asrShowAlternatives) current.alternatives
                        else current.alternatives.take(1)

                    PanelText(
                        if (shown.size > 1) "Pick what you said" else "Transcription",
                        theme.keyHintText, 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        itemsIndexed(shown) { index, alternative ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(
                                        if (index == 0) theme.keyActiveBackground.copy(alpha = 0.2f)
                                        else theme.keyBackground,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        host.perform(KeyAction.PanelText(alternative.text))
                                        host.voice.dismiss()
                                        onClose()
                                    }
                                    .padding(12.dp)
                            ) {
                                PanelText(alternative.text, theme.keyText, 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton("Again", theme) { host.voice.start() }
                        Spacer(Modifier.width(10.dp))
                        TextButton("Close", theme) {
                            host.voice.dismiss()
                            onClose()
                        }
                    }
                }

                is AsrState.Error -> {
                    PanelText(current.message, theme.keyText, 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        if (current.needsPermission) {
                            TextButton("Grant microphone", theme) { host.requestMicrophonePermission() }
                        } else {
                            TextButton("Try again", theme) { host.voice.start() }
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton("Close", theme) {
                            host.voice.dismiss()
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AI tools
// ---------------------------------------------------------------------------

@Composable
fun AiToolsPanel(theme: KeyboardTheme, settings: Settings, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeTask by remember { mutableStateOf<AiTask?>(null) }

    val tasks = remember(settings.aiCustomTasksJson) { AiTasks.all(settings.aiCustomTasksJson) }

    PanelFrame(title = "AI tools", theme = theme, onClose = onClose) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            when {
                !settings.aiEnabled -> Column {
                    PanelText(
                        "AI is switched off. It is off by default because it sends what you " +
                            "are writing to a provider you choose.",
                        theme.keyText, 14.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton("Set it up", theme) { host.openApp("ai") }
                }

                host.editor.isSensitive -> PanelText(
                    "This field is a password or has asked not to be personalised, so nothing " +
                        "from it is sent anywhere.",
                    theme.keyText, 14.sp
                )

                result != null -> {
                    val text = result.orEmpty()
                    PanelText("Result", theme.keyHintText, 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .background(theme.keyBackground, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn { item { PanelText(text, theme.keyText, 15.sp) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(
                            if (activeTask?.replaceInPlace == true) "Replace" else "Insert",
                            theme
                        ) {
                            if (activeTask?.replaceInPlace == true) {
                                host.editor.replaceSelectionOrAll(text)
                            } else {
                                host.perform(KeyAction.PanelText(text))
                            }
                            onClose()
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton("Copy", theme) {
                            host.perform(KeyAction.PanelText(text))
                            onClose()
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton("Back", theme) { result = null }
                    }
                }

                busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PanelText("Working…", theme.keyText, 16.sp)
                }

                else -> {
                    error?.let {
                        PanelText(it, theme.stripAiText, 13.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 150.dp)) {
                        gridItems(tasks) { task ->
                            Box(
                                Modifier
                                    .padding(4.dp)
                                    .background(theme.keyBackground, RoundedCornerShape(10.dp))
                                    .clickable {
                                        val source = host.textForAi()
                                        if (source.isBlank()) {
                                            error = "There is no text to work on."
                                            return@clickable
                                        }
                                        activeTask = task
                                        busy = true
                                        error = null
                                        scope.launch {
                                            val response = AiClient.complete(
                                                config = AiConfig.from(settings, maxTokens = 800),
                                                systemPrompt = task.systemPrompt,
                                                userPrompt = task.render(source)
                                            )
                                            busy = false
                                            response.onSuccess { result = it.trim() }
                                                .onFailure { error = it.message }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 14.dp)
                            ) {
                                PanelText(task.label, theme.keyText, 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cursor / selection pad
// ---------------------------------------------------------------------------

@Composable
fun CursorPanel(theme: KeyboardTheme, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    var selecting by remember { mutableStateOf(false) }

    @Composable
    fun Pad(label: String, weight: Float = 1f, onClick: () -> Unit) {
        Box(
            Modifier
                .padding(3.dp)
                .background(theme.keyBackground, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .fillMaxHeight()
                .width((56 * weight).dp),
            contentAlignment = Alignment.Center
        ) {
            PanelText(label, theme.keyText, 15.sp)
        }
    }

    PanelFrame(
        title = "Cursor & selection",
        theme = theme,
        onClose = onClose,
        actions = {
            Box(
                Modifier.fillMaxHeight()
                    .background(if (selecting) theme.keyActiveBackground.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { selecting = !selecting }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText(if (selecting) "Selecting" else "Select", theme.stripText, 13.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pad("⇤") { host.perform(KeyAction.MoveCursor(CursorDirection.LINE_START, EditUnit.CHARACTER, selecting)) }
                Pad("◂◂") { host.perform(KeyAction.MoveCursor(CursorDirection.LEFT, EditUnit.WORD, selecting)) }
                Pad("▲") { host.perform(KeyAction.MoveCursor(CursorDirection.UP, EditUnit.CHARACTER, selecting)) }
                Pad("▸▸") { host.perform(KeyAction.MoveCursor(CursorDirection.RIGHT, EditUnit.WORD, selecting)) }
                Pad("⇥") { host.perform(KeyAction.MoveCursor(CursorDirection.LINE_END, EditUnit.CHARACTER, selecting)) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pad("All") { host.perform(KeyAction.Select(EditUnit.ALL)) }
                Pad("◂") { host.perform(KeyAction.MoveCursor(CursorDirection.LEFT, EditUnit.CHARACTER, selecting)) }
                Pad("▼") { host.perform(KeyAction.MoveCursor(CursorDirection.DOWN, EditUnit.CHARACTER, selecting)) }
                Pad("▸") { host.perform(KeyAction.MoveCursor(CursorDirection.RIGHT, EditUnit.CHARACTER, selecting)) }
                Pad("Word") { host.perform(KeyAction.Select(EditUnit.WORD)) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pad("Cut") { host.perform(KeyAction.Clipboard(ClipboardOp.CUT)) }
                Pad("Copy") { host.perform(KeyAction.Clipboard(ClipboardOp.COPY)) }
                Pad("Paste") { host.perform(KeyAction.Clipboard(ClipboardOp.PASTE)) }
                Pad("Undo") { host.perform(KeyAction.Undo) }
                Pad("Redo") { host.perform(KeyAction.Redo) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pad("⌫") { host.perform(KeyAction.Backspace(EditUnit.CHARACTER)) }
                Pad("⌦") { host.perform(KeyAction.ForwardDelete(EditUnit.CHARACTER)) }
                Pad("⌫ word") { host.perform(KeyAction.Backspace(EditUnit.WORD)) }
                Pad("Line") { host.perform(KeyAction.Select(EditUnit.LINE)) }
                Pad("↵") { host.perform(KeyAction.Enter) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layout picker
// ---------------------------------------------------------------------------

@Composable
fun LayoutPickerPanel(theme: KeyboardTheme, settings: Settings, onClose: () -> Unit) {
    val host = LocalKeyboardHost.current
    PanelFrame(
        title = "Layouts",
        theme = theme,
        onClose = onClose,
        actions = {
            Box(
                Modifier.fillMaxHeight().clickable { host.openApp("layouts") }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                PanelText("Edit…", theme.stripText, 13.sp)
            }
        }
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(host.availableLayouts) { candidate ->
                val enabled = candidate.id in settings.enabledLayoutIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            host.selectLayout(candidate.id)
                            onClose()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        PanelText(
                            candidate.name,
                            if (candidate.id == host.layout.id) theme.stripAiText else theme.keyText,
                            15.sp
                        )
                        candidate.description?.let {
                            PanelText(it, theme.keyHintText, 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (enabled) PanelText("in rotation", theme.keyHintText, 11.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(theme.keyBorder))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Indicator strip
// ---------------------------------------------------------------------------

/**
 * A row of lamps: caps, num and scroll lock, live modifier state, and whether the
 * model or the microphone is busy.
 *
 * Hidden by default, because most people do not want it; available because on a
 * keyboard with real modifiers, not being able to see whether Ctrl is held is the
 * single most common way to get lost.
 */
@Composable
fun IndicatorStrip(theme: KeyboardTheme, height: androidx.compose.ui.unit.Dp) {
    val host = LocalKeyboardHost.current
    val state = host.state

    @Composable
    fun Lamp(label: String, on: Boolean) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Box(
                Modifier.size(7.dp)
                    .background(
                        if (on) theme.indicatorOn else theme.indicatorOff,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(4.dp))
            PanelText(label, if (on) theme.stripText else theme.keyHintText, 10.sp)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(theme.stripBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Lamp("CAPS", state.flag(IndicatorKeys.CAPS_LOCK))
        Lamp("SHIFT", state.isActive(ModifierKind.SHIFT))
        Lamp("CTRL", state.isActive(ModifierKind.CTRL))
        Lamp("ALT", state.isActive(ModifierKind.ALT))
        Lamp("META", state.isActive(ModifierKind.META))
        Lamp("FN", state.isActive(ModifierKind.FN))
        Lamp("NUM", state.flag(IndicatorKeys.NUM_LOCK))
        Lamp("SCRL", state.flag(IndicatorKeys.SCROLL_LOCK))
        Lamp("MIC", state.flag(IndicatorKeys.ASR_LISTENING))
        Lamp("AI", state.flag(IndicatorKeys.AI_BUSY))
        Lamp("SEL", state.flag(IndicatorKeys.SELECTION))
        Lamp("PWD", state.flag(IndicatorKeys.PASSWORD_FIELD))
        Lamp("LAYER:" + state.layer.uppercase(), state.layer != "base")
    }
}
