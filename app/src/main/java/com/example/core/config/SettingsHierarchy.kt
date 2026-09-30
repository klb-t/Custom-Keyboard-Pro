package com.example.core.config

import org.json.JSONObject

/** Presentation levels never discard values or enable a phone capability. */
enum class SettingsLevel(val title: String, val explanation: String) {
    BASIC("Basic", "Everyday keyboard choices: layout, size, theme, typing and feedback."),
    ADVANCED("Advanced", "Phone tools, layout authoring, model setup and detailed behavior."),
    EXPERT("Expert", "Every persisted user setting, with its actual owner and reset scope."),
    DEBUGGER("Debugger", "Expert settings plus a separate inspector of published runtime variables.");

    fun includes(required: SettingsLevel): Boolean = ordinal >= required.ordinal
}

/** Ownership describes the existing consumer, not a proposed override mechanism. */
enum class SettingsOwner(val title: String, val explanation: String) {
    APPLICATION("Application", "Shared behavior and services on this device."),
    KEYBOARD_DEFAULTS("Keyboard defaults", "Shared rendering/interaction defaults used by layouts. Layout elements can add their own properties."),
    INSTANCE_STORAGE("Instance state", "A collection of saved positions or choices. Edit a selected layout, panel or key in its context.");
}

object SettingsHierarchy {
    /** These settings control the shared toolbar/viewport, which has one actual panel owner. */
    private val mainPanelKeys = setOf(
        "toolbarRowsJson", Knobs.PREFIX + "viewportMinimumKeysDp", Knobs.PREFIX + "viewportMinimumKeysFraction",
        Knobs.PREFIX + "toolbarRowHeightDp", Knobs.PREFIX + "keyboardMaxScreenFraction"
    )
    fun requiresMainPanelOwner(key: String): Boolean = key in mainPanelKeys

    /** Coarse scope metadata plus structural eligibility from the actual renderer's owner. */
    fun appliesTo(layout: com.example.core.layout.LayoutDef, address: SettingsAddress, setting: String): Boolean {
        if (layout.id != address.layoutId) return false
        val spec = SettingsSchema.spec(setting) ?: return false
        if (address.scope !in spec.applicableScopes) return false
        return !requiresMainPanelOwner(setting) || address.scope != SettingsScope.PANEL ||
            address.panelId == com.example.core.layout.ToolbarRows.ownerPanelId(layout)
    }
    /** A deliberately small standard keyboard surface; the complete surface remains Expert. */
    val basicKeys = setOf(
        "settingsLevel", "themeId", "activeLayoutId", "enabledLayoutIds", "heightPortrait", "heightLandscape",
        "presentation", "keyTextScale", "hapticEnabled", "soundEnabled", "soundVolume", "autoCapitalize",
        "doubleSpacePeriod", "autoCorrect", "suggestionsEnabled", "learnFromTyping", "clipboardEnabled",
        "asrLanguage", "showOnHardwareKeyboard"
    )
    private val keyboardDefaults = setOf(
        "themeId", "presentation", "heightPortrait", "heightLandscape", "widthFraction", "bottomPaddingDp",
        "sidePaddingDp", "keyGapDp", "keyCornerDp", "keyTextScale", "splitGapFraction", "floatingX", "floatingY",
        "floatingWidthDp", "floatingHeightDp", "floatingSafeBottomDp", "separateLandscapeSize", "keyboardOpacity",
        "panelOpacity", "keyOpacity", "keyBorderWidthDp", "keyBorderOpacity", "keyLabelOpacity", "freeKeyScale",
        "freeSpreadX", "freeSpreadY", "freeOriginXDp", "freeOriginYDp", "freeKeysDraggable", "freeShowGuides",
        "freeArrangeMode", "insetsMode", "cursorAvoidStrategy", "avoidCoveringCursor", "cursorAvoidMarginDp",
        "cursorAvoidFadeTo", "indicatorsEnabled", "indicatorStripVisible", "indicatorOnColor", "indicatorOffColor",
        "keyboardToolbarVisible", "hapticEnabled", "hapticMs", "hapticAmplitude", "soundEnabled", "soundVolume",
        "longPressMs", "repeatStartMs", "repeatIntervalMs", "doubleTapMs", "swipeThresholdDp",
        "flickInput", "shiftOneShot", "symbolBoardColumns"
    )
    private val instanceStorage = setOf(
        "freeKeyPinsJson", "elementPosesJson", "controlValuesJson", "popupTabMemoryJson", "popupPinnedTabsJson"
    )
    fun owner(key: String): SettingsOwner = when (key) {
        in instanceStorage -> SettingsOwner.INSTANCE_STORAGE
        in keyboardDefaults, in cascadingKeys, in mainPanelKeys, "localSettingsPolicy" -> SettingsOwner.KEYBOARD_DEFAULTS
        else -> SettingsOwner.APPLICATION
    }

