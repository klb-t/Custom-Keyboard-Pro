package com.example.core.matrix

/** How much each kind of price counts when ways are compared. */
data class Weights(
    val latency: Double = 1.0,
    val compute: Double = 0.5,
    val energy: Double = 0.5,
    val network: Double = 1.0,
    val money: Double = 2.0,
    /** Per piece of information thrown away. */
    val loss: Double = 1.0,
    /** Per piece of information made up by the method. */
    val synthesis: Double = 0.5,
    /** For a step that sends the content off the phone. */
    val leaving: Double = 6.0,
    val inference: Double = 4.0,
    val generation: Double = 10.0,
    /** Per step, so a shorter way wins a tie. */
    val step: Double = 1.0
)

/**
 * What is allowed and what is preferred. Explicit, so that the default choice is a
 * policy anyone can read and change rather than an arbitrary pick.
 */
data class Policy(
    /** Steps that send content away, through providers the user set up. */
    val allowLeavingDevice: Boolean = true,
    /** Steps that make up content. Off unless asked: generated is not converted. */
    val allowGenerative: Boolean = false,
    /** Transforms or implementations to go through, in this order. Asking for one also allows it. */
    val through: List<String> = emptyList(),
    /** Transforms or implementations never to use. */
    val avoid: Set<String> = emptySet(),
    val weights: Weights = Weights(),
    val maxSteps: Int = 5
)

/** One step of a way: what is done, and who does it. */
data class Choice(val transform: TransformSpec, val implementation: Implementation)

/** One complete way from what arrived to what was asked for, with its price. */
data class Route(val start: Interpretation, val steps: List<Choice>, val score: Double) {
    val leavesDevice: Boolean get() = steps.any { it.implementation.leavesDevice }

    /** "picture → time_frequency → notes". */
    fun describe(): String =
        (listOf(start.type.id) + steps.map { it.transform.to }).joinToString(" → ") +
            if (steps.isEmpty()) "" else "  (" + steps.joinToString(", ") { it.transform.id } + ")"
}

/** What the planner found: the ways, best first, and what stood in the way of the others. */
data class Plan(val routes: List<Route>, val blocked: List<String>) {
    val best: Route? get() = routes.firstOrNull()
}

/**
 * Finds the ways from what arrived to what was asked for, through the transforms
 * that can run now, and ranks them by [Policy]. Every way is kept — the best is only
 * the default, the others are there to be shown and chosen — because the right one
 * depends on what the user cares about, and that is theirs to change.
 */
object Planner {

    fun price(c: Choice, w: Weights): Double {
        val cost = c.implementation.cost
        fun lv(l: Level?) = (l ?: Level.MEDIUM).weight
        val t = c.transform
        return w.step +
            w.latency * lv(cost.latency) + w.compute * lv(cost.compute) + w.energy * lv(cost.energy) +
            w.network * lv(cost.network) + w.money * lv(cost.money) +
            w.loss * t.changes.count { it.kind == ChangeKind.DISCARDED } +
            w.synthesis * t.changes.count { it.kind == ChangeKind.SYNTHESIZED } +
            (if (c.implementation.leavesDevice) w.leaving else 0.0) +
            when (t.mapping) {
                Mapping.DETERMINISTIC -> 0.0
                Mapping.INFERENTIAL, Mapping.STOCHASTIC -> w.inference
                Mapping.GENERATIVE -> w.generation
            }
    }

    private fun named(name: String, c: Choice) = name == c.transform.id || name == c.implementation.id

    /**
     * Why [c] cannot be taken here, or null when it can. [facts] are what is known
     * about its input: the input's own for the first step, what the step before
     * produced for the rest — nothing is assumed about content that does not exist yet.
     */
    private fun obstacle(
        c: Choice,
        facts: Set<String>,
        asked: Boolean,
        policy: Policy,
        ready: (Implementation) -> Boolean,
        /** Asked for, or following from a step that was: enough to override [TransformSpec.pointlessFor], not to allow making things up. */
        chained: Boolean = asked
    ): String? {
        val t = c.transform
        if (policy.avoid.any { named(it, c) }) return "${t.label} is avoided"
        if (!facts.containsAll(t.needsFacts)) return "${t.label} needs ${(t.needsFacts - facts).joinToString()} in the input"
        if (!chained && facts.any { it in t.pointlessFor }) return "${t.label} makes no sense for ${(facts intersect t.pointlessFor).joinToString()}"
        if (t.mapping == Mapping.GENERATIVE && !policy.allowGenerative && !asked) {
            return "${t.label} makes things up — ask for it with use=${t.id}"
        }
        if (c.implementation.leavesDevice && !policy.allowLeavingDevice && !asked) return "${t.label} would send it off the phone"
        if (!ready(c.implementation)) return "${t.label} needs ${c.implementation.label}"
        return null
    }

