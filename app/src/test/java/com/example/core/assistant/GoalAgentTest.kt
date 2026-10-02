package com.example.core.assistant

import org.junit.Test

class GoalAgentTest {
    private val requestedGoal = "Change media volume"
    private val actions = mapOf("volume_set" to GoalAction("volume_set", "Volume", "Media volume",
        listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0))),
        Requirement.Capability("access"), true, "Changes media volume", "Read back media volume"))
    private fun proposal(after: Boolean = false, actualGoal: String = requestedGoal) = GoalPlan(actualGoal, "Volume route",
        listOf(GoalStep("s1", "volume_set", mapOf("level" to "0.3"))) + if (after)
            listOf(GoalStep("s2", "volume_set", mapOf("level" to "0.4"), setOf("s1"))) else emptyList())
    private fun facts(now: Long = 0, granted: Boolean = true) = CapabilitySnapshot(now, mapOf(
        "access" to CapabilityFact("access", if (granted) Readiness.READY else Readiness.PERMISSION,
            if (granted) "granted" else "revoked")))
    private fun prepared(source: GoalAgent.InputSource = GoalAgent.InputSource.TEXT, after: Boolean = false) =
        GoalAgent().apply { editGoal(requestedGoal, source); offerPlans(listOf(proposal(after))); selectPlan(0, actions) }
    private fun start(agent: GoalAgent, id: String = "s1") =
        agent.begin(agent.review(id, facts(), 0), facts(1), 1)!!

    @Test fun voiceAndTextShareTheSameReviewedExecutionAndVerificationLifecycle() {
        GoalAgent.InputSource.entries.forEach { source ->
            val value = GoalAgent()
            value.editGoal(requestedGoal, source)
            check(value.phase == GoalAgent.Phase.INPUT && value.session == null && value.proposals.isEmpty())
            val turn = value.beginPlanning()
            check(turn.source == source && turn.goal == requestedGoal && value.phase == GoalAgent.Phase.PLANNING)
            check(value.finishPlanning(turn, listOf(proposal())))
            check(value.phase == GoalAgent.Phase.PROPOSED && value.session == null)
            value.selectPlan(0, actions)
            check(value.phase == GoalAgent.Phase.REVIEWING)
            val ticket = start(value)
            check(value.phase == GoalAgent.Phase.EXECUTING)
            check(value.complete(ticket, GoalSession.Receipt(GoalSession.State.VERIFIED, "Android readback")))
            check(value.phase == GoalAgent.Phase.COMPLETE)
        }
    }

    @Test fun typingAfterSpeechInvalidatesThePendingVoicePlanAndItsLateFailure() {
        val value = GoalAgent(); value.editGoal(requestedGoal, GoalAgent.InputSource.VOICE)
        val turn = value.beginPlanning()
        value.editGoal("Different goal")
        check(!value.finishPlanning(turn, listOf(proposal())) && !value.failPlanning(turn))
        check(value.goal == "Different goal" && value.inputSource == GoalAgent.InputSource.TEXT)
        check(value.phase == GoalAgent.Phase.INPUT && value.proposals.isEmpty())
    }

    @Test fun cancelAndPauseDiscardPlannerResultsAndDoNotRestartThemOnResume() {
        val cancelled = GoalAgent().apply { editGoal(requestedGoal) }
        val first = cancelled.beginPlanning(); cancelled.cancel()
        check(!cancelled.finishPlanning(first, listOf(proposal())) && cancelled.phase == GoalAgent.Phase.STOPPED)
        val paused = GoalAgent().apply { editGoal(requestedGoal) }
        val second = paused.beginPlanning(); paused.pause()
        check(!paused.finishPlanning(second, listOf(proposal())) && paused.phase == GoalAgent.Phase.INPUT)
        check(paused.goal == requestedGoal && paused.session == null)
    }

    @Test fun aNewPlannerTurnAndAnotherAgentCannotAcceptAnOldResponse() {
        val value = GoalAgent().apply { editGoal(requestedGoal) }
        val old = value.beginPlanning(); val current = value.beginPlanning()
        val other = GoalAgent().apply { editGoal(requestedGoal); beginPlanning() }
        check(!value.finishPlanning(old, listOf(proposal())) && !other.finishPlanning(current, listOf(proposal())))
        check(value.finishPlanning(current, listOf(proposal())))
        check(!value.finishPlanning(current, listOf(proposal())))
    }

    @Test fun invalidOrWrongGoalProposalsCannotReplaceTheExistingRoute() {
        val value = prepared()
        val previous = value.session
        check(runCatching { value.offerPlans(listOf(proposal(actualGoal = "Different goal"))) }.isFailure)
        check(runCatching { value.offerPlans(listOf(proposal().copy(steps = emptyList()))) }.isFailure)
        check(runCatching { value.offerPlans(emptyList()) }.isFailure)
        check(value.session === previous && !previous!!.stopped)
        val turn = value.beginPlanning()
        check(runCatching { value.finishPlanning(turn, listOf(proposal(actualGoal = "Wrong"))) }.isFailure)
        check(value.isCurrent(turn) && value.proposals.isEmpty())
        check(value.failPlanning(turn) && value.phase == GoalAgent.Phase.INPUT)
    }

    @Test fun returnedProposalsAndImportedDefinitionsCannotTransferOrMutateApproval() {
        val value = prepared(); val review = value.review("s1", facts(), 0)
        val exported = value.proposals
        (exported.single().steps.single().arguments as MutableMap<String, String>)["level"] = "0.9"
        check(value.proposals.single().steps.single().arguments["level"] == "0.3")
        value.offerPlans(value.proposals); value.selectPlan(0, actions)
        check(value.begin(review, facts(1), 1) == null)
        check(value.session!!.states()["s1"] == GoalSession.State.PENDING)
    }

    @Test fun replacingVoiceInputRevokesAnExecutingTicketAndDoesNotClaimRollback() {
        val value = prepared(GoalAgent.InputSource.VOICE)
        val prior = value.session!!; val ticket = start(value)
        value.editGoal("New spoken goal", GoalAgent.InputSource.VOICE)
        check(prior.stopped && prior.states()["s1"] == GoalSession.State.DISPATCHED_UNVERIFIED)
        check(!value.complete(ticket, GoalSession.Receipt(GoalSession.State.VERIFIED, "late result")))
        check(value.session == null && value.phase == GoalAgent.Phase.INPUT)
    }

    @Test fun pauseRevokesStepApprovalButRetainsTheSelectedPlanForFreshReview() {
        val value = prepared(); val selected = value.session!!
        val review = value.review("s1", facts(), 0); value.pause()
        check(value.session === selected && value.begin(review, facts(1), 1) == null)
        check(value.phase == GoalAgent.Phase.REVIEWING && !selected.stopped)
        check(start(value).step.id == "s1")
    }

    @Test fun unverifiedEffectsRemainBlockedUntilExplicitObservationAndCancellationRetainsHistory() {
        val value = prepared(after = true)
        check(value.complete(start(value), GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED, "request sent")))
        check(value.phase == GoalAgent.Phase.AWAITING_OBSERVATION && !value.session!!.dependenciesSatisfied("s2"))
        check(runCatching { value.review("s2", facts(), 0) }.isFailure)
        check(value.confirmObserved("s1") && value.session!!.states()["s1"] == GoalSession.State.USER_CONFIRMED)
        check(value.phase == GoalAgent.Phase.REVIEWING)
        start(value, "s2"); value.cancel()
        check(value.phase == GoalAgent.Phase.STOPPED)
        check(value.session!!.states() == mapOf("s1" to GoalSession.State.USER_CONFIRMED,
            "s2" to GoalSession.State.DISPATCHED_UNVERIFIED))
    }

    @Test fun speechSourceAndImportedPlansCannotBypassFreshPermissionsOrMissingAdapters() {
        val value = prepared(GoalAgent.InputSource.VOICE)
        val review = value.review("s1", facts(), 0)
        check(value.begin(review, facts(1, granted = false), 1) == null)
        value.offerPlans(listOf(proposal().copy(steps = listOf(GoalStep("s1", "missing.integration")))))
        value.selectPlan(0, actions)
        val gap = value.review("s1", facts(), 0)
        check(!gap.feasible.ready && gap.feasible.issues.single().state == Readiness.MISSING_ADAPTER)
        check(value.begin(gap, facts(1), 1) == null)
    }
}
