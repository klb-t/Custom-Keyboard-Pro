package com.example.core.layout

/**
 * Reads a layout and says what is wrong with it — and, where it can, hands back a
 * fixed copy.
 *
 * This exists because of a decision made everywhere else in the app: a layout is
 * data, and a model is allowed to write it. That is the right trade — it is what
 * makes "describe the keyboard you want" possible at all — but it means the code
 * that draws a layout will be handed things a compiler would never have let through:
 * an element pointing at a layer nobody defined, a shift key that switches to a layer
 * that does not exist, two keys sharing an id, a popup tab with nothing in it.
 *
 * None of those throw. They produce a keyboard that is subtly wrong on a phone, with
 * no way to find out why — which is the worst possible failure for a project whose
 * only test device belongs to the user.
 *
 * So the checks live here, once, as pure functions over [LayoutDef]:
 *
 *  - [check] lists the problems, in the words the person who has to fix them needs;
 *  - [repair] returns the nearest layout that has none of the fixable ones.
 *
 * [repair] is deliberately conservative. It never invents keys and never drops one:
 * a key the user asked for that ends up in the wrong place is a complaint, but a key
 * that silently vanished is a bug report with nothing to go on.
 */
object LayoutDoctor {

    enum class Severity {
        /** The layout will draw wrongly, or not at all. */
        BROKEN,

        /** It will draw, but something in it cannot work as written. */
        SUSPECT
    }

    data class Finding(
        val severity: Severity,
        val where: String,
        val message: String,
        /** True when [repair] knows how to deal with this one. */
        val repairable: Boolean
    ) {
        override fun toString(): String = "$where: $message"
    }

    fun check(layout: LayoutDef): List<Finding> {
        val out = mutableListOf<Finding>()

        if (layout.layers.isEmpty()) {
            out += Finding(Severity.BROKEN, layout.id, "has no layers at all", repairable = false)
            return out
        }

        if (layout.layers[layout.defaultLayer] == null) {
            out += Finding(
                Severity.BROKEN, layout.id,
                "opens on layer '${layout.defaultLayer}', which does not exist " +
                    "(it has: ${layout.layers.keys.joinToString()})",
                repairable = true
            )
        }

        layout.elements.forEach { element ->
            if (layout.layers[element.layer] == null) {
                out += Finding(
                    Severity.BROKEN, "element '${element.id}'",
                    "shows layer '${element.layer}', which does not exist",
                    repairable = true
                )
            }
            if (element.placement != ElementPlacement.DOCKED) {
                val bounds = element.bounds
                if (bounds == null) {
                    out += Finding(
                        Severity.BROKEN, "element '${element.id}'",
                        "is ${element.placement.name.lowercase()} but says nothing about where it sits",
                        repairable = true
                    )
                } else if (bounds.width <= 0f || bounds.height <= 0f) {
                    out += Finding(
                        Severity.BROKEN, "element '${element.id}'",
                        "has no size: ${bounds.describe()}",
                        repairable = true
                    )
                } else if (bounds.right <= 0f || bounds.bottom <= 0f ||
                    bounds.left >= 1f || bounds.top >= 1f
                ) {
                    out += Finding(
                        Severity.BROKEN, "element '${element.id}'",
                        "sits entirely off screen: ${bounds.describe()}",
                        repairable = true
                    )
                }
            }
        }

        layout.elements.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            out += Finding(
                Severity.BROKEN, "element '$id'", "is defined more than once", repairable = true
            )
        }

        layout.layers.forEach { (name, layer) ->
            val keys = layer.rows.flatMap { it.keys } + layer.freeKeys
            keys.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
                out += Finding(
                    Severity.SUSPECT, "layer '$name'",
                    "two keys share the id '$id'; touch learning, popup memory and " +
                        "cursor avoidance all address keys by id, so they will be crossed",
                    repairable = true
                )
            }

