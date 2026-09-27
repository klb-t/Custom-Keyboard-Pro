package com.example.core.vault

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.PasswordBasedDecrypter
import com.nimbusds.jose.crypto.PasswordBasedEncrypter
import java.nio.CharBuffer
import java.util.UUID

/** Portable JWE, separate from the device-authenticated local vault and its key. */
object VaultBackup {
    const val MAX_BYTES = 3 * 1024 * 1024
    private const val MAX_PLAINTEXT = 2 * 1024 * 1024 - 128
    private const val ITERATIONS = 220_000 // PBKDF2-HMAC-SHA512; OWASP, checked 2026-09-28.
    private const val MAX_ITERATIONS = 1_000_000 // Reject hostile work factors before deriving a key.
    private const val TYPE = "io-matrix-vault-backup+jwe"

    fun encrypt(entries: List<VaultEntry>, passphrase: CharArray): ByteArray {
        require(passphrase.size in 12..1024) { "Use a long, unique backup passphrase of 12–1024 characters." }
        require(entries.size <= VaultCsv.MAX_ENTRIES)
        entries.forEach(VaultEntry::validate)
        require(entries.map { it.id }.distinct().size == entries.size)
        // App trust must be confirmed on the receiving phone; no signer binding is exported.
        val plaintext = VaultStore.encode(entries.map { it.copy(origin = null) }).toByteArray(Charsets.UTF_8)
        val password = utf8(passphrase)
        try {
            require(plaintext.size <= MAX_PLAINTEXT) { "Vault is too large for a backup." }
            val header = JWEHeader.Builder(JWEAlgorithm.PBES2_HS512_A256KW, EncryptionMethod.A256GCM)
                .type(JOSEObjectType(TYPE)).contentType("application/json").build()
            val jwe = JWEObject(header, Payload(plaintext))
            jwe.encrypt(PasswordBasedEncrypter(password, 32, ITERATIONS))
            return jwe.serialize().toByteArray(Charsets.US_ASCII).also { require(it.size <= MAX_BYTES) }
        } finally { plaintext.fill(0); password.fill(0) }
    }

    fun decrypt(archive: ByteArray, passphrase: CharArray): List<VaultEntry> {
        require(archive.size in 1..MAX_BYTES) { "Backup exceeds the 3 MiB limit." }
        require(passphrase.size in 12..1024) { "Enter the backup passphrase." }
        require(archive.all { it.toInt() in 33..126 }) { "Not a compact JWE backup." }
        val compact = String(archive, Charsets.US_ASCII)
        val parts = compact.split('.')
        require(parts.size == 5 && parts[0].length <= 4096 && parts[1].length <= 128 &&
            parts[2].length <= 64 && parts[4].length <= 64) { "Invalid backup envelope." }
        val jwe = JWEObject.parse(compact)
        val h = jwe.header
        require(h.algorithm == JWEAlgorithm.PBES2_HS512_A256KW && h.encryptionMethod == EncryptionMethod.A256GCM &&
            h.type?.type == TYPE && h.contentType == "application/json" && h.compressionAlgorithm == null &&
            h.criticalParams.isNullOrEmpty()) { "Unsupported backup format or encryption." }
        require(h.getPBES2Count() in ITERATIONS..MAX_ITERATIONS && h.getPBES2Salt()?.decode()?.size in 16..64) {
            "Invalid backup derivation parameters."
        }
        val password = utf8(passphrase)
        try {
            jwe.decrypt(PasswordBasedDecrypter(password))
            val plaintext = jwe.payload.toBytes()
            try {
                require(plaintext.size <= MAX_PLAINTEXT)
                // Validate the whole archive before returning any entry. Restore is a separate action.
                return VaultStore.decode(String(plaintext, Charsets.UTF_8)).map { it.copy(origin = null) }
            } finally { plaintext.fill(0) }
        } finally { password.fill(0) }
    }

    private fun utf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { out ->
            buffer.get(out)
            if (buffer.hasArray()) buffer.array().fill(0)
        }
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
