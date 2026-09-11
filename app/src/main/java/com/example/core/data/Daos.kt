package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_items ORDER BY pinned DESC, timestamp DESC")
    fun observeAll(): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items WHERE content LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    fun search(query: String): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 1")
    suspend fun newest(): ClipboardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardEntity): Long

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_items WHERE pinned = 0 AND timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM clipboard_items WHERE pinned = 0")
    suspend fun deleteAllUnpinned()

    @Query("UPDATE clipboard_items SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE clipboard_items SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun count(): Int

    /** Drops the oldest unpinned entries beyond [keep]. */
    @Query(
        "DELETE FROM clipboard_items WHERE pinned = 0 AND id NOT IN " +
            "(SELECT id FROM clipboard_items ORDER BY pinned DESC, timestamp DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM clipboard_items WHERE content = :content AND pinned = 0")
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
