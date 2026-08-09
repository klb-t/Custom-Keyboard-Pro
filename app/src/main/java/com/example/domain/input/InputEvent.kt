package com.example.domain.input

enum class CoordinateSpace {
    SCREEN, WINDOW, WORKSPACE, PANEL, NORMALIZED
}

sealed interface InputEvent {
    val timestamp: Long
    val sourceId: String
}

data class PointerEvent(
    override val timestamp: Long,
    override val sourceId: String,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val coordinateSpace: CoordinateSpace,
    val pressure: Float = 1.0f,
    val phase: PointerPhase
) : InputEvent

enum class PointerPhase {
    DOWN, MOVE, UP, CANCEL
}

data class PhysicalKeyEvent(
    override val timestamp: Long,
    override val sourceId: String,
    val keyCode: Int,
    val isDown: Boolean,
    val modifiers: Int
) : InputEvent
