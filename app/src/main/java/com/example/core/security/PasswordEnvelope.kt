package com.example.core.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.PasswordBasedDecrypter
import com.nimbusds.jose.crypto.PasswordBasedEncrypter
import java.nio.CharBuffer

/** Standard authenticated envelopes. Purpose and limits are fixed by the caller, never by input. */
class PasswordEnvelope(private val purpose: String, private val maxPlaintext: Int, val maxBytes: Int) {
    private val ITERATIONS = 220_000 // PBKDF2-HMAC-SHA512; OWASP, checked 2026-09-28.
    private val MAX_ITERATIONS = 1_000_000 // Reject hostile work factors before deriving a key.

    fun encrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size in 12..1024) { "Use a passphrase of 12–1024 characters." }
        val plaintext = data.copyOf()
        val password = utf8(passphrase)
        try {
            require(plaintext.size <= maxPlaintext) { "Vault is too large for a backup." }
            val header = JWEHeader.Builder(JWEAlgorithm.PBES2_HS512_A256KW, EncryptionMethod.A256GCM)
                .type(JOSEObjectType(purpose)).contentType("application/json").build()
            val jwe = JWEObject(header, Payload(plaintext))
            jwe.encrypt(PasswordBasedEncrypter(password, 32, ITERATIONS))
            return jwe.serialize().toByteArray(Charsets.US_ASCII).also { require(it.size <= maxBytes) }
        } finally { plaintext.fill(0); password.fill(0) }
    }

    fun decrypt(archive: ByteArray, passphrase: CharArray): ByteArray {
        require(archive.size in 1..maxBytes) { "Encrypted file exceeds its size limit." }
        require(passphrase.size in 12..1024) { "Enter the backup passphrase." }
        require(archive.all { it.toInt() in 33..126 }) { "Not a compact JWE backup." }
        val compact = String(archive, Charsets.US_ASCII)
        val parts = compact.split('.')
        require(parts.size == 5 && parts[0].length <= 4096 && parts[1].length <= 128 &&
            parts[2].length <= 64 && parts[4].length <= 64) { "Invalid backup envelope." }
        val jwe = JWEObject.parse(compact)
        val h = jwe.header
        require(h.algorithm == JWEAlgorithm.PBES2_HS512_A256KW && h.encryptionMethod == EncryptionMethod.A256GCM &&
            h.type?.type == purpose && h.contentType == "application/json" && h.compressionAlgorithm == null &&
            h.criticalParams.isNullOrEmpty()) { "Unsupported backup format or encryption." }
        require(h.getPBES2Count() in ITERATIONS..MAX_ITERATIONS && h.getPBES2Salt()?.decode()?.size in 16..64) {
            "Invalid backup derivation parameters."
        }
        val password = utf8(passphrase)
        try {
            jwe.decrypt(PasswordBasedDecrypter(password))
            val plaintext = jwe.payload.toBytes()
            try {
                require(plaintext.size <= maxPlaintext)
                return plaintext.copyOf()
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
