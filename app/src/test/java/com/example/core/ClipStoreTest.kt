package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.ClipStore
import com.example.core.data.ClipboardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The parts of the clipboard that can be wrong without a second app being involved:
 * what a captured file is called, what an entry reads as, and whether an entry whose
 * bytes have gone is honest about it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun textEntry(content: String) = ClipboardEntity(
        type = ClipboardEntity.TYPE_TEXT, content = content
    )

    private fun fileEntry(path: String?, mime: String = "image/png", size: Long = 2048) =
        ClipboardEntity(
            type = ClipboardEntity.TYPE_FILE, content = "shot.png",
            mime = mime, filePath = path, sizeBytes = size
        )

    @Test
    fun `an old row still reads as the text it always was`() {
        // The migration adds columns with defaults; nothing already copied changes.
        val old = ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "hello")
        assertTrue(old.isText)
        assertEquals("text/plain", old.mime)
        assertNull(old.filePath)
        assertNull(old.groupId)
    }

    @Test
    fun `a captured file is named after what it actually is`() {
        assertEquals(".png", ClipStore.extensionFor("image/png"))
        assertEquals(".jpg", ClipStore.extensionFor("image/jpeg"))
        assertEquals(".webp", ClipStore.extensionFor("image/webp"))
        assertEquals(".pdf", ClipStore.extensionFor("application/pdf"))
        assertEquals(".mp4", ClipStore.extensionFor("video/mp4"))
        assertEquals(".mp3", ClipStore.extensionFor("audio/mpeg"))
        assertEquals(".svg", ClipStore.extensionFor("image/svg+xml"))
        assertEquals(".txt", ClipStore.extensionFor("text/plain"))
    }

    @Test
    fun `an unknown type falls back to the name, then to something harmless`() {
        assertEquals(
            ".odt",
            ClipStore.extensionFor("application/x-mystery", android.net.Uri.parse("content://x/a.odt"))
        )
        assertEquals(".bin", ClipStore.extensionFor("application/x-mystery"))
        assertEquals(".bin", ClipStore.extensionFor("", android.net.Uri.parse("content://x/12345")))
    }

    @Test
    fun `a type can be guessed from a name when the provider will not say`() {
        assertEquals("image/png", ClipStore.guessMime("content://media/1/screenshot.PNG"))
        assertEquals("application/pdf", ClipStore.guessMime("/a/b/invoice.pdf"))
        assertEquals("application/octet-stream", ClipStore.guessMime("content://media/1/9981"))
    }

    @Test
    fun `an entry whose bytes have gone reports that, rather than pretending`() {
        assertNull(ClipStore.fileFor(fileEntry(null)))
        assertNull(ClipStore.fileFor(fileEntry("")))
        assertNull(ClipStore.fileFor(fileEntry("/no/such/file.png")))
        // And it cannot be handed to another app either, which is the point: a clip
        // that resolves to nothing is worse than no clip at all.
        assertNull(ClipStore.single(context, fileEntry("/no/such/file.png")))
    }

    @Test
    fun `an entry whose bytes are there can be handed over`() {
        val file = File(ClipStore.dir(context), "kept.png").apply { writeBytes(ByteArray(16)) }
        val entry = fileEntry(file.absolutePath)
        assertNotNull(ClipStore.fileFor(entry))

        // Step by step, and with the failure carrying everything needed to tell a
        // misconfigured provider apart from a wrong path. Two rounds were lost to
        // guessing at an exception nobody had read.
        val uri = try {
            ClipStore.shareUri(context, file)
        } catch (e: Exception) {
            throw AssertionError(
                "shareUri could not publish a file it should own.\n" +
                    "  file      = ${file.absolutePath} (exists=${file.exists()})\n" +
                    "  clips dir = ${ClipStore.dir(context)}\n" +
                    "  filesDir  = ${context.filesDir}\n" +
                    "  canonical = ${file.canonicalPath}\n" +
                    "  package   = ${context.packageName}\n" +
                    "  cause     = $e"
            )
        }
        assertEquals("content", uri.scheme)

        val clip = ClipStore.single(context, entry)
        assertNotNull("single() could not build a clip for a file that exists", clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(uri, clip.getItemAt(0).uri)
        // The type comes from what was recorded at capture, not from asking the
        // provider again — so it is right even where the provider will not answer.
        assertTrue(clip.description.hasMimeType("image/png"))
        file.delete()
    }

    @Test
    fun `text is always handed over as text`() {
        val clip = ClipStore.single(context, textEntry("ala ma kota"))
        assertNotNull(clip)
        assertEquals("ala ma kota", clip!!.getItemAt(0).text)
    }

    @Test
    fun `a composite leads with a summary so a one-item app still gets something`() {
        val file = File(ClipStore.dir(context), "part.png").apply { writeBytes(ByteArray(16)) }
        val clip = ClipStore.compose(
            context,
            listOf(textEntry("first"), textEntry("second"), fileEntry(file.absolutePath))
        )
        assertNotNull(clip)
        clip!!
        // Most apps read item zero and nothing else. Leading with the summary means
        // they get the whole thing rather than an arbitrary fragment.
        val summary = clip.getItemAt(0).text.toString()
        assertTrue(summary.contains("first"))
        assertTrue(summary.contains("second"))
        // …and an app that reads further gets each part on its own.
        assertEquals(4, clip.itemCount)
        assertEquals("first", clip.getItemAt(1).text)
        assertEquals("second", clip.getItemAt(2).text)
        assertNotNull(clip.getItemAt(3).uri)
        assertTrue(clip.description.hasMimeType("image/png"))
        assertTrue(clip.description.hasMimeType("text/plain"))
        file.delete()
    }

    @Test
    fun `a composite skips a part whose bytes have gone rather than failing whole`() {
        val clip = ClipStore.compose(
            context,
            listOf(textEntry("kept"), fileEntry("/gone/missing.png"))
        )
        assertNotNull(clip)
        // Summary plus the one surviving part. Losing one picture must not cost the
        // user the text they selected alongside it.
        assertEquals(2, clip!!.itemCount)
        assertEquals("kept", clip.getItemAt(1).text)
    }

    @Test
    fun `nothing selected is nothing to paste`() {
        assertNull(ClipStore.compose(context, emptyList()))
    }

    @Test
    fun `a description says what it is, how big, and where from`() {
        val entry = ClipboardEntity(
            type = ClipboardEntity.TYPE_FILE, content = "a.png", mime = "image/png",
            sizeBytes = 2_500_000, sourcePackage = "com.android.chrome"
        )
        val described = ClipStore.describe(entry)
        assertTrue(described.startsWith("Picture"))
        assertTrue(described.contains("MB"))
        assertTrue(described.contains("chrome"))

        assertTrue(ClipStore.describe(entry.copy(mime = "video/mp4")).startsWith("Video"))
        assertTrue(ClipStore.describe(entry.copy(mime = "application/pdf")).startsWith("PDF"))
        assertTrue(ClipStore.describe(entry.copy(mime = "application/zip")).startsWith("File"))
        // No size known means no size claimed.
        assertFalse(ClipStore.describe(entry.copy(sizeBytes = 0)).contains("·  ·"))
    }

    @Test
    fun `sweeping removes what nothing points at and keeps what does`() {
        val kept = File(ClipStore.dir(context), "kept.bin").apply { writeBytes(ByteArray(4)) }
        val orphan = File(ClipStore.dir(context), "orphan.bin").apply { writeBytes(ByteArray(4)) }
        ClipStore.sweepOrphans(context, listOf(kept.absolutePath))
        assertTrue("a file still spoken for was deleted", kept.exists())
        assertFalse("an orphan survived the sweep", orphan.exists())
        kept.delete()
    }
}
