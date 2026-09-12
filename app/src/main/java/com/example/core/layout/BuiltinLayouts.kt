package com.example.core.layout

import android.view.KeyEvent

/**
 * The layouts that ship with the app.
 *
 * They are ordinary [LayoutDef]s built in Kotlin rather than a privileged format:
 * anything here can be exported to JSON, edited and re-imported, and a user-made
 * layout can do everything these do. They exist so the keyboard is complete on first
 * launch, not to be the only way to have a good layout.
 */
object BuiltinLayouts {

    // -----------------------------------------------------------------------
    // Small construction helpers
    // -----------------------------------------------------------------------

    private fun tap(action: KeyAction) = listOf(Binding(KeyTrigger.Tap, action))

    /** A plain character key. */
    private fun ch(
        c: String,
        hint: String? = null,
        popup: List<String> = emptyList(),
        swipeUp: String? = null,
        width: Float = 1f
    ): KeyDef = KeyDef(
        id = c,
        label = c,
        hint = hint ?: swipeUp,
        widthWeight = width,
        bindings = tap(KeyAction.Text(c)) + (swipeUp?.let {
            listOf(Binding(KeyTrigger.Swipe(SwipeDirection.UP), KeyAction.Text(it)))
        } ?: emptyList()),
        popup = popup
    )

    /** A key that does something other than emit its own label. */
    private fun fn(
        id: String,
        label: String?,
        action: KeyAction,
        width: Float = 1f,
        icon: String? = null,
        style: String = "special",
        repeatable: Boolean = false,
        popup: List<String> = emptyList(),
        indicators: List<IndicatorDef> = emptyList(),
        longPress: KeyAction? = null,
        swipes: Map<SwipeDirection, KeyAction> = emptyMap(),
        hint: String? = null
    ): KeyDef = KeyDef(
        id = id,
        label = label,
        hint = hint,
        icon = icon,
        widthWeight = width,
        style = style,
        bindings = tap(action) +
            (longPress?.let { listOf(Binding(KeyTrigger.LongPress, it)) } ?: emptyList()) +
            swipes.map { (dir, a) -> Binding(KeyTrigger.Swipe(dir), a) } +
            (if (repeatable) listOf(Binding(KeyTrigger.Repeat, action)) else emptyList()),
        repeatable = repeatable,
        popup = popup,
        indicators = indicators
    )

    private fun key(code: Int, label: String, width: Float = 1f, repeatable: Boolean = false, style: String = "special") =
        fn("kc_$code", label, KeyAction.SendKey(code), width = width, style = style, repeatable = repeatable)

    private fun modIndicator(kind: ModifierKind) =
        IndicatorDef(IndicatorSource.Modifier(kind), IndicatorStyle.BAR_BOTTOM)

    private fun lockIndicator(runtimeKey: String) =
        IndicatorDef(IndicatorSource.Runtime(runtimeKey), IndicatorStyle.DOT_TOP_RIGHT)

    private val backspace = fn(
        id = "backspace", label = null, icon = "backspace",
        action = KeyAction.Backspace(TextUnit.CHARACTER),
        width = 1.5f, repeatable = true,
        longPress = KeyAction.Backspace(TextUnit.WORD),
        swipes = mapOf(SwipeDirection.LEFT to KeyAction.Backspace(TextUnit.WORD))
    )

    private val enterKey = fn(
        id = "enter", label = null, icon = "enter",
        action = KeyAction.Enter, width = 1.5f,
        longPress = KeyAction.Text("\n")
    )

