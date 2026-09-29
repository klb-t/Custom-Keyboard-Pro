package com.example.assistant

import android.content.Context
import android.os.SystemClock
import com.example.core.assistant.*
import com.example.core.caps.Abilities
import com.example.core.caps.Availability
import com.example.core.caps.Need
import com.example.core.io.Verbs

/** Projection of Verbs/Abilities, not another catalogue of what the whole product can do. */
object GoalCatalogue {
    private val choices = { name: String, values: Set<String> -> GoalArgument(name, ArgumentRule.Choice(values)) }
    private val supported = mapOf(
        "volume_set" to listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0)), choices("stream", setOf("music"))),
        "brightness" to listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0))),
        "media" to listOf(choices("action", setOf("play", "pause", "stop", "next", "previous"))),
        "vibrate" to listOf(GoalArgument("ms", ArgumentRule.Number(1.0, 1000.0, true))),
        "open" to listOf(GoalArgument("target", ArgumentRule.OpenTarget)),
        "search" to listOf(GoalArgument("query", ArgumentRule.Text(1000)))
    )
    fun actions(): Map<String, GoalAction> = Verbs.ALL.associate { v ->
        v.id to GoalAction(v.id, v.label, v.help,
            arguments = supported[v.id] ?: v.params.map { GoalArgument(it.name, ArgumentRule.Text(), false) },
            needs = Requirement.All(listOfNotNull(Requirement.Capability("host.foreground"),
                v.ability?.let { Requirement.Capability(it) })), runnable = v.id in supported,
            effects = when (v.id) {
                "volume_set" -> "Changes the media volume on this phone."
                "brightness" -> "Changes system brightness (may disable automatic brightness)."
                "open" -> "Opens the displayed target. Internal capture/light links open setup only."
                "media" -> "Sends a media command to the active player; it may start or interrupt audio."
                "vibrate" -> "Requests vibration on this phone."
                "search" -> "Opens a web search; the query leaves the phone through the browser."
                else -> "Requires an audited host adapter; no effect will be attempted by this host yet."
            }, verification = when (v.id) {
                "volume_set", "brightness" -> "Read back the setting; device quantization is taken into account."
                else -> "Dispatch only. The user must confirm the actual effect before dependent steps."
            }, api = "Canonical Verbs/Performer; see docs/GOAL_ASSISTANT_IMPLEMENTATION.md")
    }
    fun snapshot(context: Context, foreground: Boolean): CapabilitySnapshot {
        val facts = Abilities.ALL.associate { a ->
            val availability = runCatching { Abilities.availability(context, a) }.getOrNull()
            a.id to CapabilityFact(a.id, when (availability) {
                Availability.ON -> Readiness.READY
                Availability.OFF -> if (a.needs is Need.Permission) Readiness.PERMISSION else Readiness.SETUP
                Availability.ABSENT -> Readiness.MISSING_ADAPTER
                null -> Readiness.UNKNOWN
            }, when (availability) {
                Availability.ON -> a.gives
                Availability.ABSENT -> "Not implemented in this build. Fallback: ${a.without}"
                else -> "Access/setup not confirmed. Fallback: ${a.without}"
            }, setup = (a.needs as? Need.SpecialAccess)?.settingsAction)
        }.toMutableMap()
        facts["host.foreground"] = CapabilityFact("host.foreground", if (foreground) Readiness.READY else Readiness.SETUP,
            "Execution requires the visible, unlocked assistant activity.")
        return CapabilitySnapshot(SystemClock.elapsedRealtime(), facts)
    }
}
