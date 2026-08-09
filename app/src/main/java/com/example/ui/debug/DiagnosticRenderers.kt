package com.example.ui.debug

import com.example.domain.model.Point
import com.example.domain.sensitivity.KeyProbabilityDistribution

interface DiagnosticRenderer {
    val name: String
    fun render()
}

// These are logical models of what would be rendered, since we can't draw Canvas here in domain.
sealed class DiagnosticMap {
    data class RawSensitivityMap(val patchId: String, val rawValues: ByteArray) : DiagnosticMap()
    data class EffectiveSensitivityMap(val patchId: String, val effectiveValues: FloatArray) : DiagnosticMap()
    data class WinnerRegionMap(val regions: Map<String, List<Point>>) : DiagnosticMap()
    data class EntropyMap(val entropyValues: FloatArray) : DiagnosticMap()
    data class CompositeMap(val distributions: List<KeyProbabilityDistribution>) : DiagnosticMap()
}
