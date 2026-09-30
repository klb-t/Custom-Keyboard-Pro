package com.example.core.config

/** Shared destination catalogue for navigation, action profiles and contextual links. */
object SettingsDestinations {
    val titles = linkedMapOf(
        "home" to "IO Matrix",
        "appearance" to "Size, shape & theme",
        "typing" to "Typing",
        "layouts" to "Layouts",
        "ai" to "AI",
        "voice" to "Dictation",
        "permissions" to "Dictation",
        "dictionary" to "Dictionary & shortcuts",
        "about" to "About & help",
        "diagnostics" to "Diagnostics",
        "all" to "Settings explorer",
        "debugger" to "Runtime debugger",
        "request" to "What would you like to change?",
        "theme" to "Theme editor",
        "setup" to "Setting up",
        "media" to "Files & cloud media",
        "vault" to "Passwords & cards",
        "capabilities" to "Phone capabilities"
    )
    fun isKnown(route: String): Boolean = route in titles ||
        (route.startsWith("panel:") && route.removePrefix("panel:").isNotBlank())
    fun title(route: String): String = titles[route] ?: if (route.startsWith("panel:")) "Your panel" else "IO Matrix"
}
