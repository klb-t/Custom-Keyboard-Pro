package com.example.domain.composition

/**
 * Composition/preedit boundary (Zasada 31).
 */
data class CompositionSession(
    val uncommittedText: String = "",
    val candidates: List<String> = emptyList(),
    val activeLanguageCode: String? = null
)

/**
 * Contextual transform/rule-engine interface (Zasada 32).
 */
interface ContextualTransformEngine {
    fun applyRules(
        input: String,
        contextBefore: String,
        session: CompositionSession
    ): TransformResult
}

data class TransformResult(
    val updatedComposition: CompositionSession,
    val textToCommit: String? = null,
    val replacementRange: IntRange? = null
)
