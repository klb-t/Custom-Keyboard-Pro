package com.example.core.capture

/** Provider facts, not inferred roles. Missing keys mean unavailable, not false/zero. */
data class NodeSemantics(
    val flags: Map<String, Boolean> = emptyMap(),
    val numbers: Map<String, Int> = emptyMap(),
    val uniqueId: String = "",
    val actions: List<NodeAction> = emptyList()
) {
    fun detached() = copy(flags = flags.toMap(), numbers = numbers.toMap(), actions = actions.toList())
    fun json(): String = buildString {
        append("{\"flags\":{").append(flags.entries.joinToString(",") { ConversationArchive.quote(it.key) + ":" + it.value })
        append("},\"numbers\":{").append(numbers.entries.joinToString(",") { ConversationArchive.quote(it.key) + ":" + it.value })
        append("},\"uniqueId\":").append(ConversationArchive.quote(uniqueId)).append(",\"actions\":[")
        append(actions.joinToString(",") { "{\"id\":${it.id},\"label\":${ConversationArchive.quote(it.label)}}" })
        append("]}")
    }
}
data class NodeAction(val id: Int, val label: String = "")

/** The adapter owns the root; this reader closes every acquired child, including on failure. */
interface CaptureTreeNode : AutoCloseable {
    val privateSubtree: Boolean
    val visible: Boolean
    val childCount: Int
    fun child(index: Int): CaptureTreeNode?
    fun describe(path: String, parent: String?): CapturedNode
}

object CaptureTreeReader {
    data class Limits(val nodes: Int = 2048, val depth: Int = 48, val chars: Int = 200_000, val millis: Long = 250) {
        init { require(nodes > 0 && depth > 0 && chars > 0 && millis > 0) }
    }
    fun read(root: CaptureTreeNode, elapsed: Long, phase: String, includeOffscreen: Boolean,
             now: () -> Long, limits: Limits = Limits()): CaptureFrame {
        val out = mutableListOf<CapturedNode>()
        val counts = linkedMapOf<String, Int>()
        val started = now()
        var visited = 0; var chars = 0; var clipped = false
        fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        fun limited() = visited >= limits.nodes || chars >= limits.chars || now() - started >= limits.millis
        fun take(s: String, max: Int): String {
            val limit = minOf(max, (limits.chars - chars).coerceAtLeast(0))
            if (s.length > limit) { clipped = true; count("text_clipped") }
            return s.take(limit).also { chars += it.length }
        }
        // A redacted or unreadable descendant invalidates aggregate text on EVERY ancestor.
        fun visit(node: CaptureTreeNode, path: String, parent: String?, depth: Int): Boolean {
            if (limited() || depth > limits.depth) { clipped = true; count("traversal_limit"); return true }
            visited++
            if (node.privateSubtree) { count("private_subtrees_excluded"); return true }
            val visible = node.visible
            if (!visible) count("nonvisible_nodes_exposed")
            val index = out.size
            out += CapturedNode(path, parent)
            var redacted = false
            val children = node.childCount
            for (i in 0 until minOf(children, limits.nodes)) {
                if (limited()) { clipped = true; count("traversal_limit"); redacted = true; break }
                val child = node.child(i)
                if (child == null) { count("unavailable_children"); redacted = true; continue }
                try { redacted = visit(child, "$path/$i", path, depth + 1) || redacted }
                finally { child.close() }
            }
            if (children > limits.nodes) { clipped = true; redacted = true; count("child_limit") }
            val n = node.describe(path, parent)
            val omit = redacted || (!visible && !includeOffscreen)
            if (omit) count(if (redacted) "aggregate_text_redacted" else "nonvisible_text_excluded")
            if (n.semantics.actions.size > 32) { clipped = true; count("actions_clipped") }
            val flags = n.semantics.flags.toMutableMap().apply {
                put("visibleToUser", visible)
                if (redacted) put("redactedDescendant", true)
                if (omit) put("textOmitted", true)
            }
            out[index] = n.copy(
                path = path, parent = parent,
                text = if (omit) "" else take(n.text, 100_000),
                description = if (omit) "" else take(n.description, 20_000),
                state = if (omit) "" else take(n.state, 512),
                className = take(n.className, 256), resourceId = take(n.resourceId, 512),
                bounds = n.bounds.take(4),
                semantics = n.semantics.copy(flags = flags.toMap(), numbers = n.semantics.numbers.toMap(),
                    uniqueId = if (omit) "" else take(n.semantics.uniqueId, 512),
                    actions = n.semantics.actions.take(32).map { it.copy(label = if (omit) "" else take(it.label, 256)) })
            )
            return redacted || (!visible && !includeOffscreen)
        }
        visit(root, "0", null, 0)
        counts["nodes_visited"] = visited
        return CaptureFrame(elapsed, phase, out.toList(), clipped, counts.toMap())
    }
}

