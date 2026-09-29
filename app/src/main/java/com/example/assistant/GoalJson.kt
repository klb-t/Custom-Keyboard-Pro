package com.example.assistant

import com.example.core.assistant.*
import org.json.JSONArray
import org.json.JSONObject

/** An imported/model-supplied plan carries no permission, device selection or execution state. */
object GoalJson {
    private fun exact(obj: JSONObject, allowed: Set<String>) {
        require(obj.keys().asSequence().all { it in allowed }) { "Unknown plan field." }
    }
    private fun text(obj: JSONObject, key: String, max: Int, optional: Boolean = false): String {
        if (optional && !obj.has(key)) return ""
        val s = obj.get(key)
        require(s is String && s.length <= max) { "Invalid text field: $key" }
        return s
    }
    private fun array(obj: JSONObject, key: String, max: Int, optional: Boolean = false): JSONArray {
        if (optional && !obj.has(key)) return JSONArray()
        return (obj.get(key) as? JSONArray)?.also { require(it.length() <= max) } ?: error("Invalid array: $key")
    }
    private fun strings(a: JSONArray, maxLength: Int) = (0 until a.length()).map { i ->
        val v = a.get(i); require(v is String && v.length <= maxLength); v
    }
    fun read(raw: String, actualGoal: String): List<GoalPlan> {
        PlanJsonBudget.check(raw)
        val root = JSONObject(raw)
        exact(root, setOf("version", "alternatives"))
        val version = root.get("version")
        require(version is Int && version == 1) { "Unsupported plan version." }
        val alternatives = array(root, "alternatives", 4)
        require(alternatives.length() > 0)
        return (0 until alternatives.length()).map { i ->
            val p = alternatives.getJSONObject(i); exact(p, setOf("title", "steps"))
            val steps = array(p, "steps", 32)
            GoalPlan(actualGoal, text(p, "title", 200), (0 until steps.length()).map { j ->
                val s = steps.getJSONObject(j)
                exact(s, setOf("id", "action", "arguments", "after", "reason", "expected", "apiHints"))
                val args = s.getJSONObject("arguments"); require(args.length() <= 16)
                val map = args.keys().asSequence().associateWith { key -> text(args, key, 8000) }
                val after = strings(array(s, "after", 32), 80)
                require(after.distinct().size == after.size)
                GoalStep(text(s, "id", 80), text(s, "action", 80), map, after.toSet(),
                    text(s, "reason", 2000, true), text(s, "expected", 2000, true),
                    strings(array(s, "apiHints", 8, true), 1000))
            }).also { require(it.validate().isEmpty()) { it.validate().joinToString(" ") } }
        }
    }
    fun write(plans: List<GoalPlan>): String {
        require(plans.size in 1..4 && plans.all { it.validate().isEmpty() })
        val array = JSONArray()
        plans.forEach { p ->
            val steps = JSONArray()
            p.steps.forEach { s -> steps.put(JSONObject().put("id", s.id).put("action", s.action)
                .put("arguments", JSONObject(s.arguments)).put("after", JSONArray(s.after.sorted()))
                .put("reason", s.reason).put("expected", s.expected).put("apiHints", JSONArray(s.apiHints))) }
            array.put(JSONObject().put("title", p.title).put("steps", steps))
        }
        return JSONObject().put("version", 1).put("alternatives", array).toString(2).also { PlanJsonBudget.check(it) }
    }
}
