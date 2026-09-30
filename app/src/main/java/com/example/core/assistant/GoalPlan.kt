package com.example.core.assistant

import java.security.MessageDigest

/** Planning describes effects, not a second implementation of the platform's verbs. */
enum class Readiness { READY, PERMISSION, SETUP, MISSING_ADAPTER, UNSUPPORTED_DEVICE, UNKNOWN }

data class CapabilityFact(val id: String, val state: Readiness, val reason: String,
                          val api: String = "", val setup: String? = null)
data class CapabilitySnapshot(val observedAt: Long, val facts: Map<String, CapabilityFact>) {
    fun detached() = copy(facts = facts.toMap())
    fun fingerprint() = digest(buildString {
        fun field(s: String) { append(s.length).append(':').append(s) }
        facts.toSortedMap().forEach { (k, v) ->
            field(k); field(v.id); field(v.state.name); field(v.reason); field(v.api)
            append(if (v.setup == null) '0' else '1'); v.setup?.let { field(it) }
        }
    })
}

/** References the canonical ability/provider inventories; AND and OR remain explicit. */
sealed interface Requirement {
    data object None : Requirement
    data class Capability(val id: String) : Requirement
    data class All(val requirements: List<Requirement>) : Requirement
    data class Any(val alternatives: List<Requirement>) : Requirement
}

data class Feasibility(val ready: Boolean, val issues: List<CapabilityFact>,
                       val alternatives: List<Feasibility> = emptyList(),
                       val requirements: List<Feasibility> = emptyList())
object FeasibilityCheck {
    fun inspect(need: Requirement, snapshot: CapabilitySnapshot): Feasibility = when (need) {
        Requirement.None -> Feasibility(true, emptyList())
        is Requirement.Capability -> {
            val fact = snapshot.facts[need.id] ?: CapabilityFact(need.id, Readiness.UNKNOWN,
                "No current evidence for this prerequisite; this is not proof of impossibility.")
            Feasibility(fact.state == Readiness.READY, if (fact.state == Readiness.READY) emptyList() else listOf(fact))
        }
        is Requirement.All -> {
            val parts = need.requirements.map { inspect(it, snapshot) }
            Feasibility(parts.all { it.ready }, parts.flatMap { it.issues }.distinctBy { it.id }, requirements = parts)
        }
        is Requirement.Any -> {
            val parts = need.alternatives.map { inspect(it, snapshot) }
            if (parts.any { it.ready }) Feasibility(true, emptyList(), parts)
            else Feasibility(false, parts.flatMap { it.issues }.distinctBy { it.id }.ifEmpty {
                listOf(CapabilityFact("empty_alternatives", Readiness.UNKNOWN, "No alternative has been specified."))
            }, parts)
        }
    }
}

sealed interface ArgumentRule {
    fun accepts(value: String): Boolean
    data class Choice(val values: Set<String>) : ArgumentRule {
        override fun accepts(value: String) = value in values
    }
    data class Number(val min: Double, val max: Double, val integer: Boolean = false) : ArgumentRule {
        init { require(min.isFinite() && max.isFinite() && min <= max) }
        override fun accepts(value: String): Boolean {
            val n = value.toDoubleOrNull() ?: return false
            return n.isFinite() && n in min..max && (!integer || value.toIntOrNull() != null)
        }
    }
    data object OpenTarget : ArgumentRule {
        override fun accepts(value: String): Boolean {
            if (value.length > 2000 || value.any { it <= ' ' }) return false
            if (Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)) return true
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
            if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
            return when (uri.scheme?.lowercase()) {
                "https" -> uri.port == -1 || uri.port in 1..65535
                "iomatrix" -> uri.host in setOf("capture", "colour-organ") &&
                    uri.port == -1 && uri.path.orEmpty().isEmpty() && uri.query == null && uri.fragment == null
                else -> false
            }
        }
    }
    data class Text(val max: Int = 2000, val allowBlank: Boolean = false) : ArgumentRule {
        override fun accepts(value: String) = value.length <= max && (allowBlank || value.isNotBlank()) &&
            value.none { it == '\u0000' || (it < ' ' && it != '\n' && it != '\t') }
    }
    /** A host-owned lexical domain; expressions are never imported from model output. */
    data class Pattern(val expression: String, val max: Int) : ArgumentRule {
        private val regex = Regex(expression)
        init { require(max in 1..2000 && expression.length <= 500) }
        override fun accepts(value: String) = value.length <= max && regex.matches(value)
    }
}

