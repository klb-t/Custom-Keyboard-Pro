package com.example.ime.selection

import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import com.example.domain.selection.*

class InputConnectionSelectionBackend(
    private val icProvider: () -> InputConnection?,
    private val contextSource: SelectionContextSource,
    private val boundaryProvider: TextBoundaryProvider
) : SelectionBackend {

    override val capabilities: Set<SelectionCapability> = setOf(SelectionCapability.SINGLE_SEGMENT)

    override fun executeIntent(intent: SelectionIntent): SelectionResult {
        val ic = icProvider() ?: return SelectionResult.Unavailable

        if (intent == SelectionIntent.SelectAll) {
            ic.performContextMenuAction(android.R.id.selectAll)
            return SelectionResult.Success
        }
        
        // Simple fallback to arrow keys if granularity is character
        if (intent is SelectionIntent.Move && intent.granularity == SelectionGranularity.CHARACTER) {
            val keyCode = when (intent.direction) {
                Direction.FORWARD -> KeyEvent.KEYCODE_DPAD_RIGHT
                Direction.BACKWARD -> KeyEvent.KEYCODE_DPAD_LEFT
                Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
                Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            }
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return SelectionResult.Success
        }

        val ctx = contextSource.getCurrentContext() ?: return SelectionResult.Unavailable
        
        return when (intent) {
            is SelectionIntent.Move -> {
                val nextOffset = boundaryProvider.findNextBoundary(ctx.text, ctx.endOffset, intent.direction, intent.granularity)
                if (nextOffset != null) {
                    ic.setSelection(nextOffset, nextOffset)
                    SelectionResult.Success
                } else {
                    SelectionResult.Unavailable
                }
            }
            is SelectionIntent.Extend -> {
                val nextOffset = boundaryProvider.findNextBoundary(ctx.text, ctx.endOffset, intent.direction, intent.granularity)
                if (nextOffset != null) {
                    ic.setSelection(ctx.startOffset, nextOffset)
                    SelectionResult.Success
                } else {
                    SelectionResult.Unavailable
                }
            }
            else -> SelectionResult.Unsupported
        }
    }

    override fun setSelection(selection: Selection): SelectionResult {
        val ic = icProvider() ?: return SelectionResult.Unavailable
        if (selection.segments.size > 1) return SelectionResult.Unsupported
        
        val segment = selection.segments.firstOrNull() ?: return SelectionResult.Success
        val anchor = segment.anchor.position as? SelectionPosition.Offset ?: return SelectionResult.Unsupported
        val extent = segment.extent.position as? SelectionPosition.Offset ?: return SelectionResult.Unsupported
        
        ic.setSelection(anchor.characterOffset, extent.characterOffset)
        return SelectionResult.Success
    }
}
