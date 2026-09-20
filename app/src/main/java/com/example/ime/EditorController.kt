package com.example.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.example.core.config.Settings
import com.example.core.layout.CursorDirection
import com.example.core.layout.TextUnit
import com.example.core.text.CapitalHow
import com.example.core.text.CapitalMoment
import com.example.core.text.Capitalisation
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

    /**
     * Where an extend-selection run started, and where its moving end is.
     *
     * Kept here rather than derived from the selection on each press, because the
     * selection does not say which end the caret is. Extend left once and the platform
     * reports the range normalised — start below end — so the next press reads the
     * left edge as the anchor and walks the *right* edge inwards, shrinking the
     * selection it was asked to grow. Two presses left and the thing is smaller than
     * after one. Remembering both ends is the only way shift-and-arrow can keep going
     * in the direction it was going.
     */
    private var anchor: Int = -1
    private var caret: Int = -1

    /** What we last asked the platform for, so a selection we did not cause is noticed. */
    private var expected: Pair<Int, Int>? = null

    fun onSelectionUpdate(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
        // A selection that is not the one we asked for came from somewhere else — a
        // tap, a drag, the app itself — and whatever run we were in is over. Keeping
        // the old anchor would make the next shift-and-arrow leap back to wherever
        // the user was selecting several taps ago.
        val mine = expected?.let { (a, b) ->
            (a == start && b == end) || (a == end && b == start)
        } ?: false
        if (!mine) {
            anchor = -1
            caret = -1
        }
        // Deliberately kept rather than cleared once matched. The platform is free to
        // report the same selection twice — a composing-region change reports one —
        // and a second identical report arriving against a cleared expectation would
        // read as "somebody else moved the cursor" and break a run mid-selection.
    }

    /**
     * Seeds the cursor position from what the editor declared when it opened.
     *
     * Without this the keyboard begins every field not knowing where the cursor is,
     * and -1 is not a harmless "unknown": it makes an empty field and a field whose
     * text is simply not readable yet look identical, and it sends cursor movement
     * down the blind path of raw key events. Android hands these over in [EditorInfo]
     * precisely so a keyboard need not guess before the first edit.
     */
    fun seedSelection(info: EditorInfo?) {
        anchor = -1
        caret = -1
        expected = null
        // A different field is a different document. Carrying edits across would offer
        // to put text back into somewhere it was never taken from.
        forgetHistory()
        val start = info?.initialSelStart ?: -1
        val end = info?.initialSelEnd ?: -1
        // Still -1 when the editor does not say, which is honest and is what the
        // callers already handle. Guessing 0 here would claim the cursor is at the
        // start of every field that declines to answer.
        selectionStart = start
        selectionEnd = end
    }

    /** True when the field is empty and the cursor is known to be in it at all. */
    val isKnownEmpty: Boolean
        get() = selectionStart == 0 && selectionEnd == 0 &&
            textBefore(1).isEmpty() && textAfter(1).isEmpty()

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
        // Read before writing: once the commit lands there is nothing left to record.
        val replaced = if (hasSelection) selectedText()?.toString().orEmpty() else ""

        if (applyConventions && text.isNotEmpty()) {
            val before = textBefore(4)

            if (text == " " && s.doubleSpacePeriod && !hasSelection) {
                TextOps.doubleSpaceReplacement(before)?.let { replacement ->
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(replacement, 1)
                    ic.endBatchEdit()
                    lastCommit = replacement
                    // The character removed is the space the rule required.
                    remember(removed = replaced + " ", inserted = replacement)
                    return
                }
            }

            if (s.smartQuotes && (text == "\"" || text == "'")) {
                text = TextOps.smartQuote(text[0], before)
            }

            // The same rules the service consults, asked again here.
            //
            // This branch is what makes a capital appear when the shift flag was not
            // set — after a paste, or when the state was cleared under the keyboard.
            // It used to ask its own question with its own hardcoded condition, which
            // meant capitalisation had two implementations that could disagree, and
            // that turning the rules off would not have turned this one off. One
            // policy, read from one place.
            if (s.autoCapitalize && !shiftActive && text.length == 1 && text[0].isLowerCase()) {
                val window = textBefore(200)
                val wanted = Capitalisation.decide(
                    rules = Capitalisation.fromJson(s.capitalisationRulesJson),
                    before = window,
                    atStartOfField = window.isEmpty() && selectionStart == 0,
                    moment = CapitalMoment.TYPING
                )
                if (wanted == CapitalHow.SHIFT) text = text.uppercase()
            }
        }

        if (applyConventions && s.autoSpaceAfterPunctuation && text.length == 1 &&
            TextOps.wantsTrailingSpace(text[0]) && !textAfter(1).startsWith(" ")
        ) {
            ic.commitText("$text ", 1)
            lastCommit = "$text "
            remember(removed = replaced, inserted = "$text ")
            return
        }

        ic.commitText(text, 1)
        remember(removed = replaced, inserted = text)
        lastCommit = text
    }

    fun commitRepeatLast() {
        if (lastCommit.isEmpty()) return
        connection()?.commitText(lastCommit, 1)
        remember(removed = "", inserted = lastCommit)
    }

    fun setComposing(text: String) {
        connection()?.setComposingText(text, 1)
        // A composing region is text in flux that the platform rewrites under us. What
        // it settles as is not something this history can describe.
        forgetHistory()
    }

    fun finishComposing() {
        connection()?.finishComposingText()
    }

    /** Replaces the word under the cursor. Used to accept a suggestion. */
    fun replaceCurrentWord(replacement: String, addTrailingSpace: Boolean = true) {
        val ic = connection() ?: return
        val word = currentWord()
        ic.beginBatchEdit()
        val added = replacement + if (addTrailingSpace) " " else ""
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText(added, 1)
        ic.endBatchEdit()
        remember(removed = word, inserted = added)
        lastCommit = replacement
    }

    /** Appends a completion that continues what has already been typed. */
    fun commitCompletion(completion: String) {
        if (completion.isEmpty()) return
        connection()?.commitText(completion, 1)
        remember(removed = "", inserted = completion)
        lastCommit = completion
    }

    fun replaceSelectionOrAll(replacement: String) {
        val ic = connection() ?: return
        val replaced = if (hasSelection) selectedText()?.toString().orEmpty() else null
        ic.beginBatchEdit()
        if (hasSelection) {
            ic.commitText(replacement, 1)
        } else {
            selectAll()
            ic.commitText(replacement, 1)
        }
        ic.endBatchEdit()
        // Whole-field replacement goes through the platform's select-all, so what was
        // there is never read. Rather than record a guess, the history is dropped.
        if (replaced != null) remember(removed = replaced, inserted = replacement)
        else forgetHistory()
    }

    // -----------------------------------------------------------------------
    // Deleting
    // -----------------------------------------------------------------------

    /** Deletes exactly [count] characters before the cursor, ignoring grapheme rules. */
    fun deleteExactly(count: Int) {
        if (count <= 0) return
        val removed = textBefore(count).toString()
        connection()?.deleteSurroundingText(count, 0)
        remember(removed = removed, inserted = "")
    }

    fun backspace(unit: TextUnit) {
        val ic = connection() ?: return
        if (hasSelection) {
            val removed = selectedText()?.toString().orEmpty()
            ic.commitText("", 1)
            remember(removed = removed, inserted = "")
            return
        }
        val count = when (unit) {
            TextUnit.CHARACTER -> TextOps.lastGraphemeLength(textBefore(8))
            TextUnit.WORD -> TextOps.backwardWordLength(textBefore(128))
            TextUnit.LINE, TextUnit.PARAGRAPH -> TextOps.toLineStartLength(textBefore(4096))
            TextUnit.ALL -> {
                selectAll()
                ic.commitText("", 1)
                // Never read, so never described. See [forgetHistory].
                forgetHistory()
                return
            }
        }
        if (count > 0) {
            val removed = textBefore(count).toString()
            ic.deleteSurroundingText(count, 0)
            remember(removed = removed, inserted = "")
        } else {
            // Empty field, or an editor that will not report surrounding text: let the
            // platform decide, which is also what makes backspace work in a terminal.
            // What it deletes is its business, so nothing here can claim to know.
            sendKey(KeyEvent.KEYCODE_DEL, 0)
            forgetHistory()
        }
    }

    fun forwardDelete(unit: TextUnit) {
        val ic = connection() ?: return
        if (hasSelection) {
            val removed = selectedText()?.toString().orEmpty()
            ic.commitText("", 1)
            remember(removed = removed, inserted = "")
            return
        }
        val count = when (unit) {
            TextUnit.CHARACTER -> TextOps.firstGraphemeLength(textAfter(8))
            TextUnit.WORD -> TextOps.forwardWordLength(textAfter(128))
            TextUnit.LINE, TextUnit.PARAGRAPH -> TextOps.toLineEndLength(textAfter(4096))
            TextUnit.ALL -> {
                selectAll()
                ic.commitText("", 1)
                forgetHistory()
                return
            }
        }
        if (count > 0) {
            val removed = textAfter(count).toString()
            ic.deleteSurroundingText(0, count)
            // Recorded as a forward removal, so putting it back leaves the cursor in
            // front of it rather than behind — which is where it was.
            remember(removed = removed, inserted = "", forward = true)
        } else {
            sendKey(KeyEvent.KEYCODE_FORWARD_DEL, 0)
            forgetHistory()
        }
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

        // A run in progress keeps its own anchor and caret; a new one takes them from
        // wherever the cursor is now. Without the first half, every press after the
        // first reads the normalised range and walks the wrong end.
        if (!extend) {
            anchor = -1
            caret = -1
        }
        val runAnchor = if (extend && anchor >= 0) anchor else selectionStart
        val from = if (extend && caret >= 0) caret else selectionEnd
        // Known limit, stated rather than hidden: the text window below is read around
        // the platform's idea of the cursor, which during a selection is its lower
        // edge. That is the caret in every run that is still going the way it started,
        // so character steps are exact either way. A run that reverses *and* asks for
        // a word or a line — extend right, then left by word — measures from the far
        // edge and can land on the wrong boundary. Fixing it needs the whole field
        // text on every press, which is not worth it until somebody hits it.
        val target = when (direction) {
            CursorDirection.LEFT -> when (unit) {
                TextUnit.WORD -> from - TextOps.backwardWordLength(textBefore(128))
                TextUnit.LINE, TextUnit.PARAGRAPH -> from - TextOps.toLineStartLength(textBefore(4096))
                TextUnit.ALL -> 0
                TextUnit.CHARACTER -> from - TextOps.lastGraphemeLength(textBefore(8)).coerceAtLeast(1)
            }
            CursorDirection.RIGHT -> when (unit) {
                TextUnit.WORD -> from + TextOps.forwardWordLength(textAfter(128))
                TextUnit.LINE, TextUnit.PARAGRAPH -> from + TextOps.toLineEndLength(textAfter(4096))
                TextUnit.ALL -> from + textAfter(100_000).length
                TextUnit.CHARACTER -> from + TextOps.firstGraphemeLength(textAfter(8)).coerceAtLeast(1)
            }
            CursorDirection.LINE_START -> from - TextOps.toLineStartLength(textBefore(4096))
            CursorDirection.LINE_END -> from + TextOps.toLineEndLength(textAfter(4096))
            CursorDirection.DOC_START -> 0
            CursorDirection.DOC_END -> from + textAfter(1_000_000).length
            else -> from
        }.coerceAtLeast(0)

        if (extend) {
            // Deliberately not normalised. A reversed range is how the platform itself
            // represents a selection whose caret is at the left end, and handing it
            // back sorted would lose the one fact the next press needs.
            anchor = runAnchor
            caret = target
            expected = runAnchor to target
            ic.setSelection(runAnchor, target)
        } else {
            expected = target to target
            ic.setSelection(target, target)
        }
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

    fun contextMenu(id: Int): Boolean {
        val handled = connection()?.performContextMenuAction(id) ?: false
        // Cut and paste change the text and the platform does not say how. Copy and
        // select-all do not, but telling them apart here would be a list to maintain
        // for the sake of keeping a few entries nobody is about to use.
        if (handled) forgetHistory()
        return handled
    }

    fun copy() = contextMenu(android.R.id.copy)
    fun cut() = contextMenu(android.R.id.cut)
    fun paste() = contextMenu(android.R.id.paste)

    /**
     * Undo, done by the keyboard rather than asked of the app.
     *
     * Ctrl+Z is a request, not an API: there is no `InputConnection` call for undo, so
     * a keyboard can only send the chord and hope. Plenty of modern text fields —
     * Compose, Flutter, anything inside a web view — implement paste and never
     * implement that chord, which is why Ctrl+V worked here and Ctrl+Z did nothing.
     *
     * So the keyboard keeps its own short history of the edits *it* made, and puts the
     * last one back itself. The safety rule is the whole design: if the text no longer
     * looks the way this edit left it, nothing is touched. Somebody else has been
     * writing — the app's own autocomplete, a second keyboard, a paste from elsewhere
     * — and our idea of where things are is worthless. Then, and only then, the chord
     * is sent, because an app that does handle it is better placed than we are.
     *
     * Returns true when the keyboard undid something itself.
     */
    fun undo(): Boolean = step(undoStack, redoStack) {
        sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    }

    fun redo(): Boolean = step(redoStack, undoStack) {
        sendKey(
            KeyEvent.KEYCODE_Z,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        )
    }

    /** True when there is something of the keyboard's own to undo, for an indicator. */
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun step(from: ArrayDeque<Edit>, onto: ArrayDeque<Edit>, fallback: () -> Unit): Boolean {
        // Guarded, because the fallback sends a key event and sending one normally
        // throws the history away — which would take the *other* stack with it, and
        // undoing to the start should not make redo impossible.
        fun handOver() {
            inHistory = true
            try { fallback() } finally { inHistory = false }
        }

        val edit = from.lastOrNull() ?: run { handOver(); return false }
        if (textBefore(CONTEXT).toString() != edit.context) {
            // Not where we left it. Everything we remember is about positions that
            // have moved, so the history is not stale in part — it is worthless.
            undoStack.clear()
            redoStack.clear()
            handOver()
            return false
        }
        from.removeLast()

        inHistory = true
        try {
            batch {
                val ic = connection() ?: return@batch
                if (edit.inserted.isNotEmpty()) {
                    if (edit.forward) ic.deleteSurroundingText(0, edit.inserted.length)
                    else ic.deleteSurroundingText(edit.inserted.length, 0)
                }
                // Cursor before the text when the edit had removed what was ahead of
                // it, so a forward delete undoes to where the cursor actually was.
                if (edit.removed.isNotEmpty()) ic.commitText(edit.removed, if (edit.forward) 0 else 1)
            }
        } finally {
            inHistory = false
        }

        onto.addLast(
            Edit(
                removed = edit.inserted,
                inserted = edit.removed,
                forward = edit.forward,
                context = textBefore(CONTEXT).toString()
            )
        )
        return true
    }

    // -----------------------------------------------------------------------
    // The history itself
    // -----------------------------------------------------------------------

    /**
     * One edit the keyboard made: what it took out, what it put in, and what the text
     * looked like immediately afterwards.
     *
     * [context] is the precondition rather than a position. Offsets go stale the
     * moment anything else writes to the field; a window of the text as we left it
     * either still matches or does not, and that is exactly the question worth asking.
     */
    private data class Edit(
        val removed: String,
        val inserted: String,
        /** The removal was ahead of the cursor, as a forward delete. */
        val forward: Boolean = false,
        val context: String
    )

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** True while undoing or redoing, so the edit that does it is not itself recorded. */
    private var inHistory = false

    /**
     * Records an edit the keyboard just made. Call *after* the text has changed.
     *
     * Consecutive single characters are merged, so undo works by word rather than by
     * keystroke — anything else means tapping undo eleven times to remove "Hello there".
     * Whitespace breaks the run, which is what makes the word the unit.
     */
    private fun remember(removed: String, inserted: String, forward: Boolean = false) {
        if (inHistory) return
        if (removed.isEmpty() && inserted.isEmpty()) return
        val context = textBefore(CONTEXT).toString()

        val last = undoStack.lastOrNull()
        // A letter joins whatever run precedes it; a space starts a new one. So
        // "hello world" is two entries — "hello" and " world" — and undo works the way
        // it does in an editor rather than one keystroke at a time.
        val mergeable = last != null && !forward && !last.forward &&
            last.removed.isEmpty() && removed.isEmpty() &&
            last.inserted.isNotEmpty() &&
            inserted.length == 1 && !inserted[0].isWhitespace()

        if (mergeable) {
            undoStack.removeLast()
            undoStack.addLast(Edit(removed = "", inserted = last!!.inserted + inserted, context = context))
        } else {
            undoStack.addLast(Edit(removed, inserted, forward, context))
        }
        while (undoStack.size > HISTORY) undoStack.removeFirst()
        // A new edit makes any redo path unreachable, which is what every editor does.
        redoStack.clear()
    }

    /**
     * Throws the history away, for a change the keyboard made and cannot describe.
     *
     * Honest rather than convenient: an edit we did not record leaves the entries
     * beneath it describing a text that no longer exists, and undoing one of those
     * would corrupt the field rather than fail.
     */
    private fun forgetHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    // -----------------------------------------------------------------------
    // Raw key events
    // -----------------------------------------------------------------------

    fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connection() ?: return
        // What a raw key event does is the app's business — it may insert, delete,
        // move, or nothing at all. Anything we remember from before it is a claim
        // about text we can no longer vouch for.
        if (!inHistory && !isHarmless(keyCode)) forgetHistory()
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
    /** Keys that cannot change the text, so the history survives them. */
    private fun isHarmless(keyCode: Int): Boolean = keyCode in HARMLESS_KEYS

    fun performEnter() {
        val ic = connection() ?: return
        val info = editorInfo()
        if (info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                // Send, search, next field — the app may clear the field entirely.
                forgetHistory()
                return
            }
        }
        ic.commitText("\n", 1)
        remember(removed = "", inserted = "\n")
        lastCommit = "\n"
    }

    /**
     * Runs several edits as one, and records them as one.
     *
     * Auto-correction deletes a word and commits another; without coalescing that is
     * two entries and takes two presses of undo to put right, which is not what anyone
     * means by undoing a correction. The pair is collapsed by comparing the tail of
     * the text before and after — which is precisely what these batches rewrite — and
     * if the change reaches back further than the window can see, the history is
     * dropped rather than described wrongly.
     */
    fun batch(block: () -> Unit) {
        val ic = connection()
        val outer = inHistory
        val pre = if (outer) "" else textBefore(WINDOW).toString()
        inHistory = true
        ic?.beginBatchEdit()
        try {
            block()
        } finally {
            ic?.endBatchEdit()
            inHistory = outer
        }
        if (outer) return

        val post = textBefore(WINDOW).toString()
        if (pre == post) return
        val shared = pre.commonPrefixWith(post).length
        if (shared == 0 && pre.isNotEmpty() && post.isNotEmpty()) {
            // The edit reached further back than we can see. Anything we recorded
            // would be a guess about text we never read.
            forgetHistory()
            return
        }
        remember(removed = pre.substring(shared), inserted = post.substring(shared))
    }

    private companion object {
        /** How much text either side of an edit is kept as its precondition. */
        const val CONTEXT = 24

        /** How far back a batched edit is allowed to reach and still be described. */
        const val WINDOW = 96

        /** Edits kept. Far more than anyone taps back through, far less than a document. */
        const val HISTORY = 50

        /** Cursor movement and modifiers. Everything else may rewrite the field. */
        val HARMLESS_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT
        )
    }
}
