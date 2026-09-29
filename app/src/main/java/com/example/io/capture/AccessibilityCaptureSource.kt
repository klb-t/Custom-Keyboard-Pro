package com.example.io.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo
import com.example.core.capture.*
import com.example.io.IoAccessibilityService

/** Fresh, bounded, pinned tree access. No screen coordinates, private app state or background reads. */
@Suppress("DEPRECATION")
internal class AccessibilityCaptureSource(private val service: IoAccessibilityService) {
    data class Target(val pkg: String, val window: Int, val path: List<Int>, val id: String, val type: String, val uniqueId: String = "")
    private var options = CaptureOptions()
    private var addedFlags = 0
    private var lastFrame: CaptureFrame? = null
    private var triedTopPosition = false
    private fun recycle(n: Node?) { n?.recycle() }
    private fun privateNode(n: Node) = n.isPassword || n.isEditable ||
        (Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive)
    private fun unique(n: Node) = if (Build.VERSION.SDK_INT >= 33) n.uniqueId.orEmpty() else ""
    private fun ready() = IoAccessibilityService.instance === service &&
        (service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != true

    /** Temporary additive lease; preserve key filtering and every pre-existing service flag. */
    fun begin(value: CaptureOptions) {
        close(); options = value; triedTopPosition = false
        val info = service.serviceInfo ?: error("No accessibility configuration")
        val requested = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            (if (value.includeNotImportantViews) AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS else 0)
        addedFlags = requested and info.flags.inv()
        info.flags = info.flags or requested
        try { service.serviceInfo = info } catch (e: Exception) { close(); throw e }
    }
    fun close() {
        if (addedFlags != 0) runCatching {
            service.serviceInfo?.let { info -> info.flags = info.flags and addedFlags.inv(); service.serviceInfo = info }
        }
        addedFlags = 0; lastFrame = null
    }

    private fun <T> root(block: (Node) -> T): T {
        check(ready()) { "Service unavailable or device locked" }
        val n = service.rootInActiveWindow ?: error("No active application window")
        try {
            check(n.refresh()) { "Stale application window" }
            val pkg = n.packageName?.toString().orEmpty()
            check(pkg.isNotBlank() && pkg != service.packageName && pkg != "com.android.systemui" && pkg != "android") {
                "Return to the conversation first"
            }
            val windows = service.windows
            try { check(windows.any { it.id == n.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) }
            finally { windows.forEach { it.recycle() } }
            return block(n)
        } finally { recycle(n) }
    }

    fun bind(fullWindow: Boolean = false): Target = root { root ->
        var bestPath = emptyList<Int>(); var bestId = root.viewIdResourceName.orEmpty()
        var bestType = root.className?.toString().orEmpty(); var bestUnique = unique(root)
        var area = 0L; var visited = 0
        val deadline = SystemClock.uptimeMillis() + 250
        fun visit(n: Node, path: List<Int>, depth: Int) {
            if (++visited > 2048 || depth > 48 || SystemClock.uptimeMillis() > deadline || privateNode(n)) return
            if (n.isVisibleToUser && n.isScrollable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                val a = b.width().coerceAtLeast(0).toLong() * b.height().coerceAtLeast(0)
                if (a > area) { area = a; bestPath = path; bestId = n.viewIdResourceName.orEmpty(); bestType = n.className?.toString().orEmpty(); bestUnique = unique(n) }
            }
            for (i in 0 until n.childCount.coerceAtMost(2048)) {
                if (visited >= 2048 || SystemClock.uptimeMillis() > deadline) break
                val c = n.getChild(i) ?: continue
                try { visit(c, path + i, depth + 1) } finally { recycle(c) }
            }
        }
        if (!fullWindow) visit(root, emptyList(), 0)
        Target(root.packageName.toString(), root.windowId, bestPath, bestId, bestType, bestUnique)
    }

    private fun <T> scope(target: Target, block: (Node) -> T): T = root { root ->
        check(root.packageName?.toString() == target.pkg && root.windowId == target.window) { "Target window changed" }
        var at = Node.obtain(root)
        try {
            for (index in target.path) {
                check(!privateNode(at)) { "Private ancestor" }
                val next = at.getChild(index) ?: error("Conversation container changed")
                recycle(at); at = next
            }
            check(at.refresh() && at.isVisibleToUser && !privateNode(at) && at.viewIdResourceName.orEmpty() == target.id &&
                at.className?.toString().orEmpty() == target.type && (target.uniqueId.isEmpty() || unique(at) == target.uniqueId)) { "Conversation container changed" }
            block(at)
        } finally { recycle(at) }
    }

    private inner class TreeNode(private val n: Node, private val viewport: Rect) : CaptureTreeNode {
        override val privateSubtree get() = privateNode(n)
        override val visible get() = n.isVisibleToUser
        override val childCount get() = n.childCount
        override fun child(index: Int): CaptureTreeNode? = n.getChild(index)?.let { TreeNode(it, viewport) }
        override fun close() { recycle(n) }
        override fun describe(path: String, parent: String?): CapturedNode {
            val b = Rect().also { n.getBoundsInScreen(it) }
            val flags = linkedMapOf("intersectsViewportBounds" to Rect.intersects(b, viewport), "enabled" to n.isEnabled,
                "clickable" to n.isClickable, "scrollable" to n.isScrollable, "selected" to n.isSelected,
                "checkable" to n.isCheckable, "checked" to n.isChecked, "focusable" to n.isFocusable,
                "accessibilityFocused" to n.isAccessibilityFocused, "importantForAccessibility" to n.isImportantForAccessibility)
            val text = n.text
            flags["link"] = n.className?.toString()?.contains("link", true) == true ||
                (text is Spanned && text.getSpans(0, text.length, URLSpan::class.java).isNotEmpty())
            if (Build.VERSION.SDK_INT >= 28) flags["heading"] = n.isHeading
            val numbers = linkedMapOf("childCount" to n.childCount)
            if (Build.VERSION.SDK_INT >= 36) { numbers["expandedState"] = n.expandedState; numbers["checkedState"] = n.checked }
            n.collectionInfo?.let { numbers["rowCount"] = it.rowCount; numbers["columnCount"] = it.columnCount; flags["hierarchical"] = it.isHierarchical }
            n.collectionItemInfo?.let { numbers["rowIndex"] = it.rowIndex; numbers["columnIndex"] = it.columnIndex }
            return CapturedNode(path, parent, n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(),
                n.className?.toString().orEmpty(), n.viewIdResourceName.orEmpty(),
                if (Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString().orEmpty() else "",
                listOf(b.left, b.top, b.right, b.bottom), NodeSemantics(flags, numbers, unique(n),
                    n.actionList.take(33).map { NodeAction(it.id, it.label?.toString().orEmpty()) }))
        }
    }

    fun frame(target: Target, elapsed: Long, phase: String): CaptureFrame = scope(target) { node ->
        val viewport = Rect().also { node.getBoundsInScreen(it) }
        CaptureTreeReader.read(TreeNode(node, viewport), elapsed, phase, options.includeOffscreenNodes, SystemClock::uptimeMillis)
            .let { it.copy(diagnostics = it.diagnostics + ("service_flags" to (service.serviceInfo?.flags ?: 0))) }
            .also { lastFrame = it }
    }
    fun validate(target: Target) { scope(target) { Unit } }

    private fun move(n: Node, forward: Boolean): Boolean {
        if (!n.refresh() || privateNode(n) || !n.isEnabled) return false
        val actions = if (forward) listOf(Node.AccessibilityAction.ACTION_SCROLL_DOWN.id, Node.ACTION_SCROLL_FORWARD)
            else listOf(Node.AccessibilityAction.ACTION_SCROLL_UP.id, Node.ACTION_SCROLL_BACKWARD)
        return actions.any { id -> n.actionList.any { it.id == id } && n.performAction(id) }
    }
    fun scroll(target: Target, forward: Boolean): Boolean = scope(target) { n ->
        if (!forward && !triedTopPosition) {
            triedTopPosition = true
            val id = Node.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
            val info = n.collectionInfo
            if (info != null && info.rowCount > 0 && !info.isHierarchical && n.actionList.any { it.id == id } &&
                n.performAction(id, Bundle().apply { putInt(Node.ACTION_ARGUMENT_ROW_INT, 0); putInt(Node.ACTION_ARGUMENT_COLUMN_INT, 0) })) return@scope true
        }
        move(n, forward)
    }

    /** Re-resolve paths inside the pinned container; never retain live nodes between ticks. */
    private fun <T> atPath(root: Node, expected: CapturedNode, block: (Node) -> T): T? {
        var n = Node.obtain(root)
        try {
            for (part in expected.path.split('/').drop(1)) {
                if (privateNode(n)) return null
                val child = n.getChild(part.toInt()) ?: return null
                recycle(n); n = child
            }
            if (!n.refresh() || privateNode(n) || !n.isEnabled ||
                n.className?.toString().orEmpty() != expected.className || n.viewIdResourceName.orEmpty() != expected.resourceId ||
                (expected.semantics.uniqueId.isNotEmpty() && unique(n) != expected.semantics.uniqueId) ||
                n.isVisibleToUser != expected.semantics.flags["visibleToUser"] ||
                n.text?.toString().orEmpty() != expected.text || n.contentDescription?.toString().orEmpty() != expected.description ||
                (Build.VERSION.SDK_INT >= 30 && n.stateDescription?.toString().orEmpty() != expected.state) ||
                (Build.VERSION.SDK_INT >= 36 && n.expandedState != expected.semantics.numbers["expandedState"])) return null
            return block(n)
        } finally { recycle(n) }
    }

    /** One accepted operation per tick, revalidating the window before EACH attempted operation. */
    fun advanceDetails(target: Target, attempted: MutableSet<String>): DetailProgress {
        val frame = lastFrame ?: return DetailProgress.NONE
        val ids = DetailActionIds(Node.ACTION_EXPAND, Node.ACTION_COLLAPSE, Node.ACTION_CLICK, Node.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        val deadline = SystemClock.uptimeMillis() + 250
        for (candidate in DetailPlanner.candidates(frame, options, ids)) {
            if (attempted.size >= 128 || SystemClock.uptimeMillis() > deadline) break
            if (candidate.progress != DetailProgress.NESTED_SCROLL_REQUESTED && candidate.key in attempted) continue
            for (retry in 0..1) {
                var retryForward = false
                val result = scope(target) { root -> atPath(root, candidate.node) { n ->
                    if (candidate.progress == DetailProgress.NESTED_SCROLL_REQUESTED) {
                        val path = candidate.node.path
                        val nodes = frame.nodes.filter { it.path == path || it.path.startsWith("$path/") }
                        val signature = CaptureFrame(0, "inner", nodes).fingerprint()
                        val forward = InnerScrollPolicy.direction(candidate.key, signature, options.seekStart, attempted)
                            ?: return@atPath DetailProgress.NONE
                        if (move(n, forward)) DetailProgress.NESTED_SCROLL_REQUESTED else {
                            if (!forward) { InnerScrollPolicy.backwardUnavailable(candidate.key, attempted); retryForward = true }
                            DetailProgress.NONE
                        }
                    } else {
                        val id = candidate.actionId ?: return@atPath DetailProgress.NONE
                        if (!n.actionList.any { it.id == id } || !attempted.add(candidate.key)) return@atPath DetailProgress.NONE
                        // Toggle captions can live in children. A recycled virtual row must not
                        // inherit the previous row's permission to click, even with the same ID.
                        if (id == Node.ACTION_CLICK) {
                            val viewport = Rect().also { n.getBoundsInScreen(it) }
                            val fresh = CaptureTreeReader.read(TreeNode(n, viewport), 0, "action_check", true,
                                SystemClock::uptimeMillis, CaptureTreeReader.Limits(nodes = 64, depth = 8, chars = 16_000, millis = 100))
                            if (fresh.clipped || DetailPlanner.candidates(fresh, options, ids).none {
                                it.node.path == "0" && it.actionId == Node.ACTION_CLICK
                            }) return@atPath DetailProgress.NONE
                        }
                        if (n.performAction(id)) candidate.progress else DetailProgress.NONE
                    }
                } } ?: DetailProgress.NONE
                if (result != DetailProgress.NONE) return result
                if (!retryForward) break
            }
        }
        return DetailProgress.NONE
    }

    fun selectFocused(target: Target): Boolean = scope(target) { root ->
        val n = root.findFocus(Node.FOCUS_ACCESSIBILITY) ?: return@scope false
        try {
            var ancestor: Node? = Node.obtain(n); var sensitive = false; var hops = 0
            while (ancestor != null) {
                val a = ancestor
                if (++hops > 64 || privateNode(a)) { sensitive = true; recycle(a); break }
                ancestor = a.parent; recycle(a)
            }
            val value = n.text
            if (sensitive || value.isNullOrEmpty() || n.actionList.none { it.id == Node.ACTION_SET_SELECTION }) return@scope false
            n.performAction(Node.ACTION_SET_SELECTION, Bundle().apply {
                putInt(Node.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
            })
        } finally { recycle(n) }
    }
}
