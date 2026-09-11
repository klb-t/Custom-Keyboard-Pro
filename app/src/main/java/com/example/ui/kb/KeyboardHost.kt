package com.example.ui.kb

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.core.asr.VoiceController
import com.example.core.data.KeyboardRepository
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.LayoutDef
import com.example.core.layout.PanelId
import com.example.core.suggest.SuggestionEngine
import com.example.ime.EditorController
import com.example.ime.KeyboardState

/**
 * What the keyboard UI is allowed to ask of the service hosting it.
 *
 * Narrow on purpose: panels and the key surface can request actions and read state,
 * but they cannot reach an `InputConnection`, so every edit to the user's text goes
 * through one path and can be reasoned about in one place.
 */
interface KeyboardHost {
    val state: KeyboardState
    val editor: EditorController
    val suggestions: SuggestionEngine
    val voice: VoiceController
    val repository: KeyboardRepository

    val layout: LayoutDef
    val availableLayouts: List<LayoutDef>

    /** Runs an action as if a key bound to it had been pressed. */
    fun perform(action: KeyAction)

    /** Feedback for a press: haptics and sound, subject to settings. */
    fun feedback(key: KeyDef?)

    /**
     * Runs whatever the user bound to a long swipe across the whole keyboard.
     * Returns false when that direction is unbound, so the press can still type.
     */
    fun performSurfaceGesture(direction: com.example.core.layout.SwipeDirection): Boolean

    /** Where per-key touch offsets are learned and kept. */
    val touchLearner: com.example.core.hitmap.TouchLearner

    fun openPanel(panel: PanelId?)
    val openPanelId: PanelId?

    fun selectLayout(id: String)

    /** Opens the app, optionally on a particular screen. */
    fun openApp(route: String? = null)

    /** Sends the user to the app to grant the microphone permission. */
    fun requestMicrophonePermission()

    fun hideKeyboard()
    fun switchIme()

    /** Text the AI panel should work on: the selection, or the surrounding text. */
    fun textForAi(): String
}

val LocalKeyboardHost = staticCompositionLocalOf<KeyboardHost> {
    error("No KeyboardHost has been provided.")
}
