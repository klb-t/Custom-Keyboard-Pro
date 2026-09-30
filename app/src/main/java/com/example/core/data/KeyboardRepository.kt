package com.example.core.data

import com.example.core.clipboard.ClipStore

import android.content.Context
import com.example.core.text.TextOps
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

/**
 * Everything persisted about the user's typing, behind one object.
 *
 * All writes are suspending and all reads that the UI needs are flows, so nothing here
 * can block the input thread — a keyboard that stutters while it writes to SQLite is
 * worse than a keyboard with no history at all.
 */
class KeyboardRepository(context: Context, private val db: KeyboardDatabase = KeyboardDatabase.get(context)) {
    private val clipboard = db.clipboardDao()
    private val dictionary = db.dictionaryDao()
    private val shortcuts = db.shortcutDao()

    // --- clipboard ---------------------------------------------------------

    fun observeClipboard(): Flow<List<ClipboardEntity>> = clipboard.observeAll()

    fun searchClipboard(query: String): Flow<List<ClipboardEntity>> = clipboard.search(query)
    fun observeClipboardTrash(): Flow<List<ClipboardEntity>> = clipboard.observeTrash()

    data class ClipboardClearPlan(val ids: List<Long>, val pinnedCount: Int)
    data class ClipboardTrashBatch(val id: String, val ids: List<Long>)

    suspend fun prepareClipboardClear(): ClipboardClearPlan = db.withTransaction {
        ClipboardClearPlan(clipboard.allUnpinned().map { it.id }, clipboard.pinnedCount())
    }

    /** Only the reviewed snapshot is affected; the receipt names rows actually moved. */
    suspend fun trashClipboard(plan: ClipboardClearPlan): ClipboardTrashBatch = db.withTransaction {
        val batch = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        plan.ids.distinct().chunked(400).forEach { clipboard.trash(it, now, batch, false) }
        clipboard.recordDeletedBatch(batch)
        ClipboardTrashBatch(batch, clipboard.deletedBatch(batch).map { it.id })
    }

    /** Undo this operation only; a later restore/re-delete belongs to another batch. */
    suspend fun undoClipboardTrash(batch: ClipboardTrashBatch): Int = db.withTransaction {
        val ids = batch.ids.toSet()
        val moved = clipboard.deletedBatch(batch.id).filter { it.id in ids }
        val now = System.currentTimeMillis()
        val count = moved.map { it.id }.chunked(400).sumOf { clipboard.restoreBatch(it, batch.id, now) }
        moved.forEach { clipboard.clearDeletion(it.syncId) }
        count
    }

    suspend fun restoreClip(id: Long): Boolean = db.withTransaction {
        val entry = clipboard.byId(id) ?: return@withTransaction false
        val restored = clipboard.restore(id, System.currentTimeMillis()) > 0
        if (restored) clipboard.clearDeletion(entry.syncId)
        restored
    }

    suspend fun rememberClip(type: String, content: String, maxItems: Int) =
        rememberClipEntry(ClipboardEntity(type = type, content = content), maxItems)

    /**
     * Remembers an entry that already knows what it is — a file we took a copy of, a
     * part of a multi-item clip, or text with a source attached.
     *
     * Trimming deletes rows, and rows now own bytes, so the files those rows pointed
     * at go with them. Without this the app would quietly accumulate every picture
     * ever copied, long after its history entry was gone.
     */
    suspend fun rememberClipEntry(entry: ClipboardEntity, maxItems: Int) {
        var removed = emptyList<ClipboardEntity>()
        db.withTransaction {
            if (entry.content.isBlank() && entry.filePath.isNullOrBlank()) return@withTransaction
            if (entry.isText) {
                if (clipboard.newest()?.let { it.isText && it.content == entry.content } == true) return@withTransaction
                clipboard.recordReplacedContent(entry.content, System.currentTimeMillis())
                clipboard.deleteByContent(entry.content)
            }
            clipboard.insert(entry)
            if (maxItems > 0) {
                removed = clipboard.overflowing(maxItems)
                clipboard.trimTo(maxItems)
            }
        }
        // Database rollback must never leave a retained row pointing at deleted bytes.
        releaseClipboardFiles(removed)
    }

    suspend fun newestClip(): ClipboardEntity? = clipboard.newest()

    suspend fun deleteClip(id: Long) = db.withTransaction {
        val batch = java.util.UUID.randomUUID().toString()
        clipboard.trash(listOf(id), System.currentTimeMillis(), batch, true)
        clipboard.recordDeletedBatch(batch)
    }
    suspend fun setClipPinned(id: Long, pinned: Boolean) = clipboard.setPinned(id, pinned, System.currentTimeMillis())
    suspend fun updateClip(id: Long, content: String) = clipboard.updateContent(id, content, System.currentTimeMillis())
    suspend fun sweepClipboard(retentionDays: Int) {
        val trashBefore = System.currentTimeMillis() - com.example.core.config.SettingsStore.current.clipboardTrashHours.coerceIn(1, 168) * 3_600_000L
        val removed = db.withTransaction {
            clipboard.recordExpiredTrash(trashBefore)
            val trash = clipboard.expiredTrash(trashBefore)
            clipboard.purgeTrash(trashBefore)
            val old = if (retentionDays > 0) {
                val before = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L
                clipboard.olderThan(before).also { clipboard.deleteOlderThan(before) }
            } else emptyList()
            trash + old
        }
        releaseClipboardFiles(removed)
    }

