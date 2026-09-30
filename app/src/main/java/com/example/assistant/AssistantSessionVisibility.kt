package com.example.assistant

import java.util.UUID

/** Main-thread visibility lease only. It is never an execution or model-request approval. */
internal class AssistantSessionVisibility {
    private var current: String? = null
    private var hidden: (() -> Unit)? = null
    fun show(): String {
        hide(current)
        return UUID.randomUUID().toString().also { current = it }
    }
    fun isVisible(token: String?) = token != null && token == current
    fun attach(token: String?, onHidden: () -> Unit): Boolean {
        if (!isVisible(token)) return false
        hidden = onHidden
        return true
    }
    fun detach(token: String?) { if (isVisible(token)) hidden = null }
    fun hide(token: String?) {
        if (!isVisible(token)) return
        current = null
        val notify = hidden; hidden = null
        notify?.invoke()
    }
    companion object {
        val currentSession = AssistantSessionVisibility()
        const val EXTRA_VISIBILITY_TOKEN = "com.example.assistant.VISIBILITY_TOKEN"
    }
}
