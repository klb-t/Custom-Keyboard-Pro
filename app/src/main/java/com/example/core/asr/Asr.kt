package com.example.core.asr

/** One candidate transcription. [confidence] is -1 when the engine does not report it. */
data class AsrAlternative(val text: String, val confidence: Float = -1f)

sealed interface AsrState {
    object Idle : AsrState

    /** Microphone is open. [partial] is the running guess, if the engine streams one. */
    data class Listening(val partial: String = "", val rms: Float = 0f) : AsrState

    object Processing : AsrState

    /** Finished. The user picks one; nothing is committed on their behalf by default. */
    data class Results(val alternatives: List<AsrAlternative>) : AsrState

    data class Error(val message: String, val needsPermission: Boolean = false) : AsrState
}

interface AsrEngine {
    /** True when this engine can run right now (permissions, availability, configuration). */
    fun isAvailable(): Boolean

    fun start(language: String, maxAlternatives: Int, onState: (AsrState) -> Unit)

    /** Stop listening and transcribe what was captured. */
    fun stop()

    /** Stop listening and throw the audio away. */
    fun cancel()

    fun release()
}
