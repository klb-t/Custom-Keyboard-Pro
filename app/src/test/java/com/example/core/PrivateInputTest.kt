package com.example.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.example.core.config.Settings
import com.example.core.security.PrivateInputContract
import com.example.ime.EditorController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateInputTest {
    @Test fun `external context does not read private fields and still reads ordinary selection`() {
        var reads = 0
        val connection = java.lang.reflect.Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, _ ->
            if (method.name == "getSelectedText") { reads++; "private-value" }
            else error("Unexpected input connection read: ${method.name}")
        } as InputConnection
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            privateImeOptions = PrivateInputContract.MARKER
        }
        val editor = EditorController({ connection }, { info }, { Settings() })
        assertEquals("", editor.textForExternalUse(100))
        assertEquals(0, reads)
        info.privateImeOptions = null
        assertEquals("private-value", editor.textForExternalUse(100))
        assertEquals(1, reads)
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals("", editor.textForExternalUse(100))
        assertEquals(1, reads)
    }

    @Test fun `private form identifiers are sensitive even when visible text`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            privateImeOptions = PrivateInputContract.MARKER
        }
        val editor = EditorController({ null }, { info }, { Settings() })
        assertFalse(editor.isPasswordField)
        assertTrue(editor.isSensitive)
        info.privateImeOptions = null
        assertFalse(editor.isSensitive)
        info.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertTrue(editor.isSensitive)
    }

    @Test fun `private marker uses token equality`() {
        assertTrue(PrivateInputContract.isPrivate("vendor.option; io.matrix.private"))
        assertFalse(PrivateInputContract.isPrivate("not.io.matrix.private"))
        assertFalse(PrivateInputContract.isPrivate(null))
    }
}
