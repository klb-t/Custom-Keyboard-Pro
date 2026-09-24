package com.example.core.layout

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for [LayoutDef].
 *
 * Hand-written on top of `org.json` rather than generated, for three reasons: the
 * format is something users and language models both write by hand, so it has to
 * forgive shorthand and report useful errors; the shape is a sealed hierarchy that
 * reflective adapters handle badly; and it adds no dependency.
 *
 * Reading is lenient — unknown fields are ignored, missing fields take defaults,
 * and a key may be written as a bare string. Writing is canonical and complete, so
 * `parse(write(x)) == x`.
 */
object LayoutJson {

    class ParseException(message: String) : Exception(message)

    // -----------------------------------------------------------------------
    // Colours
    // -----------------------------------------------------------------------

    fun parseColor(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().removePrefix("#")
        return try {
            when (s.length) {
                6 -> 0xFF000000L or s.toLong(16)
                8 -> s.toLong(16)
                3 -> {
                    val r = s[0].digitToInt(16) * 17
                    val g = s[1].digitToInt(16) * 17
                    val b = s[2].digitToInt(16) * 17
                    0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun colorToString(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /** Accepts an object, or a bare string treated as literal text. */
    fun parseAction(any: Any?): KeyAction? {
        if (any == null || any === JSONObject.NULL) return null
        if (any is String) {
            if (any.isEmpty()) return null
            // "type:payload" shorthand, e.g. "panel:emoji" or "key:KEYCODE_TAB".
            val colon = any.indexOf(':')
            if (colon > 0) {
                val head = any.substring(0, colon)
                val tail = any.substring(colon + 1)
                if (head in SHORTHAND_TYPES) {
                    return parseAction(JSONObject().put("type", head).put(SHORTHAND_FIELD[head] ?: "value", tail))
                }
            }
            return KeyAction.Text(any)
        }
        val o = any as? JSONObject ?: return null
        return when (o.optString("type", "text").lowercase()) {
            "none" -> KeyAction.None
            "text" -> KeyAction.Text(o.optString("text", o.optString("value", "")))
            "key" -> KeyAction.SendKey(
                keyCode = parseKeyCode(o.opt("code") ?: o.opt("keyCode") ?: o.opt("value")),
                metaState = parseMeta(o.opt("meta"))
            )
            "modifier" -> KeyAction.Modifier(
                kind = enumOf(o.optString("kind", o.optString("value", "shift")), ModifierKind.SHIFT),
                mode = enumOf(o.optString("mode", "one_shot"), ModifierMode.ONE_SHOT)
            )
            "layer" -> KeyAction.Layer(
                layer = o.optString("layer", o.optString("value", LayoutDef.BASE_LAYER)),
                mode = enumOf(o.optString("mode", "momentary"), LayerMode.MOMENTARY)
            )
            "layout" -> KeyAction.SwitchLayout(
                layoutId = o.optStringOrNull("layout") ?: o.optStringOrNull("value"),
                target = enumOf(o.optString("target", "next"), SwitchTarget.NEXT)
            )
            "language" -> KeyAction.SwitchLanguage(
                locale = o.optStringOrNull("locale") ?: o.optStringOrNull("value"),
                target = enumOf(o.optString("target", "next"), SwitchTarget.NEXT)
            )
            "backspace" -> KeyAction.Backspace(enumOf(o.optString("unit", o.optString("value", "character")), TextUnit.CHARACTER))
            "delete" -> KeyAction.ForwardDelete(enumOf(o.optString("unit", o.optString("value", "character")), TextUnit.CHARACTER))
            "enter" -> KeyAction.Enter
            "space" -> KeyAction.Space
            "cursor" -> KeyAction.MoveCursor(
                direction = enumOf(o.optString("direction", o.optString("value", "left")), CursorDirection.LEFT),
                unit = enumOf(o.optString("unit", "character"), TextUnit.CHARACTER),
                extendSelection = o.optBoolean("select", false)
            )
            "select" -> KeyAction.Select(enumOf(o.optString("unit", o.optString("value", "all")), TextUnit.ALL))
            "clipboard" -> KeyAction.Clipboard(enumOf(o.optString("op", o.optString("value", "paste")), ClipboardOp.PASTE))
            "undo" -> KeyAction.Undo
            "redo" -> KeyAction.Redo
            "panel" -> KeyAction.OpenPanel(enumOf(o.optString("panel", o.optString("value", "emoji")), PanelId.EMOJI))
            "voice" -> KeyAction.Voice
            "ai" -> KeyAction.Ai(o.optString("task", o.optString("value", "")))
            "macro" -> KeyAction.Macro(
                (0 until (o.optJSONArray("steps")?.length() ?: 0)).mapNotNull { i ->
                    parseAction(o.optJSONArray("steps")?.opt(i))
                }
            )
            "dead" -> {
                val combining = o.optString("combining", o.optString("value", ""))
                KeyAction.DeadKey(combining, o.optString("display", combining))
            }
            "compose" -> KeyAction.Compose
            "unicode" -> KeyAction.UnicodeInput
            "repeatlast", "repeat_last" -> KeyAction.RepeatLast
            "settings" -> KeyAction.ShowSettings
            "switchime", "switch_ime" -> KeyAction.SwitchIme
            "hide" -> KeyAction.HideKeyboard
            "presentation" -> KeyAction.Presentation(enumOf(o.optString("mode", o.optString("value", "cycle")), PresentationMode.CYCLE))
            "do" -> com.example.core.io.Command.parse(o.optString("command", o.optString("value", "")))
                ?.let { KeyAction.Do(it) } ?: KeyAction.None
            else -> KeyAction.Text(o.optString("text", ""))
        }
    }

    private val SHORTHAND_TYPES = setOf(
        "text", "key", "modifier", "layer", "layout", "language", "backspace",
        "delete", "cursor", "select", "clipboard", "panel", "ai", "dead", "presentation", "do"
    )

    private val SHORTHAND_FIELD = mapOf(
        "text" to "text", "key" to "code", "modifier" to "kind", "layer" to "layer",
        "layout" to "layout", "language" to "locale", "backspace" to "unit",
        "delete" to "unit", "cursor" to "direction", "select" to "unit",
        "clipboard" to "op", "panel" to "panel", "ai" to "task", "dead" to "combining",
        "presentation" to "mode", "do" to "command"
    )

    fun writeAction(action: KeyAction): Any = when (action) {
        is KeyAction.None -> JSONObject().put("type", "none")
        is KeyAction.Text -> JSONObject().put("type", "text").put("text", action.text)
        is KeyAction.SendKey -> JSONObject().put("type", "key")
            .put("code", KeyCodes.name(action.keyCode) ?: action.keyCode)
            .put("meta", MetaCodes.describe(action.metaState))
        is KeyAction.Modifier -> JSONObject().put("type", "modifier")
            .put("kind", action.kind.name.lowercase()).put("mode", action.mode.name.lowercase())
        is KeyAction.Layer -> JSONObject().put("type", "layer")
            .put("layer", action.layer).put("mode", action.mode.name.lowercase())
        is KeyAction.SwitchLayout -> JSONObject().put("type", "layout")
            .putOpt("layout", action.layoutId).put("target", action.target.name.lowercase())
        is KeyAction.SwitchLanguage -> JSONObject().put("type", "language")
            .putOpt("locale", action.locale).put("target", action.target.name.lowercase())
        is KeyAction.Backspace -> JSONObject().put("type", "backspace").put("unit", action.unit.name.lowercase())
        is KeyAction.ForwardDelete -> JSONObject().put("type", "delete").put("unit", action.unit.name.lowercase())
        is KeyAction.Enter -> JSONObject().put("type", "enter")
        is KeyAction.Space -> JSONObject().put("type", "space")
        is KeyAction.MoveCursor -> JSONObject().put("type", "cursor")
            .put("direction", action.direction.name.lowercase())
            .put("unit", action.unit.name.lowercase())
            .put("select", action.extendSelection)
        is KeyAction.Select -> JSONObject().put("type", "select").put("unit", action.unit.name.lowercase())
        is KeyAction.Clipboard -> JSONObject().put("type", "clipboard").put("op", action.op.name.lowercase())
        is KeyAction.Undo -> JSONObject().put("type", "undo")
        is KeyAction.Redo -> JSONObject().put("type", "redo")
        is KeyAction.OpenPanel -> JSONObject().put("type", "panel").put("panel", action.panel.name.lowercase())
        is KeyAction.Voice -> JSONObject().put("type", "voice")
        is KeyAction.Ai -> JSONObject().put("type", "ai").put("task", action.taskId)
        is KeyAction.Macro -> JSONObject().put("type", "macro")
            .put("steps", JSONArray().also { arr -> action.steps.forEach { arr.put(writeAction(it)) } })
        is KeyAction.DeadKey -> JSONObject().put("type", "dead")
            .put("combining", action.combining).put("display", action.display)
        is KeyAction.Compose -> JSONObject().put("type", "compose")
        is KeyAction.UnicodeInput -> JSONObject().put("type", "unicode")
        is KeyAction.RepeatLast -> JSONObject().put("type", "repeat_last")
        is KeyAction.ShowSettings -> JSONObject().put("type", "settings")
        is KeyAction.SwitchIme -> JSONObject().put("type", "switch_ime")
        is KeyAction.HideKeyboard -> JSONObject().put("type", "hide")
        is KeyAction.Presentation -> JSONObject().put("type", "presentation").put("mode", action.mode.name.lowercase())
        is KeyAction.Do -> JSONObject().put("type", "do").put("command", action.command.write())
    }

    private fun parseKeyCode(raw: Any?): Int = when (raw) {
        null -> 0
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: KeyCodes.code(raw) ?: 0
        else -> 0
    }

    private fun parseMeta(raw: Any?): Int = when (raw) {
        null -> 0
        is Number -> raw.toInt()
        is String -> MetaCodes.parse(raw)
        is JSONArray -> (0 until raw.length()).fold(0) { acc, i -> acc or MetaCodes.parse(raw.optString(i)) }
        else -> 0
    }

    // -----------------------------------------------------------------------
    // Indicators
    // -----------------------------------------------------------------------

    fun parseIndicator(o: JSONObject): IndicatorDef {
        val rawSource = o.optString("source", IndicatorKeys.CAPS_LOCK).lowercase()
        val source: IndicatorSource = when {
            rawSource.startsWith("layer:") -> IndicatorSource.Layer(rawSource.removePrefix("layer:"))
            rawSource.startsWith("lock:") ->
                IndicatorSource.ModifierLock(enumOf(rawSource.removePrefix("lock:"), ModifierKind.SHIFT))
            rawSource.startsWith("mod:") ->
                IndicatorSource.Modifier(enumOf(rawSource.removePrefix("mod:"), ModifierKind.SHIFT))
            ModifierKind.entries.any { it.name.equals(rawSource, true) } ->
                IndicatorSource.Modifier(enumOf(rawSource, ModifierKind.SHIFT))
            else -> IndicatorSource.Runtime(rawSource)
        }
        return IndicatorDef(
            source = source,
            style = enumOf(o.optString("style", "dot_top_right"), IndicatorStyle.DOT_TOP_RIGHT),
            onColor = parseColor(o.optStringOrNull("on")),
            offColor = parseColor(o.optStringOrNull("off"))
        )
    }

    fun writeIndicator(def: IndicatorDef): JSONObject {
        val source = when (val s = def.source) {
            is IndicatorSource.Modifier -> "mod:${s.kind.name.lowercase()}"
            is IndicatorSource.ModifierLock -> "lock:${s.kind.name.lowercase()}"
            is IndicatorSource.Layer -> "layer:${s.layer}"
            is IndicatorSource.Runtime -> s.key
        }
        return JSONObject()
            .put("source", source)
            .put("style", def.style.name.lowercase())
            .putOpt("on", def.onColor?.let { colorToString(it) })
            .putOpt("off", def.offColor?.let { colorToString(it) })
    }

    // -----------------------------------------------------------------------
    // Keys
    // -----------------------------------------------------------------------

    private var autoKeyCounter = 0

    fun parseKey(any: Any?, fallbackId: String): KeyDef? {
        if (any == null || any === JSONObject.NULL) return null
        if (any is String) {
            if (any.isEmpty()) return null
            return KeyDef(
                id = any,
                label = any,
                bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text(any)))
            )
        }
        val o = any as? JSONObject ?: return null
        val bindings = mutableListOf<Binding>()
        parseAction(o.opt("tap"))?.let { bindings += Binding(KeyTrigger.Tap, it) }
        parseAction(o.opt("longPress") ?: o.opt("long_press") ?: o.opt("hold"))
            ?.let { bindings += Binding(KeyTrigger.LongPress, it) }
        parseAction(o.opt("doubleTap") ?: o.opt("double_tap"))
            ?.let { bindings += Binding(KeyTrigger.DoubleTap, it) }
        parseAction(o.opt("repeatAction") ?: o.opt("repeat_action"))
            ?.let { bindings += Binding(KeyTrigger.Repeat, it) }

        o.optJSONObject("swipe")?.let { swipes ->
            swipes.keys().forEach { dirName ->
                val dir = SwipeDirection.entries.firstOrNull { it.name.equals(dirName.replace('-', '_'), true) }
                val action = parseAction(swipes.opt(dirName))
                if (dir != null && action != null) bindings += Binding(KeyTrigger.Swipe(dir), action)
            }
        }
        o.optJSONObject("chords")?.let { chords ->
            chords.keys().forEach { other ->
                parseAction(chords.opt(other))?.let { bindings += Binding(KeyTrigger.Chord(other), it) }
            }
        }

        val popup = o.optJSONArray("popup")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } ?: emptyList()

        val indicators = o.optJSONArray("indicators")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseIndicator(it) } }
        } ?: emptyList()

