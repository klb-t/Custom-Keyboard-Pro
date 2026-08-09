package com.example.domain.diagnostics

interface DiagnosticSink {
    fun onEvent(event: DiagnosticEvent)
    fun flush()
}

class DiagnosticBus(private val sinks: List<DiagnosticSink>) {
    fun publish(event: DiagnosticEvent) {
        // Redaction policy is applied at the sink level or here.
        sinks.forEach {
            try {
                it.onEvent(event)
            } catch (e: Exception) {
                // Sinks must not crash the bus
            }
        }
    }
}
