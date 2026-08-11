package com.example.domain.input

class CoordinateTransform(
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f,
    private val scaleX: Float = 1f,
    private val scaleY: Float = 1f
) {
    fun transform(x: Float, y: Float, fromSpace: CoordinateSpace, toSpace: CoordinateSpace): Pair<Float, Float> {
        // Simplified identity if spaces match or no transform needed for test
        if (fromSpace == toSpace) return Pair(x, y)
        // A real transform would use the offsets/scales
        return Pair(x * scaleX + offsetX, y * scaleY + offsetY)
    }
}
