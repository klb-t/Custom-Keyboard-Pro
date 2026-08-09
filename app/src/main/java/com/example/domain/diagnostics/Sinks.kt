package com.example.domain.diagnostics

import java.util.ArrayDeque

class RingBufferSink(val capacity: Int, val minLevel: DiagnosticLevel = DiagnosticLevel.TRACE) : DiagnosticSink {
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)
    
    override fun onEvent(event: DiagnosticEvent) {
        if (event.level.severity < minLevel.severity) return
        
        synchronized(this) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(event)
        }
    }
    
    override fun flush() {}
    
    fun getEvents(): List<DiagnosticEvent> = synchronized(this) { buffer.toList() }
}
