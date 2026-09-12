package com.example.core.layout

/**
 * The keyboard is data, not code.
 *
 * Everything a layout can do is described by the types in this file, every one of
 * them serialisable (see [LayoutJson]). A built-in layout, a layout traced off a
 * screenshot, a layout an LLM wrote from a prose description and a layout the user
 * hand-edited are all the same thing by the time they reach the renderer, so any
 * capability one of them has is available to all of them.
 *
 * Nothing here is required. Every field that can carry a default does, and the
 * defaults are the boring ones, so the smallest legal layout is a handful of rows
 * of characters.
 */

// ---------------------------------------------------------------------------
// Triggers
// ---------------------------------------------------------------------------

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT;

    companion object {
        /** Nearest of the eight directions to a drag vector, or null if it is too short. */
        fun of(dx: Float, dy: Float, minDistance: Float): SwipeDirection? {
            val distance = kotlin.math.hypot(dx, dy)
            if (distance < minDistance) return null
            // Screen y grows downwards; negate so 90 degrees means "up".
            var degrees = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble()))
            if (degrees < 0) degrees += 360.0
            return when (((degrees + 22.5) / 45.0).toInt() % 8) {
                0 -> RIGHT
                1 -> UP_RIGHT
                2 -> UP
                3 -> UP_LEFT
                4 -> LEFT
                5 -> DOWN_LEFT
                6 -> DOWN
                else -> DOWN_RIGHT
            }
        }
    }
}

sealed interface KeyTrigger {
    object Tap : KeyTrigger
    object LongPress : KeyTrigger
    object DoubleTap : KeyTrigger

    /** Fired repeatedly while the key stays down. */
    object Repeat : KeyTrigger

    data class Swipe(val direction: SwipeDirection) : KeyTrigger

    /** Fired when this key is pressed while [otherKeyId] is already held. */
    data class Chord(val otherKeyId: String) : KeyTrigger
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

enum class ModifierKind { SHIFT, CTRL, ALT, META, FN, ALT_GR }

/**
 * How a modifier behaves when pressed.
 *
 * Different keyboards disagree about this and users disagree harder, so all four
 * classic behaviours exist and the layout picks per key.
 */
enum class ModifierMode {
    /** Active only while physically held. */
    MOMENTARY,

    /** Applies to exactly the next key, then clears. The Android default for Shift. */
    ONE_SHOT,

    /** Toggles, stays until pressed again. */
    TOGGLE,

    /** Toggles and also sets the corresponding lock indicator (Caps Lock and friends). */
    LOCK
}

enum class LayerMode { MOMENTARY, ONE_SHOT, TOGGLE, LOCK }

enum class TextUnit { CHARACTER, WORD, LINE, PARAGRAPH, ALL }

enum class CursorDirection { LEFT, RIGHT, UP, DOWN, LINE_START, LINE_END, DOC_START, DOC_END }

enum class ClipboardOp { COPY, CUT, PASTE, PASTE_PLAIN, HISTORY, PIN_CURRENT, CLEAR }

enum class SwitchTarget { NEXT, PREVIOUS }

/** Panels that can be raised above the key surface. */
enum class PanelId { EMOJI, CLIPBOARD, VOICE, AI_TOOLS, CURSOR, SETTINGS, LAYOUT_PICKER, INDICATORS, NUMPAD }

sealed interface KeyAction {

    /** Does nothing. Useful as an explicit "unbound" marker in the editor. */
    object None : KeyAction

    /** Commit literal text. The workhorse: every character key is one of these. */
    data class Text(val text: String) : KeyAction

    /**
     * Send a raw key event with modifier bits. This is what lets the keyboard drive
     * terminals, remote desktops and games rather than only text fields.
     */
    data class SendKey(val keyCode: Int, val metaState: Int = 0) : KeyAction

    data class Modifier(val kind: ModifierKind, val mode: ModifierMode = ModifierMode.ONE_SHOT) : KeyAction

    data class Layer(val layer: String, val mode: LayerMode = LayerMode.MOMENTARY) : KeyAction

    /** Switch whole layout: by id, or cycle. */
    data class SwitchLayout(val layoutId: String? = null, val target: SwitchTarget = SwitchTarget.NEXT) : KeyAction

    /** Switch input language / subtype. */
    data class SwitchLanguage(val locale: String? = null, val target: SwitchTarget = SwitchTarget.NEXT) : KeyAction

    data class Backspace(val unit: TextUnit = TextUnit.CHARACTER) : KeyAction

