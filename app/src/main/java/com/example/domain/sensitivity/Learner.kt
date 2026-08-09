package com.example.domain.sensitivity

import com.example.domain.model.Point
import com.example.domain.model.UserAdaptation

data class CorrectionObservation(
    val originalEventId: String,
    val originalTapPoint: Point,
    val originalDistribution: KeyProbabilityDistribution?,
    val originalSelectedElementId: String?,
    val correctedElementId: String,
    val layoutRevision: Int,
    val sensitivityRevision: Int,
    val timestamp: Long,
    val correctionSource: String
)

data class CalibrationSample(
    val intendedElementId: String,
    val tapPoint: Point,
    val layoutRevision: Int,
    val deviceTransformMetadata: String,
    val timestamp: Long
)

class MinimalLearner(val learningRate: Float = 0.05f) {

    fun learnFromCorrection(
        observation: CorrectionObservation,
        currentAdaptation: UserAdaptation
    ): UserAdaptation {
        val deltaX = observation.originalTapPoint.x * learningRate
        val deltaY = observation.originalTapPoint.y * learningRate
        
        return UserAdaptation(
            offsetX = currentAdaptation.offsetX + deltaX,
            offsetY = currentAdaptation.offsetY + deltaY,
            scaleX = currentAdaptation.scaleX,
            scaleY = currentAdaptation.scaleY
        )
    }
}
