package com.example.assistant

import com.example.core.assistant.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android JSONObject schema gates. Requires the real Gradle/Robolectric suite (not standalone shim). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GoalJsonTest {
    private fun plan() = GoalPlan("Original local goal", "Route 😀", listOf(GoalStep("s1", "open",
        mapOf("target" to "iomatrix://colour-organ"), reason = "Open setup, not automatic capture",
        expected = "A visible panel", apiHints = listOf("unverified documentation lead"))))
    private fun fails(raw: String) { check(runCatching { GoalJson.read(raw, "Actual user goal") }.isFailure) }
    @Test fun roundTripBindsTheActualGoalNotAnImportedAuthority() {
        val original = plan(); val decoded = GoalJson.read(GoalJson.write(listOf(original)), "New local goal").single()
        check(decoded == original.copy(goal = "New local goal"))
    }
    @Test fun executionStateAndPermissionClaimsCannotBeImported() {
        val raw = GoalJson.write(listOf(plan()))
        fails(raw.replace("\"version\": 1", "\"version\": 1, \"approved\":true"))
        fails(raw.replace("\"id\": \"s1\"", "\"id\": \"s1\",\"state\":\"VERIFIED\""))
    }
    @Test fun argumentsAreStringsNotExecutableObjects() {
        val raw = GoalJson.write(listOf(plan()))
        fails(raw.replace("\"iomatrix://colour-organ\"", "{\"run\":\"code\"}"))
        fails(raw.replace("\"iomatrix://colour-organ\"", "true"))
    }
    @Test fun versionIsAnIntegerAndSupported() {
        fails(GoalJson.write(listOf(plan())).replace("\"version\": 1", "\"version\": \"1\""))
        fails(GoalJson.write(listOf(plan())).replace("\"version\": 1", "\"version\": 2"))
    }
    @Test fun unknownOperationsRemainGapsNotParserErrors() {
        val p = plan().copy(steps = listOf(GoalStep("s1", "missing.some_api", expected = "Research a real adapter")))
        val read = GoalJson.read(GoalJson.write(listOf(p)), "User goal").single()
        check(!GoalAssessment.step(read.steps.single(), emptyMap(), CapabilitySnapshot(0, emptyMap())).ready)
    }
    @Test fun malformedCyclicOrOversizedPlansAreRejected() {
        fails("{broken}")
        val raw = """{"version":1,"alternatives":[{"title":"cycle","steps":[{"id":"s1","action":"open","arguments":{},"after":["s1"]}]}]}"""
        fails(raw); fails(" ".repeat(65536) + "{}")
    }
    @Test fun noAlternativeIsNotAnExecutablePlan() { fails("""{"version":1,"alternatives":[]}""") }
    @Test fun exportContainsNoGoalOrRuntimeAuthorizationFields() {
        val raw = GoalJson.write(listOf(plan()))
        check(!raw.contains("Original local goal") && !raw.contains("approved") && !raw.contains("evidenceHash"))
    }
}
