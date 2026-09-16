package com.example.core.layout

import android.view.KeyEvent

/**
 * A keyboard for someone who writes equations and prose in the same paragraph.
 *
 * Two ideas hold it together.
 *
 * **Symbols live where their meaning lives.** `∂` and `∇` are on `d` — one is a
 * derivative, the other is a delta someone turned upside down — and the integrals are
 * on `i`. `∑` is on `s`, `∏` on `p`, `√` on `r`, `ħ` on `h`. Nothing has to be
 * memorised as a position, because the position is the first letter of the thing. The
 * arithmetic that has no letter goes where the arithmetic already is: the operator and
 * relation boards hang off `-` and `=`.
 *
 * **A key can carry a board, not just a row.** Six accented `e`s fit in a strip you
 * slide across. Sixty mathematical symbols do not, so those keys open a board with
 * tabs that stays open and is tapped — and more than one tab can be shown at once,
 * because writing `∫f(x)dx` inside a Polish sentence means wanting maths and `ą` in
 * the same breath rather than a toggle between them.
 *
 * Everything an editor needs is on the surface too: real modifiers, Tab, Esc, arrows,
 * Home/End and Page Up/Down. A layout for working, not for messaging.
 */
object ScienceLayout {

    // -----------------------------------------------------------------------
    // Shared boards. These are the tabs that appear on several keys.
    // -----------------------------------------------------------------------

    private val greekLower = PopupGroup(
        id = "greek", label = "α", title = "Greek, lowercase",
        items = listOf(
            "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ",
            "ν", "ξ", "ο", "π", "ρ", "σ", "ς", "τ", "υ", "φ", "χ", "ψ", "ω",
            "ϑ", "ϕ", "ϖ", "ϱ", "ϵ", "ϝ"
        )
    )

    private val greekUpper = PopupGroup(
        id = "greek_upper", label = "Ω", title = "Greek, uppercase",
        items = listOf(
            "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ",
            "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω"
        )
    )

    private val operators = PopupGroup(
        id = "operators", label = "∑", title = "Operators",
        items = listOf(
            "+", "−", "×", "÷", "±", "∓", "⋅", "∘", "∗", "⊕", "⊖", "⊗", "⊘", "⊙",
            "∑", "∏", "∐", "√", "∛", "∜", "∫", "∬", "∭", "∮", "∯", "∰",
            "∂", "∇", "Δ", "∆", "′", "″", "‴", "!", "⌈", "⌉", "⌊", "⌋"
        )
    )

    private val relations = PopupGroup(
        id = "relations", label = "≈", title = "Relations",
        items = listOf(
            "=", "≠", "≈", "≡", "≢", "≅", "∼", "≃", "≜", "≝", "≐", "∝",
            "<", ">", "≤", "≥", "≪", "≫", "⩽", "⩾", "≺", "≻", "⪯", "⪰",
            "∈", "∉", "∋", "⊂", "⊃", "⊆", "⊇", "⊄", "⊅", "⊢", "⊨", "∥", "∦", "⊥"
        )
    )

    private val logicSets = PopupGroup(
        id = "logic", label = "∀", title = "Logic and sets",
        items = listOf(
            "∀", "∃", "∄", "¬", "∧", "∨", "⊻", "⇒", "⇐", "⇔", "∴", "∵", "□", "◇",
            "∅", "∪", "∩", "∖", "△", "℘", "×",
            "ℕ", "ℤ", "ℚ", "ℝ", "ℂ", "ℍ", "𝔽", "⊤", "⊥", "∞", "ℵ", "ℶ"
        )
    )

    private val arrows = PopupGroup(
        id = "arrows", label = "→", title = "Arrows",
        items = listOf(
            "→", "←", "↑", "↓", "↔", "↕", "↖", "↗", "↘", "↙",
            "⇒", "⇐", "⇑", "⇓", "⇔", "⇕", "↦", "⟼", "↪", "↩",
            "⟶", "⟵", "⟷", "⇀", "⇁", "⇌", "⇋", "↻", "↺", "⤴", "⤵", "↯"
        )
    )

    private val physics = PopupGroup(
        id = "physics", label = "ℏ", title = "Physics and units",
        items = listOf(
            "ℏ", "ħ", "°", "℃", "℉", "K", "Å", "Ω", "℧", "µ", "‰", "‱",
            "′", "″", "☉", "⊕", "♁", "∞", "∝", "≈", "⌀", "Δ", "∇", "λ", "ν",
            "eV", "Hz", "㎏", "㎧", "㎾", "㏈", "Ω⁻¹"
        )
    )

