package com.example.assistant

import com.example.core.assistant.GoalStep
import org.junit.Test

class GoalDispatchPolicyTest {
    @Test fun localReadOnlyBindingsUseAWorker() {
        check(GoalDispatchPolicy.runOnWorker(GoalStep("s1", "phone_info", mapOf("section" to "processes"))))
        check(GoalDispatchPolicy.runOnWorker(GoalStep("s1", "sensors")))
        check(GoalDispatchPolicy.runOnWorker(GoalStep("s1", "app_info", mapOf("package" to "com.example.app"))))
    }
    @Test fun launchCaptureSetupAndMutationBindingsStayOnMain() {
        listOf(
            GoalStep("s1", "sensor_monitor", mapOf("sensor" to "s1.1.0")),
            GoalStep("s1", "phone_tools"),
            GoalStep("s1", "special_access", mapOf("target" to "usage")),
            GoalStep("s1", "app_settings", mapOf("package" to "com.example.app")),
            GoalStep("s1", "rotation", mapOf("state" to "locked")),
            GoalStep("s1", "screen_timeout", mapOf("seconds" to "60")),
            GoalStep("s1", "volume_set", mapOf("level" to "0.3")),
            GoalStep("s1", "app_info", mapOf("package" to "invalid"))
        ).forEach { check(!GoalDispatchPolicy.runOnWorker(it)) }
    }
}
