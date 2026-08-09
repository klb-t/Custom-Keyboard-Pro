package com.example.domain.action

import java.util.UUID

/**
 * Zgodnie z wytycznymi architektonicznymi, Klawisz nie musi wysyłać tylko znaków.
 * Klawisz referuje Action. To umożliwia bibliotekę akcji, makra, skrypty, import/export.
 */
sealed interface Action {
    val id: String

    data class CommitText(
        val text: String,
        override val id: String = UUID.randomUUID().toString()
    ) : Action

    data class SendKeyEvent(
        val keyCode: Int,
        override val id: String = UUID.randomUUID().toString()
    ) : Action
    
    data class ToggleModifier(
        val modifier: ModifierType,
        override val id: String = UUID.randomUUID().toString()
    ) : Action

    data class SwitchLayer(
        val layerName: String,
        override val id: String = UUID.randomUUID().toString()
    ) : Action

    data class OpenPanel(
        val panelId: String,
        override val id: String = UUID.randomUUID().toString()
    ) : Action
    
    data class ExecuteMacro(
        val actions: List<Action>,
        override val id: String = UUID.randomUUID().toString()
    ) : Action
    
    data class ClipboardAction(
        val type: ClipboardActionType,
        override val id: String = UUID.randomUUID().toString()
    ) : Action
}

enum class ModifierType {
    SHIFT, CTRL, ALT, META
}

enum class ClipboardActionType {
    HISTORY, PASTE, PIN, EDIT, EXPORT
}
