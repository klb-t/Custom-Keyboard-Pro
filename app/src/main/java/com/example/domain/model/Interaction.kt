package com.example.domain.model

import com.example.domain.action.Action

/**
 * Geometria wizualna i geometria interakcji to dwie różne rzeczy (Zasada 9).
 * interaction field (np. dla uczenia maszynowego) jest oddzielone od wyglądu.
 */
sealed interface InteractionField {
    data class Rect(val width: Float, val height: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : InteractionField
    data class Circle(val radius: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : InteractionField
    // w przyszłości: ścieżki, fieldy Gaussa, itp.
}

/**
 * Warstwy (States/Layers) (Zasada 13)
 * normal, Shift, AltGr, symbols, navigation itp.
 */
data class LayerState(
    val name: String,
    val isActive: Boolean = false
)

/**
 * Klawisz ma niezależne warstwy: identity, visual, interaction, action (Zasada 8)
 */
data class InteractionBehavior(
    val onTap: Action? = null,
    val onLongPress: Action? = null,
    val onDoubleTap: Action? = null,
    val onSwipeLeft: Action? = null,
    val onSwipeRight: Action? = null,
    val onSwipeUp: Action? = null,
    val onSwipeDown: Action? = null
)