    private val superscripts = PopupGroup(
        id = "super", label = "xⁿ", title = "Superscript (no q exists in Unicode)",
        items = listOf(
            "⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹",
            "⁺", "⁻", "⁼", "⁽", "⁾", "ⁿ", "ⁱ",
            "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ʲ", "ᵏ", "ˡ", "ᵐ",
            "ᵒ", "ᵖ", "ʳ", "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ˣ", "ʸ", "ᶻ"
        )
    )

    private val subscripts = PopupGroup(
        id = "sub", label = "xₙ", title = "Subscript (Unicode has only these letters)",
        items = listOf(
            "₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉",
            "₊", "₋", "₌", "₍", "₎",
            "ₐ", "ₑ", "ₕ", "ᵢ", "ⱼ", "ₖ", "ₗ", "ₘ", "ₙ", "ₒ", "ₚ", "ᵣ", "ₛ", "ₜ", "ᵤ", "ᵥ", "ₓ"
        )
    )

    private val polish = PopupGroup(
        id = "pl", label = "ą", title = "Polish",
        items = listOf(
            "ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż",
            "Ą", "Ć", "Ę", "Ł", "Ń", "Ó", "Ś", "Ź", "Ż"
        )
    )

    private val typography = PopupGroup(
        id = "type", label = "—", title = "Typography",
        items = listOf(
            "—", "–", "‒", "…", "«", "»", "„", "”", "“", "‘", "’", "‚",
            "§", "¶", "†", "‡", "•", "◦", "‰", "№", "℮", "™", "©", "®", "℠", "·"
        )
    )

    private val currency = PopupGroup(
        id = "money", label = "€", title = "Currency",
        items = listOf("€", "£", "\$", "¥", "₽", "₹", "₿", "¢", "₩", "₪", "₱", "ƒ", "¤", "₴", "₺")
    )

    /** Everything, in the order a working day tends to need it. */
    private val fullBoard = listOf(
        operators, relations, logicSets, greekLower, greekUpper,
        arrows, physics, superscripts, subscripts, polish, typography, currency
    )

    // -----------------------------------------------------------------------
    // Key construction
    // -----------------------------------------------------------------------

    private fun tap(action: KeyAction) = listOf(Binding(KeyTrigger.Tap, action))

    /**
     * A letter, with the symbols that belong to it.
     *
     * [accents] is the quick strip — slide and release. [groups] is the board, opened
     * by the same long press when there is more than a strip's worth to offer.
     */
    private fun letter(
        char: String,
        accents: List<String> = emptyList(),
        groups: List<PopupGroup> = emptyList(),
        hint: String? = null
    ): KeyDef = KeyDef(
        id = "k_$char",
        label = char,
        hint = hint,
        bindings = tap(KeyAction.Text(char)),
        popup = if (groups.isEmpty()) accents else emptyList(),
        popupGroups = if (groups.isEmpty()) emptyList() else {
            // The accents stay reachable as the first tab rather than being lost to
            // the board: they are what this key is asked for most often.
            (if (accents.isEmpty()) emptyList()
            else listOf(PopupGroup("accents", accents.first(), accents, "Accents"))) + groups
        }
    )

    private fun symbolKey(
        id: String,
        label: String,
        text: String = label,
        width: Float = 1f,
        style: String? = null,
        hint: String? = null,
        accents: List<String> = emptyList(),
        groups: List<PopupGroup> = emptyList()
    ): KeyDef = KeyDef(
        id = id,
        label = label,
        hint = hint,
        widthWeight = width,
        style = style,
        bindings = tap(KeyAction.Text(text)),
        popup = if (groups.isEmpty()) accents else emptyList(),
        popupGroups = groups
    )

    private fun action(
        id: String,
        label: String?,
        act: KeyAction,
        width: Float = 1f,
        style: String = "special",
        icon: String? = null,
        repeatable: Boolean = false,
        longPress: KeyAction? = null,
        indicators: List<IndicatorDef> = emptyList(),
        hint: String? = null
    ): KeyDef = KeyDef(
        id = id,
        label = label,
        hint = hint,
        icon = icon,
        widthWeight = width,
        style = style,
        bindings = tap(act) +
            (longPress?.let { listOf(Binding(KeyTrigger.LongPress, it)) } ?: emptyList()) +
            (if (repeatable) listOf(Binding(KeyTrigger.Repeat, act)) else emptyList()),
        repeatable = repeatable,
        indicators = indicators
    )

