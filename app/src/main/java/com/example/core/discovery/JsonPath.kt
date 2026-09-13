package com.example.core.discovery

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reaching into a reply without knowing its shape at compile time.
 *
 * Every provider puts the answer somewhere different — `text`, `choices[0].message.content`,
 * `results.channels[0].alternatives[0].transcript`, `predictions[0].bytesBase64Encoded` —
 * and the difference is the *only* thing that differs between reading one and reading
 * another. So the path is data and this reads it, which is what lets a provider nobody
 * has written code for be added from a settings screen.
 *
 * A missing step is null, never an exception: a provider that answered with an error
 * object instead of a result should produce "nothing was found there", so the caller
 * can show the error it actually got rather than a crash.
 */
object JsonPath {

    /** `a.b[0].c`, with `[n]` allowed on any step and a bare `[0]` allowed at the root. */
    fun get(root: Any?, path: String): Any? {
        if (path.isBlank()) return root
        var current: Any? = root
        val steps = steps(path) ?: return null
        steps.forEach { step ->
            current = when (step) {
                is Step.Name -> when (current) {
                    is JSONObject -> (current as JSONObject).opt(step.name)
                    else -> null
                }
                is Step.Index -> when (current) {
                    is JSONArray -> (current as JSONArray).opt(step.index)
                    else -> null
                }
            }
            if (current == JSONObject.NULL) current = null
            if (current == null) return null
        }
        return current
    }

    fun string(root: Any?, path: String): String? = when (val value = get(root, path)) {
        null -> null
        is String -> value
        is JSONArray, is JSONObject -> value.toString()
        else -> value.toString()
    }

    private sealed interface Step {
        data class Name(val name: String) : Step
        data class Index(val index: Int) : Step
    }

    /** Null when the path itself is malformed, so a typo reads as "not found". */
    private fun steps(path: String): List<Step>? {
        val out = mutableListOf<Step>()
        val name = StringBuilder()
        var i = 0
        fun flush() {
            if (name.isNotEmpty()) {
                out += Step.Name(name.toString())
                name.clear()
            }
        }
        while (i < path.length) {
            when (val c = path[i]) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    // An unterminated bracket used to stop the walk and hand back
                    // whatever it had reached, so `data[0` quietly returned the whole
                    // array. A path that does not parse is a path that found nothing.
                    val close = path.indexOf(']', i)
                    if (close < 0) return null
                    val index = path.substring(i + 1, close).trim().toIntOrNull() ?: return null
                    out += Step.Index(index)
                    i = close + 1
                }
                else -> { name.append(c); i++ }
            }
        }
        flush()
        return out
    }
}

/**
 * Filling `{{placeholders}}` in, which is the other half of describing a request.
 *
 * Two ways in, deliberately. [fill] does plain substitution, for a URL path or a
 * header — places where the value is a model id or a language tag and there is no
 * escaping to get wrong. [fillJson] walks a parsed body instead of its text, so a
 * prompt containing a quote, a newline or a backslash cannot break out of the string
 * it was put in. Substituting into JSON *text* would work until the first person
 * dictated a sentence with a quotation mark in it.
 */
object Templates {

    private val PLACEHOLDER = Regex("\\{\\{(\\w+)}}")

    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { values[it.groupValues[1]].orEmpty() }

    /** Every placeholder the template asks for, whether or not anything supplies it. */
    fun placeholdersIn(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    /**
     * Substitutes into a JSON body, keeping types.
     *
     * A string that is *exactly* one placeholder takes the type of what it resolves
     * to, so `"temperature": "{{temperature}}"` sends a number rather than the string
     * "0.7" — which several providers reject. A placeholder with text around it stays
     * a string, because that is the only thing it can be.
     */
    fun fillJson(template: String, values: Map<String, String>): JSONObject =
        substitute(JSONObject(template), values) as JSONObject

    private fun substitute(node: Any?, values: Map<String, String>): Any? = when (node) {
        is JSONObject -> JSONObject().also { out ->
            node.keys().forEach { key ->
                val filled = substitute(node.opt(key), values)
                // A placeholder nothing supplies drops the field rather than sending
                // an empty one: providers reject `"language": ""` more often than
                // they reject its absence.
                if (filled != null && filled != "") out.put(Templates.fill(key, values), filled)
            }
        }
        is JSONArray -> JSONArray().also { out ->
            (0 until node.length()).forEach { i ->
                substitute(node.opt(i), values)?.let { out.put(it) }
            }
        }
        is String -> substituteString(node, values)
        else -> node
    }

    private fun substituteString(raw: String, values: Map<String, String>): Any? {
        val whole = PLACEHOLDER.matchEntire(raw)
        if (whole != null) {
            val value = values[whole.groupValues[1]] ?: return null
            return typed(value)
        }
        return fill(raw, values)
    }

    /** "0.7" becomes a number, "true" a boolean, anything else stays text. */
    private fun typed(value: String): Any {
        value.toLongOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }
        return when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> value
        }
    }
}
