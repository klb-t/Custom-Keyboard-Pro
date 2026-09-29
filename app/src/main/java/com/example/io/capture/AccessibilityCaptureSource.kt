package com.example.io.capture

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo
import com.example.core.capture.CaptureFrame
import com.example.core.capture.CapturedNode
import com.example.core.capture.DisclosurePolicy
import com.example.io.IoAccessibilityService

/** Fresh, recycled node handles for every operation. Never swipes arbitrary screen coordinates. */
@Suppress("DEPRECATION")
internal class AccessibilityCaptureSource(private val service: IoAccessibilityService) {
    data class Target(val pkg: String, val window: Int, val path: List<Int>, val id: String, val type: String)
    private fun recycle(n: Node?) { n?.recycle() }
    private fun privateNode(n: Node) = n.isPassword || n.isEditable
    private fun ready() = IoAccessibilityService.instance === service &&
        (service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != true

    private fun <T> root(block: (Node) -> T): T {
        check(ready()) { "Service unavailable or device locked" }
        val n = service.rootInActiveWindow ?: error("No active application window")
        try {
            val pkg = n.packageName?.toString().orEmpty()
            check(pkg.isNotBlank() && pkg != service.packageName && pkg != "com.android.systemui" && pkg != "android") {
                "Return to the conversation first"
            }
            // An overlay, permission prompt, shade or another split-screen app must not become the target.
            check(service.windows.any { it.id == n.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) {
                "The active window is not an application"
            }
            return block(n)
        } finally { recycle(n) }
    }

    fun bind(): Target = root { root ->
        var bestPath = emptyList<Int>()
        var bestId = root.viewIdResourceName.orEmpty()
        var bestType = root.className?.toString().orEmpty()
        var area = 0L
        var visited = 0
        val deadline = SystemClock.uptimeMillis() + 500
        fun visit(n: Node, path: List<Int>, depth: Int) {
            if (++visited > 2048 || depth > 48 || SystemClock.uptimeMillis() > deadline || privateNode(n) || !n.isVisibleToUser) return
            if (n.isScrollable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                val a = b.width().toLong() * b.height()
                if (a > area) { area = a; bestPath = path; bestId = n.viewIdResourceName.orEmpty(); bestType = n.className?.toString().orEmpty() }
            }
            for (i in 0 until n.childCount.coerceAtMost(2048)) {
                if (visited >= 2048 || SystemClock.uptimeMillis() > deadline) break
                val c = n.getChild(i) ?: continue
                try { visit(c, path + i, depth + 1) } finally { recycle(c) }
            }
        }
        visit(root, emptyList(), 0)
        Target(root.packageName.toString(), root.windowId, bestPath, bestId, bestType)
    }

    private fun <T> scope(target: Target, block: (Node) -> T): T = root { root ->
        check(root.packageName?.toString() == target.pkg && root.windowId == target.window) {
            "Target application/window changed; capture stopped"
        }
        var at = Node.obtain(root)
        try {
            for (index in target.path) {
                check(!privateNode(at)) { "Private ancestor" }
                val next = at.getChild(index) ?: error("Conversation container changed")
                recycle(at); at = next
            }
            check(at.isVisibleToUser && !privateNode(at) && at.viewIdResourceName.orEmpty() == target.id &&
                at.className?.toString().orEmpty() == target.type) { "Conversation container changed" }
            block(at)
        } finally { recycle(at) }
    }

    fun validate(target: Target) { scope(target) { Unit } }

    fun frame(target: Target, elapsed: Long, phase: String): CaptureFrame = scope(target) { node ->
        val out = mutableListOf<CapturedNode>()
        var clipped = false
        var visited = 0
        var chars = 0
        val deadline = SystemClock.uptimeMillis() + 500
        fun text(s: CharSequence?, max: Int): String {
            if (s == null) return ""
            if (s.length > max) clipped = true
            return s.take(max).toString()
        }
        // Return true for a private or uninspected subtree: do not retain aggregate parent text.
        fun visit(n: Node, path: String, parent: String?, depth: Int): Boolean {
            if (++visited > 2048 || depth > 48 || chars > 200_000 || SystemClock.uptimeMillis() > deadline) {
                clipped = true; return true
            }
            if (privateNode(n)) return true
            if (!n.isVisibleToUser) return true
            val index = out.size
            val b = Rect().also { n.getBoundsInScreen(it) }
            out += CapturedNode(path, parent, className = text(n.className, 256),
                resourceId = text(n.viewIdResourceName, 512), bounds = listOf(b.left, b.top, b.right, b.bottom))
            var redactedChild = false
            if (n.childCount > 2048) { clipped = true; redactedChild = true }
            for (i in 0 until n.childCount.coerceAtMost(2048)) {
                if (visited >= 2048 || chars > 200_000 || SystemClock.uptimeMillis() > deadline) { clipped = true; redactedChild = true; break }
                val c = n.getChild(i)
                if (c == null) { redactedChild = true; continue }
                try { redactedChild = visit(c, "$path/$i", path, depth + 1) || redactedChild } finally { recycle(c) }
            }
            if (!redactedChild) {
                val t = text(n.text, 100_000)
                val d = text(n.contentDescription, 20_000)
                val state = if (Build.VERSION.SDK_INT >= 30) text(n.stateDescription, 512) else ""
                out[index] = out[index].copy(text = t, description = d, state = state)
                chars += t.length + d.length + state.length
            }
            return redactedChild
        }
        visit(node, "0", null, 0)
        CaptureFrame(elapsed, phase, out.toList(), clipped)
    }

    fun scroll(target: Target, forward: Boolean): Boolean = scope(target) { n ->
        val action = if (forward) Node.ACTION_SCROLL_FORWARD else Node.ACTION_SCROLL_BACKWARD
        n.actionList.any { it.id == action } && n.performAction(action)
    }

    /** Returns a per-scroll-epoch identity. The same label in another message is not globally suppressed. */
    fun expandOne(target: Target, attempted: MutableSet<String>): Boolean = scope(target) { root ->
        var visits = 0
        val deadline = SystemClock.uptimeMillis() + 500
        fun label(n: Node): String {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(512) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(512) }
            val labels = mutableListOf<String>()
            for (i in 0 until n.childCount.coerceAtMost(4)) {
                val c = n.getChild(i) ?: continue
                try {
                    if (!privateNode(c) && c.isVisibleToUser) c.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(128) }
                } finally { recycle(c) }
            }
            return labels.joinToString(" ")
        }
        fun visit(n: Node, path: String, depth: Int): Boolean {
            if (++visits > 2048 || depth > 48 || SystemClock.uptimeMillis() > deadline || privateNode(n) || !n.isVisibleToUser) return false
            val l = label(n)
            val action = DisclosurePolicy.action(DisclosurePolicy.Control(
                label = l, state = if (Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString().orEmpty() else "",
                expand = n.actionList.any { it.id == Node.ACTION_EXPAND }, collapse = n.actionList.any { it.id == Node.ACTION_COLLAPSE },
                clickable = n.isClickable, checkable = n.isCheckable,
                link = n.className?.toString()?.contains("link", ignoreCase = true) == true
            ))
            val key = "$path|${n.viewIdResourceName}|$l"
            if (action != null && attempted.add(key)) {
                val id = if (action == DisclosurePolicy.Action.EXPAND) Node.ACTION_EXPAND else Node.ACTION_CLICK
                if (n.performAction(id)) return true
            }
            for (i in 0 until n.childCount.coerceAtMost(2048)) {
                if (visits >= 2048 || SystemClock.uptimeMillis() > deadline) break
                val c = n.getChild(i) ?: continue
                try { if (visit(c, "$path/$i", depth + 1)) return true } finally { recycle(c) }
            }
            return false
        }
        visit(root, "0", 0)
    }

    fun selectFocused(target: Target): Boolean = scope(target) { root ->
        val n = root.findFocus(Node.FOCUS_ACCESSIBILITY) ?: return@scope false
        try {
            var ancestor: Node? = Node.obtain(n)
            var sensitive = false
            var hops = 0
            while (ancestor != null) {
                val a = ancestor
                if (++hops > 64 || privateNode(a)) { sensitive = true; recycle(a); break }
                ancestor = a.parent
                recycle(a)
            }
            val value = n.text
            if (sensitive || value.isNullOrEmpty() || n.actionList.none { it.id == Node.ACTION_SET_SELECTION }) return@scope false
            n.performAction(Node.ACTION_SET_SELECTION, Bundle().apply {
                putInt(Node.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
            })
        } finally { recycle(n) }
    }
}
