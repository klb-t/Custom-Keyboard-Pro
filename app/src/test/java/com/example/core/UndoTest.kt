package com.example.core

import android.content.Context
import android.text.Selection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.layout.TextUnit
import com.example.ime.EditorController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Undo the keyboard does itself.
 *
 * Ctrl+Z is a request rather than an API — there is no `InputConnection` call for
 * undo — so a keyboard can only send the chord and hope. Plenty of modern text fields
 * implement paste and never implement that chord, which is why Ctrl+V worked in this
 * keyboard and Ctrl+Z did nothing at all.
 *
 * Driven through a real [EditText] and a real input connection, because the thing most
 * worth proving is what happens when the text is *not* how the keyboard left it: a
 * stub that echoed our own writes back could never be in that state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Conventions off, so the test is about the history and not about smart quotes. */
    private val plain = Settings(
        autoCapitalize = false,
        autoSpaceAfterPunctuation = false,
        doubleSpacePeriod = false,
        smartQuotes = false
    )

    private inner class Field(text: String = "", cursor: Int = 0) {
        val view = EditText(context).apply {
            setText(text)
            setSelection(cursor)
        }
        val connection = requireNotNull(view.onCreateInputConnection(EditorInfo())) {
            "the editor gave no input connection — the test cannot drive anything"
        }
        val editor = EditorController(
            connection = { connection },
            editorInfo = { EditorInfo() },
            settings = { plain }
        )

        init { sync() }

        fun sync() = editor.onSelectionUpdate(
            Selection.getSelectionStart(view.text),
            Selection.getSelectionEnd(view.text)
        )

        fun type(s: String) = s.forEach { editor.commitText(it.toString()); sync() }

        val text: String get() = view.text.toString()
    }

    @Test
    fun `undo puts back what the keyboard typed`() {
        val f = Field()
        f.type("hello")
        assertEquals("hello", f.text)
        assertTrue(f.editor.undo())
        assertEquals("", f.text)
    }

    @Test
    fun `a run of letters undoes as one word, not one keystroke at a time`() {
        // Otherwise undoing "Hello there" is eleven taps, which is not what anybody
        // means by undo.
        val f = Field()
        f.type("hello world")
        assertTrue(f.editor.undo())
        assertEquals("hello", f.text)
        assertTrue(f.editor.undo())
        assertEquals("", f.text)
    }

    @Test
    fun `redo puts it back again`() {
        val f = Field()
        f.type("hello")
        f.editor.undo()
        assertEquals("", f.text)
        assertTrue(f.editor.redo())
        assertEquals("hello", f.text)
    }

    @Test
    fun `typing again makes the redo path unreachable`() {
        val f = Field()
        f.type("hello")
        f.editor.undo()
        f.type("other")
        assertFalse(f.editor.canRedo)
    }

    @Test
    fun `undo restores a backspace`() {
        val f = Field()
        f.type("cat")
        f.editor.backspace(TextUnit.CHARACTER)
        f.sync()
        assertEquals("ca", f.text)
        assertTrue(f.editor.undo())
        assertEquals("cat", f.text)
    }

    @Test
    fun `undo restores a deleted word`() {
        val f = Field()
        f.type("alpha beta")
        f.editor.backspace(TextUnit.WORD)
        f.sync()
        assertEquals("alpha ", f.text)
        assertTrue(f.editor.undo())
        assertEquals("alpha beta", f.text)
    }

    @Test
    fun `undo restores a forward delete without moving the cursor past it`() {
        val f = Field("keyboard", cursor = 3)
        f.editor.forwardDelete(TextUnit.CHARACTER)
        f.sync()
        assertEquals("keyoard", f.text)
        assertTrue(f.editor.undo())
        assertEquals("keyboard", f.text)
        // The cursor belongs in front of what came back, where it was.
        assertEquals(3, Selection.getSelectionStart(f.view.text))
    }

    @Test
    fun `a batched rewrite is one edit, because a correction is one thing`() {
        // What auto-correction does: delete the word just finished, commit a better
        // one. Two operations, one intention, and it must take one press to undo.
        val f = Field()
        f.type("teh ")
        f.editor.batch {
            f.editor.deleteExactly(4)
            f.editor.commitText("the ", applyConventions = false)
        }
        f.sync()
        assertEquals("the ", f.text)
        assertTrue(f.editor.undo())
        assertEquals("teh ", f.text)
    }

    @Test
    fun `an accepted suggestion undoes in one press`() {
        val f = Field()
        f.type("keyb")
        f.editor.replaceCurrentWord("keyboard")
        f.sync()
        assertEquals("keyboard ", f.text)
        assertTrue(f.editor.undo())
        assertEquals("keyb", f.text)
    }

    // -----------------------------------------------------------------------
    // The part that matters: refusing to act on text that is no longer ours
    // -----------------------------------------------------------------------

    @Test
    fun `text changed by somebody else is never rewritten`() {
        // The app's own autocomplete, a paste, another keyboard. Our entries describe
        // positions that have moved, so acting on them would corrupt the field rather
        // than fail. It hands over to the chord instead and reports that it did
        // nothing itself.
        val f = Field()
        f.type("hello")
        f.view.setText("something else entirely")
        f.view.setSelection(f.view.text.length)
        f.sync()

        assertFalse(f.editor.undo())
        // The claim is that the keyboard did not act, not what the chord it handed
        // over to may or may not do inside a test harness.
        assertFalse("a stale edit was applied to text it did not describe",
            f.text.contains("hello"))
    }

    @Test
    fun `a history that went stale is dropped rather than half-trusted`() {
        val f = Field()
        f.type("hello")
        f.view.setText("interference")
        f.view.setSelection(f.view.text.length)
        f.sync()
        f.editor.undo()
        assertFalse(f.editor.canUndo)
        assertFalse(f.editor.canRedo)
    }

    @Test
    fun `nothing to undo reports that it did nothing`() {
        val f = Field()
        assertFalse(f.editor.undo())
        assertFalse(f.editor.canUndo)
    }

    @Test
    fun `a new field starts a new history`() {
        // Carrying edits across would offer to put text back into somewhere it was
        // never taken from.
        val f = Field()
        f.type("hello")
        assertTrue(f.editor.canUndo)
        f.editor.seedSelection(EditorInfo().apply { initialSelStart = 0; initialSelEnd = 0 })
        assertFalse(f.editor.canUndo)
    }

    @Test
    fun `undoing back to the start leaves redo working`() {
        // The fallback sends a key event, and sending one normally throws the history
        // away — which would take the redo stack with it.
        val f = Field()
        f.type("hi")
        assertTrue(f.editor.undo())
        assertFalse(f.editor.undo())
        assertTrue(f.editor.canRedo)
        assertTrue(f.editor.redo())
        assertEquals("hi", f.text)
    }
}
