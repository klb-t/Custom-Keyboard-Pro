package com.example.io

import android.os.Handler
import android.os.Looper
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobInt
import com.example.core.config.knobLong
import com.example.core.speech.SpeechText

/**
 * Reading a whole page aloud, not just what fits on the screen.
 *
 * What is on screen is read; then the page is scrolled and only the lines not read
 * yet are read — a line scrolled back into view is not said twice. It stops when a
 * scroll brings nothing new, or after the most screens the user allows, so an endless
 * feed is not read forever. With the pocket lock, this is a phone in a pocket reading
 * an article out loud.
 */
class PageReader(private val service: IoAccessibilityService, private val tell: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private var screens = 0

    fun start() = read()

    private fun read() {
        val lines = service.screenText().split('\n').filter { it.trim().length >= 2 }
        val fresh = SpeechText.unread(lines, seen)
        if (fresh.isEmpty()) {
            tell(if (screens == 0) "Nothing readable on screen" else "Finished reading the page")
            return
        }
        Speaker.speak(service, fresh.joinToString("\n")) { next() }
    }

    private fun next() {
        screens++
        val s = SettingsStore.current
        if (screens >= s.knobInt(Knobs.READ_PAGE_MAX)) {
            tell("Stopped after $screens screens")
            return
        }
        service.scroll("down")
        main.postDelayed({ read() }, s.knobLong(Knobs.READ_PAGE_WAIT_MS))
    }
}