        val label = o.optStringOrNull("label")
        val id = o.optStringOrNull("id") ?: label ?: fallbackId

        // A key written with only a label and no explicit tap binding commits its label.
        if (bindings.none { it.trigger == KeyTrigger.Tap } && label != null && o.optStringOrNull("icon") == null) {
            bindings += Binding(KeyTrigger.Tap, KeyAction.Text(label))
        }

        return KeyDef(
            id = id,
            label = label,
            hint = o.optStringOrNull("hint"),
            icon = o.optStringOrNull("icon"),
            widthWeight = o.optDouble("width", o.optDouble("w", 1.0)).toFloat().let { if (it > 0f) it else 1f },
            style = o.optStringOrNull("style"),
            bindings = bindings,
            popup = popup,
            popupGroups = readPopupGroups(o),
            repeatable = o.optBoolean("repeat", false),
            indicators = indicators,
            shape = enumOf(o.optString("shape", "rounded"), KeyShape.ROUNDED),
            bounds = parseRect(o.opt("bounds")),
            touchWeight = o.optDouble("touch", 1.0).toFloat(),
            visible = o.optBoolean("visible", true)
        )
    }

    private fun parseRect(any: Any?): NormRect? = when (any) {
        is JSONArray -> if (any.length() >= 4) NormRect(
            any.optDouble(0).toFloat(), any.optDouble(1).toFloat(),
            any.optDouble(2).toFloat(), any.optDouble(3).toFloat()
        ) else null
        is JSONObject -> NormRect(
            any.optDouble("left", 0.0).toFloat(), any.optDouble("top", 0.0).toFloat(),
            any.optDouble("right", 0.0).toFloat(), any.optDouble("bottom", 0.0).toFloat()
        )
        else -> null
    }

    fun writeKey(key: KeyDef): JSONObject {
        val o = JSONObject()
            .put("id", key.id)
            .putOpt("label", key.label)
            .putOpt("hint", key.hint)
            .putOpt("icon", key.icon)
            .put("width", key.widthWeight.toDouble())
            .putOpt("style", key.style)
            .put("shape", key.shape.name.lowercase())
            .put("touch", key.touchWeight.toDouble())
        if (key.repeatable) o.put("repeat", true)
        if (!key.visible) o.put("visible", false)
        key.bounds?.let {
            o.put("bounds", JSONArray().put(it.left.toDouble()).put(it.top.toDouble())
                .put(it.right.toDouble()).put(it.bottom.toDouble()))
        }
        key.actionFor(KeyTrigger.Tap)?.let { o.put("tap", writeAction(it)) }
        key.actionFor(KeyTrigger.LongPress)?.let { o.put("longPress", writeAction(it)) }
        key.actionFor(KeyTrigger.DoubleTap)?.let { o.put("doubleTap", writeAction(it)) }
        key.actionFor(KeyTrigger.Repeat)?.let { o.put("repeatAction", writeAction(it)) }

        val swipes = JSONObject()
        key.bindings.forEach { b ->
            val t = b.trigger
            if (t is KeyTrigger.Swipe) swipes.put(t.direction.name.lowercase(), writeAction(b.action))
        }
        if (swipes.length() > 0) o.put("swipe", swipes)

        val chords = JSONObject()
        key.bindings.forEach { b ->
            val t = b.trigger
            if (t is KeyTrigger.Chord) chords.put(t.otherKeyId, writeAction(b.action))
        }
        if (chords.length() > 0) o.put("chords", chords)

        if (key.popup.isNotEmpty()) o.put("popup", JSONArray(key.popup))
        if (key.popupGroups.isNotEmpty()) {
            o.put("popupGroups", JSONArray().apply {
                key.popupGroups.forEach { group ->
                    put(JSONObject().apply {
                        put("id", group.id)
                        put("label", group.label)
                        group.title?.let { put("title", it) }
                        put("items", JSONArray(group.items))
                    })
                }
            })
        }
        if (key.indicators.isNotEmpty()) {
            o.put("indicators", JSONArray().also { arr -> key.indicators.forEach { arr.put(writeIndicator(it)) } })
        }
        return o
    }

    // -----------------------------------------------------------------------
    // Layouts
    // -----------------------------------------------------------------------

    fun parse(json: String): LayoutDef {
        val cleaned = stripCodeFence(json)
        val root = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw ParseException("Not valid JSON: ${e.message}")
        }
        return parse(root)
    }

    fun parse(root: JSONObject): LayoutDef {
        autoKeyCounter = 0
        val layersObj = root.optJSONObject("layers")
            ?: root.optJSONArray("rows")?.let { JSONObject().put(LayoutDef.BASE_LAYER, JSONObject().put("rows", it)) }
            ?: throw ParseException("Layout has no \"layers\" object and no top-level \"rows\" array.")

        val layers = LinkedHashMap<String, LayerDef>()
        layersObj.keys().forEach { layerName ->
            val layerAny = layersObj.opt(layerName)
            val layerObj = when (layerAny) {
                is JSONArray -> JSONObject().put("rows", layerAny)
                is JSONObject -> layerAny
                else -> return@forEach
            }
            layers[layerName] = parseLayer(layerName, layerObj)
        }
        if (layers.isEmpty()) throw ParseException("Layout defines no layers.")

        val id = root.optStringOrNull("id") ?: "layout_${System.currentTimeMillis()}"
        return LayoutDef(
            id = id,
            name = root.optStringOrNull("name") ?: id,
            layers = layers,
            defaultLayer = root.optStringOrNull("defaultLayer")
                ?: if (layers.containsKey(LayoutDef.BASE_LAYER)) LayoutDef.BASE_LAYER else layers.keys.first(),
            locale = root.optStringOrNull("locale"),
            background = root.optJSONObject("background")?.let { bg ->
                val image = bg.optStringOrNull("image") ?: return@let null
                BackgroundDef(
                    imageFile = image,
                    fit = enumOf(bg.optString("fit", "stretch"), BackgroundFit.STRETCH),
                    opacity = bg.optDouble("opacity", 1.0).toFloat(),
                    sensitivityFile = bg.optStringOrNull("sensitivity")
                )
            },
            author = root.optStringOrNull("author"),
            description = root.optStringOrNull("description"),
            elements = readElements(root),
            rowCountHint = root.optInt("rowCountHint", layers.values.maxOfOrNull { it.rows.size } ?: 4),
            builtIn = root.optBoolean("builtIn", false)
        )
    }

    private fun parseLayer(name: String, o: JSONObject): LayerDef {
        val rows = mutableListOf<RowDef>()
        o.optJSONArray("rows")?.let { rowsArr ->
            for (r in 0 until rowsArr.length()) {
                val rowAny = rowsArr.opt(r)
                val rowObj = when (rowAny) {
                    is JSONArray -> JSONObject().put("keys", rowAny)
                    is JSONObject -> rowAny
                    is String -> JSONObject().put("keys", JSONArray(rowAny.map { it.toString() }))
                    else -> continue
                }
                val keysArr = rowObj.optJSONArray("keys") ?: JSONArray()
                val keys = (0 until keysArr.length()).mapNotNull { k ->
                    parseKey(keysArr.opt(k), "${name}_${r}_$k")
                }
                rows += RowDef(
                    keys = keys,
                    heightWeight = rowObj.optDouble("height", 1.0).toFloat(),
                    padStart = rowObj.optDouble("padStart", 0.0).toFloat(),
                    padEnd = rowObj.optDouble("padEnd", 0.0).toFloat()
                )
            }
        }
        val free = o.optJSONArray("free")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> parseKey(arr.opt(i), "${name}_free_$i") }
        } ?: emptyList()
        return LayerDef(name = name, rows = rows, freeKeys = free)
    }

    fun write(layout: LayoutDef): JSONObject {
        val layers = JSONObject()
        layout.layers.forEach { (name, layer) ->
            val rows = JSONArray()
            layer.rows.forEach { row ->
                val keys = JSONArray()
                row.keys.forEach { keys.put(writeKey(it)) }
                rows.put(
                    JSONObject()
                        .put("height", row.heightWeight.toDouble())
                        .put("padStart", row.padStart.toDouble())
                        .put("padEnd", row.padEnd.toDouble())
                        .put("keys", keys)
                )
            }
            val layerObj = JSONObject().put("rows", rows)
            if (layer.freeKeys.isNotEmpty()) {
                layerObj.put("free", JSONArray().also { arr -> layer.freeKeys.forEach { arr.put(writeKey(it)) } })
            }
            layers.put(name, layerObj)
        }
        return JSONObject()
            .put("id", layout.id)
            .put("name", layout.name)
            .putOpt("locale", layout.locale)
            .put("defaultLayer", layout.defaultLayer)
            .putOpt("author", layout.author)
            .putOpt("description", layout.description)
            .put("rowCountHint", layout.rowCountHint)
            .putOpt("background", layout.background?.let { bg ->
                JSONObject()
                    .put("image", bg.imageFile)
                    .put("fit", bg.fit.name.lowercase())
                    .put("opacity", bg.opacity.toDouble())
                    .putOpt("sensitivity", bg.sensitivityFile)
            })
            .put("layers", layers)
            .also { out ->
                if (layout.elements.isNotEmpty()) {
                    out.put("elements", JSONArray().apply {
                        layout.elements.forEach { element ->
                            put(JSONObject().apply {
                                put("id", element.id)
                                put("layer", element.layer)
                                put("placement", element.placement.name.lowercase())
                                element.bounds?.let {
                                    put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom)))
                                }
                                put("opacity", element.opacity.toDouble())
                                put("panelOpacity", element.panelOpacity.toDouble())
                                put("reservesSpace", element.reservesSpace)
                                put("pinned", element.pinned)
                                put("draggable", element.draggable)
                                if (element.overlapsPanel) put("overlapsPanel", true)
                                element.control?.let { put("control", it.toJson()) }
                                put("visible", element.visible)
                            })
                        }
                    })
                }
            }
    }

    fun writeString(layout: LayoutDef, indent: Int = 2): String = write(layout).toString(indent)

    /** LLMs like fencing their JSON. Take it off before parsing. */
    fun stripCodeFence(raw: String): String = narrowTo(raw, '{', '}')

    /**
     * The same, for a reply that is a JSON *array*.
     *
     * Needed as its own function because [stripCodeFence] narrows to the outermost
     * braces, which is right for an object and quietly destroys an array: given
     * `[{"a":1},{"b":2}]` it returns `{"a":1},{"b":2}`, which parses as nothing. Three
     * things in this app are stored as arrays — the provider catalogue, custom themes
     * and generated panels — and all three came back empty because of it, with no
     * error anywhere, because every one of those readers catches and returns a list.
     *
     * Keeping the two apart rather than making one function guess is deliberate: a
     * model's reply can legitimately contain both shapes, and the caller is the only
     * one that knows which it asked for.
     */
    fun stripCodeFenceArray(raw: String): String = narrowTo(raw, '[', ']')

    private fun narrowTo(raw: String, open: Char, close: Char): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "")
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
            s = s.trim()
        }
        val first = s.indexOf(open)
        val last = s.lastIndexOf(close)
        return if (first >= 0 && last > first) s.substring(first, last + 1) else s.trim()
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val v = optString(name, "")
    return v.ifEmpty { null }
}

