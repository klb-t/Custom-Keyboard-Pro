package com.example.core.layout

/** A local typing policy may decline an automatic capital without declining manual Shift. */
object AutomaticShift {
    fun present(base: KeyDef?, shown: KeyDef, automaticShift: Boolean, autoCapitalize: Boolean): KeyDef {
        if (!automaticShift || autoCapitalize || base == null) return shown
        val literal = base.tapAction as? KeyAction.Text ?: return shown
        if (shown.tapAction !is KeyAction.Text) return shown
        // Use authored base text verbatim: lowercasing would corrupt intentional capitals.
        return shown.copy(label = base.label, hint = base.hint,
            bindings = shown.bindings.map { binding -> if (binding.trigger == KeyTrigger.Tap) binding.copy(action = literal) else binding },
            popup = base.popup, popupGroups = base.popupGroups)
    }
}
