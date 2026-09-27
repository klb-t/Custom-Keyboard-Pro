package com.example.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.media.MediaEntry
import com.example.core.config.SettingsStore
import com.example.core.media.MediaHubRepository
import com.example.core.media.MediaSort
import com.example.core.media.MediaSourceKind
import com.example.core.media.MediaSourceProfile
import com.example.core.media.MediaViewProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Shared local/cloud browser. The system picker owns provider discovery and sign-in. */
@Composable
fun MediaHubScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { MediaHubRepository(context) }
    val settings by SettingsStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val initialSources = remember(repository) { runCatching { repository.sources() } }
    var sources by remember(repository) { mutableStateOf(initialSources.getOrDefault(emptyList())) }
    var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val view = remember(settings) { repository.view(settings) }
    val limit = settings.mediaEntryLimit.coerceIn(50, 5000)
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var notices by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialSources.exceptionOrNull()?.let {
        "Could not read saved source profiles: ${it.message}"
    }) }
    var editedSource by remember { mutableStateOf<MediaSourceProfile?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<MediaEntry?>(null) }

    fun picked(result: androidx.activity.result.ActivityResult, kind: MediaSourceKind) {
        val data = result.data ?: return
        if (result.resultCode != Activity.RESULT_OK) return
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clips -> for (i in 0 until clips.itemCount) clips.getItemAt(i).uri?.let(::add) }
        }.distinct()
        scope.launch {
            importing = true
            val errors = mutableListOf<String>()
            var added = 0
            try {
                for (uri in uris) {
                    try { repository.add(uri, data.flags, kind); added++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { errors += error.message ?: "This provider could not save access." }
                }
                sources = repository.sources()
                message = (listOf("Added $added source(s).") + errors).joinToString("\n")
                refresh++
            } finally { importing = false }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        picked(it, MediaSourceKind.FOLDER)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        picked(it, MediaSourceKind.DOCUMENT)
    }

    fun launchPicker(kind: MediaSourceKind) {
        runCatching {
            if (kind == MediaSourceKind.FOLDER) pickFolder.launch(MediaHubRepository.picker(kind))
            else pickFiles.launch(MediaHubRepository.picker(kind))
        }.onFailure { message = "The system file picker could not open: ${it.message}" }
    }

    fun changeView(next: MediaViewProfile) {
        repository.saveView(next).onFailure { message = "Could not save the view: ${it.message}" }
    }

    fun up() {
        query = ""
        if (path.isNotEmpty()) path = path.dropLast(1) else selectedSource = null
    }
    BackHandler(selectedSource != null) { up() }

    LaunchedEffect(sources, selectedSource, path, refresh, limit, settings.mediaProviderTimeoutMs) {
        loading = true
        entries = emptyList()
        notices = emptyList()
        val found = mutableListOf<MediaEntry>()
        val errors = mutableListOf<String>()
        try {
            val chosen = sources.filter { it.enabled && (selectedSource == null || it.uri == selectedSource) }
            for (source in chosen) {
                try {
                    val result = repository.list(source, path.lastOrNull()?.uri)
                    found += result.entries
                    errors += result.notices
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    errors += "${source.label}: ${error.message ?: "Provider unavailable; try refreshing."}"
                }
                entries = found.distinctBy { it.sourceUri to it.uri }
                notices = errors.toList()
            }
        } finally { loading = false }
    }

    fun fileAction(entry: MediaEntry, share: Boolean) {
        runCatching {
            val intent = if (share) MediaHubRepository.shareIntent(entry) else MediaHubRepository.openIntent(entry)
            context.startActivity(Intent.createChooser(intent, if (share) "Share ${entry.name}" else "Open ${entry.name}"))
        }.onFailure { message = "Could not ${if (share) "share" else "open"} this file: ${it.message}" }
    }

    val visible = remember(entries, view, query) { view.apply(entries, query) }
    val currentSource = sources.firstOrNull { it.uri == selectedSource }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SettingsSection("Media hub", "One view of the folders and files you choose, on this device or in a provider app.") {
                InfoRow("Use the system picker's side menu to choose an installed cloud provider and its account. " +
                    "Providers that cannot expose folders can still supply individual files. Available services depend on their Android apps.")
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { launchPicker(MediaSourceKind.FOLDER) }, enabled = !importing,
                        modifier = Modifier.weight(1f)) { Text("Add folder") }
                    OutlinedButton(onClick = { launchPicker(MediaSourceKind.DOCUMENT) }, enabled = !importing,
                        modifier = Modifier.weight(1f)) { Text("Add files") }
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { InfoRow(it); TextButton(onClick = { message = null }) { Text("Dismiss") } }
            }
        }
        item {
            SettingsSection("Sources", "Selections are remembered across restarts; cloud accounts remain managed by their provider apps.") {
                ActionRow("All enabled sources", "Top-level folder contents and selected files", onClick = {
                    selectedSource = null; path = emptyList(); query = ""
                })
                if (sources.isEmpty()) InfoRow("No sources yet. Add a folder or select files to begin.")
                sources.forEach { source ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            ActionRow(source.label, (if (source.kind == MediaSourceKind.FOLDER) "Folder" else "Selected file") +
                                if (source.enabled) "" else " · disabled", onClick = {
                                selectedSource = source.uri; path = emptyList(); query = ""
                            })
                        }
                        TextButton(onClick = { editedSource = source }) { Text("Edit") }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(currentSource?.label ?: "All enabled sources", style = MaterialTheme.typography.titleMedium)
                if (path.isNotEmpty()) Text(path.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (selectedSource != null) TextButton(onClick = { up() }) { Text("Up") }
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
                    TextButton(onClick = { showOptions = true }) { Text("View options") }
                }
                OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search loaded names, types and sources") }, modifier = Modifier.fillMaxWidth())
                Text("${view.label} · ${visible.size} visible / ${entries.size} loaded. Search covers this level only.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(notices) { notice -> InfoRow(notice) }
        if (!loading && visible.isEmpty()) item {
            InfoRow(if (entries.isEmpty()) "No files are loaded in this view." else "No loaded files match this filter.")
        }
        items(visible, key = { it.sourceUri + "\n" + it.uri }) { entry ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ActionRow(entry.name, metadata(entry), trailing = if (entry.directory) "Open folder" else null, onClick = {
                    if (entry.directory) {
                        if (selectedSource == entry.sourceUri) path = path + entry else {
                            selectedSource = entry.sourceUri; path = listOf(entry)
                        }
                        query = ""
                    } else fileAction(entry, false)
                })
                if (!entry.directory) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { fileAction(entry, true) }, enabled = !entry.virtual) { Text("Share") }
                        TextButton(onClick = {
                            runCatching {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(MediaHubRepository.clip(entry))
                            }.onSuccess { message = "Copied file link. The receiving app must support file pasting." }
                                .onFailure { message = "Could not copy this file: ${it.message}" }
                        }, enabled = !entry.virtual) { Text("Copy file") }
                        TextButton(onClick = { details = entry }) { Text("Details") }
                    }
                    if (entry.virtual) InfoRow("Virtual document: open it in its provider app, then export a file to share or copy.")
                }
            }
        }
        item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }

    editedSource?.let { source ->
        var label by remember(source.uri) { mutableStateOf(source.label) }
        var enabled by remember(source.uri) { mutableStateOf(source.enabled) }
        AlertDialog(onDismissRequest = { editedSource = null }, title = { Text("Source profile") }, text = {
            Column {
                OutlinedTextField(label, onValueChange = { label = it }, label = { Text("Display name") }, singleLine = true)
                SwitchRow("Enabled", checked = enabled, onChange = { enabled = it })
                SelectionContainer { Text(source.uri, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { launchPicker(source.kind) }) { Text("Select again / reconnect") }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            repository.remove(source); sources = repository.sources()
                            if (selectedSource == source.uri) { selectedSource = null; path = emptyList() }
                            editedSource = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = "Could not disconnect this source: ${error.message}" }
                    }
                }) { Text("Remove and revoke access") }
                Text("Disconnects this selection without deleting its files.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = {
                sources = sources.map { if (it.uri == source.uri) it.copy(label = label.trim().ifBlank { source.label }, enabled = enabled) else it }
                repository.saveSources(sources); editedSource = null
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { editedSource = null }) { Text("Cancel") } })
    }

    if (showOptions) Dialog(onDismissRequest = { showOptions = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // This is a full settings form, not AlertDialog text. Bound its viewport
        // explicitly so long MIME patterns and scroll content have stable constraints.
        Card(Modifier.fillMaxWidth().padding(16.dp)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
            shape = MaterialTheme.shapes.extraLarge) {
        Column {
        Text("View profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ChoiceRow("Preset", options = repository.viewProfiles() + listOf(view).filter { current ->
                repository.viewProfiles().none { it == current }
            }, selected = view, optionLabel = { it.label }, onSelect = { changeView(it) })
            OutlinedTextField(view.mimePatterns.joinToString(", "), onValueChange = { text ->
                changeView(view.copy(id = "custom", label = "Custom", mimePatterns = text.split(',').map(String::trim)))
            }, modifier = Modifier.fillMaxWidth(), label = { Text("MIME patterns, separated by commas") },
                supportingText = { Text("Examples: image/*, application/pdf, */*") })
            SwitchRow("Show hidden names", checked = view.showHidden,
                onChange = { changeView(view.copy(id = "custom", label = "Custom", showHidden = it)) })
            SwitchRow("Show folders", checked = view.showFolders,
                onChange = { changeView(view.copy(id = "custom", label = "Custom", showFolders = it)) })
            ChoiceRow("Sort", options = MediaSort.entries, selected = view.sort, optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { changeView(view.copy(id = "custom", label = "Custom", sort = it)) })
            ChoiceRow("Entries per source", "Large cloud folders may load slowly. No background crawl or automatic download.",
                options = listOf(50, 100, 250, 500, 1000, 2500, 5000), selected = limit, optionLabel = Int::toString,
                onSelect = { repository.entryLimit = it })
            ChoiceRow("Provider timeout", "Stops waiting for an offline or unresponsive source.",
                options = listOf(5000, 15000, 30000, 60000, 120000), selected = settings.mediaProviderTimeoutMs,
                optionLabel = { "${it / 1000}s" }, onSelect = { repository.providerTimeoutMs = it })
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showOptions = false }) { Text("Done") }
        }
        }
        }
    }

    details?.let { entry -> AlertDialog(onDismissRequest = { details = null }, title = { Text(entry.name) }, text = {
        SelectionContainer { Text(metadata(entry) + "\n\n" + entry.uri) }
    }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }) }
}

private fun metadata(entry: MediaEntry): String = buildList {
    add(entry.sourceLabel)
    add(if (entry.directory) "Folder" else entry.mime)
    entry.size?.let { size -> add(when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KiB".format(size / 1024.0)
        size < 1024 * 1024 * 1024 -> "%.1f MiB".format(size / 1024.0 / 1024.0)
        else -> "%.1f GiB".format(size / 1024.0 / 1024.0 / 1024.0)
    }) }
    entry.modified?.let { add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
    if (entry.virtual) add("Virtual")
}.joinToString(" · ")
