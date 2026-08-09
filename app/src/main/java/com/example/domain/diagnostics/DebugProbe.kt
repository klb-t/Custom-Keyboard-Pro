package com.example.domain.diagnostics

data class DebugProbe<T>(
    val id: String,
    val label: String,
    val category: DiagnosticCategory,
    val valueSupplier: () -> T,
    val sensitivityClassification: SensitivityClassification = SensitivityClassification.INTERNAL
)

class DebugProbeRegistry {
    private val probes = mutableMapOf<String, DebugProbe<*>>()
    
    fun <T> register(probe: DebugProbe<T>) {
        synchronized(probes) {
            probes[probe.id] = probe
        }
    }
    
    fun unregister(probeId: String) {
        synchronized(probes) {
            probes.remove(probeId)
        }
    }
    
    fun snapshot(): Map<String, Any?> {
        val currentProbes = synchronized(probes) { probes.values.toList() }
        val result = mutableMapOf<String, Any?>()
        for (probe in currentProbes) {
            result[probe.id] = try {
                probe.valueSupplier()
            } catch (e: Exception) {
                "Error computing probe"
            }
        }
        return result
    }
}
