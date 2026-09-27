package com.example.core.sync

import com.example.core.config.Settings
import com.example.core.config.SettingsProfiles
import com.example.core.config.SettingsSchema
import com.example.core.security.PasswordEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMergeTest {
    private val a = "device-aaaaaaaaaaaa"
    private val b = "device-bbbbbbbbbbbb"
    private val key = "clip:clipboard-aaaaaaaa"
    private fun value(text: String) = JSONObject().put("type", "TEXT").put("content", text).put("label", "")
        .put("timestamp", 1).put("pinned", false)
    private fun record(text: String?, tick: Long, device: String = a) = SyncRecord(key, SyncStamp(tick, device), text?.let(::value))

    @Test fun `concurrent merge is commutative associative and idempotent`() {
        val first = mapOf(key to record("a", 5))
        val second = mapOf(key to record("b", 5, b))
        val third = mapOf(key to record("c", 6))
        fun print(records: Map<String, SyncRecord>) = SyncJson.canonical(JSONObject(records.mapValues { it.value.value }))
        assertEquals(print(SyncMerge.merge(first, second)), print(SyncMerge.merge(second, first)))
        assertEquals(print(SyncMerge.merge(SyncMerge.merge(first, second), third)), print(SyncMerge.merge(first, SyncMerge.merge(second, third))))
        assertEquals(print(first), print(SyncMerge.merge(first, first)))
        assertEquals("b", SyncMerge.merge(first, second).getValue(key).value!!.getString("content"))
    }
    @Test fun `old offline snapshot cannot resurrect a tombstone but explicit restore can`() {
        val old = mapOf(key to record("old", 4))
        val deleted = mapOf(key to record(null, 6, b))
        assertTrue(SyncMerge.merge(old, deleted).getValue(key).deleted)
        val state = SyncState(a, 6, deleted, mapOf(key to deleted.getValue(key).fingerprint()))
        val restored = SyncMerge.capture(state, mapOf(key to value("restored")), setOf(key), true)
        assertFalse(SyncMerge.merge(deleted, restored.records).getValue(key).deleted)
        assertEquals(7L, restored.clock)
    }
    @Test fun `automatic pruning is local unless explicitly selected and disabled clipboard never emits it`() {
        val existing = record("kept", 5)
        val state = SyncState(a, 5, mapOf(key to existing))
        assertFalse(SyncMerge.capture(state, emptyMap(), emptySet(), true).records.getValue(key).deleted)
        val observed = state.copy(observed = mapOf(key to existing.fingerprint()))
        assertFalse(SyncMerge.capture(observed, emptyMap(), emptySet(), true).records.getValue(key).deleted)
        assertTrue(SyncMerge.capture(observed, emptyMap(), emptySet(), true, propagatePruning = true).records.getValue(key).deleted)
        assertFalse(SyncMerge.capture(observed, emptyMap(), emptySet(), false).records.getValue(key).deleted)
    }
    @Test fun `new phone defaults do not defeat configured settings on the existing phone`() {
        val setting = "setting:clipboardMaxItems"
        val configured = SyncMerge.capture(SyncState(a), mapOf(setting to JSONObject().put("v", 100)), emptySet(), false)
        val joining = SyncMerge.capture(SyncState(b), mapOf(setting to JSONObject().put("v", 200)), emptySet(), false, true)
        assertEquals(100, SyncMerge.merge(configured.records, joining.records).getValue(setting).value!!.getInt("v"))
    }
    @Test fun `same revision with different data is rejected rather than silently lost`() {
        assertTrue(runCatching { SyncMerge.merge(mapOf(key to record("one", 1)), mapOf(key to record("two", 1))) }.isFailure)
    }
    @Test fun `portable settings and serialized input reject credential fields`() {
        val portable = SettingsProfiles.portableValues(Settings())
        SettingsSchema.all.filter { it.secret }.forEach { assertFalse(portable.has(it.key)) }
        assertFalse(portable.has("syncClipboard"))
        val malicious = SyncRecord("setting:aiApiKey", SyncStamp(1, a), JSONObject().put("v", "secret"))
        assertTrue(runCatching { SyncJson.decode(SyncJson.encode(SyncState(a, 1, mapOf(malicious.key to malicious)))) }.isFailure)
    }
    @Test fun `encrypted device snapshot round trips and cannot be opened as a vault backup`() {
        val state = SyncState(a, 3, mapOf(key to record("zażółć", 3)))
        val pass = "correct horse separate battery".toCharArray()
        val bytes = SyncJson.seal(state, pass)
        assertFalse(String(bytes).contains("zażółć"))
        val restored = SyncJson.open(bytes, pass)
        assertEquals("zażółć", restored.records.getValue(key).value!!.getString("content"))
        assertTrue(runCatching { SyncJson.open(bytes, "a different passphrase".toCharArray()) }.isFailure)
        assertTrue(runCatching { PasswordEnvelope("io-matrix-vault-backup+jwe", 12 * 1024 * 1024, SyncJson.MAX_BYTES).decrypt(bytes, pass) }.isFailure)
        pass.fill('\u0000')
    }
    @Test fun `remote data cannot name a local file or counterfeit duplicate identity`() {
        val path = value("bad").put("filePath", "/private/vault")
        assertTrue(runCatching { SyncClip.validate(path) }.isFailure)
        val raw = JSONObject(String(SyncJson.encode(SyncState(a, 1, mapOf(key to record("x", 1))))))
        raw.getJSONArray("records").put(raw.getJSONArray("records").getJSONObject(0))
        assertTrue(runCatching { SyncJson.decode(raw.toString().toByteArray()) }.isFailure)
    }
}
