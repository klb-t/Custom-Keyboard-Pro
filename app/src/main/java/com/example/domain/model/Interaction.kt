package com.example.domain.model

import com.example.domain.action.ActionRef

/**
 * Canonical vector sensitivity model (Zasada 17).
 */
sealed interface VectorShape {
    data class Rect(val width: Float, val height: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : VectorShape
    data class Circle(val radius: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) : VectorShape
    data class Polygon(val points: List<Point>) : VectorShape
}

data class Point(val x: Float, val y: Float)

data class SensitivityField(
    val baseShape: VectorShape,
    val weight: Float = 1.0f
)

/**
 * User learning/adaptation (Zasada 20).
 */
data class UserAdaptation(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f
)

data class EffectiveSensitivity(
    val field: SensitivityField,
    val adaptation: UserAdaptation = UserAdaptation()
)

/**
 * States/Layers
 */
data class LayerState(
    val name: String,
    val activationMode: ActivationMode = ActivationMode.Momentary,
    val isActive: Boolean = false
)

sealed interface ActivationMode {
    object Momentary : ActivationMode
    data class Sticky(val actionCountLimit: Int = 1) : ActivationMode
    object Latched : ActivationMode
    data class Timeout(val durationMs: Long) : ActivationMode
    data class PredicateDriven(val continuationRuleId: String) : ActivationMode
}

/**
 * Triggers (Zasada 24).
 */
sealed interface Trigger {
    object Tap : Trigger
    object LongPress : Trigger
    object DoubleTap : Trigger
    data class Swipe(val angleDegrees: Float, val distanceDp: Float, val velocity: Float? = null) : Trigger
    data class MultiTap(val count: Int) : Trigger
    data class TapAndHold(val count: Int) : Trigger
    data class Sequence(val inputs: List<InputToken>) : Trigger // generic input sequence
}

sealed interface InputToken {
    data class KeyPress(val keyName: String) : InputToken
}

/**
 * Action Binding.
 */
data class ActionBinding(
    val trigger: Trigger,
    val actionRef: ActionRef
)

data class InteractionBehavior(
    val bindings: List<ActionBinding> = emptyList(),
    val tapHoldPolicy: TapHoldPolicy = TapHoldPolicy(TapHoldType.BALANCED)
)

data class TapHoldPolicy(
    val type: TapHoldType,
    val timeoutMs: Long = 200L,
    val interruptRule: InterruptRule = InterruptRule.DEFAULT
)

enum class TapHoldType {
    HOLD_PREFERRED, BALANCED, TAP_PREFERRED, TAP_UNLESS_INTERRUPTED
}

enum class InterruptRule {
    DEFAULT, REQUIRE_PRIOR_RELEASE, ALLOW_INTERLEAVED
}
