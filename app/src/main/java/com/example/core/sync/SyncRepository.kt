package com.example.core.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import androidx.room.withTransaction
import com.example.core.clipboard.ClipStore
import com.example.core.config.*
import com.example.core.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Manual, reviewed sync. Caller runs disk/crypto on IO; no background upload. */
class SyncRepository(private val context: Context, private val db: KeyboardDatabase = KeyboardDatabase.get(context)) {
    data class Plan(val remote: Map<String, SyncRecord>, val settings: Settings,
        val changedSettings: List<String>, val changedClips: Int, val deletions: Int, val sharedClips: Int, val sharedSettings: Int)
    private data class Local(val values: Map<String, JSONObject?>, val ids: Set<String>)
    private val stateFile get() = AtomicFile(File(context.noBackupFilesDir, "sync-state-v1.jwe"))
    private fun deviceId(): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "sync-device-id"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) return file.openRead().bufferedReader().use { it.readText() }
        val id = UUID.randomUUID().toString()
        write(file, id.toByteArray()); return id
    }
    private fun load(passphrase: CharArray): SyncState {
        val file = stateFile
        return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            SyncJson.open(file.openRead().use { readBounded(it, SyncJson.MAX_BYTES) }, passphrase, true)
                .also { check(it.device == deviceId()) }
        else SyncState(deviceId())
    }
    private fun permitted(key: String, settings: Settings) = when {
        key.startsWith("clip:") -> settings.syncClipboard
        key.startsWith("setting:") -> settings.syncSettings
        else -> false
    }
    private fun allowed(record: SyncRecord, settings: Settings): Boolean = permitted(record.key, settings) &&
        (record.deleted || !record.key.startsWith("clip:") || record.value!!.getString("type") != ClipboardEntity.TYPE_FILE || settings.syncImages)

    private suspend fun local(settings: Settings): Local {
        val values = linkedMapOf<String, JSONObject?>()
        if (settings.syncSettings) SettingsProfiles.portableValues(SettingsStore.current).let { json ->
            json.keys().forEach { key -> values["setting:$key"] = JSONObject().put("v", json.get(key)) }
        }
        val all = if (settings.syncClipboard) db.clipboardDao().syncEntries() else emptyList()
        require(all.size <= 10_000) { "Clipboard is too large for this sync version; no data changed." }
        var count = 0
        var imageBytes = 0L
        all.forEach { clip ->
            val key = "clip:${clip.syncId}"
            if (clip.deletedAt > 0) { values[key] = null; return@forEach }
            if (clip.type !in listOf(ClipboardEntity.TYPE_TEXT, ClipboardEntity.TYPE_FILE)) return@forEach
            if (count >= settings.syncMaxItems) return@forEach
            if (clip.content.length > 200_000) return@forEach
            val value = JSONObject().put("type", clip.type).put("content", clip.content)
                .put("label", clip.label.orEmpty().take(1000)).put("pinned", clip.pinned).put("timestamp", clip.timestamp)
            if (!clip.isText) {
                if (!settings.syncImages || !clip.mime.startsWith("image/")) return@forEach
                val file = ClipStore.fileFor(clip) ?: return@forEach
                val limit = settings.syncMaxImageMb.coerceIn(1, 8) * 1024 * 1024
                if (file.length() !in 1..limit.toLong() || imageBytes + file.length() > 7 * 1024 * 1024) return@forEach
                val bytes = file.inputStream().use { readBounded(it, limit) }
                value.put("mime", clip.mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                imageBytes += bytes.size
            }
            SyncClip.validate(value)
            values[key] = value; count++
        }
        return Local(values, all.map { "clip:${it.syncId}" }.toSet())
    }

    suspend fun prepare(remote: List<SyncState>, passphrase: CharArray): Plan = mutex.withLock {
        val settings = SettingsStore.current
        require(settings.syncClipboard || settings.syncSettings) { "Select clipboard or settings in expert sync options." }
        val current = load(passphrase)
        val local = local(settings)
        val captured = SyncMerge.capture(current, local.values, local.ids, settings.syncClipboard, remote.any { r -> r.records.keys.any { it.startsWith("setting:") } })
        val incoming = SyncMerge.merge(*remote.map { it.records.filterValues { record -> allowed(record, settings) } }.toTypedArray())
        val merged = SyncMerge.merge(captured.records, incoming)
        val changed = merged.values.filter { permitted(it.key, settings) && it.fingerprint() != captured.records[it.key]?.fingerprint() }
        Plan(incoming, settings, changed.filter { it.key.startsWith("setting:") }.map { it.key.removePrefix("setting:") },
            changed.count { it.key.startsWith("clip:") && !it.deleted }, changed.count { it.key.startsWith("clip:") && it.deleted },
            merged.values.count { allowed(it, settings) && it.key.startsWith("clip:") && !it.deleted },
            merged.values.count { allowed(it, settings) && it.key.startsWith("setting:") })
    }

    /** Re-capture immediately before applying, preserving edits made while reviewing/downloading. */
    suspend fun apply(plan: Plan, passphrase: CharArray): ByteArray = mutex.withLock {
        val settings = SettingsStore.current
        require(sameScope(settings, plan.settings)) { "Sync options changed; preview again." }
        val before = load(passphrase)
        val nowLocal = local(settings)
        val captured = SyncMerge.capture(before, nowLocal.values, nowLocal.ids, settings.syncClipboard, plan.remote.keys.any { it.startsWith("setting:") })
        val merged = SyncMerge.merge(captured.records, plan.remote)
        val changed = merged.values.filter { permitted(it.key, settings) && it.fingerprint() != captured.records[it.key]?.fingerprint() }
        // Require a fresh review if settings changed while preview was open. Clipboard
        // changes are merged by Lamport order, with device ID breaking concurrent ties.
        require(SyncJson.canonical(SettingsProfiles.portableValues(settings)) == SyncJson.canonical(SettingsProfiles.portableValues(plan.settings))) {
            "Settings changed during review; preview again."
        }
        val patch = JSONObject()
        changed.filter { it.key.startsWith("setting:") }.forEach { patch.put(it.key.removePrefix("setting:"), it.value!!.get("v")) }
        SettingsProfiles.validate(patch)
        val staged = mutableListOf<File>()
        try {
            val replacements = changed.filter { it.key.startsWith("clip:") && !it.deleted }.associate { r ->
                val v = r.value!!; SyncClip.validate(v)
                val file = if (v.getString("type") == ClipboardEntity.TYPE_FILE) {
                    require(settings.syncImages) { "Incoming images are disabled; preview with a matching scope." }
                    val bytes = SyncClip.bytes(v)
                    require(bytes.size <= settings.syncMaxImageMb * 1024 * 1024) { "An incoming image exceeds your sync limit." }
                    File(ClipStore.dir(context), "${UUID.randomUUID()}${ClipStore.extensionFor(v.getString("mime"))}")
                        .also { staged += it; it.writeBytes(bytes) }
                } else null
                r.key to ClipboardEntity(type = v.getString("type"), content = v.getString("content"),
                    label = v.optString("label"), mime = v.optString("mime", "text/plain"), pinned = v.getBoolean("pinned"),
                    timestamp = v.getLong("timestamp"), filePath = file?.path, sizeBytes = file?.length() ?: 0,
                    syncId = r.key.removePrefix("clip:"))
            }
            // Seal/size-check before mutating either local store.
            val mergedState = captured.copy(records = merged, clock = maxOf(captured.clock, merged.values.maxOfOrNull { it.stamp.tick } ?: 0))
            val observed = captured.observed.toMutableMap()
            changed.forEach { observed[it.key] = it.fingerprint() }
            val saved = mergedState.copy(observed = observed)
            val localArchive = SyncJson.seal(saved, passphrase, true)
            val sharedArchive = SyncJson.seal(saved.copy(records = saved.records.filterValues { allowed(it, settings) }, observed = emptyMap()), passphrase)
            db.withTransaction {
                val dao = db.clipboardDao()
                changed.filter { it.key.startsWith("clip:") }.forEach { r ->
                    val previous = dao.bySyncId(r.key.removePrefix("clip:"))
                    if (r.deleted) previous?.let { dao.trash(listOf(it.id), System.currentTimeMillis(), "sync", true) }
                    else dao.insert(replacements.getValue(r.key).copy(id = previous?.id ?: 0))
                }
            }
            staged.clear() // These files are now owned by database entries.
            if (patch.length() > 0) {
                SettingsStore.update { base -> SettingsProfiles.apply(base, SettingsProfile("sync", "Device sync", "Reviewed sync", patch)).getOrThrow() }.getOrThrow()
                SettingsStore.awaitPersistence().getOrThrow()
            }
            write(stateFile, localArchive)
            // State is durable before remote upload, so retry never reuses a device revision.
            sharedArchive
        } finally { staged.forEach { it.delete() } }
    }

    private fun sameScope(a: Settings, b: Settings) = listOf(a.syncClipboard, a.syncSettings, a.syncImages, a.syncMaxItems, a.syncMaxImageMb) ==
        listOf(b.syncClipboard, b.syncSettings, b.syncImages, b.syncMaxItems, b.syncMaxImageMb)

    companion object {
        private val mutex = Mutex()
        fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16_384); var count = 0
            while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= limit) { "File exceeds safe sync size" }; output.write(buffer, 0, n) }
            return output.toByteArray()
        }
        private fun write(file: AtomicFile, bytes: ByteArray) {
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        }
    }
}
