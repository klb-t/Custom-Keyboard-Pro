package com.example.domain.input

import com.example.domain.action.ActionRef

sealed interface ProcessorResult {
    object Pass : ProcessorResult
    object Consume : ProcessorResult
    data class Replace(val newEvents: List<InputEvent>) : ProcessorResult
    data class Emit(val additionalEvents: List<InputEvent>) : ProcessorResult
    data class DispatchAction(val actionRef: ActionRef) : ProcessorResult
    data class UpdateState(val stateMutations: Map<String, Any>) : ProcessorResult
}

interface InputProcessor {
    fun process(event: InputEvent, context: ProcessorContext): ProcessorResult
}

interface ProcessorContext {
    val activeLayoutRevision: Int
    // ...
}
