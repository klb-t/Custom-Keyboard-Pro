package com.example.core

import com.example.core.vault.VaultEntry
import com.example.core.vault.VaultKind
import com.example.core.vault.VaultOrigin
import com.example.core.vault.VaultStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCodecTest {
    @Test fun entryRoundTripPreservesSecretsAndPinnedIdentity() {
        val entry = VaultEntry(kind = VaultKind.LOGIN, label = "Account", username = "user", password = "line1\n\"line2\"",
            website = "https://example.com", origin = VaultOrigin("org.example.browser", setOf("a".repeat(64)), "https://example.com"))
        assertEquals(listOf(entry), VaultStore.decode(VaultStore.encode(listOf(entry))))
    }

    @Test fun unsupportedVersionAndDuplicateIdsFailClosed() {
        assertThrows(Exception::class.java) { VaultStore.decode("{\"version\":2,\"entries\":[]}") }
        val entry = VaultEntry(kind = VaultKind.LOGIN, label = "Account", password = "secret")
        assertThrows(IllegalArgumentException::class.java) { VaultStore.decode(VaultStore.encode(listOf(entry, entry))) }
    }
}
