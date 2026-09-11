package com.example.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.example.core.config.Settings
import com.example.core.layout.CursorDirection
import com.example.core.layout.TextUnit
import com.example.core.text.TextOps

/**
 * Everything that touches the edited text goes through here.
 *
 * Kept apart from the service so the rules about what typing *means* — when a capital
 * is implied, what a backspace removes, whether a field is a password field — are
 * testable and stated once, rather than scattered through key handlers.
 */
class EditorController(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val settings: () -> Settings
) {

    /** Cursor position as last reported by the platform; -1 when unknown. */
    var selectionStart: Int = -1
        private set
    var selectionEnd: Int = -1
        private set

    fun onSelectionUpdate(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    val hasSelection: Boolean
        get() = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd

    // -----------------------------------------------------------------------
    // Field inspection
    // -----------------------------------------------------------------------

    val isPasswordField: Boolean
        get() {
            val type = editorInfo()?.inputType ?: return false
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

    /** The field asked not to be learned from — honour it regardless of settings. */
    val noPersonalisedLearning: Boolean
        get() {
            val info = editorInfo() ?: return false
            return (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        }

    /** True when nothing typed here should be stored, learned from or sent anywhere. */
    val isSensitive: Boolean
        get() = isPasswordField || noPersonalisedLearning

    val isMultiline: Boolean
        get() {
            val info = editorInfo() ?: return false
            if ((info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) return true
            return (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_NONE
        }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    fun textBefore(n: Int): CharSequence =
        connection()?.getTextBeforeCursor(n, 0) ?: ""

    fun textAfter(n: Int): CharSequence =
        connection()?.getTextAfterCursor(n, 0) ?: ""

    fun selectedText(): CharSequence? = connection()?.getSelectedText(0)

    /** Whole field text when the editor will hand it over, else the nearby window. */
    fun allText(limit: Int = 20_000): CharSequence {
        val ic = connection() ?: return ""
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        extracted?.text?.let { return it }
        return textBefore(limit).toString() + textAfter(limit).toString()
    }

    fun currentWord(): String = TextOps.currentWord(textBefore(48))

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /** The last thing committed, for [com.example.core.layout.KeyAction.RepeatLast]. */
    var lastCommit: String = ""
        private set

    /**
     * Commits text with the conventions the user has enabled.
     *
     * [raw] is what the key says; what lands in the field may differ — a capital at the
     * start of a sentence, a curly quote, or ". " where a second space was typed.
     */
    fun commitText(raw: String, applyConventions: Boolean = true, shiftActive: Boolean = false) {
        val ic = connection() ?: return
        val s = settings()
        var text = raw

        if (applyConventions && text.isNotEmpty()) {
            val before = textBefore(4)

            if (text == " " && s.doubleSpacePeriod && !hasSelection) {
                TextOps.doubleSpaceReplacement(before)?.let { replacement ->
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(replacement, 1)
                    ic.endBatchEdit()
                    lastCommit = replacement
                    return
                }
            }

            if (s.smartQuotes && (text == "\"" || text == "'")) {
                text = TextOps.smartQuote(text[0], before)
            }

            if (s.autoCapitalize && !shiftActive && text.length == 1 && text[0].isLowerCase() &&
                TextOps.shouldCapitalise(textBefore(64))
            ) {
                text = text.uppercase()
            }
        }

        ic.commitText(text, 1)
        lastCommit = text
    }

    fun commitRepeatLast() {
        if (lastCommit.isNotEmpty()) connection()?.commitText(lastCommit, 1)
    }

    fun setComposing(text: String) {
        connection()?.setComposingText(text, 1)
    }

    fun finishComposing() {
        connection()?.finishComposingText()
    }

    /** Replaces the word under the cursor. Used to accept a suggestion. */
    fun replaceCurrentWord(replacement: String, addTrailingSpace: Boolean = true) {
        val ic = connection() ?: return
        val word = currentWord()
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText(replacement + if (addTrailingSpace) " " else "", 1)
        ic.endBatchEdit()
        lastCommit = replacement
    }

    /** Appends a completion that continues what has already been typed. */
    fun commitCompletion(completion: String) {
        if (completion.isEmpty()) return
        connection()?.commitText(completion, 1)
        lastCommit = completion
    }

    fun replaceSelectionOrAll(replacement: String) {
        val ic = connection() ?: return
        ic.beginBatchEdit()
        if (hasSelection) {
            ic.commitText(replacement, 1)
        } else {
            selectAll()
            ic.commitText(replacement, 1)
        }
        ic.endBatchEdit()
    }

    // -----------------------------------------------------------------------
    // Deleting
    // -----------------------------------------------------------------------

    fun backspace(unit: TextUnit) {
        val ic = connection() ?: return
        if (hasSelection) {
            ic.commitText("", 1)
            return
        }
        val count = when (unit) {
            TextUnit.CHARACTER -> TextOps.lastGraphemeLength(textBefore(8))
            TextUnit.WORD -> TextOps.backwardWordLength(textBefore(128))
            TextUnit.LINE, TextUnit.PARAGRAPH -> TextOps.toLineStartLength(textBefore(4096))
            TextUnit.ALL -> {
                selectAll()
                ic.commitText("", 1)
                return
            }
        }
        if (count > 0) {
            ic.deleteSurroundingText(count, 0)
        } else {
            // Empty field, or an editor that will not report surrounding text: let the
            // platform decide, which is also what makes backspace work in a terminal.
            sendKey(KeyEvent.KEYCODE_DEL, 0)
        }
    }

    fun forwardDelete(unit: TextUnit) {
        val ic = connection() ?: return
        if (hasSelection) {
            ic.commitText("", 1)
            return
        }
        val count = when (unit) {
            TextUnit.CHARACTER -> TextOps.firstGraphemeLength(textAfter(8))
            TextUnit.WORD -> TextOps.forwardWordLength(textAfter(128))
            TextUnit.LINE, TextUnit.PARAGRAPH -> TextOps.toLineEndLength(textAfter(4096))
            TextUnit.ALL -> {
                selectAll()
                ic.commitText("", 1)
                return
            }
        }
        if (count > 0) ic.deleteSurroundingText(0, count) else sendKey(KeyEvent.KEYCODE_FORWARD_DEL, 0)
    }

    // -----------------------------------------------------------------------
    // Cursor and selection
    // -----------------------------------------------------------------------

    /**
     * Moves the cursor, preferring an exact `setSelection` when the editor reports
     * positions and falling back to arrow-key events when it does not — which is what
     * keeps this working in terminal emulators and web views.
     */
    fun moveCursor(direction: CursorDirection, unit: TextUnit, extend: Boolean) {
        val ic = connection() ?: return

        if (direction == CursorDirection.UP || direction == CursorDirection.DOWN) {
            val code = if (direction == CursorDirection.UP) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            sendKey(code, if (extend) KeyEvent.META_SHIFT_ON else 0)
            return
        }

        if (selectionStart < 0 || selectionEnd < 0) {
            val code = when (direction) {
                CursorDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                CursorDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                CursorDirection.LINE_START, CursorDirection.DOC_START -> KeyEvent.KEYCODE_MOVE_HOME
                CursorDirection.LINE_END, CursorDirection.DOC_END -> KeyEvent.KEYCODE_MOVE_END
                else -> KeyEvent.KEYCODE_DPAD_RIGHT
            }
            var meta = if (extend) KeyEvent.META_SHIFT_ON else 0
            if (unit == TextUnit.WORD) meta = meta or KeyEvent.META_CTRL_ON
            if (direction == CursorDirection.DOC_START || direction == CursorDirection.DOC_END) {
                meta = meta or KeyEvent.META_CTRL_ON
            }
            sendKey(code, meta)
            return
        }

        val anchor = if (extend) selectionStart else selectionEnd.coerceAtLeast(selectionStart)
        val caret = selectionEnd
        val target = when (direction) {
            CursorDirection.LEFT -> when (unit) {
                TextUnit.WORD -> caret - TextOps.backwardWordLength(textBefore(128))
                TextUnit.LINE, TextUnit.PARAGRAPH -> caret - TextOps.toLineStartLength(textBefore(4096))
                TextUnit.ALL -> 0
                TextUnit.CHARACTER -> caret - TextOps.lastGraphemeLength(textBefore(8)).coerceAtLeast(1)
            }
            CursorDirection.RIGHT -> when (unit) {
                TextUnit.WORD -> caret + TextOps.forwardWordLength(textAfter(128))
                TextUnit.LINE, TextUnit.PARAGRAPH -> caret + TextOps.toLineEndLength(textAfter(4096))
                TextUnit.ALL -> caret + textAfter(100_000).length
                TextUnit.CHARACTER -> caret + TextOps.firstGraphemeLength(textAfter(8)).coerceAtLeast(1)
            }
            CursorDirection.LINE_START -> caret - TextOps.toLineStartLength(textBefore(4096))
            CursorDirection.LINE_END -> caret + TextOps.toLineEndLength(textAfter(4096))
            CursorDirection.DOC_START -> 0
            CursorDirection.DOC_END -> caret + textAfter(1_000_000).length
            else -> caret
        }.coerceAtLeast(0)

        if (extend) ic.setSelection(anchor, target) else ic.setSelection(target, target)
    }

    fun selectAll() {
        connection()?.performContextMenuAction(android.R.id.selectAll)
    }

    fun select(unit: TextUnit) {
        val ic = connection() ?: return
        when (unit) {
            TextUnit.ALL -> selectAll()
            TextUnit.WORD -> {
                if (selectionStart < 0) return
                val back = TextOps.backwardWordLength(textBefore(128))
                val forward = TextOps.forwardWordLength(textAfter(128))
                ic.setSelection((selectionEnd - back).coerceAtLeast(0), selectionEnd + forward)
            }
            TextUnit.LINE, TextUnit.PARAGRAPH -> {
                if (selectionStart < 0) return
                val back = TextOps.toLineStartLength(textBefore(4096))
                val forward = TextOps.toLineEndLength(textAfter(4096))
                ic.setSelection((selectionEnd - back).coerceAtLeast(0), selectionEnd + forward)
            }
            TextUnit.CHARACTER -> {
                if (selectionStart < 0) return
                ic.setSelection(selectionEnd, selectionEnd + 1)
            }
        }
    }

    fun collapseSelection() {
        if (selectionEnd >= 0) connection()?.setSelection(selectionEnd, selectionEnd)
    }

    // -----------------------------------------------------------------------
    // Clipboard and history, via the editor so the app's own undo stack is used
    // -----------------------------------------------------------------------

    fun contextMenu(id: Int): Boolean = connection()?.performContextMenuAction(id) ?: false

    fun copy() = contextMenu(android.R.id.copy)
    fun cut() = contextMenu(android.R.id.cut)
    fun paste() = contextMenu(android.R.id.paste)

    fun undo() {
        // No InputConnection API for undo; Ctrl+Z is what editors actually listen for.
        sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    }

    fun redo() {
        sendKey(
            KeyEvent.KEYCODE_Z,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        )
    }

    // -----------------------------------------------------------------------
    // Raw key events
    // -----------------------------------------------------------------------

    fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connection() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyEvent.KEYCODE_UNKNOWN, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
        ic.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyEvent.KEYCODE_UNKNOWN, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /** Enter means "do what the field asked for", falling back to a newline. */
    fun performEnter() {
        val ic = connection() ?: return
        val info = editorInfo()
        if (info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        ic.commitText("\n", 1)
        lastCommit = "\n"
    }

    fun batch(block: () -> Unit) {
        val ic = connection()
        ic?.beginBatchEdit()
        try {
            block()
        } finally {
            ic?.endBatchEdit()
        }
    }
}
