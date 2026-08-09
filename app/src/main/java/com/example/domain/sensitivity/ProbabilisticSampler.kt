package com.example.domain.sensitivity

import com.example.domain.model.Point
import kotlin.math.pow

class ProbabilisticSampler(
    val transferSettings: ModelTransferSettings = ModelTransferSettings()
) {
    fun sample(
        x: Float,
        y: Float,
        candidatePatches: List<LocalSensitivityPatch>,
        eventId: String,
        sourceRevision: Int
    ): KeyProbabilityDistribution {
        val candidates = mutableListOf<CandidateKey>()
        var sumScores = 0f
        
        for (patch in candidatePatches) {
            val raw = patch.sampleRaw(x, y)
            if (raw > 0f) {
                val transferred = transferSettings.applyTransfer(raw)
                if (transferred > 0f) {
                    candidates.add(CandidateKey(patch.elementId, transferred))
                    sumScores += transferred
                }
            }
        }
        
        // Background evidence
        val bgScore = transferSettings.backgroundEvidence
        sumScores += bgScore
        candidates.add(CandidateKey(BACKGROUND_KEY_ID, bgScore))
        
        // Temperature transform & normalization
        var temperatureSum = 0f
        val tempCandidates = candidates.map {
            val tempScore = it.score.pow(1f / transferSettings.decisionTemperature)
            temperatureSum += tempScore
            CandidateKey(it.elementId, tempScore)
        }
        
        val normalizedCandidates = tempCandidates.map {
            CandidateKey(it.elementId, it.score / temperatureSum)
        }.sortedByDescending { it.score }
        
        return KeyProbabilityDistribution(
            eventId = eventId,
            tapPoint = Point(x, y),
            candidates = normalizedCandidates,
            sourceRevision = sourceRevision,
            timestamp = System.currentTimeMillis()
        )
    }
    
    companion object {
        const val BACKGROUND_KEY_ID = "__BACKGROUND__"
    }
}
