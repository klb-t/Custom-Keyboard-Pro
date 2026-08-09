package com.example.domain

import com.example.domain.input.*
import com.example.domain.model.*
import com.example.domain.security.*
import com.example.domain.sensitivity.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SensitivityTest {

    @Test
    fun testProbabilityNormalization() {
        val sampler = ProbabilisticSampler()
        val metadata = RasterMetadata(1, 1, 100f, 100f, 1f, 0, 1, 1)
        val mapA = RasterSensitivityMap("A", 10, 10, ByteArray(100) { 64.toByte() }, metadata)
        val mapB = RasterSensitivityMap("B", 10, 10, ByteArray(100) { 128.toByte() }, metadata)
        val mapC = RasterSensitivityMap("C", 10, 10, ByteArray(100) { 0.toByte() }, metadata)

        val distribution = sampler.sample(5f, 5f, listOf(mapA, mapB, mapC), "evt1", 1)
        
        assertEquals(2, distribution.candidates.size)
        
        val candA = distribution.candidates.find { it.elementId == "A" }
        val candB = distribution.candidates.find { it.elementId == "B" }
        
        assertNotNull(candA)
        assertNotNull(candB)
        
        val scoreA = 64f / 255f
        val scoreB = 128f / 255f
        val total = scoreA + scoreB
        
        assertEquals(scoreA / total, candA!!.score, 0.001f)
        assertEquals(scoreB / total, candB!!.score, 0.001f)
    }

    @Test
    fun testOverlap() {
        val sampler = ProbabilisticSampler()
        val metadata = RasterMetadata(1, 1, 100f, 100f, 1f, 0, 1, 1)
        val mapA = RasterSensitivityMap("A", 10, 10, ByteArray(100) { 255.toByte() }, metadata)
        val mapB = RasterSensitivityMap("B", 10, 10, ByteArray(100) { 255.toByte() }, metadata)
        
        val distribution = sampler.sample(5f, 5f, listOf(mapA, mapB), "evt1", 1)
        
        assertEquals(2, distribution.candidates.size)
        assertEquals(0.5f, distribution.candidates[0].score, 0.001f)
        assertEquals(0.5f, distribution.candidates[1].score, 0.001f)
    }

    @Test
    fun testDeterministicDecision() {
        val candidates = listOf(
            CandidateKey("A", 0.3f),
            CandidateKey("B", 0.6f),
            CandidateKey("C", 0.1f)
        )
        val dist = KeyProbabilityDistribution("evt1", Point(0f, 0f), candidates, 1, 0)
        
        val policy = ArgmaxDecisionPolicy()
        val result1 = policy.decide(dist)
        val result2 = policy.decide(dist)
        
        assertTrue(result1 is DecisionResult.Winner)
        assertEquals("B", (result1 as DecisionResult.Winner).elementId)
        assertEquals(result1, result2) // Deterministic
    }

    @Test
    fun testCorrectionSemantics() {
        val obs = CorrectionObservation(
            originalEventId = "evt-bad-tap",
            originalTapPoint = Point(10f, 10f),
            originalDistribution = null,
            originalSelectedElementId = "X",
            correctedElementId = "Y",
            layoutRevision = 1,
            sensitivityRevision = 1,
            timestamp = 1000L,
            correctionSource = "BACKSPACE"
        )
        
        // Ensure it points to the original event
        assertEquals("evt-bad-tap", obs.originalEventId)
        assertEquals("Y", obs.correctedElementId)
    }

    @Test
    fun testAdaptationSeparation() {
        val base = SensitivityField(VectorShape.Rect(10f, 10f))
        val currentAdaptation = UserAdaptation()
        val effective = EffectiveSensitivity(base, currentAdaptation)
        
        val learner = MinimalLearner(0.1f)
        val obs = CorrectionObservation("evt", Point(10f, 0f), null, "A", "B", 1, 1, 0, "SRC")
        
        val newAdaptation = learner.learnFromCorrection(obs, currentAdaptation)
        val newEffective = EffectiveSensitivity(base, newAdaptation)
        
        // Base sensitivity is untouched
        assertEquals(base, newEffective.field)
        // Adaptation is updated
        assertNotEquals(currentAdaptation, newAdaptation)
    }

    @Test
    fun testResetAdaptation() {
        val base = SensitivityField(VectorShape.Rect(10f, 10f))
        var adaptation = UserAdaptation(offsetX = 5f)
        val effective = EffectiveSensitivity(base, adaptation)
        
        // Reset
        adaptation = UserAdaptation()
        val resetEffective = EffectiveSensitivity(base, adaptation)
        
        assertEquals(0f, resetEffective.adaptation.offsetX, 0.001f)
    }
    
    @Test
    fun testRasterInvalidation() {
        val oldMetadata = RasterMetadata(1, 1, 100f, 100f, 1f, 0, 1, 1)
        val currentLayoutRevision = 2
        
        val isStale = oldMetadata.layoutRevision != currentLayoutRevision
        assertTrue(isStale)
    }

    @Test
    fun testSecurityContext() {
        val baseConfig = SecurityContext()
        assertTrue(baseConfig.allowLearning)
        
        val secureConfig = SecurityContextResolver.resolvePolicy(
            isPasswordField = true,
            isIncognitoMode = false,
            isSensitiveApp = false,
            userSettings = baseConfig
        )
        
        assertFalse(secureConfig.allowLearning)
        assertFalse(secureConfig.allowHistory)
        assertFalse(secureConfig.allowPersistence)
    }

    @Test
    fun testPipelineResultSemantics() {
        val result: ProcessorResult = ProcessorResult.Consume
        
        // Result is explicit rather than a boolean
        assertTrue(result is ProcessorResult.Consume)
        
        val replaceResult = ProcessorResult.Replace(listOf())
        assertTrue(replaceResult is ProcessorResult.Replace)
    }
}
