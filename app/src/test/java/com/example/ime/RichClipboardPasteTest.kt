package com.example.ime

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.example.core.config.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichClipboardPasteTest {
    private class Connection(var accepts: Boolean = true) : BaseInputConnection(null, false) {
        var content: InputContentInfo? = null
        var flags = 0
        var nativePaste = 0
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            content = inputContentInfo; this.flags = flags; return accepts
        }
        override fun performContextMenuAction(id: Int): Boolean { if (id == android.R.id.paste) nativePaste++; return false }
    }
    private val uri = Uri.parse("content://test.files/clips/screenshot.png")
    private fun clip() = ClipData(ClipDescription("Screenshot", arrayOf("image/png")), ClipData.Item(uri))
    private fun editor(connection: Connection, vararg mime: String, nativeFallback: Boolean = false) = EditorController({ connection },
        { EditorInfo().apply { contentMimeTypes = arrayOf(*mime) } }, { Settings(clipboardNativeFileFallback = nativeFallback) })

    @Test fun `image goes to an advertised rich editor with a temporary read grant`() {
        val connection = Connection()
        assertTrue(editor(connection, "image/*").pasteClip(clip()))
        assertEquals(uri, connection.content!!.contentUri)
        assertEquals(InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, connection.flags)
        assertEquals(0, connection.nativePaste)
    }
    @Test fun `unsupported mime and rejected rich content cannot turn an image into URI text`() {
        val unsupported = Connection()
        assertFalse(editor(unsupported, "text/plain").pasteClip(clip()))
        assertNull(unsupported.content); assertEquals(0, unsupported.nativePaste)
        val rejected = Connection(false)
        assertFalse(editor(rejected, "image/png").pasteClip(clip()))
        assertNotNull(rejected.content); assertEquals(0, rejected.nativePaste)
    }
    @Test fun `text and summary first multi item clips never become an arbitrary image`() {
        val connection = Connection()
        val combined = ClipData.newPlainText("Summary", "Two items")
        combined.addItem(ClipData.Item(uri))
        assertFalse(editor(connection, "image/*").pasteClip(combined))
        assertNull(connection.content); assertEquals(1, connection.nativePaste)
    }
    @Test fun `native file paste fallback is an explicit expert policy`() {
        val connection = Connection(false)
        assertFalse(editor(connection, "image/png", nativeFallback = true).pasteClip(clip()))
        assertNotNull(connection.content)
        assertEquals(1, connection.nativePaste)
    }

}
