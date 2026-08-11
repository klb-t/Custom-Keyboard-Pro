package com.example.domain.diagnostics

import java.util.concurrent.Executors

class SessionTraceSink(private val fileWriter: (String) -> Unit) : DiagnosticSink {
    private val executor = Executors.newSingleThreadExecutor()
    override var isHealthy: Boolean = true
        private set

    override fun onEvent(event: DiagnosticEvent) {
        if (!isHealthy) return
        
        // Redact here if needed.
        if (event.sensitivityClassification == SensitivityClassification.PRIVATE_CONTENT || 
            event.sensitivityClassification == SensitivityClassification.SECRET) {
            return
        }

        // Full serialization of structured event
        val fieldsJson = event.fields.entries.joinToString(",") { 
            "\"${it.key}\": \"${it.value}\"" // simplistic JSON, would use proper Moshi/Gson
        }
        val eventJson = """{"eventId": "${event.eventId}", "type": "${event.eventType}", "category": "${event.category}", "fields": {$fieldsJson}}"""
        
        executor.submit {
            try {
                fileWriter(eventJson)
            } catch (e: Exception) {
                isHealthy = false
            }
        }
    }

    override fun flush() {
        if (!isHealthy) return
        executor.submit {
            // flush actual file writer
        }
    }
}
