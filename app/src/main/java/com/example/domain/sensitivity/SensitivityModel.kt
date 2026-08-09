package com.example.domain.sensitivity

import com.example.domain.model.Point

data class CandidateKey(
    val elementId: String,
    val score: Float
)

data class KeyProbabilityDistribution(
    val eventId: String,
    val tapPoint: Point,
    val candidates: List<CandidateKey>,
    val sourceRevision: Int,
    val timestamp: Long
)
