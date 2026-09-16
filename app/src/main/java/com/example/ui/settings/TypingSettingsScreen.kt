package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.hitmap.TouchLearner

@Composable
fun TypingSettingsScreen(settings: Settings) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

        SettingsSection("Text conventions") {
            SwitchRow(
                label = "Capitalise sentences",
                description = "The first letter after a full stop, and at the start of a field.",
                checked = settings.autoCapitalize,
                onChange = { on -> SettingsStore.update { it.copy(autoCapitalize = on) } }
            )
            Divider()
            SwitchRow(
                label = "Double space makes a full stop",
                checked = settings.doubleSpacePeriod,
                onChange = { on -> SettingsStore.update { it.copy(doubleSpacePeriod = on) } }
            )
            Divider()
            SwitchRow(
                label = "Curly quotes",
                description = "Turns \" and ' into “ ” and ‘ ’ depending on position. " +
                    "Off by default, because it breaks code and search queries.",
                checked = settings.smartQuotes,
                onChange = { on -> SettingsStore.update { it.copy(smartQuotes = on) } }
            )
            Divider()
            SwitchRow(
                label = "Shift releases after one letter",
                description = "On, Shift is one-shot like a phone keyboard. Off, it stays " +
                    "on until pressed again. Long-press Shift for caps lock either way.",
                checked = settings.shiftOneShot,
                onChange = { on -> SettingsStore.update { it.copy(shiftOneShot = on) } }
            )
        }

        SettingsSection("Suggestions") {
            SwitchRow(
                label = "Suggestion strip",
                checked = settings.suggestionsEnabled,
                onChange = { on -> SettingsStore.update { it.copy(suggestionsEnabled = on) } }
            )
            Divider()
            SwitchRow(
                label = "Learn from what you type",
                description = "Builds a private dictionary and a next-word model on this " +
                    "device. Nothing is uploaded. Password fields are never learned from.",
                checked = settings.learnFromTyping,
                onChange = { on -> SettingsStore.update { it.copy(learnFromTyping = on) } }
            )
            Divider()
            SwitchRow(
                label = "Correct words automatically",
                description = "Off by default: suggestions are offered, and replacing what " +
                    "you typed is your decision, not the keyboard's.",
                checked = settings.autoCorrect,
                onChange = { on -> SettingsStore.update { it.copy(autoCorrect = on) } }
            )
            if (settings.expertMode) {
                Divider()
                SliderRow(
                    label = "How many suggestions",
                    value = settings.suggestionCount.toFloat(),
                    range = 1f..6f,
                    steps = 4,
                    format = { it.toInt().toString() },
                    onChange = { v -> SettingsStore.update { it.copy(suggestionCount = v.toInt()) } }
                )
            }
            InfoRow("Long-press a suggestion to stop it being offered again.")
        }

        SettingsSection("Gestures") {
            SwitchRow(
                label = "Slide along the space bar to move the cursor",
                checked = settings.spaceSlideCursor,
                onChange = { on -> SettingsStore.update { it.copy(spaceSlideCursor = on) } }
            )
            Divider()
            SwitchRow(
                label = "Swipe left on backspace deletes a word",
                checked = settings.backspaceSwipeDeletesWord,
                onChange = { on -> SettingsStore.update { it.copy(backspaceSwipeDeletesWord = on) } }
            )
            Divider()
            SwitchRow(
                label = "Long-press for accents",
                description = "Hold a letter for its alternates, then slide to pick one " +
                    "and let go.",
                checked = settings.longPressPopup,
                onChange = { on -> SettingsStore.update { it.copy(longPressPopup = on) } }
            )
            if (settings.expertMode) {
                Divider()
                SliderRow(
                    label = "Long-press delay",
                    value = settings.longPressMs.toFloat(),
                    range = 120f..800f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(longPressMs = v.toLong()) } }
                )
                SliderRow(
                    label = "Repeat starts after",
                    description = "How long a repeating key waits before it starts repeating.",
                    value = settings.repeatStartMs.toFloat(),
                    range = 150f..1200f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(repeatStartMs = v.toLong()) } }
                )
                SliderRow(
                    label = "Repeat interval",
                    value = settings.repeatIntervalMs.toFloat(),
                    range = 15f..250f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(repeatIntervalMs = v.toLong()) } }
                )
                SliderRow(
                    label = "Double-tap window",
                    value = settings.doubleTapMs.toFloat(),
                    range = 120f..600f,
                    format = { "${it.toInt()} ms" },
                    onChange = { v -> SettingsStore.update { it.copy(doubleTapMs = v.toLong()) } }
                )
                SliderRow(
                    label = "Swipe distance",
                    description = "How far a finger must travel before it counts as a swipe " +
                        "rather than a tap.",
                    value = settings.swipeThresholdDp,
                    range = 8f..60f,
                    format = { "${it.toInt()} dp" },
                    onChange = { v -> SettingsStore.update { it.copy(swipeThresholdDp = v) } }
                )
            }
        }

        SettingsSection(
            title = "Swipes across the whole keyboard",
            subtitle = "A long swipe that no key claimed. Bound to actions, so anything a " +
                "key can do, a gesture can do."
        ) {
            SurfaceGestureRow("Swipe up", settings.gestureSwipeUp) { action ->
                SettingsStore.update { it.copy(gestureSwipeUp = action) }
            }
            Divider()
            SurfaceGestureRow("Swipe down", settings.gestureSwipeDown) { action ->
                SettingsStore.update { it.copy(gestureSwipeDown = action) }
            }
            Divider()
            SurfaceGestureRow("Swipe left", settings.gestureSwipeLeft) { action ->
                SettingsStore.update { it.copy(gestureSwipeLeft = action) }
            }
            Divider()
            SurfaceGestureRow("Swipe right", settings.gestureSwipeRight) { action ->
                SettingsStore.update { it.copy(gestureSwipeRight = action) }
            }
            if (settings.expertMode) {
                InfoRow(
                    "Not listed here? Any action from the layout format works — paste its " +
                        "JSON into a layout's key instead, or edit the exported settings."
                )
            }
        }

        SettingsSection("Clipboard") {
            SwitchRow(
                label = "Remember what you copy",
                checked = settings.clipboardEnabled,
                onChange = { on -> SettingsStore.update { it.copy(clipboardEnabled = on) } }
            )
            Divider()
            SwitchRow(
                label = "Never record from password fields",
                description = "Also skips clips the source app marked as sensitive.",
                checked = settings.clipboardIgnorePasswordFields,
                onChange = { on -> SettingsStore.update { it.copy(clipboardIgnorePasswordFields = on) } }
            )
            Divider()
            SwitchRow(
                label = "Offer what you just copied",
                description = "While a field is still empty, something copied a moment ago " +
                    "appears in the suggestion strip. Never in a password field.",
                checked = settings.clipboardSuggestions,
                onChange = { on -> SettingsStore.update { it.copy(clipboardSuggestions = on) } }
            )
            Divider()
            ChoiceRow(
                label = "Forget after",
                options = listOf(0, 1, 7, 30, 90),
                selected = settings.clipboardRetentionDays,
                optionLabel = { days -> if (days == 0) "Never" else "$days days" },
                onSelect = { days -> SettingsStore.update { it.copy(clipboardRetentionDays = days) } }
            )
            if (settings.expertMode) {
                Divider()
                SliderRow(
                    label = "How many entries to keep",
                    value = settings.clipboardMaxItems.toFloat(),
                    range = 20f..2000f,
                    format = { it.toInt().toString() },
                    onChange = { v -> SettingsStore.update { it.copy(clipboardMaxItems = v.toInt()) } }
                )
            }
            InfoRow("Pinned entries are never removed by either rule.")
        }

        SettingsSection(
            title = "Privacy",
            subtitle = "These apply regardless of anything else you switch on."
        ) {
            SwitchRow(
                label = "Treat password fields as private",
                description = "No suggestions, no learning, no clipboard capture, nothing " +
                    "sent to a model.",
                checked = settings.incognitoInPasswordFields,
                onChange = { on -> SettingsStore.update { it.copy(incognitoInPasswordFields = on) } }
            )
            InfoRow(
                "Editors can also ask not to be personalised; that request is honoured " +
                    "whatever this is set to."
            )
        }

        if (settings.expertMode) {
            SettingsSection(
                title = "Touch model",
                subtitle = "How a press becomes a key."
            ) {
                SwitchRow(
                    label = "Weigh neighbouring keys",
                    description = "Instead of a hard rectangle, each key's pull falls off " +
                        "with distance. Helps if you consistently land off-centre; can also " +
                        "override a press you meant, which is why it is off by default.",
                    checked = settings.touchModelEnabled,
                    onChange = { on -> SettingsStore.update { it.copy(touchModelEnabled = on) } }
                )
                if (settings.touchModelEnabled) {
                    SliderRow(
                        label = "Spread",
                        description = "Larger means more willing to override the key your " +
                            "finger actually landed on.",
                        value = settings.touchModelSigmaDp,
                        range = 4f..48f,
                        format = { "${it.toInt()} dp" },
                        onChange = { v -> SettingsStore.update { it.copy(touchModelSigmaDp = v) } }
                    )
                    Divider()
                    SwitchRow(
                        label = "Learn where you actually press",
                        description = "When you type a letter, delete it and type a " +
                            "different one in the same spot, that key moves slightly " +
                            "towards where your finger was. Nothing else is recorded.",
                        checked = settings.touchModelLearning,
                        onChange = { on -> SettingsStore.update { it.copy(touchModelLearning = on) } }
                    )
                    ActionRow(
                        "Forget what it has learned",
                        "Puts every key back at its drawn position",
                        onClick = { TouchLearner.forgetAll(context) }
                    )
                }
            }

            SettingsSection("Hardware & system") {
                SwitchRow(
                    label = "Show on screen with a hardware keyboard attached",
                    description = "Off, the on-screen keyboard gets out of the way when a " +
                        "physical keyboard is connected.",
                    checked = settings.showOnHardwareKeyboard,
                    onChange = { on -> SettingsStore.update { it.copy(showOnHardwareKeyboard = on) } }
                )
                Divider()
                SwitchRow(
                    label = "Show a diagnostic overlay",
                    description = "Draws the current layer, modifier states, selection " +
                        "range and any pending dead key over the keys. Useful while " +
                        "building a layout.",
                    checked = settings.debugOverlay,
                    onChange = { on -> SettingsStore.update { it.copy(debugOverlay = on) } }
                )
                Divider()
                SwitchRow(
                    label = "Volume keys resize the keyboard",
                    description = "While the keyboard is up, volume up and down change its " +
                        "height instead of the volume.",
                    checked = settings.volumeKeysResize,
                    onChange = { on -> SettingsStore.update { it.copy(volumeKeysResize = on) } }
                )
            }
        }
    }
}


