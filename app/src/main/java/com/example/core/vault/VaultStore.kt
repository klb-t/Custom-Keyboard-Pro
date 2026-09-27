package com.example.core.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No singleton plaintext cache, backup, logging, clipboard, network or settings export. */
class VaultStore(context: Context) {
    private val app = context.applicationContext
    private val atomic = AtomicFile(File(app.noBackupFilesDir, "io-matrix-vault-v1.enc"))

    fun prepareAuthentication() {
        require(app.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Set a device screen lock before using the vault." }
        synchronized(storageLock) {
            val ks = keyStore()
            if (!ks.containsAlias(KEY_ALIAS)) {
                check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
                    "The vault key is unavailable. Existing encrypted data has been preserved."
                }
                val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setUserAuthenticationParameters(SESSION_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(SESSION_SECONDS)
                }
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
            }
        }
    }

    /** Must follow a fresh successful system credential challenge. Keystore enforces access. */
    fun unlock(sessionSeconds: Int = SESSION_SECONDS): VaultSession = synchronized(storageLock) {
        val entries = if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            val bytes = atomic.openRead().use { input ->
                val data = input.readBytesBounded(MAX_BYTES)
                check(data.size >= MAGIC.size + 12 + 16) { "Vault data is invalid." }
                data
            }
            val header = bytes.copyOfRange(0, MAGIC.size)
            check(header.contentEquals(MAGIC)) { "Unsupported vault format." }
            val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC)
            val plaintext = cipher.doFinal(bytes, MAGIC.size + 12, bytes.size - MAGIC.size - 12)
            try { decode(String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
        } else {
            // Exercise the authenticated key even for a first, empty vault.
            write(emptyList())
            emptyList()
        }
        VaultSession(this, entries, SystemClock.elapsedRealtime() + sessionSeconds.coerceIn(15, SESSION_SECONDS) * 1000L, generation)
    }

    internal fun save(entries: List<VaultEntry>, expectedGeneration: Long): Long = synchronized(storageLock) {
        check(generation == expectedGeneration) { "The vault changed in another window. Unlock it again before editing." }
        write(entries)
        ++generation
    }

    private fun write(entries: List<VaultEntry>) {
        require(entries.size <= VaultCsv.MAX_ENTRIES)
        entries.forEach(VaultEntry::validate)
        val plaintext = encode(entries).toByteArray(Charsets.UTF_8)
        try {
            require(plaintext.size < MAX_BYTES - 128) { "Vault is full." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(MAGIC)
            val encrypted = cipher.doFinal(plaintext)
            val bytes = ByteBuffer.allocate(MAGIC.size + cipher.iv.size + encrypted.size).put(MAGIC).put(cipher.iv).put(encrypted).array()
            var out: java.io.FileOutputStream? = null
            try { out = atomic.startWrite(); out.write(bytes); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        } finally { plaintext.fill(0) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey = (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)
        ?: error("The vault key is unavailable.")

    companion object {
        const val SESSION_SECONDS = 60
        private const val KEY_ALIAS = "io.matrix.vault.aes.v1"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val MAGIC = "IOMV1".toByteArray(Charsets.US_ASCII)
        private val storageLock = Any()
        private var generation = 0L

        internal fun encode(entries: List<VaultEntry>): String = JSONObject().put("version", 1)
            .put("entries", JSONArray().apply { entries.forEach { e ->
                put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("label", e.label)
                    .put("username", e.username).put("password", e.password).put("cardholder", e.cardholder)
                    .put("cardNumber", e.cardNumber).put("expiryMonth", e.expiryMonth).put("expiryYear", e.expiryYear)
                    .put("website", e.website).apply { e.origin?.let { origin ->
                        put("origin", JSONObject().put("package", origin.packageName).put("certificates", JSONArray(origin.certificateSha256.sorted()))
                            .put("https", origin.httpsOrigin ?: JSONObject.NULL))
                    } })
            } }).toString()

        internal fun decode(text: String): List<VaultEntry> {
            val root = JSONObject(text)
            check(root.getInt("version") == 1)
            val array = root.getJSONArray("entries")
            require(array.length() <= VaultCsv.MAX_ENTRIES)
            return (0 until array.length()).map { i ->
                val e = array.getJSONObject(i)
                val o = e.optJSONObject("origin")
                VaultEntry(id = e.getString("id"), kind = VaultKind.valueOf(e.getString("kind")), label = e.getString("label"),
                    username = e.optString("username"), password = e.optString("password"), cardholder = e.optString("cardholder"),
                    cardNumber = e.optString("cardNumber"), expiryMonth = e.optString("expiryMonth"), expiryYear = e.optString("expiryYear"),
                    website = e.optString("website"), origin = o?.let {
                        val hashes = it.getJSONArray("certificates")
                        VaultOrigin(it.getString("package"), (0 until hashes.length()).map(hashes::getString).toSet(), if (it.isNull("https")) null else it.getString("https"))
                    }).also(VaultEntry::validate)
            }.also { entries -> require(entries.map { it.id }.distinct().size == entries.size) }
        }
    }
}

class VaultSession internal constructor(
    private val store: VaultStore,
    entries: List<VaultEntry>,
    val expiresAt: Long,
    private var generation: Long
) {
    private var content = entries
    private var closed = false
    fun entries(): List<VaultEntry> { checkActive(); return content.toList() }
    fun replace(entries: List<VaultEntry>) {
        checkActive()
        val next = entries.toList()
        generation = store.save(next, generation)
        content = next
    }
    fun close() { content = emptyList(); closed = true }
    fun checkActive() { check(!closed && SystemClock.elapsedRealtime() < expiresAt) { "Vault locked. Unlock again." } }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        require(out.size() + n <= limit) { "File is too large." }
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}
