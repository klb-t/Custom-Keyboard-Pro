package com.example.core.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named thing the model can be asked to do to the user's text.
 *
 * Tasks are data, so the shipped ones and the ones a user writes are the same kind of
 * object and appear in the same list. A task can be bound to a key like any other
 * action ([com.example.core.layout.KeyAction.Ai]).
 */
data class AiTask(
    val id: String,
    val label: String,
    val systemPrompt: String,
    /** `{text}` is replaced with the selection, or the surrounding text if none. */
    val userPrompt: String = "{text}",
    /** Replace the input in place rather than offering the result. */
    val replaceInPlace: Boolean = true,
    val builtIn: Boolean = false
) {
    fun render(text: String): String = userPrompt.replace("{text}", text)
}

object AiTasks {

    private const val EDITOR_SYSTEM =
        "You are a text-editing tool inside a keyboard. Reply with the rewritten text " +
            "and nothing else: no preamble, no explanation, no quotation marks around it. " +
            "Preserve the language of the input unless told otherwise."

    val BUILT_IN: List<AiTask> = listOf(
        AiTask(
            id = "fix",
            label = "Fix spelling & grammar",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Correct the spelling, grammar and punctuation. Change nothing else:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "formal",
            label = "More formal",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Rewrite this in a more formal register:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "casual",
            label = "More casual",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Rewrite this in a relaxed, conversational register:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "shorten",
            label = "Shorten",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Say this in fewer words without losing anything important:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "expand",
            label = "Expand",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Expand this into fuller prose, keeping the meaning and tone:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "bullets",
            label = "To bullet points",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Rewrite this as a short bulleted list:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "summarise",
            label = "Summarise",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Summarise this in one or two sentences:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "translate_en",
            label = "Translate to English",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Translate into English:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "translate_pl",
            label = "Translate to Polish",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Translate into Polish:\n\n{text}",
            builtIn = true
        ),
        AiTask(
            id = "continue",
            label = "Continue writing",
            systemPrompt = "You continue text inside a keyboard. Reply only with the " +
                "continuation, starting exactly where the input stops, including any leading " +
                "space that is needed. Never repeat the input.",
            userPrompt = "{text}",
            replaceInPlace = false,
            builtIn = true
        ),
        AiTask(
            id = "reply",
            label = "Draft a reply",
            systemPrompt = "You draft replies inside a keyboard. Reply with the draft message " +
                "only, in the same language as the input.",
            userPrompt = "Draft a short reply to this message:\n\n{text}",
            replaceInPlace = false,
            builtIn = true
        ),
        AiTask(
            id = "explain",
            label = "Explain this",
            systemPrompt = "You explain text inside a keyboard. Be brief and concrete.",
            userPrompt = "Explain this plainly:\n\n{text}",
            replaceInPlace = false,
            builtIn = true
        ),
        AiTask(
            id = "emoji",
            label = "Add emoji",
            systemPrompt = EDITOR_SYSTEM,
            userPrompt = "Add fitting emoji to this text without changing the words:\n\n{text}",
            builtIn = true
        )
    )

    /** The prompt used for inline completion in the suggestion strip. */
    const val COMPLETION_SYSTEM: String =
        "You complete text inside a phone keyboard. Reply with between one and five " +
            "words that continue the text, and nothing else. Do not repeat what is already " +
            "written. Do not add quotation marks. Match the language and register of the input. " +
            "If the text ends mid-word, finish that word first."

    fun parseCustom(json: String): List<AiTask> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { "custom_$i" }
                AiTask(
                    id = id,
                    label = o.optString("label", id),
                    systemPrompt = o.optString("system", EDITOR_SYSTEM),
                    userPrompt = o.optString("prompt", "{text}"),
                    replaceInPlace = o.optBoolean("replace", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeCustom(tasks: List<AiTask>): String {
        val arr = JSONArray()
        tasks.filter { !it.builtIn }.forEach { task ->
            arr.put(
                JSONObject()
                    .put("id", task.id)
                    .put("label", task.label)
                    .put("system", task.systemPrompt)
                    .put("prompt", task.userPrompt)
                    .put("replace", task.replaceInPlace)
            )
        }
        return arr.toString(2)
    }

    fun all(customJson: String): List<AiTask> = BUILT_IN + parseCustom(customJson)

    fun byId(customJson: String, id: String): AiTask? = all(customJson).firstOrNull { it.id == id }
}
