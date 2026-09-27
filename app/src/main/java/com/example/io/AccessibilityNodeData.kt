package com.example.io

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.io.NodeFacts

/** Structural automation remains possible without copying private field contents. */
internal object AccessibilityNodeData {
    fun isPrivate(node: AccessibilityNodeInfo): Boolean {
        var at: AccessibilityNodeInfo? = node
        var depth = 0
        while (at != null && depth++ <= 64) {
            if (at.isPassword || (Build.VERSION.SDK_INT >= 34 && at.isAccessibilityDataSensitive)) return true
            at = at.parent
        }
        // A malformed/cyclic hierarchy is not evidence that a field is public.
        return at != null
    }

    fun screenText(node: AccessibilityNodeInfo): String? {
        if (isPrivate(node)) return null
        return node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            ?: node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }

    fun facts(node: AccessibilityNodeInfo): NodeFacts {
        val private = isPrivate(node)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return NodeFacts(
            text = if (private) null else node.text?.toString(),
            desc = if (private) null else node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            row = node.collectionItemInfo?.rowIndex,
            top = bounds.top,
            left = bounds.left
        )
    }
}
