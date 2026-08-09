package com.example.domain.sensitivity

data class ModelTransferSettings(
    val bias: Float = 0f,
    val contrast: Float = 1.0f,
    val contrastPivot: Float = 0.5f,
    val gamma: Float = 1.0f,
    val floor: Float = 0.01f,
    val ceiling: Float = 1.0f,
    val backgroundEvidence: Float = 0.05f,
    val decisionTemperature: Float = 1.0f
) {
    fun applyTransfer(rawScore: Float): Float {
        if (rawScore <= 0f) return 0f // Zgodnie z wytycznymi - poza patchem lub zero to zero.
        
        var v = (rawScore - contrastPivot) * contrast + contrastPivot + bias
        v = v.coerceIn(0f, 1f)
        var s = Math.pow(v.toDouble(), gamma.toDouble()).toFloat()
        s = s.coerceIn(floor, ceiling)
        return s
    }
}
