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
}
