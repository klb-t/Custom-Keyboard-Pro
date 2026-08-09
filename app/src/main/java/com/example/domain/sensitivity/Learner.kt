package com.example.domain.sensitivity

import com.example.domain.model.Point
import com.example.domain.model.UserAdaptation
import com.example.domain.model.AdaptationKernel

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

class MinimalLearner(
    val learningRate: Float = 0.05f,
    val kernelRadius: Float = 30f,
    val positiveStrength: Float = 1.0f,
    val negativeStrength: Float = 0.2f
) {

    fun learnFromCorrection(
        observation: CorrectionObservation,
        currentAdaptation: UserAdaptation
    ): UserAdaptation {
        val kernels = currentAdaptation.kernels.toMutableList()
        
        kernels.add(AdaptationKernel(
            targetElementId = observation.correctedElementId,
            center = observation.originalTapPoint,
            radius = kernelRadius,
            strength = positiveStrength * learningRate,
            revision = observation.sensitivityRevision
        ))
        
        observation.originalSelectedElementId?.let { wrongId ->
            if (wrongId != ProbabilisticSampler.BACKGROUND_KEY_ID) {
                kernels.add(AdaptationKernel(
                    targetElementId = wrongId,
                    center = observation.originalTapPoint,
                    radius = kernelRadius,
                    strength = -negativeStrength * learningRate,
                    revision = observation.sensitivityRevision
                ))
            }
        }
        
        return UserAdaptation(kernels)
    }
}
