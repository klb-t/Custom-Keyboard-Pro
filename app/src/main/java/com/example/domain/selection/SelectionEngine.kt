package com.example.domain.selection

class SelectionEngine(
    private val backendProvider: () -> SelectionBackend
) {
    fun handleIntent(intent: SelectionIntent): SelectionResult {
        return backendProvider().executeIntent(intent)
    }
}
