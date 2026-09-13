package com.example.ui.kb

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * One colour of a theme, addressable by name.
 *
 * The editor renders this list rather than twenty hand-written rows, for the same
 * reason the settings screen renders a schema: a colour added to [KeyboardTheme]
 * should appear in the editor because it exists, not because someone remembered.
 */
data class ThemeField(
    val key: String,
    val label: String,
    val help: String? = null,
    val get: (KeyboardTheme) -> Color,
    val set: (KeyboardTheme, Color) -> KeyboardTheme
)

/**
 * Themes the user made, alongside the built-in ones.
 *
 * [KeyboardTheme] could already read and write itself as JSON — that was written and
 * then never called by anything, so a theme was in practice one of five fixed
 * choices. This is the other half: custom themes persist as data in settings, merge
 * over the built-ins by id, and are what the keyboard actually resolves against.
 *
 * Transparency comes free with that, and is the reason it matters here: every colour
 * carries an alpha channel, so "keys you can see through" is a theme someone can save
 * and share rather than a feature someone has to implement.
 */
object ThemeStore {

    fun custom(settings: Settings = SettingsStore.current): List<KeyboardTheme> {
        val raw = settings.customThemesJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(LayoutJson.stripCodeFenceArray(raw))
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { KeyboardTheme.fromJson(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Custom themes first, so saving one under a built-in id overrides that built-in. */
    fun allThemes(settings: Settings = SettingsStore.current): List<KeyboardTheme> {
        val merged = LinkedHashMap<String, KeyboardTheme>()
        (custom(settings) + BuiltinThemes.ALL).forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    /** What the keyboard should actually draw with. */
    fun resolve(settings: Settings = SettingsStore.current): KeyboardTheme =
        allThemes(settings).firstOrNull { it.id == settings.themeId } ?: BuiltinThemes.byId(settings.themeId)

    fun byId(id: String, settings: Settings = SettingsStore.current): KeyboardTheme =
        allThemes(settings).firstOrNull { it.id == id } ?: BuiltinThemes.byId(id)

    fun save(theme: KeyboardTheme) {
        SettingsStore.update { s ->
            val kept = custom(s).filterNot { it.id == theme.id }
            val arr = JSONArray().apply { (kept + theme).forEach { put(it.toJson()) } }
            s.copy(customThemesJson = arr.toString())
        }
    }

    fun delete(id: String) {
        SettingsStore.update { s ->
            val kept = custom(s).filterNot { it.id == id }
            val arr = JSONArray().apply { kept.forEach { put(it.toJson()) } }
            s.copy(
                customThemesJson = arr.toString(),
                themeId = if (s.themeId == id) BuiltinThemes.DARK.id else s.themeId
            )
        }
    }

    fun parseOne(raw: String, base: KeyboardTheme = BuiltinThemes.DARK): Result<KeyboardTheme> = runCatching {
        KeyboardTheme.fromJson(JSONObject(LayoutJson.stripCodeFence(raw)), base)
    }

    /** A new id that will not collide with a built-in or an existing custom theme. */
    fun freshId(preferred: String, settings: Settings = SettingsStore.current): String {
        val slug = preferred.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "theme" }
        val taken = allThemes(settings).map { it.id }.toSet()
        if (slug !in taken) return slug
        var n = 2
        while ("${slug}_$n" in taken) n++
        return "${slug}_$n"
    }

    /**
     * Every editable colour. Adding a colour to [KeyboardTheme] means adding one line
     * here, and it appears in the editor, in the model's prompt and in the export.
     */
    val FIELDS: List<ThemeField> = listOf(
        ThemeField("background", "Panel background",
            "Behind the keys. Set its opacity to 0 for keys that float over the app.",
            { it.background }, { t, c -> t.copy(background = c) }),
        ThemeField("keyBackground", "Key face",
            "The ordinary key. Lower its opacity to see through the keys themselves.",
            { it.keyBackground }, { t, c -> t.copy(keyBackground = c) }),
        ThemeField("keySpecialBackground", "Special key face", null,
            { it.keySpecialBackground }, { t, c -> t.copy(keySpecialBackground = c) }),
        ThemeField("keyModifierBackground", "Modifier key face", null,
            { it.keyModifierBackground }, { t, c -> t.copy(keyModifierBackground = c) }),
        ThemeField("keyAccentBackground", "Accent key face", null,
            { it.keyAccentBackground }, { t, c -> t.copy(keyAccentBackground = c) }),
        ThemeField("keyPressedBackground", "Key while pressed", null,
            { it.keyPressedBackground }, { t, c -> t.copy(keyPressedBackground = c) }),
        ThemeField("keyActiveBackground", "Key while active or locked", null,
            { it.keyActiveBackground }, { t, c -> t.copy(keyActiveBackground = c) }),
        ThemeField("keyText", "Key label", null, { it.keyText }, { t, c -> t.copy(keyText = c) }),
        ThemeField("keySpecialText", "Special key label", null,
            { it.keySpecialText }, { t, c -> t.copy(keySpecialText = c) }),
        ThemeField("keyHintText", "Hint glyph",
            "The small mark showing what a swipe or long press would give you.",
            { it.keyHintText }, { t, c -> t.copy(keyHintText = c) }),
        ThemeField("keyBorder", "Key outline",
            "Only drawn when the outline width is above zero — which is what keeps " +
                "transparent keys findable.",
            { it.keyBorder }, { t, c -> t.copy(keyBorder = c) }),
        ThemeField("stripBackground", "Suggestion strip", null,
            { it.stripBackground }, { t, c -> t.copy(stripBackground = c) }),
        ThemeField("stripText", "Suggestion text", null, { it.stripText }, { t, c -> t.copy(stripText = c) }),
        ThemeField("stripAiText", "Suggestion text from the model", null,
            { it.stripAiText }, { t, c -> t.copy(stripAiText = c) }),
        ThemeField("popupBackground", "Popup background", null,
            { it.popupBackground }, { t, c -> t.copy(popupBackground = c) }),
        ThemeField("popupText", "Popup text", null, { it.popupText }, { t, c -> t.copy(popupText = c) }),
        ThemeField("popupSelected", "Popup selection", null,
            { it.popupSelected }, { t, c -> t.copy(popupSelected = c) }),
        ThemeField("indicatorOn", "Indicator lamp — on", null,
            { it.indicatorOn }, { t, c -> t.copy(indicatorOn = c) }),
        ThemeField("indicatorOff", "Indicator lamp — off", null,
            { it.indicatorOff }, { t, c -> t.copy(indicatorOff = c) })
    )

    fun argbOf(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    fun colorOf(argb: Long): Color = Color(argb.toInt())
}
