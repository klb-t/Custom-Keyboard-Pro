package com.example.domain.parser

import com.example.domain.action.ActionDefinition
import com.example.domain.action.ActionRef
import com.example.domain.action.ModifierType
import com.example.domain.model.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

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
    val specialType: String? = null
)

/**
 * Zwracamy pełny wynik importu ze wszystkimi nowymi obiektami (Zasada 10).
 */
data class ImportResult(
    val importedLayoutId: String,
    val newElements: Map<String, Element>,
    val newActions: Map<String, ActionDefinition>,
    val newGroups: Map<String, Group>,
    val newPanels: Map<String, Panel>,
    val newLayouts: Map<String, Layout>
)

object LayoutParser {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RecognizedLayout::class.java)

    fun parseGeminiResponse(json: String): ImportResult? {
        val cleanJson = json.replace(Regex("```(json)?"), "").trim()
        
        val recognizedLayout = try {
            adapter.fromJson(cleanJson)
        } catch (e: Exception) {
            null
        } ?: return null

        val elements = mutableMapOf<String, Element>()
        val actions = mutableMapOf<String, ActionDefinition>()
        
        recognizedLayout.rows.forEachIndexed { rowIndex, row ->
            row.keys.forEachIndexed { colIndex, key ->
                
                val tapAction = when (key.specialType?.uppercase()) {
                    "SPACE" -> ActionDefinition.CommitText(text = " ")
                    "BACKSPACE" -> ActionDefinition.SendKeyEvent(keyCode = 67)
                    "ENTER" -> ActionDefinition.SendKeyEvent(keyCode = 66)
                    "SHIFT" -> ActionDefinition.ToggleModifier(modifier = ModifierType.SHIFT)
                    "CTRL" -> ActionDefinition.ToggleModifier(modifier = ModifierType.CTRL)
                    "ALT" -> ActionDefinition.ToggleModifier(modifier = ModifierType.ALT)
                    else -> key.mainChar?.let { ActionDefinition.CommitText(text = it) }
                }
                
                tapAction?.let { actions[it.id] = it }
                
                val longPressAction = key.altChars?.firstOrNull()?.let { ActionDefinition.CommitText(text = it) }
                longPressAction?.let { actions[it.id] = it }
                
                val behavior = InteractionBehavior(
                    onTapRef = tapAction?.let { ActionRef(it.id) },
                    onLongPressRef = longPressAction?.let { ActionRef(it.id) }
                )
                
                val visual = VisualRepresentation(
                    label = key.mainChar ?: key.specialType ?: "?",
                    widthWeight = if (key.specialType?.uppercase() == "SPACE") 4f else 1f
                )
                
                val element = Element.Key(
                    name = "Key_${rowIndex}_${colIndex}", // Naprawiono interpolację (Zasada 10)
                    visual = visual,
                    sensitivity = null,
                    interactionBehavior = behavior
                )
                elements[element.id] = element
            }
        }

        val mainGroup = Group(
            name = "MainKeyboardGroup",
            elements = elements.values.map { ElementReference(elementId = it.id) }
        )
        
        val mainPanel = Panel(
            name = "MainPanel",
            content = PanelContent.GroupRef(GroupReference(groupId = mainGroup.id))
        )

        val layout = Layout(
            name = "GeminiRecognizedLayout",
            panels = listOf(PanelReference(panelId = mainPanel.id))
        )

        return ImportResult(
            importedLayoutId = layout.id,
            newElements = elements,
            newActions = actions,
            newGroups = mapOf(mainGroup.id to mainGroup),
            newPanels = mapOf(mainPanel.id to mainPanel),
            newLayouts = mapOf(layout.id to layout)
        )
    }
}
