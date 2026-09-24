package com.example.core.config

/**
 * One number the code would otherwise have hardcoded.
 *
 * The rule this exists to enforce: anything a user could want to be different is a
 * setting, and "a threshold somebody picked while writing the feature" is exactly
 * that. A knob is declared once, next to its siblings, with its range and what it
 * does; the settings screen, search and persistence all pick it up from here, so
 * exposing a constant costs one line instead of four edits in three files.
 *
 * Knobs are expert settings. The default is what the feature was designed and tested
 * with; changing one is a deliberate act.
 */
data class Knob(
    val id: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val label: String,
    val help: String,
    val group: String
) {
    /** The settings key it is stored and shown under. */
    val key: String get() = Knobs.PREFIX + id
}

object Knobs {
    const val PREFIX = "tune."

    private val all = mutableListOf<Knob>()

    private fun k(id: String, default: Double, min: Double, max: Double, group: String, label: String, help: String) =
        Knob(id, default, min, max, label, help, group).also { all += it }

    private const val ENGINE = SettingsSchema.GROUP_ENGINE
    private const val POCKET = SettingsSchema.GROUP_POCKET
    private const val TIMING = SettingsSchema.GROUP_TIMING
    private const val TYPING = SettingsSchema.GROUP_TYPING
    private const val TOUCH = SettingsSchema.GROUP_TOUCH

    // --- motion and proximity gestures ---------------------------------------
    val SHAKE_FORCE = k("shakeForce", 13.0, 10.0, 40.0, ENGINE, "Shake: how hard (m/s²)",
        "Acceleration a jolt must reach to count towards a shake. Higher ignores bumpier bus rides.")
    val SHAKE_JOLTS = k("shakeJolts", 3.0, 2.0, 8.0, ENGINE, "Shake: jolts needed",
        "How many strong jolts make one shake.")
    val SHAKE_WINDOW_MS = k("shakeWindowMs", 700.0, 300.0, 3000.0, ENGINE, "Shake: within (ms)",
        "The jolts must all happen within this time.")
    val TILT_DEGREES = k("tiltDegrees", 35.0, 10.0, 80.0, ENGINE, "Tilt: angle (°)",
        "How far the phone must be tilted to count.")
    val TILT_HOLD_MS = k("tiltHoldMs", 350.0, 50.0, 3000.0, ENGINE, "Tilt: held for (ms)",
        "How long the tilt must be held; face down waits twice this.")
    val GESTURE_REFRACTORY_MS = k("gestureRefractoryMs", 1200.0, 0.0, 10000.0, ENGINE, "Gestures: quiet time after one (ms)",
        "After a gesture fires, the same gesture cannot fire again for this long.")
    val PROXIMITY_NEAR_CM = k("proximityNearCm", 3.0, 0.5, 10.0, ENGINE, "Proximity: covered below (cm)",
        "Closer than this counts as covered. Many sensors only report near and far.")
    val POCKET_DETECT_MS = k("pocketDetectMs", 1500.0, 200.0, 10000.0, ENGINE, "In pocket after (ms)",
        "Covered this long, and the phone counts as in a pocket (the in_pocket input).")

    // --- side keys ----------------------------------------------------------
    val VOLUME_DOUBLE_MS = k("volumeDoubleMs", 350.0, 150.0, 1000.0, ENGINE, "Volume key: double press within (ms)",
        "Only used when a double press is wired; a single press is then delayed by this much.")
    val VOLUME_LONG_MS = k("volumeLongMs", 600.0, 250.0, 3000.0, ENGINE, "Volume key: long press after (ms)",
        "Held this long, it is a long press rather than a volume step.")

    // --- voice phrases ------------------------------------------------------
    val VOICE_MATCH = k("voiceMatch", 0.8, 0.5, 1.0, ENGINE, "Voice phrase: how exact",
        "1 means word for word; lower forgives a misheard word or two.")
    val VOICE_RETRY_MS = k("voiceRetryMs", 800.0, 100.0, 30000.0, ENGINE, "Voice phrase: pause between listens (ms)",
        "After each listen ends. Grows after errors, up to thirty times this.")

