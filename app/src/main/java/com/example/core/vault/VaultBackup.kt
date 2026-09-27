package com.example.core.vault

import com.example.core.security.PasswordEnvelope
import java.util.UUID

/** Portable vault data with a purpose-bound standard JWE envelope. */
object VaultBackup {
    const val MAX_BYTES = 3 * 1024 * 1024
    private val envelope = PasswordEnvelope("io-matrix-vault-backup+jwe", 2 * 1024 * 1024 - 128, MAX_BYTES)
    fun encrypt(entries: List<VaultEntry>, passphrase: CharArray): ByteArray {
        require(entries.size <= VaultCsv.MAX_ENTRIES)
        entries.forEach(VaultEntry::validate)
        require(entries.map { it.id }.distinct().size == entries.size)
        val plaintext = VaultStore.encode(entries.map { it.copy(origin = null) }).toByteArray(Charsets.UTF_8)
        return try { envelope.encrypt(plaintext, passphrase) } finally { plaintext.fill(0) }
    }
    fun decrypt(archive: ByteArray, passphrase: CharArray): List<VaultEntry> {
        val plaintext = envelope.decrypt(archive, passphrase)
        return try { VaultStore.decode(String(plaintext, Charsets.UTF_8)).map { it.copy(origin = null) } }
        finally { plaintext.fill(0) }
    }
}

enum class VaultMergeMode(val label: String) {
    KEEP_EXISTING("Keep existing entries"),
    UPDATE_MATCHING("Update matching backup IDs"),
    KEEP_BOTH("Keep both copies")
}

data class VaultMergeResult(val entries: List<VaultEntry>, val added: Int, val replaced: Int, val skipped: Int)

object VaultImports {
    fun merge(current: List<VaultEntry>, incoming: List<VaultEntry>, mode: VaultMergeMode): VaultMergeResult {
        (current + incoming).forEach(VaultEntry::validate)
        require(current.map { it.id }.distinct().size == current.size)
        require(incoming.map { it.id }.distinct().size == incoming.size)
        val result = current.associateByTo(linkedMapOf()) { it.id }
        var added = 0; var replaced = 0; var skipped = 0
        incoming.forEach { original ->
            var entry = original.copy(origin = null)
            if (result.containsKey(entry.id)) {
                when (mode) {
                    VaultMergeMode.KEEP_EXISTING -> { skipped++; return@forEach }
                    VaultMergeMode.UPDATE_MATCHING -> replaced++
                    VaultMergeMode.KEEP_BOTH -> { entry = entry.copy(id = UUID.randomUUID().toString()); added++ }
                }
            } else added++
            result[entry.id] = entry
        }
        require(result.size <= VaultCsv.MAX_ENTRIES) { "Import would exceed the 1000-entry limit." }
        return VaultMergeResult(result.values.toList(), added, replaced, skipped)
    }
}
