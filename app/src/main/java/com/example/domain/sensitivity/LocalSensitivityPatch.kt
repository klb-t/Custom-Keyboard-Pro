package com.example.domain.sensitivity

import com.example.domain.input.CoordinateSpace

data class LocalSensitivityPatch(
    val patchId: String,
    val elementId: String,
    val coordinateSpace: CoordinateSpace,
    val originX: Float,
    val originY: Float,
    val width: Int,
    val height: Int,
    val stride: Int = width,
    val data8bit: ByteArray,
    val interpolationMode: InterpolationMode = InterpolationMode.NEAREST,
    val metadata: PatchMetadata
) {
    fun sampleRaw(x: Float, y: Float): Float {
        val localX = (x - originX).toInt()
        val localY = (y - originY).toInt()
        
        if (localX in 0 until width && localY in 0 until height) {
            val index = localY * stride + localX
            val pixelValue = data8bit[index].toInt() and 0xFF
            return pixelValue / 255f
        }
        return 0f
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocalSensitivityPatch

        if (patchId != other.patchId) return false
        if (!data8bit.contentEquals(other.data8bit)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = patchId.hashCode()
        result = 31 * result + data8bit.contentHashCode()
        return result
    }
}

enum class InterpolationMode { NEAREST, BILINEAR }

data class PatchMetadata(
    val layoutRevision: Int,
    val geometryRevision: Int,
    val sensitivityRevision: Int,
    val adaptationRevision: Int,
    val surfaceRevision: Int
)
