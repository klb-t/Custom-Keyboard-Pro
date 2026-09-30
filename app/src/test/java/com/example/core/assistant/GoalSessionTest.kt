package com.example.core.assistant

import org.junit.Test

/** Authorization belongs to one session and its frozen host catalogue, not just equal JSON. */
class GoalSessionTest {
    private fun action() = GoalAction("volume_set", "Volume", "Set volume",
        listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0))),
        Requirement.Capability("access"), true, "Changes only media volume", "Read back the value")
    private fun step(id: String = "s1", after: Set<String> = emptySet()) =
        GoalStep(id, "volume_set", mapOf("level" to "0.3"), after, apiHints = listOf("documentation"))
    private fun plan(vararg steps: GoalStep) = GoalPlan("Set volume", "Reviewed route",
        steps.toList().ifEmpty { listOf(step()) })
    private fun facts(time: Long = 0) = CapabilitySnapshot(time,
        mapOf("access" to CapabilityFact("access", Readiness.READY, "granted")))
    private fun session(plan: GoalPlan = plan()) = GoalSession(plan, mapOf("volume_set" to action()))
    private fun start(session: GoalSession, id: String = "s1") =
        session.begin(session.review(id, facts(), 0), facts(1), 1)!!

    @Test fun equalPlansAndEvidenceDoNotTransferReviewsToAnotherSession() {
        val first = session(); val second = session()
        val original = first.review("s1", facts(), 0)
        check(second.begin(original, facts(1), 1) == null)
        check(second.states()["s1"] == GoalSession.State.PENDING)
        check(first.begin(original, facts(1), 1) != null)
    }
    @Test fun equalStepsAndEpochsDoNotTransferReceiptsToAnotherSession() {
        val first = session(); val second = session()
        val original = start(first); val actual = start(second)
        check(!second.complete(original, GoalSession.Receipt(GoalSession.State.VERIFIED, "other session")))
        check(second.states()["s1"] == GoalSession.State.RUNNING)
        check(second.complete(actual, GoalSession.Receipt(GoalSession.State.VERIFIED, "actual receipt")))
        check(first.complete(original, GoalSession.Receipt(GoalSession.State.VERIFIED, "original receipt")))
    }
    @Test fun mutatingAPublicReviewOrTicketStepCannotChangeApprovedDispatchArguments() {
        val value = session(); val review = value.review("s1", facts(), 0)
        (review.step.arguments as MutableMap<String, String>)["level"] = "0.9"
        val ticket = value.begin(review, facts(1), 1)!!
        (ticket.step.arguments as MutableMap<String, String>)["level"] = "0.8"
        check(review.step.arguments["level"] == "0.3" && ticket.step.arguments["level"] == "0.3")
        check(value.plan.steps.single().arguments["level"] == "0.3")
    }
    @Test fun replacingCallerCatalogueDoesNotChangeExistingSessionReviewMetadata() {
        val original = action(); val source = mutableMapOf(original.id to original)
        val value = GoalSession(plan(), source)
        source[original.id] = original.copy(effects = "Unreviewed effects", verification = "No readback",
            runnable = false, needs = Requirement.Capability("different"))
        val review = value.review("s1", facts(), 0)
        check(review.feasible.ready && review.effects == original.effects && review.verification == original.verification)
        check(value.begin(review, facts(1), 1) != null)
    }
    @Test fun mutableChoiceSetsCannotAddUnreviewedValuesToAnExistingSession() {
        val choices = mutableSetOf("on")
        val original = action().copy(arguments = listOf(GoalArgument("level", ArgumentRule.Choice(choices))))
        val proposal = plan(step().copy(arguments = mapOf("level" to "off")))
        val value = GoalSession(proposal, mapOf(original.id to original))
        choices += "off"
        check(!value.review("s1", facts(), 0).feasible.ready)
    }
    @Test fun mutatingNestedRequirementsCannotRemoveTheOriginalAccessCondition() {
        val alternatives = mutableListOf<Requirement>(Requirement.Capability("ungranted"))
        val conjunction = mutableListOf<Requirement>(Requirement.Capability("access"), Requirement.Any(alternatives))
        val original = action().copy(needs = Requirement.All(conjunction))
        val value = GoalSession(plan(), mapOf(original.id to original))
        alternatives.clear(); alternatives += Requirement.None; conjunction.clear()
        check(!value.review("s1", facts(), 0).feasible.ready)
    }
    @Test fun mutatingCrossArgumentListsCannotRelaxAnExistingSession() {
        val arguments = mutableListOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0)),
            GoalArgument("state", ArgumentRule.Choice(setOf("on", "off"))))
        val constraints = mutableListOf<ArgumentConstraint>(ArgumentConstraint.ExcludesWhen("state", "off", "level"))
        val original = action().copy(arguments = arguments, constraints = constraints)
        val proposal = plan(step().copy(arguments = mapOf("level" to "0.3", "state" to "off")))
        val value = GoalSession(proposal, mapOf(original.id to original))
        constraints.clear()
        check(!value.review("s1", facts(), 0).feasible.ready)
    }
    @Test fun mutatingArgumentRulesCannotRelaxAnExistingSession() {
        val arguments = mutableListOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0)))
        val original = action().copy(arguments = arguments)
        val proposal = plan(step().copy(arguments = mapOf("level" to "anything")))
        val value = GoalSession(proposal, mapOf(original.id to original))
        arguments[0] = GoalArgument("level", ArgumentRule.Text())
        check(!value.review("s1", facts(), 0).feasible.ready)
    }
    @Test fun conditionalArgumentRequirementsAreFrozenRecursively() {
        val alternatives = mutableListOf<Requirement>(Requirement.Capability("ungranted"))
        val conditional = mutableListOf(ArgumentRequirement("level", "0.3", Requirement.Any(alternatives)))
        val original = action().copy(argumentNeeds = conditional)
        val value = GoalSession(plan(), mapOf(original.id to original))
        alternatives.clear(); alternatives += Requirement.None; conditional.clear()
        check(!value.review("s1", facts(), 0).feasible.ready)
    }
    @Test fun longDiagnosticNotesCarryAnExplicitMarkerWithinTheBound() {
        val value = session(); val content = "diagnostic\n".repeat(1000)
        check(value.complete(start(value), GoalSession.Receipt(GoalSession.State.VERIFIED, content)))
        val note = value.notes().getValue("s1")
        check(note.length == GoalSession.NOTE_LIMIT && note.endsWith(GoalSession.NOTE_SHORTENED))
        check(value.noteWasShortened("s1") && !value.noteWasShortened("missing"))
    }
    @Test fun definitiveFailureStopsTheRemainingRouteAndRetainsAlreadyVerifiedEffects() {
        val value = session(plan(step(), step("s2", setOf("s1")), step("s3"), step("s4", setOf("s2"))))
        check(value.complete(start(value), GoalSession.Receipt(GoalSession.State.VERIFIED, "first read back")))
        val failing = start(value, "s2")
        check(value.complete(failing, GoalSession.Receipt(GoalSession.State.FAILED, "permission revoked")))
        check(value.stopped && !value.allEffectsConfirmed())
        check(value.states() == mapOf("s1" to GoalSession.State.VERIFIED, "s2" to GoalSession.State.FAILED,
            "s3" to GoalSession.State.CANCELLED, "s4" to GoalSession.State.CANCELLED))
        check(value.notes()["s1"] == "first read back" && value.notes()["s2"] == "permission revoked")
        check(runCatching { value.review("s3", facts(), 0) }.isFailure)
    }
    @Test fun aDefinitiveCancelledReceiptStopsRemainingIndependentSteps() {
        val value = session(plan(step(), step("s2")))
        check(value.complete(start(value), GoalSession.Receipt(GoalSession.State.CANCELLED, "cancelled before dispatch")))
        check(value.stopped && value.states().values.all { it == GoalSession.State.CANCELLED })
    }
    @Test fun unverifiedDispatchRemainsPartialAndCannotUnlockADependentStep() {
        val value = session(plan(step(), step("s2", setOf("s1")), step("s3")))
        check(value.complete(start(value), GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED, "settings opened")))
        check(!value.stopped && !value.allEffectsConfirmed() && !value.dependenciesSatisfied("s2"))
        check(value.review("s3", facts(), 0).feasible.ready)
        check(value.confirmObserved("s1") && value.dependenciesSatisfied("s2"))
    }
}
