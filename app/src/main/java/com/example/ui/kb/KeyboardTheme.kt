package com.example.ui.kb

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

/**
 * Colours and metrics for the key surface.
 *
 * Deliberately a flat bag of colours rather than a Material colour scheme: a keyboard
 * is one dense surface whose contrast has to be controlled directly, and a user
 * writing their own theme should be able to see every colour they can change in one
 * list.
 */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val keyBackground: Color,
    val keySpecialBackground: Color,
    val keyModifierBackground: Color,
    val keyAccentBackground: Color,
    val keyPressedBackground: Color,
    val keyActiveBackground: Color,
    val keyText: Color,
    val keySpecialText: Color,
    val keyHintText: Color,
    val keyBorder: Color,
    val stripBackground: Color,
    val stripText: Color,
    val stripAiText: Color,
    val popupBackground: Color,
    val popupText: Color,
    val popupSelected: Color,
    val indicatorOn: Color,
    val indicatorOff: Color,
    val borderWidthDp: Float = 0f
) {
    fun keyBackgroundFor(style: String?): Color = when (style) {
        "special" -> keySpecialBackground
        "modifier" -> keyModifierBackground
        "accent" -> keyAccentBackground
        else -> keyBackground
    }

    fun keyTextFor(style: String?): Color = when (style) {
        "special", "modifier" -> keySpecialText
        else -> keyText
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("dark", isDark)
        .put("background", hex(background))
        .put("keyBackground", hex(keyBackground))
        .put("keySpecialBackground", hex(keySpecialBackground))
        .put("keyModifierBackground", hex(keyModifierBackground))
        .put("keyAccentBackground", hex(keyAccentBackground))
        .put("keyPressedBackground", hex(keyPressedBackground))
        .put("keyActiveBackground", hex(keyActiveBackground))
        .put("keyText", hex(keyText))
        .put("keySpecialText", hex(keySpecialText))
        .put("keyHintText", hex(keyHintText))
        .put("keyBorder", hex(keyBorder))
        .put("stripBackground", hex(stripBackground))
        .put("stripText", hex(stripText))
        .put("stripAiText", hex(stripAiText))
        .put("popupBackground", hex(popupBackground))
        .put("popupText", hex(popupText))
        .put("popupSelected", hex(popupSelected))
        .put("indicatorOn", hex(indicatorOn))
        .put("indicatorOff", hex(indicatorOff))
        .put("borderWidthDp", borderWidthDp.toDouble())

    companion object {
        fun hex(c: Color): String = "#%08X".format(c.toArgb())

        private fun color(raw: String?, fallback: Color): Color {
            val parsed = com.example.core.layout.LayoutJson.parseColor(raw) ?: return fallback
            return Color(parsed.toInt())
        }

        fun fromJson(o: JSONObject, base: KeyboardTheme = BuiltinThemes.DARK): KeyboardTheme =
            KeyboardTheme(
                id = o.optString("id", base.id),
                name = o.optString("name", base.name),
                isDark = o.optBoolean("dark", base.isDark),
                background = color(o.optString("background"), base.background),
                keyBackground = color(o.optString("keyBackground"), base.keyBackground),
                keySpecialBackground = color(o.optString("keySpecialBackground"), base.keySpecialBackground),
                keyModifierBackground = color(o.optString("keyModifierBackground"), base.keyModifierBackground),
                keyAccentBackground = color(o.optString("keyAccentBackground"), base.keyAccentBackground),
                keyPressedBackground = color(o.optString("keyPressedBackground"), base.keyPressedBackground),
                keyActiveBackground = color(o.optString("keyActiveBackground"), base.keyActiveBackground),
                keyText = color(o.optString("keyText"), base.keyText),
                keySpecialText = color(o.optString("keySpecialText"), base.keySpecialText),
                keyHintText = color(o.optString("keyHintText"), base.keyHintText),
                keyBorder = color(o.optString("keyBorder"), base.keyBorder),
                stripBackground = color(o.optString("stripBackground"), base.stripBackground),
                stripText = color(o.optString("stripText"), base.stripText),
                stripAiText = color(o.optString("stripAiText"), base.stripAiText),
                popupBackground = color(o.optString("popupBackground"), base.popupBackground),
                popupText = color(o.optString("popupText"), base.popupText),
                popupSelected = color(o.optString("popupSelected"), base.popupSelected),
                indicatorOn = color(o.optString("indicatorOn"), base.indicatorOn),
                indicatorOff = color(o.optString("indicatorOff"), base.indicatorOff),
                borderWidthDp = o.optDouble("borderWidthDp", base.borderWidthDp.toDouble()).toFloat()
            )
    }
}

object BuiltinThemes {

    val DARK = KeyboardTheme(
        id = "dark", name = "Dark", isDark = true,
        background = Color(0xFF17181C),
        keyBackground = Color(0xFF2E3038),
        keySpecialBackground = Color(0xFF23242A),
        keyModifierBackground = Color(0xFF23242A),
        keyAccentBackground = Color(0xFF3A6DF0),
        keyPressedBackground = Color(0xFF4A4D57),
        keyActiveBackground = Color(0xFF3A6DF0),
        keyText = Color(0xFFF2F3F5),
        keySpecialText = Color(0xFFC7CAD1),
        keyHintText = Color(0x99C7CAD1),
        keyBorder = Color(0x22FFFFFF),
        stripBackground = Color(0xFF1D1E23),
        stripText = Color(0xFFE6E8EC),
        stripAiText = Color(0xFF8AB4FF),
        popupBackground = Color(0xFF2B2D35),
        popupText = Color(0xFFF2F3F5),
        popupSelected = Color(0xFF3A6DF0),
        indicatorOn = Color(0xFF4ADE80),
        indicatorOff = Color(0x22FFFFFF)
    )

    val LIGHT = KeyboardTheme(
        id = "light", name = "Light", isDark = false,
        background = Color(0xFFE8EAEF),
        keyBackground = Color(0xFFFFFFFF),
        keySpecialBackground = Color(0xFFD3D7DE),
        keyModifierBackground = Color(0xFFD3D7DE),
        keyAccentBackground = Color(0xFF2F6BE0),
        keyPressedBackground = Color(0xFFBFC5CF),
        keyActiveBackground = Color(0xFF2F6BE0),
        keyText = Color(0xFF14161A),
        keySpecialText = Color(0xFF2B2F36),
        keyHintText = Color(0x99424852),
        keyBorder = Color(0x14000000),
        stripBackground = Color(0xFFDDE0E6),
        stripText = Color(0xFF14161A),
        stripAiText = Color(0xFF1B4FBF),
        popupBackground = Color(0xFFFFFFFF),
        popupText = Color(0xFF14161A),
        popupSelected = Color(0xFF2F6BE0),
        indicatorOn = Color(0xFF16A34A),
        indicatorOff = Color(0x22000000)
    )

    /** True black, for OLED panels and for typing at night. */
    val AMOLED = DARK.copy(
        id = "amoled", name = "AMOLED black",
        background = Color(0xFF000000),
        keyBackground = Color(0xFF101014),
        keySpecialBackground = Color(0xFF08080A),
        keyModifierBackground = Color(0xFF08080A),
        stripBackground = Color(0xFF000000),
        keyBorder = Color(0x33FFFFFF),
        borderWidthDp = 0.5f
    )

    /** Green on black, for people who spend their day in a terminal. */
    val TERMINAL = KeyboardTheme(
        id = "terminal", name = "Terminal", isDark = true,
        background = Color(0xFF04120A),
        keyBackground = Color(0xFF0A2314),
        keySpecialBackground = Color(0xFF06180E),
        keyModifierBackground = Color(0xFF06180E),
        keyAccentBackground = Color(0xFF1F7A3F),
        keyPressedBackground = Color(0xFF17512C),
        keyActiveBackground = Color(0xFF1F7A3F),
        keyText = Color(0xFF6EE787),
        keySpecialText = Color(0xFF3FB950),
        keyHintText = Color(0x993FB950),
        keyBorder = Color(0x333FB950),
        stripBackground = Color(0xFF04120A),
        stripText = Color(0xFF6EE787),
        stripAiText = Color(0xFF56D4DD),
        popupBackground = Color(0xFF0A2314),
        popupText = Color(0xFF6EE787),
        popupSelected = Color(0xFF1F7A3F),
        indicatorOn = Color(0xFF56D4DD),
        indicatorOff = Color(0x223FB950),
        borderWidthDp = 0.5f
    )

    /** Maximum contrast, large borders: for low vision and for bright sunlight. */
    val HIGH_CONTRAST = KeyboardTheme(
        id = "high_contrast", name = "High contrast", isDark = true,
        background = Color(0xFF000000),
        keyBackground = Color(0xFF000000),
        keySpecialBackground = Color(0xFF1A1A1A),
        keyModifierBackground = Color(0xFF1A1A1A),
        keyAccentBackground = Color(0xFFFFD400),
        keyPressedBackground = Color(0xFFFFD400),
        keyActiveBackground = Color(0xFFFFD400),
        keyText = Color(0xFFFFFFFF),
        keySpecialText = Color(0xFFFFFFFF),
        keyHintText = Color(0xFFFFD400),
        keyBorder = Color(0xFFFFFFFF),
        stripBackground = Color(0xFF000000),
        stripText = Color(0xFFFFFFFF),
        stripAiText = Color(0xFFFFD400),
        popupBackground = Color(0xFF000000),
        popupText = Color(0xFFFFFFFF),
        popupSelected = Color(0xFFFFD400),
        indicatorOn = Color(0xFFFFD400),
        indicatorOff = Color(0xFF404040),
        borderWidthDp = 2f
    )

    val PAPER = KeyboardTheme(
        id = "paper", name = "Paper", isDark = false,
        background = Color(0xFFF3EEE3),
        keyBackground = Color(0xFFFDFBF6),
        keySpecialBackground = Color(0xFFE4DCCB),
        keyModifierBackground = Color(0xFFE4DCCB),
        keyAccentBackground = Color(0xFF9A6B3F),
        keyPressedBackground = Color(0xFFD8CDB6),
        keyActiveBackground = Color(0xFF9A6B3F),
        keyText = Color(0xFF3A3226),
        keySpecialText = Color(0xFF5A5042),
        keyHintText = Color(0x996B6152),
        keyBorder = Color(0x1A3A3226),
        stripBackground = Color(0xFFEDE6D8),
        stripText = Color(0xFF3A3226),
        stripAiText = Color(0xFF7A4F24),
        popupBackground = Color(0xFFFDFBF6),
        popupText = Color(0xFF3A3226),
        popupSelected = Color(0xFF9A6B3F),
        indicatorOn = Color(0xFF2E7D32),
        indicatorOff = Color(0x223A3226)
    )

    val ALL = listOf(DARK, LIGHT, AMOLED, TERMINAL, HIGH_CONTRAST, PAPER)

    fun byId(id: String): KeyboardTheme = ALL.firstOrNull { it.id == id } ?: DARK
}
