package com.example.domain.input

/**
 * Generic InputEvent model (Zasada 21).
 * Abstraction for various input types.
 */
sealed interface InputEvent {
    val timestamp: Long
    val sourceId: String // e.g. "touch_screen_1", "physical_keyboard", "mouse"
}

data class PointerEvent(
    override val timestamp: Long = System.currentTimeMillis(),
    override val sourceId: String,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val phase: PointerPhase
) : InputEvent

enum class PointerPhase {
    DOWN, MOVE, UP, CANCEL
}

data class PhysicalKeyEvent(
    override val timestamp: Long = System.currentTimeMillis(),
    override val sourceId: String,
    val keyCode: Int,
    val isDown: Boolean,
    val modifiers: Int
) : InputEvent