    internal suspend fun releaseClipboardFiles(removed: List<ClipboardEntity>) {
        if (removed.none { it.filePath != null }) return
        val retained = clipboard.filePaths().toSet()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            removed.filter { it.filePath !in retained }.forEach { ClipStore.delete(it) }
        }
    }

    /** Every file still spoken for, so orphans can be told apart from the rest. */
    suspend fun clipFilePaths(): List<String> = clipboard.filePaths()

    // --- dictionary --------------------------------------------------------

    fun observeDictionary(limit: Int = 500, offset: Int = 0): Flow<List<WordEntity>> =
        dictionary.observeAll(limit, offset)

    suspend fun dictionarySize(): Int = dictionary.count()

    /** Records one typed word and the transition into it. */
    suspend fun learn(previous: String?, word: String, locale: String) {
        if (word.length < 2 || word.any { !TextOps.isWordChar(it) }) return
        val existing = dictionary.find(word)
        if (existing != null) {
            if (existing.blocked) return
            dictionary.bump(word, System.currentTimeMillis())
        } else {
            dictionary.upsert(WordEntity(word = word, count = 1, locale = locale))
        }
        if (!previous.isNullOrEmpty()) {
            val bigram = dictionary.findBigram(previous, word)
            dictionary.upsertBigram(
                bigram?.copy(count = bigram.count + 1, lastUsed = System.currentTimeMillis())
                    ?: BigramEntity(previous = previous, next = word)
            )
        }
    }

    suspend fun addWord(word: String, locale: String = "") {
        if (word.isBlank()) return
        dictionary.upsert(
            WordEntity(
                id = dictionary.find(word)?.id ?: 0,
                word = word,
                count = (dictionary.find(word)?.count ?: 0) + 5,
                locale = locale,
                locked = true
            )
        )
    }

    /**
     * Adds many words at once, for importing a real word list.
     *
     * Accepts one word per line, optionally followed by whitespace and a count, which
     * is the format most published frequency lists already use. Imported words are
     * marked as the user's own so automatic pruning leaves them alone.
     */
    suspend fun importWords(text: CharSequence, locale: String = ""): Int {
        val separator = Regex("[\\s,;]+")
        var imported = 0
        // Written in batches: one query per word turns a 100,000-line list into
        // several minutes of work for no reason.
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(separator)
                val word = parts.firstOrNull()?.lowercase().orEmpty()
                if (word.length < 2 || word.any { !TextOps.isWordChar(it) }) return@mapNotNull null
                WordEntity(
                    word = word,
                    count = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 1_000_000),
                    locale = locale,
                    locked = true
                )
            }
            .distinctBy { it.word }
            .chunked(500)
            .forEach { batch ->
                dictionary.upsertAll(batch)
                imported += batch.size
            }
        return imported
    }

    suspend fun blockWord(word: String) = dictionary.block(word)
    suspend fun deleteWord(word: String) = dictionary.delete(word)
    suspend fun clearLearnedWords() {
        dictionary.clearLearned()
        dictionary.clearBigrams()
    }

    /** True when the user has typed this word before and has not blocked it. */
    suspend fun knows(word: String): Boolean {
        val entity = dictionary.find(word) ?: return false
        return !entity.blocked
    }

    suspend fun wordsStartingWith(prefix: String, limit: Int): List<WordEntity> =
        if (prefix.isEmpty()) emptyList() else dictionary.startingWith(prefix, limit)

    suspend fun nextWords(previous: String, prefix: String, limit: Int): List<BigramEntity> =
        if (previous.isEmpty()) emptyList() else dictionary.following(previous, prefix, limit)

    // --- text shortcuts ----------------------------------------------------

    fun observeShortcuts(): Flow<List<ShortcutEntity>> = shortcuts.observeAll()
    suspend fun allShortcuts(): List<ShortcutEntity> = shortcuts.all()
    suspend fun findShortcut(key: String): ShortcutEntity? = shortcuts.find(key)
    suspend fun saveShortcut(item: ShortcutEntity) = shortcuts.upsert(item)
    suspend fun deleteShortcut(id: Long) = shortcuts.delete(id)

    companion object {
        @Volatile
        private var instance: KeyboardRepository? = null

        fun get(context: Context): KeyboardRepository =
            instance ?: synchronized(this) {
                instance ?: KeyboardRepository(context.applicationContext).also { instance = it }
            }
    }
}
