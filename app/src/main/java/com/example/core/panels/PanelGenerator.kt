package com.example.core.panels

import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.config.Settings
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore

/**
 * Turns "I wish I could change X" into a panel that changes X.
 *
 * The model is not asked to invent a feature. It is handed the complete list of
 * settings this build has — names, types, ranges, current values — and asked which of
 * them answer the request and how to present them. That framing is deliberate: a
 * model that can only select and arrange cannot promise behaviour the app does not
 * have, and when nothing answers the request it is told to say so rather than to
 * produce a plausible-looking panel of controls that do nothing.
 *
 * What this genuinely buys: nobody has to hand-write a screen for every combination
 * of settings someone might want next to each other. The settings are data, the
 * schema describes them, and the arrangement is now data too.
 */
object PanelGenerator {

    private const val SYSTEM = """
You build settings panels for an Android keyboard app.

You will be given the COMPLETE list of settings the app has. Each line is:
  key: type | (one of [...] when it is a fixed choice) | (range a..b) | now=<current value> | "human label"

The user will describe something they want to be able to change.

Your job: choose the settings that actually answer that request and arrange them into
one panel. Reply with ONE JSON object and nothing else:

{
  "title": "short panel name",
  "description": "one sentence on what this panel is for",
  "controls": [
    {"key": "<exact key from the list>", "label": "optional friendlier label", "help": "optional one-line explanation"}
  ],
  "note": "optional. REQUIRED when the request cannot be fully answered."
}

Rules:
- "key" MUST be copied exactly from the list. Never invent a key. Never guess a name
  that "should" exist. A control naming a key that is not on the list is discarded.
- Include only settings that genuinely bear on the request, in the order someone
  would want to meet them. A focused panel of four is better than a vague one of
  twenty.
- If the request is only partly answerable, build the panel from what does exist and
  use "note" to say plainly which part has no setting behind it.
- If nothing on the list answers the request at all, return an empty "controls" array
  and explain in "note" what the app would have to grow for this to be possible. Do
  not pad the panel with loosely related settings.
- "label" and "help" are for humans: no key names, no types, no JSON in them.
"""

    data class Outcome(
        val panel: PanelSpec,
        /** Keys the model asked for that this build does not have. */
        val droppedKeys: List<String>
    )

    suspend fun generate(
        request: String,
        settings: Settings = SettingsStore.current,
        config: AiConfig = AiConfig.from(settings, maxTokens = 1200)
    ): Result<Outcome> {
        if (request.isBlank()) {
            return Result.failure(IllegalArgumentException("Describe what you want to change first."))
        }
        val schema = SettingsSchema.describeForModel(settings)
        val prompt = buildString {
            append("SETTINGS AVAILABLE IN THIS BUILD:\n")
            append(schema)
            append("\n\nWHAT THE USER WANTS TO BE ABLE TO CHANGE:\n")
            append(request.trim())
        }
        return AiClient.complete(
            config = config,
            systemPrompt = SYSTEM.trim(),
            userPrompt = prompt
        ).mapCatching { reply ->
            val parsed = PanelSpec.parse(reply).getOrThrow()
            val (clean, dropped) = parsed.copy(
                id = "panel_${System.currentTimeMillis()}",
                request = request.trim()
            ).validate()
            Outcome(clean, dropped)
        }
    }

    // -----------------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------------

    fun saved(settings: Settings = SettingsStore.current): List<PanelSpec> =
        PanelSpec.listFromJson(settings.generatedPanelsJson)

    fun save(panel: PanelSpec) {
        SettingsStore.update { s ->
            val existing = PanelSpec.listFromJson(s.generatedPanelsJson).filterNot { it.id == panel.id }
            s.copy(generatedPanelsJson = PanelSpec.listToJson(existing + panel))
        }
    }

    fun delete(id: String) {
        SettingsStore.update { s ->
            val kept = PanelSpec.listFromJson(s.generatedPanelsJson).filterNot { it.id == id }
            s.copy(generatedPanelsJson = PanelSpec.listToJson(kept))
        }
    }
}
