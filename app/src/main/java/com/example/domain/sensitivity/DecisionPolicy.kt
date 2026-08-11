package com.example.domain.sensitivity

import kotlin.math.ln

interface DecisionPolicy {
    fun decide(distribution: KeyProbabilityDistribution): DecisionResult
}

sealed interface DecisionResult {
    data class Winner(val elementId: String, val metrics: DecisionMetrics) : DecisionResult
    object Fallback : DecisionResult
}

data class DecisionMetrics(
    val top1Prob: Float,
    val top2Prob: Float?,
    val margin: Float?,
    val entropy: Float,
    val relevantCandidatesCount: Int
)

class ArgmaxDecisionPolicy(private val fallbackThreshold: Float = 0f) : DecisionPolicy {
    override fun decide(distribution: KeyProbabilityDistribution): DecisionResult {
        if (distribution.candidates.isEmpty()) return DecisionResult.Fallback
        
        val sorted = distribution.candidates.sortedByDescending { it.score }
        val top1 = sorted[0]
        if (top1.score <= fallbackThreshold) return DecisionResult.Fallback
        
        val top2 = sorted.getOrNull(1)
        val margin = if (top2 != null) top1.score - top2.score else null
        
        var entropy = 0f
        for (c in sorted) {
            if (c.score > 0f) {
                entropy -= c.score * ln(c.score)
            }
        }
        
        val metrics = DecisionMetrics(
            top1Prob = top1.score,
            top2Prob = top2?.score,
            margin = margin,
            entropy = entropy, 
            relevantCandidatesCount = sorted.size
        )
        
        return DecisionResult.Winner(top1.elementId, metrics)
    }
}
