package com.example.core.io

/**
 * A user-started shortcut session, independent of Android's transient IME window.
 * Hiding is never itself a reason to show: only an explicit request or a new input
 * target can do that. This avoids a hide/show loop when Android refuses a window.
 */
class ShortcutSession {
    var active: Boolean = false
        private set
    var pinned: Boolean = false
        private set

    fun start(keepVisible: Boolean) {
        active = true
        pinned = keepVisible
    }

    fun hidden() {
        if (!pinned) active = false
    }

    fun stop() {
        active = false
        pinned = false
    }

    fun updatePreference(keepVisible: Boolean) {
        pinned = active && keepVisible
    }

    fun shouldRestore(ownTarget: Boolean, deviceUnlocked: Boolean, pocketLocked: Boolean): Boolean =
        active && pinned && !ownTarget && deviceUnlocked && !pocketLocked
}
