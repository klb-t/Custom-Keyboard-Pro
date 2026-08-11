package com.example.domain.selection

data class SelectionContext(
    val target: SelectionTargetRef,
    val text: CharSequence,
    val startOffset: Int,
    val endOffset: Int,
    val revision: Int
)

interface SelectionContextSource {
    fun getCurrentContext(): SelectionContext?
}