    data class ForwardDelete(val unit: TextUnit = TextUnit.CHARACTER) : KeyAction

    /** Editor action if the field asks for one (Search, Send, Go...), newline otherwise. */
    object Enter : KeyAction

    object Space : KeyAction

    data class MoveCursor(
        val direction: CursorDirection,
        val unit: TextUnit = TextUnit.CHARACTER,
        val extendSelection: Boolean = false
    ) : KeyAction

    data class Select(val unit: TextUnit) : KeyAction

    data class Clipboard(val op: ClipboardOp) : KeyAction

    object Undo : KeyAction
    object Redo : KeyAction

    data class OpenPanel(val panel: PanelId) : KeyAction

    /** Start speech recognition. */
    object Voice : KeyAction

    /** Run a named AI task over the selection or the surrounding text. */
    data class Ai(val taskId: String) : KeyAction

    /** Run several actions in order. */
    data class Macro(val steps: List<KeyAction>) : KeyAction

    /**
     * Dead key: combines with the next character typed. `´` then `e` gives `é`.
     * [combining] is the Unicode combining mark used to compose.
     */
    data class DeadKey(val combining: String, val display: String = combining) : KeyAction

    /** Start an X11-style compose sequence: Compose, then `'`, then `e` gives `é`. */
    object Compose : KeyAction

    /** Start hex Unicode entry: type digits, confirm, get the code point. */
    object UnicodeInput : KeyAction

    /** Insert the current selection's text again, or re-commit the last commit. */
    object RepeatLast : KeyAction

    object ShowSettings : KeyAction

    /** Open the system input-method picker. */
    object SwitchIme : KeyAction

    object HideKeyboard : KeyAction

    /** Toggle the one-handed / split / floating presentation. */
    data class Presentation(val mode: PresentationMode) : KeyAction
}

/**
 * How the keyboard occupies the screen.
 *
 * [FREE] is the one that is not a panel at all: the keys are placed individually over
 * a fully transparent surface, with nothing drawn behind them. A floating *panel* is
 * still a rectangle you drag around; free keys have no rectangle, so what the user
 * sees between them is the app underneath. That difference is why it needs its own
 * mode rather than a flag on [FLOATING] — the panel chrome, the drag handle, the
 * resize corner and the single background all stop existing.
 */
enum class PresentationMode { NORMAL, ONE_HANDED_LEFT, ONE_HANDED_RIGHT, SPLIT, FLOATING, FREE, CYCLE }

/**
 * How much of the screen the keyboard asks the app to keep clear for it.
 *
 * This is the "don't cover the field I am typing into" contract, and it is a policy
 * rather than a fact, which is why it is a setting and not a rule: a full-width
 * keyboard wants the app pushed all the way up, while free transparent keys floating
 * over a photo probably want the app left exactly where it is. Neither is more
 * correct, so both are offered.
 */
enum class InsetsMode {
    /** The whole input view is reserved. The classic behaviour. */
    FULL,

    /** Only the panel's own rectangle. The rest of the screen belongs to the app. */
    PANEL_ONLY,

    /** Only the individual key rectangles, so the gaps between keys stay the app's. */
    KEYS_ONLY,

    /** Nothing is reserved: the keyboard floats over the app and may cover the field. */
    NONE
}

/** What to do when the keyboard would cover the text cursor. */
enum class CursorAvoidStrategy {
    /** Slide the keyboard out of the way, keeping its size. */
    MOVE_PANEL,

    /** Keep the keyboard still and reserve space so the app scrolls the field clear. */
    RESERVE_SPACE,

    /** Fade the keyboard so the field stays readable through it. */
    FADE
}

data class Binding(val trigger: KeyTrigger, val action: KeyAction)

// ---------------------------------------------------------------------------
// Indicators (the "LED" ask)
// ---------------------------------------------------------------------------

/** What an indicator watches. */
sealed interface IndicatorSource {
    data class Modifier(val kind: ModifierKind) : IndicatorSource

    /** True while the modifier is *locked* rather than merely active. */
    data class ModifierLock(val kind: ModifierKind) : IndicatorSource

    data class Layer(val layer: String) : IndicatorSource

