package com.example.core.sync

import com.example.core.config.SettingsProfiles
import com.example.core.security.PasswordEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Lamport order is independent of wall-clock skew; device ID breaks concurrent ties. */
data class SyncStamp(val tick: Long, val device: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int = compareValuesBy(this, other, { it.tick }, { it.device })
}
data class SyncRecord(val key: String, val stamp: SyncStamp, val value: JSONObject?) {
    val deleted: Boolean get() = value == null
    fun fingerprint(): String = SyncJson.hash(value?.let(SyncJson::canonical) ?: "deleted")
}
data class SyncState(val device: String, val clock: Long = 0, val records: Map<String, SyncRecord> = emptyMap(),
    val observed: Map<String, String> = emptyMap())

object SyncMerge {
    const val MAX_RECORDS = 10_000 // Includes durable tombstones: never silently discard them.
    fun merge(vararg sources: Map<String, SyncRecord>): Map<String, SyncRecord> {
        val result = sortedMapOf<String, SyncRecord>()
        sources.forEach { records -> records.forEach { (key, record) ->
            val previous = result[key]
            if (previous == null || previous.stamp < record.stamp) result[key] = record
            else if (previous.stamp == record.stamp) require(previous.fingerprint() == record.fingerprint()) {
                "Conflicting snapshots from the same device revision; nothing was applied."
            }
        } }
        require(result.size <= MAX_RECORDS) { "Sync history reached its safe limit; existing files were preserved." }
        return result
    }

    /** Manual tombstones arrive as data; missing rows propagate only by explicit pruning policy. */
    fun capture(state: SyncState, local: Map<String, JSONObject?>, presentClipboardIds: Set<String>,
        clipboardEnabled: Boolean, joiningExistingSettings: Boolean = false, propagatePruning: Boolean = false): SyncState {
        var clock = maxOf(state.clock, state.records.values.maxOfOrNull { it.stamp.tick } ?: 0)
        val records = state.records.toMutableMap()
        val observed = state.observed.toMutableMap()
        val candidates = local.toMutableMap()
        if (clipboardEnabled && propagatePruning) state.observed.keys.filter { it.startsWith("clip:") && it !in presentClipboardIds }
            .forEach { candidates[it] = null }
        candidates.forEach { (key, value) ->
            val hash = SyncJson.hash(value?.let(SyncJson::canonical) ?: "deleted")
            if (observed[key] != hash) {
                val existing = records[key]
                if (existing?.fingerprint() != hash) {
                    // A newly joined device's defaults do not defeat already shared settings.
                    val tick = if (joiningExistingSettings && key.startsWith("setting:") && key !in observed && existing == null) 0 else ++clock
                    require(tick < 1_000_000_000_000_000L)
                    records[key] = SyncRecord(key, SyncStamp(tick, state.device), value)
                }
                observed[key] = hash
            }
        }
        require(records.size <= MAX_RECORDS)
        return state.copy(clock = clock, records = records, observed = observed)
    }
}

object SyncJson {
    const val MAX_BYTES = 16 * 1024 * 1024
    val envelope = PasswordEnvelope("io-matrix-sync+jwe", 12 * 1024 * 1024 - 1024, MAX_BYTES)
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun encode(state: SyncState, localState: Boolean = false): ByteArray {
        val json = JSONObject().put("format", 1).put("device", state.device).put("clock", state.clock)
            .put("records", JSONArray(state.records.values.sortedBy { it.key }.map { r ->
                JSONObject().put("key", r.key).put("tick", r.stamp.tick).put("device", r.stamp.device)
                    .put("value", r.value ?: JSONObject.NULL)
            }))
        if (localState) json.put("observed", JSONObject(state.observed))
        return json.toString().toByteArray(Charsets.UTF_8).also { require(it.size < 12 * 1024 * 1024) { "Selected sync data exceeds 12 MiB. Reduce image size or history selection." } }
    }
    fun decode(bytes: ByteArray, localState: Boolean = false): SyncState {
        require(bytes.size < 12 * 1024 * 1024)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("format") == 1) { "Unsupported sync format" }
        val device = json.getString("device").also(::validateId)
        val clock = json.getLong("clock"); require(clock in 0 until 1_000_000_000_000_000L)
        val array = json.getJSONArray("records"); require(array.length() <= SyncMerge.MAX_RECORDS)
        val records = linkedMapOf<String, SyncRecord>()
        repeat(array.length()) { i ->
            val row = array.getJSONObject(i)
            val key = row.getString("key"); require(key.length <= 180)
            val source = row.getString("device").also(::validateId)
            val tick = row.getLong("tick"); require(tick in 0 until 1_000_000_000_000_000L)
            val value = if (row.isNull("value")) null else row.getJSONObject("value")
            when {
                key.startsWith("setting:") -> {
                    require(value != null && value.length() == 1 && value.has("v"))
                    SettingsProfiles.validate(JSONObject().put(key.removePrefix("setting:"), value.get("v")))
                }
                key.startsWith("clip:") -> {
                    validateId(key.removePrefix("clip:"))
                    value?.let { SyncClip.validate(it) }
                }
                else -> error("Unknown sync data kind")
            }
            require(records.put(key, SyncRecord(key, SyncStamp(tick, source), value)) == null) { "Duplicate sync identity" }
        }
        val observed = mutableMapOf<String, String>()
        if (localState) json.optJSONObject("observed")?.let { o ->
            require(o.length() <= SyncMerge.MAX_RECORDS)
            o.keys().forEach { key -> val v = o.getString(key); require(v.matches(Regex("[a-f0-9]{64}"))); observed[key] = v }
        }
        return SyncState(device, clock, records, observed)
    }
    fun seal(state: SyncState, passphrase: CharArray, localState: Boolean = false): ByteArray {
        val bytes = encode(state, localState)
        return try { envelope.encrypt(bytes, passphrase) } finally { bytes.fill(0) }
    }
    fun open(bytes: ByteArray, passphrase: CharArray, localState: Boolean = false): SyncState {
        val clear = envelope.decrypt(bytes, passphrase)
        return try { decode(clear, localState) } finally { clear.fill(0) }
    }
    private fun validateId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid sync identity" } }
}
