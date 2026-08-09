package com.example.domain.host

/**
 * Host abstraction: IME/overlay/editor preview (Zasada 69).
 */
enum class HostType {
    IME,
    OVERLAY,
    EDITOR_PREVIEW
}

interface PresentationHost {
    val type: HostType
    
    /**
     * Zwraca możliwości nakładania na inne aplikacje (Zasada 70).
     */
    val canOverlayCrossApp: Boolean
    val canCaptureTouch: Boolean
    val canPassThroughTouch: Boolean
    
    fun requestPointerCapture()
    fun releasePointerCapture()
}