    private fun shiftKey(width: Float = 1.5f) = fn(
        id = "shift", label = null, icon = "shift",
        action = KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT),
        width = width, style = "modifier",
        longPress = KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.LOCK),
        // Only the lock lamp. The bar that used to sit here said "shift is on", which
        // the key's own highlight already says — two marks for one fact, while the
        // lamp beside them meant something else entirely.
        indicators = listOf(lockIndicator(IndicatorKeys.CAPS_LOCK))
    )

    private fun spaceKey(width: Float = 4f) = fn(
        id = "space", label = null, icon = "space",
        action = KeyAction.Space, width = width, style = "normal",
        longPress = KeyAction.SwitchLanguage(target = SwitchTarget.NEXT),
        swipes = mapOf(
            SwipeDirection.LEFT to KeyAction.MoveCursor(CursorDirection.LEFT),
            SwipeDirection.RIGHT to KeyAction.MoveCursor(CursorDirection.RIGHT),
            SwipeDirection.UP to KeyAction.OpenPanel(PanelId.CLIPBOARD)
        )
    )

    private fun symbolsSwitch(target: String = LayoutDef.SYMBOL_LAYER, label: String = "?123") = fn(
        id = "to_$target", label = label,
        action = KeyAction.Layer(target, LayerMode.TOGGLE), width = 1.5f,
        longPress = KeyAction.SwitchLayout(target = SwitchTarget.NEXT)
    )

    private fun alphaSwitch() = fn(
        id = "to_base", label = "ABC",
        action = KeyAction.Layer(LayoutDef.BASE_LAYER, LayerMode.TOGGLE), width = 1.5f
    )

    private val emojiKey = fn(
        id = "emoji", label = null, icon = "emoji",
        action = KeyAction.OpenPanel(PanelId.EMOJI),
        longPress = KeyAction.OpenPanel(PanelId.CLIPBOARD)
    )

    private val micKey = fn(
        id = "mic", label = null, icon = "mic",
        action = KeyAction.Voice,
        longPress = KeyAction.OpenPanel(PanelId.VOICE),
        indicators = listOf(
            IndicatorDef(IndicatorSource.Runtime(IndicatorKeys.ASR_LISTENING), IndicatorStyle.GLOW)
        )
    )

    private val aiKey = fn(
        id = "ai", label = null, icon = "ai",
        action = KeyAction.OpenPanel(PanelId.AI_TOOLS),
        indicators = listOf(
            IndicatorDef(IndicatorSource.Runtime(IndicatorKeys.AI_BUSY), IndicatorStyle.GLOW)
        )
    )

    private val commaKey = fn(
        id = "comma", label = ",", action = KeyAction.Text(","), style = "normal",
        longPress = KeyAction.OpenPanel(PanelId.SETTINGS),
        popup = listOf("،", ";", ":", "!", "?", "'", "\"")
    )

    private val periodKey = fn(
        id = "period", label = ".", action = KeyAction.Text("."), style = "normal",
        longPress = KeyAction.Text("…"),
        popup = listOf("…", "-", "_", "/", "\\", "@", "#", "&")
    )

    // -----------------------------------------------------------------------
    // Alphabetic layouts
    // -----------------------------------------------------------------------

    /** Long-press alternates that most Latin layouts want. */
    private val LATIN_POPUPS: Map<String, List<String>> = mapOf(
        "a" to listOf("ą", "á", "à", "â", "ä", "ã", "å", "ā", "æ"),
        "c" to listOf("ć", "ç", "č", "©"),
        "e" to listOf("ę", "é", "è", "ê", "ë", "ē", "€"),
        "i" to listOf("í", "ì", "î", "ï", "ī"),
        "l" to listOf("ł", "ĺ", "£"),
        "n" to listOf("ń", "ñ", "ň"),
        "o" to listOf("ó", "ö", "ô", "ò", "õ", "ø", "ō", "œ"),
        "s" to listOf("ś", "š", "ş", "ß"),
        "u" to listOf("ú", "ü", "û", "ù", "ū"),
        "y" to listOf("ý", "ÿ"),
        "z" to listOf("ż", "ź", "ž"),
        "d" to listOf("đ", "ð"),
        "g" to listOf("ğ"),
        "t" to listOf("ť", "þ"),
        "r" to listOf("ř")
    )

    /** The digit a letter flicks to, matching its column on a PC keyboard. */
    private val TOP_ROW_DIGITS = "1234567890"

    private fun alphaLayout(
        id: String,
        name: String,
        locale: String,
        row1: String,
        row2: String,
        row3: String,
        numberRow: Boolean,
        popups: Map<String, List<String>> = LATIN_POPUPS,
        description: String? = null
    ): LayoutDef {
        fun letters(src: String, flick: Boolean): List<KeyDef> =
            src.mapIndexed { index, c ->
                val s = c.toString()
                ch(
                    c = s,
                    popup = popups[s] ?: emptyList(),
                    swipeUp = if (flick) TOP_ROW_DIGITS.getOrNull(index)?.toString() else null,
                    hint = null
                )
            }

        fun buildLayer(upper: Boolean): LayerDef {
            fun cased(src: String, flick: Boolean) = letters(src, flick).map { k ->
                if (!upper) k else {
                    val up = k.id.uppercase()
                    k.copy(
                        label = up,
                        bindings = k.bindings.map { b ->
                            if (b.trigger == KeyTrigger.Tap) Binding(b.trigger, KeyAction.Text(up)) else b
                        },
                        popup = k.popup.map { it.uppercase() }
                    )
                }
            }

            val rows = mutableListOf<RowDef>()
            if (numberRow) {
                rows += RowDef(
                    keys = TOP_ROW_DIGITS.map { d ->
                        ch(d.toString(), popup = digitPopup(d))
                    },
                    heightWeight = 0.85f
                )
            }
            rows += RowDef(cased(row1, !numberRow))
            rows += RowDef(cased(row2, false), padStart = 0.5f, padEnd = 0.5f)
            rows += RowDef(listOf(shiftKey()) + cased(row3, false) + listOf(backspace))
            rows += RowDef(
                listOf(
                    symbolsSwitch(),
                    emojiKey,
                    commaKey,
                    spaceKey(4f),
                    periodKey,
                    micKey,
                    enterKey
                )
            )
            return LayerDef(if (upper) LayoutDef.SHIFT_LAYER else LayoutDef.BASE_LAYER, rows)
        }

        return LayoutDef(
            id = id,
            name = name,
            locale = locale,
            layers = linkedMapOf(
                LayoutDef.BASE_LAYER to buildLayer(false),
                LayoutDef.SHIFT_LAYER to buildLayer(true),
                LayoutDef.SYMBOL_LAYER to symbolLayer(),
                LayoutDef.SYMBOL_SHIFT_LAYER to symbolShiftLayer()
            ),
            rowCountHint = if (numberRow) 5 else 4,
            description = description,
            builtIn = true
        )
    }

    private fun digitPopup(d: Char): List<String> = when (d) {
        '1' -> listOf("!", "¹", "½", "⅓")
        '2' -> listOf("@", "²", "⅔")
        '3' -> listOf("#", "³", "¾")
        '4' -> listOf("\$", "€", "£", "¥", "₽", "₹", "zł")
        '5' -> listOf("%", "‰")
        '6' -> listOf("^", "°")
        '7' -> listOf("&", "§")
        '8' -> listOf("*", "†", "‡")
        '9' -> listOf("(", "[", "{", "<")
        '0' -> listOf(")", "]", "}", ">", "ø")
        else -> emptyList()
    }

    private fun symbolLayer(): LayerDef = LayerDef(
        name = LayoutDef.SYMBOL_LAYER,
        rows = listOf(
            RowDef("1234567890".map { ch(it.toString(), popup = digitPopup(it)) }),
            RowDef("@#\$_&-+()/".map { ch(it.toString()) }),
            RowDef(
                listOf(fn("to_sym2", "=\\<", KeyAction.Layer(LayoutDef.SYMBOL_SHIFT_LAYER, LayerMode.TOGGLE), 1.5f)) +
                    "*\"':;!?".map { ch(it.toString()) } +
                    listOf(backspace)
            ),
            RowDef(
                listOf(
                    alphaSwitch(),
                    emojiKey,
                    commaKey,
                    spaceKey(4f),
                    periodKey,
                    micKey,
                    enterKey
                )
            )
        )
    )

    private fun symbolShiftLayer(): LayerDef = LayerDef(
        name = LayoutDef.SYMBOL_SHIFT_LAYER,
        rows = listOf(
            RowDef("~`|•√π÷×¶∆".map { ch(it.toString()) }),
            RowDef(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { ch(it) }),
            RowDef(
                listOf(fn("to_sym1", "?123", KeyAction.Layer(LayoutDef.SYMBOL_LAYER, LayerMode.TOGGLE), 1.5f)) +
                    listOf("%", "©", "®", "™", "✓", "[", "]").map { ch(it) } +
                    listOf(backspace)
            ),
            RowDef(
                listOf(
                    alphaSwitch(),
                    emojiKey,
                    fn("lt", "<", KeyAction.Text("<"), style = "normal"),
                    spaceKey(4f),
                    fn("gt", ">", KeyAction.Text(">"), style = "normal"),
                    micKey,
                    enterKey
                )
            )
        )
    )

    // -----------------------------------------------------------------------
    // Numeric keypad
    // -----------------------------------------------------------------------

    private fun numpad(): LayoutDef = LayoutDef(
        id = "numpad",
        name = "Numpad",
        layers = linkedMapOf(
            LayoutDef.BASE_LAYER to LayerDef(
                LayoutDef.BASE_LAYER,
                rows = listOf(
                    RowDef(listOf(ch("7"), ch("8"), ch("9"), ch("/"), backspace)),
                    RowDef(listOf(ch("4"), ch("5"), ch("6"), ch("*"),
                        fn("num_left", "←", KeyAction.MoveCursor(CursorDirection.LEFT), repeatable = true))),
                    RowDef(listOf(ch("1"), ch("2"), ch("3"), ch("-"),
                        fn("num_right", "→", KeyAction.MoveCursor(CursorDirection.RIGHT), repeatable = true))),
                    RowDef(listOf(
                        ch("0", width = 2f),
                        fn("num_dot", ".", KeyAction.Text("."), style = "normal",
                            popup = listOf(",", ":", ";")),
                        ch("+"),
                        enterKey
                    )),
                    RowDef(listOf(
                        alphaLayoutSwitch(),
                        ch("("), ch(")"), ch("="), ch("%"),
                        spaceKey(2f)
                    ), heightWeight = 0.85f)
                )
            )
        ),
        rowCountHint = 5,
        description = "Calculator-style keypad with cursor keys.",
        builtIn = true
    )

    private fun alphaLayoutSwitch() =
        fn("to_alpha_layout", "ABC", KeyAction.SwitchLayout(layoutId = "qwerty_pl"), 1.2f)

    // -----------------------------------------------------------------------
    // Navigation / editing
    // -----------------------------------------------------------------------

    private fun navigation(): LayoutDef {
        fun nav(label: String, dir: CursorDirection, unit: TextUnit = TextUnit.CHARACTER, width: Float = 1f) =
            fn("nav_${dir.name}_${unit.name}", label, KeyAction.MoveCursor(dir, unit), width,
                repeatable = true, style = "normal",
                longPress = KeyAction.MoveCursor(dir, unit, extendSelection = true))

        return LayoutDef(
            id = "navigation",
            name = "Navigation & editing",
            layers = linkedMapOf(
                LayoutDef.BASE_LAYER to LayerDef(
                    LayoutDef.BASE_LAYER,
                    rows = listOf(
                        RowDef(listOf(
                            fn("sel_all", "Select all", KeyAction.Select(TextUnit.ALL), 1.6f),
                            fn("cut", "Cut", KeyAction.Clipboard(ClipboardOp.CUT), 1.2f),
                            fn("copy", "Copy", KeyAction.Clipboard(ClipboardOp.COPY), 1.2f),
                            fn("paste", "Paste", KeyAction.Clipboard(ClipboardOp.PASTE), 1.2f,
                                longPress = KeyAction.OpenPanel(PanelId.CLIPBOARD)),
                            fn("undo", "Undo", KeyAction.Undo, 1.2f),
                            fn("redo", "Redo", KeyAction.Redo, 1.2f)
                        )),
                        RowDef(listOf(
                            nav("⇤", CursorDirection.LINE_START),
                            nav("◂◂", CursorDirection.LEFT, TextUnit.WORD),
                            nav("▲", CursorDirection.UP),
                            nav("▸▸", CursorDirection.RIGHT, TextUnit.WORD),
                            nav("⇥", CursorDirection.LINE_END)
                        )),
                        RowDef(listOf(
                            fn("sel_word", "Word", KeyAction.Select(TextUnit.WORD), 1f),
                            nav("◂", CursorDirection.LEFT),
                            nav("▼", CursorDirection.DOWN),
                            nav("▸", CursorDirection.RIGHT),
                            fn("sel_line", "Line", KeyAction.Select(TextUnit.LINE), 1f)
                        )),
                        RowDef(listOf(
                            nav("Top", CursorDirection.DOC_START),
                            key(KeyEvent.KEYCODE_PAGE_UP, "PgUp"),
                            key(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"),
                            nav("End", CursorDirection.DOC_END),
                            fn("fdel", "Del→", KeyAction.ForwardDelete(TextUnit.CHARACTER), repeatable = true)
                        )),
                        RowDef(listOf(
                            alphaLayoutSwitch(),
                            emojiKey,
                            aiKey,
                            spaceKey(2.5f),
                            backspace,
                            enterKey
                        ))
                    )
                )
            ),
            rowCountHint = 5,
            description = "Cursor, selection and clipboard, nothing else.",
            builtIn = true
        )
    }

    // -----------------------------------------------------------------------
    // Full PC layout, with lit modifiers. The Hacker's-Keyboard homage.
    // -----------------------------------------------------------------------

    private fun hacker(): LayoutDef {
        fun mod(id: String, label: String, kind: ModifierKind, width: Float = 1.3f) = fn(
            id = id, label = label,
            action = KeyAction.Modifier(kind, ModifierMode.ONE_SHOT),
            width = width, style = "modifier",
            longPress = KeyAction.Modifier(kind, ModifierMode.LOCK),
            indicators = listOf(
                modIndicator(kind),
                IndicatorDef(IndicatorSource.ModifierLock(kind), IndicatorStyle.DOT_TOP_RIGHT)
            )
        )

        /**
         * A letter that types normally but turns into a raw key event once Ctrl or Alt
         * is down, so Ctrl+C reaches the app instead of committing the letter "c".
         */
        fun pcLetter(c: String, shifted: String? = null): KeyDef {
            val code = KeyCodes.code(c)
            return KeyDef(
                id = "pc_$c",
                label = c,
                hint = shifted,
                bindings = listOf(
                    Binding(KeyTrigger.Tap, KeyAction.Text(c)),
                    Binding(KeyTrigger.LongPress, if (code != null) KeyAction.SendKey(code) else KeyAction.Text(c))
                ),
                popup = LATIN_POPUPS[c] ?: emptyList()
            )
        }

        fun sym(c: String, shifted: String) = KeyDef(
            id = "sym_$c",
            label = c,
            hint = shifted,
            bindings = listOf(
                Binding(KeyTrigger.Tap, KeyAction.Text(c)),
                Binding(KeyTrigger.Swipe(SwipeDirection.UP), KeyAction.Text(shifted)),
                Binding(KeyTrigger.LongPress, KeyAction.Text(shifted))
            )
        )

        val fRow = RowDef(
            keys = listOf(key(KeyEvent.KEYCODE_ESCAPE, "Esc")) +
                (1..12).map { key(KeyEvent.KEYCODE_F1 + it - 1, "F$it") },
            heightWeight = 0.7f
        )

        val digitRow = RowDef(
            keys = listOf(sym("`", "~")) +
                listOf("1" to "!", "2" to "@", "3" to "#", "4" to "\$", "5" to "%",
                    "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")")
                    .map { (a, b) -> sym(a, b) } +
                listOf(sym("-", "_"), sym("=", "+"), backspace),
            heightWeight = 0.9f
        )

        val qRow = RowDef(
            keys = listOf(key(KeyEvent.KEYCODE_TAB, "Tab", width = 1.3f)) +
                "qwertyuiop".map { pcLetter(it.toString()) } +
                listOf(sym("[", "{"), sym("]", "}"), sym("\\", "|"))
        )

        val aRow = RowDef(
            keys = listOf(
                fn(
                    id = "capslock", label = "Caps",
                    action = KeyAction.Modifier(ModifierKind.SHIFT, ModifierMode.LOCK),
                    width = 1.6f, style = "modifier",
                    indicators = listOf(lockIndicator(IndicatorKeys.CAPS_LOCK))
                )
            ) + "asdfghjkl".map { pcLetter(it.toString()) } +
                listOf(sym(";", ":"), sym("'", "\""), enterKey.copy(widthWeight = 1.8f))
        )

        val zRow = RowDef(
            keys = listOf(mod("shift_l", "Shift", ModifierKind.SHIFT, 2f)) +
                "zxcvbnm".map { pcLetter(it.toString()) } +
                listOf(sym(",", "<"), sym(".", ">"), sym("/", "?"),
                    mod("shift_r", "Shift", ModifierKind.SHIFT, 1.6f))
        )

        val bottomRow = RowDef(
            keys = listOf(
                mod("ctrl_l", "Ctrl", ModifierKind.CTRL),
                mod("meta", "Meta", ModifierKind.META),
                mod("alt_l", "Alt", ModifierKind.ALT),
                spaceKey(4.5f),
                mod("altgr", "AltGr", ModifierKind.ALT_GR),
                mod("ctrl_r", "Ctrl", ModifierKind.CTRL),
                fn("h_left", "←", KeyAction.MoveCursor(CursorDirection.LEFT), repeatable = true, style = "normal"),
                fn("h_up", "↑", KeyAction.MoveCursor(CursorDirection.UP), repeatable = true, style = "normal"),
                fn("h_down", "↓", KeyAction.MoveCursor(CursorDirection.DOWN), repeatable = true, style = "normal"),
                fn("h_right", "→", KeyAction.MoveCursor(CursorDirection.RIGHT), repeatable = true, style = "normal"),
                fn("h_layout", "⌨", KeyAction.SwitchLayout(target = SwitchTarget.NEXT),
                    longPress = KeyAction.ShowSettings)
            )
        )

        return LayoutDef(
            id = "hacker",
            name = "Full PC keyboard",
            layers = linkedMapOf(
                LayoutDef.BASE_LAYER to LayerDef(
                    LayoutDef.BASE_LAYER,
                    rows = listOf(fRow, digitRow, qRow, aRow, zRow, bottomRow)
                )
            ),
            rowCountHint = 6,
            description = "Every key a PC keyboard has: function row, real modifiers with " +
                "lit state, Tab, Esc and cursor keys. Long-press a letter to send it as a " +
                "raw key event instead of text.",
            builtIn = true
        )
    }

    // -----------------------------------------------------------------------
    // Registry
    // -----------------------------------------------------------------------

    val QWERTY_PL = alphaLayout(
        id = "qwerty_pl", name = "Polski (QWERTY)", locale = "pl",
        row1 = "qwertyuiop", row2 = "asdfghjkl", row3 = "zxcvbnm",
        numberRow = false,
        description = "Polish diacritics on long-press; flick a letter up for its digit."
    )

    val QWERTY_EN = alphaLayout(
        id = "qwerty_en", name = "English (QWERTY)", locale = "en",
        row1 = "qwertyuiop", row2 = "asdfghjkl", row3 = "zxcvbnm",
        numberRow = false
    )

    val QWERTY_NUMROW = alphaLayout(
        id = "qwerty_numrow", name = "QWERTY + number row", locale = "en",
        row1 = "qwertyuiop", row2 = "asdfghjkl", row3 = "zxcvbnm",
        numberRow = true
    )

    val QWERTZ = alphaLayout(
        id = "qwertz", name = "QWERTZ (DE/CZ)", locale = "de",
        row1 = "qwertzuiop", row2 = "asdfghjkl", row3 = "yxcvbnm",
        numberRow = false
    )

    val AZERTY = alphaLayout(
        id = "azerty", name = "AZERTY (FR)", locale = "fr",
        row1 = "azertyuiop", row2 = "qsdfghjklm", row3 = "wxcvbn",
        numberRow = false
    )

    val DVORAK = alphaLayout(
        id = "dvorak", name = "Dvorak", locale = "en",
        row1 = "pyfgcrl", row2 = "aoeuidhtns", row3 = "qjkxbmwvz",
        numberRow = false
    )

    val COLEMAK = alphaLayout(
        id = "colemak", name = "Colemak", locale = "en",
        row1 = "qwfpgjluy", row2 = "arstdhneio", row3 = "zxcvbkm",
        numberRow = false
    )

    val SYMBOLS = LayoutDef(
        id = "symbols",
        name = "Symbols",
        layers = linkedMapOf(
            LayoutDef.BASE_LAYER to symbolLayer().copy(name = LayoutDef.BASE_LAYER),
            LayoutDef.SYMBOL_SHIFT_LAYER to symbolShiftLayer()
        ),
        rowCountHint = 4,
        builtIn = true
    )

    val NUMPAD = numpad()
    val NAVIGATION = navigation()
    val HACKER = hacker()

    val ALL: List<LayoutDef> = listOf(
        QWERTY_PL, QWERTY_EN, QWERTY_NUMROW, QWERTZ, AZERTY, DVORAK, COLEMAK,
        SYMBOLS, NUMPAD, NAVIGATION, HACKER, ScienceLayout.SCIENCE, WorkbenchLayout.WORKBENCH
    )

    fun byId(id: String): LayoutDef? = ALL.firstOrNull { it.id == id }
}