    /** Digit with its superscript, subscript and the fractions that start with it. */
    private fun digit(d: String, fractions: List<String>, extra: List<String> = emptyList()): KeyDef {
        val supers = mapOf(
            "1" to "¹", "2" to "²", "3" to "³", "4" to "⁴", "5" to "⁵",
            "6" to "⁶", "7" to "⁷", "8" to "⁸", "9" to "⁹", "0" to "⁰"
        )
        val subs = mapOf(
            "1" to "₁", "2" to "₂", "3" to "₃", "4" to "₄", "5" to "₅",
            "6" to "₆", "7" to "₇", "8" to "₈", "9" to "₉", "0" to "₀"
        )
        return symbolKey(
            id = "d_$d",
            label = d,
            hint = supers[d],
            accents = listOfNotNull(supers[d], subs[d]) + fractions + extra
        )
    }

    // -----------------------------------------------------------------------
    // The layout
    // -----------------------------------------------------------------------

    private val numberRow = RowDef(
        keys = listOf(
            action("esc", "Esc", KeyAction.SendKey(KeyEvent.KEYCODE_ESCAPE), width = 1.2f),
            digit("1", listOf("½", "⅓", "¼", "⅕", "⅙", "⅛"), listOf("!", "①")),
            digit("2", listOf("⅔", "⅖"), listOf("@", "²", "√", "②")),
            digit("3", listOf("¾", "⅗", "⅜"), listOf("#", "∛", "③")),
            digit("4", listOf("⅘"), listOf("\$", "∜", "④")),
            digit("5", listOf("⅚", "⅝"), listOf("%", "‰", "⑤")),
            digit("6", emptyList(), listOf("^", "⁶", "⑥")),
            digit("7", listOf("⅞"), listOf("&", "⑦")),
            digit("8", emptyList(), listOf("*", "∗", "⊛", "⑧")),
            digit("9", emptyList(), listOf("(", "⁽", "₍", "⑨")),
            digit("0", emptyList(), listOf(")", "⁾", "₎", "∅", "⓪", "°")),
            // His placement: the operators live next to the minus and the equals.
            symbolKey(
                "minus", "-", hint = "−", groups = listOf(
                    PopupGroup(
                        "dashes", "−", listOf("−", "–", "—", "±", "∓", "÷", "⁻", "₋", "¬", "‐", "_"),
                        "Signs and dashes"
                    ),
                    operators, relations, superscripts, subscripts
                )
            ),
            symbolKey(
                "equals", "=", hint = "≠", groups = listOf(
                    PopupGroup(
                        "eq", "≠", listOf("≠", "≈", "≡", "≤", "≥", "≪", "≫", "∝", "≜", "≐", "⇒", "⇔", "→", "↔"),
                        "Equality and implication"
                    ),
                    relations, operators, logicSets, arrows
                )
            ),
            action(
                "backspace", null, KeyAction.Backspace(TextUnit.CHARACTER),
                width = 1.6f, icon = "backspace", repeatable = true,
                longPress = KeyAction.Backspace(TextUnit.WORD)
            )
        ),
        heightWeight = 0.9f
    )

    private val topRow = RowDef(
        keys = listOf(
            action("tab", "Tab", KeyAction.SendKey(KeyEvent.KEYCODE_TAB), width = 1.4f),
            letter("q", listOf("ℚ"), hint = "ℚ"),
            letter("w", listOf("ŵ", "ω", "Ω"), hint = "ω"),
            letter("e", listOf("é", "è", "ê", "ë", "ē", "ę", "ě", "ė"),
                groups = listOf(
                    PopupGroup("e_math", "∈", listOf("∈", "∉", "∃", "∄", "ε", "ϵ", "η", "ℯ", "℮", "𝑒"), "e in maths"),
                    logicSets, greekLower
                ), hint = "ε"),
            letter("r", listOf("ř", "ŕ", "ρ"),
                groups = listOf(
                    PopupGroup("r_math", "√", listOf("√", "∛", "∜", "ρ", "ℝ", "ℜ", "↻", "↺"), "r in maths"),
                    operators, greekLower
                ), hint = "√"),
            letter("t", listOf("ť", "ţ", "ț", "ŧ", "τ", "θ", "Θ", "ϑ"), hint = "τ"),
            letter("y", listOf("ý", "ÿ", "ŷ", "ψ", "Ψ", "υ"), hint = "ψ"),
            letter("u", listOf("ú", "ù", "û", "ü", "ū", "ů", "ű", "ų", "υ", "∪"), hint = "∪"),
            letter("i", listOf("í", "ì", "î", "ï", "ī", "į", "ı", "ι"),
                groups = listOf(
                    PopupGroup(
                        "integrals", "∫",
                        listOf("∫", "∬", "∭", "∮", "∯", "∰", "∞", "ι", "ℑ", "ⅈ", "⨌"),
                        "Integrals and infinity"
                    ),
                    operators, greekLower
                ), hint = "∫"),
            letter("o", listOf("ó", "ò", "ô", "ö", "õ", "ō", "ő", "ø", "œ", "ο", "ω", "∘", "⊙", "∅"), hint = "∘"),
            letter("p", listOf("π", "Π", "∏", "℘", "ℙ", "¶", "ṗ"),
                groups = listOf(
                    PopupGroup("p_math", "∏", listOf("π", "Π", "∏", "℘", "ℙ", "¶", "∂"), "p in maths"),
                    operators, greekLower
                ), hint = "π"),
            symbolKey("lbracket", "[", hint = "⌈", accents = listOf("⟦", "⌈", "⌊", "⟨", "{", "⎡", "⟅")),
            symbolKey("rbracket", "]", hint = "⌉", accents = listOf("⟧", "⌉", "⌋", "⟩", "}", "⎤", "⟆")),
            symbolKey("backslash", "\\", hint = "∖", accents = listOf("∖", "∣", "‖", "⫽", "|"))
        )
    )