    private fun search(
        starts: List<Interpretation>,
        goal: DataType,
        policy: Policy,
        transforms: List<TransformSpec>,
        implementations: List<Implementation>,
        can: (Choice, Set<String>, Boolean, Boolean) -> Boolean
    ): List<Route> {
        val routes = mutableListOf<Route>()
        starts.forEachIndexed { rank, start ->
            fun walk(at: String, facts: Set<String>, path: List<Choice>, seen: Set<String>) {
                if (at == goal.id && path.isNotEmpty()) {
                    // A less specific reading of the input is a fallback: it costs a little
                    // more, so the reading the content most likely means wins a tie.
                    routes += Route(start, path, path.sumOf { price(it, policy.weights) } + rank * 2.0)
                    return
                }
                if (path.size >= policy.maxSteps) return
                // Never back to a type already passed through — except the goal, which a
                // way may leave and come back to: a waterfall redrawn in another layout.
                transforms.filter { it.from == at && (it.to !in seen || it.to == goal.id) }.forEach { t ->
                    implementations.filter { it.transform == t.id }.forEach { impl ->
                        val c = Choice(t, impl)
                        val asked = policy.through.any { named(it, c) }
                        // Asking for a step also means what follows from it: reading a
                        // picture of text as a spectrogram is the point of asking for one.
                        val chained = asked || policy.through.any { name -> path.any { named(name, it) } }
                        if (can(c, facts, asked, chained)) walk(t.to, t.produces, path + c, seen + t.to)
                    }
                }
            }
            if (start.type.id == goal.id && policy.through.isEmpty()) routes += Route(start, emptyList(), rank * 2.0)
            walk(start.type.id, start.facts, emptyList(), setOf(start.type.id))
        }
        val through = policy.through
        val kept = if (through.isEmpty()) routes else routes.filter { r ->
            // The asked-for steps, in order, somewhere along the way.
            var i = 0
            r.steps.forEach { c -> if (i < through.size && named(through[i], c)) i++ }
            i == through.size
        }
        return kept.sortedBy { it.score }
    }

    /**
     * Every way from any of [starts] (most specific first) to [goal], ranked by
     * [policy]. [ready] says whether an implementation can run now — a provider set
     * up, a decoder present. When there is no way, [Plan.blocked] says what stands in
     * the way of the ways there would be.
     */
    fun plan(
        starts: List<Interpretation>,
        goal: DataType,
        ready: (Implementation) -> Boolean,
        policy: Policy = Policy(),
        transforms: List<TransformSpec> = Transforms.ALL,
        implementations: List<Implementation> = Transforms.IMPLEMENTATIONS
    ): Plan {
        val routes = search(starts, goal, policy, transforms, implementations) { c, facts, asked, chained ->
            obstacle(c, facts, asked, policy, ready, chained) == null
        }
        if (routes.isNotEmpty()) return Plan(routes, emptyList())
        // What would be possible with everything set up and allowed — facts about the
        // content still hold, since no setting changes what the content is.
        val open = policy.copy(allowGenerative = true, allowLeavingDevice = true, avoid = emptySet())
        val ideal = search(starts, goal, open, transforms, implementations) { c, facts, _, chained ->
            (chained || facts.none { it in c.transform.pointlessFor }) && facts.containsAll(c.transform.needsFacts)
        }
        val blocked = ideal.take(3).flatMap { r ->
            var facts = r.start.facts
            r.steps.mapIndexedNotNull { i, c ->
                val asked = policy.through.any { named(it, c) }
                val chained = asked || policy.through.any { name -> r.steps.take(i).any { named(name, it) } }
                obstacle(c, facts, asked, policy, ready, chained).also { facts = c.transform.produces }
            }
        }.distinct()
        return Plan(emptyList(), blocked)
    }
}
