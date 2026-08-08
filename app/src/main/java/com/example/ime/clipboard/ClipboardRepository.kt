package com.example.ime.clipboard

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

class ClipboardRepository(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "clipboard_db"
    ).build()

    private val dao = db.clipboardDao()

    val allItems: Flow<List<ClipboardItem>> = dao.getAllItems()

    suspend fun insert(item: ClipboardItem) = dao.insertItem(item)

    suspend fun deleteById(id: Long) = dao.deleteItemById(id)
    
    suspend fun updateContent(id: Long, newContent: String) = dao.updateItemContent(id, newContent)

    suspend fun deleteOlderThan(timeLimit: Long) = dao.deleteItemsOlderThan(timeLimit)
    
    suspend fun deleteAll() = dao.deleteAll()
}
