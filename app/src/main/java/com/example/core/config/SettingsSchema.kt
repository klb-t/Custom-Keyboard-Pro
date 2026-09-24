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

    /**
     * Metadata entries that describe a setting which does not exist.
     *
     * A mistyped key here is invisible in every other way: the setting it was meant
     * for quietly falls back to a generated label and a guessed group, which looks
     * like an oversight rather than a typo. Naming them costs one list and makes the
     * failure findable — Diagnostics shows it, and a test fails on it.
     */
    val orphanedMetadata: List<String> by lazy {
        val real = SettingsStore.toJson(Settings()).keys().asSequence().toSet()
        METADATA.keys.filterNot { it in real }.sorted()
    }

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
            val meta = METADATA[key] ?: Knobs.byKey(key)?.let { k ->
                Meta(
                    kind = SettingKind.FLOAT, group = k.group, label = k.label, help = k.help,
                    min = k.min.toFloat(), max = k.max.toFloat(), expert = true
                )
            }
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
        // Before the generic rules, or "completionDebounceMs" lands under Timing and
        // "completionMaxChars" under Typing — one feature in three places, which is
        // how a settings screen becomes unusable while every setting is present.
        key.startsWith("completion") -> GROUP_PREDICTION
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
    const val GROUP_PREDICTION = "Prediction"
    const val GROUP_VOICE = "Dictation"
    const val GROUP_CLIPBOARD = "Clipboard"
    const val GROUP_INDICATORS = "Indicators"
    const val GROUP_TOUCH = "Touch model"
    const val GROUP_GESTURES = "Whole-keyboard gestures"
    const val GROUP_PRIVACY = "Privacy & diagnostics"
    const val GROUP_DATA = "Stored data"
    const val GROUP_POCKET = "Pocket lock"
    const val GROUP_ENGINE = "Engine: inputs and wires"
    const val GROUP_SPEECH = "Reading aloud"

    private val GROUP_ORDER = listOf(
        GROUP_APPEARANCE, GROUP_FREE, GROUP_COVERAGE, GROUP_LAYOUTS, GROUP_TYPING,
        GROUP_TIMING, GROUP_SUGGESTIONS, GROUP_AI, GROUP_VOICE, GROUP_SPEECH, GROUP_CLIPBOARD,
        GROUP_FEEDBACK, GROUP_INDICATORS, GROUP_TOUCH, GROUP_GESTURES, GROUP_ENGINE, GROUP_POCKET,
        GROUP_PRIVACY, GROUP_DATA
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
            kind = SettingKind.ENUM, group = GROUP_APPEARANCE, label = "Presentation (layouts that do not say)",
            help = "Full width, one-handed, split, a floating panel, or free keys with no " +
                "panel at all. This is only the fallback: a layout that describes its own " +
                "pieces — a docked panel with a floating block beside it, say — places them " +
                "itself and ignores this.",
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
        "floatingSafeBottomDp" to Meta(
            group = GROUP_APPEARANCE, label = "Keep clear of the navigation bar", min = 0f, max = 160f,
            help = "The system bars are kept clear of already. This is extra, for what " +
                "the system does not report: a launcher's gesture strip, a case's lip."
        ),
        "floatingWidthDp" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — width", min = 180f, max = 720f),
        "floatingHeightDp" to Meta(group = GROUP_APPEARANCE, label = "Floating panel — height", min = 0f, max = 720f),

        "freeKeyScale" to Meta(group = GROUP_FREE, label = "Key size", min = 0.3f, max = 3f),
        "freeSpreadX" to Meta(group = GROUP_FREE, label = "Spread across", min = 0.2f, max = 2f),
        "freeSpreadY" to Meta(group = GROUP_FREE, label = "Spread down", min = 0.2f, max = 2f),
        "freeOriginXDp" to Meta(group = GROUP_FREE, label = "Offset from the left", min = -400f, max = 400f),
        "freeOriginYDp" to Meta(group = GROUP_FREE, label = "Offset from the top", min = -800f, max = 800f),
        "freeKeysDraggable" to Meta(group = GROUP_FREE, label = "Drag individual keys"),
        "freeArrangeMode" to Meta(
            group = GROUP_FREE, label = "Arrange keys by dragging",
            help = "While this is on a drag moves a key instead of typing with it. " +
                "One extra tap, and never a misread between the two."
        ),
        "freeKeyPinsJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_FREE, label = "Where keys were dragged to",
            multiline = true, expert = true
        ),
        "engineWiresJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_ENGINE, label = "Wires: when this happens, do that",
            multiline = true,
            help = "A list of {\"on\": …, \"do\": …, \"in\": [apps]}. On: shake, face_down, face_up, " +
                "tilt_left, tilt_right, tilt_forward, tilt_back, cover, uncover, volume_up, " +
                "volume_down, keyboard_shown, keyboard_hidden. Do: anything a key can do, e.g. " +
                "\"cursor:left\", \"do:torch toggle\", \"layout:science\". Sensors are switched on " +
                "only for the wires that need them, and only while the keyboard is open."
        ),
        "pocketMode" to Meta(
            kind = SettingKind.ENUM, group = GROUP_POCKET, label = "What the lock does to the screen",
            help = "Locks touch and keys while an app keeps running, to put a talking app in a " +
                "pocket. TOUCH leaves the screen as it is; SCREEN turns it black at the lowest " +
                "brightness. Start it from the quick settings tile, the app icon's shortcut, or " +
                "any key or wire with do:pocket_lock. Needs the accessibility service. The power " +
                "button cannot be locked by any app.",
            options = com.example.core.io.PocketMode.entries.map { it.name }
        ),
        "pocketBlockKeys" to Meta(group = GROUP_POCKET, label = "Lock the volume keys too"),
        "pocketUnlockKeys" to Meta(
            group = GROUP_POCKET, label = "Unlock with the volume keys",
            help = "In order, within three seconds: up, down, up is +, −, +. Blank turns it off."
        ),
        "pocketUnlockFingers" to Meta(
            group = GROUP_POCKET, label = "…or hold this many fingers on the screen", min = 0f, max = 4f,
            help = "0 turns the hold off. Two still fingers for two seconds is something a pocket does not do."
        ),
        "pocketUnlockHoldMs" to Meta(group = GROUP_POCKET, label = "…for this long", min = 500f, max = 6000f),
        "pocketIgnoreWhenCovered" to Meta(
            group = GROUP_POCKET, label = "Never unlock while covered",
            help = "The proximity sensor says whether something is right over the screen. " +
                "Covered means pocket, and nothing unlocks there."
        ),
        "pocketKeepAwake" to Meta(
            group = GROUP_POCKET, label = "Keep the screen awake while black",
            help = "For apps that stop talking when the screen sleeps. Off lets the phone sleep as usual."
        ),
        "pocketRestoreApp" to Meta(
            group = GROUP_POCKET, label = "Bring the app back if something gets past",
            help = "Navigation gestures from the screen edge can reach past any window. If another " +
                "app comes to the front while locked, the locked one is reopened."
        ),
        "fieldlessLayoutId" to Meta(
            kind = SettingKind.ENUM, group = GROUP_LAYOUTS, label = "Layout for the keyboard without a field",
            help = "What the quick settings tile \"Keyboard\" opens with: keys and shortcuts sent to the " +
                "app in front. Blank keeps whatever layout is current."
        ),
        "ttsEngine" to Meta(
            kind = SettingKind.ENUM, group = GROUP_SPEECH, label = "Speech engine",
            help = "Blank uses the phone's default. The list fills in once reading has been used."
        ),
        "ttsLanguage" to Meta(
            group = GROUP_SPEECH, label = "Language",
            help = "Blank follows the keyboard's current layout. Otherwise a tag such as pl, en-GB. " +
                "A voice for it must be installed in the phone's text-to-speech settings."
        ),
        "ttsVoice" to Meta(
            kind = SettingKind.ENUM, group = GROUP_SPEECH, label = "Voice",
            help = "Blank lets the language choose. Overrides the language when set."
        ),
        "ttsRate" to Meta(group = GROUP_SPEECH, label = "Speed", min = 0.25f, max = 4f),
        "ttsPitch" to Meta(group = GROUP_SPEECH, label = "Pitch", min = 0.5f, max = 2f),
        "ttsUsage" to Meta(
            kind = SettingKind.ENUM, group = GROUP_SPEECH, label = "Plays as",
            help = "MEDIA uses the media volume and pauses other audio politely; ACCESSIBILITY uses the " +
                "accessibility volume; ASSISTANT and NOTIFICATION as named.",
            options = listOf("MEDIA", "ACCESSIBILITY", "ASSISTANT", "NOTIFICATION")
        ),
        "ttsEcho" to Meta(
            kind = SettingKind.ENUM, group = GROUP_SPEECH, label = "Read back what I type",
            help = "Each character, each word or each sentence as it is finished. Never in password or " +
                "private fields.",
            options = com.example.core.speech.SpeechText.Echo.entries.map { it.name }
        ),
        "ttsProvider" to Meta(
            kind = SettingKind.ENUM, group = GROUP_SPEECH, label = "Voice from a provider",
            help = "Blank reads with the phone's own voice, on the phone. A provider (OpenAI, ElevenLabs, " +
                "or any you add that can speak) reads with its voice — the text is sent to it. Typing " +
                "echo always uses the phone's voice; private fields are never read by either."
        ),
        "ttsModel" to Meta(group = GROUP_SPEECH, label = "…its model", help = "Blank uses the provider's default."),
        "ttsCloudVoice" to Meta(
            group = GROUP_SPEECH, label = "…its voice",
            help = "Blank uses the provider's default. OpenAI: alloy, ash, coral, echo, fable, nova, onyx, " +
                "sage, shimmer… ElevenLabs: a voice id from your library."
        ),
        "ttsFollowAlong" to Meta(
            group = GROUP_SPEECH, label = "Follow along in the field",
            help = "While reading text from the field, the word being read is selected, so you can see where it is."
        ),
        "actionSuggestions" to Meta(
            group = GROUP_SUGGESTIONS, label = "Offer shortcuts in the strip",
            help = "Copy with a selection, paste in an empty field, Ctrl+C in a terminal — and then " +
                "whatever you actually use where you are, learned per app and situation."
        ),
        "actionSuggestionCount" to Meta(group = GROUP_SUGGESTIONS, label = "…how many", min = 1f, max = 8f),
        "learnActions" to Meta(
            group = GROUP_SUGGESTIONS, label = "Learn which shortcuts I use where",
            help = "Counts, kept on the phone. Never in password or private fields."
        ),
        "actionCommandPrefix" to Meta(
            group = GROUP_SUGGESTIONS, label = "Call shortcuts by name after",
            help = "Type it at the start of a word and then a name — /copy, /torch, /home — and the " +
                "strip offers it; taking it removes what you typed. Blank turns it off."
        ),
        "pocketUnlockPhrase" to Meta(
            group = GROUP_POCKET, label = "Unlock by saying",
            help = "Listened for only while locked, on the phone's own recogniser where it has one. " +
                "Works even covered — speaking is how you unlock without taking it out. Needs the " +
                "microphone permission; whether the system allows listening with the keyboard closed " +
                "depends on the phone."
        ),
        "pocketLockPhrase" to Meta(
            group = GROUP_POCKET, label = "Lock by saying",
            help = "Means listening whenever unlocked: the microphone indicator stays on and it costs " +
                "battery. Limit it to the talking apps below."
        ),
        "pocketLockPhraseApps" to Meta(
            kind = SettingKind.STRING_LIST, group = GROUP_POCKET, label = "…only in these apps",
            help = "Package names, e.g. com.google.android.apps.maps. Empty: everywhere."
        ),
        "macrosJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_LAYOUTS, label = "Recorded macros",
            multiline = true,
            help = "Each name holds the steps recorded under it, written like key actions. " +
                "Editable: a wait is {\"type\": \"do\", \"command\": \"wait 500\"}."
        ),
        "elementPosesJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_LAYOUTS, label = "Where floating pieces were moved",
            multiline = true, expert = true,
            help = "Per layout and piece: share of free space to the left, share above, and " +
                "size. Empty puts every piece back where its layout drew it."
        ),
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

        "longPressMs" to Meta(
            group = GROUP_TIMING, label = "Long press", min = 100f, max = 1000f,
            help = "How long before a key's alternates appear. Low, because on a board " +
                "where your language lives in the popup this is how letters are typed."
        ),
        "longPressBoardMs" to Meta(
            group = GROUP_TIMING, label = "…and then the full board", min = 120f, max = 2000f,
            help = "Only on keys that have both. Keep holding past the strip and the " +
                "tabbed board opens instead; sliding along the strip cancels it."
        ),
        "longPressAdaptive" to Meta(
            group = GROUP_TIMING, label = "Let the long press learn your timing",
            help = "Keeps it clear of how long you hold ordinary taps; a little longer when an " +
                "alternate is deleted at once, a little shorter when you delete a tap and " +
                "long-press the same key. The number above moves as it learns."
        ),
        "repeatStartMs" to Meta(group = GROUP_TIMING, label = "Repeat starts after", min = 150f, max = 1500f),
        "repeatIntervalMs" to Meta(group = GROUP_TIMING, label = "Repeat interval", min = 10f, max = 300f),
        "doubleTapMs" to Meta(group = GROUP_TIMING, label = "Double tap window", min = 100f, max = 800f),
        "swipeThresholdDp" to Meta(group = GROUP_TIMING, label = "Swipe threshold", min = 4f, max = 80f),

        "autoCapitalize" to Meta(group = GROUP_TYPING, label = "Capitalise sentences"),
        "capitalisationRulesJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_TYPING, label = "Capitalisation rules",
            multiline = true,
            help = "One rule per line of policy: \"on\" is the situation " +
                "(field_start, sentence_end, line_start, list_item, after_colon, " +
                "word_start, proper_noun), \"does\" is what happens (shift, fix_after_word, " +
                "nothing), \"at\" is when it is asked (opening, typing). A rule " +
                "that says nothing is a veto. Blank uses the built-in set; an empty " +
                "list means no rules at all."
        ),
        "properNouns" to Meta(
            kind = SettingKind.STRING_LIST, group = GROUP_TYPING, label = "Names, as they are spelled",
            help = "Written this way from the first time you type them in lower case — " +
                "Kowalski, iPhone, Łódź. Others are learned from what you capitalise mid-sentence."
        ),
        "learnProperNouns" to Meta(
            group = GROUP_TYPING, label = "Learn names from what I capitalise",
            help = "A word you keep writing with a capital in the middle of sentences is " +
                "fixed the same way when you type it in lower case. Only with capitalisation " +
                "on, and never in password or private fields."
        ),
        "cursorMagnet" to Meta(
            kind = SettingKind.ENUM, group = GROUP_TYPING, label = "Arrows find the typo",
            help = "Tapping the arrows back towards a word the dictionary does not know: " +
                "OFFER shows the jump in the strip, ON_PAUSE finishes the trip when you stop " +
                "near it, STRIDE goes straight there on the first press.",
            options = com.example.core.text.CursorMagnet.Pull.entries.map { it.name }
        ),
        "cursorMagnetReach" to Meta(
            group = GROUP_TYPING, label = "…when you stop this close (characters)", min = 1f, max = 12f
        ),
        "cursorMagnetPauseMs" to Meta(
            group = GROUP_TYPING, label = "…after a pause of", min = 120f, max = 2000f, expert = true
        ),
        "cursorMagnetStride" to Meta(
            group = GROUP_TYPING, label = "…and STRIDE jumps at most (characters)", min = 5f, max = 400f,
            expert = true
        ),
        "doubleSpacePeriod" to Meta(group = GROUP_TYPING, label = "Double space inserts a full stop"),
        "autoSpaceAfterPunctuation" to Meta(group = GROUP_TYPING, label = "Space after punctuation"),
        "smartQuotes" to Meta(group = GROUP_TYPING, label = "Curly quotes"),
        "spaceSlideCursor" to Meta(group = GROUP_TYPING, label = "Slide on space to move the cursor"),
        "backspaceSwipeDeletesWord" to Meta(group = GROUP_TYPING, label = "Swipe backspace deletes a word"),
        "keyPreviewPopup" to Meta(group = GROUP_TYPING, label = "Key preview popup"),
        "longPressPopup" to Meta(group = GROUP_TYPING, label = "Long-press popup"),
        "symbolBoardColumns" to Meta(
            group = GROUP_TYPING, label = "Symbols per row on the long-press board",
            min = 4f, max = 16f,
            help = "Keys carrying a whole board of symbols rather than a single row."
        ),
        "popupTabMemoryJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_TYPING, label = "Remembered board tabs",
            multiline = true, expert = true
        ),
        "popupPinnedTabsJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_TYPING, label = "Tabs pinned open",
            multiline = true, expert = true,
            help = "Shown alongside whichever tab is selected — maths and Polish at once."
        ),
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
        "wordListSourcesJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_SUGGESTIONS, label = "Word list sources",
            multiline = true,
            help = "Addresses a word list can be downloaded from, added to the ones " +
                "bundled. One word per line, most common first; a second column is " +
                "ignored, so a published frequency list works as it is. A leading '!' " +
                "means known but never suggested."
        ),

        "aiEnabled" to Meta(group = GROUP_AI, label = "AI features"),
        "aiProvider" to Meta(kind = SettingKind.ENUM, group = GROUP_AI, label = "Provider", options = AiProviders.ALL),
        "aiBaseUrl" to Meta(group = GROUP_AI, label = "Base URL"),
        "aiApiKey" to Meta(
            group = GROUP_AI, label = "API key", secret = true,
            help = "Kept so a setup made before per-provider profiles keeps working. " +
                "It answers for the selected provider only. New keys go in the " +
                "provider's own profile."
        ),
        "providerProfilesJson" to Meta(
            kind = SettingKind.JSON, group = GROUP_AI, label = "Provider profiles",
            multiline = true, secret = true,
            help = "Key, base URL, model and parameters for each provider you have set " +
                "up, keyed by provider id. Contains your API keys: anything you export " +
                "from here carries them with it."
        ),
        "aiModel" to Meta(kind = SettingKind.ENUM, group = GROUP_AI, label = "Model"),
        "aiCompletionEnabled" to Meta(group = GROUP_AI, label = "Inline completion"),
        "aiCompletionMinChars" to Meta(group = GROUP_AI, label = "Complete after", min = 0f, max = 20f),
        "aiCompletionDebounceMs" to Meta(group = GROUP_AI, label = "Completion delay", min = 100f, max = 3000f),
        "aiContextChars" to Meta(group = GROUP_AI, label = "Context sent", min = 0f, max = 4000f),
        "aiTemperature" to Meta(group = GROUP_AI, label = "Temperature", min = 0f, max = 2f),
        "aiMaxTokens" to Meta(group = GROUP_AI, label = "Max tokens", min = 8f, max = 2048f),
        "aiCustomTasksJson" to Meta(kind = SettingKind.JSON, group = GROUP_AI, label = "Custom AI tasks", multiline = true),

        // The prediction lane. Its own group rather than a corner of the AI one,
        // because it is the only feature here that sends text somewhere on its own,
        // without anyone pressing anything — which is a thing to be able to find.
        "completionEnabled" to Meta(
            group = GROUP_PREDICTION, label = "Predict what comes next",
            help = "Off by default. When on, the keyboard asks a model to continue " +
                "what you are writing shortly after you stop typing, and offers the " +
                "result on a second row. That costs a request per pause and sends " +
                "the text being written. Never in a password field."
        ),
        "completionProvider" to Meta(
            kind = SettingKind.ENUM, group = GROUP_PREDICTION, label = "Provider",
            help = "Blank uses the AI provider. Only providers that can complete text " +
                "are offered — a chat endpoint answers a continuation with a reply to it."
        ),
        "completionModel" to Meta(kind = SettingKind.ENUM, group = GROUP_PREDICTION, label = "Model"),
        "completionApiKey" to Meta(group = GROUP_PREDICTION, label = "API key", secret = true),
        "completionMinChars" to Meta(
            group = GROUP_PREDICTION, label = "Start predicting after", min = 0f, max = 80f,
            help = "Characters written before it is worth asking anything."
        ),
        "completionDebounceMs" to Meta(
            group = GROUP_PREDICTION, label = "Wait before asking", min = 100f, max = 5000f,
            help = "Every keystroke sending a request would pay for text you are still " +
                "changing. This is how long the typing has to stop first."
        ),
        "completionReserveRow" to Meta(
            group = GROUP_PREDICTION, label = "Keep the row's space when empty",
            help = "On, the keys never move. Off gives the space back and the board " +
                "shifts by one row whenever a prediction arrives or goes."
        ),
        "completionMaxTokens" to Meta(
            group = GROUP_PREDICTION, label = "Max tokens", min = 8f, max = 1024f, expert = true
        ),
        "completionTemperature" to Meta(
            group = GROUP_PREDICTION, label = "Temperature", min = 0f, max = 2f, expert = true
        ),
        "completionTokenFloor" to Meta(
            group = GROUP_PREDICTION, label = "Stop below this log-probability",
            min = -12f, max = 0f, expert = true,
            help = "0 disables it. A single uncertain token — a name, a number — cuts " +
                "an otherwise good continuation in half here, which is why the budget " +
                "below is usually the better one to set."
        ),
        "completionSurpriseBudget" to Meta(
            group = GROUP_PREDICTION, label = "Total surprise allowed", min = 0f, max = 60f, expert = true,
            help = "Stops when the generation as a whole has drifted rather than when " +
                "one token was odd. 0 disables it."
        ),
        "completionMaxChars" to Meta(
            group = GROUP_PREDICTION, label = "Longest prediction", min = 20f, max = 4000f, expert = true,
            help = "The hard stop. Every other condition can fail to fire."
        ),
        "completionMaxMillis" to Meta(
            group = GROUP_PREDICTION, label = "Stop after", min = 300f, max = 20000f, expert = true,
            help = "On a phone, having been kept waiting is itself a reason to stop."
        ),
        "completionAcceptedScope" to Meta(
            group = GROUP_PREDICTION, label = "Last length accepted", expert = true,
            help = "Written by the keyboard, not for you to set: which depth of " +
                "prediction you last took. Shown because nothing here is hidden."
        ),

        "asrEngine" to Meta(
            kind = SettingKind.ENUM, group = GROUP_VOICE, label = "Where dictation goes",
            help = "This phone needs nothing and works offline. A provider from the " +
                "catalogue is usually more accurate and handles more languages. An " +
                "endpoint is a URL you type yourself, including your own Whisper server.",
            options = AsrEngines.ALL
        ),
        "pasteConvert" to Meta(
            group = GROUP_CLIPBOARD, label = "Read a pasted picture or recording",
            help = "Paste a photographed document and get its text; paste a voice note " +
                "and get the transcript. Off by default because it sends the file to " +
                "the provider that reads it, and a photographed document is a payslip " +
                "or a contract. There is no on-device reader to use instead."
        ),
        "ocrProvider" to Meta(
            kind = SettingKind.ENUM, group = GROUP_CLIPBOARD, label = "Who reads pictures",
            help = "Blank uses the first provider you have set up that can."
        ),
        "ocrModel" to Meta(group = GROUP_CLIPBOARD, label = "Model for reading pictures"),

        "asrProvider" to Meta(
            kind = SettingKind.ENUM, group = GROUP_VOICE, label = "Dictation provider",
            help = "Everything in the catalogue that can take dictation, including " +
                "this phone's own recogniser."
        ),
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
        "clipboardKeepFiles" to Meta(
            group = GROUP_CLIPBOARD, label = "Keep pictures and files, not just their names",
            help = "A copied picture's link stops working almost immediately — the " +
                "permission that came with it belongs to the clip, not to you. Keeping " +
                "the bytes is the only way the entry still works later. Off stores " +
                "nothing but the name."
        ),
        "clipboardMaxFileMb" to Meta(
            group = GROUP_CLIPBOARD, label = "Largest file to keep (MB)", min = 0f, max = 500f,
            help = "Anything bigger is noted by name only. 0 means no limit."
        ),
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
