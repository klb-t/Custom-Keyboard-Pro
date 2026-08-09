package com.example.domain.sensitivity

import com.example.domain.model.Point
import com.example.domain.model.VectorShape

interface FallbackPolicy {
    fun fallbackHitTest(tapPoint: Point, geometry: Map<String, VectorShape>): DecisionResult
}

class GeometricFallbackPolicy : FallbackPolicy {
    override fun fallbackHitTest(tapPoint: Point, geometry: Map<String, VectorShape>): DecisionResult {
        // Simple geometric bounding box check
        for ((elementId, shape) in geometry) {
            when (shape) {
                is VectorShape.Rect -> {
                    if (tapPoint.x >= shape.offsetX && tapPoint.x <= shape.offsetX + shape.width &&
                        tapPoint.y >= shape.offsetY && tapPoint.y <= shape.offsetY + shape.height) {
                        return DecisionResult.Winner(
                            elementId = elementId,
                            metrics = DecisionMetrics(1.0f, null, null, 0f, 1)
                        )
                    }
                }
                is VectorShape.Circle -> {
                    val dx = tapPoint.x - shape.offsetX
                    val dy = tapPoint.y - shape.offsetY
                    if (dx * dx + dy * dy <= shape.radius * shape.radius) {
                        return DecisionResult.Winner(
                            elementId = elementId,
                            metrics = DecisionMetrics(1.0f, null, null, 0f, 1)
                        )
                    }
                }
                is VectorShape.Polygon -> {
                    // Placeholder for actual polygon check
                }
            }
        }
        return DecisionResult.Fallback
    }
}