data class GoalArgument(val name: String, val rule: ArgumentRule, val required: Boolean = true)
/** A host-authored prerequisite that applies only to the selected variant of an operation. */
data class ArgumentRequirement(val name: String, val equals: String, val need: Requirement) {
    fun applies(values: Map<String, String>) = values[name] == equals
}
sealed interface ArgumentConstraint {
    fun issue(values: Map<String, String>): String?
    data class ExcludesWhen(val name: String, val equals: String, val excluded: String) : ArgumentConstraint {
        override fun issue(values: Map<String, String>): String? =
            if (values[name] == equals && excluded in values) "$excluded cannot be combined with $name=$equals" else null
    }
}
data class GoalAction(
    val id: String, val label: String, val help: String,
    val arguments: List<GoalArgument> = emptyList(),
    val needs: Requirement = Requirement.None,
    /** A catalogue entry is not evidence of a runnable host adapter. */
    val runnable: Boolean = false,
    val effects: String,
    val verification: String,
    val api: String = "",
    val constraints: List<ArgumentConstraint> = emptyList(),
    val argumentNeeds: List<ArgumentRequirement> = emptyList()
) {
    fun invalidArguments(values: Map<String, String>): List<String> {
        val known = arguments.associateBy { it.name }
        return values.keys.filter { it !in known }.map { "Unknown argument: $it" } +
            arguments.mapNotNull { arg ->
                val v = values[arg.name]
                when { v == null && arg.required -> "Missing argument: ${arg.name}"
                    v != null && !arg.rule.accepts(v) -> "Invalid value for ${arg.name}"
                    else -> null }
            } + constraints.mapNotNull { it.issue(values) }
    }
}

data class GoalStep(val id: String, val action: String, val arguments: Map<String, String> = emptyMap(),
                    val after: Set<String> = emptySet(), val reason: String = "", val expected: String = "",
                    /** Model-suggested references, never executable endpoints or verified evidence. */
                    val apiHints: List<String> = emptyList()) {
    fun detached() = copy(arguments = arguments.toSortedMap(), after = after.toSortedSet(), apiHints = apiHints.toList())
}
data class GoalPlan(val goal: String, val title: String, val steps: List<GoalStep>) {
    fun detached() = copy(steps = steps.map { it.detached() })
    fun fingerprint(): String = digest(buildString {
        fun field(s: String) { append(s.length).append(':').append(s) }
        field(goal); field(title)
        steps.forEach { s ->
            field(s.id); field(s.action); field(s.reason); field(s.expected)
            append(s.arguments.size).append(';'); s.arguments.toSortedMap().forEach { (k,v) -> field(k); field(v) }
            append(s.after.size).append(';'); s.after.sorted().forEach { field(it) }
            append(s.apiHints.size).append(';'); s.apiHints.forEach { field(it) }
        }
    })
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        if (goal.isBlank() || goal.length > 8000) issues += "Goal must have 1–8000 characters."
        if (title.isBlank() || title.length > 200) issues += "Plan title must have 1–200 characters."
        if (steps.isEmpty() || steps.size > 32) issues += "Plan must have 1–32 steps."
        val ids = steps.map { it.id }.toSet()
        if (ids.size != steps.size) issues += "Duplicate step identifiers."
        val identifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")
        steps.forEach { s ->
            if (!identifier.matches(s.id) || !identifier.matches(s.action)) issues += "Invalid step or action identifier."
            if (s.after.size > 32 || s.id in s.after || !ids.containsAll(s.after)) issues += "Invalid dependencies for ${s.id}."
            if (s.arguments.size > 16 || s.arguments.any { !identifier.matches(it.key) || it.value.length > 8000 }) issues += "Argument limit for ${s.id}."
            if (s.reason.length > 2000 || s.expected.length > 2000 || s.apiHints.size > 8 || s.apiHints.any { it.length > 1000 }) issues += "Description limit for ${s.id}."
        }
        if (steps.size <= 32 && ids.size == steps.size) {
            val done = mutableSetOf<String>()
            repeat(steps.size) { steps.filter { done.containsAll(it.after) }.forEach { done += it.id } }
            if (done.size != steps.size) issues += "Cyclic or unresolved dependencies."
        }
        return issues.distinct()
    }
}

/** Assessment never accepts a model's claim that it has granted a permission or built an adapter. */
object GoalAssessment {
    fun step(step: GoalStep, actions: Map<String, GoalAction>, snapshot: CapabilitySnapshot): Feasibility {
        val action = actions[step.action] ?: return Feasibility(false, listOf(
            CapabilityFact(step.action, Readiness.MISSING_ADAPTER, "Unknown operation. Find or implement an adapter; retain the goal.")))
        val args = action.invalidArguments(step.arguments)
        val requirements = listOf(action.needs) + action.argumentNeeds.filter { it.applies(step.arguments) }.map { it.need }
        val need = FeasibilityCheck.inspect(Requirement.All(requirements), snapshot)
        val issues = need.issues + args.map { CapabilityFact("argument", Readiness.SETUP, it) } +
            if (action.runnable) emptyList() else listOf(CapabilityFact(action.id, Readiness.MISSING_ADAPTER,
                "Listed in the canonical catalogue, but not executable by this assistant host yet.", action.api))
        return Feasibility(issues.isEmpty(), issues, need.alternatives, need.requirements)
    }
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
