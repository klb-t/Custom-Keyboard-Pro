package com.example.ime

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.core.layout.IndicatorKeys
import com.example.core.layout.IndicatorSource
import com.example.core.layout.LayerMode
import com.example.core.layout.LayoutDef
import com.example.core.layout.ModifierKind
import com.example.core.layout.ModifierMode

/**
 * Live keyboard state: which modifiers are down, which layer is showing, and the
 * boolean flags that drive indicators.
 *
 * Held in Compose snapshot state so the key surface redraws when a modifier lights
 * up, without an observer of its own.
 */
class KeyboardState(
    private val settings: () -> com.example.core.config.Settings = {
        com.example.core.config.Settings()
    }
) {

    // -----------------------------------------------------------------------
    // Modifiers
    // -----------------------------------------------------------------------

    /** `active` covers all three ways a modifier can be on; `locked` is the sticky one. */
    data class ModifierState(
        val active: Boolean = false,
        val locked: Boolean = false,
        val oneShot: Boolean = false,
        val held: Boolean = false
    )

    private val modifiers = mutableStateMapOf<ModifierKind, ModifierState>()

    fun modifier(kind: ModifierKind): ModifierState = modifiers[kind] ?: ModifierState()

    fun isActive(kind: ModifierKind): Boolean = modifier(kind).active
    fun isLocked(kind: ModifierKind): Boolean = modifier(kind).locked

    /**
     * Applies a press of a modifier key.
     *
     * `ONE_SHOT` cycles off → one-shot → locked → off, which is the behaviour people
     * expect from tapping Shift twice, while still letting a layout ask for a plain
     * toggle or a hold-only modifier instead.
     */
    fun pressModifier(kind: ModifierKind, mode: ModifierMode) {
        val s = modifier(kind)
        // A layout asks for one-shot Shift because that is the phone convention; a user
        // who wants Shift to stay on until pressed again overrides it here.
        val effective = if (kind == ModifierKind.SHIFT &&
            mode == ModifierMode.ONE_SHOT &&
            !settings().shiftOneShot
        ) ModifierMode.TOGGLE else mode

        modifiers[kind] = when (effective) {
            ModifierMode.MOMENTARY -> ModifierState(active = true, held = true)
            ModifierMode.TOGGLE -> if (s.active) ModifierState() else ModifierState(active = true)
            ModifierMode.LOCK -> if (s.locked) ModifierState() else ModifierState(active = true, locked = true)
            ModifierMode.ONE_SHOT -> when {
                s.locked -> ModifierState()
                s.oneShot -> ModifierState(active = true, locked = true)
                s.active -> ModifierState()
                else -> ModifierState(active = true, oneShot = true)
            }
        }
        syncLockFlags()
    }

    fun releaseModifier(kind: ModifierKind) {
        val s = modifier(kind)
        if (s.held) {
            modifiers[kind] = ModifierState()
            syncLockFlags()
        }
    }

    fun setModifier(kind: ModifierKind, active: Boolean, locked: Boolean = false) {
        modifiers[kind] = ModifierState(active = active || locked, locked = locked)
        syncLockFlags()
    }

    /** Clears every one-shot modifier. Called after a character is committed. */
    fun consumeOneShots() {
        var changed = false
        ModifierKind.entries.forEach { kind ->
            val s = modifier(kind)
            if (s.oneShot && !s.locked) {
                modifiers[kind] = ModifierState()
                changed = true
            }
        }
        if (changed) syncLockFlags()
    }

    fun clearAllModifiers() {
        modifiers.clear()
        syncLockFlags()
    }

    /** The Android meta bitmask for the modifiers currently on. */
    fun metaState(): Int {
        var meta = 0
        if (isActive(ModifierKind.SHIFT)) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (isActive(ModifierKind.CTRL)) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (isActive(ModifierKind.ALT)) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (isActive(ModifierKind.ALT_GR)) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        if (isActive(ModifierKind.META)) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (isActive(ModifierKind.FN)) meta = meta or KeyEvent.META_FUNCTION_ON
        if (isLocked(ModifierKind.SHIFT)) meta = meta or KeyEvent.META_CAPS_LOCK_ON
        return meta
    }

    /** True when a letter key should send a key event rather than commit text. */
    val wantsRawKeyEvents: Boolean
        get() = isActive(ModifierKind.CTRL) || isActive(ModifierKind.ALT) || isActive(ModifierKind.META)

    // -----------------------------------------------------------------------
    // Layers
    // -----------------------------------------------------------------------

    var layer by mutableStateOf(LayoutDef.BASE_LAYER)
        private set

    private var layerReturnsTo: String? = null
    private var layerOneShot = false

    fun switchLayer(name: String, mode: LayerMode) {
        when (mode) {
            LayerMode.TOGGLE -> {
                layer = if (layer == name) LayoutDef.BASE_LAYER else name
                layerReturnsTo = null
                layerOneShot = false
            }
            LayerMode.MOMENTARY, LayerMode.ONE_SHOT -> {
                if (layer == name) {
                    layer = layerReturnsTo ?: LayoutDef.BASE_LAYER
                    layerReturnsTo = null
                    layerOneShot = false
                } else {
                    layerReturnsTo = layer
                    layer = name
                    layerOneShot = mode == LayerMode.ONE_SHOT
                }
            }
            LayerMode.LOCK -> {
                layer = name
                layerReturnsTo = null
                layerOneShot = false
            }
        }
    }

    /**
     * Goes to [name] and forgets any momentary or one-shot layer that was pending.
     *
     * Not called setLayer: `layer` is a property, so that name is already taken by its
     * generated setter.
     */
    fun resetLayer(name: String) {
        layer = name
        layerReturnsTo = null
        layerOneShot = false
    }

    fun consumeOneShotLayer() {
        if (layerOneShot) {
            layer = layerReturnsTo ?: LayoutDef.BASE_LAYER
            layerReturnsTo = null
            layerOneShot = false
        }
    }

    /**
     * The layer to actually draw.
     *
     * A layout that defines a `shift` layer gets it automatically while Shift is on,
     * so a layout author does not have to bind the switch by hand — but a layout that
     * does not define one is left alone, and shifting is handled per key instead.
     */
    fun renderLayer(layout: LayoutDef): String {
        if (isActive(ModifierKind.SHIFT)) {
            when (layer) {
                LayoutDef.BASE_LAYER ->
                    if (layout.layers.containsKey(LayoutDef.SHIFT_LAYER)) return LayoutDef.SHIFT_LAYER
                LayoutDef.SYMBOL_LAYER ->
                    if (layout.layers.containsKey(LayoutDef.SYMBOL_SHIFT_LAYER)) return LayoutDef.SYMBOL_SHIFT_LAYER
            }
        }
        return if (layout.layers.containsKey(layer)) layer else layout.defaultLayer
    }

    // -----------------------------------------------------------------------
    // Indicator flags
    // -----------------------------------------------------------------------

    private val flags = mutableStateMapOf<String, Boolean>()

    fun flag(key: String): Boolean = flags[key] ?: false

    fun setFlag(key: String, value: Boolean) {
        if (flags[key] != value) flags[key] = value
    }

    fun allFlags(): Map<String, Boolean> = flags.toMap()

    private fun syncLockFlags() {
        setFlag(IndicatorKeys.CAPS_LOCK, isLocked(ModifierKind.SHIFT))
    }

    /** Resolves an indicator's source against current state. */
    fun indicatorOn(source: IndicatorSource): Boolean = when (source) {
        is IndicatorSource.Modifier -> isActive(source.kind)
        is IndicatorSource.ModifierLock -> isLocked(source.kind)
        is IndicatorSource.Layer -> layer == source.layer
        is IndicatorSource.Runtime -> flag(source.key)
    }

    // -----------------------------------------------------------------------
    // Pending composition helpers (dead keys, Unicode entry, compose sequences)
    // -----------------------------------------------------------------------

    var pendingDeadKey by mutableStateOf<String?>(null)
        private set

    var unicodeBuffer by mutableStateOf<String?>(null)
        private set

    var composeBuffer by mutableStateOf<String?>(null)
        private set

    fun setDeadKey(combining: String?) {
        pendingDeadKey = combining
        setFlag(IndicatorKeys.DEAD_KEY, combining != null)
    }

    fun startUnicode() {
        unicodeBuffer = ""
        setFlag(IndicatorKeys.UNICODE_ENTRY, true)
    }

    fun appendUnicode(c: String) {
        unicodeBuffer = (unicodeBuffer ?: "") + c
    }

    fun endUnicode(): String? {
        val buffer = unicodeBuffer
        unicodeBuffer = null
        setFlag(IndicatorKeys.UNICODE_ENTRY, false)
        return buffer
    }

    fun startCompose() {
        composeBuffer = ""
        setFlag(IndicatorKeys.COMPOSING, true)
    }

    fun appendCompose(c: String) {
        composeBuffer = (composeBuffer ?: "") + c
    }

    fun endCompose(): String? {
        val buffer = composeBuffer
        composeBuffer = null
        setFlag(IndicatorKeys.COMPOSING, false)
        return buffer
    }

    fun clearPending() {
        setDeadKey(null)
        unicodeBuffer = null
        composeBuffer = null
        setFlag(IndicatorKeys.UNICODE_ENTRY, false)
        setFlag(IndicatorKeys.COMPOSING, false)
    }
}
