package com.example.core.phone

/** Bounds UI delivery independently of the hardware/OEM sensor sampling rate. */
class SensorSessionPolicy(val hz: Int, val seconds: Int, private val startedAtMs: Long) {
    init { require(hz in 1..20 && seconds in 1..300 && startedAtMs >= 0) }
    private var lastDeliveryMs: Long? = null
    var events: Long = 0
        private set
    val deadlineMs = startedAtMs + seconds * 1000L
    fun expired(nowMs: Long) = nowMs >= deadlineMs
    fun deliver(nowMs: Long, values: FloatArray, observedAtMs: Long = nowMs): FloatArray? {
        // SensorEvent timestamps share elapsed-realtime's boot origin. A queued event
        // from an earlier session must never become a sample of a freshly armed one.
        if (nowMs < startedAtMs || observedAtMs < startedAtMs || observedAtMs > nowMs || expired(nowMs) || values.isEmpty() || values.size > 64 || values.any { !it.isFinite() }) return null
        events++
        val previous = lastDeliveryMs
        if (previous != null && nowMs - previous < (1000L + hz - 1L) / hz) return null
        lastDeliveryMs = nowMs
        return values.take(16).toFloatArray()
    }
}

object SensorProfiles {
    /** Public permission-audited numeric types; new/vendor/health types stay inspect-only. */
    fun audited(type: Int): Boolean = type in (1..20) || type in setOf(29, 30, 34, 35, 36, 38, 39, 40, 41, 42)
}

/** Nanosecond ownership prevents sub-millisecond queued samples crossing sessions. */
class SensorSessionBoundary(private val startedAtNanos: Long) {
    init { require(startedAtNanos >= 0) }
    fun owns(observedAtNanos: Long, receivedAtNanos: Long): Boolean =
        observedAtNanos >= startedAtNanos && observedAtNanos <= receivedAtNanos
}
