package com.example.assistant

import com.example.core.assistant.*
import com.example.core.caps.Abilities
import com.example.core.caps.Need
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in evaluator bridge. It makes no HTTP requests and needs no credential.
 * The fixtures are synthetic; no phone availability or execution is established.
 * IO_GOAL_EVAL_DIR activates export and validation during this focused test task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoalEvaluationBridgeTest {
    @Test fun exportProductionPromptAndValidateResponses() {
        val requested = System.getenv("IO_GOAL_EVAL_DIR") ?: return
        val directory = File(requested).canonicalFile
        require(directory.isDirectory) { "Create the evaluator directory first." }
        val actions = GoalCatalogue.actions()
        val abilities = Abilities.ALL.associateBy { it.id }
        val requirements = (abilities.keys + actions.values.flatMap { action ->
            allCapabilityIds(action.needs) + action.argumentNeeds.flatMap { allCapabilityIds(it.need) }
        } + setOf("host.foreground", "sensor.selection", "privileged.bridge")).toSet()
        val bundle = JSONObject().put("version", 1).put("origin", "production GoalPrompt + GoalCatalogue")
        val scenarios = JSONObject()
        val snapshots = mutableMapOf<String, CapabilitySnapshot>()
        listOf("ready", "missing_grants").forEach { name ->
            val facts = requirements.associateWith { id ->
                val ability = abilities[id]
                val state = when {
                    id == "host.foreground" -> Readiness.READY
                    id == "sensor.selection" -> Readiness.MISSING_ADAPTER
                    id == "privileged.bridge" -> Readiness.MISSING_ADAPTER
                    ability?.built == false -> Readiness.MISSING_ADAPTER
                    name == "ready" || ability?.needs == Need.Nothing -> Readiness.READY
                    ability?.needs is Need.Permission -> Readiness.PERMISSION
                    else -> Readiness.SETUP
                }
                CapabilityFact(id, state, when {
                    id == "sensor.selection" -> "No reviewed sensor-selection binding exists in this assistant host. Select/start manually in Phone tools; no sensor identifier is provided to the model."
                    state == Readiness.READY -> "Synthetic evaluator evidence; no actual device observation."
                    else -> "Synthetic evaluator scenario: prerequisite is ${state.name}."
                })
            }
            val snapshot = CapabilitySnapshot(0, facts)
            snapshots[name] = snapshot
            val prompt = GoalPrompt.system(actions.values, snapshot)
            scenarios.put(name, JSONObject().put("system", prompt).put("sha256", hash(prompt))
                .put("facts", JSONArray(facts.toSortedMap().values.map { fact ->
                    JSONObject().put("id", fact.id).put("state", fact.state.name).put("reason", fact.reason)
                })))
        }
        bundle.put("scenarios", scenarios)
        bundle.put("actions", JSONArray().apply {
            actions.toSortedMap().values.forEach { action ->
                put(JSONObject().put("id", action.id).put("runnable", action.runnable)
                    .put("needs", requirementJson(action.needs))
                    .put("conditionalNeeds", JSONArray(action.argumentNeeds.map { condition ->
                        JSONObject().put("name", condition.name).put("equals", condition.equals)
                            .put("need", requirementJson(condition.need))
                    }))
                    .put("constraints", JSONArray(action.constraints.map { constraint ->
                        when (constraint) {
                            is ArgumentConstraint.ExcludesWhen -> JSONObject().put("type", "excludesWhen")
                                .put("name", constraint.name).put("equals", constraint.equals).put("excluded", constraint.excluded)
                        }
                    }))
                    .put("arguments", JSONArray(action.arguments.map { argument ->
                        JSONObject().put("name", argument.name).put("required", argument.required)
                            .put("rule", ruleJson(argument.rule))
                    })))
            }
        })
        File(directory, "prompt-bundle.json").writeText(bundle.toString(2))

        val validations = JSONArray()
        File(directory, "responses").listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (!file.name.endsWith(".json")) return@forEach
            val input = JSONObject(file.readText())
            val result = JSONObject().put("id", input.getString("id"))
            try {
                val plans = GoalJson.read(input.getString("content"), input.getString("goal"))
                result.put("schemaAccepted", true)
                val errors = JSONArray()
                val missing = JSONArray()
                val assessment = JSONArray()
                val snapshot = snapshots[input.optString("scenario", "ready")]
                    ?: error("Unknown evaluator scenario.")
                plans.forEachIndexed { index, plan -> plan.steps.forEach { step ->
                    val action = actions[step.action]
                    when {
                        action != null -> action.invalidArguments(step.arguments).forEach { error ->
                            errors.put(JSONObject().put("alternative", index).put("step", step.id).put("error", error))
                        }
                        step.action.startsWith("missing.") -> missing.put(step.action)
                        else -> errors.put(JSONObject().put("alternative", index).put("step", step.id)
                            .put("error", "Unknown action must use missing.*"))
                    }
                    val feasibility = GoalAssessment.step(step, actions, snapshot)
                    assessment.put(JSONObject().put("alternative", index).put("step", step.id)
                        .put("action", step.action).put("ready", feasibility.ready)
                        .put("issues", JSONArray(feasibility.issues.map { fact ->
                            JSONObject().put("id", fact.id).put("state", fact.state.name)
                        })))
                } }
                result.put("argumentErrors", errors).put("missingActions", missing)
                    .put("assessment", assessment)
            } catch (failure: Exception) {
                result.put("schemaAccepted", false).put("schemaError", failure.javaClass.simpleName)
            }
            validations.put(result)
        }
        File(directory, "production-validation.json").writeText(JSONObject().put("version", 1)
            .put("validator", "production GoalJson.read, GoalAction.invalidArguments and GoalAssessment.step")
            .put("results", validations).toString(2))
    }

    private fun ruleJson(rule: ArgumentRule): JSONObject = when (rule) {
        is ArgumentRule.Choice -> JSONObject().put("type", "choice").put("values", JSONArray(rule.values.sorted()))
        is ArgumentRule.Number -> JSONObject().put("type", "number").put("min", rule.min).put("max", rule.max).put("integer", rule.integer)
        ArgumentRule.OpenTarget -> JSONObject().put("type", "openTarget")
        is ArgumentRule.Text -> JSONObject().put("type", "text").put("max", rule.max).put("allowBlank", rule.allowBlank)
        is ArgumentRule.Pattern -> JSONObject().put("type", "pattern").put("expression", rule.expression).put("max", rule.max)
    }

    private fun requirementJson(requirement: Requirement): JSONObject = when (requirement) {
        Requirement.None -> JSONObject().put("type", "none")
        is Requirement.Capability -> JSONObject().put("type", "capability").put("id", requirement.id)
        is Requirement.All -> JSONObject().put("type", "all").put("requirements", JSONArray(requirement.requirements.map(::requirementJson)))
        is Requirement.Any -> JSONObject().put("type", "any").put("alternatives", JSONArray(requirement.alternatives.map(::requirementJson)))
    }

    private fun allCapabilityIds(requirement: Requirement): List<String> = when (requirement) {
        Requirement.None -> emptyList()
        is Requirement.Capability -> listOf(requirement.id)
        is Requirement.All -> requirement.requirements.flatMap(::allCapabilityIds)
        is Requirement.Any -> requirement.alternatives.flatMap(::allCapabilityIds)
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
