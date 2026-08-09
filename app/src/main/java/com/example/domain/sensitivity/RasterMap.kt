package com.example.domain.sensitivity

import com.example.domain.model.Point

data class RasterSensitivityMap(
    val elementId: String,
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val metadata: RasterMetadata
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RasterSensitivityMap
        if (elementId != other.elementId) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!data.contentEquals(other.data)) return false
        return metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = elementId.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

data class RasterMetadata(
    val layoutRevision: Int,
    val geometryRevision: Int,
    val effectiveSurfaceWidth: Float,
    val effectiveSurfaceHeight: Float,
    val scale: Float,
    val orientation: Int,
    val sensitivityRevision: Int,
    val adaptationRevision: Int
)

class ProbabilisticSampler {
    fun sample(
        x: Float, 
        y: Float, 
        rasterMaps: List<RasterSensitivityMap>, 
        eventId: String,
        sourceRevision: Int
    ): KeyProbabilityDistribution {
        val candidates = mutableListOf<CandidateKey>()
        var sumScores = 0f
        
        for (map in rasterMaps) {
            val localX = x.toInt()
            val localY = y.toInt()
            
            if (localX in 0 until map.width && localY in 0 until map.height) {
                val index = localY * map.width + localX
                val pixelValue = map.data[index].toInt() and 0xFF
                val score = pixelValue / 255f
                
                if (score > 0) {
                    candidates.add(CandidateKey(map.elementId, score))
                    sumScores += score
                }
            }
        }
        
        val normalizedCandidates = if (sumScores > 0f) {
            candidates.map { CandidateKey(it.elementId, it.score / sumScores) }
        } else {
            emptyList()
        }
        
        return KeyProbabilityDistribution(
            eventId = eventId,
            tapPoint = Point(x, y),
            candidates = normalizedCandidates,
            sourceRevision = sourceRevision,
            timestamp = System.currentTimeMillis()
        )
    }
}