internal inline fun <reified T : Enum<T>> enumOf(raw: String?, fallback: T): T {
    if (raw.isNullOrBlank()) return fallback
    val normalised = raw.trim().replace('-', '_').replace(' ', '_')
    return enumValues<T>().firstOrNull { it.name.equals(normalised, ignoreCase = true) } ?: fallback
}

/**
 * Tabs of symbols on a key, read tolerantly.
 *
 * Accepts the full form (objects with id/label/items) and the shorthand a person or a
 * model is likely to type instead: an object of `{"label": [items]}`, where the label
 * doubles as the id. Anything unreadable is skipped rather than failing the layout,
 * on the same principle as the rest of this file — a layout with one bad group should
 * lose that group, not stop being a keyboard.
 */
private fun readPopupGroups(o: JSONObject): List<PopupGroup> {
    val out = mutableListOf<PopupGroup>()

    o.optJSONArray("popupGroups")?.let { arr ->
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val items = item.optJSONArray("items")?.let { list ->
                (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }
            }.orEmpty()
            if (items.isEmpty()) continue
            val label = item.optString("label").ifBlank { items.first() }
            out += PopupGroup(
                id = item.optString("id").ifBlank { label },
                label = label,
                items = items,
                title = item.optString("title").ifBlank { null }
            )
        }
    }

    if (out.isEmpty()) {
        o.optJSONObject("popupGroups")?.let { obj ->
            obj.keys().forEach { label ->
                val list = obj.optJSONArray(label) ?: return@forEach
                val items = (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) out += PopupGroup(id = label, label = label, items = items)
            }
        }
    }

    return out
}

