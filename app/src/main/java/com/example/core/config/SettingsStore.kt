package com.example.core.config

import android.content.Context
import com.example.core.layout.enumOf
import com.example.core.security.EncryptedSettingsPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()
    private val _persistencePending = MutableStateFlow(false)
    val persistencePending: StateFlow<Boolean> = _persistencePending.asStateFlow()
    private data class WriteRequest(val settings: Settings, val revision: Long)
    private val writes = Channel<WriteRequest>(Channel.CONFLATED)
    private var revision = 0L
    private var startupReadFailure: String? = null
    private var lastWriteFailure: String? = null
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        writer.launch {
            for (first in writes) {
                // Dragging produces tens of updates per second. Encrypt and fsync
                // once per burst, on IO, rather than blocking input for every pixel.
                delay(100)
                var pending = first
                while (true) pending = writes.tryReceive().getOrNull() ?: break
                val outcome = runCatching {
                    check(startupReadFailure == null) { startupReadFailure.orEmpty() }
                    appContext?.let { write(it, pending.settings) }
                }
                synchronized(this@SettingsStore) {
                    if (pending.revision == revision) {
                        lastWriteFailure = outcome.exceptionOrNull()?.message
                        _persistenceError.value = lastWriteFailure ?: EncryptedSettingsPersistence.warning
                        _persistencePending.value = false
                    }
                }
            }
        }
    }

    val current: Settings get() = _state.value

    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        _state.value = runCatching {
            read(ctx).also { _persistenceError.value = EncryptedSettingsPersistence.warning }
        }.getOrElse {
            startupReadFailure = it.message ?: "Settings could not be opened. Existing data was preserved."
            _persistenceError.value = startupReadFailure
            Settings()
        }
        SettingsProfiles.init(ctx)
        SettingsSearch.init(ctx)
    }

    /**
     * Apply immediately and enqueue device storage on a single IO writer. Success
     * means accepted, not fsynced: [persistencePending] and [persistenceError] expose
     * durability. Failure preserves the previous encrypted file and the unsaved
     * edits remain visible, so a user can retry or export rather than lose work.
     */
    @Synchronized
    fun update(transform: (Settings) -> Settings): Result<Unit> = runCatching {
        val next = transform(_state.value)
        _state.value = next
        if (appContext != null) {
            revision++
            _persistencePending.value = true
            check(writes.trySend(WriteRequest(next, revision)).isSuccess) { "Settings writer is unavailable" }
        }
    }.onFailure { _persistenceError.value = it.message ?: "Settings could not be updated" }

    /** For a deliberate save/import flow that needs to wait for durable storage. */
    suspend fun awaitPersistence(timeoutMs: Long = 10_000): Result<Unit> = runCatching {
        withTimeout(timeoutMs) { persistencePending.first { !it } }
        (startupReadFailure ?: lastWriteFailure)?.let { error(it) }
    }

    fun retryPersistence(): Result<Unit> = update { it }

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
    fun setByKey(key: String, value: Any?) = update {
        if (key == "settingsLevel") SettingsHierarchy.selectLevel(it, enumOf(value.toString(), it.settingsLevel))
        else SettingsSchema.withValue(it, key, value)
    }

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
        val raw = EncryptedSettingsPersistence.read(context) ?: return Settings()
        return fromJson(JSONObject(raw))
    }

    private fun write(context: Context, settings: Settings) =
        EncryptedSettingsPersistence.write(context, toJson(settings).toString())

    /** A portable patch: credentials and opaque account/action data stay on this device. */
    fun exportJson(settings: Settings = current): String = SettingsProfiles.portableValues(settings).toString(2)

    fun importJson(raw: String): Result<Settings> = runCatching {
        val parsed = SettingsProfiles.parseImport(raw).getOrThrow()
        var applied = current
        update { previous -> SettingsProfiles.apply(previous, parsed).getOrThrow().also { applied = it } }.getOrThrow()
        applied
    }

    // -----------------------------------------------------------------------
    // Codec. Unknown keys are ignored and missing keys fall back to the default,
    // so a settings file from an older or newer build still loads.
    // -----------------------------------------------------------------------

    internal fun toJson(s: Settings): JSONObject = JSONObject().apply {
        put("mediaMimePatterns", s.mediaMimePatterns)
        put("mediaShowHidden", s.mediaShowHidden)
        put("mediaShowFolders", s.mediaShowFolders)
        put("mediaSort", s.mediaSort)
        put("mediaEntryLimit", s.mediaEntryLimit)
        put("mediaProviderTimeoutMs", s.mediaProviderTimeoutMs)
        put("settingsLevel", s.settingsLevel.name)
        put("localSettingsPolicy", s.localSettingsPolicy.name)
        put("toolbarRowsJson", s.toolbarRowsJson)
        put("expertMode", s.expertMode)
        put("vaultSessionSeconds", s.vaultSessionSeconds)
        put("keyboardToolbarVisible", s.keyboardToolbarVisible)
        put("ioActionProfilesJson", s.ioActionProfilesJson)
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
        put("floatingSafeBottomDp", s.floatingSafeBottomDp.toDouble())
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
        put("freeArrangeMode", s.freeArrangeMode)
        put("freeKeyPinsJson", s.freeKeyPinsJson)
        put("elementPosesJson", s.elementPosesJson)
        put("controlValuesJson", s.controlValuesJson)
        put("engineWiresJson", s.engineWiresJson)
        put("macrosJson", s.macrosJson)
        put("convertLocalOnly", s.convertLocalOnly)
        put("engineStreamsJson", s.engineStreamsJson)
        put("netListenPort", s.netListenPort)
        put("netToken", s.netToken)
        put("netAllowCommands", s.netAllowCommands)
        put("pocketMode", s.pocketMode.name)
        put("pocketBlockTouch", s.pocketBlockTouch)
        put("pocketBlockNavigation", s.pocketBlockNavigation)
        put("pocketBlockMediaKeys", s.pocketBlockMediaKeys)
        put("pocketBlockOtherKeys", s.pocketBlockOtherKeys)
        put("keyboardKeepVisible", s.keyboardKeepVisible)
        put("pocketBlockKeys", s.pocketBlockKeys)
        put("pocketUnlockKeys", s.pocketUnlockKeys)
        put("pocketUnlockFingers", s.pocketUnlockFingers)
        put("pocketUnlockHoldMs", s.pocketUnlockHoldMs)
        put("pocketIgnoreWhenCovered", s.pocketIgnoreWhenCovered)
        put("pocketKeepAwake", s.pocketKeepAwake)
        put("pocketRestoreApp", s.pocketRestoreApp)
        put("fieldlessLayoutId", s.fieldlessLayoutId)
        put("actionSuggestions", s.actionSuggestions)
        put("actionSuggestionCount", s.actionSuggestionCount)
        put("learnActions", s.learnActions)
        put("actionCommandPrefix", s.actionCommandPrefix)
        put("ttsEngine", s.ttsEngine)
        put("ttsLanguage", s.ttsLanguage)
        put("ttsVoice", s.ttsVoice)
        put("ttsRate", s.ttsRate.toDouble())
        put("ttsPitch", s.ttsPitch.toDouble())
        put("ttsUsage", s.ttsUsage)
        put("ttsEcho", s.ttsEcho.name)
        put("ttsFollowAlong", s.ttsFollowAlong)
        put("ttsProvider", s.ttsProvider)
        put("ttsModel", s.ttsModel)
        put("ttsCloudVoice", s.ttsCloudVoice)
        put("pocketUnlockPhrase", s.pocketUnlockPhrase)
        put("pocketLockPhrase", s.pocketLockPhrase)
        put("pocketLockPhraseApps", JSONArray(s.pocketLockPhraseApps))

        put("insetsMode", s.insetsMode.name)
        put("avoidCoveringCursor", s.avoidCoveringCursor)
        put("cursorAvoidMarginDp", s.cursorAvoidMarginDp.toDouble())
        put("cursorAvoidStrategy", s.cursorAvoidStrategy.name)
        put("cursorAvoidFadeTo", s.cursorAvoidFadeTo.toDouble())

        put("enabledLayoutIds", JSONArray(s.enabledLayoutIds))
        put("activeLayoutId", s.activeLayoutId)
        put("rememberLayoutPerApp", s.rememberLayoutPerApp)

        put("longPressMs", s.longPressMs)
        put("longPressBoardMs", s.longPressBoardMs)
        put("longPressAdaptive", s.longPressAdaptive)
        put("repeatStartMs", s.repeatStartMs)
        put("repeatIntervalMs", s.repeatIntervalMs)
        put("doubleTapMs", s.doubleTapMs)
        put("swipeThresholdDp", s.swipeThresholdDp.toDouble())

        put("autoCapitalize", s.autoCapitalize)
        put("capitalisationRulesJson", s.capitalisationRulesJson)
        put("properNouns", JSONArray(s.properNouns))
        put("learnProperNouns", s.learnProperNouns)
        put("cursorMagnet", s.cursorMagnet.name)
        put("cursorMagnetReach", s.cursorMagnetReach)
        put("cursorMagnetPauseMs", s.cursorMagnetPauseMs)
        put("cursorMagnetStride", s.cursorMagnetStride)
        put("providerProfilesJson", s.providerProfilesJson)
        put("wordListSourcesJson", s.wordListSourcesJson)
        put("doubleSpacePeriod", s.doubleSpacePeriod)
        put("autoSpaceAfterPunctuation", s.autoSpaceAfterPunctuation)
        put("smartQuotes", s.smartQuotes)
        put("spaceSlideCursor", s.spaceSlideCursor)
        put("backspaceSwipeDeletesWord", s.backspaceSwipeDeletesWord)
        put("keyPreviewPopup", s.keyPreviewPopup)
        put("longPressPopup", s.longPressPopup)
        put("flickInput", s.flickInput)
        put("shiftOneShot", s.shiftOneShot)
        put("symbolBoardColumns", s.symbolBoardColumns)
        put("popupTabMemoryJson", s.popupTabMemoryJson)
        put("popupPinnedTabsJson", s.popupPinnedTabsJson)

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
        put("completionEnabled", s.completionEnabled)
        put("completionProvider", s.completionProvider)
        put("completionModel", s.completionModel)
        put("completionApiKey", s.completionApiKey)
        put("completionMinChars", s.completionMinChars)
        put("completionDebounceMs", s.completionDebounceMs)
        put("completionMaxTokens", s.completionMaxTokens)
        put("completionTemperature", s.completionTemperature)
        put("completionTokenFloor", s.completionTokenFloor)
        put("completionSurpriseBudget", s.completionSurpriseBudget)
        put("completionMaxChars", s.completionMaxChars)
        put("completionMaxMillis", s.completionMaxMillis)
        put("completionAcceptedScope", s.completionAcceptedScope)
        put("completionReserveRow", s.completionReserveRow)
        put("catalogUrl", s.catalogUrl)
        put("fetchedProvidersJson", s.fetchedProvidersJson)
        put("catalogFetchedAt", s.catalogFetchedAt)
        put("setupDone", s.setupDone)

        put("asrEngine", s.asrEngine)
        put("asrProvider", s.asrProvider)
        put("asrRemoteUrl", s.asrRemoteUrl)
        put("asrApiKey", s.asrApiKey)
        put("asrModel", s.asrModel)
        put("asrLanguage", s.asrLanguage)
        put("pasteConvert", s.pasteConvert)
        put("ocrProvider", s.ocrProvider)
        put("ocrModel", s.ocrModel)
        put("asrShowAlternatives", s.asrShowAlternatives)
        put("asrAlternativeCount", s.asrAlternativeCount)
        put("asrAutoCommitBest", s.asrAutoCommitBest)

        put("clipboardEnabled", s.clipboardEnabled)
        put("clipboardRetentionDays", s.clipboardRetentionDays)
        put("syncClipboard", s.syncClipboard)
        put("syncSettings", s.syncSettings)
        put("syncImages", s.syncImages)
        put("syncPropagatePruning", s.syncPropagatePruning)
        put("syncMaxItems", s.syncMaxItems)
        put("syncMaxImageMb", s.syncMaxImageMb)
        put("clipboardTrashHours", s.clipboardTrashHours)
        put("screenshotToClipboard", s.screenshotToClipboard)
        put("clipboardNativeFileFallback", s.clipboardNativeFileFallback)
        put("clipboardMaxItems", s.clipboardMaxItems)
        put("clipboardIgnorePasswordFields", s.clipboardIgnorePasswordFields)
        put("clipboardKeepFiles", s.clipboardKeepFiles)
        put("clipboardMaxFileMb", s.clipboardMaxFileMb)
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

        // Every knob, at its current value — so each one is a setting like any other,
        // shown, searched and restored by the same machinery.
        Knobs.ALL.forEach { k -> put(k.key, s.knob(k)) }
    }

    internal fun fromJson(o: JSONObject): Settings {
        val d = Settings()
        val level = SettingsHierarchy.decodeLevel(o)
        return Settings(
            mediaMimePatterns = o.optString("mediaMimePatterns", d.mediaMimePatterns),
            mediaShowHidden = o.optBoolean("mediaShowHidden", d.mediaShowHidden),
            mediaShowFolders = o.optBoolean("mediaShowFolders", d.mediaShowFolders),
            mediaSort = o.optString("mediaSort", d.mediaSort),
            mediaEntryLimit = o.optInt("mediaEntryLimit", d.mediaEntryLimit),
            mediaProviderTimeoutMs = o.optInt("mediaProviderTimeoutMs", d.mediaProviderTimeoutMs),
            settingsLevel = level,
            localSettingsPolicy = enumOf(o.optString("localSettingsPolicy", d.localSettingsPolicy.name), d.localSettingsPolicy),
            toolbarRowsJson = o.optString("toolbarRowsJson", d.toolbarRowsJson),
            expertMode = if (o.has("settingsLevel")) level.includes(SettingsLevel.EXPERT) else o.optBoolean("expertMode", d.expertMode),
            vaultSessionSeconds = o.optInt("vaultSessionSeconds", d.vaultSessionSeconds).coerceIn(15, 60),
            keyboardToolbarVisible = o.optBoolean("keyboardToolbarVisible", d.keyboardToolbarVisible),
            ioActionProfilesJson = o.optString("ioActionProfilesJson", d.ioActionProfilesJson),
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
            floatingSafeBottomDp = o.optDouble("floatingSafeBottomDp", d.floatingSafeBottomDp.toDouble()).toFloat(),
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
            freeArrangeMode = o.optBoolean("freeArrangeMode", d.freeArrangeMode),
            freeKeyPinsJson = o.optString("freeKeyPinsJson", d.freeKeyPinsJson),
            elementPosesJson = o.optString("elementPosesJson", d.elementPosesJson),
            controlValuesJson = o.optString("controlValuesJson", d.controlValuesJson),
            engineWiresJson = o.optString("engineWiresJson", d.engineWiresJson),
            macrosJson = o.optString("macrosJson", d.macrosJson),
            convertLocalOnly = o.optBoolean("convertLocalOnly", d.convertLocalOnly),
            engineStreamsJson = o.optString("engineStreamsJson", d.engineStreamsJson),
            netListenPort = o.optInt("netListenPort", d.netListenPort).coerceIn(0, 65535),
            netToken = o.optString("netToken", d.netToken),
            netAllowCommands = o.optBoolean("netAllowCommands", d.netAllowCommands),
            pocketMode = enumOf(o.optString("pocketMode", d.pocketMode.name), d.pocketMode),
            pocketBlockTouch = o.optBoolean("pocketBlockTouch", d.pocketBlockTouch),
            pocketBlockNavigation = o.optBoolean("pocketBlockNavigation", o.optBoolean("pocketBlockKeys", d.pocketBlockKeys)),
            pocketBlockMediaKeys = o.optBoolean("pocketBlockMediaKeys", d.pocketBlockMediaKeys),
            pocketBlockOtherKeys = o.optBoolean("pocketBlockOtherKeys", o.optBoolean("pocketBlockKeys", d.pocketBlockKeys)),
            keyboardKeepVisible = o.optBoolean("keyboardKeepVisible", d.keyboardKeepVisible),
            pocketBlockKeys = o.optBoolean("pocketBlockKeys", d.pocketBlockKeys),
            pocketUnlockKeys = o.optString("pocketUnlockKeys", d.pocketUnlockKeys),
            pocketUnlockFingers = o.optInt("pocketUnlockFingers", d.pocketUnlockFingers),
            pocketUnlockHoldMs = o.optLong("pocketUnlockHoldMs", d.pocketUnlockHoldMs),
            pocketIgnoreWhenCovered = o.optBoolean("pocketIgnoreWhenCovered", d.pocketIgnoreWhenCovered),
            pocketKeepAwake = o.optBoolean("pocketKeepAwake", d.pocketKeepAwake),
            pocketRestoreApp = o.optBoolean("pocketRestoreApp", d.pocketRestoreApp),
            fieldlessLayoutId = o.optString("fieldlessLayoutId", d.fieldlessLayoutId),
            actionSuggestions = o.optBoolean("actionSuggestions", d.actionSuggestions),
            actionSuggestionCount = o.optInt("actionSuggestionCount", d.actionSuggestionCount),
            learnActions = o.optBoolean("learnActions", d.learnActions),
            actionCommandPrefix = o.optString("actionCommandPrefix", d.actionCommandPrefix),
            ttsEngine = o.optString("ttsEngine", d.ttsEngine),
            ttsLanguage = o.optString("ttsLanguage", d.ttsLanguage),
            ttsVoice = o.optString("ttsVoice", d.ttsVoice),
            ttsRate = o.optDouble("ttsRate", d.ttsRate.toDouble()).toFloat(),
            ttsPitch = o.optDouble("ttsPitch", d.ttsPitch.toDouble()).toFloat(),
            ttsUsage = o.optString("ttsUsage", d.ttsUsage),
            ttsEcho = enumOf(o.optString("ttsEcho", d.ttsEcho.name), d.ttsEcho),
            ttsFollowAlong = o.optBoolean("ttsFollowAlong", d.ttsFollowAlong),
            ttsProvider = o.optString("ttsProvider", d.ttsProvider),
            ttsModel = o.optString("ttsModel", d.ttsModel),
            ttsCloudVoice = o.optString("ttsCloudVoice", d.ttsCloudVoice),
            pocketUnlockPhrase = o.optString("pocketUnlockPhrase", d.pocketUnlockPhrase),
            pocketLockPhrase = o.optString("pocketLockPhrase", d.pocketLockPhrase),
            pocketLockPhraseApps = o.optJSONArray("pocketLockPhraseApps")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            } ?: d.pocketLockPhraseApps,

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
            longPressBoardMs = o.optLong("longPressBoardMs", d.longPressBoardMs),
            longPressAdaptive = o.optBoolean("longPressAdaptive", d.longPressAdaptive),
            repeatStartMs = o.optLong("repeatStartMs", d.repeatStartMs),
            repeatIntervalMs = o.optLong("repeatIntervalMs", d.repeatIntervalMs),
            doubleTapMs = o.optLong("doubleTapMs", d.doubleTapMs),
            swipeThresholdDp = o.optDouble("swipeThresholdDp", d.swipeThresholdDp.toDouble()).toFloat(),

            autoCapitalize = o.optBoolean("autoCapitalize", d.autoCapitalize),
            capitalisationRulesJson = o.optString("capitalisationRulesJson", d.capitalisationRulesJson),
            properNouns = o.optJSONArray("properNouns")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            } ?: d.properNouns,
            learnProperNouns = o.optBoolean("learnProperNouns", d.learnProperNouns),
            cursorMagnet = enumOf(o.optString("cursorMagnet", d.cursorMagnet.name), d.cursorMagnet),
            cursorMagnetReach = o.optInt("cursorMagnetReach", d.cursorMagnetReach),
            cursorMagnetPauseMs = o.optLong("cursorMagnetPauseMs", d.cursorMagnetPauseMs),
            cursorMagnetStride = o.optInt("cursorMagnetStride", d.cursorMagnetStride),
            providerProfilesJson = o.optString("providerProfilesJson", d.providerProfilesJson),
            wordListSourcesJson = o.optString("wordListSourcesJson", d.wordListSourcesJson),
            doubleSpacePeriod = o.optBoolean("doubleSpacePeriod", d.doubleSpacePeriod),
            autoSpaceAfterPunctuation = o.optBoolean("autoSpaceAfterPunctuation", d.autoSpaceAfterPunctuation),
            smartQuotes = o.optBoolean("smartQuotes", d.smartQuotes),
            spaceSlideCursor = o.optBoolean("spaceSlideCursor", d.spaceSlideCursor),
            backspaceSwipeDeletesWord = o.optBoolean("backspaceSwipeDeletesWord", d.backspaceSwipeDeletesWord),
            keyPreviewPopup = o.optBoolean("keyPreviewPopup", d.keyPreviewPopup),
            longPressPopup = o.optBoolean("longPressPopup", d.longPressPopup),
            flickInput = o.optBoolean("flickInput", d.flickInput),
            shiftOneShot = o.optBoolean("shiftOneShot", d.shiftOneShot),
            symbolBoardColumns = o.optInt("symbolBoardColumns", d.symbolBoardColumns),
            popupTabMemoryJson = o.optString("popupTabMemoryJson", d.popupTabMemoryJson),
            popupPinnedTabsJson = o.optString("popupPinnedTabsJson", d.popupPinnedTabsJson),

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
            completionEnabled = o.optBoolean("completionEnabled", d.completionEnabled),
            completionProvider = o.optString("completionProvider", d.completionProvider),
            completionModel = o.optString("completionModel", d.completionModel),
            completionApiKey = o.optString("completionApiKey", d.completionApiKey),
            completionMinChars = o.optInt("completionMinChars", d.completionMinChars),
            completionDebounceMs = o.optLong("completionDebounceMs", d.completionDebounceMs),
            completionMaxTokens = o.optInt("completionMaxTokens", d.completionMaxTokens),
            completionTemperature = o.optDouble("completionTemperature", d.completionTemperature.toDouble()).toFloat(),
            completionTokenFloor = o.optDouble("completionTokenFloor", d.completionTokenFloor.toDouble()).toFloat(),
            completionSurpriseBudget = o.optDouble("completionSurpriseBudget", d.completionSurpriseBudget.toDouble()).toFloat(),
            completionMaxChars = o.optInt("completionMaxChars", d.completionMaxChars),
            completionMaxMillis = o.optLong("completionMaxMillis", d.completionMaxMillis),
            completionAcceptedScope = o.optString("completionAcceptedScope", d.completionAcceptedScope),
            completionReserveRow = o.optBoolean("completionReserveRow", d.completionReserveRow),
            catalogUrl = o.optString("catalogUrl", d.catalogUrl),
            fetchedProvidersJson = o.optString("fetchedProvidersJson", d.fetchedProvidersJson),
            catalogFetchedAt = o.optLong("catalogFetchedAt", d.catalogFetchedAt),
            setupDone = o.optBoolean("setupDone", d.setupDone),

            asrEngine = o.optString("asrEngine", d.asrEngine),
            asrProvider = o.optString("asrProvider", d.asrProvider),
            asrRemoteUrl = o.optString("asrRemoteUrl", d.asrRemoteUrl),
            asrApiKey = o.optString("asrApiKey", d.asrApiKey),
            asrModel = o.optString("asrModel", d.asrModel),
            asrLanguage = o.optString("asrLanguage", d.asrLanguage),
            pasteConvert = o.optBoolean("pasteConvert", d.pasteConvert),
            ocrProvider = o.optString("ocrProvider", d.ocrProvider),
            ocrModel = o.optString("ocrModel", d.ocrModel),
            asrShowAlternatives = o.optBoolean("asrShowAlternatives", d.asrShowAlternatives),
            asrAlternativeCount = o.optInt("asrAlternativeCount", d.asrAlternativeCount),
            asrAutoCommitBest = o.optBoolean("asrAutoCommitBest", d.asrAutoCommitBest),

            clipboardEnabled = o.optBoolean("clipboardEnabled", d.clipboardEnabled),
            clipboardRetentionDays = o.optInt("clipboardRetentionDays", d.clipboardRetentionDays),
            syncClipboard = o.optBoolean("syncClipboard", d.syncClipboard),
            syncSettings = o.optBoolean("syncSettings", d.syncSettings),
            syncImages = o.optBoolean("syncImages", d.syncImages),
            syncPropagatePruning = o.optBoolean("syncPropagatePruning", d.syncPropagatePruning),
            syncMaxItems = o.optInt("syncMaxItems", d.syncMaxItems).coerceIn(10, 1000),
            syncMaxImageMb = o.optInt("syncMaxImageMb", d.syncMaxImageMb).coerceIn(1, 8),
            clipboardTrashHours = o.optInt("clipboardTrashHours", d.clipboardTrashHours).coerceIn(1, 168),
            screenshotToClipboard = o.optBoolean("screenshotToClipboard", d.screenshotToClipboard),
            clipboardNativeFileFallback = o.optBoolean("clipboardNativeFileFallback", d.clipboardNativeFileFallback),
            clipboardMaxItems = o.optInt("clipboardMaxItems", d.clipboardMaxItems),
            clipboardIgnorePasswordFields = o.optBoolean("clipboardIgnorePasswordFields", d.clipboardIgnorePasswordFields),
            clipboardKeepFiles = o.optBoolean("clipboardKeepFiles", d.clipboardKeepFiles),
            clipboardMaxFileMb = o.optInt("clipboardMaxFileMb", d.clipboardMaxFileMb),
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
            discoveredModelsJson = o.optString("discoveredModelsJson", d.discoveredModelsJson),
            // Only what differs from the designed default is kept, so a later build
            // that improves a default still reaches everyone who never touched it.
            knobs = Knobs.ALL.mapNotNull { k ->
                val v = o.optDouble(k.key)
                if (v.isNaN() || v == k.default) null else k.id to v.coerceIn(k.min, k.max)
            }.toMap()
        )
    }
}
