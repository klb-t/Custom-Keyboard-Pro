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
 * Warstwy (States/Layers) (Zasada 13)
 */
data class LayerState(
    val name: String,
    val isActive: Boolean = false
)

/**
 * Klawisz ma niezależne warstwy: interaction, action. Tutaj łączymy je przez ActionRef (Zasada 8, 34).
 */
data class InteractionBehavior(
    val onTapRef: ActionRef? = null,
    val onLongPressRef: ActionRef? = null,
    val onDoubleTapRef: ActionRef? = null,
    val onSwipeLeftRef: ActionRef? = null,
    val onSwipeRightRef: ActionRef? = null,
    val onSwipeUpRef: ActionRef? = null,
    val onSwipeDownRef: ActionRef? = null
)
