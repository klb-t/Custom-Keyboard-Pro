package com.example.domain.model

import com.example.domain.action.ActionDefinition
import java.util.UUID

/**
 * Główny dokument (Zasada 1, Zasada 9).
 * Canonical document that owns all reusable definitions.
 */
data class KeyboardDocument(
    val schemaVersion: Int = 1,
    val workspace: Workspace,
    val layoutRegistry: Map<String, Layout> = emptyMap(),
    val panelRegistry: Map<String, Panel> = emptyMap(),
    val groupRegistry: Map<String, Group> = emptyMap(),
    val elementRegistry: Map<String, Element> = emptyMap(),
    val actionRegistry: Map<String, ActionDefinition> = emptyMap(),
    val styleRegistry: Map<String, StyleDefinition> = emptyMap()
)

data class StyleDefinition(
    val id: String = UUID.randomUUID().toString(),
    val appearance: Appearance? = null
)

/**
 * Workspace jest przestrzenią, w której materializowane są wszystkie elementy (Zasada 2)
 */
data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val activeLayoutId: String? = null
)

/**
 * Layout to konfiguracja kompozycji (Zasada 3).
 */
data class Layout(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val panels: List<PanelReference>,
    val activeLayers: List<String> = listOf("base")
)

/**
 * Referencje i typed overrides (Zasada 2, Zasada 31).
 */
data class PanelReference(
    val panelId: String,
    val positionOverride: PositionConstraint? = null,
    val appearanceOverride: Appearance? = null
)

data class GroupReference(
    val groupId: String,
    val appearanceOverride: Appearance? = null
)

data class ElementReference(
    val elementId: String,
    val visualOverride: VisualOverrides? = null,
    val interactionOverride: InteractionOverrides? = null
)

data class VisualOverrides(
    val label: String? = null,
    val widthWeight: Float? = null,
    val appearance: Appearance? = null
)

data class InteractionOverrides(
    val behaviorOverride: InteractionBehavior? = null
)

/**
 * System constraints zamiast Docking enum (Zasada 3).
 */
sealed interface PositionConstraint {
    data class AnchorToEdge(val edge: Edge, val marginDp: Float = 0f) : PositionConstraint
    data class AnchorToPanel(val targetPanelId: String, val myEdge: Edge, val targetEdge: Edge, val offsetDp: Float = 0f) : PositionConstraint
    data class RelativeOffset(val xDp: Float, val yDp: Float) : PositionConstraint
    object Floating : PositionConstraint
}

enum class Edge { Top, Bottom, Left, Right }

/**
 * Panel to region prezentacyjny (Zasada 6).
 */
data class Panel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val positionConstraint: PositionConstraint = PositionConstraint.AnchorToEdge(Edge.Bottom),
    val size: SizeConfig = SizeConfig.ContentSized,
    val appearance: Appearance = Appearance.Transparent,
    val content: PanelContent
)

sealed interface PanelContent {
    data class GroupRef(val group: GroupReference) : PanelContent
    data class Elements(val elements: List<ElementReference>) : PanelContent
    data class Panels(val panels: List<PanelReference>) : PanelContent
}

sealed interface SizeConfig {
    object ContentSized : SizeConfig
    data class Fixed(val widthDp: Float?, val heightDp: Float?) : SizeConfig
    data class Percentage(val widthPct: Float?, val heightPct: Float?) : SizeConfig
}

sealed interface Appearance {
    object Transparent : Appearance
    data class Solid(val colorHex: String, val opacity: Float = 1.0f) : Appearance
}

/**
 * Group - obiekt pierwszej klasy i wielokrotnego użytku (Zasada 4)
 */
data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val elements: List<ElementReference>
)

/**
 * Element interaktywny. "Key" to nie jedyny typ (Zasada 7)
 */
sealed interface Element {
    val id: String
    val name: String
    val visual: VisualRepresentation
    val sensitivity: EffectiveSensitivity?

    data class Key(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val visual: VisualRepresentation,
        override val sensitivity: EffectiveSensitivity?,
        val interactionBehavior: InteractionBehavior,
        val layerOverrides: Map<String, InteractionBehavior> = emptyMap() // zachowanie zalezne od layer (Zasada 13)
    ) : Element
    
    data class Spacer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val visual: VisualRepresentation,
        override val sensitivity: EffectiveSensitivity? = null
    ) : Element
}

data class VisualRepresentation(
    val label: String? = null,
    val iconResId: Int? = null,
    val widthWeight: Float = 1f,
    val appearance: Appearance = Appearance.Transparent
)
