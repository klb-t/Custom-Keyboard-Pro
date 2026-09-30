package com.example.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System-selected lightweight host. It opens no mic, detector, model, or storage on startup. */
class GoalVoiceInteractionService : VoiceInteractionService() {
    private var retired = false
    override fun onReady() {
        super.onReady()
        // Android can send onReady after onShutdown/onDestroy; never restart work from it.
        if (!retired) setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
    override fun onShutdown() { retired = true; super.onShutdown() }
    override fun onDestroy() { retired = true; super.onDestroy() }
}
