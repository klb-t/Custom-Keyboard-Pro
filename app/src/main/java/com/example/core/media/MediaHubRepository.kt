package com.example.core.media

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class MediaListing(val entries: List<MediaEntry>, val notices: List<String> = emptyList())

/**
 * One adapter for every installed DocumentsProvider. There is no provider-name
 * switch and no storage-wide permission. All queries stay within user grants.
 */
class MediaHubRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("media_hub_profiles", Context.MODE_PRIVATE)
    private val builtInViews: List<MediaViewProfile> by lazy {
        runCatching {
            val root = JSONObject(app.assets.open("media_view_profiles.json").bufferedReader().use { it.readText() })
            val profiles = root.getJSONArray("profiles")
            (0 until profiles.length()).map { MediaProfileCodec.viewFromJson(profiles.getJSONObject(it).toString()) }
        }.getOrDefault(MediaViewProfile.defaults)
    }

    fun viewProfiles(): List<MediaViewProfile> = builtInViews

    fun sources(): List<MediaSourceProfile> =
        MediaProfileCodec.sourcesFromJson(prefs.getString("sources", "").orEmpty())

    fun saveSources(sources: List<MediaSourceProfile>) {
        prefs.edit().putString("sources", MediaProfileCodec.sourcesToJson(sources)).apply()
    }

    fun view(settings: Settings = SettingsStore.current): MediaViewProfile {
        val custom = MediaViewProfile("custom", "Custom",
            settings.mediaMimePatterns.split(',').map(String::trim), settings.mediaShowHidden,
            settings.mediaShowFolders, runCatching { MediaSort.valueOf(settings.mediaSort) }.getOrDefault(MediaSort.NAME))
        return builtInViews.firstOrNull { it.copy(id = "custom", label = "Custom") == custom } ?: custom
    }

    fun saveView(view: MediaViewProfile): Result<Unit> = SettingsStore.update {
        it.copy(mediaMimePatterns = view.mimePatterns.joinToString(","), mediaShowHidden = view.showHidden,
            mediaShowFolders = view.showFolders, mediaSort = view.sort.name)
    }

    var entryLimit: Int
        get() = SettingsStore.current.mediaEntryLimit.coerceIn(50, 5000)
        set(value) { SettingsStore.setByKey("mediaEntryLimit", value.coerceIn(50, 5000)) }

    var providerTimeoutMs: Int
        get() = SettingsStore.current.mediaProviderTimeoutMs.coerceIn(1000, 120000)
        set(value) { SettingsStore.setByKey("mediaProviderTimeoutMs", value.coerceIn(1000, 120000)) }

    fun hasGrant(source: MediaSourceProfile): Boolean = resolver.persistedUriPermissions.any {
        it.uri.toString() == source.uri && it.isReadPermission
    }

    /** Only the read permission returned by the picker is persisted. */
    suspend fun add(uri: Uri, resultFlags: Int, kind: MediaSourceKind): MediaSourceProfile = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "The provider did not return a content URI." }
        require(kind != MediaSourceKind.FOLDER || DocumentsContract.isTreeUri(uri)) {
            "This provider did not return a folder. Use Add files instead."
        }
        val read = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        require(read != 0) { "The provider did not grant read access. Please select the item again." }
        resolver.takePersistableUriPermission(uri, read)
        val provisional = MediaSourceProfile(uri.toString(), uri.authority ?: "Source", kind)
        // Some cloud providers are temporarily offline immediately after selection.
        // Keep the actual persisted grant even if fetching its display name fails.
        val label = try { queryOne(provisional, rootDocument(provisional)).name }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { provisional.label }
        val existing = sources()
        val source = existing.firstOrNull { it.uri == provisional.uri } ?: provisional.copy(label = label)
        saveSources(existing.filterNot { it.uri == source.uri } + source)
        source
    }

    /** Removing a profile releases its exact read grant; it never deletes a document. */
    suspend fun remove(source: MediaSourceProfile) = withContext(Dispatchers.IO) {
        if (hasGrant(source)) resolver.releasePersistableUriPermission(Uri.parse(source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        saveSources(sources().filterNot { it.uri == source.uri })
    }

    fun rootDocument(source: MediaSourceProfile): Uri {
        val uri = Uri.parse(source.uri)
        return if (source.kind == MediaSourceKind.FOLDER)
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    }

    suspend fun list(source: MediaSourceProfile, folder: String? = null): MediaListing = withContext(Dispatchers.IO) {
        check(hasGrant(source)) { "Access was revoked or was not restored on this device. Select this source again." }
        if (source.kind == MediaSourceKind.DOCUMENT) {
            return@withContext MediaListing(listOf(queryOne(source, rootDocument(source))))
        }
        val tree = Uri.parse(source.uri)
        val parent = folder?.let(Uri::parse) ?: rootDocument(source)
        require(parent.authority == tree.authority && DocumentsContract.isTreeUri(parent) &&
            DocumentsContract.getTreeDocumentId(parent) == DocumentsContract.getTreeDocumentId(tree)) {
            "The folder is outside the selected source."
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        query(children) { cursor ->
            val entries = mutableListOf<MediaEntry>()
            while (entries.size < entryLimit && cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val documentId = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                entries += cursor.entry(source, DocumentsContract.buildDocumentUriUsingTree(tree, documentId))
            }
            val notices = mutableListOf<String>()
            if (entries.size >= entryLimit && cursor.moveToNext()) notices +=
                "Showing the first $entryLimit entries from ${source.label}. Increase the limit in View options."
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) notices +=
                "${source.label} is still loading from its provider. Refresh to fetch the rest."
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR)?.takeIf { it.isNotBlank() }?.let {
                notices += "${source.label}: $it"
            }
            MediaListing(entries, notices)
        }
    }

    private suspend fun queryOne(source: MediaSourceProfile, uri: Uri): MediaEntry = query(uri) { cursor ->
        check(cursor.moveToFirst()) { "The item is unavailable. It may have been moved or deleted." }
        cursor.entry(source, uri)
    }

    /** Cancellation reaches a cloud provider instead of leaving abandoned queries running. */
    private suspend fun <T> query(uri: Uri, read: suspend (Cursor) -> T): T = try {
        withTimeout(providerTimeoutMs.toLong()) { queryWithCancellation(uri, read) }
    } catch (timeout: TimeoutCancellationException) {
        coroutineContext.ensureActive()
        error("The provider did not respond within ${providerTimeoutMs / 1000} seconds. Check its connection or increase the timeout in View options.")
    }

    private suspend fun <T> queryWithCancellation(uri: Uri, read: suspend (Cursor) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            val cursor = resolver.query(uri, null, null, null, null, signal)
                ?: error("The provider is unavailable. Check its app, account and connection, then refresh.")
            cursor.use { read(it) }
        } finally { cancellation.cancel() }
    }

    private fun Cursor.entry(source: MediaSourceProfile, uri: Uri): MediaEntry {
        val mime = string(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
        val flags = number(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0
        return MediaEntry(
            uri = uri.toString(), sourceUri = source.uri, sourceLabel = source.label,
            name = string(OpenableColumns.DISPLAY_NAME) ?: "Unnamed document", mime = mime,
            directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            virtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
            size = number(OpenableColumns.SIZE)?.takeIf { it >= 0 },
            modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }
        )
    }

    private fun Cursor.string(column: String): String? = getColumnIndex(column).let {
        if (it >= 0 && !isNull(it)) getString(it) else null
    }
    private fun Cursor.number(column: String): Long? = getColumnIndex(column).let {
        if (it >= 0 && !isNull(it)) getLong(it) else null
    }

    companion object {
        fun picker(kind: MediaSourceKind): Intent = Intent(
            if (kind == MediaSourceKind.FOLDER) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (kind == MediaSourceKind.FOLDER) addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            else { type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
            // No CATEGORY_OPENABLE: virtual documents must remain discoverable.
        }

        fun clip(entry: MediaEntry): ClipData {
            require(!entry.directory && !entry.virtual) { "Only ordinary files can be copied directly." }
            return ClipData(ClipDescription(entry.name, arrayOf(entry.mime)), ClipData.Item(Uri.parse(entry.uri)))
        }

        fun openIntent(entry: MediaEntry): Intent {
            require(!entry.directory) { "Browse folders inside the media hub." }
            return Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), entry.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(entry.name, Uri.parse(entry.uri)) }
        }

        fun shareIntent(entry: MediaEntry): Intent = Intent(Intent.ACTION_SEND).apply {
            require(!entry.directory && !entry.virtual) {
                "Open virtual documents in their provider app and export a file before sharing."
            }
            type = entry.mime
            putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
            clipData = clip(entry)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
