package com.example.domain.parser

import com.example.domain.action.Action
import com.example.domain.action.ModifierType
import com.example.domain.model.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@JsonClass(generateAdapter = true)
data class RecognizedLayout(
    val rows: List<RecognizedRow>
)

@JsonClass(generateAdapter = true)
data class RecognizedRow(
    val keys: List<RecognizedKey>
)

@JsonClass(generateAdapter = true)
data class RecognizedKey(
    val mainChar: String? = null,
    val altChars: List<String>? = null,
    val specialType: String? = null // e.g. "SPACE", "BACKSPACE", "SHIFT", "ENTER", "CTRL", "ALT"
)

object LayoutParser {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RecognizedLayout::class.java)

    fun parseGeminiResponse(json: String): Layout? {
        // Clean JSON string in case Gemini wraps it in markdown blocks
        val cleanJson = json.replace(Regex("```(json)?"), "").trim()
        
        val recognizedLayout = try {
            adapter.fromJson(cleanJson)
        } catch (e: Exception) {
            null
        } ?: return null

        val elements = mutableListOf<Element>()
        
        // Simple mapping to a grid-like panel
        recognizedLayout.rows.forEachIndexed { rowIndex, row ->
            row.keys.forEachIndexed { colIndex, key ->
                
                val action = when (key.specialType?.uppercase()) {
                    "SPACE" -> Action.CommitText(" ")
                    "BACKSPACE" -> Action.SendKeyEvent(67) // KEYCODE_DEL
                    "ENTER" -> Action.SendKeyEvent(66) // KEYCODE_ENTER
                    "SHIFT" -> Action.ToggleModifier(ModifierType.SHIFT)
                    "CTRL" -> Action.ToggleModifier(ModifierType.CTRL)
                    "ALT" -> Action.ToggleModifier(ModifierType.ALT)
                    else -> key.mainChar?.let { Action.CommitText(it) }
                }
                
                val behavior = InteractionBehavior(
                    onTap = action,
                    onLongPress = key.altChars?.firstOrNull()?.let { Action.CommitText(it) }
                )
                
                val visual = VisualRepresentation(
                    label = key.mainChar ?: key.specialType ?: "?",
                    widthWeight = if (key.specialType?.uppercase() == "SPACE") 4f else 1f
                )
                
                elements.add(
                    Element.Key(
                        name = "Key_\${rowIndex}_\${colIndex}",
                        visual = visual,
                        interactionField = null,
                        interactionBehavior = behavior
                    )
                )
            }
        }

        val mainGroup = Group(
            name = "MainKeyboardGroup",
            elements = elements.map { ElementReference(elementId = it.id) }
        )
        
        val mainPanel = Panel(
            name = "MainPanel",
            content = PanelContent.Elements(elements) // For simplicity keeping it direct
        )

        return Layout(
            name = "GeminiRecognizedLayout",
            panels = listOf(PanelReference(panelId = mainPanel.id))
        )
    }
}
