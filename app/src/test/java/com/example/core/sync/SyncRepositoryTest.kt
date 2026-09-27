package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: KeyboardDatabase
    private lateinit var repo: SyncRepository
    private val password = "shared device passphrase".toCharArray()
    private val peer = "peer-bbbbbbbbbbbbb"
    private val id = "clipboard-aaaaaaaa"
    @Before fun setup() {
        SettingsStore.replaceAll(Settings(syncSettings = false))
        db = Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java).allowMainThreadQueries().build()
        repo = SyncRepository(context, db)
    }
    @After fun close() { db.close(); password.fill('\u0000') }
    private fun remote(tick: Long, text: String?): SyncState {
        val value = text?.let { JSONObject().put("type", "TEXT").put("content", it).put("label", "")
            .put("timestamp", 1234).put("pinned", false) }
        val key = "clip:$id"
        return SyncState(peer, tick, mapOf(key to SyncRecord(key, SyncStamp(tick, peer), value)))
    }
    @Test fun `preview cannot mutate and repeat import cannot duplicate clipboard entries`() = runBlocking {
        val plan = repo.prepare(listOf(remote(5, "from another phone")), password)
        assertEquals(1, plan.changedClips)
        assertEquals(0, db.clipboardDao().count())
        val outgoing = repo.apply(plan, password)
        assertEquals(1, db.clipboardDao().count())
        assertEquals(id, db.clipboardDao().newest()!!.syncId)
        assertEquals("from another phone", SyncJson.open(outgoing, password).records.getValue("clip:$id").value!!.getString("content"))
        val repeat = repo.prepare(listOf(remote(5, "from another phone")), password)
        assertEquals(0, repeat.changedClips)
        repo.apply(repeat, password)
        assertEquals(1, db.clipboardDao().count())
    }
    @Test fun `remote delete uses local trash and stale transfer does not restore it`() = runBlocking {
        repo.apply(repo.prepare(listOf(remote(5, "recoverable")), password), password)
        repo.apply(repo.prepare(listOf(remote(6, null)), password), password)
        val repository = KeyboardRepository(context, db)
        assertTrue(repository.observeClipboard().first().isEmpty())
        assertEquals("recoverable", repository.observeClipboardTrash().first().single().content)
        repo.apply(repo.prepare(listOf(remote(5, "recoverable")), password), password)
        assertTrue(repository.observeClipboard().first().isEmpty())
        val clip = repository.observeClipboardTrash().first().single()
        repository.restoreClip(clip.id)
        val restored = SyncJson.open(repo.apply(repo.prepare(emptyList(), password), password), password)
        assertFalse(restored.records.getValue("clip:$id").deleted)
        assertTrue(restored.records.getValue("clip:$id").stamp.tick > 6)
    }
    @Test fun `wrong passphrase leaves local state and clipboard untouched`() = runBlocking {
        repo.apply(repo.prepare(listOf(remote(5, "original")), password), password)
        val before = db.clipboardDao().newest()
        assertTrue(runCatching { repo.prepare(listOf(remote(6, "replacement")), "the wrong passphrase".toCharArray()) }.isFailure)
        assertEquals(before, db.clipboardDao().newest())
    }
    @Test fun `a pin made after preview is preserved and requires a fresh review`() = runBlocking {
        repo.apply(repo.prepare(listOf(remote(5, "original")), password), password)
        val plan = repo.prepare(listOf(remote(6, "remote edit")), password)
        val clip = db.clipboardDao().newest()!!
        KeyboardRepository(context, db).setClipPinned(clip.id, true)
        assertTrue(runCatching { repo.apply(plan, password) }.isFailure)
        assertEquals("original", db.clipboardDao().newest()!!.content)
        assertTrue(db.clipboardDao().newest()!!.pinned)
    }

    @Test fun `manual deletion is shared after trash bytes have expired`() = runBlocking {
        repo.apply(repo.prepare(listOf(remote(5, "delete deliberately")), password), password)
        val clip = db.clipboardDao().newest()!!
        val clipboard = KeyboardRepository(context, db)
        clipboard.deleteClip(clip.id)
        db.clipboardDao().purgeTrash(Long.MAX_VALUE)
        assertNull(db.clipboardDao().byId(clip.id))
        assertTrue(db.clipboardDao().syncDeletions().any { it.syncId == clip.syncId })
        val snapshot = SyncJson.open(repo.apply(repo.prepare(emptyList(), password), password), password)
        assertTrue(snapshot.records.getValue("clip:$id").deleted)
        assertTrue(snapshot.records.getValue("clip:$id").stamp.tick > 5)
    }
    @Test fun `local capacity cleanup never silently deletes another phones history`() = runBlocking {
        repo.apply(repo.prepare(listOf(remote(5, "keep on other phone")), password), password)
        db.clipboardDao().trimTo(0)
        assertEquals(0, db.clipboardDao().count())
        assertTrue(db.clipboardDao().syncDeletions().isEmpty())
        val snapshot = SyncJson.open(repo.apply(repo.prepare(emptyList(), password), password), password)
        assertFalse(snapshot.records.getValue("clip:$id").deleted)
        assertEquals(0, db.clipboardDao().count())
    }

    @Test fun `syncing an image pin preserves the existing bytes and file grant path`() = runBlocking {
        fun image(tick: Long, pinned: Boolean): SyncState {
            val value = JSONObject().put("type", "FILE").put("content", "Screenshot").put("label", "Screenshot")
                .put("timestamp", 100).put("pinned", pinned).put("mime", "image/png")
                .put("data", android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP))
            return SyncState(peer, tick, mapOf("clip:$id" to SyncRecord("clip:$id", SyncStamp(tick, peer), value)))
        }
        repo.apply(repo.prepare(listOf(image(5, false)), password), password)
        val path = db.clipboardDao().newest()!!.filePath
        repo.apply(repo.prepare(listOf(image(6, true)), password), password)
        val current = db.clipboardDao().newest()!!
        assertTrue(current.pinned)
        assertEquals(path, current.filePath)
        assertArrayEquals(byteArrayOf(1, 2, 3), java.io.File(path!!).readBytes())
    }

}
