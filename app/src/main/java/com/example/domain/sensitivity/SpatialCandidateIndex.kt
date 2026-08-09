package com.example.domain.sensitivity

class SpatialCandidateIndex(
    val tileSize: Float,
    val width: Float,
    val height: Float
) {
    private val tiles = mutableMapOf<Pair<Int, Int>, MutableList<LocalSensitivityPatch>>()

    fun buildIndex(patches: List<LocalSensitivityPatch>) {
        tiles.clear()
        for (patch in patches) {
            val startCol = (patch.originX / tileSize).toInt()
            val startRow = (patch.originY / tileSize).toInt()
            val endCol = ((patch.originX + patch.width) / tileSize).toInt()
            val endRow = ((patch.originY + patch.height) / tileSize).toInt()
            
            for (row in startRow..endRow) {
                for (col in startCol..endCol) {
                    tiles.getOrPut(col to row) { mutableListOf() }.add(patch)
                }
            }
        }
    }

    fun getCandidates(x: Float, y: Float): List<LocalSensitivityPatch> {
        val col = (x / tileSize).toInt()
        val row = (y / tileSize).toInt()
        return tiles[col to row] ?: emptyList()
    }
}
