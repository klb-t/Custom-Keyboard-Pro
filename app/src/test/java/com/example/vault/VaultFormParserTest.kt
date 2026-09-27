package com.example.vault

import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.core.vault.VaultKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Synthetic Android structures exercise the actual parser's disclosure boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultFormParserTest {
    private fun node(vararg hints: String, domain: String? = null, scheme: String? = null,
        children: Array<AssistStructure.ViewNode> = emptyArray()): AssistStructure.ViewNode {
        return ReflectionHelpers.callConstructor(AssistStructure.ViewNode::class.java).also {
            ReflectionHelpers.setField(it, "mAutofillHints", hints)
            ReflectionHelpers.setField(it, "mAutofillId", View(ApplicationProvider.getApplicationContext<Context>()).autofillId)
            ReflectionHelpers.setField(it, "mAutofillType", View.AUTOFILL_TYPE_TEXT)
            ReflectionHelpers.setField(it, "mWebDomain", domain)
            ReflectionHelpers.setField(it, "mWebScheme", scheme)
            ReflectionHelpers.setField(it, "mChildren", children)
        }
    }

    private fun structure(root: AssistStructure.ViewNode): AssistStructure = AssistStructure().also { structure ->
        ReflectionHelpers.setField(structure, "mHaveData", true)
        ReflectionHelpers.setField(structure, "mActivityComponent", ComponentName("org.example.app", "org.example.app.Form"))
        val window = ReflectionHelpers.callConstructor(AssistStructure.WindowNode::class.java)
        ReflectionHelpers.setField(window, "mRoot", root)
        ReflectionHelpers.setField(structure, "mWindowNodes", arrayListOf(window))
    }

    @Test fun nativeLoginHintsProduceOnlyLoginFields() {
        val parsed = VaultFormParser.parse(structure(node(children = arrayOf(node("username"), node("password")))))!!
        assertEquals(VaultKind.LOGIN, parsed.kind)
        assertNull(parsed.httpsOrigin)
        assertEquals(setOf(VaultField.USERNAME, VaultField.PASSWORD), parsed.fields.keys)
    }

    @Test fun cardHintsNeverIncludeSecurityCode() {
        val parsed = VaultFormParser.parse(structure(node(children = arrayOf(node("creditCardNumber"), node("creditCardExpirationMonth"),
            node("creditCardExpirationYear"), node("creditCardSecurityCode")))))!!
        assertEquals(VaultKind.PAYMENT_CARD, parsed.kind)
        assertEquals(setOf(VaultField.CARD_NUMBER, VaultField.EXPIRY_MONTH, VaultField.EXPIRY_YEAR), parsed.fields.keys)
    }

    @Test fun exactHttpsWebOriginIsInheritedByFields() {
        val parsed = VaultFormParser.parse(structure(node(domain = "example.com", scheme = "https", children = arrayOf(node("password")))))!!
        assertEquals("https://example.com", parsed.httpsOrigin)
    }

    @Test fun mixedOriginsAndMixedNativeWebCandidatesAreRejected() {
        assertNull(VaultFormParser.parse(structure(node(domain = "example.com", scheme = "https", children = arrayOf(
            node("username"), node("password", domain = "evil.test", scheme = "https"))))))
        assertNull(VaultFormParser.parse(structure(node(children = arrayOf(
            node("username"), node(domain = "example.com", scheme = "https", children = arrayOf(node("password"))))))))
    }

    @Test fun httpMissingSchemeNewPasswordDuplicateRolesAndMixedKindsAreRejected() {
        assertNull(VaultFormParser.parse(structure(node("password", domain = "example.com", scheme = "http"))))
        assertNull(VaultFormParser.parse(structure(node("password", domain = "example.com"))))
        assertNull(VaultFormParser.parse(structure(node(children = arrayOf(node("password"), node("new-password"))))))
        assertNull(VaultFormParser.parse(structure(node(children = arrayOf(node("password"), node("password"))))))
        assertNull(VaultFormParser.parse(structure(node(children = arrayOf(node("password"), node("creditCardNumber"))))))
        assertNull(VaultFormParser.parse(structure(node(children = arrayOf(node("username"))))))
    }
}