    private val homeRow = RowDef(
        keys = listOf(
            action(
                "caps", "Caps", KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.LOCK),
                width = 1.7f, style = "modifier",
                indicators = listOf(
                    IndicatorDef(IndicatorSource.ModifierLock(ModifierKind.SHIFT), IndicatorStyle.DOT_TOP_RIGHT)
                )
            ),
            letter("a", listOf("á", "à", "â", "ä", "ã", "å", "ā", "ą", "æ", "α"),
                groups = listOf(
                    PopupGroup("a_math", "∀", listOf("∀", "α", "ℵ", "∠", "∡", "∢", "Å"), "a in maths"),
                    logicSets, greekLower
                ), hint = "α"),
            letter("s", listOf("ś", "š", "ş", "ŝ", "ș", "ß"),
                groups = listOf(
                    PopupGroup("s_math", "∑", listOf("∑", "σ", "ς", "Σ", "⊃", "⊇", "∖", "§"), "s in maths"),
                    operators, greekLower
                ), hint = "∑"),
            // Nabla under d, exactly as asked: it is a delta someone turned over.
            letter("d", listOf("ď", "đ", "ḋ", "ð"),
                groups = listOf(
                    PopupGroup(
                        "d_math", "∂",
                        listOf("∂", "∇", "Δ", "δ", "∆", "ⅆ", "⋄", "𝑑", "ð", "đ"),
                        "Derivatives and deltas"
                    ),
                    operators, greekLower
                ), hint = "∂"),
            letter("f", listOf("ƒ", "φ", "ϕ", "ḟ"), hint = "ƒ"),
            letter("g", listOf("ğ", "ĝ", "ġ", "ģ", "γ", "Γ", "ℊ"), hint = "γ"),
            letter("h", listOf("ĥ", "ħ", "ℏ", "η", "ℎ", "ℋ"), hint = "ℏ"),
            letter("j", listOf("ĵ", "ȷ", "ⅉ"), hint = "ĵ"),
            letter("k", listOf("ķ", "ǩ", "κ", "⊗"), hint = "κ"),
            letter("l", listOf("ł", "ĺ", "ľ", "ļ", "ŀ", "λ", "Λ", "ℓ", "∟"), hint = "λ"),
            symbolKey("semicolon", ";", hint = "·", accents = listOf(":", "·", "∶", "⋮", "⋯", "∴", "∵")),
            symbolKey("quote", "'", hint = "′", accents = listOf("′", "″", "‴", "`", "´", "\"", "’", "‘")),
            action(
                "enter", null, KeyAction.Enter, width = 1.8f, icon = "enter",
                longPress = KeyAction.Text("\n")
            )
        )
    )

    private val bottomRow = RowDef(
        keys = listOf(
            action(
                "shift_left", null, KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT),
                width = 2.2f, style = "modifier", icon = "shift",
                longPress = KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.LOCK),
                indicators = listOf(
                    IndicatorDef(IndicatorSource.ModifierLock(ModifierKind.SHIFT), IndicatorStyle.DOT_TOP_RIGHT)
                )
            ),
            letter("z", listOf("ź", "ż", "ž", "ζ", "ℤ"), hint = "ζ"),
            letter("x", listOf("ξ", "Ξ", "χ", "×", "⊗", "⨯", "ẋ"), hint = "×"),
            letter("c", listOf("ć", "č", "ç", "ĉ", "ċ", "⊂", "⊆", "ℂ", "©"), hint = "⊂"),
            letter("v", listOf("∨", "√", "ṽ", "⊻", "⌄"), hint = "∨"),
            letter("b", listOf("β", "♭", "⊥", "ḃ", "ƀ"), hint = "β"),
            letter("n", listOf("ń", "ň", "ñ", "ņ", "ṅ", "ν", "∩", "ℕ", "¬"), hint = "∩"),
            letter("m", listOf("μ", "∓", "ℳ", "ṁ", "µ"), hint = "μ"),
            symbolKey("comma", ",", hint = "‚", accents = listOf("‚", "„", "⟨", "‹", "¸", "<", "≤")),
            symbolKey("period", ".", hint = "…", accents = listOf("…", "·", "∙", "⋅", "∘", "⟩", "›", ">", "≥")),
            symbolKey("slash", "/", hint = "÷", accents = listOf("÷", "∕", "⁄", "?", "¿", "∣", "∤", "‖")),
            action(
                "shift_right", null, KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT),
                width = 1.6f, style = "modifier", icon = "shift",
                longPress = KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.LOCK)
            ),
            action(
                "up", "↑", KeyAction.MoveCursor(CursorDirection.UP), repeatable = true,
                longPress = KeyAction.MoveCursor(CursorDirection.UP, TextUnit.PARAGRAPH), hint = "PgUp"
            )
        )
    )

    private val modifierRow = RowDef(
        keys = listOf(
            action(
                "ctrl", "Ctrl", KeyAction.Modifier(ModifierKind.CTRL, ModifierMode.ONE_SHOT),
                width = 1.3f, style = "modifier",
                longPress = KeyAction.Modifier(ModifierKind.CTRL, ModifierMode.LOCK),
                indicators = listOf(
                    IndicatorDef(IndicatorSource.ModifierLock(ModifierKind.CTRL), IndicatorStyle.DOT_TOP_RIGHT)
                )
            ),
            action(
                "alt", "Alt", KeyAction.Modifier(ModifierKind.ALT, ModifierMode.ONE_SHOT),
                width = 1.1f, style = "modifier",
                longPress = KeyAction.Modifier(ModifierKind.ALT, ModifierMode.LOCK)
            ),
            // The whole symbol library, one key.
            KeyDef(
                id = "sigma",
                label = "∑",
                hint = "hold",
                widthWeight = 1.2f,
                style = "accent",
                bindings = tap(KeyAction.Text("∑")),
                popupGroups = fullBoard
            ),
            action("space", null, KeyAction.Space, width = 3.4f, style = "normal", icon = "space"),
            action(
                "altgr", "AltGr", KeyAction.Modifier(ModifierKind.ALT_GR, ModifierMode.ONE_SHOT),
                width = 1.2f, style = "modifier"
            ),
            action(
                "home", "Home", KeyAction.MoveCursor(CursorDirection.LINE_START),
                width = 1.1f, longPress = KeyAction.MoveCursor(CursorDirection.DOC_START), hint = "doc"
            ),
            action(
                "end", "End", KeyAction.MoveCursor(CursorDirection.LINE_END),
                width = 1.1f, longPress = KeyAction.MoveCursor(CursorDirection.DOC_END), hint = "doc"
            ),
            action(
                "left", "←", KeyAction.MoveCursor(CursorDirection.LEFT), repeatable = true,
                longPress = KeyAction.MoveCursor(CursorDirection.LEFT, TextUnit.WORD)
            ),
            action(
                "down", "↓", KeyAction.MoveCursor(CursorDirection.DOWN), repeatable = true,
                longPress = KeyAction.MoveCursor(CursorDirection.DOWN, TextUnit.PARAGRAPH), hint = "PgDn"
            ),
            action(
                "right", "→", KeyAction.MoveCursor(CursorDirection.RIGHT), repeatable = true,
                longPress = KeyAction.MoveCursor(CursorDirection.RIGHT, TextUnit.WORD)
            )
        ),
        heightWeight = 0.95f
    )

    val SCIENCE = LayoutDef(
        id = "science",
        name = "Scientific",
        builtIn = true,
        locale = "pl",
        rowCountHint = 5,
        description = "Editing keys on the surface, and every symbol a working " +
            "scientist needs one long press away — placed where its meaning is.",
        layers = mapOf(
            LayoutDef.BASE_LAYER to LayerDef(
                name = LayoutDef.BASE_LAYER,
                rows = listOf(numberRow, topRow, homeRow, bottomRow, modifierRow)
            )
        )
    )
}
