package com.example.core.config

import com.example.core.layout.PresentationMode

/**
 * Every knob in the app, in one immutable snapshot.
 *
 * Two rules govern this file.
 *
 * First: **every default is one that works.** A user who installs the app, enables
 * the keyboard and never opens settings gets a complete keyboard. Nothing here is a
 * placeholder and nothing has to be filled in before the app is usable.
 *
 * Second: **anything that leaves the device is off by default.** AI completion,
 * remote speech recognition and diagnostics all start disabled, not because they are
 * unimportant but because turning them on is the user's decision to make, and it is
 * not reversible after the fact.
 */
data class Settings(

    // --- presentation -----------------------------------------------------
    val expertMode: Boolean = false,
    val themeId: String = "dark",
    val presentation: PresentationMode = PresentationMode.NORMAL,

    /** Keyboard height as a fraction of screen height, per orientation. */
    val heightPortrait: Float = 0.33f,
    val heightLandscape: Float = 0.50f,
    val widthFraction: Float = 1.0f,
    val bottomPaddingDp: Float = 0f,
    val sidePaddingDp: Float = 2f,
    val keyGapDp: Float = 3f,
    val keyCornerDp: Float = 8f,
    val keyTextScale: Float = 1.0f,
    val splitGapFraction: Float = 0.12f,
    val floatingX: Float = 24f,
    val floatingY: Float = 120f,
    val floatingWidthDp: Float = 320f,
    val floatingHeightDp: Float = 240f,
    val separateLandscapeSize: Boolean = true,
    val keyboardOpacity: Float = 1.0f,

    // --- layouts ----------------------------------------------------------
    /** Ordered; the layout-switch key cycles through exactly this list. */
    val enabledLayoutIds: List<String> = listOf("qwerty_pl", "symbols", "numpad", "navigation", "hacker"),
    val activeLayoutId: String = "qwerty_pl",

    // --- timing -----------------------------------------------------------
    val longPressMs: Long = 300L,
    val repeatStartMs: Long = 400L,
    val repeatIntervalMs: Long = 55L,
    val doubleTapMs: Long = 280L,
    val swipeThresholdDp: Float = 22f,

    // --- typing behaviour -------------------------------------------------
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val autoSpaceAfterPunctuation: Boolean = false,
    val smartQuotes: Boolean = false,
    val spaceSlideCursor: Boolean = true,
    val backspaceSwipeDeletesWord: Boolean = true,
    val keyPreviewPopup: Boolean = true,
    val longPressPopup: Boolean = true,
    /**
     * Honour a layout's swipe-up-for-the-printed-symbol bindings. Layouts opt in by
     * carrying them; this is how someone who keeps triggering them by accident opts out.
     */
    val flickInput: Boolean = true,
    /** Shift returns to lowercase after one character rather than staying on. */
    val shiftOneShot: Boolean = true,

    // --- feedback ---------------------------------------------------------
    val hapticEnabled: Boolean = true,
    val hapticMs: Int = 18,
    val hapticAmplitude: Int = 90,
    val soundEnabled: Boolean = false,
    val soundVolume: Float = 0.35f,

    // --- suggestions ------------------------------------------------------
    val suggestionsEnabled: Boolean = true,
    val suggestionCount: Int = 3,
    /** Silently replace what was typed. Off: suggestions are offered, never imposed. */
    val autoCorrect: Boolean = false,
    val learnFromTyping: Boolean = true,
    val personalDictionary: Boolean = true,

    // --- AI ---------------------------------------------------------------
    val aiEnabled: Boolean = false,
    val aiProvider: String = AiProviders.OPENAI_COMPATIBLE,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    /** Inline next-word / next-phrase completion in the suggestion strip. */
    val aiCompletionEnabled: Boolean = false,
    val aiCompletionMinChars: Int = 3,
    val aiCompletionDebounceMs: Long = 700L,
    val aiContextChars: Int = 600,
    val aiTemperature: Float = 0.3f,
    val aiMaxTokens: Int = 64,
    val aiCustomTasksJson: String = "",

    // --- speech -----------------------------------------------------------
    val asrEngine: String = AsrEngines.ANDROID,
    val asrRemoteUrl: String = "",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    /** Empty means "follow the system locale". */
    val asrLanguage: String = "",
    val asrShowAlternatives: Boolean = true,
    val asrAlternativeCount: Int = 5,
    /** Off: the best guess is offered, the user commits it. */
    val asrAutoCommitBest: Boolean = false,

    // --- clipboard --------------------------------------------------------
    val clipboardEnabled: Boolean = true,
    val clipboardRetentionDays: Int = 0,
    val clipboardMaxItems: Int = 200,
    val clipboardIgnorePasswordFields: Boolean = true,

    // --- indicators -------------------------------------------------------
    val indicatorsEnabled: Boolean = true,
    val indicatorStripVisible: Boolean = false,
    val indicatorOnColor: Long = 0xFF4ADE80L,
    val indicatorOffColor: Long = 0x332B2C30L,

    // --- touch model ------------------------------------------------------
    /** Probabilistic hit correction: weigh neighbouring keys instead of a hard hit test. */
    val touchModelEnabled: Boolean = false,
    /** Learn a per-key offset from the user's corrections. */
    val touchModelLearning: Boolean = false,
    val touchModelSigmaDp: Float = 16f,

    // --- privacy ----------------------------------------------------------
    val incognitoInPasswordFields: Boolean = true,
    val debugOverlay: Boolean = false,

    // --- input hardware ---------------------------------------------------
    val volumeKeysResize: Boolean = false,
    val showOnHardwareKeyboard: Boolean = false,

    // --- gestures across the whole keyboard --------------------------------
    /**
     * A long swipe that no key claimed. Each is an action in the same notation
     * layouts use, so anything a key can do, a gesture can do. Empty means nothing.
     */
    val gestureSwipeUp: String = "",
    val gestureSwipeDown: String = "{\"type\":\"hide\"}",
    val gestureSwipeLeft: String = "",
    val gestureSwipeRight: String = ""
) {
    /** Height fraction for the current orientation. */
    fun heightFor(landscape: Boolean): Float =
        if (landscape && separateLandscapeSize) heightLandscape else heightPortrait
}

