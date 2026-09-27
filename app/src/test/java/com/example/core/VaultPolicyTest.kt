package com.example.core

import com.example.core.vault.VaultCsv
import com.example.core.vault.VaultEntry
import com.example.core.vault.VaultKind
import com.example.core.vault.VaultOrigin
import org.junit.Assert.*
import org.junit.Test

class VaultPolicyTest {
    private val signer = "a".repeat(64)
    private val origin = VaultOrigin("org.example.browser", setOf(signer), "https://example.com")

    @Test fun exactHttpsNormalizationRejectsAmbiguousOrUnsafeOrigins() {
        assertEquals("https://example.com", VaultOrigin.https("HTTPS://EXAMPLE.COM:443/"))
        listOf("http://example.com", "https://example.com.evil.test/path", "https://example.com@evil.test", "https://example.com:8443", "https://example.com/child", "https://example.com?q=x", "https://example.com#foo", "https://example.com.", "https://a..com", "https://*.example.com").forEach {
            assertNull(it, VaultOrigin.https(it))
        }
    }

    @Test fun originsRequireExactPackageSignerSetAndHost() {
        assertTrue(origin.matches(origin.copy()))
        assertFalse(origin.matches(origin.copy(packageName = "org.example.fakebrowser")))
        assertFalse(origin.matches(origin.copy(certificateSha256 = setOf("b".repeat(64)))))
        assertFalse(origin.matches(origin.copy(certificateSha256 = setOf(signer, "b".repeat(64)))))
        assertFalse(origin.matches(origin.copy(httpsOrigin = "https://sub.example.com")))
        assertFalse(origin.matches(origin.copy(httpsOrigin = "https://example.com.evil.test")))
        assertFalse(origin.matches(origin.copy(httpsOrigin = null)))
        assertFalse(origin.copy(certificateSha256 = emptySet()).matches(origin.copy(certificateSha256 = emptySet())))
    }

    @Test fun csvImportsQuotedPasswordsWithoutBindingAnyTarget() {
        val csv = "name,url,username,password\r\n\"My, account\",https://example.com/path,me,\"p\"\"ass,word\"\r\n"
        val entry = VaultCsv.parse(csv).single()
        assertEquals("My, account", entry.label)
        assertEquals("p\"ass,word", entry.password)
        assertEquals("https://example.com", entry.website)
        assertNull(entry.origin)
    }

    @Test fun bitwardenAndFirefoxHeadersSupportedButUnsafeUrlsRemainUnbound() {
        val bitwarden = VaultCsv.parse("name,login_uri,login_username,login_password\naccount,http://example.com,user,password").single()
        assertEquals("", bitwarden.website)
        assertNull(bitwarden.origin)
        val firefox = VaultCsv.parse("url,username,password,httpRealm\nhttps://example.com,user,password,realm").single()
        assertEquals("https://example.com", firefox.website)
        assertNull(firefox.origin)
    }

    @Test fun multilineCsvAndBomAreHandledWithoutDroppingSecretCharacters() {
        val entry = VaultCsv.parse("\uFEFFname,username,password\nlabel,user,\"line1\nline2\"").single()
        assertEquals("line1\nline2", entry.password)
    }

    @Test fun malformedCsvAndMissingSecretsFailEntireImport() {
        listOf("name,user,secret\na,b,c", "username,password\na,\"unclosed", "username,password\na,\"quoted\"junk", "username,password\na,ok\nb,").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { VaultCsv.parse(input) }
        }
        assertThrows(IllegalArgumentException::class.java) { VaultCsv.parse("x".repeat(VaultCsv.MAX_CHARS + 1)) }
    }

    @Test fun cardValidationRejectsInvalidNumbersAndDoesNotMixCredentialKinds() {
        val card = VaultEntry(kind = VaultKind.PAYMENT_CARD, label = "Test card", cardholder = "Test", cardNumber = "4111111111111111", expiryMonth = "12", expiryYear = "2030")
        card.validate()
        assertEquals("•••• 1111 · 12/2030", card.summary)
        assertThrows(IllegalArgumentException::class.java) { card.copy(cardNumber = "4111111111111112").validate() }
        assertThrows(IllegalArgumentException::class.java) { card.copy(expiryMonth = "13").validate() }
        assertThrows(IllegalArgumentException::class.java) { card.copy(password = "secret").validate() }
        val login = VaultEntry(kind = VaultKind.LOGIN, label = "Account", password = "secret")
        assertThrows(IllegalArgumentException::class.java) { login.copy(cardNumber = card.cardNumber).validate() }
    }

    @Test fun bindingCannotDisagreeWithDisplayedWebsite() {
        val login = VaultEntry(kind = VaultKind.LOGIN, label = "Account", password = "secret", website = "https://example.com", origin = origin)
        login.validate()
        assertThrows(IllegalArgumentException::class.java) { login.copy(website = "https://another.test").validate() }
    }
}
