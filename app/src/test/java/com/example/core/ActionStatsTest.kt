package com.example.core

import android.view.KeyEvent
import com.example.core.layout.ClipboardOp
import com.example.core.layout.KeyAction
import com.example.core.predict.ActionCommands
import com.example.core.predict.ActionContext
import com.example.core.predict.ActionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionStatsTest {

    private val terminal = ActionContext("com.termux", "text", selection = false, emptyField = false)
    private val chat = ActionContext("com.chat", "text", selection = false, emptyField = false)
    private val ctrlC = KeyAction.SendKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
    private val undo = KeyAction.Undo

    @Test
    fun `what is used in an app is offered in that app`() {
        val s = ActionStats()
        repeat(3) { s.record(chat, undo, 0) }
        repeat(5) { s.record(terminal, ctrlC, 0) }
        assertEquals(ctrlC, s.suggest(terminal, 1).single())
        assertEquals(undo, s.suggest(chat, 1).single())
    }

    @Test
    fun `defaults show before anything is learned, and give way to use`() {
        val s = ActionStats()
        val withSelection = chat.copy(selection = true)
        val priors = ActionStats.priors(withSelection, clipboardHasText = false)
        assertEquals(KeyAction.Clipboard(ClipboardOp.COPY), s.suggest(withSelection, 1, priors).single())
        repeat(2) { s.record(withSelection, undo, 0) }
        assertEquals(undo, s.suggest(withSelection, 1, priors).single())
    }

    @Test
    fun `an empty field with something copied offers paste, a terminal its keys`() {
        assertEquals(
            KeyAction.Clipboard(ClipboardOp.PASTE),
            ActionStats.priors(chat.copy(emptyField = true), clipboardHasText = true).first()
        )
        assertTrue(ctrlC in ActionStats.priors(terminal, clipboardHasText = false))
    }

    @Test
    fun `typing and cursor keys are not shortcuts`() {
        assertFalse(ActionStats.isShortcut(KeyAction.Text("a")))
        assertFalse(ActionStats.isShortcut(KeyAction.Space))
        assertNull(ActionStats.keyOf(KeyAction.Backspace()))
        assertTrue(ActionStats.isShortcut(ctrlC))
    }

    @Test
    fun `learning survives being saved`() {
        val s = ActionStats()
        repeat(4) { s.record(terminal, ctrlC, 5) }
        val back = ActionStats.fromJson(s.toJson())
        assertEquals(ctrlC, back.suggest(terminal, 1).single())
    }

    @Test
    fun `labels read like what a person calls them`() {
        assertEquals("Ctrl+C", ActionStats.label(ctrlC))
        assertEquals("Copy", ActionStats.label(KeyAction.Clipboard(ClipboardOp.COPY)))
        assertEquals("Torch", ActionStats.label(KeyAction.Do(com.example.core.io.Command("torch"))))
    }

    @Test
    fun `a shortcut is called up by name only after the prefix`() {
        assertEquals("cop", ActionCommands.typed("/cop", "/"))
        assertNull(ActionCommands.typed("copy", "/"))
        assertNull(ActionCommands.typed("/", "/"))
        assertNull(ActionCommands.typed("/copy", ""))
        val found = ActionCommands.matching("tor", ActionCommands.CATALOGUE, 3)
        assertEquals("Torch", ActionStats.label(found.first()))
        assertTrue(ActionCommands.matching("copy", ActionCommands.CATALOGUE, 3).isNotEmpty())
    }
}
