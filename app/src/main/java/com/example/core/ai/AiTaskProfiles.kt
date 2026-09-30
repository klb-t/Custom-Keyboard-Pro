package com.example.core.ai

import com.example.core.config.Settings
import com.example.core.json.JsonFields
import org.json.JSONObject

/** A task is a use of the existing client, not a provider or permission grant. */
enum class AiRequestTask(val id: String, val label: String, val defaultMaxTokens: Int?) {
    WRITING("writing", "Writing and chat", null),
    GOAL_PLAN("goal_plan", "Assistant planning", 4096),
    REWRITE("rewrite", "Rewrite or transform text", 800),
    THEME("theme", "Generate a theme", 900),
    OCR("ocr", "Recognize text in an image", 4096),
    PANEL("panel", "Generate a panel", 1200),
    INLINE("inline", "Inline suggestions", 24),
    VOICE_COMMAND("voice_command", "Interpret a voice command", 200)
}

data class AiTaskParameters(val maxTokens: Int? = null, val temperature: Float? = null)
data class AiResolvedParameters(
    val maxTokens: Int, val temperature: Float,
    val tokenSource: String, val temperatureSource: String
)

/** Sparse task parameters share one codec, resolver and UI. No arbitrary HTTP fields. */
object AiTaskProfiles {
    const val MAX_DOCUMENT = 16_384
    val tokenRange = 8..8192

    fun parse(raw: String): Map<AiRequestTask, AiTaskParameters> {
        if (raw.isBlank()) return emptyMap()
        JsonFields.check(raw, MAX_DOCUMENT, 2)
        val root = JSONObject(raw)
        return root.keys().asSequence().associate { id ->
            val task = AiRequestTask.entries.firstOrNull { it.id == id }
                ?: error("Unknown AI task profile.")
            val obj = root.get(id) as? JSONObject ?: error("Task parameters must be an object.")
            require(obj.keys().asSequence().all { it in setOf("maxTokens", "temperature") }) {
                "Unknown AI task parameter."
            }
            val tokens = if (obj.has("maxTokens")) {
                val value = obj.get("maxTokens")
                require(value is Int && value in tokenRange) { "Token limit must be 8–8192." }
                value
            } else null
            val temperature = if (obj.has("temperature")) {
                val value = obj.get("temperature")
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..2.0) {
                    "Temperature must be 0–2."
                }
                value.toFloat()
            } else null
            task to AiTaskParameters(tokens, temperature)
        }
    }

    fun write(values: Map<AiRequestTask, AiTaskParameters>): String {
        val root = JSONObject()
        AiRequestTask.entries.forEach { task ->
            values[task]?.let { value ->
                val obj = JSONObject()
                value.maxTokens?.let { obj.put("maxTokens", it) }
                value.temperature?.let { obj.put("temperature", it.toDouble()) }
                if (obj.length() > 0) root.put(task.id, obj)
            }
        }
        return root.toString().also { parse(it) }
    }

    fun update(raw: String, task: AiRequestTask, value: AiTaskParameters): String =
        write(parse(raw).toMutableMap().also { it[task] = value })

    /** Task presets preserve previously fixed limits; writing inherits provider defaults. */
    fun resolve(settings: Settings, task: AiRequestTask, provider: Map<String, String>, callerMaxTokens: Int? = null): AiResolvedParameters {
        val local = parse(settings.aiTaskProfilesJson)[task] ?: AiTaskParameters()
        val providerTokens = provider["maxTokens"]?.toIntOrNull()?.takeIf { it in tokenRange }
        val providerTemperature = provider["temperature"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..2f }
        require(callerMaxTokens == null || callerMaxTokens in tokenRange) { "Token limit must be 8–8192." }
        val tokens = callerMaxTokens ?: local.maxTokens ?: task.defaultMaxTokens
            ?: providerTokens ?: settings.aiMaxTokens.coerceIn(tokenRange)
        val temperature = local.temperature ?: providerTemperature
            ?: settings.aiTemperature.takeIf { it.isFinite() && it in 0f..2f } ?: 0.3f
        return AiResolvedParameters(tokens, temperature,
            when { callerMaxTokens != null -> "Caller"; local.maxTokens != null -> "Task profile"
                task.defaultMaxTokens != null -> "Task default"; providerTokens != null -> "Provider profile"
                else -> "Writing defaults" },
            when { local.temperature != null -> "Task profile"; providerTemperature != null -> "Provider profile"
                else -> "Writing defaults" })
    }
}
