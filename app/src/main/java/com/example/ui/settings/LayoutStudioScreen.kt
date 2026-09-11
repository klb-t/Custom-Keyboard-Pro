package com.example.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.ai.AiConfig
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.hitmap.HitmapExtractor
import com.example.core.layout.LayoutAuthoring
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutGenerator
import com.example.core.layout.LayoutJson
import com.example.core.layout.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where layouts are made.
 *
 * Four ways in, because "build a keyboard" means different things to different people:
 * pick a built-in and tweak it, paint a mask and place keys anywhere on it, hand a
 * screenshot to a model, or describe in words how the keys should behave. All four end
 * at the same editable JSON, so none of them is a dead end.
 */
@Composable
fun LayoutStudioScreen(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layouts by LayoutRepository.layouts.collectAsState()

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LayoutDef?>(null) }
    var describing by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var pendingImageMode by remember { mutableStateOf(ImageMode.MASK) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Reading the picture…"
        scope.launch {
            val result = runCatching {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                } ?: error("That file could not be read as an image.")

                when (pendingImageMode) {
                    ImageMode.MASK -> withContext(Dispatchers.Default) {
                        val assetName = LayoutRepository
                            .saveAsset("mask_${System.currentTimeMillis()}", bitmap)
                            .getOrNull()
                        val regions = HitmapExtractor.extractColorRegions(bitmap)
                        if (regions.isEmpty()) error("No distinct colour regions were found.")
                        LayoutAuthoring.fromRegions(
                            regions = regions,
                            id = "mask_${System.currentTimeMillis()}",
                            name = "From mask (${regions.size} keys)",
                            backgroundFile = assetName
                        )
                    }

                    ImageMode.TRACE -> withContext(Dispatchers.Default) {
                        val grid = HitmapExtractor.detectGrid(bitmap)
                        if (grid.rows.isEmpty()) error("No rows could be made out in that image.")
                        LayoutAuthoring.fromGrid(
                            grid = grid,
                            id = "traced_${System.currentTimeMillis()}",
                            name = "Traced (${grid.rows.size} rows)"
                        )
                    }

                    ImageMode.AI -> {
                        if (!settings.aiEnabled) error("Switch AI on first, under Settings → AI.")
                        LayoutGenerator.fromImage(AiConfig.from(settings), bitmap).getOrThrow()
                    }
                }
            }
            busy = false
            result.onSuccess { layout ->
                LayoutRepository.save(layout)
                    .onSuccess {
                        status = "Made \"${layout.name}\". Open it to check what each key does."
                        editing = layout
                    }
                    .onFailure { status = "Could not save it: ${it.message}" }
            }.onFailure { status = it.message ?: "That did not work." }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection(
            title = "Rotation",
            subtitle = "The layouts the layout key cycles through, in order."
        ) {
            OrderedChoiceList(
                all = layouts.map { it.id to it.name },
                enabled = settings.enabledLayoutIds.filter { id -> layouts.any { it.id == id } },
                onChange = { next ->
                    SettingsStore.update {
                        it.copy(
                            enabledLayoutIds = next,
                            activeLayoutId = if (it.activeLayoutId in next) it.activeLayoutId
                            else next.firstOrNull() ?: it.activeLayoutId
                        )
                    }
                }
            )
        }

        SettingsSection("Per-app") {
            SwitchRow(
                label = "Remember a layout per app",
                description = "The layout you last used in an app comes back when you " +
                    "return to it. Off by default: a keyboard that changes shape on its " +
                    "own is startling if you did not ask for it.",
                checked = settings.rememberLayoutPerApp,
                onChange = { on -> SettingsStore.update { it.copy(rememberLayoutPerApp = on) } }
            )
        }

        SettingsSection(
            title = "Make a layout",
            subtitle = "All four produce the same editable JSON."
        ) {
            ActionRow(
                "From a colour mask",
                "One flat colour per key, any shape, anywhere on the image. Exact — " +
                    "nothing is guessed. The picture becomes the keyboard's background.",
                onClick = {
                    pendingImageMode = ImageMode.MASK
                    imagePicker.launch("image/*")
                }
            )
            Divider()
            ActionRow(
                "Trace a screenshot",
                "Finds the rows and keys by looking for the gaps. On device, offline, " +
                    "instant — and a guess, so expect to fix labels.",
                onClick = {
                    pendingImageMode = ImageMode.TRACE
                    imagePicker.launch("image/*")
                }
            )
            Divider()
            ActionRow(
                "Read a screenshot with AI",
                if (settings.aiEnabled) {
                    "Reads the glyphs and infers what each key should do, including " +
                        "long-press alternates."
                } else {
                    "Needs AI switched on."
                },
                onClick = {
                    pendingImageMode = ImageMode.AI
                    imagePicker.launch("image/*")
                }
            )
            Divider()
            ActionRow(
                "Describe one in words",
                "\"Like Hacker's Keyboard but with a lit Caps Lock and swipe-up digits\" — " +
                    "and it writes the JSON.",
                onClick = { describing = true }
            )
            Divider()
            ActionRow(
                "Start from a blank grid",
                "A 4×10 grid of unbound keys to fill in yourself.",
                onClick = {
                    val blank = blankLayout()
                    LayoutRepository.save(blank)
                        .onSuccess {
                            editing = blank
                            status = "Created an empty layout."
                        }
                        .onFailure { status = "Could not save it: ${it.message}" }
                }
            )
        }

        if (busy) InfoRow("Working…")
        status?.let { InfoRow(it) }

        SettingsSection("All layouts") {
            layouts.forEach { layout ->
                ActionRow(
                    label = layout.name,
                    description = buildString {
                        append(layout.layers.size)
                        append(if (layout.layers.size == 1) " layer · " else " layers · ")
                        append(layout.base.allKeys.size)
                        append(" keys")
                        if (layout.builtIn && !LayoutRepository.isUserOwned(layout.id)) append(" · built in")
                        layout.description?.let { append("\n$it") }
                    },
                    trailing = if (layout.id == settings.activeLayoutId) "active" else null,
                    onClick = { editing = layout }
                )
                Divider()
            }
        }
    }

    editing?.let { layout ->
        LayoutEditorDialog(
            layout = layout,
            onDismiss = { editing = null },
            onSaved = { saved ->
                status = "Saved \"${saved.name}\"."
                editing = null
            }
        )
    }

    if (describing) {
        DescribeLayoutDialog(
            settings = settings,
            text = description,
            onTextChange = { description = it },
            onDismiss = { describing = false },
            onGenerate = {
                describing = false
                busy = true
                status = "Asking the model…"
                scope.launch {
                    val result = LayoutGenerator.fromDescription(AiConfig.from(settings), description)
                    busy = false
                    result.onSuccess { layout ->
                        LayoutRepository.save(layout)
                            .onSuccess {
                                editing = layout
                                status = "Made \"${layout.name}\"."
                            }
                            .onFailure { status = "Could not save it: ${it.message}" }
                    }.onFailure { status = it.message ?: "That did not work." }
                }
            }
        )
    }
}

