package com.example.domain.input

import com.example.domain.sensitivity.ProbabilisticSampler
import com.example.domain.sensitivity.ArgmaxDecisionPolicy
import com.example.domain.sensitivity.DecisionResult
import com.example.domain.sensitivity.SpatialCandidateIndex
import com.example.domain.diagnostics.*
import java.util.UUID

class ProbabilisticInputProcessor(
    private val sampler: ProbabilisticSampler,
    private val decisionPolicy: ArgmaxDecisionPolicy,
    private val spatialIndexProvider: () -> SpatialCandidateIndex,
    private val fallbackHandler: (PointerEvent) -> ProcessorResult,
    private val diagnosticBus: DiagnosticBus? = null
) : InputProcessor {

    override fun process(event: InputEvent, context: ProcessorContext): ProcessorResult {
        if (event !is PointerEvent || event.phase != PointerPhase.UP) {
            return ProcessorResult.Pass // Only handle UP/taps for simple probabilistic hit testing
        }

        val startNanos = System.nanoTime()
        val correlationId = "input_${event.timestamp}_${event.sourceId}_${UUID.randomUUID().toString().take(4)}"

        val spatialIndex = spatialIndexProvider()
        val candidatePatches = spatialIndex.getCandidates(event.x, event.y)
        
        val distribution = sampler.sample(
            x = event.x,
            y = event.y,
            candidatePatches = candidatePatches,
            eventId = correlationId,
            sourceRevision = context.activeLayoutRevision
        )
        
        val decision = decisionPolicy.decide(distribution)
        
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
        
        diagnosticBus?.publish(DiagnosticEvent(
            eventId = UUID.randomUUID().toString(),
            sessionId = "current_session",
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.INPUT,
            component = "ProbabilisticInputProcessor",
            eventType = "ProcessTap",
            correlationId = correlationId,
            fields = mapOf(
                "tapX" to event.x,
                "tapY" to event.y,
                "candidatesCount" to candidatePatches.size,
                "winner" to (decision as? DecisionResult.Winner)?.elementId,
                "durationMs" to durationMs
            )
        ))
        
        return when (decision) {
            is DecisionResult.Winner -> {
                ProcessorResult.DispatchAction(decision.elementId)
            }
            is DecisionResult.Fallback -> {
                fallbackHandler(event)
            }
        }
    }
}
