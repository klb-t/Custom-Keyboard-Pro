package com.example.core.assistant

/** The user approves a concrete step, not arbitrary future model-generated actions. */
class GoalSession(plan: GoalPlan, private val actions: Map<String, GoalAction>,
                  private val evidenceTtlMillis: Long = 5000) {
    private val storedPlan = plan.detached().also { require(it.validate().isEmpty()) { it.validate().joinToString(" ") } }
    val plan: GoalPlan get() = storedPlan.detached()
    enum class State { PENDING, RUNNING, DISPATCHED_UNVERIFIED, VERIFIED, USER_CONFIRMED, FAILED, CANCELLED }
    data class Receipt(val state: State, val detail: String) {
        init { require(state in setOf(State.DISPATCHED_UNVERIFIED, State.VERIFIED, State.FAILED, State.CANCELLED)) }
    }
    data class Review internal constructor(val step: GoalStep, val feasible: Feasibility, val effects: String,
                                           val verification: String, internal val planHash: String,
                                           internal val evidenceHash: String, internal val epoch: Long,
                                           internal val reviewedAt: Long)
    data class Ticket internal constructor(val step: GoalStep, internal val epoch: Long)
    private val states = storedPlan.steps.associate { it.id to State.PENDING }.toMutableMap()
    private val notes = mutableMapOf<String, String>()
    private var epoch = 0L
    private var active: Ticket? = null
    var stopped = false
        private set

    init { require(evidenceTtlMillis in 1..60_000) }
    @Synchronized fun states(): Map<String, State> = states.toMap()
    @Synchronized fun notes(): Map<String, String> = notes.toMap()
    @Synchronized fun dependenciesSatisfied(id: String): Boolean = storedPlan.steps.first { it.id == id }.after.all {
        states[it] == State.VERIFIED || states[it] == State.USER_CONFIRMED
    }
    @Synchronized fun review(id: String, snapshot: CapabilitySnapshot, now: Long): Review {
        check(!stopped && active == null && states[id] == State.PENDING && dependenciesSatisfied(id))
        val step = storedPlan.steps.first { it.id == id }
        val stale = now < snapshot.observedAt || now - snapshot.observedAt > evidenceTtlMillis
        val assessment = if (stale) Feasibility(false, listOf(CapabilityFact("stale_evidence", Readiness.UNKNOWN,
            "Refresh device state before approval."))) else GoalAssessment.step(step, actions, snapshot)
        val action = actions[step.action]
        return Review(step.detached(), assessment, action?.effects ?: "Unknown external effects",
            action?.verification ?: "An adapter and verification method are missing", storedPlan.fingerprint(),
            snapshot.fingerprint(), epoch, now)
    }
    /** Must be called after the user's confirmation, against a newly sampled device state. */
    @Synchronized fun begin(review: Review, fresh: CapabilitySnapshot, now: Long): Ticket? {
        if (stopped || active != null || review.epoch != epoch || review.planHash != storedPlan.fingerprint() ||
            review.step != storedPlan.steps.firstOrNull { it.id == review.step.id } || !review.feasible.ready ||
            now < review.reviewedAt || now - review.reviewedAt > 120_000 ||
            now < fresh.observedAt || now - fresh.observedAt > evidenceTtlMillis ||
            fresh.fingerprint() != review.evidenceHash || states[review.step.id] != State.PENDING ||
            !dependenciesSatisfied(review.step.id) || !GoalAssessment.step(review.step, actions, fresh).ready) return null
        val ticket = Ticket(review.step.detached(), ++epoch)
        states[ticket.step.id] = State.RUNNING; active = ticket
        return ticket
    }
    @Synchronized fun complete(ticket: Ticket, receipt: Receipt): Boolean {
        if (stopped || active != ticket || ticket.epoch != epoch) return false
        states[ticket.step.id] = receipt.state; notes[ticket.step.id] = receipt.detail.take(2000)
        active = null; epoch++
        return true
    }
    /** Human confirmation is retained as such, never relabelled as platform verification. */
    @Synchronized fun confirmObserved(id: String): Boolean {
        if (stopped || active != null || states[id] != State.DISPATCHED_UNVERIFIED) return false
        states[id] = State.USER_CONFIRMED; notes[id] = "The user confirmed the observed effect."
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
}
