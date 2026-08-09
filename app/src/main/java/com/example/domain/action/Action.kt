package com.example.domain.action

import java.util.UUID

/**
 * Zgodnie z wytycznymi architektonicznymi (Zasada 34), Actions to obiekty wielokrotnego użytku.
 * ActionDefinition określa zachowanie, a klawisze trzymają ActionRef.
 */
sealed interface ActionDefinition {
    val id: String

    data class CommitText(
        override val id: String = UUID.randomUUID().toString(),
        val text: String
    ) : ActionDefinition

    data class SendKeyEvent(
        override val id: String = UUID.randomUUID().toString(),
        val keyCode: Int
    ) : ActionDefinition
    
    data class ToggleModifier(
        override val id: String = UUID.randomUUID().toString(),
        val modifier: ModifierType
    ) : ActionDefinition

    data class SwitchLayer(
        override val id: String = UUID.randomUUID().toString(),
        val layerName: String
    ) : ActionDefinition

    data class OpenPanel(
        override val id: String = UUID.randomUUID().toString(),
        val panelId: String
    ) : ActionDefinition
    
    data class ExecuteMacro(
        override val id: String = UUID.randomUUID().toString(),
        val actionIds: List<String> // odniesienia do innych ActionDefinition
    ) : ActionDefinition
    
    data class ClipboardAction(
        override val id: String = UUID.randomUUID().toString(),
        val type: ClipboardActionType
    ) : ActionDefinition
}

/**
 * Odniesienie do ActionDefinition.
 */
data class ActionRef(val actionId: String)

enum class ModifierType {
    SHIFT, CTRL, ALT, META
}

enum class ClipboardActionType {
    HISTORY, PASTE, PIN, EDIT, EXPORT
}
