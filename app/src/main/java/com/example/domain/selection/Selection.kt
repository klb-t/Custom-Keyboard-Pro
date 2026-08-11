package com.example.domain.selection

data class SelectionTargetRef(val targetId: String)

sealed interface SelectionPosition {
    data class Offset(val characterOffset: Int) : SelectionPosition
    data class NodeBoundary(val nodeId: String, val atEnd: Boolean) : SelectionPosition
}

data class SelectionAnchor(val position: SelectionPosition)
data class SelectionExtent(val position: SelectionPosition)

data class SelectionSegment(
    val anchor: SelectionAnchor,
    val extent: SelectionExtent
) {
    val isCollapsed: Boolean
        get() = anchor.position == extent.position
}

data class Selection(
    val target: SelectionTargetRef,
    val segments: List<SelectionSegment>
)

enum class SelectionGranularity {
    CHARACTER, GRAPHEME, WORD, SENTENCE, LINE, PARAGRAPH, OBJECT
}

sealed interface SelectionIntent {
    data class Move(val direction: Direction, val granularity: SelectionGranularity) : SelectionIntent
    data class Extend(val direction: Direction, val granularity: SelectionGranularity) : SelectionIntent
    object SelectAll : SelectionIntent
}

enum class Direction {
    FORWARD, BACKWARD, UP, DOWN
}

enum class SelectionCapability {
    SINGLE_SEGMENT,
    MULTI_SEGMENT,
    CROSS_NODE,
    OBJECT_SELECTION
}

sealed interface SelectionResult {
    object Success : SelectionResult
    object Unsupported : SelectionResult
    object Unavailable : SelectionResult
    data class Error(val reason: String) : SelectionResult
}

interface SelectionBackend {
    val capabilities: Set<SelectionCapability>
    fun executeIntent(intent: SelectionIntent): SelectionResult
    fun setSelection(selection: Selection): SelectionResult
}
