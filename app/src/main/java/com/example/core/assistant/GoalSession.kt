package com.example.core.assistant

/** The user approves a concrete step, not arbitrary future model-generated actions. */
class GoalSession(plan: GoalPlan, actions: Map<String, GoalAction>,
                  private val evidenceTtlMillis: Long = 5000) {
    private val storedPlan = plan.detached().also { require(it.validate().isEmpty()) { it.validate().joinToString(" ") } }
    private val actions = actions.mapValues { (_, action) -> action.snapshot() }
    private val identity = Any()
    val plan: GoalPlan get() = storedPlan.detached()
    enum class State { PENDING, RUNNING, DISPATCHED_UNVERIFIED, VERIFIED, USER_CONFIRMED, FAILED, CANCELLED }
    data class Receipt(val state: State, val detail: String) {
        init { require(state in setOf(State.DISPATCHED_UNVERIFIED, State.VERIFIED, State.FAILED, State.CANCELLED)) }
    }
    class Review internal constructor(step: GoalStep, val feasible: Feasibility, val effects: String,
                                      val verification: String, internal val planHash: String,
                                      internal val evidenceHash: String, internal val epoch: Long,
                                      internal val reviewedAt: Long, internal val owner: Any) {
        private val storedStep = step.detached()
        val step: GoalStep get() = storedStep.detached()
    }
    class Ticket internal constructor(step: GoalStep, internal val epoch: Long, internal val owner: Any) {
        private val storedStep = step.detached()
        val step: GoalStep get() = storedStep.detached()
    }
    private val states = storedPlan.steps.associate { it.id to State.PENDING }.toMutableMap()
    private val notes = mutableMapOf<String, String>()
    private val shortenedNotes = mutableSetOf<String>()
    private var epoch = 0L
    private var active: Ticket? = null
    var stopped = false
        private set

    init { require(evidenceTtlMillis in 1..60_000) }
    @Synchronized fun states(): Map<String, State> = states.toMap()
    @Synchronized fun notes(): Map<String, String> = notes.toMap()
    @Synchronized fun noteWasShortened(id: String): Boolean = id in shortenedNotes
    @Synchronized fun dependenciesSatisfied(id: String): Boolean = storedPlan.steps.first { it.id == id }.after.all {
        states[it] == State.VERIFIED || states[it] == State.USER_CONFIRMED
    }
    @Synchronized fun review(id: String, snapshot: CapabilitySnapshot, now: Long): Review {
        check(!stopped && active == null && states[id] == State.PENDING && dependenciesSatisfied(id))
        val step = storedPlan.steps.first { it.id == id }
        val evidence = snapshot.detached()
        val stale = now < evidence.observedAt || now - evidence.observedAt > evidenceTtlMillis
        val assessment = if (stale) Feasibility(false, listOf(CapabilityFact("stale_evidence", Readiness.UNKNOWN,
            "Refresh device state before approval."))) else GoalAssessment.step(step, actions, evidence)
        val action = actions[step.action]
        return Review(step.detached(), assessment, action?.effects ?: "Unknown external effects",
            action?.verification ?: "An adapter and verification method are missing", storedPlan.fingerprint(),
            evidence.fingerprint(), epoch, now, identity)
    }
    /** Must be called after the user's confirmation, against a newly sampled device state. */
    @Synchronized fun begin(review: Review, fresh: CapabilitySnapshot, now: Long): Ticket? {
        val evidence = fresh.detached()
        if (stopped || active != null || review.owner !== identity || review.epoch != epoch || review.planHash != storedPlan.fingerprint() ||
            review.step != storedPlan.steps.firstOrNull { it.id == review.step.id } || !review.feasible.ready ||
            now < review.reviewedAt || now - review.reviewedAt > 120_000 ||
            now < evidence.observedAt || now - evidence.observedAt > evidenceTtlMillis ||
            evidence.fingerprint() != review.evidenceHash || states[review.step.id] != State.PENDING ||
            !dependenciesSatisfied(review.step.id) || !GoalAssessment.step(review.step, actions, evidence).ready) return null
        val ticket = Ticket(review.step, ++epoch, identity)
        states[ticket.step.id] = State.RUNNING; active = ticket
        return ticket
    }
    @Synchronized fun complete(ticket: Ticket, receipt: Receipt): Boolean {
        if (stopped || ticket.owner !== identity || active !== ticket || ticket.epoch != epoch) return false
        states[ticket.step.id] = receipt.state
        val detail = receipt.detail
        notes[ticket.step.id] = if (detail.length > NOTE_LIMIT) {
            shortenedNotes += ticket.step.id
            detail.take(NOTE_LIMIT - NOTE_SHORTENED.length) + NOTE_SHORTENED
        } else { shortenedNotes -= ticket.step.id; detail }
        active = null; epoch++
        if (receipt.state == State.FAILED || receipt.state == State.CANCELLED) stop()
        return true
    }
    /** Human confirmation is retained as such, never relabelled as platform verification. */
    @Synchronized fun confirmObserved(id: String): Boolean {
        if (stopped || active != null || states[id] != State.DISPATCHED_UNVERIFIED) return false
        states[id] = State.USER_CONFIRMED; notes[id] = "The user confirmed the observed effect."
        shortenedNotes -= id
        epoch++; return true
    }
    /** Leaving the host invalidates approvals. A possibly dispatched effect stays unverified. */
    @Synchronized fun pause() {
        if (stopped) return
        active?.let {
            states[it.step.id] = State.DISPATCHED_UNVERIFIED
            notes[it.step.id] = "The host paused before an outcome was recorded; inspect the actual effect."
        }
        active = null; epoch++
    }
    @Synchronized fun stop() {
        if (stopped) return
        stopped = true; epoch++
        active?.let {
            states[it.step.id] = State.DISPATCHED_UNVERIFIED
            notes[it.step.id] = "Stopped during a potentially dispatched operation; cancellation does not prove rollback."
        }
        states.keys.toList().filter { states[it] == State.PENDING }.forEach { states[it] = State.CANCELLED }
        active = null
    }
    @Synchronized fun allEffectsConfirmed() = states.values.all { it == State.VERIFIED || it == State.USER_CONFIRMED }
    companion object {
        const val NOTE_LIMIT = 2000
        const val NOTE_SHORTENED = "\n[Output shortened for this plan. Open the relevant tool for details.]"
    }
}

/** Freeze host-owned metadata just as strictly as the proposed plan's arguments. */
private fun GoalAction.snapshot(): GoalAction = copy(
    arguments = arguments.map { it.copy(rule = it.rule.snapshot()) },
    needs = needs.snapshot(), constraints = constraints.toList(),
    argumentNeeds = argumentNeeds.map { it.copy(need = it.need.snapshot()) }
)
private fun ArgumentRule.snapshot(): ArgumentRule = when (this) {
    is ArgumentRule.Choice -> copy(values = values.toSet())
    is ArgumentRule.Number, is ArgumentRule.Text, is ArgumentRule.Pattern, ArgumentRule.OpenTarget -> this
}
private fun Requirement.snapshot(): Requirement = when (this) {
    Requirement.None, is Requirement.Capability -> this
    is Requirement.All -> copy(requirements = requirements.map { it.snapshot() })
    is Requirement.Any -> copy(alternatives = alternatives.map { it.snapshot() })
}
