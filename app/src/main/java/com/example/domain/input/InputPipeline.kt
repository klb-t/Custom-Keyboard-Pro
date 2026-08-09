package com.example.domain.input

import com.example.domain.sensitivity.ProbabilisticSampler
import com.example.domain.sensitivity.ArgmaxDecisionPolicy
import com.example.domain.sensitivity.DecisionResult
import com.example.domain.sensitivity.RasterSensitivityMap

class ProbabilisticInputProcessor(
    private val sampler: ProbabilisticSampler,
    private val decisionPolicy: ArgmaxDecisionPolicy,
    private val rasterProvider: () -> List<RasterSensitivityMap>,
    private val fallbackHandler: (PointerEvent) -> ProcessorResult
) : InputProcessor {

    override fun process(event: InputEvent, context: ProcessorContext): ProcessorResult {
        if (event !is PointerEvent || event.phase != PointerPhase.UP) {
            return ProcessorResult.Pass // Only handle UP/taps for simple probabilistic hit testing
        }

        val rasterMaps = rasterProvider()
        
        // coordinate transform processor byłby osobno, tu zakładamy że event jest w znormalizowanych lub lokalnych współrzędnych
        val distribution = sampler.sample(
            x = event.x,
            y = event.y,
            rasterMaps = rasterMaps,
            eventId = "${event.timestamp}_${event.sourceId}",
            sourceRevision = context.activeLayoutRevision
        )
        
        val decision = decisionPolicy.decide(distribution)
        
        return when (decision) {
            is DecisionResult.Winner -> {
                // Return action dispatch instruction
                ProcessorResult.DispatchAction(decision.elementId) // simplified, usually it would dispatch the action bound to the trigger
            }
            is DecisionResult.Fallback -> {
                fallbackHandler(event)
            }
        }
    }
}
