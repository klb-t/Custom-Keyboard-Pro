package com.example.domain

import com.example.domain.input.*
import com.example.domain.model.*
import com.example.domain.security.*
import com.example.domain.sensitivity.*
import org.junit.Assert.*
import org.junit.Test

class SensitivityTest {

    @Test
    fun testProbabilityNormalization() {
        val transferSettings = ModelTransferSettings(backgroundEvidence = 0f)
        val sampler = ProbabilisticSampler(transferSettings)
        val metadata = PatchMetadata(1, 1, 1, 1, 1)
        
        val patchA = LocalSensitivityPatch("A", "A", CoordinateSpace.WORKSPACE, 0f, 0f, 10, 10, 10, ByteArray(100) { 64.toByte() }, InterpolationMode.NEAREST, metadata)
        val patchB = LocalSensitivityPatch("B", "B", CoordinateSpace.WORKSPACE, 0f, 0f, 10, 10, 10, ByteArray(100) { 128.toByte() }, InterpolationMode.NEAREST, metadata)
        val patchC = LocalSensitivityPatch("C", "C", CoordinateSpace.WORKSPACE, 0f, 0f, 10, 10, 10, ByteArray(100) { 0.toByte() }, InterpolationMode.NEAREST, metadata)

        val distribution = sampler.sample(5f, 5f, listOf(patchA, patchB, patchC), "evt1", 1)
        
        // With floor = 0.01f, even raw 64 (0.25) goes up. 
        // Let's use a simpler transfer for test: 
        val rawA = 64f / 255f
        val rawB = 128f / 255f
        
        val candA = distribution.candidates.find { it.elementId == "A" }
        val candB = distribution.candidates.find { it.elementId == "B" }
        
        assertNotNull(candA)
        assertNotNull(candB)
        
        val sA = transferSettings.applyTransfer(rawA)
        val sB = transferSettings.applyTransfer(rawB)
        
        val total = sA + sB
        
        assertEquals(sA / total, candA!!.score, 0.001f)
        assertEquals(sB / total, candB!!.score, 0.001f)
    }

    @Test
    fun testOverlap() {
        val transferSettings = ModelTransferSettings(backgroundEvidence = 0f)
        val sampler = ProbabilisticSampler(transferSettings)
        val metadata = PatchMetadata(1, 1, 1, 1, 1)
        
        val patchA = LocalSensitivityPatch("A", "A", CoordinateSpace.WORKSPACE, 0f, 0f, 10, 10, 10, ByteArray(100) { 255.toByte() }, InterpolationMode.NEAREST, metadata)
        val patchB = LocalSensitivityPatch("B", "B", CoordinateSpace.WORKSPACE, 0f, 0f, 10, 10, 10, ByteArray(100) { 255.toByte() }, InterpolationMode.NEAREST, metadata)
        
        val distribution = sampler.sample(5f, 5f, listOf(patchA, patchB), "evt1", 1)
        
        val candA = distribution.candidates.find { it.elementId == "A" }
        val candB = distribution.candidates.find { it.elementId == "B" }
        
        assertNotNull(candA)
        assertNotNull(candB)
        assertEquals(0.5f, candA!!.score, 0.001f)
        assertEquals(0.5f, candB!!.score, 0.001f)
    }

    @Test
    fun testOutsidePatch() {
        val sampler = ProbabilisticSampler(ModelTransferSettings())
        val metadata = PatchMetadata(1, 1, 1, 1, 1)
        val patchA = LocalSensitivityPatch("A", "A", CoordinateSpace.WORKSPACE, 10f, 10f, 10, 10, 10, ByteArray(100) { 255.toByte() }, InterpolationMode.NEAREST, metadata)
        
        val distribution = sampler.sample(5f, 5f, listOf(patchA), "evt1", 1)
        val candA = distribution.candidates.find { it.elementId == "A" }
        
        assertNull(candA) // Since (5,5) is outside origin (10,10) to (20,20)
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
        
        assertEquals("evt-bad-tap", obs.originalEventId)
        assertEquals("Y", obs.correctedElementId)
    }

    @Test
    fun testAdaptationSeparation() {
        val currentAdaptation = UserAdaptation()
        
        val learner = MinimalLearner(0.1f)
        val obs = CorrectionObservation("evt", Point(10f, 0f), null, "A", "B", 1, 1, 0, "SRC")
        
        val newAdaptation = learner.learnFromCorrection(obs, currentAdaptation)
        
        assertTrue(currentAdaptation.kernels.isEmpty())
        assertEquals(2, newAdaptation.kernels.size) // Positive for B, Negative for A
        assertEquals("B", newAdaptation.kernels[0].targetElementId)
        assertEquals("A", newAdaptation.kernels[1].targetElementId)
        assertTrue(newAdaptation.kernels[0].strength > 0f)
        assertTrue(newAdaptation.kernels[1].strength < 0f)
    }

    @Test
    fun testSpatialIndex() {
        val metadata = PatchMetadata(1, 1, 1, 1, 1)
        val index = SpatialCandidateIndex(10f, 100f, 100f)
        
        val patchA = LocalSensitivityPatch("pA", "A", CoordinateSpace.WORKSPACE, 0f, 0f, 15, 15, 15, ByteArray(225), InterpolationMode.NEAREST, metadata)
        val patchB = LocalSensitivityPatch("pB", "B", CoordinateSpace.WORKSPACE, 20f, 20f, 10, 10, 10, ByteArray(100), InterpolationMode.NEAREST, metadata)
        
        index.buildIndex(listOf(patchA, patchB))
        
        val candidates1 = index.getCandidates(5f, 5f)
        assertTrue(candidates1.any { it.patchId == "pA" })
        assertFalse(candidates1.any { it.patchId == "pB" })
        
        val candidates2 = index.getCandidates(25f, 25f)
        assertFalse(candidates2.any { it.patchId == "pA" })
        assertTrue(candidates2.any { it.patchId == "pB" })
    }
}
