package com.example.core.config

import com.example.core.layout.CursorAvoidStrategy
import com.example.core.layout.InsetsMode
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
    /**
     * Extra bottom strip kept clear of the floating panel, on top of the system bars.
     *
     * The bars themselves are now asked for and kept clear of exactly, whichever kind
     * the phone has, so this defaults to nothing. It stays for the cases the system
     * does not report: a launcher's own gesture area, a case with a lip, a thumb that
     * cannot reach the bottom edge.
     */
    val floatingSafeBottomDp: Float = 0f,
    val separateLandscapeSize: Boolean = true,

    /**
     * Three opacities, not one, because they are three different questions.
     *
     * [keyboardOpacity] fades the whole thing at once. [panelOpacity] fades only what
     * is drawn *behind* the keys, so the keys stay solid over whatever is underneath.
     * [keyOpacity] fades only the key faces, so the panel stays and the keys become
     * glass. A floating panel of solid keys on an invisible background is
     * panelOpacity=0, keyOpacity=1 — which is the "floating transparent keys" idea,
     * and it is reachable without leaving the normal presentation at all.
     */
    val keyboardOpacity: Float = 1.0f,
    val panelOpacity: Float = 1.0f,
    val keyOpacity: Float = 1.0f,

    /** Outline drawn around each key. Useful once the key faces go transparent. */
    val keyBorderWidthDp: Float = 0f,
    val keyBorderOpacity: Float = 0.6f,
    /** Fades only the glyphs, independently of the key face they sit on. */
    val keyLabelOpacity: Float = 1.0f,

    // --- free-floating keys (PresentationMode.FREE) -----------------------
    /**
     * Keys with no panel behind them at all, placed individually over the app.
     *
     * A layout can pin any key anywhere with [com.example.core.layout.KeyDef.bounds];
     * for a layout that does not, these spread the ordinary row grid out over the
     * screen so an existing layout can be thrown into free mode and still work.
     */
    val freeKeyScale: Float = 1.0f,
    val freeSpreadX: Float = 1.0f,
    val freeSpreadY: Float = 1.0f,
    val freeOriginXDp: Float = 0f,
    val freeOriginYDp: Float = 0f,
    /** Drag an individual key to move it, and remember where it was put. */
    val freeKeysDraggable: Boolean = true,
    /** Faint outlines showing where the untouchable gaps are, while arranging. */
    val freeShowGuides: Boolean = false,
    /**
     * While on, a drag moves a key instead of typing with it.
     *
     * A mode rather than a gesture, on purpose: sharing one gesture between "type
     * this" and "move this" means every misread drag either loses a keystroke or
     * moves a key you did not want moved, and there is no way to tell which the user
     * meant. A mode is one extra tap and never wrong.
     */
    val freeArrangeMode: Boolean = false,
    /** Where the user dragged keys to: {"keyId": [left, top, right, bottom]}. */
    val freeKeyPinsJson: String = "",
    /**
     * Where floating pieces of layouts were moved and how far they were resized:
     * {"layoutId/elementId": [shareLeft, shareAbove, scale]}. Kept apart from the
     * layout, so moving a piece never edits a built-in layout, and as shares of free
     * space rather than coordinates, so the move survives turning the phone.
     */
    val elementPosesJson: String = "",

    /**
     * The engine's wiring: when something happens, do something. A JSON list of
     * {"on": input, "do": action, "in": [packages]}, where the input is one of
     * [com.example.core.engine.Inputs] (shake, face_down, tilt_left, cover,
     * volume_down, keyboard_shown…) and the action anything a key can do — including
     * "do:<verb line>". Empty wires nothing and switches no sensor on.
     */
    val engineWiresJson: String = "",

    /**
     * Recorded macros: {"name": [actions…]}, each action written the way layouts write
     * them. Recorded with the "record" action, played with "play" — from a key, a wire
     * or the Actions panel.
     */
    val macrosJson: String = "",

    // --- pocket lock: touch, screen and keys locked while an app keeps running ----
    /** TOUCH keeps the screen as it is; SCREEN turns it black at the lowest brightness. */
    val pocketMode: com.example.core.io.PocketMode = com.example.core.io.PocketMode.SCREEN,
    /** Volume keys (and any others the system passes on) are taken while locked. */
    val pocketBlockKeys: Boolean = true,
    /** The key sequence that unlocks: "up, down, up" is volume +, −, +. Blank: none. */
    val pocketUnlockKeys: String = "up, down, up",
    /** Fingers held on the screen to unlock; 0 turns the hold off. */
    val pocketUnlockFingers: Int = 2,
    val pocketUnlockHoldMs: Long = 2000L,
    /** Refuse to unlock while the proximity sensor is covered — in a pocket. */
    val pocketIgnoreWhenCovered: Boolean = true,
    /** Keep the screen awake while black, for apps that stop talking when it sleeps. */
    val pocketKeepAwake: Boolean = true,
    /** Bring the locked app back if something else reaches the front. */
    val pocketRestoreApp: Boolean = true,

    // --- what the keyboard asks the app to keep clear ----------------------
    val insetsMode: InsetsMode = InsetsMode.FULL,
    /**
     * Watch where the text cursor actually is and get out of its way.
     *
     * Off by default: it needs the app to report the cursor position
     * (`requestCursorUpdates`), most do, some do not, and a keyboard that
     * occasionally jumps for no visible reason is worse than one that never jumps.
     */
    val avoidCoveringCursor: Boolean = false,
    val cursorAvoidMarginDp: Float = 12f,
    val cursorAvoidStrategy: CursorAvoidStrategy = CursorAvoidStrategy.MOVE_PANEL,
    /** How far the keyboard fades when [cursorAvoidStrategy] is FADE. */
    val cursorAvoidFadeTo: Float = 0.25f,

    // --- layouts ----------------------------------------------------------
    /** Ordered; the layout-switch key cycles through exactly this list. */
    val enabledLayoutIds: List<String> = listOf("qwerty_pl", "science", "symbols", "numpad", "navigation", "hacker"),
    val activeLayoutId: String = "qwerty_pl",
    /**
     * Remember which layout was last used in each app and return to it.
     *
     * Off by default because a keyboard that changes shape when you change app is
     * startling if you did not ask for it — and excellent if you did.
     */
    val rememberLayoutPerApp: Boolean = false,

    // --- timing -----------------------------------------------------------
    /**
     * How long a key must be held before its alternates appear.
     *
     * 130 ms rather than the 300 that phone keyboards conventionally use, because on a
     * board where the language's own letters live in the popup, a long press is not an
     * occasional flourish — it is how "ę" is typed, fifty times a message. At 300 ms
     * that is a third of a second of waiting per Polish letter.
     *
     * The floor is what it costs to tell a hold from a tap: below about 100 ms an
     * ordinary press starts opening popups by itself.
     */
    val longPressMs: Long = 130L,

    /**
     * How much longer a key with both a strip and a board must be held for the board.
     *
     * Only keys that have both: the scientific layout's letters carry their accents in
     * the first tab of a board, so once a language promotes its own letters into the
     * quick strip, the two have to share one gesture. Short hold gives the letters,
     * keeping hold gives everything — and sliding along the strip cancels the upgrade,
     * because that is somebody already choosing.
     */
    val longPressBoardMs: Long = 320L,
    /**
     * Let [longPressMs] follow the hand: kept clear of how long plain taps are held,
     * nudged up when an alternate is deleted straight away, down when a tap is deleted
     * and the same key long-pressed at once. Off by default, because a number the
     * user chose should not move without being asked to.
     */
    val longPressAdaptive: Boolean = false,
    val repeatStartMs: Long = 400L,
    val repeatIntervalMs: Long = 55L,
    val doubleTapMs: Long = 280L,
    val swipeThresholdDp: Float = 22f,

    // --- typing behaviour -------------------------------------------------
    /**
     * Whether the keyboard ever raises Shift by itself.
     *
     * Off by default. A keyboard guessing at capitals is wrong often enough to be
     * noticed and quiet enough not to be, and the two errors are not the same size: a
     * missing capital is one tap, an unwanted one is a word to delete and retype that
     * is easy not to see until it has been sent. Whoever wants it can turn it on and
     * shape it through [capitalisationRulesJson]; nobody has to discover it was on.
     */
    val autoCapitalize: Boolean = false,
    /**
     * How capitalisation actually behaves, as a list of rules.
     *
     * [autoCapitalize] stays as the master switch, because "off" should be one tap.
     * Everything below it is data: which situations imply a capital (an empty field,
     * the end of a sentence, a line break, a list bullet, a colon, or simply the start
     * of any word), what the keyboard does about each (press shift, or leave the
     * typing alone and fix the word once it ends, or deliberately nothing), and at
     * which moment each rule applies — opening a field is a different question from
     * finishing a word in one.
     *
     * Blank means [com.example.core.text.Capitalisation.DEFAULT]. An explicit empty
     * list means no rules, which is not the same thing and is honoured.
     */
    val capitalisationRulesJson: String = "",
    /**
     * Names written the way they are spelled from the first time: "Kowalski",
     * "iPhone", "Łódź". The rest are learned from which words the user capitalises in
     * the middle of sentences; this list is for the ones they want right at once.
     */
    val properNouns: List<String> = emptyList(),
    /** Whether names are learned from typing at all. */
    val learnProperNouns: Boolean = true,

    /**
     * How hard the arrow keys are pulled towards a word the dictionary does not know.
     *
     * The case: a typo noticed after the word is finished, the left arrow tapped to get
     * back to it, a few presses off. With exactly one unknown word near where the
     * presses stopped, heading towards it, that word is where they were going — and
     * its nearest real word says where in it the slip is. OFF, OFFER (a tap in the
     * strip), ON_PAUSE (finishes the trip when the presses stop) or STRIDE (the first
     * press goes straight there). Default: ON_PAUSE, the gentlest one that saves taps.
     */
    val cursorMagnet: com.example.core.text.CursorMagnet.Pull = com.example.core.text.CursorMagnet.Pull.ON_PAUSE,
    /** How close, in characters, the presses must stop for the pull to apply. */
    val cursorMagnetReach: Int = 3,
    /** How long the presses must stop before ON_PAUSE and OFFER act. */
    val cursorMagnetPauseMs: Long = 380L,
    /** How far STRIDE will jump in one press. */
    val cursorMagnetStride: Int = 40,

    /**
     * Per-provider setup: key, base URL, model and parameters, keyed by provider id.
     *
     * A key belongs to a provider, not to the app and not to a feature. Keeping one
     * key for everything meant that anybody with accounts at more than one provider —
     * which is anybody comparing them — retyped it on every switch, and the times they
     * forgot produced a 401 that is indistinguishable from a bad key.
     *
     * Stored in app-private storage like the rest of the settings, which keeps it from
     * other apps and is not a hardware-backed keystore. An exported settings file
     * contains these keys.
     */
    val providerProfilesJson: String = "",

    /**
     * Extra places a word list can be fetched from, beyond the ones bundled.
     *
     * Data for the same reason the provider catalogue is: which list is *good* is a
     * judgement about a language, a register and a person. Somebody writing legal
     * drafts, somebody writing Kotlin and somebody texting want three different lists
     * and none of them is wrong.
     */
    val wordListSourcesJson: String = "",
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

    // --- the long-press symbol board --------------------------------------
    /** How many symbols per row on a tabbed long-press board. */
    val symbolBoardColumns: Int = 10,
    /** Which tab each key's board was left on: {"keyId": "groupId"}. */
    val popupTabMemoryJson: String = "",
    /** Tabs shown alongside the selected one, whatever key opened the board. */
    val popupPinnedTabsJson: String = "",

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
    /**
     * The app-wide key, kept only so a setup made before profiles existed keeps working.
     *
     * New keys go into [providerProfilesJson] against the provider they belong to. This
     * one answers for the selected provider and for no other, because a key that
     * answers for whichever provider happens to be selected is how a request goes out
     * to one company carrying another's credential.
     */
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

    // --- finishing sentences (the prediction lane) ------------------------
    //
    // Off by default. It costs money per keystroke-pause and sends what is being
    // written to whoever is configured; neither is a thing to switch on for somebody.
    val completionEnabled: Boolean = false,
    /** Empty follows the main AI provider. */
    val completionProvider: String = "",
    val completionModel: String = "",
    val completionApiKey: String = "",
    /** Nothing is asked for until this much has been typed. */
    val completionMinChars: Int = 12,
    /** Time after the last keystroke before asking. Not politeness — money. */
    val completionDebounceMs: Long = 500L,
    val completionMaxTokens: Int = 96,
    val completionTemperature: Float = 0.3f,
    /** Cut when one token drops below this log-probability. 0 disables it. */
    val completionTokenFloor: Float = 0f,
    /** Cut when the generation as a whole has drifted. Better than a floor alone. */
    val completionSurpriseBudget: Float = 12f,
    val completionMaxChars: Int = 400,
    /** On a phone, waiting is a reason to stop. */
    val completionMaxMillis: Long = 3500L,
    /** Which depth the user last took, so the strip can lead with it. */
    val completionAcceptedScope: String = "",
    /**
     * Keep the prediction row's space even while it is empty.
     *
     * On by default, and the reason is the keys rather than the row. A row that
     * appears when a continuation arrives and vanishes when the next keystroke
     * cancels it moves every key on the board by its own height, twice, between one
     * letter and the next — under a finger already on its way down. Reserving the
     * space costs the height of one strip and makes the board stop moving.
     *
     * Off gives the space back to anyone who would rather have it and does not mind
     * the jump.
     */
    val completionReserveRow: Boolean = true,
    /**
     * Where to fetch an updated provider catalogue from. Empty by default, and
     * deliberately so: a default would point every install at an address of the
     * author's choosing and make a keyboard that works offline quietly phone home.
     */
    val catalogUrl: String = "",
    /** Providers fetched from [catalogUrl]. Kept apart from the user's own. */
    val fetchedProvidersJson: String = "",
    val catalogFetchedAt: Long = 0L,
    /** False until the setup wizard has been through once. */
    val setupDone: Boolean = false,

    // --- speech -----------------------------------------------------------
    val asrEngine: String = AsrEngines.ANDROID,
    /**
     * Which catalogue provider takes dictation, when [asrEngine] is
     * [AsrEngines.PROVIDER]. Empty falls back to the hand-typed URL, so settings
     * written before the catalogue could take dictation keep working unchanged.
     */
    val asrProvider: String = "",
    val asrRemoteUrl: String = "",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    /** Empty means "follow the system locale". */
    val asrLanguage: String = "",

    /**
     * Turn a pasted picture or recording into its text.
     *
     * Off by default, and not because it is unfinished. The rule here is not "nothing
     * useful by default", it is *nothing leaves the device unasked* — and reading a
     * photographed document means sending that photograph to a provider. A
     * photographed document is a payslip, a prescription, a contract. Turning this on
     * is one tap; a keyboard that had uploaded one already is not something anybody
     * can take back.
     *
     * There is no on-device converter to offer instead: Android has no
     * text-recognition API, and its speech recogniser listens to a microphone rather
     * than reading a file. Both would mean shipping a model.
     */
    val pasteConvert: Boolean = false,

    /**
     * Who reads a pasted picture. Blank means the first configured provider that can.
     *
     * Blank rather than a name, so the feature appears the moment somebody sets up a
     * provider for something else instead of being a second setup waiting to be found.
     */
    val ocrProvider: String = "",
    val ocrModel: String = "",
    val asrShowAlternatives: Boolean = true,
    val asrAlternativeCount: Int = 5,
    /** Off: the best guess is offered, the user commits it. */
    val asrAutoCommitBest: Boolean = false,

    // --- clipboard --------------------------------------------------------
    val clipboardEnabled: Boolean = true,
    val clipboardRetentionDays: Int = 0,
    val clipboardMaxItems: Int = 200,
    val clipboardIgnorePasswordFields: Boolean = true,
    /**
     * Keep a copy of pictures and files, not just their names.
     *
     * On, because the alternative is a history full of entries that cannot be opened:
     * a clipboard URI's read permission lapses with the clip, so the name alone is
     * worth nothing by the time anybody wants it back. Off keeps the names and stores
     * no bytes, for anyone who would rather the app held nothing.
     */
    val clipboardKeepFiles: Boolean = true,
    /** Anything larger than this is noted by name and not copied. 0 means no limit. */
    val clipboardMaxFileMb: Int = 25,
    /** Offer something just copied as a suggestion while the field is still empty. */
    val clipboardSuggestions: Boolean = true,
    /** How long after copying it stays on offer. */
    val clipboardSuggestionSeconds: Int = 90,

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
    val gestureSwipeRight: String = "",

    // --- things the app itself does not interpret, only stores ------------
    /**
     * The four fields below hold data the user or a model authored, in the same
     * notation the app already reads elsewhere. They are settings only in the sense
     * that they persist with the rest; what gives them meaning is the code that
     * already parses themes, panels and provider descriptions.
     *
     * This is the point of keeping decisions as data: a new theme, a new settings
     * panel or a new model provider is a string in here, not a new Kotlin file.
     */
    val customThemesJson: String = "",
    /** Settings panels generated on request. See com.example.core.panels.PanelSpec. */
    val generatedPanelsJson: String = "",
    /** Providers the user added, merged over the bundled catalogue. */
    val customProvidersJson: String = "",
    /** Model ids last discovered from a provider, so the picker is not empty offline. */
    val discoveredModelsJson: String = ""
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

    /** Whatever the provider catalogue says can take dictation. */
    const val PROVIDER = "provider"

    val ALL = listOf(ANDROID, PROVIDER, REMOTE)

    fun label(id: String): String = when (id) {
        ANDROID -> "This phone (no key, usually offline)"
        PROVIDER -> "A provider from the catalogue"
        REMOTE -> "A URL I type myself"
        else -> id
    }
}
