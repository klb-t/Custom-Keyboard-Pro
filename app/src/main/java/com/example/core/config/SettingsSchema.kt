package com.example.core.config

import com.example.core.layout.CursorAvoidStrategy
import com.example.core.layout.InsetsMode
import com.example.core.layout.PresentationMode
import org.json.JSONArray
import org.json.JSONObject

/** What kind of control a setting needs, and how its value is carried in JSON. */
enum class SettingKind { BOOL, INT, LONG, FLOAT, STRING, ENUM, STRING_LIST, JSON, COLOR, ACTION }

/**
 * One setting, described rather than coded.
 *
 * [key] is the same string the persisted JSON uses, which is what lets anything —
 * a generated panel, a search box, an export — address a setting without knowing
 * anything about the [Settings] class.
 */
data class SettingSpec(
    val key: String,
    val kind: SettingKind,
    val default: Any?,
    val group: String,
    val label: String,
    val help: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val options: List<String> = emptyList(),
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val expert: Boolean = false
) {
    /** Options that are only known at runtime, e.g. the list of installed layouts. */
    val liveOptions: List<String>
        get() = SettingsSchema.dynamicOptions[key]?.invoke() ?: options
}

/**
 * Every setting, as data.
 *
 * The list is **derived from the persistence codec**, not written out by hand:
 * [SettingsStore.toJson] of a default [Settings] is the authoritative list of what
 * exists and what type it has, so a field added there shows up here — and in every
 * screen built on here — without anyone remembering to register it. A hand-written
 * table supplies only what JSON cannot know: a readable label, a sensible slider
 * range, which strings are really an enum, which are secret.
 *
 * That inversion is the point. Hardcoding stops at the invariants — the set of enum
 * constants, the fact that an opacity runs 0..1 — and everything else is looked up.
 * Anything that can read this list can offer the user every setting there is,
 * including settings that did not exist when that reader was written.
 */
object SettingsSchema {

    /** Runtime-populated option lists, registered by whoever actually knows them. */
    val dynamicOptions: MutableMap<String, () -> List<String>> = mutableMapOf()

    val all: List<SettingSpec> by lazy { derive() }

    private val byKey: Map<String, SettingSpec> by lazy { all.associateBy { it.key } }

    fun spec(key: String): SettingSpec? = byKey[key]

    fun groups(): List<String> = all.map { it.group }.distinct()

    fun inGroup(group: String): List<SettingSpec> = all.filter { it.group == group }

