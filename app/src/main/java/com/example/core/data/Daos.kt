package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 10001")
    suspend fun syncEntries(): List<ClipboardEntity>

    @Query("SELECT * FROM clipboard_items WHERE syncId = :syncId LIMIT 1")
    suspend fun bySyncId(syncId: String): ClipboardEntity?


    @Query("SELECT * FROM clipboard_items WHERE deletedAt = 0 ORDER BY pinned DESC, timestamp DESC")
    fun observeAll(): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items WHERE deletedAt = 0 AND content LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    fun search(query: String): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items WHERE deletedAt = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun newest(): ClipboardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardEntity): Long

    @Query("SELECT * FROM clipboard_items WHERE id = :id")
    suspend fun byId(id: Long): ClipboardEntity?

    @Query("SELECT * FROM clipboard_items WHERE deletedAt = 0 AND pinned = 0")
    suspend fun allUnpinned(): List<ClipboardEntity>

    @Query("SELECT * FROM clipboard_items WHERE deletedAt = 0 AND pinned = 0 AND timestamp < :before")
    suspend fun olderThan(before: Long): List<ClipboardEntity>

    @Query("SELECT filePath FROM clipboard_items WHERE filePath IS NOT NULL")
    suspend fun filePaths(): List<String>

    /**
     * The rows a trim is about to remove.
     *
     * Asked for separately rather than inferred, because rows now own files on disk
     * and a trim that only deletes rows leaves every picture ever copied behind.
     */
    @Query(
        "SELECT * FROM clipboard_items WHERE deletedAt = 0 AND pinned = 0 AND id NOT IN (" +
            "SELECT id FROM clipboard_items WHERE deletedAt = 0 ORDER BY pinned DESC, timestamp DESC LIMIT :keep)"
    )
    suspend fun overflowing(keep: Int): List<ClipboardEntity>

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_items WHERE deletedAt = 0 AND pinned = 0 AND timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM clipboard_items WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<ClipboardEntity>>

    @Query("SELECT COUNT(*) FROM clipboard_items WHERE deletedAt = 0 AND pinned = 1")
    suspend fun pinnedCount(): Int

    @Query("UPDATE clipboard_items SET deletedAt = :now, modifiedAt = :now, deleteBatch = :batch WHERE id IN (:ids) AND deletedAt = 0 AND (pinned = 0 OR :allowPinned)")
    suspend fun trash(ids: List<Long>, now: Long, batch: String, allowPinned: Boolean): Int

    @Query("UPDATE clipboard_items SET deletedAt = 0, deleteBatch = NULL, modifiedAt = :now, timestamp = :now WHERE id = :id AND deletedAt > 0")
    suspend fun restore(id: Long, now: Long): Int

    @Query("SELECT * FROM clipboard_items WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expiredTrash(before: Long): List<ClipboardEntity>

    @Query("DELETE FROM clipboard_items WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun purgeTrash(before: Long)

    @Query("UPDATE clipboard_items SET content = :content, modifiedAt = :now WHERE id = :id AND deletedAt = 0")
    suspend fun updateContent(id: Long, content: String, now: Long)

    @Query("UPDATE clipboard_items SET pinned = :pinned, modifiedAt = :now WHERE id = :id AND deletedAt = 0")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM clipboard_items WHERE deletedAt = 0")
    suspend fun count(): Int

    /** Drops the oldest unpinned entries beyond [keep]. */
    @Query(
        "DELETE FROM clipboard_items WHERE deletedAt = 0 AND pinned = 0 AND id NOT IN " +
            "(SELECT id FROM clipboard_items WHERE deletedAt = 0 ORDER BY pinned DESC, timestamp DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM clipboard_items WHERE deletedAt = 0 AND content = :content AND pinned = 0")
    suspend fun deleteByContent(content: String)
}

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionary_words WHERE word = :word LIMIT 1")
    suspend fun find(word: String): WordEntity?

    @Query(
        "SELECT * FROM dictionary_words WHERE blocked = 0 AND word LIKE :prefix || '%' " +
            "ORDER BY count DESC, lastUsed DESC LIMIT :limit"
    )
    suspend fun startingWith(prefix: String, limit: Int): List<WordEntity>

    @Query("SELECT * FROM dictionary_words ORDER BY count DESC, word ASC LIMIT :limit OFFSET :offset")
    fun observeAll(limit: Int, offset: Int): Flow<List<WordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: WordEntity): Long

    /** Bulk path for importing a word list; the unique index on `word` deduplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(words: List<WordEntity>)

    @Query("UPDATE dictionary_words SET count = count + 1, lastUsed = :now WHERE word = :word")
    suspend fun bump(word: String, now: Long)

    @Query("UPDATE dictionary_words SET blocked = 1 WHERE word = :word")
    suspend fun block(word: String)

    @Query("DELETE FROM dictionary_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("DELETE FROM dictionary_words WHERE locked = 0")
    suspend fun clearLearned()

    @Query("SELECT COUNT(*) FROM dictionary_words")
    suspend fun count(): Int

    // --- bigrams --------------------------------------------------------

    @Query(
        "SELECT * FROM word_bigrams WHERE previous = :previous AND next LIKE :prefix || '%' " +
            "ORDER BY count DESC, lastUsed DESC LIMIT :limit"
    )
    suspend fun following(previous: String, prefix: String, limit: Int): List<BigramEntity>

    @Query("SELECT * FROM word_bigrams WHERE previous = :previous AND next = :next LIMIT 1")
    suspend fun findBigram(previous: String, next: String): BigramEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBigram(bigram: BigramEntity)

    @Query("DELETE FROM word_bigrams")
    suspend fun clearBigrams()
}

@Dao
interface ShortcutDao {

    @Query("SELECT * FROM text_shortcuts ORDER BY shortcut ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM text_shortcuts WHERE shortcut = :shortcut LIMIT 1")
    suspend fun find(shortcut: String): ShortcutEntity?

    @Query("SELECT * FROM text_shortcuts")
    suspend fun all(): List<ShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShortcutEntity)

    @Query("DELETE FROM text_shortcuts WHERE id = :id")
    suspend fun delete(id: Long)
}
