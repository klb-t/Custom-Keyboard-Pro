package com.example.ui.kb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /**
     * Tells the service where the keys ended up, in input-view pixels.
     *
     * Needed because the insets the service reports can be finer than "the whole
     * view": with KEYS_ONLY the untouched gaps between free-floating keys belong to
     * the app underneath, and only the UI knows where those gaps are. Reporting the
     * drawn geometry rather than recomputing it service-side keeps the touchable
     * region and the visible keys from ever disagreeing.
     *
     * Keyed by [sourceId] because a layout can be several elements at once — a docked
     * panel and a floating block are two surfaces, and the second reporting must not
     * erase the first. An element that does not reserve space simply reports nothing.
     */
    fun reportKeyRects(sourceId: String, rects: List<android.graphics.Rect>)

    /** How the keyboard is currently getting out of the cursor's way, if at all. */
    val avoidance: AvoidanceState
}

/**
 * Runtime state for cursor avoidance — deliberately not a setting.
 *
 * The *policy* is a setting; where the cursor happens to be this second is not, and
 * writing it to disk on every keystroke would be both wasteful and meaningless on the
 * next launch.
 */
class AvoidanceState {
    /** Upward shift, in pixels, applied to the keyboard to clear the cursor. */
    var shiftPx by androidx.compose.runtime.mutableStateOf(0f)

    /** Multiplier on the keyboard's opacity while it would otherwise cover the cursor. */
    var fade by androidx.compose.runtime.mutableStateOf(1f)

    fun clear() {
        shiftPx = 0f
        fade = 1f
    }
}

val LocalKeyboardHost = staticCompositionLocalOf<KeyboardHost> {
    error("No KeyboardHost has been provided.")
}
