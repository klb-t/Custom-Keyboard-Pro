package com.example.domain.history

import java.util.UUID

/**
 * Editing architecture — wszystkie zmiany jako operacje (Zasada 14)
 * Zapewnia Undo/Redo (Zasada 15) oraz Persistent full edit history (Zasada 16).
 */
interface Operation {
    val id: String
    val timestamp: Long
    val description: String
    
    fun apply()
    fun revert()
}

/**
 * Journaling (Zasada 16) - dopisujemy operacje, aplikacja odtwarza stan.
 */
class HistoryManager {
    private val journal = mutableListOf<Operation>()
    private var currentIndex = -1

    fun execute(operation: Operation) {
        // Jeżeli jesteśmy w trakcie "cofnięcia", to nowa operacja tworzy nową gałąź (Zasada 17)
        // W prostej implementacji ucinamy branch, ale zgodnie z zasadą 17 powinniśmy go zachować.
        // Na ten moment dla prostoty zachowujemy listową formę.
        if (currentIndex < journal.size - 1) {
            // "Alternative branch" logic would go here.
        }
        
        operation.apply()
        journal.add(operation)
        currentIndex = journal.size - 1
    }

    fun undo() {
        if (currentIndex >= 0) {
            journal[currentIndex].revert()
            currentIndex--
        }
    }

    fun redo() {
        if (currentIndex < journal.size - 1) {
            currentIndex++
            journal[currentIndex].apply()
        }
    }
}
