package com.example.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound settings, including provider credentials. Never exported or backed up. */
object EncryptedSettingsPersistence {
    private const val ALIAS = "io.matrix.settings.v1"
    private val magic = byteArrayOf(73, 79, 83, 1)
    private const val LEGACY_PREFS = "keyboard_settings"
    private const val LEGACY_BLOB = "settings_json"
    private var unreadable = false
    @Volatile var warning: String? = null
        private set

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "settings-v1.enc"))

    @Synchronized
    fun read(context: Context): String? {
        val target = file(context)
        if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
            try {
                val bytes = target.openRead().use { it.readBytes() }
                require(bytes.size >= magic.size + 12 + 16 && bytes.take(4).toByteArray().contentEquals(magic))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
                cipher.updateAAD(magic)
                val raw = cipher.doFinal(bytes.copyOfRange(16, bytes.size)).toString(Charsets.UTF_8)
                org.json.JSONObject(raw)
                // Retry cleanup if the previous process stopped after the atomic write.
                cleanupLegacy(context)
                return raw
            } catch (e: Exception) {
                unreadable = true
                throw IllegalStateException("Encrypted settings cannot be opened on this device; existing data was preserved", e)
            }
        }
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(LEGACY_BLOB, null) ?: return null
        // The original survives every interrupted/failed migration.
        try { org.json.JSONObject(legacy) }
        catch (e: Exception) { unreadable = true; throw IllegalStateException("Legacy settings are damaged; original data was preserved", e) }
        write(context, legacy)
        return legacy
    }

    @Synchronized
    fun write(context: Context, raw: String) {
        check(!unreadable) { "Settings storage is locked after a decryption failure; restart after resolving device key access" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(magic)
        val bytes = magic + cipher.iv + cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        val target = file(context)
        val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) }
        catch (e: Exception) { target.failWrite(out); throw e }
        cleanupLegacy(context)
    }

    private fun cleanupLegacy(context: Context) {
        val cleaned = runCatching {
            val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            !prefs.contains(LEGACY_BLOB) || prefs.edit().remove(LEGACY_BLOB).commit()
        }.getOrDefault(false)
        warning = if (cleaned) null else
            "Encrypted settings saved, but legacy plaintext cleanup failed. Retry saving to remove the old copy."
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Device settings key is missing" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
