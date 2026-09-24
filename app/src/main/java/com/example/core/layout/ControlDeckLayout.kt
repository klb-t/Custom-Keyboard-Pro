package com.example.core.layout

/**
 * A layout that shows what controls are for: the scientific board docked, with a
 * volume fader, a reading-speed knob and a torch switch floating above it.
 *
 * Every control here is only an action with a number in it — "do:volume_set {v}",
 * "do:set ttsRate {v}", "do:torch {on}" — so this is an example to copy, not a
 * feature: a brightness slider is "do:brightness {v}", a pad that scrolls is a wire.
 */
object ControlDeckLayout {

    val CONTROL_DECK = LayoutDef(
        id = "control_deck",
        name = "Control deck — keys, fader, knob, switch",
        builtIn = true,
        locale = "pl",
        rowCountHint = 5,
        description = "The scientific board with floating controls: media volume, reading speed, torch. " +
            "Each control is an action with its value in it; edit them to control anything that takes a number.",
        layers = ScienceLayout.SCIENCE.layers,
        elements = listOf(
            ElementDef(id = "panel", layer = LayoutDef.BASE_LAYER, placement = ElementPlacement.DOCKED),
            ElementDef(
                id = "volume",
                placement = ElementPlacement.FLOATING,
                bounds = NormRect(0.84f, 0.18f, 0.98f, 0.52f),
                panelOpacity = 0.85f,
                control = ControlDef(
                    kind = ControlKind.SLIDER, min = 0.0, max = 1.0, value = 0.5,
                    action = "do:volume_set {v}", vertical = true, label = "Vol"
                )
            ),
            ElementDef(
                id = "speed",
                placement = ElementPlacement.FLOATING,
                bounds = NormRect(0.62f, 0.18f, 0.82f, 0.30f),
                panelOpacity = 0.85f,
                control = ControlDef(
                    kind = ControlKind.KNOB, min = 0.5, max = 2.5, step = 0.05, value = 1.0,
                    action = "do:set ttsRate {v}", label = "Read", live = false
                )
            ),
            ElementDef(
                id = "torch",
                placement = ElementPlacement.FLOATING,
                bounds = NormRect(0.62f, 0.32f, 0.82f, 0.38f),
                panelOpacity = 0.85f,
                control = ControlDef(
                    kind = ControlKind.TOGGLE, min = 0.0, max = 1.0, value = 0.0,
                    action = "do:torch {on}", label = "Torch", decimals = 0
                )
            )
        )
    )
}
