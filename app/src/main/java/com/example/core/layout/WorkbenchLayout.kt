package com.example.core.layout

import android.view.KeyEvent

/**
 * The layout that exists to prove elements are real.
 *
 * It is the example that made the case for them, built: a docked panel, a floating
 * numeric block, and a transparent Escape pinned in a corner — one layout, three
 * pieces, three different placements. No app-wide presentation setting can describe
 * this, which is the whole reason placement had to move into the layout.
 *
 * The three pieces also differ in the two things that only make sense per piece:
 *
 *  - the **panel** claims its space, the way a keyboard always has;
 *  - the **numeric block** claims none, so touches between and around it reach the
 *    app, and it is not pinned, so it slides out of the way when it would cover the
 *    text cursor;
 *  - the **Escape** claims none and *is* pinned, because a key deliberately placed in
 *    a corner that wanders off on its own is a key you can no longer find.
 */
object WorkbenchLayout {

    private fun tap(action: KeyAction) = listOf(Binding(KeyTrigger.Tap, action))

    private fun pad(label: String, action: KeyAction, style: String? = null) = KeyDef(
        id = "wb_${label.lowercase().replace(' ', '_')}",
        label = label,
        style = style,
        bindings = tap(action)
    )

    private fun digit(d: String) = pad(d, KeyAction.Text(d))

    /** A calculator block: the reason anyone wants a second keyboard on screen. */
    private val numberBlock = LayerDef(
        name = "numblock",
        rows = listOf(
            RowDef(listOf(digit("7"), digit("8"), digit("9"), pad("÷", KeyAction.Text("÷"), "special"))),
            RowDef(listOf(digit("4"), digit("5"), digit("6"), pad("×", KeyAction.Text("×"), "special"))),
            RowDef(listOf(digit("1"), digit("2"), digit("3"), pad("−", KeyAction.Text("−"), "special"))),
            RowDef(
                listOf(
                    digit("0"),
                    pad(".", KeyAction.Text(".")),
                    pad("=", KeyAction.Text("="), "accent"),
                    pad("+", KeyAction.Text("+"), "special")
                )
            )
        )
    )

    /** One key, alone, over the app. */
    private val escapeCorner = LayerDef(
        name = "escape",
        rows = listOf(
            RowDef(
                listOf(
                    KeyDef(
                        id = "wb_escape",
                        label = "Esc",
                        style = "accent",
                        bindings = tap(KeyAction.SendKey(KeyEvent.KEYCODE_ESCAPE))
                    )
                )
            )
        )
    )

    val WORKBENCH = LayoutDef(
        id = "workbench",
        name = "Workbench — panel, floating numpad, pinned Esc",
        builtIn = true,
        locale = "pl",
        rowCountHint = 5,
        description = "One layout made of three pieces, each placed differently. " +
            "Shows what a layout can be once placement stops being a single app setting.",
        layers = ScienceLayout.SCIENCE.layers +
            mapOf(
                numberBlock.name to numberBlock,
                escapeCorner.name to escapeCorner
            ),
        elements = listOf(
            ElementDef(
                id = "panel",
                layer = LayoutDef.BASE_LAYER,
                placement = ElementPlacement.DOCKED
            ),
            ElementDef(
                id = "numblock",
                layer = "numblock",
                placement = ElementPlacement.FLOATING,
                bounds = NormRect(0.58f, 0.26f, 0.99f, 0.56f),
                panelOpacity = 0.9f,
                // Claims nothing: the app keeps the screen it would otherwise lose,
                // and gets every touch that misses the block.
                reservesSpace = false,
                pinned = false
            ),
            ElementDef(
                id = "escape",
                layer = "escape",
                placement = ElementPlacement.FREE,
                bounds = NormRect(0.02f, 0.05f, 0.17f, 0.115f),
                // No background at all — the key is the only thing drawn.
                panelOpacity = 0f,
                reservesSpace = false,
                // Put in a corner on purpose, so it stays in that corner.
                pinned = true,
                draggable = false
            )
        )
    )
}