/** Attempt identities are scoped to a scroll epoch; paths are never permanent message IDs. */
object InnerScrollPolicy {
    fun direction(key: String, signature: String, seekStart: Boolean, attempted: MutableSet<String>): Boolean? {
        if (seekStart && "inner_ready:$key" !in attempted) {
            if (attempted.add("inner_back:$key:$signature")) return false
            attempted.add("inner_ready:$key")
        }
        return if (attempted.add("inner_forward:$key:$signature")) true else null
    }
    fun backwardUnavailable(key: String, attempted: MutableSet<String>) { attempted.add("inner_ready:$key") }
}

/** Platform IDs are injected; the planner itself is JVM-testable and never owns live UI objects. */
data class DetailActionIds(val expand: Int, val collapse: Int, val click: Int, val show: Int)
data class DetailCandidate(val node: CapturedNode, val progress: DetailProgress, val actionId: Int?, val key: String)
object DetailPlanner {
    fun candidates(frame: CaptureFrame, options: CaptureOptions, ids: DetailActionIds): List<DetailCandidate> {
        val visible = mutableListOf<DetailCandidate>(); val outside = mutableListOf<DetailCandidate>()
        val show = mutableListOf<DetailCandidate>(); val inner = mutableListOf<DetailCandidate>()
        val children = frame.nodes.groupBy { it.parent }
        for (n in frame.nodes) {
            val f = n.semantics.flags
            if (f["redactedDescendant"] == true || f["enabled"] == false) continue
            val onScreen = f["visibleToUser"] == true
            val key = "${n.path}|${n.semantics.uniqueId}|${n.resourceId}|${n.semantics.numbers["rowIndex"]}"
            if (options.expandDetails && (onScreen || options.includeOffscreenNodes)) {
                val label = n.text.ifBlank { n.description }.ifBlank {
                    children[n.path].orEmpty().filter { it.semantics.flags["textOmitted"] != true }
                        .take(4).joinToString(" ") { it.text.ifBlank { it.description }.take(128) }
                }.take(512)
                val actionIds = n.semantics.actions.map { it.id }
                val action = DisclosurePolicy.action(DisclosurePolicy.Control(label, n.state,
                    expand = ids.expand in actionIds, collapse = ids.collapse in actionIds,
                    clickable = f["clickable"] == true && ids.click in actionIds,
                    checkable = f["checkable"] == true, link = f["link"] == true,
                    expandedState = n.semantics.numbers["expandedState"]))
                if (action != null) {
                    val identity = "$key|$label|${n.semantics.numbers["expandedState"]}"
                    if (onScreen || action == DisclosurePolicy.Action.EXPAND) {
                        val c = DetailCandidate(n, DetailProgress.EXPAND_REQUESTED,
                            if (action == DisclosurePolicy.Action.EXPAND) ids.expand else ids.click, "expand:$onScreen:$identity")
                        if (onScreen) visible += c else outside += c
                    }
                    if (!onScreen && options.autoScroll && ids.show in actionIds)
                        show += DetailCandidate(n, DetailProgress.SHOW_REQUESTED, ids.show, "show:$identity")
                }
            }
            if (options.autoScroll && options.captureNestedScrolls && onScreen && f["scrollable"] == true && n.path != "0")
                inner += DetailCandidate(n, DetailProgress.NESTED_SCROLL_REQUESTED, null, key)
        }
        return visible + outside + show + inner.sortedByDescending { it.node.path.count { c -> c == '/' } }
    }
}
