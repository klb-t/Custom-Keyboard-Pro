package com.example.core.layout

import android.graphics.Bitmap
import com.example.core.hitmap.HitmapExtractor

/**
 * Builds layouts from things that are not layouts: a grid inferred from a screenshot,
 * a painted colour mask, or a description.
 *
 * Everything produced here is an ordinary [LayoutDef] with no special status, so the
 * user can open it in the JSON editor and change anything the importer guessed wrong.
 */
object LayoutAuthoring {

    /**
     * A guessed label for a key at a given position in a QWERTY-shaped grid.
     *
     * The importer cannot read the glyphs off the picture, so it seeds the layout with
     * the letters a keyboard of that shape usually has. Wrong guesses are visible and
     * one tap to fix, which beats an unlabelled grid.
     */
    private val SEED_ROWS = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private fun seedLabel(row: Int, column: Int, rowCount: Int, columnCount: Int): String? {
        // Assume the last row is the space row and work upwards from the letter rows.
        val letterRowIndex = row - (rowCount - 1 - SEED_ROWS.size)
        if (letterRowIndex !in SEED_ROWS.indices) return null
        val source = SEED_ROWS[letterRowIndex]
        if (columnCount == source.length) return source[column].toString()
        // Different key count: spread the letters across what is there.
        if (columnCount in 1 until source.length) {
            val index = (column * source.length / columnCount).coerceIn(0, source.length - 1)
            return source[index].toString()
        }
        return source.getOrNull(column)?.toString()
    }

    /** Turns an inferred grid into a row-flow layout. */
    fun fromGrid(
        grid: HitmapExtractor.Grid,
        id: String,
        name: String,
        backgroundFile: String? = null
    ): LayoutDef {
        val rowCount = grid.rows.size
        val rows = grid.rows.mapIndexed { rowIndex, cells ->
            val totalWidth = cells.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(0.0001f)
            val keys = cells.mapIndexed { columnIndex, rect ->
                val label = seedLabel(rowIndex, columnIndex, rowCount, cells.size)
                KeyDef(
                    id = "k_${rowIndex}_$columnIndex",
                    label = label,
                    widthWeight = (rect.width / totalWidth * cells.size).coerceIn(0.4f, 8f),
                    bindings = if (label != null) {
                        listOf(Binding(KeyTrigger.Tap, KeyAction.Text(label)))
                    } else {
                        emptyList()
                    },
                    style = if (label == null) "special" else null
                )
            }
            RowDef(keys = keys, heightWeight = cells.firstOrNull()?.height ?: 1f)
        }

        return LayoutDef(
            id = id,
            name = name,
            layers = linkedMapOf(LayoutDef.BASE_LAYER to LayerDef(LayoutDef.BASE_LAYER, rows)),
            background = backgroundFile?.let { BackgroundDef(imageFile = it) },
            rowCountHint = rowCount,
            description = "Traced from a picture. Every key is editable; unlabelled keys " +
                "were detected but not guessed."
        )
    }

    /**
     * Turns a colour mask into absolutely-placed keys over the picture it came from.
     *
     * This is the mode that imposes nothing: a key can be any rectangle anywhere, so a
     * layout can be circular, staggered, overlapping, or shaped like something that is
     * not a keyboard at all.
     */
    fun fromRegions(
        regions: List<HitmapExtractor.Region>,
        id: String,
        name: String,
        backgroundFile: String?,
        weightMapFile: String? = null
    ): LayoutDef {
        val keys = regions.mapIndexed { index, region ->
            KeyDef(
                id = "r_$index",
                label = null,
                bindings = emptyList(),
                bounds = region.bounds,
                style = "normal",
                visible = backgroundFile == null
            )
        }
        return LayoutDef(
            id = id,
            name = name,
            layers = linkedMapOf(
                LayoutDef.BASE_LAYER to LayerDef(LayoutDef.BASE_LAYER, rows = emptyList(), freeKeys = keys)
            ),
            background = backgroundFile?.let {
                BackgroundDef(imageFile = it, sensitivityFile = weightMapFile)
            },
            description = "Built from a colour mask: ${keys.size} regions. Assign what each " +
                "one does in the key editor."
        )
    }

    /** Applies a greyscale weight map to a layout's keys as touch weights. */
    fun applyWeightMap(layout: LayoutDef, weights: Bitmap): LayoutDef {
        fun weigh(key: KeyDef): KeyDef {
            val bounds = key.bounds ?: return key
            val weight = HitmapExtractor.averageWeight(weights, bounds)
            return key.copy(touchWeight = (weight * 2f).coerceIn(0.05f, 4f))
        }
        return layout.copy(
            layers = layout.layers.mapValues { (_, layer) ->
                layer.copy(
                    rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::weigh)) },
                    freeKeys = layer.freeKeys.map(::weigh)
                )
            }
        )
    }

    /** Gives every unbound key in a layout a tap action that commits its label. */
    fun bindLabelsAsText(layout: LayoutDef): LayoutDef {
        fun bind(key: KeyDef): KeyDef {
            if (key.bindings.isNotEmpty()) return key
            val label = key.label ?: return key
            return key.copy(bindings = listOf(Binding(KeyTrigger.Tap, KeyAction.Text(label))))
        }
        return layout.copy(
            layers = layout.layers.mapValues { (_, layer) ->
                layer.copy(
                    rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::bind)) },
                    freeKeys = layer.freeKeys.map(::bind)
                )
            }
        )
    }

    /** Replaces a single key everywhere it appears. */
    fun replaceKey(layout: LayoutDef, keyId: String, replacement: KeyDef): LayoutDef {
        fun swap(key: KeyDef) = if (key.id == keyId) replacement else key
        return layout.copy(
            layers = layout.layers.mapValues { (_, layer) ->
                layer.copy(
                    rows = layer.rows.map { row -> row.copy(keys = row.keys.map(::swap)) },
                    freeKeys = layer.freeKeys.map(::swap)
                )
            }
        )
    }
}
