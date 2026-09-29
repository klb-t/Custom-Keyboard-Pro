package com.example.core.assistant

import org.junit.Test

class GoalPlanTest {
    private val action = GoalAction("volume_set", "Volume", "Set volume",
        listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0))),
        Requirement.Capability("access"), true, "Changes volume", "Read back")
    private val catalogue = mapOf(action.id to action)
    private fun snapshot(state: Readiness = Readiness.READY, time: Long = 0) = CapabilitySnapshot(time,
        mapOf("access" to CapabilityFact("access", state, "test")))
    private fun step(id: String = "s1", after: Set<String> = emptySet()) = GoalStep(id, "volume_set", mapOf("level" to "0.3"), after)
    private fun plan(vararg steps: GoalStep) = GoalPlan("Set the volume", "Direct route", steps.toList().ifEmpty { listOf(step()) })
    private fun session(p: GoalPlan = plan()) = GoalSession(p, catalogue)
    private fun ticket(s: GoalSession) = s.begin(s.review("s1", snapshot(), 0), snapshot(time = 1), 1)!!
    @Test fun allRequirementsNeedAllEvidence() {
        val r = Requirement.All(listOf(Requirement.Capability("access"), Requirement.Capability("hardware")))
        val f = FeasibilityCheck.inspect(r, snapshot()); check(!f.ready && f.issues.single().id == "hardware")
    }
    @Test fun alternativesDoNotBecomeConjunctions() {
        val r = Requirement.Any(listOf(Requirement.Capability("missing"), Requirement.Capability("access")))
        val f = FeasibilityCheck.inspect(r, snapshot()); check(f.ready && f.alternatives.size == 2)
    }
    @Test fun failedAlternativesRemainVisible() {
        val f = FeasibilityCheck.inspect(Requirement.Any(listOf(Requirement.Capability("a"), Requirement.Capability("b"))), snapshot())
        check(!f.ready && f.issues.size == 2 && f.alternatives.size == 2)
    }
    @Test fun emptyAlternativesAreNotPermission() { check(!FeasibilityCheck.inspect(Requirement.Any(emptyList()), snapshot()).ready) }
    @Test fun noRequirementReallyNeedsNothing() { check(FeasibilityCheck.inspect(Requirement.None, CapabilitySnapshot(0, emptyMap())).ready) }
    @Test fun missingAdapterIsNotCalledImpossible() {
        val f = GoalAssessment.step(step().copy(action = "missing.lamp"), catalogue, snapshot())
        check(f.issues.single().state == Readiness.MISSING_ADAPTER && !f.ready)
    }
    @Test fun cataloguePresenceDoesNotEstablishHostImplementation() {
        val f = GoalAssessment.step(step(), mapOf(action.id to action.copy(runnable = false)), snapshot())
        check(!f.ready && f.issues.single().state == Readiness.MISSING_ADAPTER)
    }
    @Test fun unknownCapabilityCannotBeInventedByModel() {
        check(!GoalAssessment.step(step(), catalogue, CapabilitySnapshot(0, emptyMap())).ready)
    }
    @Test fun readinessCategoriesStayDistinct() {
        for (v in Readiness.entries.filter { it != Readiness.READY }) {
            val f = GoalAssessment.step(step(), catalogue, snapshot(v)); check(!f.ready && f.issues.single().state == v)
        }
    }
    @Test fun rejectsUnknownOrMissingArguments() {
        check(action.invalidArguments(emptyMap()).isNotEmpty())
        check(action.invalidArguments(mapOf("level" to "0.3", "shell" to "anything")).isNotEmpty())
    }
    @Test fun numericArgumentsRejectNonfiniteAndOutOfRange() {
        for (v in listOf("NaN", "Infinity", "-Infinity", "1e999", "1.1", "-0.1", "")) check(!ArgumentRule.Number(0.0,1.0).accepts(v))
        check(ArgumentRule.Number(0.0,1.0).accepts("0.35"))
    }
    @Test fun integersAreNotSilentlyRounded() {
        check(!ArgumentRule.Number(1.0,1000.0,true).accepts("1.5"));check(ArgumentRule.Number(1.0,1000.0,true).accepts("1000"))
    }
    @Test fun explicitChoicesAreExact() { check(!ArgumentRule.Choice(setOf("on","off")).accepts("toggle")) }
    @Test fun textInputHasABudgetAndNoNul() {
        check(!ArgumentRule.Text(2).accepts("abc"));check(!ArgumentRule.Text().accepts("a\u0000b"));check(ArgumentRule.Text().accepts("line\nline"))
    }
    @Test fun openTargetsRejectPrivilegedSchemesAndCredentials() {
        for (v in listOf("intent://x", "javascript:alert(1)", "file:///x", "content://x", "tel:123", "https://u:p@example.org", "https://example.org\n", "http://example.org"))
            check(!ArgumentRule.OpenTarget.accepts(v)) { v }
    }
    @Test fun onlyKnownInternalConsentLinksAreAccepted() {
        check(ArgumentRule.OpenTarget.accepts("iomatrix://capture"));check(ArgumentRule.OpenTarget.accepts("iomatrix://colour-organ"))
        for (v in listOf("iomatrix://capture?start=true", "iomatrix://assistant", "iomatrix://colour-organ#run", "iomatrix://capture/other")) check(!ArgumentRule.OpenTarget.accepts(v))
    }
    @Test fun webAndPackageTargetsAreAcceptedAsOpenActionsOnly() {
        check(ArgumentRule.OpenTarget.accepts("https://example.org/docs?q=a"));check(ArgumentRule.OpenTarget.accepts("com.example.application"))
    }
    @Test fun validDagCanBeListedInAnyOrder() { check(plan(step("s2",setOf("s1")),step()).validate().isEmpty()) }
    @Test fun cyclesAndUnknownDependenciesAreRejected() {
        check(plan(step("s1",setOf("s2")),step("s2",setOf("s1"))).validate().isNotEmpty())
        check(plan(step(after=setOf("unknown"))).validate().isNotEmpty())
    }
    @Test fun duplicateAndSelfDependentStepsAreRejected() {
        check(plan(step(),step()).validate().isNotEmpty());check(plan(step(after=setOf("s1"))).validate().isNotEmpty())
    }
    @Test fun invalidIdentifiersAndResourceLimitsAreRejected() {
        check(plan(step().copy(id="a\nb")).validate().isNotEmpty())
        check(plan(*Array(33) { step("s$it") }).validate().isNotEmpty())
        check(plan().copy(goal="x".repeat(8001)).validate().isNotEmpty())
        check(plan(step().copy(arguments=mapOf("x" to "x".repeat(8001)))).validate().isNotEmpty())
    }
    @Test fun fingerprintBindsEveryEffectAndReason() {
        val p=plan();check(p.fingerprint()!=p.copy(goal="other").fingerprint())
        check(p.fingerprint()!=plan(step().copy(arguments=mapOf("level" to "0.4"))).fingerprint())
        check(p.fingerprint()!=plan(step().copy(expected="other")).fingerprint())
    }
    @Test fun callerCannotMutateTheStoredPlan() {
        val args=mutableMapOf("level" to "0.3");val steps=mutableListOf(step().copy(arguments=args))
        val s=session(plan().copy(steps=steps));args["level"]="0.9";steps.clear()
        check(s.plan.steps.single().arguments["level"]=="0.3")
    }
    @Test fun planningAndReviewDoNotRunAnything() {
        val s=session();s.review("s1",snapshot(),0);check(s.states()["s1"]==GoalSession.State.PENDING)
    }
    @Test fun deniedGrantPreventsBeginning() {
        val s=session();val snap=snapshot(Readiness.PERMISSION);check(s.begin(s.review("s1",snap,0),snap,0)==null)
    }
    @Test fun revokedGrantInvalidatesApproval() {
        val s=session();val r=s.review("s1",snapshot(),0)
        check(s.begin(r,snapshot(Readiness.PERMISSION,1),1)==null)
    }
    @Test fun staleAndFutureEvidenceFailClosed() {
        val s=session();check(!s.review("s1",snapshot(),6000).feasible.ready)
        check(!s.review("s1",snapshot(time=1000),0).feasible.ready)
    }
    @Test fun currentEvidenceIsRequiredAtDispatch() {
        val s=session();val r=s.review("s1",snapshot(),0)
        check(s.begin(r,snapshot(),6000)==null);check(s.begin(r,snapshot(time=5),1)==null)
    }
    @Test fun oldConfirmationExpiresEvenWithFreshEvidence() {
        val s=session();val r=s.review("s1",snapshot(),0)
        check(s.begin(r,snapshot(time=121000),121000)==null)
    }
    @Test fun oneApprovalAllowsOneDispatchOnly() {
        val s=session();val r=s.review("s1",snapshot(),0);check(s.begin(r,snapshot(),0)!=null);check(s.begin(r,snapshot(),0)==null)
    }
    @Test fun dependenciesWaitForActualVerification() {
        val s=session(plan(step(),step("s2",setOf("s1"))));val t=ticket(s)
        s.complete(t,GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED,"sent"))
        check(!s.dependenciesSatisfied("s2"));check(!s.allEffectsConfirmed())
    }
    @Test fun humanObservationIsLabelledAndUnblocksDependencies() {
        val s=session(plan(step(),step("s2",setOf("s1"))));s.complete(ticket(s),GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED,"sent"))
        check(s.confirmObserved("s1"));check(s.states()["s1"]==GoalSession.State.USER_CONFIRMED && s.dependenciesSatisfied("s2"))
    }
    @Test fun platformReadbackIsDistinctFromHumanObservation() {
        val s=session();s.complete(ticket(s),GoalSession.Receipt(GoalSession.State.VERIFIED,"read back"))
        check(s.allEffectsConfirmed());check(!s.confirmObserved("s1"))
    }
    @Test fun failedEffectsNeverUnblockDependentTasks() {
        val s=session(plan(step(),step("s2",setOf("s1"))));s.complete(ticket(s),GoalSession.Receipt(GoalSession.State.FAILED,"failed"))
        check(!s.dependenciesSatisfied("s2"));check(!s.confirmObserved("s1"))
    }
    @Test fun failedNonIdempotentEffectIsNotAutomaticallyRetried() {
        val s=session();s.complete(ticket(s),GoalSession.Receipt(GoalSession.State.FAILED,"unknown"))
        check(runCatching{s.review("s1",snapshot(),0)}.isFailure)
    }
    @Test fun pauseInvalidatesApprovalWithoutLosingThePlan() {
        val s=session();val r=s.review("s1",snapshot(),0);s.pause()
        check(s.begin(r,snapshot(),0)==null && s.states()["s1"]==GoalSession.State.PENDING)
        check(s.begin(s.review("s1",snapshot(),0),snapshot(),0)!=null)
    }
    @Test fun pauseDuringDispatchPreservesUncertainty() {
        val s=session();val t=ticket(s);s.pause()
        check(s.states()["s1"]==GoalSession.State.DISPATCHED_UNVERIFIED)
        check(!s.complete(t,GoalSession.Receipt(GoalSession.State.VERIFIED,"late")))
    }
    @Test fun stopPreventsLateCallbacksAndFutureEffects() {
        val s=session();val t=ticket(s);s.stop();s.stop()
        check(!s.complete(t,GoalSession.Receipt(GoalSession.State.VERIFIED,"late")))
        check(s.states()["s1"]==GoalSession.State.DISPATCHED_UNVERIFIED && runCatching{s.review("s1",snapshot(),0)}.isFailure)
    }
    @Test fun twoUnrelatedStepsCannotRunConcurrently() {
        val s=session(plan(step(),step("s2")));ticket(s)
        check(runCatching{s.review("s2",snapshot(),0)}.isFailure)
    }
    @Test fun aReceiptCannotBeReplayed() {
        val s=session();val t=ticket(s);check(s.complete(t,GoalSession.Receipt(GoalSession.State.VERIFIED,"ok")))
        check(!s.complete(t,GoalSession.Receipt(GoalSession.State.FAILED,"replayed")))
    }
    @Test fun jsonBudgetRejectsDeepOrUnfinishedDocuments() {
        for (s in listOf("{"+"[".repeat(13)+"]".repeat(13)+"}","{\"x\":\"unfinished}","{}".repeat(40000),"```json\n{}\n```"))
            check(runCatching{PlanJsonBudget.check(s)}.isFailure)
    }
    @Test fun jsonBudgetIgnoresBracketsInsideStrings() { PlanJsonBudget.check("{\"x\":\"[[[{{{ \\\" yes\"}") }
    @Test fun promptContainsTheActualCatalogueAndNoInventedReadiness() {
        val s=GoalPrompt.system(catalogue.values,snapshot(Readiness.PERMISSION))
        check(s.contains("volume_set") && s.contains("PERMISSION") && s.contains("missing.*") && s.contains("proposal, never permission"))
    }
    @Test fun integerArgumentsMatchTheCanonicalPerformerParser() {
        for (raw in listOf("1e2", "100.0", "Infinity")) check(!ArgumentRule.Number(1.0,1000.0,true).accepts(raw))
    }
    @Test fun nestedConjunctionsKeepTheirAlternativeEvidence() {
        val need = Requirement.All(listOf(Requirement.Any(listOf(Requirement.Capability("a"),Requirement.Capability("b"))),Requirement.Capability("access")))
        val f = FeasibilityCheck.inspect(need,snapshot())
        check(!f.ready && f.requirements.size == 2 && f.requirements[0].alternatives.size == 2)
    }
    @Test fun stopCancelsPendingStepsButDoesNotClaimRollbackOfDispatchedOnes() {
        val s=session(plan(step(),step("s2")));ticket(s);s.stop()
        check(s.states()["s2"]==GoalSession.State.CANCELLED && s.states()["s1"]==GoalSession.State.DISPATCHED_UNVERIFIED)
        check(s.notes()["s1"]!!.contains("rollback"))
    }
}