    // --- pocket lock ----------------------------------------------------------
    val POCKET_SEQUENCE_MS = k("pocketSequenceMs", 3000.0, 800.0, 10000.0, POCKET, "Key sequence within (ms)",
        "The whole unlock sequence must be pressed within this time.")
    val POCKET_BRIGHTNESS = k("pocketBrightness", 0.01, 0.0, 1.0, POCKET, "Black screen brightness",
        "The backlight level while the screen is black. 0 is as low as the panel goes.")
    val POCKET_RESTORE_GAP_MS = k("pocketRestoreGapMs", 2000.0, 300.0, 30000.0, POCKET, "Reopen the app at most every (ms)",
        "So two apps can never fight over the screen.")
    val POCKET_SHADE_DELAY_MS = k("pocketShadeDelayMs", 450.0, 0.0, 3000.0, POCKET, "Wait for the shade to close (ms)",
        "From the quick settings tile: how long before the lock goes up.")
    val POCKET_HINT_MS = k("pocketHintMs", 2500.0, 0.0, 10000.0, POCKET, "Show the unlock hint for (ms)",
        "0 never shows it.")
    val POCKET_HOLD_SLOP_DP = k("pocketHoldSlopDp", 40.0, 5.0, 300.0, POCKET, "Held fingers may drift (dp)",
        "Further than this restarts the hold.")

    // --- macros and sweeps ---------------------------------------------------
    val MACRO_MIN_PAUSE_MS = k("macroMinPauseMs", 250.0, 0.0, 5000.0, TIMING, "Macro: keep pauses from (ms)",
        "Around actions on other apps, shorter pauses are dropped.")
    val MACRO_MAX_PAUSE_MS = k("macroMaxPauseMs", 3000.0, 100.0, 60000.0, TIMING, "Macro: longest pause kept (ms)",
        "Longer pauses are shortened to this when played back.")
    val SWEEP_PAGE_MS = k("sweepPageMs", 700.0, 100.0, 10000.0, TIMING, "Select many: wait after scrolling (ms)",
        "Time for the next page to load before its items are pressed.")

    // --- pointer and trackpad -------------------------------------------------
    val POINTER_GAIN = k("pointerGain", 1.4, 0.2, 6.0, TOUCH, "Pointer: speed",
        "How far the pointer moves per finger movement, when slow.")
    val POINTER_ACCEL = k("pointerAccel", 2.2, 0.0, 10.0, TOUCH, "Pointer: acceleration",
        "Extra speed for fast strokes. 0 turns acceleration off.")
    val TRACKPAD_STEP_DP = k("trackpadStepDp", 14.0, 4.0, 80.0, TOUCH, "Trackpad without pointer: per character (dp)",
        "Finger travel that moves the text cursor one character.")
    val POINTER_LONG_MS = k("pointerLongMs", 650.0, 300.0, 3000.0, TOUCH, "Pointer: hold lasts (ms)",
        "How long a long press made by the pointer or by 'long_press' holds.")

    // --- typing intelligence ------------------------------------------------
    val PROPER_NOUN_MIN = k("properNounMin", 2.0, 1.0, 20.0, TYPING, "Names: capitalised at least",
        "Times a word must be capitalised mid-sentence before it counts as a name.")
    val PROPER_NOUN_RATIO = k("properNounRatio", 2.0, 1.0, 20.0, TYPING, "Names: capitals outnumber lower case by",
        "How many times more often capitalised than not.")
    val TUNER_STEP_MS = k("tunerStepMs", 12.0, 1.0, 100.0, TIMING, "Learning long press: step (ms)",
        "How far one piece of evidence moves the threshold.")
    val TUNER_MARGIN_MS = k("tunerMarginMs", 30.0, 0.0, 300.0, TIMING, "Learning long press: margin above taps (ms)",
        "How far above nearly all plain taps the threshold is kept.")

