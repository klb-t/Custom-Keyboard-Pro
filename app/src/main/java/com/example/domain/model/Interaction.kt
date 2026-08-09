package com.example.domain.model

import com.example.domain.action.ActionRef

/**
 * Geometria wizualna i geometria interakcji to dwie różne rzeczy (Zasada 9).
 * Canonical vector sensitivity model.
 */
sealed interface VectorShape {
    data class Rect(val width: Float, val height: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : VectorShape
    data class Circle(val radius: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : VectorShape
    data class Polygon(val points: List<Point>) : VectorShape
}

data class Point(val x: Float, val y: Float)

/**
 * Reprezentuje obszar czułości i jego wagę (Zasada 10).
 */
data class SensitivityField(
    val baseShape: VectorShape,
    val weight: Float = 1.0f
)

/**
 * Zmiany naniesione przez proces uczenia maszynowego użytkownika (Zasada 12).
 */
data class UserAdaptation(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f
)

/**
 * Sumaryczne pole czułości (Zasada 12).
 */
data class EffectiveSensitivity(
    val field: SensitivityField,
    val adaptation: UserAdaptation = UserAdaptation()
)

/**
 * Warstwy (States/Layers)
 * Zgodnie z wytycznymi, state może być momentary, sticky/one-shot, latched.
 */
data class LayerState(
    val name: String,
    val activationMode: ActivationMode = ActivationMode.MOMENTARY,
    val isActive: Boolean = false
)

enum class ActivationMode {
    MOMENTARY, // Active while held
    STICKY,    // One-shot, active for next action
    LATCHED    // Active until explicitly toggled off
}

/**
 * Typy wyzwalaczy (Triggers) zamiast sztywnych pól onTap, onLongPress.
 */
sealed interface Trigger {
    object Tap : Trigger
    object LongPress : Trigger
    object DoubleTap : Trigger
    data class Swipe(val direction: SwipeDirection) : Trigger
    data class MultiTap(val count: Int) : Trigger
    data class TapAndHold(val count: Int) : Trigger
    data class Sequence(val actionIds: List<String>) : Trigger // ZMK Leader-like sequence
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Wiązanie wyzwalacza z akcją.
 */
data class ActionBinding(
    val trigger: Trigger,
    val actionRef: ActionRef
)

/**
 * Klawisz ma niezależne warstwy: interaction, action.
 * Zastąpienie sztywnych pól dynamiczną listą bindowań.
 */
data class InteractionBehavior(
    val bindings: List<ActionBinding> = emptyList(),
    // Opcjonalna polityka rozwiązywania konfliktów tap vs hold
    val tapHoldPolicy: TapHoldPolicy = TapHoldPolicy.BALANCED
)

enum class TapHoldPolicy {
    HOLD_PREFERRED,
    BALANCED,
    TAP_PREFERRED,
    TAP_UNLESS_INTERRUPTED
}
