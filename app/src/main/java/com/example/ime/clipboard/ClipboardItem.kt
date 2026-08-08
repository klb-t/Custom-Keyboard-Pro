package com.example.ime.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_items")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "TEXT", "URI"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
