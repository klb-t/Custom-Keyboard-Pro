package com.example.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingsStore
import com.example.core.data.BundledDictionary
import com.example.core.data.KeyboardRepository
import com.example.core.data.WordLists
import com.example.core.data.ShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the keyboard has learned, and the expansions the user has defined.
 *
 * Shown in full and editable because a learned dictionary that you cannot inspect is
 * a black box that quietly shapes everything you write.
 */
@Composable
fun DictionaryScreen() {
    val context = LocalContext.current
    val repository = remember { KeyboardRepository.get(context) }
    val scope = rememberCoroutineScope()

    val words by repository.observeDictionary().collectAsState(initial = emptyList())
    val shortcuts by repository.observeShortcuts().collectAsState(initial = emptyList())

    var newWord by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var listStatus by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val settingsNow by SettingsStore.state.collectAsState()

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importStatus = "Reading…"
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("That file could not be read.")
                repository.importWords(text)
            }
            importStatus = result.fold(
                onSuccess = { "Imported $it words." },
                onFailure = { "Could not import: ${it.message}" }
            )
        }
    }
    var shortcutKey by remember { mutableStateOf("") }
    var shortcutValue by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(onClick = { tab = 0 }) {
                Text(if (tab == 0) "• Words (${words.size})" else "Words (${words.size})")
            }
            TextButton(onClick = { tab = 1 }) {
                Text(if (tab == 1) "• Shortcuts (${shortcuts.size})" else "Shortcuts (${shortcuts.size})")
            }
        }

        if (tab == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newWord,
                    onValueChange = { newWord = it },
                    label = { Text("Add a word") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val word = newWord.trim()
                        if (word.isNotEmpty()) {
                            scope.launch { repository.addWord(word) }
                            newWord = ""
                        }
                    }
                ) { Text("Add") }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { importPicker.launch("*/*") }) {
                    Text("Import a word list")
                }
                TextButton(onClick = { scope.launch { repository.clearLearnedWords() } }) {
                    Text("Forget everything learned")
                }
            }
            importStatus?.let { InfoRow(it) }

            // The base lists, which are a different thing from the words this screen
            // lists: these are not learned, are not the user's, and forgetting does not
            // touch them. Shown here anyway because this is where somebody goes when
            // the suggestions are wrong, and "there are only 575 words" is the answer
            // they need rather than a setting buried elsewhere.
            val locale = remember { java.util.Locale.getDefault().language }
            val languages = remember(settingsNow.wordListSourcesJson) {
                (BundledDictionary.BUNDLED + locale +
                    WordLists.all(settingsNow).map { it.language }).distinct()
            }
            InfoRow(
                "Base word lists, separate from the words above: built in for " +
                    "English and Polish so suggestions and correction work offline on " +
                    "day one, and replaceable for any language. Import accepts one word " +
                    "per line, optionally followed by a frequency count, which is the " +
                    "format published word lists already use."
            )
            languages.forEach { language ->
                val replaced = WordLists.isReplaced(context, language)
                val count = BundledDictionary.size(context, language)
                val sources = WordLists.forLanguage(language, settingsNow)
                if (count > 0 || sources.isNotEmpty()) {
                    InfoRow(
                        buildString {
                            append(language.uppercase())
                            append(": ")
                            append(if (count > 0) "$count words" else "nothing installed")
                            if (replaced) append(" (downloaded)")
                        }
                    )
                }
                sources.forEach { source ->
                    TextButton(
                        onClick = {
                            if (downloading == null) {
                                downloading = source.id
                                listStatus = null
                                scope.launch {
                                    WordLists.download(context, source).fold(
                                        onSuccess = { n -> listStatus = "${source.label}: $n words installed." },
                                        onFailure = { listStatus = "${source.label}: ${it.message}" }
                                    )
                                    downloading = null
                                }
                            }
                        }
                    ) {
                        Text(
                            if (downloading == source.id) "Downloading ${source.label}…"
                            else "Download ${source.label}" +
                                (if (source.words > 0) " (~${source.words / 1000}k)" else "")
                        )
                    }
                    if (source.licence.isNotBlank()) InfoRow(source.licence)
                }
                if (replaced) {
                    TextButton(
                        onClick = {
                            WordLists.revert(context, language)
                            listStatus = "$language: back to the list in the app."
                        }
                    ) { Text("Use the built-in $language list again") }
                }
            }
            listStatus?.let { InfoRow(it) }
            if (words.isEmpty()) {
                InfoRow("Nothing learned yet. Words appear here as you type them.")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(words, key = { it.id }) { word ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(word.word, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append("used ${word.count}×")
                                    if (word.locked) append(" · added by you")
                                    if (word.blocked) append(" · blocked")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { scope.launch { repository.blockWord(word.word) } }) {
                            Text("Block")
                        }
                        TextButton(onClick = { scope.launch { repository.deleteWord(word.word) } }) {
                            Text("Delete")
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = shortcutKey,
                    onValueChange = { shortcutKey = it },
                    label = { Text("Type this…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = shortcutValue,
                    onValueChange = { shortcutValue = it },
                    label = { Text("…to get this") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        val key = shortcutKey.trim()
                        if (key.isNotEmpty() && shortcutValue.isNotEmpty()) {
                            scope.launch {
                                repository.saveShortcut(
                                    ShortcutEntity(shortcut = key, expansion = shortcutValue)
                                )
                            }
                            shortcutKey = ""
                            shortcutValue = ""
                        }
                    }
                ) { Text("Save shortcut") }
            }
            InfoRow(
                "Shortcuts appear in the suggestion strip as soon as you finish typing " +
                    "the trigger, so they never fire by surprise."
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shortcuts, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.shortcut, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                item.expansion.take(90),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { scope.launch { repository.deleteShortcut(item.id) } }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
