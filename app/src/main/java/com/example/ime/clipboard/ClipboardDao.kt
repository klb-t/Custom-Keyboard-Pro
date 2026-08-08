package com.example.ime.clipboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ClipboardItem)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteItemById(id: Long)

    @Query("DELETE FROM clipboard_items WHERE timestamp < :timeLimit")
    suspend fun deleteItemsOlderThan(timeLimit: Long)
    
    @Query("DELETE FROM clipboard_items")
    suspend fun deleteAll()
    
    @Query("UPDATE clipboard_items SET content = :newContent WHERE id = :id")
    suspend fun updateItemContent(id: Long, newContent: String)
}
