package com.example.domain.input

import com.example.domain.sensitivity.ProbabilisticSampler
import com.example.domain.sensitivity.ArgmaxDecisionPolicy
import com.example.domain.sensitivity.DecisionResult
import com.example.domain.sensitivity.SpatialCandidateIndex
import com.example.domain.diagnostics.*
import com.example.domain.action.ActionRef
import com.example.domain.model.Trigger
import com.example.domain.environment.IdSource
import com.example.domain.environment.MonotonicClock
import com.example.domain.environment.DiagnosticSessionContext

class ProbabilisticInputProcessor(
    private val sampler: ProbabilisticSampler,
    private val decisionPolicy: ArgmaxDecisionPolicy,
    private val spatialIndexProvider: () -> SpatialCandidateIndex,
    private val actionResolver: (String, Trigger) -> ActionRef?,
    private val fallbackHandler: (PointerEvent) -> ProcessorResult,
    private val coordinateTransform: CoordinateTransform = CoordinateTransform(),
    private val diagnosticBus: DiagnosticBus? = null,
    private val idSource: IdSource,
    private val clock: MonotonicClock,
    private val sessionContext: DiagnosticSessionContext
) : InputProcessor {

    override fun process(event: InputEvent, context: ProcessorContext): ProcessorResult {
        if (event !is PointerEvent || event.phase != PointerPhase.UP) {
            return ProcessorResult.Pass
        }

        val startNanos = clock.nanoTime()
        val correlationId = "input_${event.timestamp}_${event.sourceId}_${idSource.generateId().take(4)}"

        // Transform to workspace space for spatial index
        val (workX, workY) = coordinateTransform.transform(event.x, event.y, event.coordinateSpace, CoordinateSpace.WORKSPACE)

        val spatialIndex = spatialIndexProvider()
        val candidatePatches = spatialIndex.getCandidates(workX, workY)
        
        val distribution = sampler.sample(
            x = workX,
            y = workY,
            candidatePatches = candidatePatches,
            eventId = correlationId,
            sourceRevision = context.activeLayoutRevision
        )
        
        val decision = decisionPolicy.decide(distribution)
        
        val durationMs = (clock.nanoTime() - startNanos) / 1_000_000.0
        
        diagnosticBus?.publish(DiagnosticEvent(
            eventId = idSource.generateId(),
            sessionId = sessionContext.currentSessionId,
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.INPUT,
            component = "ProbabilisticInputProcessor",
            eventType = "ProcessTap",
            correlationId = correlationId,
            fields = mapOf(
                "tapX" to workX.toDiagnosticValue(),
                "tapY" to workY.toDiagnosticValue(),
                "candidatesCount" to candidatePatches.size.toDiagnosticValue(),
                "winner" to ((decision as? DecisionResult.Winner)?.elementId).toDiagnosticValue(),
                "durationMs" to durationMs.toDiagnosticValue()
            )
        ))
        
        return when (decision) {
            is DecisionResult.Winner -> {
                val actionRef = actionResolver(decision.elementId, Trigger.Tap)
                if (actionRef != null) {
                    ProcessorResult.DispatchAction(actionRef)
                } else {
                    fallbackHandler(event)
                }
            }
            is DecisionResult.Fallback -> {
                fallbackHandler(event)
            }
        }
    }
}