/**
 * The presets a surface gesture can be bound to.
 *
 * Stored as action JSON rather than an enum, so the list here is a convenience and
 * not a limit: anything the layout format can express is a legal value.
 */
private val SURFACE_GESTURE_ACTIONS: List<Pair<String, String>> = listOf(
    "" to "Nothing",
    "{\"type\":\"hide\"}" to "Hide the keyboard",
    "{\"type\":\"layout\",\"target\":\"next\"}" to "Next layout",
    "{\"type\":\"layout\",\"target\":\"previous\"}" to "Previous layout",
    "{\"type\":\"language\",\"target\":\"next\"}" to "Next language",
    "{\"type\":\"panel\",\"panel\":\"emoji\"}" to "Emoji",
    "{\"type\":\"panel\",\"panel\":\"clipboard\"}" to "Clipboard",
    "{\"type\":\"panel\",\"panel\":\"cursor\"}" to "Cursor pad",
    "{\"type\":\"panel\",\"panel\":\"ai_tools\"}" to "AI tools",
    "{\"type\":\"voice\"}" to "Dictation",
    "{\"type\":\"presentation\",\"mode\":\"cycle\"}" to "Cycle one-handed / split / floating",
    "{\"type\":\"undo\"}" to "Undo",
    "{\"type\":\"redo\"}" to "Redo",
    "{\"type\":\"select\",\"unit\":\"all\"}" to "Select all",
    "{\"type\":\"settings\"}" to "Open settings"
)

@Composable
private fun SurfaceGestureRow(label: String, current: String, onSelect: (String) -> Unit) {
    val known = SURFACE_GESTURE_ACTIONS.map { it.first }
    val options = if (current in known) known else known + current
    ChoiceRow(
        label = label,
        options = options,
        selected = current,
        optionLabel = { value ->
            SURFACE_GESTURE_ACTIONS.firstOrNull { it.first == value }?.second
                ?: "Custom: ${value.take(30)}"
        },
        onSelect = onSelect
    )
}
