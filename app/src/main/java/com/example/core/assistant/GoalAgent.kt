package com.example.core.assistant

/**
 * Source-independent local goal-agent lifecycle. Speech and text are inputs to the same
 * planner/review/session contract; neither a transcript nor a planner response authorizes an effect.
 * The host supplies planning, current capability evidence and the existing typed performer.
 * This controller is not an external agent transport or an arbitrary-code runtime.
 */
class GoalAgent {
    enum class InputSource { TEXT, VOICE }
    enum class Phase { INPUT, PLANNING, PROPOSED, REVIEWING, EXECUTING, AWAITING_OBSERVATION, COMPLETE, STOPPED }

    class PlanningTurn internal constructor(val goal: String, val source: InputSource,
                                           internal val revision: Long, internal val owner: Any)
    private val identity = Any()
    private var activeTurn: PlanningTurn? = null
    private var storedProposals = emptyList<GoalPlan>()
    private var cancelled = false
    @Volatile var revision: Long = 0
        private set
    @Volatile var goal: String = ""
        private set
    @Volatile var inputSource: InputSource = InputSource.TEXT
        private set
    @Volatile var session: GoalSession? = null
        private set
    val proposals: List<GoalPlan> @Synchronized get() = storedProposals.map { it.detached() }
    val phase: Phase @Synchronized get() {
        if (activeTurn != null) return Phase.PLANNING
        val current = session
        if (current != null) {
            if (current.stopped) return Phase.STOPPED
            if (current.allEffectsConfirmed()) return Phase.COMPLETE
            val states = current.states().values
            if (GoalSession.State.RUNNING in states) return Phase.EXECUTING
            if (GoalSession.State.DISPATCHED_UNVERIFIED in states) return Phase.AWAITING_OBSERVATION
            return Phase.REVIEWING
        }
        if (cancelled) return Phase.STOPPED
        return if (storedProposals.isEmpty()) Phase.INPUT else Phase.PROPOSED
    }

    /** An edit, including a new speech transcript, invalidates all prior proposals and approvals. */
    @Synchronized fun editGoal(text: String, source: InputSource = InputSource.TEXT) {
        require(text.length <= 8000) { "Goal exceeds the input limit." }
        clear()
        goal = text.trim()
        inputSource = source
    }

    @Synchronized fun clear() {
        revision++; activeTurn = null; cancelled = false
        session?.stop(); session = null; storedProposals = emptyList()
    }

    /** The host calls this only after the user approves the concrete planner request. */
    @Synchronized fun beginPlanning(): PlanningTurn {
        require(goal.isNotBlank()) { "Describe the goal first." }
        clear()
        return PlanningTurn(goal, inputSource, revision, identity).also { activeTurn = it }
    }

    @Synchronized fun isCurrent(turn: PlanningTurn): Boolean = turn.owner === identity && activeTurn === turn &&
        turn.revision == revision && turn.goal == goal

    /** Late, cancelled and other-host responses can never replace the current goal or route. */
    @Synchronized fun finishPlanning(turn: PlanningTurn, plans: List<GoalPlan>): Boolean {
        if (!isCurrent(turn)) return false
        val detached = checkedPlans(plans)
        activeTurn = null; storedProposals = detached
        return true
    }

    @Synchronized fun failPlanning(turn: PlanningTurn): Boolean {
        if (!isCurrent(turn)) return false
        activeTurn = null
        return true
    }

    /** Imported/offline proposals are still definitions, never restored execution authority. */
    @Synchronized fun offerPlans(plans: List<GoalPlan>) {
        val detached = checkedPlans(plans)
        clear(); storedProposals = detached
    }

    @Synchronized fun selectPlan(index: Int, actions: Map<String, GoalAction>): GoalSession {
        val plan = storedProposals[index]
        val selected = GoalSession(plan, actions)
        revision++; activeTurn = null; cancelled = false; session?.stop()
        session = selected
        return selected
    }

    @Synchronized fun review(id: String, evidence: CapabilitySnapshot, now: Long): GoalSession.Review =
        checkNotNull(session).review(id, evidence, now)
    @Synchronized fun begin(review: GoalSession.Review, evidence: CapabilitySnapshot, now: Long): GoalSession.Ticket? =
        session?.begin(review, evidence, now)
    @Synchronized fun complete(ticket: GoalSession.Ticket, receipt: GoalSession.Receipt): Boolean =
        session?.complete(ticket, receipt) ?: false
    @Synchronized fun confirmObserved(id: String): Boolean = session?.confirmObserved(id) ?: false

    /** Pause revokes planner work and step approvals, retaining honest outcome history for review. */
    @Synchronized fun pause() { revision++; activeTurn = null; session?.pause() }

    /** Cancel is not rollback. A possibly dispatched effect remains explicitly unverified. */
    @Synchronized fun cancel() { revision++; activeTurn = null; cancelled = true; session?.stop() }

    private fun checkedPlans(plans: List<GoalPlan>): List<GoalPlan> {
        require(goal.isNotBlank() && plans.size in 1..4) { "Expected 1–4 proposals for the entered goal." }
        return plans.map { it.detached().also { plan ->
            require(plan.goal == goal) { "A proposal belongs to a different goal." }
            require(plan.validate().isEmpty()) { plan.validate().joinToString(" ") }
        } }
    }
}
