package com.example.core.io

/**
 * A thing on screen reduced to what a query can ask about it.
 *
 * The accessibility tree hands out live platform objects that cannot be built in a
 * test and go stale as the screen changes. Every decision — does this match, is this
 * an item already done, what to do to it — is made on this copy instead, so the part
 * that decides can be tested and the part that touches the platform stays thin.
 */
data class NodeFacts(
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    /**
     * Its row in the list, when the list says — RecyclerView and most feeds do. The
     * one identity that survives scrolling whatever the item looks like.
     */
    val row: Int? = null,
    /** Where it is, for telling two identical-looking rows apart on one screen. */
    val top: Int = 0,
    val left: Int = 0
) {
    /**
     * What identifies this item across scrolling.
     *
     * The list's own row number when there is one. Otherwise what the item says —
     * position is left out, because after a scroll the same post is somewhere else and
     * must not be done twice (for a selection, twice is undone). Only an item that
     * says nothing at all falls back to where it is, since the alternative is every
     * picture-only row counting as the first one.
     */
    val identity: String
        get() = when {
            row != null -> "row:$row"
            text.isNullOrBlank() && desc.isNullOrBlank() -> "at:$left,$top:${viewId.orEmpty()}"
            else -> listOf(viewId.orEmpty(), className.orEmpty(), text.orEmpty(), desc.orEmpty())
                .joinToString("\u0001")
        }
}

/** "The button that says X": what [Verbs] `click`, `long_click` and `sweep` look for. */
data class NodeQuery(
    val text: String? = null,
    val id: String? = null,
    val desc: String? = null,
    val index: Int = 0
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && id.isNullOrBlank() && desc.isNullOrBlank()

    /** Every stated condition holds; an empty query matches nothing rather than everything. */
    fun matches(n: NodeFacts): Boolean {
        if (isEmpty) return false
        text?.takeIf { it.isNotBlank() }?.let { t ->
            if (n.text?.contains(t, ignoreCase = true) != true) return false
        }
        desc?.takeIf { it.isNotBlank() }?.let { d ->
            if (n.desc?.contains(d, ignoreCase = true) != true) return false
        }
        id?.takeIf { it.isNotBlank() }?.let { wanted ->
            val have = n.viewId ?: return false
            // "com.app:id/checkbox", "id/checkbox" and "checkbox" all name the same view.
            if (!(have == wanted || have.endsWith("/$wanted") || have.endsWith(":$wanted"))) return false
        }
        return true
    }

    companion object {
        fun from(command: Command) = NodeQuery(
            text = command.arg("text"),
            id = command.arg("id"),
            desc = command.arg("desc"),
            index = command.int("index") ?: 0
        )
    }
}

/**
 * Doing the same thing to every item down a list.
 *
 * The use case that asked for accessibility in the first place: select forty posts in
 * a feed without forty long-presses. Feeds disagree about how selection works, so the
 * mode says which kind this one is — and whatever the mode, an item is done once,
 * however many times scrolling brings it back into view.
 */
object Sweep {

    enum class Mode {
        /** Tick every checkbox that is not ticked yet. */
        CHECKBOXES,

        /** Long-press the first item to start selecting, tap every one after it. */
        LONGPRESS,

        /** Tap every item — for lists already in selection mode. */
        TAP,

        /** Press every control matching the query — "Like", "Add", "Follow". */
        MATCH;

        companion object {
            fun parse(raw: String?): Mode =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: CHECKBOXES
        }
    }

    enum class Act { CLICK, LONG_CLICK }

    data class Step(val index: Int, val act: Act)

    /**
     * What to do on the screen now showing.
     *
     * [candidates] are the items on this screen in reading order — checkable nodes,
     * list rows or query matches, depending on [mode]. [seen] carries over between
     * screens and is updated here. [started] is whether an earlier screen already
     * began a selection, which is what turns the first long-press into taps.
     */
    fun plan(
        mode: Mode,
        candidates: List<NodeFacts>,
        seen: MutableSet<String>,
        started: Boolean
    ): List<Step> {
        val steps = mutableListOf<Step>()
        var begun = started
        candidates.forEachIndexed { i, item ->
            val key = item.identity
            if (key in seen) return@forEachIndexed
            when (mode) {
                Mode.CHECKBOXES -> {
                    if (!item.checkable || item.checked) {
                        seen += key
                        return@forEachIndexed
                    }
                    steps += Step(i, Act.CLICK)
                }
                Mode.LONGPRESS -> {
                    steps += Step(i, if (begun) Act.CLICK else Act.LONG_CLICK)
                    begun = true
                }
                Mode.TAP, Mode.MATCH -> steps += Step(i, Act.CLICK)
            }
            seen += key
        }
        return steps
    }
}
