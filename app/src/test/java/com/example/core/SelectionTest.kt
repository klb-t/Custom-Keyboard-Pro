package com.example.core

import android.content.Context
import android.text.Selection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.layout.CursorDirection
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
 * Moving the cursor, and holding on to where a selection started.
 *
 * Driven through a real [EditText] and a real input connection rather than a stub,
 * because the bug this is about lives in the gap between what the keyboard asks for
 * and what the platform reports back. A stub that echoes the request would have
 * agreed with the broken code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** An editor, its connection, and a controller wired the way the service wires one. */
    private class Field(context: Context, text: String, cursor: Int) {
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
            settings = { Settings() }
        )

        init {
            // What the platform does after every change: tell the keyboard where the
            // cursor ended up. Half of what is being tested is how the controller
            // survives that round trip.
            sync()
        }

        fun sync() {
            editor.onSelectionUpdate(
                Selection.getSelectionStart(view.text),
                Selection.getSelectionEnd(view.text)
            )
        }

        fun left(extend: Boolean = true, unit: TextUnit = TextUnit.CHARACTER) {
            editor.moveCursor(CursorDirection.LEFT, unit, extend)
            sync()
        }

        fun right(extend: Boolean = true, unit: TextUnit = TextUnit.CHARACTER) {
            editor.moveCursor(CursorDirection.RIGHT, unit, extend)
            sync()
        }

        /** The selection as the user sees it, lowest offset first. */
        val range: IntRange
            get() {
                val a = Selection.getSelectionStart(view.text)
                val b = Selection.getSelectionEnd(view.text)
                return minOf(a, b)..maxOf(a, b)
            }

        val selected: String get() = view.text.substring(range.first, range.last)
    }

    private fun field(text: String, cursor: Int) = Field(context, text, cursor)

    @Test
    fun `extending left twice selects two characters, not one`() {
        // The bug. After the first press the platform reports the range normalised,
        // so re-deriving the anchor from it read the left edge as the anchor and
        // walked the right edge inwards — the second press shrank what the first
        // had grown, and the selection never got past one character.
        val f = field("Hello world", cursor = 11)
        f.left()
        assertEquals("d", f.selected)
        f.left()
        assertEquals("ld", f.selected)
        f.left()
        assertEquals("rld", f.selected)
    }

    @Test
    fun `extending right twice selects two characters`() {
        val f = field("Hello world", cursor = 0)
        f.right()
        f.right()
        assertEquals("He", f.selected)
    }

    @Test
    fun `the caret turns round without dragging the anchor with it`() {
        // Select three to the right, then come back one. The anchor stays where the
        // run began, so what is left selected is the first two.
        val f = field("Hello world", cursor = 0)
        f.right(); f.right(); f.right()
        assertEquals("Hel", f.selected)
        f.left()
        assertEquals("He", f.selected)
    }

    @Test
    fun `coming back past the start collapses rather than selecting backwards`() {
        val f = field("Hello world", cursor = 2)
        f.right()
        assertEquals("l", f.selected)
        f.left()
        assertEquals("", f.selected)
        assertEquals(2, f.range.first)
    }

    @Test
    fun `a move without shift ends the run`() {
        val f = field("Hello world", cursor = 5)
        f.left(); f.left()
        assertEquals("lo", f.selected)

        f.right(extend = false)
        assertEquals("", f.selected)

        // The next extend starts a new run from here, and does not leap back to
        // where the previous one was anchored.
        f.left()
        assertEquals(1, f.range.last - f.range.first)
    }

    @Test
    fun `a selection the keyboard did not make ends the run`() {
        val f = field("Hello world", cursor = 11)
        f.left(); f.left()
        assertEquals("ld", f.selected)

        // The user taps somewhere else. The platform reports a position the keyboard
        // never asked for; a stale anchor here would make the next press select
        // everything back to the old spot.
        f.view.setSelection(3)
        f.sync()

        f.left()
        assertEquals("l", f.selected)
    }

    @Test
    fun `extending by word grows word by word`() {
        val f = field("alpha beta gamma", cursor = 16)
        f.left(unit = TextUnit.WORD)
        assertEquals("gamma", f.selected)
        f.left(unit = TextUnit.WORD)
        assertTrue("expected the selection to reach beta, got '${f.selected}'",
            f.selected.contains("beta"))
    }

    // -----------------------------------------------------------------------
    // Where the cursor is when the keyboard opens
    // -----------------------------------------------------------------------

    @Test
    fun `the cursor position is taken from what the editor declared`() {
        val f = field("Hello world", cursor = 0)
        f.editor.seedSelection(EditorInfo().apply {
            initialSelStart = 4
            initialSelEnd = 4
        })
        assertEquals(4, f.editor.selectionStart)
        assertEquals(4, f.editor.selectionEnd)
    }

    @Test
    fun `an editor that declares nothing leaves the position unknown`() {
        // -1 rather than 0. Claiming the cursor is at the start of every field that
        // declines to answer is how the keyboard came to capitalise mid-sentence.
        val f = field("Hello world", cursor = 5)
        f.editor.seedSelection(EditorInfo())
        assertTrue(f.editor.selectionStart < 0)
        f.editor.seedSelection(null)
        assertTrue(f.editor.selectionStart < 0)
    }

    @Test
    fun `only a field that is really empty counts as empty`() {
        val empty = field("", cursor = 0)
        empty.editor.seedSelection(EditorInfo().apply { initialSelStart = 0; initialSelEnd = 0 })
        assertTrue(empty.editor.isKnownEmpty)

        // Cursor at the start of text that exists: the commonest way to open a
        // keyboard on something you mean to edit, and the case that was capitalising.
        val atStart = field("already written", cursor = 0)
        atStart.editor.seedSelection(EditorInfo().apply { initialSelStart = 0; initialSelEnd = 0 })
        assertFalse(atStart.editor.isKnownEmpty)

        val midSentence = field("already written", cursor = 8)
        midSentence.editor.seedSelection(EditorInfo().apply { initialSelStart = 8; initialSelEnd = 8 })
        assertFalse(midSentence.editor.isKnownEmpty)

        // Unknown is not empty either.
        val unknown = field("", cursor = 0)
        unknown.editor.seedSelection(EditorInfo())
        assertFalse(unknown.editor.isKnownEmpty)
    }
}
