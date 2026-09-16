package com.example.ui.kb

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Named icons a layout can ask for by string, so `"icon": "backspace"` in JSON draws
 * the right glyph without the layout format knowing anything about Compose.
 *
 * Names with no vector fall back to a typographic glyph, which keeps the set open:
 * an unknown name never produces a blank key.
 */
object KeyIcons {

    fun vector(name: String?): ImageVector? = when (name?.lowercase()) {
        "backspace", "delete" -> Icons.AutoMirrored.Filled.Backspace
        "enter", "return" -> Icons.AutoMirrored.Filled.KeyboardReturn
        "space" -> Icons.Filled.SpaceBar
        "globe", "language" -> Icons.Filled.Language
        "mic", "voice" -> Icons.Filled.Mic
        "settings" -> Icons.Filled.Settings
        "close", "hide" -> Icons.Filled.Close
        "search" -> Icons.Filled.Search
        else -> null
    }

    /** Typographic stand-in for names with no vector. */
    fun glyph(name: String?): String? = when (name?.lowercase()) {
        "shift" -> "⇧"
        "shift_lock", "capslock" -> "⇪"
        "emoji" -> "☺"
        "ai", "sparkle" -> "✦"
        "clipboard" -> "▤"
        "tab" -> "⇥"
        "escape" -> "⎋"
        "up" -> "▲"
        "down" -> "▼"
        "left" -> "◀"
        "right" -> "▶"
        "undo" -> "↶"
        "redo" -> "↷"
        "layout", "keyboard" -> "⌨"
        "menu" -> "☰"
        else -> null
    }

    /** Every name the key editor offers. */
    val NAMES: List<String> = listOf(
        "backspace", "enter", "space", "globe", "mic", "settings", "close", "search",
        "shift", "capslock", "emoji", "ai", "clipboard", "tab", "escape",
        "up", "down", "left", "right", "undo", "redo", "layout", "menu"
    )
}
