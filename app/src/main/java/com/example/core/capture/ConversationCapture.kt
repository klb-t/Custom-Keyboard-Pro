package com.example.core.capture

import java.security.MessageDigest
import java.util.Locale

/** Session-local policy. No screen monitoring exists outside an explicitly started session. */
data class CaptureOptions(
    val autoScroll: Boolean = true,
    val seekStart: Boolean = true,
    val expandDetails: Boolean = true,
    val maxFrames: Int = 200,
    val maxChars: Int = 1_000_000,
    val maxMillis: Long = 600_000,
    val settleMillis: Long = 700,
    val includeOffscreenNodes: Boolean = true,
    val includeNotImportantViews: Boolean = false,
    val captureNestedScrolls: Boolean = true
) {
    init {
        require(maxFrames in 1..1000)
        require(maxChars in 1000..2_000_000)
        require(maxMillis in 1000..1_800_000)
        require(settleMillis in 300..5000)
    }
}

/** Paths are scoped to ONE viewport, never treated as persistent message IDs. */
data class CapturedNode(
    val path: String,
    val parent: String?,
    val text: String = "",
    val description: String = "",
    val className: String = "",
    val resourceId: String = "",
    val state: String = "",
    val bounds: List<Int> = emptyList(),
    val semantics: NodeSemantics = NodeSemantics()
)

