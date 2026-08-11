package com.example.domain.diagnostics

sealed interface DiagnosticValue {
    data class StringValue(val value: String) : DiagnosticValue
    data class LongValue(val value: Long) : DiagnosticValue
    data class DoubleValue(val value: Double) : DiagnosticValue
    data class BooleanValue(val value: Boolean) : DiagnosticValue
    object NullValue : DiagnosticValue
    data class ListValue(val items: List<DiagnosticValue>) : DiagnosticValue
    data class ObjectValue(val fields: Map<String, DiagnosticValue>) : DiagnosticValue
    object RedactedValue : DiagnosticValue
    object UnknownValue : DiagnosticValue
}

fun Any?.toDiagnosticValue(): DiagnosticValue = when (this) {
    null -> DiagnosticValue.NullValue
    is String -> DiagnosticValue.StringValue(this)
    is Long -> DiagnosticValue.LongValue(this)
    is Int -> DiagnosticValue.LongValue(this.toLong())
    is Double -> DiagnosticValue.DoubleValue(this)
    is Float -> DiagnosticValue.DoubleValue(this.toDouble())
    is Boolean -> DiagnosticValue.BooleanValue(this)
    is List<*> -> DiagnosticValue.ListValue(this.map { it.toDiagnosticValue() })
    is Map<*, *> -> {
        val mappedFields = this.entries.associate { (k, v) -> 
            (k as? String ?: k.toString()) to v.toDiagnosticValue()
        }
        DiagnosticValue.ObjectValue(mappedFields)
    }
    else -> DiagnosticValue.StringValue(this.toString()) // Fallback
}
