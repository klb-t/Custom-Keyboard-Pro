package com.example.assistant

import android.content.Context
import android.os.SystemClock
import com.example.core.assistant.*
import com.example.core.caps.Abilities
import com.example.core.caps.Availability
import com.example.core.caps.Need
import com.example.core.io.Verbs
import com.example.core.phone.PhoneCatalogue
import android.content.pm.PackageManager

/** Projection of Verbs/Abilities, not another catalogue of what the whole product can do. */
object GoalCatalogue {
    private val choices = { name: String, values: Set<String> -> GoalArgument(name, ArgumentRule.Choice(values)) }
    private val supported = mapOf(
        "volume_set" to listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0)), choices("stream", setOf("music"))),
        "brightness" to listOf(GoalArgument("level", ArgumentRule.Number(0.0, 1.0))),
        "media" to listOf(choices("action", setOf("play", "pause", "stop", "next", "previous"))),
        "vibrate" to listOf(GoalArgument("ms", ArgumentRule.Number(1.0, 1000.0, true))),
        "open" to listOf(GoalArgument("target", ArgumentRule.OpenTarget)),
        "search" to listOf(GoalArgument("query", ArgumentRule.Text(1000))),
        "torch" to listOf(choices("state", setOf("on", "off", "toggle"))),
        "system_settings" to listOf(choices("page", PhoneCatalogue.routes.map { it.id }.toSet())),
        "phone_tools" to listOf(GoalArgument("tab", ArgumentRule.Choice(setOf("overview", "sensors", "apps", "access", "actions")), false)),
        "phone_info" to listOf(GoalArgument("section", ArgumentRule.Choice(setOf("device", "memory", "processes", "apps", "usage", "access")), false)),
        "sensors" to emptyList(),
        "sensor_monitor" to listOf(GoalArgument("sensor", ArgumentRule.Pattern("s[0-9]+\\.-?[0-9]+\\.[0-9]+", 64)),
            GoalArgument("hz", ArgumentRule.Number(1.0, 20.0, true), false),
            GoalArgument("seconds", ArgumentRule.Number(1.0, 300.0, true), false)),
        "special_access" to listOf(choices("target", PhoneCatalogue.accessIds)),
        "app_info" to listOf(GoalArgument("package", ArgumentRule.Pattern("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+", 255))),
        "app_settings" to listOf(GoalArgument("package", ArgumentRule.Pattern("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+", 255))),
        "rotation" to listOf(choices("state", setOf("auto", "locked")),
            GoalArgument("orientation", ArgumentRule.Choice(setOf("portrait", "landscape", "reverse_portrait", "reverse_landscape",
                "natural", "quarter_turn", "half_turn", "three_quarter_turn")), false)),
        "screen_timeout" to listOf(GoalArgument("seconds", ArgumentRule.Number(15.0, 1800.0, true)))
    )
    fun actions(): Map<String, GoalAction> = Verbs.ALL.associate { v ->
        v.id to GoalAction(v.id, v.label, v.help,
            arguments = supported[v.id] ?: v.params.map { GoalArgument(it.name, ArgumentRule.Text(), false) },
            needs = Requirement.All(listOfNotNull(Requirement.Capability("host.foreground"),
                v.ability?.let { Requirement.Capability(it) },
                if (v.id == "sensor_monitor") Requirement.Capability("sensor.selection") else null)), runnable = v.id in supported,
            effects = when (v.id) {
                "volume_set" -> "Changes the media volume on this phone."
                "brightness" -> "Changes system brightness (may disable automatic brightness)."
                "open" -> "Opens the displayed target. Internal capture/light links open setup only."
                "media" -> "Sends a media command to the active player; it may start or interrupt audio."
                "vibrate" -> "Requests vibration on this phone."
                "torch" -> "Requests the selected flashlight state; uses no camera images."
                "phone_info", "sensors", "app_info" -> "Reads only the selected local diagnostic data. It is shown here; it is not added to model context."
                "screen_timeout", "rotation" -> "Changes the displayed system setting. Android policy and foreground apps may restrict the effective behavior."
                "system_settings", "special_access", "app_settings", "phone_tools" -> "Opens the displayed setup/inspection screen; it grants no access and performs no further operation."
                "sensor_monitor" -> "Opens a bounded foreground sensor review. A separate Start press is required there; no capture is started by this plan."
                "search" -> "Opens a web search; the query leaves the phone through the browser."
                else -> "Requires an audited host adapter; no effect will be attempted by this host yet."
            }, verification = when (v.id) {
                "volume_set", "brightness", "rotation", "screen_timeout" -> "Read back the configured setting; this is not proof of physical display/timeout behavior."
                "phone_info", "sensors", "app_info" -> "An actual local snapshot is returned by the same invocation, with platform visibility limits."
                else -> "Dispatch only. The user must confirm the actual effect before dependent steps."
            }, api = if (v.id in PhoneCatalogue.verbs.map { it.id } || v.id in setOf("torch", "system_settings"))
                "Android typed phone adapter; see docs/PHONE_TOOLS.md and ANDROID_PHONE_CAPABILITIES.md"
            else "Canonical Verbs/Performer; see docs/GOAL_ASSISTANT_IMPLEMENTATION.md",
            constraints = if (v.id == "rotation") listOf(ArgumentConstraint.ExcludesWhen("state", "auto", "orientation")) else emptyList(),
            argumentNeeds = if (v.id == "phone_info") listOf(ArgumentRequirement("section", "usage", Requirement.Capability(Abilities.APP_USAGE))) else emptyList())
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
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            facts[Abilities.FLASHLIGHT] = CapabilityFact(Abilities.FLASHLIGHT, Readiness.UNSUPPORTED_DEVICE,
                "This device reports no camera flash. A granted camera permission cannot create one.")
        }
        facts["privileged.bridge"] = CapabilityFact("privileged.bridge", Readiness.MISSING_ADAPTER,
            "No authorized ADB/root/device-owner bridge is connected. Developer secure flags and other-app force-stop need a separate privileged route.",
            api = "https://developer.android.com/tools/adb", setup = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        facts["sensor.selection"] = CapabilityFact("sensor.selection", Readiness.MISSING_ADAPTER,
            "This goal host has no reviewed sensor-selection binding. Open Phone tools, select and start the sensor there. Model input includes no sensor identifier or measurements; do not invent one.")
        return CapabilitySnapshot(SystemClock.elapsedRealtime(), facts)
    }
}
