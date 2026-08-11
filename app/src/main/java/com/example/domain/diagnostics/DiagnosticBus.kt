package com.example.domain.diagnostics

interface DiagnosticSink {
    fun onEvent(event: DiagnosticEvent)
    fun flush()
    val isHealthy: Boolean
}

class DiagnosticBus(private val sinks: List<DiagnosticSink>) {
    fun publish(event: DiagnosticEvent) {
        // Redaction policy is applied at the sink level or here.
        sinks.forEach { sink ->
            if (sink.isHealthy) {
                try {
                    sink.onEvent(event)
                } catch (e: Exception) {
                    // Mark as degraded (implementation detail of sink wrapper or sink itself)
                    System.err.println("Diagnostic sink failed: ${e.message}")
                }
            }
        }
    }
}
