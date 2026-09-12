package com.example.core.config

import android.content.Context
import com.example.core.layout.enumOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single source of truth for settings, shared by the settings UI and the IME.
 *
 * It is a process-wide singleton holding a [StateFlow], so a change made in the
 * settings screen is visible to the running keyboard on the next frame without any
 * listener plumbing. Persistence is one JSON blob rather than a hundred preference
 * keys: it keeps read and write symmetrical (a key can not be written under one name
 * and read under another), and it makes "export all my settings" a one-liner.
 */
object SettingsStore {

    private const val PREFS_FILE = "keyboard_settings"
    private const val KEY_BLOB = "settings_json"

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        _state.value = read(ctx)
    }

    /** Apply a change and persist it. Safe to call from any thread. */
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        appContext?.let { write(it, next) }
    }

    fun replaceAll(settings: Settings) = update { settings }

    fun resetToDefaults() = update { Settings() }

    /**
     * Change one setting by its name.
     *
     * This is the door that lets something which was never written for a particular
     * setting still change it — a generated panel, a search result, an imported
     * profile. An unrecognised name or an unusable value is a no-op rather than an
     * error, because the callers are open-ended by design.
     */
    fun setByKey(key: String, value: Any?) = update { SettingsSchema.withValue(it, key, value) }

    fun getByKey(key: String, settings: Settings = current): Any? =
        SettingsSchema.valueOf(settings, key)

    /** Restore one setting to what it ships as, without touching the rest. */
    fun resetKey(key: String) = update {
        SettingsSchema.withValue(it, key, SettingsSchema.spec(key)?.default)
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private fun read(context: Context): Settings {
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null) ?: return Settings()
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            // A corrupt blob must never stop the keyboard from coming up.
            Settings()
        }
    }

    private fun write(context: Context, settings: Settings) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLOB, toJson(settings).toString())
            .apply()
    }

    fun exportJson(settings: Settings = current): String = toJson(settings).toString(2)

    fun importJson(raw: String): Result<Settings> = try {
        val parsed = fromJson(JSONObject(com.example.core.layout.LayoutJson.stripCodeFence(raw)))
        update { parsed }
        Result.success(parsed)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // -----------------------------------------------------------------------
    // Codec. Unknown keys are ignored and missing keys fall back to the default,
    // so a settings file from an older or newer build still loads.
    // -----------------------------------------------------------------------

    internal fun toJson(s: Settings): JSONObject = JSONObject().apply {
        put("expertMode", s.expertMode)
        put("themeId", s.themeId)
        put("presentation", s.presentation.name)
        put("heightPortrait", s.heightPortrait.toDouble())
        put("heightLandscape", s.heightLandscape.toDouble())
        put("widthFraction", s.widthFraction.toDouble())
        put("bottomPaddingDp", s.bottomPaddingDp.toDouble())
        put("sidePaddingDp", s.sidePaddingDp.toDouble())
        put("keyGapDp", s.keyGapDp.toDouble())
        put("keyCornerDp", s.keyCornerDp.toDouble())
        put("keyTextScale", s.keyTextScale.toDouble())
        put("splitGapFraction", s.splitGapFraction.toDouble())
        put("floatingX", s.floatingX.toDouble())
        put("floatingY", s.floatingY.toDouble())
        put("floatingWidthDp", s.floatingWidthDp.toDouble())
        put("floatingHeightDp", s.floatingHeightDp.toDouble())
        put("separateLandscapeSize", s.separateLandscapeSize)
        put("keyboardOpacity", s.keyboardOpacity.toDouble())
        put("panelOpacity", s.panelOpacity.toDouble())
        put("keyOpacity", s.keyOpacity.toDouble())
        put("keyBorderWidthDp", s.keyBorderWidthDp.toDouble())
        put("keyBorderOpacity", s.keyBorderOpacity.toDouble())
        put("keyLabelOpacity", s.keyLabelOpacity.toDouble())

        put("freeKeyScale", s.freeKeyScale.toDouble())
        put("freeSpreadX", s.freeSpreadX.toDouble())
        put("freeSpreadY", s.freeSpreadY.toDouble())
        put("freeOriginXDp", s.freeOriginXDp.toDouble())
        put("freeOriginYDp", s.freeOriginYDp.toDouble())
        put("freeKeysDraggable", s.freeKeysDraggable)
        put("freeShowGuides", s.freeShowGuides)

        put("insetsMode", s.insetsMode.name)
        put("avoidCoveringCursor", s.avoidCoveringCursor)
        put("cursorAvoidMarginDp", s.cursorAvoidMarginDp.toDouble())
        put("cursorAvoidStrategy", s.cursorAvoidStrategy.name)
        put("cursorAvoidFadeTo", s.cursorAvoidFadeTo.toDouble())

        put("enabledLayoutIds", JSONArray(s.enabledLayoutIds))
        put("activeLayoutId", s.activeLayoutId)
        put("rememberLayoutPerApp", s.rememberLayoutPerApp)

        put("longPressMs", s.longPressMs)
        put("repeatStartMs", s.repeatStartMs)
        put("repeatIntervalMs", s.repeatIntervalMs)
        put("doubleTapMs", s.doubleTapMs)
        put("swipeThresholdDp", s.swipeThresholdDp.toDouble())

        put("autoCapitalize", s.autoCapitalize)
        put("doubleSpacePeriod", s.doubleSpacePeriod)
        put("autoSpaceAfterPunctuation", s.autoSpaceAfterPunctuation)
        put("smartQuotes", s.smartQuotes)
        put("spaceSlideCursor", s.spaceSlideCursor)
        put("backspaceSwipeDeletesWord", s.backspaceSwipeDeletesWord)
        put("keyPreviewPopup", s.keyPreviewPopup)
        put("longPressPopup", s.longPressPopup)
        put("flickInput", s.flickInput)
        put("shiftOneShot", s.shiftOneShot)

        put("hapticEnabled", s.hapticEnabled)
        put("hapticMs", s.hapticMs)
        put("hapticAmplitude", s.hapticAmplitude)
        put("soundEnabled", s.soundEnabled)
        put("soundVolume", s.soundVolume.toDouble())

        put("suggestionsEnabled", s.suggestionsEnabled)
        put("suggestionCount", s.suggestionCount)
        put("autoCorrect", s.autoCorrect)
        put("learnFromTyping", s.learnFromTyping)
        put("personalDictionary", s.personalDictionary)

        put("aiEnabled", s.aiEnabled)
        put("aiProvider", s.aiProvider)
        put("aiBaseUrl", s.aiBaseUrl)
        put("aiApiKey", s.aiApiKey)
        put("aiModel", s.aiModel)
        put("aiCompletionEnabled", s.aiCompletionEnabled)
        put("aiCompletionMinChars", s.aiCompletionMinChars)
        put("aiCompletionDebounceMs", s.aiCompletionDebounceMs)
        put("aiContextChars", s.aiContextChars)
        put("aiTemperature", s.aiTemperature.toDouble())
        put("aiMaxTokens", s.aiMaxTokens)
        put("aiCustomTasksJson", s.aiCustomTasksJson)

        put("asrEngine", s.asrEngine)
        put("asrRemoteUrl", s.asrRemoteUrl)
        put("asrApiKey", s.asrApiKey)
        put("asrModel", s.asrModel)
        put("asrLanguage", s.asrLanguage)
        put("asrShowAlternatives", s.asrShowAlternatives)
        put("asrAlternativeCount", s.asrAlternativeCount)
        put("asrAutoCommitBest", s.asrAutoCommitBest)

        put("clipboardEnabled", s.clipboardEnabled)
        put("clipboardRetentionDays", s.clipboardRetentionDays)
        put("clipboardMaxItems", s.clipboardMaxItems)
        put("clipboardIgnorePasswordFields", s.clipboardIgnorePasswordFields)
        put("clipboardSuggestions", s.clipboardSuggestions)
        put("clipboardSuggestionSeconds", s.clipboardSuggestionSeconds)

        put("indicatorsEnabled", s.indicatorsEnabled)
        put("indicatorStripVisible", s.indicatorStripVisible)
        put("indicatorOnColor", s.indicatorOnColor)
        put("indicatorOffColor", s.indicatorOffColor)

        put("touchModelEnabled", s.touchModelEnabled)
        put("touchModelLearning", s.touchModelLearning)
        put("touchModelSigmaDp", s.touchModelSigmaDp.toDouble())

        put("incognitoInPasswordFields", s.incognitoInPasswordFields)
        put("debugOverlay", s.debugOverlay)

        put("volumeKeysResize", s.volumeKeysResize)
        put("showOnHardwareKeyboard", s.showOnHardwareKeyboard)

        put("gestureSwipeUp", s.gestureSwipeUp)
        put("gestureSwipeDown", s.gestureSwipeDown)
        put("gestureSwipeLeft", s.gestureSwipeLeft)
        put("gestureSwipeRight", s.gestureSwipeRight)

        put("customThemesJson", s.customThemesJson)
        put("generatedPanelsJson", s.generatedPanelsJson)
        put("customProvidersJson", s.customProvidersJson)
        put("discoveredModelsJson", s.discoveredModelsJson)
    }

    internal fun fromJson(o: JSONObject): Settings {
        val d = Settings()
        return Settings(
            expertMode = o.optBoolean("expertMode", d.expertMode),
            themeId = o.optString("themeId", d.themeId),
            presentation = enumOf(o.optString("presentation", d.presentation.name), d.presentation),
            heightPortrait = o.optDouble("heightPortrait", d.heightPortrait.toDouble()).toFloat(),
            heightLandscape = o.optDouble("heightLandscape", d.heightLandscape.toDouble()).toFloat(),
            widthFraction = o.optDouble("widthFraction", d.widthFraction.toDouble()).toFloat(),
            bottomPaddingDp = o.optDouble("bottomPaddingDp", d.bottomPaddingDp.toDouble()).toFloat(),
            sidePaddingDp = o.optDouble("sidePaddingDp", d.sidePaddingDp.toDouble()).toFloat(),
            keyGapDp = o.optDouble("keyGapDp", d.keyGapDp.toDouble()).toFloat(),
            keyCornerDp = o.optDouble("keyCornerDp", d.keyCornerDp.toDouble()).toFloat(),
            keyTextScale = o.optDouble("keyTextScale", d.keyTextScale.toDouble()).toFloat(),
            splitGapFraction = o.optDouble("splitGapFraction", d.splitGapFraction.toDouble()).toFloat(),
            floatingX = o.optDouble("floatingX", d.floatingX.toDouble()).toFloat(),
            floatingY = o.optDouble("floatingY", d.floatingY.toDouble()).toFloat(),
            floatingWidthDp = o.optDouble("floatingWidthDp", d.floatingWidthDp.toDouble()).toFloat(),
            floatingHeightDp = o.optDouble("floatingHeightDp", d.floatingHeightDp.toDouble()).toFloat(),
            separateLandscapeSize = o.optBoolean("separateLandscapeSize", d.separateLandscapeSize),
            keyboardOpacity = o.optDouble("keyboardOpacity", d.keyboardOpacity.toDouble()).toFloat(),
            panelOpacity = o.optDouble("panelOpacity", d.panelOpacity.toDouble()).toFloat(),
            keyOpacity = o.optDouble("keyOpacity", d.keyOpacity.toDouble()).toFloat(),
            keyBorderWidthDp = o.optDouble("keyBorderWidthDp", d.keyBorderWidthDp.toDouble()).toFloat(),
            keyBorderOpacity = o.optDouble("keyBorderOpacity", d.keyBorderOpacity.toDouble()).toFloat(),
            keyLabelOpacity = o.optDouble("keyLabelOpacity", d.keyLabelOpacity.toDouble()).toFloat(),

            freeKeyScale = o.optDouble("freeKeyScale", d.freeKeyScale.toDouble()).toFloat(),
            freeSpreadX = o.optDouble("freeSpreadX", d.freeSpreadX.toDouble()).toFloat(),
            freeSpreadY = o.optDouble("freeSpreadY", d.freeSpreadY.toDouble()).toFloat(),
            freeOriginXDp = o.optDouble("freeOriginXDp", d.freeOriginXDp.toDouble()).toFloat(),
            freeOriginYDp = o.optDouble("freeOriginYDp", d.freeOriginYDp.toDouble()).toFloat(),
            freeKeysDraggable = o.optBoolean("freeKeysDraggable", d.freeKeysDraggable),
            freeShowGuides = o.optBoolean("freeShowGuides", d.freeShowGuides),

            insetsMode = enumOf(o.optString("insetsMode", d.insetsMode.name), d.insetsMode),
            avoidCoveringCursor = o.optBoolean("avoidCoveringCursor", d.avoidCoveringCursor),
            cursorAvoidMarginDp = o.optDouble("cursorAvoidMarginDp", d.cursorAvoidMarginDp.toDouble()).toFloat(),
            cursorAvoidStrategy = enumOf(o.optString("cursorAvoidStrategy", d.cursorAvoidStrategy.name), d.cursorAvoidStrategy),
            cursorAvoidFadeTo = o.optDouble("cursorAvoidFadeTo", d.cursorAvoidFadeTo.toDouble()).toFloat(),

            enabledLayoutIds = o.optJSONArray("enabledLayoutIds")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }?.takeIf { it.isNotEmpty() } ?: d.enabledLayoutIds,
            activeLayoutId = o.optString("activeLayoutId", d.activeLayoutId),
            rememberLayoutPerApp = o.optBoolean("rememberLayoutPerApp", d.rememberLayoutPerApp),

            longPressMs = o.optLong("longPressMs", d.longPressMs),
            repeatStartMs = o.optLong("repeatStartMs", d.repeatStartMs),
            repeatIntervalMs = o.optLong("repeatIntervalMs", d.repeatIntervalMs),
            doubleTapMs = o.optLong("doubleTapMs", d.doubleTapMs),
            swipeThresholdDp = o.optDouble("swipeThresholdDp", d.swipeThresholdDp.toDouble()).toFloat(),

            autoCapitalize = o.optBoolean("autoCapitalize", d.autoCapitalize),
            doubleSpacePeriod = o.optBoolean("doubleSpacePeriod", d.doubleSpacePeriod),
            autoSpaceAfterPunctuation = o.optBoolean("autoSpaceAfterPunctuation", d.autoSpaceAfterPunctuation),
            smartQuotes = o.optBoolean("smartQuotes", d.smartQuotes),
            spaceSlideCursor = o.optBoolean("spaceSlideCursor", d.spaceSlideCursor),
            backspaceSwipeDeletesWord = o.optBoolean("backspaceSwipeDeletesWord", d.backspaceSwipeDeletesWord),
            keyPreviewPopup = o.optBoolean("keyPreviewPopup", d.keyPreviewPopup),
            longPressPopup = o.optBoolean("longPressPopup", d.longPressPopup),
            flickInput = o.optBoolean("flickInput", d.flickInput),
            shiftOneShot = o.optBoolean("shiftOneShot", d.shiftOneShot),

            hapticEnabled = o.optBoolean("hapticEnabled", d.hapticEnabled),
            hapticMs = o.optInt("hapticMs", d.hapticMs),
            hapticAmplitude = o.optInt("hapticAmplitude", d.hapticAmplitude),
            soundEnabled = o.optBoolean("soundEnabled", d.soundEnabled),
            soundVolume = o.optDouble("soundVolume", d.soundVolume.toDouble()).toFloat(),

            suggestionsEnabled = o.optBoolean("suggestionsEnabled", d.suggestionsEnabled),
            suggestionCount = o.optInt("suggestionCount", d.suggestionCount),
            autoCorrect = o.optBoolean("autoCorrect", d.autoCorrect),
            learnFromTyping = o.optBoolean("learnFromTyping", d.learnFromTyping),
            personalDictionary = o.optBoolean("personalDictionary", d.personalDictionary),

            aiEnabled = o.optBoolean("aiEnabled", d.aiEnabled),
            aiProvider = o.optString("aiProvider", d.aiProvider),
            aiBaseUrl = o.optString("aiBaseUrl", d.aiBaseUrl),
            aiApiKey = o.optString("aiApiKey", d.aiApiKey),
            aiModel = o.optString("aiModel", d.aiModel),
            aiCompletionEnabled = o.optBoolean("aiCompletionEnabled", d.aiCompletionEnabled),
            aiCompletionMinChars = o.optInt("aiCompletionMinChars", d.aiCompletionMinChars),
            aiCompletionDebounceMs = o.optLong("aiCompletionDebounceMs", d.aiCompletionDebounceMs),
            aiContextChars = o.optInt("aiContextChars", d.aiContextChars),
            aiTemperature = o.optDouble("aiTemperature", d.aiTemperature.toDouble()).toFloat(),
            aiMaxTokens = o.optInt("aiMaxTokens", d.aiMaxTokens),
            aiCustomTasksJson = o.optString("aiCustomTasksJson", d.aiCustomTasksJson),

            asrEngine = o.optString("asrEngine", d.asrEngine),
            asrRemoteUrl = o.optString("asrRemoteUrl", d.asrRemoteUrl),
            asrApiKey = o.optString("asrApiKey", d.asrApiKey),
            asrModel = o.optString("asrModel", d.asrModel),
            asrLanguage = o.optString("asrLanguage", d.asrLanguage),
            asrShowAlternatives = o.optBoolean("asrShowAlternatives", d.asrShowAlternatives),
            asrAlternativeCount = o.optInt("asrAlternativeCount", d.asrAlternativeCount),
            asrAutoCommitBest = o.optBoolean("asrAutoCommitBest", d.asrAutoCommitBest),

            clipboardEnabled = o.optBoolean("clipboardEnabled", d.clipboardEnabled),
            clipboardRetentionDays = o.optInt("clipboardRetentionDays", d.clipboardRetentionDays),
            clipboardMaxItems = o.optInt("clipboardMaxItems", d.clipboardMaxItems),
            clipboardIgnorePasswordFields = o.optBoolean("clipboardIgnorePasswordFields", d.clipboardIgnorePasswordFields),
            clipboardSuggestions = o.optBoolean("clipboardSuggestions", d.clipboardSuggestions),
            clipboardSuggestionSeconds = o.optInt("clipboardSuggestionSeconds", d.clipboardSuggestionSeconds),

            indicatorsEnabled = o.optBoolean("indicatorsEnabled", d.indicatorsEnabled),
            indicatorStripVisible = o.optBoolean("indicatorStripVisible", d.indicatorStripVisible),
            indicatorOnColor = o.optLong("indicatorOnColor", d.indicatorOnColor),
            indicatorOffColor = o.optLong("indicatorOffColor", d.indicatorOffColor),

            touchModelEnabled = o.optBoolean("touchModelEnabled", d.touchModelEnabled),
            touchModelLearning = o.optBoolean("touchModelLearning", d.touchModelLearning),
            touchModelSigmaDp = o.optDouble("touchModelSigmaDp", d.touchModelSigmaDp.toDouble()).toFloat(),

            incognitoInPasswordFields = o.optBoolean("incognitoInPasswordFields", d.incognitoInPasswordFields),
            debugOverlay = o.optBoolean("debugOverlay", d.debugOverlay),

            volumeKeysResize = o.optBoolean("volumeKeysResize", d.volumeKeysResize),
            showOnHardwareKeyboard = o.optBoolean("showOnHardwareKeyboard", d.showOnHardwareKeyboard),

            gestureSwipeUp = o.optString("gestureSwipeUp", d.gestureSwipeUp),
            gestureSwipeDown = o.optString("gestureSwipeDown", d.gestureSwipeDown),
            gestureSwipeLeft = o.optString("gestureSwipeLeft", d.gestureSwipeLeft),
            gestureSwipeRight = o.optString("gestureSwipeRight", d.gestureSwipeRight),

            customThemesJson = o.optString("customThemesJson", d.customThemesJson),
            generatedPanelsJson = o.optString("generatedPanelsJson", d.generatedPanelsJson),
            customProvidersJson = o.optString("customProvidersJson", d.customProvidersJson),
            discoveredModelsJson = o.optString("discoveredModelsJson", d.discoveredModelsJson)
        )
    }
}
