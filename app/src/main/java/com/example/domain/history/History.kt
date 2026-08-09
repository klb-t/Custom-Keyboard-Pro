package com.example.domain.history

import com.example.domain.model.PositionConstraint
import java.util.UUID

/**
 * Editing architecture — wszystkie zmiany jako operacje (Zasada 14)
 * Zapewnia Undo/Redo (Zasada 15) oraz Persistent full edit history (Zasada 16).
 * 
 * Prefer serializable operations/events as data (Zasada 8).
 */
sealed interface KeyboardOperation {
    val id: String
    val timestamp: Long
    val description: String
    
    data class MovePanel(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val description: String = "Move Panel",
        val panelId: String,
        val newConstraint: PositionConstraint
    ) : KeyboardOperation
    
    // Inne operacje dodawane w miarę potrzeb
}

/**
 * Journaling (Zasada 16) - dopisujemy operacje.
 * Jeżeli cofamy i wykonujemy nową operację, przycinamy historię (Zasada 8).
 */
class HistoryManager {
    private var journal = listOf<KeyboardOperation>()
    private var currentIndex = -1

    fun execute(operation: KeyboardOperation) {
        if (currentIndex < journal.size - 1) {
            // "Alternative branch" logic could go here in the future.
            // For now, explicitly truncate redo history correctly in the temporary linear implementation (Zasada 8)
            journal = journal.take(currentIndex + 1)
        }
        
        journal = journal + operation
        currentIndex = journal.size - 1
    }

    fun undo() {
        if (currentIndex >= 0) {
            currentIndex--
        }
    }

    fun redo() {
        if (currentIndex < journal.size - 1) {
            currentIndex++
        }
    }
    
    fun getActiveOperations(): List<KeyboardOperation> {
        if (currentIndex < 0) return emptyList()
        return journal.take(currentIndex + 1)
    }
    
    // Do celów testowych
    fun getCurrentIndex(): Int = currentIndex
    fun getJournalSize(): Int = journal.size
}
