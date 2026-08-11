package com.example.domain

import com.example.domain.diagnostics.*
import com.example.domain.diagnostics.toDiagnosticValue
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DiagnosticsTest {

    @Test
    fun testStructuredEventDelivery() {
        val sink = RingBufferSink(10)
        val bus = DiagnosticBus(listOf(sink))
        
        bus.publish(DiagnosticEvent(
            eventId = "evt1",
            sessionId = "session1",
            level = DiagnosticLevel.INFO,
            category = DiagnosticCategory.INPUT,
            component = "Test",
            eventType = "TestEvent",
            fields = mapOf("testField" to "testValue".toDiagnosticValue())
        ))
        
        val events = sink.getEvents()
        assertEquals(1, events.size)
        assertEquals("evt1", events[0].eventId)
        assertEquals("testValue", (events[0].fields["testField"] as DiagnosticValue.StringValue).value)
    }

    @Test
    fun testRingBufferEviction() {
        val sink = RingBufferSink(3)
        val bus = DiagnosticBus(listOf(sink))
        
        bus.publish(DiagnosticEvent("evt1", "session1", level = DiagnosticLevel.INFO, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        bus.publish(DiagnosticEvent("evt2", "session1", level = DiagnosticLevel.INFO, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        bus.publish(DiagnosticEvent("evt3", "session1", level = DiagnosticLevel.INFO, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        bus.publish(DiagnosticEvent("evt4", "session1", level = DiagnosticLevel.INFO, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        
        val events = sink.getEvents()
        assertEquals(3, events.size)
        assertEquals("evt2", events[0].eventId)
        assertEquals("evt4", events[2].eventId)
    }

    @Test
    fun testRingBufferFiltering() {
        val sink = RingBufferSink(10, minLevel = DiagnosticLevel.WARN)
        val bus = DiagnosticBus(listOf(sink))
        
        bus.publish(DiagnosticEvent("evt1", "session1", level = DiagnosticLevel.INFO, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        bus.publish(DiagnosticEvent("evt2", "session1", level = DiagnosticLevel.WARN, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        bus.publish(DiagnosticEvent("evt3", "session1", level = DiagnosticLevel.ERROR, category = DiagnosticCategory.INPUT, component = "Test", eventType = "T"))
        
        val events = sink.getEvents()
        assertEquals(2, events.size)
        assertEquals("evt2", events[0].eventId)
        assertEquals("evt3", events[1].eventId)
    }

    @Test
    fun testProbeLifecycleAndSnapshot() {
        val registry = DebugProbeRegistry()
        var dynamicValue = "initial"
        
        val probe = DebugProbe(
            id = "probe1",
            label = "Test Probe",
            category = DiagnosticCategory.STATE,
            valueSupplier = { dynamicValue }
        )
        
        registry.register(probe)
        
        val snapshot1 = registry.snapshot()
        assertEquals("initial", snapshot1["probe1"])
        
        dynamicValue = "changed"
        val snapshot2 = registry.snapshot()
        assertEquals("changed", snapshot2["probe1"])
        
        registry.unregister("probe1")
        val snapshot3 = registry.snapshot()
        assertNull(snapshot3["probe1"])
    }
}