    /**
     * Free-text search over key, label and help — the thing that makes "expose
     * everything" survivable once everything is genuinely exposed.
     */
    fun search(query: String): List<SettingSpec> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter {
            it.key.lowercase().contains(q) ||
                it.label.lowercase().contains(q) ||
                it.group.lowercase().contains(q) ||
                (it.help?.lowercase()?.contains(q) ?: false)
        }
    }

    // -----------------------------------------------------------------------
    // Generic read / write
    // -----------------------------------------------------------------------

    fun valueOf(settings: Settings, key: String): Any? =
        SettingsStore.toJson(settings).opt(key)

    /**
     * Returns [settings] with [key] set to [value], going through the same codec that
     * persists it. An unknown key, or a value the codec cannot make sense of, changes
     * nothing — a generated panel cannot corrupt the settings by guessing a name.
     */
    fun withValue(settings: Settings, key: String, value: Any?): Settings {
        if (spec(key) == null) return settings
        val json = SettingsStore.toJson(settings)
        val coerced = coerce(key, value) ?: return settings
        json.put(key, coerced)
        return try {
            SettingsStore.fromJson(json)
        } catch (e: Exception) {
            settings
        }
    }

    /** Puts a value into the shape the codec expects to read back. */
    private fun coerce(key: String, value: Any?): Any? {
        val spec = spec(key) ?: return null
        if (value == null) return null
        return when (spec.kind) {
            SettingKind.BOOL -> when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                is Number -> value.toDouble() != 0.0
                else -> null
            }
            SettingKind.INT -> (value as? Number)?.toInt() ?: value.toString().trim().toIntOrNull()
            SettingKind.LONG, SettingKind.COLOR ->
                (value as? Number)?.toLong() ?: parseLongLoosely(value.toString())
            SettingKind.FLOAT -> (value as? Number)?.toDouble() ?: value.toString().trim().toDoubleOrNull()
            SettingKind.STRING, SettingKind.JSON, SettingKind.ACTION -> value.toString()
            SettingKind.ENUM -> value.toString().let { raw ->
                val options = spec.liveOptions
                // Accept any casing, and ignore an unknown constant rather than
                // writing one the codec would silently fall back from.
                options.firstOrNull { it.equals(raw, ignoreCase = true) }
            }
            SettingKind.STRING_LIST -> when (value) {
                is JSONArray -> value
                is List<*> -> JSONArray(value.map { it.toString() })
                is String -> JSONArray(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                else -> null
            }
        }
    }

    private fun parseLongLoosely(raw: String): Long? {
        val text = raw.trim()
        return when {
            text.startsWith("0x", true) -> text.drop(2).toLongOrNull(16)
            text.startsWith("#") -> text.drop(1).toLongOrNull(16)
            else -> text.toLongOrNull()
        }
    }

    // -----------------------------------------------------------------------
    // Derivation
    // -----------------------------------------------------------------------

    private fun derive(): List<SettingSpec> {
        val defaults = SettingsStore.toJson(Settings())
        val keys = defaults.keys().asSequence().toList()
        return keys.map { key ->
            val raw = defaults.opt(key)
            val meta = METADATA[key]
            val kind = meta?.kind ?: inferKind(raw)
            SettingSpec(
                key = key,
                kind = kind,
                default = raw,
                group = meta?.group ?: groupFor(key),
                label = meta?.label ?: humanise(key),
                help = meta?.help,
                min = meta?.min,
                max = meta?.max,
                step = meta?.step,
                options = meta?.options ?: emptyList(),
                secret = meta?.secret ?: key.contains("apiKey", ignoreCase = true),
                multiline = meta?.multiline ?: false,
                expert = meta?.expert ?: false
            )
        }.sortedWith(compareBy({ GROUP_ORDER.indexOf(it.group).let { i -> if (i < 0) 99 else i } }, { it.label }))
    }

    private fun inferKind(raw: Any?): SettingKind = when (raw) {
        is Boolean -> SettingKind.BOOL
        is Int -> SettingKind.INT
        is Long -> SettingKind.LONG
        is Double, is Float -> SettingKind.FLOAT
        is JSONArray -> SettingKind.STRING_LIST
        is JSONObject -> SettingKind.JSON
        else -> SettingKind.STRING
    }

    /** "keyGapDp" -> "Key gap dp". Good enough for anything the table does not name. */
    private fun humanise(key: String): String {
        val spaced = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private fun groupFor(key: String): String = when {
        key.startsWith("ai") -> GROUP_AI
        key.startsWith("asr") -> GROUP_VOICE
        key.startsWith("clipboard") -> GROUP_CLIPBOARD
        key.startsWith("free") -> GROUP_FREE
        key.startsWith("cursorAvoid") || key == "insetsMode" || key == "avoidCoveringCursor" -> GROUP_COVERAGE
        key.startsWith("haptic") || key.startsWith("sound") -> GROUP_FEEDBACK
        key.startsWith("indicator") -> GROUP_INDICATORS
        key.startsWith("touchModel") -> GROUP_TOUCH
        key.startsWith("gesture") -> GROUP_GESTURES
        key.startsWith("key") || key.startsWith("height") || key.startsWith("panel") ||
            key.startsWith("floating") || key.contains("Opacity") || key.startsWith("theme") ||
            key.startsWith("side") || key.startsWith("bottom") || key == "presentation" ||
            key == "widthFraction" || key == "splitGapFraction" || key == "separateLandscapeSize" -> GROUP_APPEARANCE
        key.contains("Layout", ignoreCase = true) -> GROUP_LAYOUTS
        key.endsWith("Ms") || key.endsWith("Dp") -> GROUP_TIMING
        key.contains("Json") -> GROUP_DATA
        else -> GROUP_TYPING
    }

    const val GROUP_APPEARANCE = "Size, shape & theme"
    const val GROUP_FREE = "Free-floating keys"
    const val GROUP_COVERAGE = "Covering the text field"
    const val GROUP_LAYOUTS = "Layouts"
    const val GROUP_TIMING = "Timing"
    const val GROUP_TYPING = "Typing"
    const val GROUP_FEEDBACK = "Feedback"
    const val GROUP_SUGGESTIONS = "Suggestions"
    const val GROUP_AI = "AI"
    const val GROUP_VOICE = "Dictation"
    const val GROUP_CLIPBOARD = "Clipboard"
    const val GROUP_INDICATORS = "Indicators"
    const val GROUP_TOUCH = "Touch model"
    const val GROUP_GESTURES = "Whole-keyboard gestures"
    const val GROUP_PRIVACY = "Privacy & diagnostics"
    const val GROUP_DATA = "Stored data"

    private val GROUP_ORDER = listOf(
        GROUP_APPEARANCE, GROUP_FREE, GROUP_COVERAGE, GROUP_LAYOUTS, GROUP_TYPING,
        GROUP_TIMING, GROUP_SUGGESTIONS, GROUP_AI, GROUP_VOICE, GROUP_CLIPBOARD,
        GROUP_FEEDBACK, GROUP_INDICATORS, GROUP_TOUCH, GROUP_GESTURES, GROUP_PRIVACY,
        GROUP_DATA
    )

    /** Only what JSON cannot tell us. Everything absent here still gets a control. */
    private data class Meta(
        val kind: SettingKind? = null,
        val group: String? = null,
        val label: String? = null,
        val help: String? = null,
        val min: Float? = null,
        val max: Float? = null,
        val step: Float? = null,
        val options: List<String>? = null,
        val secret: Boolean? = null,
        val multiline: Boolean? = null,
        val expert: Boolean? = null
    )

    private val METADATA: Map<String, Meta> = mapOf(
        "expertMode" to Meta(
            group = GROUP_APPEARANCE, label = "Show every setting",
            help = "Off hides the settings most people never touch. Nothing is removed either way."
        ),
        "themeId" to Meta(
            kind = SettingKind.ENUM, group = GROUP_APPEARANCE, label = "Theme",
            help = "Built-in themes plus any you made yourself."
        ),
        "presentation" to Meta(
            kind = SettingKind.ENUM, group = GROUP_APPEARANCE, label = "Presentation",
            help = "Full width, one-handed, split, a floating panel, or free keys with no panel at all.",
            options = PresentationMode.entries.filter { it != PresentationMode.CYCLE }.map { it.name }
        ),
        "heightPortrait" to Meta(group = GROUP_APPEARANCE, label = "Height (portrait)", min = 0.15f, max = 0.85f),
        "heightLandscape" to Meta(group = GROUP_APPEARANCE, label = "Height (landscape)", min = 0.15f, max = 0.85f),
        "widthFraction" to Meta(group = GROUP_APPEARANCE, label = "Width", min = 0.3f, max = 1f),
        "bottomPaddingDp" to Meta(group = GROUP_APPEARANCE, label = "Bottom padding", min = 0f, max = 64f),
        "sidePaddingDp" to Meta(group = GROUP_APPEARANCE, label = "Side padding", min = 0f, max = 48f),
        "keyGapDp" to Meta(group = GROUP_APPEARANCE, label = "Gap between keys", min = 0f, max = 16f),
        "keyCornerDp" to Meta(group = GROUP_APPEARANCE, label = "Key corner radius", min = 0f, max = 32f),
        "keyTextScale" to Meta(group = GROUP_APPEARANCE, label = "Label size", min = 0.5f, max = 2f),
        "splitGapFraction" to Meta(group = GROUP_APPEARANCE, label = "Split gap", min = 0f, max = 0.4f),
        "keyboardOpacity" to Meta(
            group = GROUP_APPEARANCE, label = "Opacity — everything", min = 0.05f, max = 1f,
            help = "Fades the whole keyboard at once, keys and background together."
        ),
        "panelOpacity" to Meta(
            group = GROUP_APPEARANCE, label = "Opacity — panel behind the keys", min = 0f, max = 1f,
            help = "0 leaves the keys floating with nothing drawn behind them."
        ),
        "keyOpacity" to Meta(
            group = GROUP_APPEARANCE, label = "Opacity — key faces", min = 0f, max = 1f,
            help = "0 makes the keys themselves glass: you see through them to whatever is underneath."
        ),
        "keyLabelOpacity" to Meta(
            group = GROUP_APPEARANCE, label = "Opacity — key labels", min = 0.05f, max = 1f,
            help = "Independent of the key face, so glass keys can still have solid glyphs."
        ),
        "keyBorderWidthDp" to Meta(
            group = GROUP_APPEARANCE, label = "Key outline width", min = 0f, max = 6f,
            help = "Worth turning on once the key faces go transparent and lose their edges."
        ),
        "keyBorderOpacity" to Meta(group = GROUP_APPEARANCE, label = "Key outline opacity", min = 0f, max = 1f),
        "floatingX" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — x", min = 0f, max = 1200f, expert = true),
        "floatingY" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — y", min = 0f, max = 2400f, expert = true),
        "floatingWidthDp" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — width", min = 180f, max = 720f),
        "floatingHeightDp" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — height", min = 0f, max = 720f),

        "freeKeyScale" to Meta(group = GROUP_FREE, label = "Key size", min = 0.3f, max = 3f),
        "freeSpreadX" to Meta(group = GROUP_FREE, label = "Spread across", min = 0.2f, max = 2f),
        "freeSpreadY" to Meta(group = GROUP_FREE, label = "Spread down", min = 0.2f, max = 2f),
        "freeOriginXDp" to Meta(group = GROUP_FREE, label = "Offset from the left", min = -400f, max = 400f),
        "freeOriginYDp" to Meta(group = GROUP_FREE, label = "Offset from the top", min = -800f, max = 800f),
        "freeKeysDraggable" to Meta(group = GROUP_FREE, label = "Drag individual keys"),
        "freeShowGuides" to Meta(
            group = GROUP_FREE, label = "Show arranging guides",
            help = "Outlines the area each key claims, including the gaps that pass touches through."
        ),

        "insetsMode" to Meta(
            kind = SettingKind.ENUM, group = GROUP_COVERAGE, label = "What the app must keep clear",
            help = "FULL pushes the app above the whole keyboard. PANEL_ONLY reserves just the panel. " +
                "KEYS_ONLY reserves each key, so the gaps stay the app's. NONE reserves nothing and " +
                "lets the keyboard float over the field.",
            options = InsetsMode.entries.map { it.name }
        ),
        "avoidCoveringCursor" to Meta(
            group = GROUP_COVERAGE, label = "Get out of the cursor's way",
            help = "Watches where the text cursor actually is. Needs the app to report it; most do."
        ),
        "cursorAvoidStrategy" to Meta(
            kind = SettingKind.ENUM, group = GROUP_COVERAGE, label = "…and when it would cover it",
            options = CursorAvoidStrategy.entries.map { it.name }
        ),
        "cursorAvoidMarginDp" to Meta(group = GROUP_COVERAGE, label = "Clearance kept", min = 0f, max = 96f),
        "cursorAvoidFadeTo" to Meta(group = GROUP_COVERAGE, label = "Fade down to", min = 0.05f, max = 1f),

        "enabledLayoutIds" to Meta(kind = SettingKind.STRING_LIST, group = GROUP_LAYOUTS, label = "Layout rotation"),
        "activeLayoutId" to Meta(kind = SettingKind.ENUM, group = GROUP_LAYOUTS, label = "Active layout"),
        "rememberLayoutPerApp" to Meta(group = GROUP_LAYOUTS, label = "Remember a layout per app"),

        "longPressMs" to Meta(group = GROUP_TIMING, label = "Long press", min = 100f, max = 1000f),
        "repeatStartMs" to Meta(group = GROUP_TIMING, label = "Repeat starts after", min = 150f, max = 1500f),
        "repeatIntervalMs" to Meta(group = GROUP_TIMING, label = "Repeat interval", min = 10f, max = 300f),
        "doubleTapMs" to Meta(group = GROUP_TIMING, label = "Double tap window", min = 100f, max = 800f),
        "swipeThresholdDp" to Meta(group = GROUP_TIMING, label = "Swipe threshold", min = 4f, max = 80f),

        "autoCapitalize" to Meta(group = GROUP_TYPING, label = "Capitalise sentences"),
        "doubleSpacePeriod" to Meta(group = GROUP_TYPING, label = "Double space inserts a full stop"),
        "autoSpaceAfterPunctuation" to Meta(group = GROUP_TYPING, label = "Space after punctuation"),
        "smartQuotes" to Meta(group = GROUP_TYPING, label = "Curly quotes"),
        "spaceSlideCursor" to Meta(group = GROUP_TYPING, label = "Slide on space to move the cursor"),
        "backspaceSwipeDeletesWord" to Meta(group = GROUP_TYPING, label = "Swipe backspace deletes a word"),
        "keyPreviewPopup" to Meta(group = GROUP_TYPING, label = "Key preview popup"),
        "longPressPopup" to Meta(group = GROUP_TYPING, label = "Long-press popup"),
        "flickInput" to Meta(group = GROUP_TYPING, label = "Flick for the printed symbol"),
        "shiftOneShot" to Meta(group = GROUP_TYPING, label = "Shift applies to one character"),

        "suggestionsEnabled" to Meta(group = GROUP_SUGGESTIONS, label = "Suggestion strip"),
        "suggestionCount" to Meta(group = GROUP_SUGGESTIONS, label = "How many suggestions", min = 1f, max = 8f),
        "autoCorrect" to Meta(
            group = GROUP_SUGGESTIONS, label = "Correct silently",
            help = "Off, suggestions are offered and never imposed. Backspace always undoes a correction."
        ),
        "learnFromTyping" to Meta(group = GROUP_SUGGESTIONS, label = "Learn from what you type"),
        "personalDictionary" to Meta(group = GROUP_SUGGESTIONS, label = "Use the personal dictionary"),

        "aiEnabled" to Meta(group = GROUP_AI, label = "AI features"),
        "aiProvider" to Meta(kind = SettingKind.ENUM, group = GROUP_AI, label = "Provider", options = AiProviders.ALL),
        "aiBaseUrl" to Meta(group = GROUP_AI, label = "Base URL"),
        "aiApiKey" to Meta(group = GROUP_AI, label = "API key", secret = true),
        "aiModel" to Meta(kind = SettingKind.ENUM, group = GROUP_AI, label = "Model"),
        "aiCompletionEnabled" to Meta(group = GROUP_AI, label = "Inline completion"),
        "aiCompletionMinChars" to Meta(group = GROUP_AI, label = "Complete after", min = 0f, max = 20f),
        "aiCompletionDebounceMs" to Meta(group = GROUP_AI, label = "Completion delay", min = 100f, max = 3000f),
        "aiContextChars" to Meta(group = GROUP_AI, label = "Context sent", min = 0f, max = 4000f),
        "aiTemperature" to Meta(group = GROUP_AI, label = "Temperature", min = 0f, max = 2f),
        "aiMaxTokens" to Meta(group = GROUP_AI, label = "Max tokens", min = 8f, max = 2048f),
        "aiCustomTasksJson" to Meta(kind = SettingKind.JSON, group = GROUP_AI, label = "Custom AI tasks", multiline = true),

        "asrEngine" to Meta(kind = SettingKind.ENUM, group = GROUP_VOICE, label = "Engine", options = AsrEngines.ALL),
        "asrRemoteUrl" to Meta(group = GROUP_VOICE, label = "Endpoint"),
        "asrApiKey" to Meta(group = GROUP_VOICE, label = "API key", secret = true),
        "asrModel" to Meta(group = GROUP_VOICE, label = "Model"),
        "asrLanguage" to Meta(group = GROUP_VOICE, label = "Language", help = "Empty follows the system locale."),
        "asrShowAlternatives" to Meta(group = GROUP_VOICE, label = "Offer alternatives"),
        "asrAlternativeCount" to Meta(group = GROUP_VOICE, label = "How many alternatives", min = 1f, max = 10f),
        "asrAutoCommitBest" to Meta(group = GROUP_VOICE, label = "Commit the best guess automatically"),

        "clipboardEnabled" to Meta(group = GROUP_CLIPBOARD, label = "Keep clipboard history"),
        "clipboardRetentionDays" to Meta(group = GROUP_CLIPBOARD, label = "Keep for (days, 0 = forever)", min = 0f, max = 365f),
        "clipboardMaxItems" to Meta(group = GROUP_CLIPBOARD, label = "Maximum entries", min = 10f, max = 2000f),
        "clipboardIgnorePasswordFields" to Meta(group = GROUP_CLIPBOARD, label = "Never record from password fields"),
        "clipboardSuggestions" to Meta(group = GROUP_CLIPBOARD, label = "Offer a fresh copy as a suggestion"),
        "clipboardSuggestionSeconds" to Meta(group = GROUP_CLIPBOARD, label = "…for how long", min = 5f, max = 600f),

        "indicatorsEnabled" to Meta(group = GROUP_INDICATORS, label = "Indicator lamps on keys"),
        "indicatorStripVisible" to Meta(group = GROUP_INDICATORS, label = "Indicator strip"),
        "indicatorOnColor" to Meta(kind = SettingKind.COLOR, group = GROUP_INDICATORS, label = "Lamp colour — on"),
        "indicatorOffColor" to Meta(kind = SettingKind.COLOR, group = GROUP_INDICATORS, label = "Lamp colour — off"),

        "touchModelEnabled" to Meta(group = GROUP_TOUCH, label = "Probabilistic hit correction"),
        "touchModelLearning" to Meta(group = GROUP_TOUCH, label = "Learn a per-key offset"),
        "touchModelSigmaDp" to Meta(group = GROUP_TOUCH, label = "Spread", min = 2f, max = 48f),

        "incognitoInPasswordFields" to Meta(group = GROUP_PRIVACY, label = "Incognito in password fields"),
        "debugOverlay" to Meta(group = GROUP_PRIVACY, label = "Debug overlay", expert = true),
        "volumeKeysResize" to Meta(group = GROUP_APPEARANCE, label = "Volume keys resize the keyboard", expert = true),
        "showOnHardwareKeyboard" to Meta(group = GROUP_APPEARANCE, label = "Show with a hardware keyboard"),

        "gestureSwipeUp" to Meta(kind = SettingKind.ACTION, group = GROUP_GESTURES, label = "Swipe up"),
        "gestureSwipeDown" to Meta(kind = SettingKind.ACTION, group = GROUP_GESTURES, label = "Swipe down"),
        "gestureSwipeLeft" to Meta(kind = SettingKind.ACTION, group = GROUP_GESTURES, label = "Swipe left"),
        "gestureSwipeRight" to Meta(kind = SettingKind.ACTION, group = GROUP_GESTURES, label = "Swipe right"),

        "customThemesJson" to Meta(kind = SettingKind.JSON, group = GROUP_DATA, label = "Custom themes", multiline = true, expert = true),
        "generatedPanelsJson" to Meta(kind = SettingKind.JSON, group = GROUP_DATA, label = "Generated panels", multiline = true, expert = true),
        "customProvidersJson" to Meta(kind = SettingKind.JSON, group = GROUP_DATA, label = "Custom providers", multiline = true, expert = true),
        "discoveredModelsJson" to Meta(kind = SettingKind.JSON, group = GROUP_DATA, label = "Discovered models", multiline = true, expert = true)
    )

    /**
     * The schema as text for a model to read.
     *
     * This is what makes a generated panel possible at all: the model is told exactly
     * which keys exist, what type each is and what it currently holds, so it can
     * build a panel that drives real settings rather than inventing names.
     */
    fun describeForModel(settings: Settings, includeValues: Boolean = true): String {
        val json = SettingsStore.toJson(settings)
        return all.joinToString("\n") { spec ->
            val bits = mutableListOf("${spec.key}: ${spec.kind.name.lowercase()}")
            spec.liveOptions.takeIf { it.isNotEmpty() }?.let { bits += "one of [${it.joinToString(", ")}]" }
            if (spec.min != null || spec.max != null) bits += "range ${spec.min ?: "-"}..${spec.max ?: "-"}"
            if (includeValues && !spec.secret) bits += "now=${json.opt(spec.key)}"
            bits += "\"${spec.label}\""
            bits.joinToString(" | ")
        }
    }
}
