package com.example.domain.environment

interface IdSource {
    fun generateId(): String
}

interface WallClock {
    fun currentTimeMillis(): Long
}

interface MonotonicClock {
    fun nanoTime(): Long
}

interface DiagnosticSessionContext {
    val currentSessionId: String
}