data class CaptureFrame(
    val elapsedMillis: Long,
    val phase: String,
    val nodes: List<CapturedNode>,
    val clipped: Boolean = false,
    val diagnostics: Map<String, Int> = emptyMap()
) {
    fun textLines(): List<String> = nodes.flatMap { n ->
        listOf(n.text, n.description.takeUnless { it == n.text }.orEmpty())
            .filter { it.isNotBlank() }
    }
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(nodes.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Conservative, data-driven allowlist. An unknown label is NOT permission to click. */
object DisclosurePolicy {
    enum class Action { EXPAND, CLICK }
    data class Control(
        val label: String,
        val state: String = "",
        val expand: Boolean = false,
        val collapse: Boolean = false,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val password: Boolean = false,
        val checkable: Boolean = false,
        val link: Boolean = false,
        val enabled: Boolean = true,
        val expandedState: Int? = null
    )
    val labels: List<Regex> = listOf(
        "^(show|view|read) (more|details|reasoning|thinking|thought process|tool calls|tool results|sources)(\\s*[:·(].{0,60})?$",
        "^(thought|thinking) for .{1,60}$",
        "^(reasoning|thinking|thought process|tool calls|tool results|sources)(\\s*[:·(].{0,60})?$",
        "^(pokaż|rozwiń|czytaj) (więcej|szczegóły|rozumowanie|tok rozumowania|myślenie|wyniki narzędzi|wywołania narzędzi|źródła)$",
        "^(rozumowanie|myślenie|wywołania narzędzi|wyniki narzędzi|źródła)$",
        "^(toon|bekijk|lees) (meer|details|redenering|gedachten|bronnen)$",
        "^(redenering|gedachten|bronnen)$"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val collapsed = setOf("collapsed", "zwinięte", "zwinięty", "zwinięta", "ingeklapt")

    fun action(c: Control): Action? {
        if (!c.enabled || c.editable || c.password || c.checkable || c.link || c.expandedState == 3) return null
        val label = c.label.trim().replace(Regex("\\s+"), " ")
        // A genuine EXPAND capability takes priority over a translated button caption.
        // Refuse clearly unrelated/destructive controls even if a provider mislabels them.
        if (Regex("^(delete|send|retry|share|buy|next conversation|usuń|wyślij|kup|verwijder|verstuur)(\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(label)) return null
        if (c.expand) return Action.EXPAND
        if (labels.none { it.matches(label) }) return null
        val isCollapsed = c.expandedState == 1 ||
            ((c.expandedState == null || c.expandedState == 0) && c.state.trim().lowercase(Locale.ROOT) in collapsed)
        if (c.clickable && !c.collapse && isCollapsed) return Action.CLICK
        return null
    }
}

/** Raw frames are authoritative. The convenience text never globally deduplicates messages. */
class ConversationArchive(
    val packageName: String,
    val windowId: Int,
    val startedAt: String,
    val options: CaptureOptions
) {
    private val captured = mutableListOf<CaptureFrame>()
    val frames: List<CaptureFrame> get() = captured.toList()
    private var used = 0
    var reason: String = "in_progress"
        private set
    var truncated: Boolean = false
        private set
    var startStatus: String = "not_requested"
    /** Accepted requests, NOT proof that the provider changed its UI or exposed content. */
    var expanded: Int = 0
    var showOnScreenRequests: Int = 0
    var nestedScrollRequests: Int = 0
    val detailRequests: Int get() = expanded + showOnScreenRequests + nestedScrollRequests
    val characters: Int get() = used
    val full: Boolean get() = captured.size >= options.maxFrames || used >= options.maxChars || truncated

    fun append(frame: CaptureFrame): Boolean {
        if (full) return false
        var clipped = frame.clipped
        val safe = mutableListOf<CapturedNode>()
        for (n in frame.nodes.take(2048)) {
            val cost = n.text.length + n.description.length + n.className.length + n.resourceId.length +
                n.state.length + n.path.length + (n.parent?.length ?: 0) + n.semantics.json().length + 128
            if (used + cost > options.maxChars) { clipped = true; break }
            safe += n.copy(bounds = n.bounds.toList(), semantics = n.semantics.detached())
            used += cost
        }
        if (frame.nodes.size > 2048) clipped = true
        captured += frame.copy(nodes = safe.toList(), clipped = clipped, diagnostics = frame.diagnostics.toMap())
        truncated = truncated || clipped
        return true
    }

    fun finish(why: String) { reason = why }

    fun text(): String = buildString {
        append("IO Matrix — exposed conversation capture\n")
        append("Source: ").append(packageName).append("; window: ").append(windowId).append('\n')
        append("Start coverage: ").append(startStatus).append('\n')
        append("Started: ").append(startedAt).append("; stop: ").append(reason).append('\n')
        append("Completeness: unverified. Only exposed UI text; no hidden reasoning or unexposed tool payloads.\n")
        append("Read non-visible exposed nodes: ").append(options.includeOffscreenNodes).append("; extended tree requested: ").append(options.includeNotImportantViews).append('\n')
        append("Accepted detail requests: expand=").append(expanded).append(", show=").append(showOnScreenRequests).append(", nested scroll=").append(nestedScrollRequests).append(". Effects are unverified; inspect subsequent frames.\n")
        append("Inputs/password/sensitive subtrees excluded. Roles are not inferred. JSON retains viewport hierarchy and revisions.\n")
        if (truncated) append("WARNING: capture was clipped by a resource limit.\n")
        var previous = emptyList<String>()
        captured.forEachIndexed { i, frame ->
            val lines = frame.textLines()
            // Only adjacent, multi-block overlap is removed in this VIEW. Raw JSON never removes it.
            val overlap = overlap(previous, lines)
            if (i > 0 && overlap == 0) append("\n[Viewport boundary / possible gap, repetition or expansion revision]\n")
            append("\n--- Viewport ").append(i + 1).append(" (").append(frame.phase).append(") ---\n")
            lines.drop(overlap).forEach { append(it).append('\n') }
            if (frame.clipped) append("[This viewport was clipped]\n")
            previous = lines
        }
    }

    fun json(): String = buildString {
        append("{\"schemaVersion\":2,\"source\":\"android-accessibility\",\"completeness\":\"unverified\",")
        append("\"package\":").append(quote(packageName)).append(",\"windowId\":").append(windowId)
        append(",\"startedAt\":").append(quote(startedAt)).append(",\"stopReason\":").append(quote(reason))
        append(",\"startStatus\":").append(quote(startStatus))
        append(",\"truncated\":").append(truncated).append(",\"expanded\":").append(expanded)
        append(",\"showOnScreenRequests\":").append(showOnScreenRequests)
        append(",\"nestedScrollRequests\":").append(nestedScrollRequests)
        append(",\"actionEffects\":\"unverified; counters record accepted requests\"")
        append(",\"options\":{\"autoScroll\":").append(options.autoScroll)
        append(",\"seekStart\":").append(options.seekStart).append(",\"expandDetails\":").append(options.expandDetails)
        append(",\"includeOffscreenNodes\":").append(options.includeOffscreenNodes)
        append(",\"includeNotImportantViews\":").append(options.includeNotImportantViews)
        append(",\"captureNestedScrolls\":").append(options.captureNestedScrolls)
        append(",\"maxFrames\":").append(options.maxFrames).append(",\"maxChars\":").append(options.maxChars)
        append(",\"maxMillis\":").append(options.maxMillis).append("},\"frames\":[")
        captured.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            append("{\"sequence\":").append(index).append(",\"elapsedMillis\":").append(frame.elapsedMillis)
            append(",\"phase\":").append(quote(frame.phase)).append(",\"clipped\":").append(frame.clipped)
            append(",\"diagnostics\":{").append(frame.diagnostics.entries.joinToString(",") { quote(it.key) + ":" + it.value }).append('}')
            append(",\"nodes\":[")
            frame.nodes.forEachIndexed { j, n ->
                if (j > 0) append(',')
                append("{\"path\":").append(quote(n.path)).append(",\"parent\":").append(n.parent?.let { quote(it) } ?: "null")
                append(",\"text\":").append(quote(n.text)).append(",\"description\":").append(quote(n.description))
                append(",\"className\":").append(quote(n.className)).append(",\"resourceId\":").append(quote(n.resourceId))
                append(",\"state\":").append(quote(n.state)).append(",\"bounds\":[").append(n.bounds.joinToString(",")).append(']')
                append(",\"semantics\":").append(n.semantics.json()).append('}')
            }
            append("]}")
        }
        append("]}")
    }

    /** Selectable inspector view; JSON remains the authoritative structured record. */
    fun treeText(): String = buildString {
        append("IO Matrix — accessibility tree (not an app heap or a completeness claim)\n")
        captured.forEachIndexed { i, f ->
            append("\nFRAME ").append(i).append(" ").append(f.phase).append(" ").append(f.diagnostics).append('\n')
            f.nodes.forEach { n ->
                append(n.path).append(" ").append(n.className).append(" #").append(n.resourceId).append('\n')
                append("  ").append(n.semantics.json()).append(" bounds=").append(n.bounds).append('\n')
                if (n.text.isNotEmpty()) append("  text: ").append(n.text).append('\n')
                if (n.description.isNotEmpty()) append("  description: ").append(n.description).append('\n')
                if (n.state.isNotEmpty()) append("  state: ").append(n.state).append('\n')
            }
        }
    }

    companion object {
        /** Do not identify separate occurrences of short/repeated replies by their text. */
        fun overlap(previous: List<String>, next: List<String>): Int {
            for (count in minOf(previous.size, next.size) downTo 2) {
                val tail = previous.takeLast(count)
                if (tail.distinct().size >= 2 && tail.sumOf { it.length } >= 80 && tail == next.take(count)) return count
            }
            return 0
        }
        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
