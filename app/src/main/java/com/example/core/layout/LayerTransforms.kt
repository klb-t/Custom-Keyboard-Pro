package com.example.core.layout

/**
 * Reshapes a layer without the renderer or the hit test knowing anything about it.
 *
 * Split and one-handed modes are implemented here rather than in the UI because a
 * layer is the only geometry the rest of the keyboard understands: if split inserts a
 * real (invisible) key into each row, then placement, hit testing, popups and the
 * touch model all handle it correctly for free.
 */
object LayerTransforms {

    /**
     * Splits each row in two with a gap in the middle.
     *
     * [gapFraction] is a share of the row's total weight. The spacer is an invisible,
     * unbound key, so a touch that lands in the gap does nothing instead of guessing.
     */
    fun split(layer: LayerDef, gapFraction: Float): LayerDef {
        if (gapFraction <= 0f) return layer
        return layer.copy(
            rows = layer.rows.mapIndexed { index, row ->
                if (row.keys.size < 4) return@mapIndexed row
                val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                val gapWeight = (totalWeight * gapFraction).coerceAtLeast(0.2f)

                // Cut where the running weight first passes half, so the halves are
                // even by width rather than by key count.
                var running = 0f
                var cut = row.keys.size / 2
                for (i in row.keys.indices) {
                    running += row.keys[i].widthWeight
                    if (running >= totalWeight / 2f) {
                        cut = i + 1
                        break
                    }
                }
                val spacer = KeyDef(
                    id = "__split_$index",
                    label = null,
                    widthWeight = gapWeight,
                    bindings = emptyList(),
                    visible = false,
                    touchWeight = 0f
                )
                row.copy(keys = row.keys.take(cut) + spacer + row.keys.drop(cut))
            }
        )
    }

