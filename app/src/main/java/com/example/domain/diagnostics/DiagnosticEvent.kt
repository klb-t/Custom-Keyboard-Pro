package com.example.domain.diagnostics

enum class DiagnosticLevel(val severity: Int) {
    TRACE(1), DEBUG(2), INFO(3), WARN(4), ERROR(5), FATAL(6)
}

enum class DiagnosticCategory {
    PROCESS, LIFECYCLE, IME, EDITOR, INPUT, GESTURE, COORDINATE, GEOMETRY,
    INSETS, LAYOUT, STATE, SENSITIVITY, RASTER, PROBABILITY, DECISION,
    ACTION, COMPOSITION, LEARNING, CLIPBOARD, SECURITY, PROVIDER, CAPABILITY,
    STORAGE, PERFORMANCE, FALLBACK, ERROR
}

enum class SensitivityClassification {
    PUBLIC_DIAGNOSTIC, INTERNAL, SENSITIVE_METADATA, PRIVATE_CONTENT, SECRET
}

data class DiagnosticEvent(
    val eventId: String,
    val sessionId: String,
    val timestampWall: Long = System.currentTimeMillis(),
    val timestampMonotonic: Long = System.nanoTime(),
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val component: String,
    val eventType: String,
    val correlationId: String? = null,
    val parentCorrelationId: String? = null,
    val fields: Map<String, DiagnosticValue> = emptyMap(),
    val sensitivityClassification: SensitivityClassification = SensitivityClassification.INTERNAL
)