    /** Only options with a local keyboard consumer may cascade. Permissions/secrets stay shared. */
    private val cascadingKeys = setOf(
        "autoCapitalize", "capitalisationRulesJson", "doubleSpacePeriod", "autoSpaceAfterPunctuation", "smartQuotes",
        "spaceSlideCursor", "backspaceSwipeDeletesWord", "keyPreviewPopup", "longPressPopup", "flickInput", "shiftOneShot",
        "hapticEnabled", "hapticMs", "hapticAmplitude", "soundEnabled", "soundVolume", "longPressMs", "longPressBoardMs",
        "longPressAdaptive", "repeatStartMs", "repeatIntervalMs", "doubleTapMs", "swipeThresholdDp", "keyGapDp", "keyCornerDp",
        "keyTextScale", "keyboardOpacity", "panelOpacity", "keyOpacity", "keyBorderWidthDp", "keyBorderOpacity",
        "keyLabelOpacity", "indicatorsEnabled", "indicatorOnColor", "indicatorOffColor"
    )
    fun applicableScopes(key: String): Set<SettingsScope> = when {
        requiresMainPanelOwner(key) -> java.util.Collections.unmodifiableSet(setOf(SettingsScope.KEYBOARD_DEFAULTS, SettingsScope.LAYOUT, SettingsScope.PANEL))
        key in cascadingKeys -> java.util.Collections.unmodifiableSet(SettingsScope.entries.toSet())
        else -> java.util.Collections.unmodifiableSet(setOf(SettingsScope.KEYBOARD_DEFAULTS))
    }

    fun minimumLevel(key: String, wasExpert: Boolean): SettingsLevel = when {
        key in basicKeys -> SettingsLevel.BASIC
        key == "toolbarRowsJson" -> SettingsLevel.ADVANCED
        key == "expertMode" || key in instanceStorage || key.endsWith("Json") || key.startsWith(Knobs.PREFIX) || wasExpert -> SettingsLevel.EXPERT
        else -> SettingsLevel.ADVANCED
    }

    /** Existing installations retain their old surface; fresh installations start Basic. */
    fun decodeLevel(json: JSONObject): SettingsLevel {
        if (json.has("settingsLevel")) return SettingsLevel.entries.firstOrNull {
            it.name.equals(json.optString("settingsLevel"), true)
        } ?: SettingsLevel.BASIC
        return when {
            json.optBoolean("expertMode", false) -> SettingsLevel.EXPERT
            json.has("expertMode") -> SettingsLevel.ADVANCED
            else -> SettingsLevel.BASIC
        }
    }

    fun selectLevel(settings: Settings, level: SettingsLevel): Settings =
        settings.copy(settingsLevel = level, expertMode = level.includes(SettingsLevel.EXPERT))

    /** Direct legacy callers remain compatible; the selector writes both fields. */
    fun level(settings: Settings): SettingsLevel = if (settings.expertMode && !settings.settingsLevel.includes(SettingsLevel.EXPERT))
        SettingsLevel.EXPERT else settings.settingsLevel

    fun visible(settings: Settings, query: String = "", owner: SettingsOwner? = null): List<SettingSpec> =
        SettingsSchema.search(query).filter { level(settings).includes(it.minimumLevel) && (owner == null || it.owner == owner) }

    fun hidden(settings: Settings, query: String, owner: SettingsOwner? = null): List<SettingSpec> =
        SettingsSchema.search(query).filter { !level(settings).includes(it.minimumLevel) && (owner == null || it.owner == owner) }

    /** Reset exactly a reviewed selection. A stale or unknown key fails as a whole. */
    fun reset(settings: Settings, keys: Collection<String>): Result<Settings> = runCatching {
        val specs = keys.distinct().map { SettingsSchema.spec(it) ?: error("Unknown setting: $it") }
        specs.fold(settings) { current, spec -> SettingsSchema.withValue(current, spec.key, spec.default) }
    }
}