    /**
     * Strips bindings the user has switched off.
     *
     * Done here rather than checked at press time so the key surface never has to ask
     * "is this gesture allowed?" — a binding the user disabled simply is not there, and
     * the hint glyph disappears with it.
     */
    fun applyPreferences(
        layer: LayerDef,
        allowFlick: Boolean,
        allowBackspaceWordSwipe: Boolean
    ): LayerDef {
        if (allowFlick && allowBackspaceWordSwipe) return layer

        fun keep(binding: Binding): Boolean {
            val trigger = binding.trigger
            if (trigger !is KeyTrigger.Swipe) return true
            if (!allowFlick && binding.action is KeyAction.Text) return false
            if (!allowBackspaceWordSwipe &&
                binding.action is KeyAction.Backspace &&
                (binding.action as KeyAction.Backspace).unit == TextUnit.WORD
            ) return false
            return true
        }

        fun strip(key: KeyDef): KeyDef {
            val kept = key.bindings.filter(::keep)
            if (kept.size == key.bindings.size) return key
            val lostFlick = !allowFlick && key.bindings.any {
                it.trigger is KeyTrigger.Swipe && it.action is KeyAction.Text
            }
            return key.copy(bindings = kept, hint = if (lostFlick) null else key.hint)
        }

        return layer.copy(
            rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::strip)) },
            freeKeys = layer.freeKeys.map(::strip)
        )
    }

    /** Drops rows from the top, for a shorter keyboard that keeps the important rows. */
    fun trimRows(layer: LayerDef, keep: Int): LayerDef =
        if (keep <= 0 || keep >= layer.rows.size) layer
        else layer.copy(rows = layer.rows.takeLast(keep))

    /** Mirrors each row, for left-handed use of an asymmetric layout. */
    fun mirror(layer: LayerDef): LayerDef =
        layer.copy(rows = layer.rows.map { it.copy(keys = it.keys.reversed()) })

    /**
     * Applies a layer's shift by uppercasing character keys, for layouts that do not
     * define a `shift` layer of their own. Means a one-layer user layout still shifts.
     */
    /**
     * Puts a language's own letters first in every key's alternates.
     *
     * A transform rather than a variant of each layout, because which letters belong to
     * a language is a fact about the language. The scientific board keeps every Greek
     * letter and every operator it had; "ę" simply stops being the sixth thing offered
     * under "e" to somebody writing Polish.
     */
    fun localised(layer: LayerDef, locale: String?): LayerDef {
        if (LanguageKeys.tag(locale).isEmpty()) return layer
        fun transform(key: KeyDef): KeyDef {
            val reordered = LanguageKeys.preferredFor(locale, key)
            return if (reordered == key.popup) key else key.copy(popup = reordered)
        }
        return layer.copy(
            rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::transform)) },
            freeKeys = layer.freeKeys.map(::transform)
        )
    }

    /**
     * What AltGr types, applied to whatever board is on screen.
     *
     * Matched by the letter a key *types* rather than by its id, because ids differ
     * between layouts and the letter does not — so the scientific board, the plain
     * one, and a layout somebody drew themselves all get AltGr from the same map,
     * without any of them declaring a layer for it.
     *
     * Keys the map says nothing about are left exactly as they were, so the board stays
     * recognisable and only the letters that change, change.
     */
    fun altGr(layer: LayerDef, mapping: Map<String, String>, upper: Boolean = false): LayerDef {
        if (mapping.isEmpty()) return layer
        fun transform(key: KeyDef): KeyDef {
            val typed = LanguageKeys.typedBy(key) ?: return key
            val replacement = mapping[typed] ?: return key
            val text = if (upper) replacement.uppercase() else replacement
            return key.copy(
                label = text,
                bindings = key.bindings.map { binding ->
                    if (binding.trigger == KeyTrigger.Tap) Binding(binding.trigger, KeyAction.Text(text))
                    else binding
                },
                popup = emptyList(),
                popupGroups = emptyList()
            )
        }
        return layer.copy(
            rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::transform)) },
            freeKeys = layer.freeKeys.map(::transform)
        )
    }

    fun uppercased(layer: LayerDef): LayerDef {
        fun transform(key: KeyDef): KeyDef {
            val tap = key.tapAction
            if (tap !is KeyAction.Text) return key
            val upper = tap.text.uppercase()
            if (upper == tap.text) return key
            return key.copy(
                label = key.label?.uppercase(),
                bindings = key.bindings.map { binding ->
                    if (binding.trigger == KeyTrigger.Tap) Binding(binding.trigger, KeyAction.Text(upper))
                    else binding
                },
                popup = key.popup.map { it.uppercase() }
            )
        }
        return layer.copy(
            rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::transform)) },
            freeKeys = layer.freeKeys.map(::transform)
        )
    }

    /**
     * Turns a row-based layer into individually placed keys, with no panel implied.
     *
     * Free presentation is a layer transform rather than a second renderer for the
     * same reason split is: placement, hit testing, the probabilistic touch model and
     * long-press popups all already understand a layer whose keys carry explicit
     * bounds, so a layout written years ago for rows can be thrown into free mode and
     * behave correctly without any of them learning a new case.
     *
     * [scale] resizes each key about its own centre; [spreadX]/[spreadY] push the keys
     * apart about the middle of the layer, which is what opens the gaps that a
     * transparent keyboard shows the app through; the origins shift the whole
     * arrangement. Keys already placed absolutely are transformed the same way, so a
     * bitmap-traced layout spreads out like any other.
     *
     * [pinned] overrides any of that for keys the user has dragged somewhere
     * themselves — an arrangement the user made by hand outranks one computed for
     * them.
     */
    fun scatter(
        layer: LayerDef,
        scale: Float = 1f,
        spreadX: Float = 1f,
        spreadY: Float = 1f,
        originX: Float = 0f,
        originY: Float = 0f,
        pinned: Map<String, NormRect> = emptyMap()
    ): LayerDef {
        val placed = mutableListOf<KeyDef>()

        fun transform(key: KeyDef, natural: NormRect): KeyDef {
            pinned[key.id]?.let { return key.copy(bounds = it) }

            val halfW = natural.width * scale / 2f
            val halfH = natural.height * scale / 2f
            val cx = 0.5f + (natural.centerX - 0.5f) * spreadX + originX
            val cy = 0.5f + (natural.centerY - 0.5f) * spreadY + originY
            return key.copy(bounds = NormRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH))
        }

        val totalRowWeight = layer.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        if (totalRowWeight > 0f) {
            var y = 0f
            layer.rows.forEach { row ->
                val rowHeight = row.heightWeight / totalRowWeight
                val totalKeyWeight = row.padStart + row.padEnd +
                    row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                if (totalKeyWeight > 0f) {
                    var x = row.padStart / totalKeyWeight
                    row.keys.forEach { key ->
                        val keyWidth = key.widthWeight / totalKeyWeight
                        // A spacer exists to hold a gap open in a row; with no row left
                        // to hold open it is just an invisible key eating touches.
                        if (key.visible || key.bindings.isNotEmpty()) {
                            placed += transform(key, NormRect(x, y, x + keyWidth, y + rowHeight))
                        }
                        x += keyWidth
                    }
                }
                y += rowHeight
            }
        }

        layer.freeKeys.forEach { key ->
            val natural = key.bounds ?: return@forEach
            placed += transform(key, natural)
        }

        return layer.copy(rows = emptyList(), freeKeys = placed)
    }
}
