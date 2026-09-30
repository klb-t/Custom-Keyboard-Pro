package com.example.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.service.voice.VoiceInteractionSession

/** System-assist gesture gateway to the existing editable/reviewed goal workspace. */
class GoalVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val appContext = context
    private var token: String? = null
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        // The activity supplies the actual UI. No invisible overlapping session window.
        setUiEnabled(false)
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        super.onPrepareShow(args, showFlags)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantSessionVisibility.currentSession.hide(token)
        if ((appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != false) {
            finish(); return
        }
        val lease = AssistantSessionVisibility.currentSession.show()
        token = lease
        // Caller bundles, AssistStructure, screenshots, URLs and recognized speech are not forwarded.
        val target = Intent(appContext, GoalAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantSessionVisibility.EXTRA_VISIBILITY_TOKEN, lease)
        try {
            if (Build.VERSION.SDK_INT >= 26) startAssistantActivity(target)
            else { AssistantSessionVisibility.currentSession.hide(lease); token = null; finish() }
        }
        catch (_: Exception) { AssistantSessionVisibility.currentSession.hide(lease); token = null; finish() }
    }
    override fun onHide() {
        AssistantSessionVisibility.currentSession.hide(token); token = null
        super.onHide()
    }
    override fun onLockscreenShown() {
        AssistantSessionVisibility.currentSession.hide(token); token = null
        finish()
        super.onLockscreenShown()
    }
    override fun onDestroy() {
        AssistantSessionVisibility.currentSession.hide(token); token = null
        super.onDestroy()
    }
}
