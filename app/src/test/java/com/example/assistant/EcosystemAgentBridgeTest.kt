package com.example.assistant

import com.example.core.assistant.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The same interchange fixture is consumed by the optional real Loom runtime tests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EcosystemAgentBridgeTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/agent-bridge/plan-v1.json"))
        .bufferedReader().use { it.readText() }

    @Test fun runtimeProposalEntersTheSameVoiceGoalReviewPathWithoutGrants() {
        val agent = GoalAgent()
        agent.editGoal("Make this bulb react to music", GoalAgent.InputSource.VOICE)
        agent.offerPlans(GoalJson.read(fixture(), agent.goal))
        check(agent.inputSource == GoalAgent.InputSource.VOICE)
        check(agent.phase == GoalAgent.Phase.PROPOSED)
        check(agent.session == null)
        val session = agent.selectPlan(0, emptyMap())
        check(session.states().values.all { it == GoalSession.State.PENDING })
        check(!GoalAssessment.step(agent.proposals.single().steps.last(), emptyMap(),
            CapabilitySnapshot(0, emptyMap())).ready)
    }

    @Test fun proposalContentCannotOverwriteTheLocalGoalOrRestoreAuthority() {
        val agent = GoalAgent()
        agent.editGoal("Review setup only")
        val plans = GoalJson.read(fixture(), agent.goal)
        agent.offerPlans(plans)
        check(agent.goal == "Review setup only" && plans.single().goal == agent.goal)
        check(runCatching { GoalJson.read(fixture().replace("\"version\": 1",
            "\"version\": 1, \"approved\": true"), agent.goal) }.isFailure)
        check(agent.session == null)
    }

    @Test fun exportedDefinitionsRemainCompatibleWithTheRuntimeProjection() {
        val original = GoalJson.read(fixture(), "First locally reviewed goal")
        val raw = GoalJson.write(original)
        val rebound = GoalJson.read(raw, "Second locally reviewed goal")
        check(rebound.single() == original.single().copy(goal = "Second locally reviewed goal"))
        check(!raw.contains("First locally reviewed goal"))
    }
}
