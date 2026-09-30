package com.example.core.phone

import org.junit.Assert.*
import org.junit.Test

class SensorSessionPolicyTest {
    @Test fun uiRateIsBoundedEvenIfTheHardwareReportsFaster() {
        val policy = SensorSessionPolicy(20, 1, 100)
        val delivered = (100L until 1100L).count { policy.deliver(it, floatArrayOf(1f)) != null }
        assertEquals(20, delivered)
        assertEquals(1000L, policy.events)
        assertNull(policy.deliver(1100, floatArrayOf(1f)))
        assertTrue(policy.expired(1100))
    }
    @Test fun invalidClockAndValuesDoNotBecomeMeasurements() {
        val policy = SensorSessionPolicy(10, 30, 100)
        assertNull(policy.deliver(99, floatArrayOf(1f)))
        assertNull(policy.deliver(100, floatArrayOf(Float.NaN)))
        assertNull(policy.deliver(100, floatArrayOf(Float.POSITIVE_INFINITY)))
        assertNull(policy.deliver(100, FloatArray(65)))
        assertNull(policy.deliver(100, floatArrayOf()))
        assertEquals(0L, policy.events)
        assertArrayEquals(floatArrayOf(2f), policy.deliver(100, floatArrayOf(2f)), 0f)
    }
    @Test fun readingsAreCopiedAndBoundedBeforeDelivery() {
        val input = FloatArray(32) { it.toFloat() }
        val policy = SensorSessionPolicy(1, 300, 0)
        val output = policy.deliver(0, input)!!
        input[0] = 100f
        assertEquals(16, output.size)
        assertEquals(0f, output[0], 0f)
        assertNull(policy.deliver(999, input))
        assertNotNull(policy.deliver(1000, input))
    }
    @Test fun invalidSessionLimitsFailBeforeRegistration() {
        listOf(0 to 30, 21 to 30, 10 to 0, 10 to 301).forEach { (hz, seconds) -> assertTrue(runCatching { SensorSessionPolicy(hz, seconds, 0) }.isFailure) }
    }
    @Test fun aQueuedSampleFromAnOldSessionCannotBeAttributedToARearmedSession() {
        val policy = SensorSessionPolicy(10, 30, 100)
        assertNull(policy.deliver(105, floatArrayOf(1f), observedAtMs = 99))
        assertNull(policy.deliver(105, floatArrayOf(1f), observedAtMs = 106))
        assertEquals(0L, policy.events)
        assertNotNull(policy.deliver(105, floatArrayOf(2f), observedAtMs = 100))
        val boundary = SensorSessionBoundary(100_500_000)
        assertFalse(boundary.owns(100_499_999, 100_900_000))
        assertTrue(boundary.owns(100_500_000, 100_900_000))
        assertFalse(boundary.owns(100_900_001, 100_900_000))
    }
    @Test fun physiologicalPrivateHeadTrackerAndUnknownSensorTypesAreNotAuthorizedByInventory() {
        listOf(21, 31, 37, 26, 27, 65536, 99999).forEach { assertFalse("Type $it", SensorProfiles.audited(it)) }
        listOf(1, 18, 19, 34, 36, 38, 41, 42).forEach { assertTrue("Type $it", SensorProfiles.audited(it)) }
    }
}