    // --- keys ------------------------------------------------------------------
    val REPEAT_ACCEL_MS = k("repeatAccelMs", 3.0, 0.0, 50.0, TIMING, "Key repeat: speeds up by (ms per repeat)",
        "0 keeps a held key repeating at one steady rate.")
    val REPEAT_FASTEST_MS = k("repeatFastestMs", 22.0, 5.0, 500.0, TIMING, "Key repeat: never faster than (ms)",
        "The shortest interval acceleration may reach.")
    val LONG_SWIPE_FACTOR = k("longSwipeFactor", 3.0, 1.0, 10.0, SettingsSchema.GROUP_GESTURES, "Whole-keyboard swipe: × the key swipe",
        "How much longer than a key swipe a stroke must be to count as a swipe over the whole keyboard.")
    val LABEL_SIZE = k("labelSize", 0.36, 0.15, 0.8, SettingsSchema.GROUP_APPEARANCE, "Key letters: share of key height",
        "Before the text scale setting is applied.")
    val LONG_LABEL_SHRINK = k("longLabelShrink", 0.62, 0.3, 1.0, SettingsSchema.GROUP_APPEARANCE, "Key words: shrink labels longer than two characters to",
        "So \"Enter\" and \"Esc\" fit.")
    val HINT_SIZE = k("hintSize", 0.45, 0.2, 1.0, SettingsSchema.GROUP_APPEARANCE, "Corner hints: size relative to the label", "")
    val PREVIEW_HEIGHT = k("previewHeight", 1.25, 0.5, 3.0, SettingsSchema.GROUP_APPEARANCE, "Key preview: height × key", "")
    val POPUP_RAISE = k("popupRaise", 1.15, 0.5, 3.0, SettingsSchema.GROUP_APPEARANCE, "Long-press strip: raised by × key height", "")

    // --- typing intelligence windows -----------------------------------------
    val MAGNET_BEFORE = k("magnetBefore", 300.0, 20.0, 4000.0, TYPING, "Arrows find the typo: look back (characters)", "")
    val MAGNET_AFTER = k("magnetAfter", 120.0, 0.0, 4000.0, TYPING, "Arrows find the typo: look ahead (characters)", "")
    val SCREEN_CONTEXT_MINUTES = k("screenContextMinutes", 10.0, 1.0, 240.0, SettingsSchema.GROUP_AI, "Screen text stays context for (minutes)",
        "After \"Screen → AI\", how long the next AI task still sees it.")
    val SCREEN_CONTEXT_CHARS = k("screenContextChars", 6000.0, 200.0, 50000.0, SettingsSchema.GROUP_AI, "Screen text given to the AI (characters)", "")
    val ASR_RETRY_MS = k("asrRetryMs", 250.0, 0.0, 5000.0, SettingsSchema.GROUP_VOICE, "Dictation: retry after a dropped connection (ms)", "")

    // --- reading aloud -------------------------------------------------------------
    val READ_CHUNK_CHARS = k("readChunkChars", 3000.0, 100.0, 3900.0, SettingsSchema.GROUP_SPEECH, "Reading: longest piece (characters)",
        "Long text is cut at sentence ends into pieces no longer than this; the engine's limit is about 4000.")
    val READ_PAGE_WAIT_MS = k("readPageWaitMs", 900.0, 100.0, 10000.0, SettingsSchema.GROUP_SPEECH, "Reading a page: wait after scrolling (ms)",
        "Time for the next part of the page to appear before it is read.")
    val READ_PAGE_MAX = k("readPageMax", 60.0, 1.0, 1000.0, SettingsSchema.GROUP_SPEECH, "Reading a page: most screens",
        "Stops after this many screens, so a feed without end is not read forever.")

    // --- notices ---------------------------------------------------------------
    val NOTICE_MS = k("noticeMs", 3500.0, 500.0, 30000.0, TIMING, "News in the strip stays (ms)",
        "Twice as long when it has something to tap.")

    val ALL: List<Knob> get() = all.toList()

    fun byKey(key: String): Knob? = if (key.startsWith(PREFIX)) all.firstOrNull { it.key == key } else null
}

/** The knob's current value: the user's if they changed it, the designed default otherwise. */
fun Settings.knob(k: Knob): Double = knobs[k.id]?.coerceIn(k.min, k.max) ?: k.default

fun Settings.knobLong(k: Knob): Long = knob(k).toLong()

fun Settings.knobInt(k: Knob): Int = knob(k).toInt()

fun Settings.knobFloat(k: Knob): Float = knob(k).toFloat()
