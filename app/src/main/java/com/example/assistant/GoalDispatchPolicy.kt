package com.example.assistant

import com.example.core.assistant.GoalStep
import com.example.core.io.Command
import com.example.core.phone.PhoneRequest

/** Scheduling derives from the audited typed binding; UI/effect operations stay on Main. */
internal object GoalDispatchPolicy {
    fun runOnWorker(step: GoalStep): Boolean = when (runCatching {
        PhoneRequest.parse(Command(step.action, named = step.arguments))
    }.getOrNull()) {
        is PhoneRequest.Info, PhoneRequest.Sensors, is PhoneRequest.AppInfo -> true
        else -> false
    }
}
