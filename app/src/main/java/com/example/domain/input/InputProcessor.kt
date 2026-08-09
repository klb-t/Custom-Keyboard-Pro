package com.example.domain.input

/**
 * Input processor pipeline boundary (Zasada 22, 71).
 */
interface InputProcessor {
    /**
     * Zwraca true jeśli przetworzył zdarzenie (i nie należy przekazywać go dalej)
     * lub modyfikuje strumień zdarzeń.
     */
    fun process(event: InputEvent, context: ProcessorContext): Boolean
}

interface ProcessorContext {
    fun emit(event: InputEvent)
    fun dispatchAction(actionId: String)
}
