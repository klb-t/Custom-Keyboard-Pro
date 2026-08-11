package com.example.domain

import com.example.domain.action.ActionRef
import com.example.domain.input.*
import com.example.domain.sensitivity.*
import com.example.domain.model.Trigger
import com.example.domain.environment.*
import org.junit.Assert.*
import org.junit.Test

class ArchitectureTest {
    @Test
    fun testElementIdDiffersFromActionId() {
        val transferSettings = ModelTransferSettings()
        val sampler = ProbabilisticSampler(transferSettings)
        val policy = ArgmaxDecisionPolicy(0.1f)
        val index = SpatialCandidateIndex(100f, 1000f, 1000f).apply {
            val patch = LocalSensitivityPatch(
                patchId = "patch1",
                elementId = "element_A",
                coordinateSpace = CoordinateSpace.WORKSPACE,
                originX = 0f,
                originY = 0f,
                width = 100,
                height = 100,
                stride = 100,
                data8bit = ByteArray(10000) { 255.toByte() },
                interpolationMode = InterpolationMode.NEAREST,
                metadata = PatchMetadata(1, 1, 1, 1, 1)
            )
            buildIndex(listOf(patch))
        }
        
        val processor = ProbabilisticInputProcessor(
            sampler = sampler,
            decisionPolicy = policy,
            spatialIndexProvider = { index },
            actionResolver = { elementId, trigger -> 
                if (elementId == "element_A" && trigger == Trigger.Tap) ActionRef("action_X") else null
            },
            fallbackHandler = { ProcessorResult.Pass },
            diagnosticBus = null,
            idSource = object : IdSource { override fun generateId() = "id_1" },
            clock = object : MonotonicClock { override fun nanoTime() = 1000L },
            sessionContext = object : DiagnosticSessionContext { override val currentSessionId = "sess_1" }
        )
        
        val event = PointerEvent(
            timestamp = 0L,
            sourceId = "ptr1",
            pointerId = 0,
            x = 50f,
            y = 50f,
            coordinateSpace = CoordinateSpace.WORKSPACE,
            pressure = 1.0f,
            phase = PointerPhase.UP
        )
        
        val context = object : ProcessorContext {
            override val activeLayoutRevision = 1
        }
        
        val result = processor.process(event, context)
        
        assertTrue(result is ProcessorResult.DispatchAction)
        assertEquals("action_X", (result as ProcessorResult.DispatchAction).actionRef.actionId)
    }
}