    /** Named boolean the runtime publishes: see [IndicatorKeys]. */
    data class Runtime(val key: String) : IndicatorSource
}

object IndicatorKeys {
    const val CAPS_LOCK = "caps_lock"
    const val NUM_LOCK = "num_lock"
    const val SCROLL_LOCK = "scroll_lock"
    const val AI_BUSY = "ai_busy"
    const val ASR_ACTIVE = "asr_active"
    const val ASR_LISTENING = "asr_listening"
    const val NETWORK = "network"
    const val PASSWORD_FIELD = "password_field"
    const val INCOGNITO = "incognito"
    const val COMPOSING = "composing"
    const val DEAD_KEY = "dead_key_pending"
    const val UNICODE_ENTRY = "unicode_entry"
    const val SELECTION = "has_selection"
    const val RECORDING_MACRO = "recording_macro"
}

enum class IndicatorStyle {
    DOT_TOP_LEFT, DOT_TOP_RIGHT, DOT_BOTTOM_LEFT, DOT_BOTTOM_RIGHT,
    BAR_TOP, BAR_BOTTOM, BAR_LEFT, BAR_RIGHT,
    OUTLINE, FILL, GLOW, LABEL_TINT
}

data class IndicatorDef(
    val source: IndicatorSource,
    val style: IndicatorStyle = IndicatorStyle.DOT_TOP_RIGHT,
    /** ARGB. Null means "use the theme's accent". */
    val onColor: Long? = null,
    /** ARGB. Null means "draw nothing when off". */
    val offColor: Long? = null
)

// ---------------------------------------------------------------------------
// Keys, rows, layers, layouts
// ---------------------------------------------------------------------------

enum class KeyShape { RECT, ROUNDED, CIRCLE, PILL }

/** Normalised rectangle, 0..1 in both axes, relative to the key surface. */
/**
 * One tab of a long-press board.
 *
 * Grouping is the difference between a key that offers sixty symbols and a key that
 * offers sixty symbols usefully: lowercase accents, uppercase accents, Greek, maths,
 * set theory and arrows are separate questions, and a scientist writing an equation
 * with Polish text in it wants two of them at once rather than a toggle between them.
 */
data class PopupGroup(
    val id: String,
    /** What the tab shows. Conventionally one representative character. */
    val label: String,
    val items: List<String> = emptyList(),
    /** Longer description, shown when there is room for it. */
    val title: String? = null
)

data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class KeyDef(
    val id: String,
    /** Drawn on the key. Null means "derive it from the tap action". */
    val label: String? = null,
    /** Small secondary glyph, conventionally the long-press result. */
    val hint: String? = null,
    /** Named icon from [com.example.ui.keyboard.KeyIcons]; wins over [label] when set. */
    val icon: String? = null,
    val widthWeight: Float = 1f,
    /** Style id resolved against the theme. Built-ins: "normal", "special", "modifier", "accent". */
    val style: String? = null,
    val bindings: List<Binding> = emptyList(),
    /** Long-press mini-keyboard. Plain strings are committed as text. */
    val popup: List<String> = emptyList(),
    /**
     * A long-press board with tabs, for keys carrying more than a row's worth.
     *
     * Separate from [popup] rather than replacing it because the two want different
     * interactions: a handful of accents is best picked by sliding the finger without
     * letting go, while sixty mathematical symbols need a board that stays open and is
     * tapped. Keeping both means the quick case stays quick and the deep case stops
     * being a drag across sixty targets.
     */
    val popupGroups: List<PopupGroup> = emptyList(),
    /** Auto-repeat while held (backspace, arrows). */
    val repeatable: Boolean = false,
    val indicators: List<IndicatorDef> = emptyList(),
    val shape: KeyShape = KeyShape.ROUNDED,
    /**
     * Absolute placement over a background image. When set the key ignores row flow,
     * which is how bitmap-traced layouts are represented.
     */
    val bounds: NormRect? = null,
    /**
     * Extra touch weight for the probabilistic hit model. 1.0 is neutral; a bigger
     * number makes the key easier to hit without changing how it looks.
     */
    val touchWeight: Float = 1f,
    /** Visible at all? An invisible key still takes touches: useful for bitmap skins. */
    val visible: Boolean = true
) {
    fun actionFor(trigger: KeyTrigger): KeyAction? =
        bindings.firstOrNull { it.trigger == trigger }?.action

    val tapAction: KeyAction? get() = actionFor(KeyTrigger.Tap)

    /** What to draw, falling back to the tap action's own text. */
    val effectiveLabel: String
        get() = label ?: when (val a = tapAction) {
            is KeyAction.Text -> a.text
            is KeyAction.DeadKey -> a.display
            else -> ""
        }
}

data class RowDef(
    val keys: List<KeyDef>,
    val heightWeight: Float = 1f,
    /** Leading/trailing gap measured in key-weight units, for staggered rows. */
    val padStart: Float = 0f,
    val padEnd: Float = 0f
)

data class LayerDef(
    val name: String,
    val rows: List<RowDef> = emptyList(),
    /** Absolutely-placed keys, used by bitmap layouts. Rendered above [rows]. */
    val freeKeys: List<KeyDef> = emptyList()
) {
    val allKeys: List<KeyDef> get() = rows.flatMap { it.keys } + freeKeys
}

enum class BackgroundFit { STRETCH, CONTAIN, COVER, TILE }

data class BackgroundDef(
    /** File name inside the app's `layout_assets` directory. */
    val imageFile: String,
    val fit: BackgroundFit = BackgroundFit.STRETCH,
    val opacity: Float = 1f,
    /**
     * Optional grayscale weight map, same directory. Brighter pixels attract touches
     * more strongly. Feeds the probabilistic hit model, never drawn.
     */
    val sensitivityFile: String? = null
)

/** Where one piece of a layout sits. */
enum class ElementPlacement {
    /** Part of the keyboard panel at the bottom, in the ordinary flow. */
    DOCKED,

