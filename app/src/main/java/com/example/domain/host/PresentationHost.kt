package com.example.domain.host

interface PresentationHost {
    val hostId: String
    val canOverlayCrossApp: Boolean
    val canCaptureTouch: Boolean
    val canPassThroughTouch: Boolean
    
    fun requestPointerCapture()
    fun releasePointerCapture()
}