private enum class ImageMode { MASK, TRACE, AI }

private fun blankLayout(): LayoutDef {
    val id = "blank_${System.currentTimeMillis()}"
    val rows = (0 until 4).map { row ->
        com.example.core.layout.RowDef(
            keys = (0 until 10).map { column ->
                com.example.core.layout.KeyDef(
                    id = "k_${row}_$column",
                    label = "·",
                    bindings = listOf(
                        com.example.core.layout.Binding(
                            com.example.core.layout.KeyTrigger.Tap,
                            com.example.core.layout.KeyAction.None
                        )
                    )
                )
            }
        )
    }
    return LayoutDef(
        id = id,
        name = "New layout",
        layers = linkedMapOf(
            LayoutDef.BASE_LAYER to com.example.core.layout.LayerDef(LayoutDef.BASE_LAYER, rows)
        ),
        description = "Empty grid. Edit the JSON to say what each key does."
    )
}

/**
 * The JSON editor.
 *
 * Editing the serialised form rather than a form-per-field is a deliberate choice: the
 * format is small, documented, and can express things no form would have a field for.
 * Errors are reported by line and the layout is only saved once it parses.
 */
@Composable
private fun LayoutEditorDialog(
    layout: LayoutDef,
    onDismiss: () -> Unit,
    onSaved: (LayoutDef) -> Unit
) {
    var json by remember(layout.id) { mutableStateOf(LayoutJson.writeString(layout)) }
    var error by remember(layout.id) { mutableStateOf<String?>(null) }
    var showSchema by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(layout.name) },
        text = {
            Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState())) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                TextRow(
                    label = "Layout JSON",
                    value = json,
                    singleLine = false,
                    minLines = 14,
                    onChange = {
                        json = it
                        error = null
                    }
                )
                if (showSchema) {
                    Text(
                        LayoutGenerator.SCHEMA,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = runCatching { LayoutJson.parse(json) }
                parsed.onSuccess { updated ->
                    LayoutRepository.save(updated)
                        .onSuccess { onSaved(updated) }
                        .onFailure { error = "Could not save it: ${it.message}" }
                }.onFailure { error = it.message ?: "That JSON could not be read." }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showSchema = !showSchema }) {
                    Text(if (showSchema) "Hide format" else "Format")
                }
                if (LayoutRepository.isUserOwned(layout.id)) {
                    TextButton(onClick = {
                        LayoutRepository.delete(layout.id)
                        onDismiss()
                    }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun DescribeLayoutDialog(
    settings: Settings,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Describe a layout") },
        text = {
            Column {
                Text(
                    "Say what the keys should be and how they should behave. Mention " +
                        "long-press alternates, swipes, modifiers, indicator lamps — " +
                        "anything the format supports.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextRow(
                    label = "Description",
                    value = text,
                    placeholder = "A Polish programmer's keyboard: QWERTY, a number row, " +
                        "Ctrl/Alt/Tab/Esc on the bottom row with lamps showing when they are " +
                        "held, swipe up on a letter for its digit, long-press for Polish " +
                        "accents, and a key that opens the clipboard.",
                    singleLine = false,
                    minLines = 5,
                    onChange = onTextChange
                )
                if (!settings.aiEnabled) {
                    Text(
                        "AI is off — switch it on under Settings → AI first.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = settings.aiEnabled && text.isNotBlank(),
                onClick = onGenerate
            ) { Text("Write it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
