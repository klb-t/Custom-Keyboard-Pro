package com.example.io

import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.io.NodeQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityNodeDataTest {
    @Suppress("DEPRECATION")
    private fun node() = AccessibilityNodeInfo.obtain().apply {
        text = "do-not-capture-this-value"
        contentDescription = "do-not-capture-this-description"
        viewIdResourceName = "app.test:id/login"
        className = "android.widget.EditText"
        isClickable = true
    }

    @Test fun `password values are absent from screen context and automation facts`() {
        val n = node().apply { isPassword = true }
        assertNull(AccessibilityNodeData.screenText(n))
        val facts = AccessibilityNodeData.facts(n)
        assertNull(facts.text)
        assertNull(facts.desc)
        assertTrue(NodeQuery(id = "login").matches(facts))
        assertFalse(NodeQuery(text = "do-not-capture").matches(facts))
        assertFalse(facts.identity.contains("do-not-capture"))
    }

    @Test fun `Android sensitive metadata also suppresses nonpassword text`() {
        val n = node().apply { isAccessibilityDataSensitive = true }
        assertNull(AccessibilityNodeData.screenText(n))
        assertNull(AccessibilityNodeData.facts(n).text)
    }

    @Test fun `ordinary text remains usable for screen actions`() {
        val n = node()
        assertEquals("do-not-capture-this-value", AccessibilityNodeData.screenText(n))
        assertTrue(NodeQuery(text = "capture").matches(AccessibilityNodeData.facts(n)))
    }
}
