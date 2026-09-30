package com.example.core.phone

import org.junit.Assert.*
import org.junit.Test

class RotationMappingTest {
    @Test fun naturallyPortraitDisplayStaysPortraitAfterAQuarterTurn() {
        assertEquals(false, RotationMapping.naturalLandscape(1080, 2400, 0, 1080, 2400))
        assertEquals(false, RotationMapping.naturalLandscape(2400, 1080, 1, 1080, 2400))
        assertEquals(0, RotationMapping.humanToUserRotation(0, false))
        assertEquals(1, RotationMapping.humanToUserRotation(1, false))
    }
    @Test fun naturallyLandscapeDisplayUsesItsOwnAxisForPortrait() {
        assertEquals(true, RotationMapping.naturalLandscape(2560, 1600, 0, 2560, 1600))
        assertEquals(true, RotationMapping.naturalLandscape(1600, 2560, 3, 2560, 1600))
        assertEquals(3, RotationMapping.humanToUserRotation(0, true))
        assertEquals(0, RotationMapping.humanToUserRotation(1, true))
    }
    @Test fun ambiguousPartitionedOrEmulatedDisplayShapeCannotProduceAGuessedOrientation() {
        assertNull(RotationMapping.naturalLandscape(1000, 1000, 0, 1000, 1000))
        assertNull(RotationMapping.naturalLandscape(0, 2400, 0, 1080, 2400))
        assertNull(RotationMapping.naturalLandscape(800, 1200, 0, 2560, 1600))
        assertNull(RotationMapping.naturalLandscape(1080, 2400, 4, 1080, 2400))
    }
}
