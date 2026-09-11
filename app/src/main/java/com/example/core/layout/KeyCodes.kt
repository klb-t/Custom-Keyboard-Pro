package com.example.core.layout

import android.view.KeyEvent

/**
 * Symbolic names for raw Android key codes, so layout JSON can say `"KEYCODE_TAB"`
 * instead of `61`. Numeric codes still parse, and anything the platform knows about
 * is accepted even when it is not in the curated list below.
 */
object KeyCodes {

    /** Curated, ordered list offered in the key editor's picker. */
    val COMMON: List<Pair<String, Int>> = listOf(
        "Tab" to KeyEvent.KEYCODE_TAB,
        "Escape" to KeyEvent.KEYCODE_ESCAPE,
        "Enter" to KeyEvent.KEYCODE_ENTER,
        "Backspace" to KeyEvent.KEYCODE_DEL,
        "Forward delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "Insert" to KeyEvent.KEYCODE_INSERT,
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "End" to KeyEvent.KEYCODE_MOVE_END,
        "Page up" to KeyEvent.KEYCODE_PAGE_UP,
        "Page down" to KeyEvent.KEYCODE_PAGE_DOWN,
        "Arrow up" to KeyEvent.KEYCODE_DPAD_UP,
        "Arrow down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "Arrow left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "Arrow right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "Caps lock" to KeyEvent.KEYCODE_CAPS_LOCK,
        "Num lock" to KeyEvent.KEYCODE_NUM_LOCK,
        "Scroll lock" to KeyEvent.KEYCODE_SCROLL_LOCK,
        "Print screen / SysRq" to KeyEvent.KEYCODE_SYSRQ,
        "Break / Pause" to KeyEvent.KEYCODE_BREAK,
        "Menu" to KeyEvent.KEYCODE_MENU,
        "Back" to KeyEvent.KEYCODE_BACK,
        "Search" to KeyEvent.KEYCODE_SEARCH,
        "Volume up" to KeyEvent.KEYCODE_VOLUME_UP,
        "Volume down" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "Media play/pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "Media next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "Media previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "Cut" to KeyEvent.KEYCODE_CUT,
        "Copy" to KeyEvent.KEYCODE_COPY,
        "Paste" to KeyEvent.KEYCODE_PASTE,
        "Space" to KeyEvent.KEYCODE_SPACE
    ) + (1..12).map { "F$it" to (KeyEvent.KEYCODE_F1 + it - 1) } +
        ('a'..'z').map { it.uppercase() to (KeyEvent.KEYCODE_A + (it - 'a')) } +
        ('0'..'9').map { it.toString() to (KeyEvent.KEYCODE_0 + (it - '0')) }

    /** Canonical `KEYCODE_*` name for a code, or null when the platform has no name. */
    fun name(code: Int): String? {
        if (code <= 0) return null
        val n = KeyEvent.keyCodeToString(code)
        return if (n.startsWith("KEYCODE_")) n else null
    }

    /** Accepts `KEYCODE_TAB`, `TAB`, `tab`, or a decimal number. */
    fun code(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        s.toIntOrNull()?.let { return it }
        val upper = s.uppercase().replace(' ', '_').replace('-', '_')
        val withPrefix = if (upper.startsWith("KEYCODE_")) upper else "KEYCODE_$upper"
        val resolved = try {
            KeyEvent.keyCodeFromString(withPrefix)
        } catch (e: Exception) {
            KeyEvent.KEYCODE_UNKNOWN
        }
        if (resolved != KeyEvent.KEYCODE_UNKNOWN) return resolved
        return COMMON.firstOrNull { it.first.equals(s, ignoreCase = true) }?.second
    }

    /** Human label for the editor. Falls back to the symbolic name. */
    fun label(code: Int): String =
        COMMON.firstOrNull { it.second == code }?.first
            ?: name(code)?.removePrefix("KEYCODE_")?.replace('_', ' ')?.lowercase()
            ?: code.toString()
}

/** Modifier bit helpers for [KeyAction.SendKey]. */
object MetaCodes {

    val ALL: List<Pair<String, Int>> = listOf(
        "shift" to KeyEvent.META_SHIFT_ON,
        "ctrl" to KeyEvent.META_CTRL_ON,
        "alt" to KeyEvent.META_ALT_ON,
        "meta" to KeyEvent.META_META_ON,
        "sym" to KeyEvent.META_SYM_ON,
        "fn" to KeyEvent.META_FUNCTION_ON,
        "caps_lock" to KeyEvent.META_CAPS_LOCK_ON,
        "num_lock" to KeyEvent.META_NUM_LOCK_ON,
        "scroll_lock" to KeyEvent.META_SCROLL_LOCK_ON
    )

    /** Parses `"ctrl"`, `"ctrl+shift"`, `"ctrl|shift"`, `"CTRL_ON"` or a number. */
    fun parse(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        raw.trim().toIntOrNull()?.let { return it }
        return raw.split('+', '|', ',', ' ')
            .map { it.trim().lowercase().removePrefix("meta_").removeSuffix("_on") }
            .filter { it.isNotEmpty() }
            .fold(0) { acc, token -> acc or (ALL.firstOrNull { it.first == token }?.second ?: 0) }
    }

    fun describe(meta: Int): String =
        ALL.filter { meta and it.second != 0 }.joinToString("+") { it.first }
}
