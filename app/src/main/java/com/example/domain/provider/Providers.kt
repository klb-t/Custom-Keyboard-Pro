package com.example.domain.provider

import com.example.domain.clipboard.ContentRepresentation

enum class CapabilityState {
    UNAVAILABLE, AVAILABLE_COLD, LOADING, READY, BUSY, DEGRADED, FAILED
}

interface Capability<T> {
    val state: CapabilityState
    val providerId: String
    val isLocal: Boolean
    fun getInterface(): T?
}

interface LayoutRecognitionProvider {
    suspend fun recognize(imageRef: ContentRepresentation): String?
}

interface TextGenerationProvider {
    suspend fun generate(prompt: String): String?
}

interface OCRProvider {
    suspend fun recognizeText(imageRef: ContentRepresentation): String?
}

interface ContextProvider {
    val providerId: String
    val isAvailable: Boolean
    val sensitivityClassification: SensitivityLevel
    
    suspend fun getContext(): ExtractedContext?
}

enum class SensitivityLevel { LOW, MEDIUM, HIGH, RESTRICTED }

data class ExtractedContext(
    val content: String,
    val timestamp: Long,
    val provenanceId: String
)
