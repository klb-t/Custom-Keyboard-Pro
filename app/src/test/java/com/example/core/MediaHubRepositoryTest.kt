package com.example.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.SettingsStore
import com.example.core.media.MediaHubRepository
import com.example.core.media.MediaSourceKind
import com.example.core.media.MediaSourceProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaHubRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tree = Uri.parse("content://media-test/tree/root")
    private val source = MediaSourceProfile(tree.toString(), "Test source", MediaSourceKind.FOLDER)
    private lateinit var repository: MediaHubRepository
    private lateinit var provider: MediaTestProvider

    @Before fun prepare() {
        context.getSharedPreferences("media_hub_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        provider = MediaTestProvider()
        ShadowContentResolver.registerProviderInternal("media-test", provider)
        repository = MediaHubRepository(context)
        SettingsStore.update { it.copy(mediaEntryLimit = 50, mediaProviderTimeoutMs = 15000) }.getOrThrow()
    }

    @Test fun `missing grants are visible failures without querying private content`() = runBlocking {
        val result = runCatching { repository.list(source) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("revoked"))
        assertEquals(0, provider.queryCount)
    }

    @Test fun `provider metadata and null sizes survive the shared adapter`() = runBlocking {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = repository.list(source)
        assertEquals(3, result.entries.size)
        assertTrue(result.entries.first { it.name == "Folder" }.directory)
        assertNull(result.entries.first { it.name == "Cloud photo" }.size)
        assertTrue(result.entries.first { it.name == "Drawing" }.virtual)
        assertTrue(result.entries.all { it.uri.startsWith("content://media-test/tree/root/document/") })
    }

    @Test fun `provider result limits report truncation rather than claiming complete search`() = runBlocking {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider.extraRows = 60
        val result = repository.list(source)
        assertEquals(50, result.entries.size)
        assertTrue(result.notices.single().contains("first 50"))
    }

    @Test fun `a saved profile alone cannot authorize a different tree`() = runBlocking {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = runCatching { repository.list(source, "content://media-test/tree/other/document/secret") }
        assertTrue(result.isFailure)
        assertEquals(0, provider.queryCount)
    }

    @Test fun `persisting a picker source retains its name and survives repository recreation`() = runBlocking {
        val added = repository.add(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION, MediaSourceKind.FOLDER)
        assertEquals("Root folder", added.label)
        assertTrue(repository.hasGrant(added))
        assertEquals(listOf(added), MediaHubRepository(context).sources())
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(repository.hasGrant(added))
        assertTrue(runCatching { repository.list(added) }.isFailure)
    }

    @Test fun `noncontent selections cannot be stored as authorized files`() = runBlocking {
        val result = runCatching { repository.add(Uri.parse("file:///private"), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            MediaSourceKind.DOCUMENT) }
        assertTrue(result.isFailure)
        assertTrue(repository.sources().isEmpty())
    }

    @Test fun `removing a source revokes its grant without deleting remote content`() = runBlocking {
        val added = repository.add(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION, MediaSourceKind.FOLDER)
        repository.remove(added)
        assertFalse(repository.hasGrant(added))
        assertTrue(repository.sources().isEmpty())
        assertEquals(0, provider.deleteCount)
    }

    @Test fun `built in views are loadable provider independent profile data`() {
        val views = repository.viewProfiles()
        assertTrue(views.any { it.id == "images" && it.mimePatterns == listOf("image/*") })
        assertTrue(views.any { it.id == "archives" })
        assertEquals(views.size, views.map { it.id }.distinct().size)
    }
}

private class MediaTestProvider : ContentProvider() {
    var queryCount = 0
    var extraRows = 0
    var deleteCount = 0
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor {
        queryCount++
        return MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)).apply {
            if (uri.lastPathSegment == "children") {
                addRow(arrayOf<Any?>("folder", "Folder", Document.MIME_TYPE_DIR, null, null, 0))
                addRow(arrayOf<Any?>("photo", "Cloud photo", "image/jpeg", null, 1000L, 0))
                addRow(arrayOf<Any?>("virtual", "Drawing", "application/test", null, null, Document.FLAG_VIRTUAL_DOCUMENT))
                repeat(extraRows) { addRow(arrayOf<Any?>("item-$it", "Item $it", "text/plain", 0L, null, 0)) }
            } else addRow(arrayOf<Any?>("root", "Root folder", Document.MIME_TYPE_DIR, null, null, 0))
        }
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleteCount++; return 0 }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