object AiProviders {
    const val OPENAI_COMPATIBLE = "openai_compatible"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val NONE = "none"

    val ALL = listOf(OPENAI_COMPATIBLE, ANTHROPIC, GEMINI)

    fun label(id: String): String = when (id) {
        OPENAI_COMPATIBLE -> "OpenAI-compatible (OpenAI, Groq, OpenRouter, Ollama, LM Studio, vLLM…)"
        ANTHROPIC -> "Anthropic"
        GEMINI -> "Google Gemini"
        else -> id
    }

    /** Sensible starting point; the user can replace it. */
    fun defaultBaseUrl(id: String): String = when (id) {
        OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
        ANTHROPIC -> "https://api.anthropic.com/v1"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        else -> ""
    }

    fun defaultModel(id: String): String = when (id) {
        OPENAI_COMPATIBLE -> "gpt-4o-mini"
        ANTHROPIC -> "claude-haiku-4-5-20251001"
        GEMINI -> "gemini-2.5-flash"
        else -> ""
    }
}

object AsrEngines {
    /** The system recogniser. Free, no key, works offline on most devices. */
    const val ANDROID = "android"

    /** Any OpenAI-compatible `/audio/transcriptions` endpoint, including local Whisper. */
    const val REMOTE = "remote"

    val ALL = listOf(ANDROID, REMOTE)

    fun label(id: String): String = when (id) {
        ANDROID -> "System recogniser (no key, usually offline)"
        REMOTE -> "Whisper-compatible endpoint"
        else -> id
    }
}
