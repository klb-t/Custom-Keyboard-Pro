package com.example.domain.model

import java.util.UUID

/**
 * Podstawowa hierarchia modelu (Zasada 1):
 * Workspace -> Layout -> Panel -> Group -> Element
 */

/**
 * Workspace jest przestrzenią, w której materializowane są wszystkie elementy (Zasada 2)
 */
data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val layouts: List<Layout>,
    val activeLayoutId: String? = null
)

/**
 * Layout to konfiguracja kompozycji, nie enum w kodzie (Zasada 3).
 */
data class Layout(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val panels: List<PanelReference>,
    val activeLayers: List<String> = listOf("base")
)

/**
 * Panel to region prezentacyjny (Zasada 6).
 */
data class Panel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val docking: Docking = Docking.Bottom,
    val size: SizeConfig = SizeConfig.ContentSized,
    val appearance: Appearance = Appearance.Transparent,
    val content: PanelContent
)

sealed interface PanelContent {
    data class GroupRef(val groupId: String) : PanelContent
    data class Elements(val elements: List<Element>) : PanelContent
    data class Panels(val panels: List<PanelReference>) : PanelContent
}

data class PanelReference(
    val panelId: String,
    val overrides: Map<String, Any> = emptyMap() // dziedziczenie/nadpisywanie (Zasada 31)
)

enum class Docking {
    Bottom, Top, Left, Right, Floating
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

data class ElementReference(
    val elementId: String,
    val overrides: Map<String, Any> = emptyMap()
)

/**
 * Element interaktywny. "Key" to nie jedyny typ (Zasada 7)
 */
sealed interface Element {
    val id: String
    val name: String
    val visual: VisualRepresentation
    val interactionField: InteractionField?

    data class Key(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val visual: VisualRepresentation,
        override val interactionField: InteractionField?,
        val interactionBehavior: InteractionBehavior,
        val layerOverrides: Map<String, InteractionBehavior> = emptyMap() // zachowanie zalezne od layer (Zasada 13)
    ) : Element
    
    data class Spacer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val visual: VisualRepresentation,
        override val interactionField: InteractionField? = null
    ) : Element
}

data class VisualRepresentation(
    val label: String? = null,
    val iconResId: Int? = null,
    val widthWeight: Float = 1f,
    val appearance: Appearance = Appearance.Transparent
)
