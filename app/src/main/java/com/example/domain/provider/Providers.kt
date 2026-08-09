package com.example.domain.provider

/**
 * Generic capability lifecycle/health model (Zasada 54).
 */
enum class CapabilityState {
    UNAVAILABLE,
    AVAILABLE_COLD,
    LOADING,
    READY,
    BUSY,
    DEGRADED,
    FAILED
}

interface Capability<T> {
    val state: CapabilityState
    val providerId: String
    val isLocal: Boolean // Zasada 63
    
    fun getInterface(): T?
}

/**
 * Provider-independent AI/OCR/ASR domain interfaces (Zasada 64).
 */
interface LayoutRecognitionProvider {
    suspend fun recognize(imageBytes: ByteArray): String?
}

interface TextGenerationProvider {
    suspend fun generate(prompt: String): String?
}

interface OCRProvider {
    suspend fun recognizeText(imageBytes: ByteArray): String?
}

/**
 * ContextProvider interface with provenance (Zasada 66).
 */
interface ContextProvider {
    val providerId: String
    val isAvailable: Boolean
    val sensitivityClassification: SensitivityLevel
    
    fun getContext(): ExtractedContext?
}

enum class SensitivityLevel { LOW, MEDIUM, HIGH, RESTRICTED }

data class ExtractedContext(
    val content: String,
    val timestamp: Long,
    val provenanceId: String
)
