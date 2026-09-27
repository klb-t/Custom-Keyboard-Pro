package com.example.core

import com.example.core.vault.*
import com.nimbusds.jose.JWEObject
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultBackupTest {
    private val passphrase = "An independent recovery phrase 29!".toCharArray()
    private val login = VaultEntry(id = "login-id", kind = VaultKind.LOGIN, label = "Account", username = "użytkownik",
        password = "line1\nsecret\"🔑", website = "https://example.com",
        origin = VaultOrigin("org.example.browser", setOf("a".repeat(64)), "https://example.com"))
    private val card = VaultEntry(id = "card-id", kind = VaultKind.PAYMENT_CARD, label = "Card",
        cardholder = "Example User", cardNumber = "4111111111111111", expiryMonth = "7", expiryYear = "2030")

    @Test fun backupRestoresLoginsAndCardsWithoutTrustBindings() {
        val bytes = VaultBackup.encrypt(listOf(login, card), passphrase)
        val envelope = JWEObject.parse(String(bytes, Charsets.US_ASCII))
        assertEquals("PBES2-HS512+A256KW", envelope.header.algorithm.name)
        assertEquals("A256GCM", envelope.header.encryptionMethod.name)
        assertTrue(envelope.header.getPBES2Count() >= 220_000)
        assertFalse(String(bytes).contains(login.password))
        assertEquals(listOf(login.copy(origin = null), card), VaultBackup.decrypt(bytes, passphrase))
    }

    @Test fun freshSaltAndEncryptionDoNotRepeatTheSameArchive() {
        val one = VaultBackup.encrypt(listOf(login), passphrase)
        val two = VaultBackup.encrypt(listOf(login), passphrase)
        assertFalse(one.contentEquals(two))
        assertEquals(VaultBackup.decrypt(one, passphrase), VaultBackup.decrypt(two, passphrase))
    }

    @Test fun wrongPasswordOrTamperedCiphertextNeverReturnsEntries() {
        val bytes = VaultBackup.encrypt(listOf(login), passphrase)
        assertThrows(Exception::class.java) { VaultBackup.decrypt(bytes, "Another long wrong passphrase".toCharArray()) }
        val parts = String(bytes).split('.').toMutableList()
        val cipher = Base64.getUrlDecoder().decode(parts[3]); cipher[0] = (cipher[0].toInt() xor 1).toByte()
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher)
        assertThrows(Exception::class.java) { VaultBackup.decrypt(parts.joinToString(".").toByteArray(), passphrase) }
    }

    @Test fun rejectsHostileWorkFactorsCompressionAndAlgorithmsBeforeKeyDerivation() {
        val archive = VaultBackup.encrypt(emptyList(), passphrase)
        listOf("p2c" to 2_000_000, "p2c" to 1, "zip" to "DEF", "alg" to "dir", "typ" to "unrelated").forEach { (key, value) ->
            val parts = String(archive).split('.').toMutableList()
            val header = JSONObject(String(Base64.getUrlDecoder().decode(parts[0]))).put(key, value)
            parts[0] = Base64.getUrlEncoder().withoutPadding().encodeToString(header.toString().toByteArray())
            assertThrows(IllegalArgumentException::class.java) { VaultBackup.decrypt(parts.joinToString(".").toByteArray(), passphrase) }
        }
    }

    @Test fun boundsFilesPasswordsAndEntryIds() {
        assertThrows(IllegalArgumentException::class.java) { VaultBackup.decrypt(ByteArray(VaultBackup.MAX_BYTES + 1), passphrase) }
        assertThrows(IllegalArgumentException::class.java) { VaultBackup.encrypt(listOf(login), "short".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { VaultBackup.encrypt(listOf(login.copy(id = "")), passphrase) }
        assertThrows(IllegalArgumentException::class.java) { VaultBackup.encrypt(listOf(login, login), passphrase) }
    }

    @Test fun mergeModesAreExplicitAndDoNotOverwriteByLabel() {
        val changed = login.copy(password = "restored password")
        val other = login.copy(id = "another-id", password = "different account")
        val keep = VaultImports.merge(listOf(login), listOf(changed, other), VaultMergeMode.KEEP_EXISTING)
        assertEquals(1, keep.skipped); assertEquals(1, keep.added); assertEquals(login, keep.entries.first())
        val update = VaultImports.merge(listOf(login), listOf(changed), VaultMergeMode.UPDATE_MATCHING)
        assertEquals(1, update.replaced); assertEquals(changed.copy(origin = null), update.entries.single())
        val both = VaultImports.merge(listOf(login), listOf(changed), VaultMergeMode.KEEP_BOTH)
        assertEquals(2, both.entries.size); assertEquals(2, both.entries.map { it.id }.distinct().size)
        assertEquals(login, both.entries.first()); assertNull(both.entries.last().origin)
    }

    @Test fun mergeRejectsInvalidOrOversizedInputBeforeMutatingExistingData() {
        val current = listOf(login)
        assertThrows(IllegalArgumentException::class.java) { VaultImports.merge(current, listOf(card.copy(cardNumber = "123")), VaultMergeMode.KEEP_BOTH) }
        val many = (1..1000).map { login.copy(id = "new-$it") }
        assertThrows(IllegalArgumentException::class.java) { VaultImports.merge(current, many, VaultMergeMode.KEEP_BOTH) }
        assertEquals(listOf(login), current)
    }
}