    /** Its own block, positioned and draggable, with its own background. */
    FLOATING,

    /** Keys placed individually with nothing drawn behind them. */
    FREE
}

/**
 * One piece of a layout, with its own placement.
 *
 * This is the correction to a design mistake worth naming: docked, floating and free
 * were a single app-wide setting, which cannot express the thing people actually
 * want. "A docked panel, a floating numeric block, and a transparent Escape in the
 * corner" is *one layout with three elements*, not three settings — and no amount of
 * options on a global enum reaches it, because the global enum can only ever describe
 * the keyboard as a whole.
 *
 * So placement moves where it belongs: into the layout, which is data. A layout with
 * no elements is one docked element, which is every layout written so far, so nothing
 * that existed has to change to keep working.
 *
 * Each element also answers the two questions that only make sense per element:
 * whether it asks the app to keep space clear for it, and whether it may move out of
 * the way of the text cursor. A floating numeric block should yield; an Escape key
 * deliberately pinned to a corner should not.
 */
data class ElementDef(
    val id: String,
    /** The layer this element shows. Layers are how a layout is cut into pieces. */
    val layer: String = LayoutDef.BASE_LAYER,
    val placement: ElementPlacement = ElementPlacement.DOCKED,
    /**
     * Where it sits, as fractions of the input view. Ignored when docked; defaulted
     * to something visible when a floating or free element omits it.
     */
    val bounds: NormRect? = null,
    /** Multiplies whatever the theme and settings already decided, never overrides. */
    val opacity: Float = 1f,
    /** The element's own background. 0 leaves its keys floating over the app. */
    val panelOpacity: Float = 1f,
    /** Whether the app is asked to keep this element's space clear. */
    val reservesSpace: Boolean = true,
    /** Pinned elements stay put even when they would cover the text cursor. */
    val pinned: Boolean = false,
    val draggable: Boolean = true,
    /** Drawn only when the field being typed into is of a matching kind, if set. */
    val visible: Boolean = true
)

data class LayoutDef(
    val id: String,
    val name: String,
    val layers: Map<String, LayerDef>,
    /**
     * The pieces this layout is made of, and where each sits. Empty means the whole
     * layout is one docked panel — which is what every layout without elements is.
     */
    val elements: List<ElementDef> = emptyList(),
    val defaultLayer: String = BASE_LAYER,
    /** BCP-47-ish tag used for language cycling and dictionary selection. */
    val locale: String? = null,
    val background: BackgroundDef? = null,
    val author: String? = null,
    val description: String? = null,
    /** Suggested aspect: rows tall. Used to pick a default height. */
    val rowCountHint: Int = 4,
    val builtIn: Boolean = false
) {
    fun layer(name: String): LayerDef? = layers[name]
    val base: LayerDef get() = layers[defaultLayer] ?: layers[BASE_LAYER] ?: layers.values.first()

    fun key(id: String): KeyDef? = layers.values.firstNotNullOfOrNull { layer ->
        layer.allKeys.firstOrNull { it.id == id }
    }

    companion object {
        const val BASE_LAYER = "base"
        const val SHIFT_LAYER = "shift"
        const val SYMBOL_LAYER = "symbols"
        const val SYMBOL_SHIFT_LAYER = "symbols_shift"
    }
}
