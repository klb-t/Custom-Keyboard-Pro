package com.example.core

import android.content.Intent
import android.net.Uri
import com.example.core.media.MediaEntry
import com.example.core.media.MediaHubRepository
import com.example.core.media.MediaProfileCodec
import com.example.core.media.MediaSort
import com.example.core.media.MediaSourceKind
import com.example.core.media.MediaSourceProfile
import com.example.core.media.MediaViewProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaProfilesTest {
    private fun entry(name: String, mime: String = "image/jpeg", size: Long? = null, directory: Boolean = false) =
        MediaEntry("content://provider/document/$name", "content://provider/tree/root", "Cloud account",
            name, mime, directory, size = size)

    @Test fun `file types are patterns and folders remain navigable under a media filter`() {
        val view = MediaViewProfile("custom", "Custom", listOf("image/*", "application/pdf"))
        val entries = listOf(entry("video.mp4", "video/mp4"), entry("photo.jpg"), entry("notes.pdf", "application/pdf"),
            entry("Albums", "vnd.android.document/directory", directory = true))
        assertEquals(listOf("Albums", "notes.pdf", "photo.jpg"), view.apply(entries).map { it.name })
        assertFalse(MediaViewProfile.matchesMime("image/*", "imageevil/jpeg"))
        assertTrue(MediaViewProfile.matchesMime("IMAGE/*", "image/jpeg"))
    }

    @Test fun `search matches the loaded metadata and every query term`() {
        val view = MediaViewProfile.defaults.first()
        val entries = listOf(entry("Summer.jpg"), entry("Winter.jpg"))
        assertEquals(listOf("Summer.jpg"), view.apply(entries, "CLOUD summer").map { it.name })
        assertEquals(2, view.apply(entries, "image").size)
        assertTrue(view.apply(entries, "summer winter").isEmpty())
    }

    @Test fun `missing sizes sort last and zero bytes remains a known size`() {
        val entries = listOf(entry("unknown", size = null), entry("empty", size = 0), entry("big", size = 2048))
        assertEquals(listOf("big", "empty", "unknown"),
            MediaViewProfile("large", "Large", sort = MediaSort.LARGEST).apply(entries).map { it.name })
    }

    @Test fun `hidden files and folders follow explicit view choices`() {
        val entries = listOf(entry(".hidden"), entry("visible"), entry("Folder", directory = true))
        val view = MediaViewProfile("test", "Test", showHidden = true, showFolders = false)
        assertEquals(listOf(".hidden", "visible"), view.apply(entries).map { it.name })
    }

    @Test fun `saved source and view profiles preserve user choices`() {
        val sources = listOf(MediaSourceProfile("content://x/tree/one", "My photos", MediaSourceKind.FOLDER, false),
            MediaSourceProfile("content://y/document/two", "Remote notes", MediaSourceKind.DOCUMENT))
        assertEquals(sources, MediaProfileCodec.sourcesFromJson(MediaProfileCodec.sourcesToJson(sources)))
        val view = MediaViewProfile("mine", "My types", listOf("application/pdf", "audio/*"), true, false, MediaSort.NEWEST)
        assertEquals(view, MediaProfileCodec.viewFromJson(MediaProfileCodec.viewToJson(view)))
    }

    @Test fun `source data cannot invent a filesystem grant or duplicate the same URI`() {
        val data = """{"version":1,"sources":[
          {"uri":"file:///etc/private","label":"No","kind":"DOCUMENT"},
          {"uri":"content://x/document/one","label":"One","kind":"DOCUMENT"},
          {"uri":"content://x/document/one","label":"Duplicate","kind":"DOCUMENT"},
          {"uri":"content://x/document/two","label":"Unknown kind","kind":"FTP"}
        ]}"""
        val sources = MediaProfileCodec.sourcesFromJson(data)
        assertEquals(1, sources.size)
        assertEquals("One", sources.single().label)
    }

    @Test fun `picker requests only read access and allows virtual documents`() {
        val intent = MediaHubRepository.picker(MediaSourceKind.DOCUMENT)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, MediaHubRepository.picker(MediaSourceKind.FOLDER).action)
    }

    @Test fun `sharing carries the actual URI and a read-only grant`() {
        val entry = entry("photo.jpg")
        val intent = MediaHubRepository.shareIntent(entry)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(Uri.parse(entry.uri), intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(entry.mime, intent.type)
    }

    @Test fun `virtual files use provider viewing and refuse invalid raw-byte sharing`() {
        val virtual = entry("drawing").copy(virtual = true)
        assertEquals(Intent.ACTION_VIEW, MediaHubRepository.openIntent(virtual).action)
        assertTrue(runCatching { MediaHubRepository.shareIntent(virtual) }.isFailure)
        assertTrue(runCatching { MediaHubRepository.clip(virtual) }.isFailure)
        assertTrue(runCatching { MediaHubRepository.shareIntent(entry("Folder", directory = true)) }.isFailure)
    }
}
