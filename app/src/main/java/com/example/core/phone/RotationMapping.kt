package com.example.core.phone

/** Infer only when independent display readouts agree; never use the activity window. */
object RotationMapping {
    fun naturalLandscape(rotatedWidth: Int, rotatedHeight: Int, rotation: Int,
                         physicalWidth: Int, physicalHeight: Int): Boolean? {
        if (rotation !in 0..3 || minOf(rotatedWidth, rotatedHeight, physicalWidth, physicalHeight) <= 0 ||
            rotatedWidth == rotatedHeight || physicalWidth == physicalHeight) return null
        val naturalWidth = if (rotation % 2 == 0) rotatedWidth else rotatedHeight
        val naturalHeight = if (rotation % 2 == 0) rotatedHeight else rotatedWidth
        val logicalLandscape = naturalWidth > naturalHeight
        // Display mode pixels are not an API guarantee of the natural orientation.
        // Use them only as corroboration, rejecting partition/emulation disagreement.
        return logicalLandscape.takeIf { it == (physicalWidth > physicalHeight) }
    }
    fun humanToUserRotation(orientation: Int, naturalLandscape: Boolean): Int {
        require(orientation in 0..3)
        return (orientation + if (naturalLandscape) 3 else 0) % 4
    }
}
