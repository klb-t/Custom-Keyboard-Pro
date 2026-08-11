package com.example.domain.selection

interface TextBoundaryProvider {
    fun findNextBoundary(
        text: CharSequence,
        currentOffset: Int,
        direction: Direction,
        granularity: SelectionGranularity
    ): Int?
}
