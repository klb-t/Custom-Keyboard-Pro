package com.example.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.example.core.clipboard.ClipStore
import com.example.core.config.SettingsStore
import com.example.core.data.ClipboardEntity
import com.example.core.data.KeyboardRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-chosen/share-sheet image ingress; never watches the photo library. */
class ClipboardImportActivity : ComponentActivity() {
    private var images by mutableStateOf<List<Uri>>(emptyList())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("Choose a screenshot or picture to add to clipboard history.")
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { result ->
        images = result.filter { it.scheme == "content" }.distinct().take(20)
        if (images.isEmpty()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        images = incomingImages(intent)
        setContent { MyApplicationTheme {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add to clipboard", style = MaterialTheme.typography.headlineSmall)
                Text(message)
                Text("${images.size} selected image(s). The bytes are copied into this app, so they remain usable after the original share permission expires. Nothing is sent to a model.")
                Button(enabled = !busy && images.isNotEmpty(), onClick = ::importImages) { Text(if (busy) "Importing…" else "Add selected images") }
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose images / screenshots") }
                TextButton(onClick = { finish() }) { Text("Close") }
            }
        } }
        if (savedInstanceState == null && intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            picker.launch(arrayOf("image/*"))
        }
    }

    private fun importImages() {
        val selected = images.toList()
        val settings = SettingsStore.current
        if (!settings.clipboardEnabled || !settings.clipboardKeepFiles) {
            message = "Enable clipboard history and Keep file copies in expert settings before importing images."
            return
        }
        busy = true
        lifecycleScope.launch {
            var saved = 0
            try {
                withContext(Dispatchers.IO) {
                    val repository = KeyboardRepository(this@ClipboardImportActivity)
                    selected.forEach { uri ->
                        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
                        if (!mime.startsWith("image/")) return@forEach
                        val file = ClipStore.capture(this@ClipboardImportActivity, uri, settings.clipboardMaxFileMb.coerceAtLeast(0) * 1_000_000L)
                            ?: return@forEach
                        repository.rememberClipEntry(ClipboardEntity(type = ClipboardEntity.TYPE_FILE,
                            content = "Imported image", label = "Image / screenshot", mime = mime,
                            filePath = file.absolutePath, sizeBytes = file.length()), settings.clipboardMaxItems)
                        saved++
                    }
                }
                message = "Added $saved of ${selected.size} images. Return to the keyboard and open Clipboard. Unreadable or oversized files were skipped."
                images = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Added $saved images; the remaining import could not finish. Check file access and storage." }
            finally { busy = false }
        }
    }

    companion object {
        internal fun incomingImages(intent: Intent): List<Uri> {
            val result = mutableListOf<Uri>()
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(result::add)
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(result::addAll)
            }
            if (intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
                intent.clipData?.let { clip -> (0 until minOf(clip.itemCount, 20)).forEach { i -> clip.getItemAt(i).uri?.let(result::add) } }
            }
            return result.filter { it.scheme == "content" }.distinct().take(20)
        }
    }
}