            keys.forEach { key ->
                key.bindings.map { it.action }.filterIsInstance<KeyAction.Layer>().forEach { action ->
                    if (layout.layers[action.layer] == null) {
                        out += Finding(
                            Severity.SUSPECT, "key '${key.id}' on layer '$name'",
                            "switches to layer '${action.layer}', which does not exist",
                            repairable = false
                        )
                    }
                }
                key.popupGroups.filter { it.items.isEmpty() }.forEach { group ->
                    out += Finding(
                        Severity.SUSPECT, "key '${key.id}'",
                        "has a popup tab '${group.id}' with nothing in it",
                        repairable = true
                    )
                }
                key.popupGroups.groupingBy { it.id }.eachCount().filterValues { it > 1 }
                    .keys.forEach { id ->
                        out += Finding(
                            Severity.SUSPECT, "key '${key.id}'",
                            "has two popup tabs called '$id'; only one can be remembered",
                            repairable = true
                        )
                    }
                if (key.bindings.isEmpty() && key.visible) {
                    out += Finding(
                        Severity.SUSPECT, "key '${key.id}' on layer '$name'",
                        "is drawn but does nothing when pressed",
                        repairable = false
                    )
                }
            }

            if (layer.rows.isEmpty() && layer.freeKeys.isEmpty()) {
                out += Finding(
                    Severity.SUSPECT, "layer '$name'", "is empty", repairable = false
                )
            }
        }

        return out
    }

    /**
     * The nearest layout with none of the repairable problems.
     *
     * Where a fix has to choose, it chooses the option that keeps the most of what
     * was written: a bad default layer becomes the first layer rather than an error,
     * a duplicate key id is suffixed rather than dropped, an element with nowhere to
     * be gets somewhere visible rather than disappearing.
     */
    fun repair(layout: LayoutDef): LayoutDef {
        if (layout.layers.isEmpty()) return layout

        val layers = layout.layers.mapValues { (_, layer) ->
            val taken = mutableSetOf<String>()
            fun unique(key: KeyDef): KeyDef {
                if (taken.add(key.id)) return key
                // Keep counting past a suffix that is itself already in use, so one
                // pass is enough: a layer holding "a", "a" and "a_1" must not come
                // out of the repair with two keys called "a_1".
                var n = 1
                while (!taken.add("${key.id}_$n")) n++
                return key.copy(id = "${key.id}_$n")
            }
            fun tidy(key: KeyDef): KeyDef {
                val groups = key.popupGroups.filter { it.items.isNotEmpty() }
                val deduped = groups.distinctBy { it.id }
                return if (deduped == key.popupGroups) key else key.copy(popupGroups = deduped)
            }
            layer.copy(
                rows = layer.rows.map { row -> row.copy(keys = row.keys.map { tidy(unique(it)) }) },
                freeKeys = layer.freeKeys.map { tidy(unique(it)) }
            )
        }

        val firstLayer = layers.keys.first()
        val defaultLayer =
            if (layers.containsKey(layout.defaultLayer)) layout.defaultLayer
            else if (layers.containsKey(LayoutDef.BASE_LAYER)) LayoutDef.BASE_LAYER
            else firstLayer

        val seenElements = mutableSetOf<String>()
        val elements = layout.elements.mapNotNull { element ->
            if (!seenElements.add(element.id)) return@mapNotNull null
            val layer = if (layers.containsKey(element.layer)) element.layer else defaultLayer
            val bounds = when {
                element.placement == ElementPlacement.DOCKED -> element.bounds
                else -> element.bounds?.takeIf { it.isUsable() } ?: DEFAULT_FLOATING_BOUNDS
            }
            element.copy(layer = layer, bounds = bounds)
        }

        return layout.copy(layers = layers, defaultLayer = defaultLayer, elements = elements)
    }

    /** Somewhere unmistakably on screen, for an element that did not say. */
    private val DEFAULT_FLOATING_BOUNDS = NormRect(0.25f, 0.45f, 0.75f, 0.8f)

    private fun NormRect.isUsable(): Boolean =
        width > 0f && height > 0f && right > 0f && bottom > 0f && left < 1f && top < 1f

    private fun NormRect.describe(): String =
        "[%.2f, %.2f, %.2f, %.2f]".format(left, top, right, bottom)
}