/**
 * The pieces a layout is cut into, read tolerantly.
 *
 * An element that names a layer which does not exist is dropped: the alternative is
 * an invisible piece of keyboard that takes up space and cannot be pressed.
 */
private fun readElements(root: JSONObject): List<ElementDef> {
    val arr = root.optJSONArray("elements") ?: return emptyList()
    val out = mutableListOf<ElementDef>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val layer = o.optString("layer").ifBlank { LayoutDef.BASE_LAYER }
        val boundsArray = o.optJSONArray("bounds")
        val bounds = if (boundsArray != null && boundsArray.length() >= 4) {
            NormRect(
                boundsArray.optDouble(0).toFloat(),
                boundsArray.optDouble(1).toFloat(),
                boundsArray.optDouble(2).toFloat(),
                boundsArray.optDouble(3).toFloat()
            )
        } else null
        out += ElementDef(
            id = o.optString("id").ifBlank { "element_$i" },
            layer = layer,
            placement = enumOf(o.optString("placement", "docked"), ElementPlacement.DOCKED),
            bounds = bounds,
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            panelOpacity = o.optDouble("panelOpacity", 1.0).toFloat(),
            reservesSpace = o.optBoolean("reservesSpace", true),
            pinned = o.optBoolean("pinned", false),
            draggable = o.optBoolean("draggable", true),
            overlapsPanel = o.optBoolean("overlapsPanel", false),
            control = o.optJSONObject("control")?.let { ControlDef.fromJson(it) },
            visible = o.optBoolean("visible", true)
        )
    }
    return out
}
