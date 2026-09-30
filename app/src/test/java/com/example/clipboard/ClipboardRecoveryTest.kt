package com.example.clipboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.ClipStore
import com.example.core.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardRecoveryTest {
    private lateinit var db: KeyboardDatabase
    private lateinit var repo: KeyboardRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java).allowMainThreadQueries().build()
        repo = KeyboardRepository(context, db)
    }
    @After fun close() { db.close() }

    @Test fun `review is read only and confirm cannot delete later copies or pins`() = runBlocking {
        val dao = db.clipboardDao()
        val old = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "old"))
        val pin = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "pin", pinned = true))
        val laterPin = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "pin before confirm"))
        val plan = repo.prepareClipboardClear()
        assertEquals(3, dao.count())
        assertEquals(1, plan.pinnedCount)
        repo.setClipPinned(laterPin, true)
        val fresh = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "new after review"))
        assertEquals(listOf(old), repo.trashClipboard(plan).ids)
        assertEquals(setOf(pin, laterPin, fresh), repo.observeClipboard().first().map { it.id }.toSet())
        assertEquals(listOf(old), repo.observeClipboardTrash().first().map { it.id })
        assertTrue(repo.restoreClip(old))
        assertEquals(4, dao.count())
        assertFalse(repo.restoreClip(old))
    }

    @Test fun `bulk undo preserves a separate deletion made after clear review`() = runBlocking {
        val dao = db.clipboardDao()
        val separatelyDeleted = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "manual deletion"))
        val cleared = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "bulk deletion"))
        val laterPin = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "newly pinned"))
        val plan = repo.prepareClipboardClear()
        repo.deleteClip(separatelyDeleted)
        repo.setClipPinned(laterPin, true)
        val fresh = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "copied after review"))

        val batch = repo.trashClipboard(plan)
        assertEquals(listOf(cleared), batch.ids)
        assertEquals(1, repo.undoClipboardTrash(batch))
        assertEquals(setOf(cleared, laterPin, fresh), repo.observeClipboard().first().map { it.id }.toSet())
        assertEquals(listOf(separatelyDeleted), repo.observeClipboardTrash().first().map { it.id })
        assertEquals(listOf(dao.byId(separatelyDeleted)!!.syncId), dao.syncDeletions().map { it.syncId })
        assertEquals(0, repo.undoClipboardTrash(batch))
    }

    @Test fun `bulk undo cannot restore an item restored and separately deleted again`() = runBlocking {
        val dao = db.clipboardDao()
        val independentlyDeleted = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "changed deletion"))
        val cleared = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "unchanged bulk deletion"))
        val batch = repo.trashClipboard(repo.prepareClipboardClear())
        assertTrue(repo.restoreClip(independentlyDeleted))
        repo.deleteClip(independentlyDeleted)

        assertEquals(1, repo.undoClipboardTrash(batch))
        assertEquals(listOf(cleared), repo.observeClipboard().first().map { it.id })
        assertEquals(listOf(independentlyDeleted), repo.observeClipboardTrash().first().map { it.id })
        assertEquals(listOf(dao.byId(independentlyDeleted)!!.syncId), dao.syncDeletions().map { it.syncId })
    }

    @Test fun `single deletion undo is bound to its operation including pinned entries`() = runBlocking {
        val dao = db.clipboardDao()
        val id = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "pinned", pinned = true))
        val original = repo.deleteClip(id)
        assertEquals(listOf(id), original.ids)
        assertEquals(1, repo.undoClipboardTrash(original))
        assertTrue(dao.byId(id)!!.pinned)
        val newer = repo.deleteClip(id)
        assertNotEquals(original.id, newer.id)
        assertEquals(0, repo.undoClipboardTrash(original))
        assertEquals(listOf(dao.byId(id)!!.syncId), dao.syncDeletions().map { it.syncId })
        assertTrue(repo.deleteClip(id).ids.isEmpty())
        assertEquals(1, repo.undoClipboardTrash(newer))
        assertTrue(dao.syncDeletions().isEmpty())
        assertTrue(repo.deleteClip(Long.MAX_VALUE).ids.isEmpty())
    }

    @Test fun `image bytes survive deleting reopening and restoring history`() = runBlocking {
        val file = File(ClipStore.dir(context), "recover.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val id = db.clipboardDao().insert(ClipboardEntity(type = ClipboardEntity.TYPE_FILE, content = "Screenshot",
            mime = "image/png", filePath = file.path, timestamp = 1))
        repo.deleteClip(id)
        val reopened = KeyboardRepository(context, db)
        assertTrue(reopened.observeClipboard().first().isEmpty())
        assertEquals(file.path, reopened.observeClipboardTrash().first().single().filePath)
        assertTrue(file.exists())
        assertTrue(file.path in reopened.clipFilePaths())
        assertTrue(reopened.restoreClip(id))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ClipStore.fileFor(reopened.newestClip()!!)!!.readBytes())
        assertTrue(reopened.newestClip()!!.timestamp > 1)
        file.delete()
        Unit
    }

    @Test fun `trash expiry removes only expired deleted entries and their bytes`() = runBlocking {
        val dao = db.clipboardDao()
        val file = File(ClipStore.dir(context), "expired.png").apply { writeText("old") }
        dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_FILE, content = "gone", filePath = file.path, deletedAt = 1))
        val recent = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "recover", deletedAt = System.currentTimeMillis()))
        val active = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "keep"))
        repo.sweepClipboard(0)
        assertFalse(file.exists())
        assertEquals(listOf(recent), repo.observeClipboardTrash().first().map { it.id })
        assertEquals(active, repo.newestClip()!!.id)
    }

    @Test fun `share ingress rejects file paths duplicates and excessive attachments`() {
        val uris = arrayListOf(Uri.parse("file:///private/file.png"), Uri.parse("content://images/1"))
        uris += (1..40).map { Uri.parse("content://images/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val accepted = ClipboardImportActivity.incomingImages(intent)
        assertEquals(20, accepted.size)
        assertEquals(20, accepted.distinct().size)
        assertTrue(accepted.all { it.scheme == "content" })
        assertTrue(ClipboardImportActivity.incomingImages(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, uris[1])).isEmpty())
    }

    @Test fun `migration preserves existing rows and assigns distinct stable sync ids`() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE clipboard_items(id INTEGER PRIMARY KEY, content TEXT, timestamp INTEGER)")
                        db.execSQL("INSERT INTO clipboard_items VALUES (1, 'first', 100), (2, 'second', 200)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.use {
            val sql = helper.writableDatabase
            KeyboardDatabase.MIGRATION_3_4.migrate(sql)
            sql.query("SELECT content, syncId, deletedAt, modifiedAt FROM clipboard_items ORDER BY id").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("first", c.getString(0))
                val firstId = c.getString(1); assertEquals(32, firstId.length)
                assertEquals(0L, c.getLong(2)); assertEquals(100L, c.getLong(3))
                assertTrue(c.moveToNext()); assertNotEquals(firstId, c.getString(1)); assertEquals(200L, c.getLong(3))
            }
        }
    }
    @Test fun `migration retains old manual deletion intent separately from trash`() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE clipboard_items(syncId TEXT, deletedAt INTEGER)")
                        db.execSQL("INSERT INTO clipboard_items VALUES ('removed-aaaaaaaa', 50), ('active-aaaaaaaaa', 0)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.use {
            val sql = helper.writableDatabase
            KeyboardDatabase.MIGRATION_4_5.migrate(sql)
            sql.execSQL("DELETE FROM clipboard_items WHERE deletedAt > 0")
            sql.query("SELECT syncId, modifiedAt, cause FROM clipboard_deletions").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("removed-aaaaaaaa", c.getString(0))
                assertEquals(50L, c.getLong(1)); assertEquals("MANUAL", c.getString(2))
                assertFalse(c.moveToNext())
            }
        }
    }
    @Test fun `replacing repeated text keeps one active copy and records superseded identity`() = runBlocking {
        repo.rememberClip(ClipboardEntity.TYPE_TEXT, "first", 100)
        val first = repo.newestClip()!!
        repo.rememberClip(ClipboardEntity.TYPE_TEXT, "second", 100)
        repo.rememberClip(ClipboardEntity.TYPE_TEXT, "first", 100)
        assertEquals(2, db.clipboardDao().count())
        assertEquals(listOf(first.syncId), db.clipboardDao().syncDeletions().map { it.syncId })
    }

    @Test fun `copying a pictures label as text never deduplicates image entries`() = runBlocking {
        val file = File(ClipStore.dir(context), "label.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        repo.rememberClipEntry(ClipboardEntity(type = ClipboardEntity.TYPE_FILE, content = "Screenshot", mime = "image/png", filePath = file.path), 100)
        repo.rememberClip(ClipboardEntity.TYPE_TEXT, "Screenshot", 100)
        assertEquals(2, db.clipboardDao().count())
        assertTrue(file.exists())
        assertTrue(db.clipboardDao().syncDeletions().isEmpty())
    }
    @Test fun `a failed trim transaction cannot delete retained image bytes`() = runBlocking {
        val file = File(ClipStore.dir(context), "rollback.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = db.clipboardDao().insert(ClipboardEntity(type = ClipboardEntity.TYPE_FILE, content = "keep", filePath = file.path, timestamp = 1))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_clip_delete BEFORE DELETE ON clipboard_items BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
        assertTrue(runCatching { repo.rememberClip(ClipboardEntity.TYPE_TEXT, "new", 1) }.isFailure)
        assertNotNull(db.clipboardDao().byId(id))
        assertEquals(1, db.clipboardDao().count())
        assertTrue(file.exists())
    }

    @Test fun `copies within the same millisecond still paste and trim in insertion order`() = runBlocking {
        val dao = db.clipboardDao()
        val first = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "first", timestamp = 100))
        val second = dao.insert(ClipboardEntity(type = ClipboardEntity.TYPE_TEXT, content = "second", timestamp = 100))
        assertEquals(second, dao.newest()!!.id)
        assertEquals(listOf(first), dao.overflowing(1).map { it.id })
        dao.trimTo(1)
        assertEquals(second, dao.newest()!!.id)
    }

}
