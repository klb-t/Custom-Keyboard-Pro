package com.example.assistant

import com.example.core.assistant.*
import com.example.core.io.Verbs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GoalPhoneCatalogueTest {
    @Test fun phoneBindingsRemainCanonicalAndDistinguishSetupFromEffects() {
        val actions = GoalCatalogue.actions()
        assertEquals(Verbs.ALL.map { it.id }.toSet(), actions.keys)
        listOf("phone_tools", "phone_info", "sensors", "sensor_monitor", "special_access", "app_info", "app_settings", "rotation", "screen_timeout", "torch", "system_settings")
            .forEach { assertTrue("Missing audited binding: $it", actions.getValue(it).runnable) }
        assertTrue(actions.getValue("sensor_monitor").effects.contains("no capture"))
        assertTrue(actions.getValue("system_settings").effects.contains("grants no access"))
        assertFalse(actions.getValue("tap").runnable)
    }
    @Test fun schemasRejectGuessedOrInjectedIdentitiesAndUnknownSystemRoutes() {
        val a = GoalCatalogue.actions()
        assertTrue(a.getValue("app_info").invalidArguments(mapOf("package" to "com.android.chrome")).isEmpty())
        assertTrue(a.getValue("app_info").invalidArguments(mapOf("package" to "com.app; rm -rf /")).isNotEmpty())
        assertTrue(a.getValue("sensor_monitor").invalidArguments(mapOf("sensor" to "accelerometer")).isNotEmpty())
        assertTrue(a.getValue("sensor_monitor").invalidArguments(mapOf("sensor" to "s1.-2.0")).isEmpty())
        assertTrue(a.getValue("sensor_monitor").invalidArguments(mapOf("sensor" to "s1.0.0", "hz" to "2000")).isNotEmpty())
        assertTrue(a.getValue("system_settings").invalidArguments(mapOf("page" to "developer")).isEmpty())
        assertTrue(a.getValue("system_settings").invalidArguments(mapOf("page" to "android.settings.FAKE_GRANT")).isNotEmpty())
    }
    @Test fun rotationConstraintsAreAssessedBeforeApproval() {
        val action = GoalCatalogue.actions().getValue("rotation")
        val step = GoalStep("s1", "rotation", mapOf("state" to "auto", "orientation" to "landscape"))
        assertTrue(action.invalidArguments(step.arguments).isNotEmpty())
        val evidence = CapabilitySnapshot(0, mapOf("host.foreground" to CapabilityFact("host.foreground", Readiness.READY, "visible"),
            "write_system_settings" to CapabilityFact("write_system_settings", Readiness.READY, "granted")))
        assertFalse(GoalAssessment.step(step, mapOf(action.id to action), evidence).ready)
    }
    @Test fun diagnosticVariantsRequireOnlyTheirActualPrerequisites() {
        val actions = GoalCatalogue.actions()
        val evidence = CapabilitySnapshot(0, mapOf(
            "host.foreground" to CapabilityFact("host.foreground", Readiness.READY, "visible"),
            com.example.core.caps.Abilities.PHONE_DIAGNOSTICS to CapabilityFact(com.example.core.caps.Abilities.PHONE_DIAGNOSTICS, Readiness.READY, "local"),
            com.example.core.caps.Abilities.SENSOR_INVENTORY to CapabilityFact(com.example.core.caps.Abilities.SENSOR_INVENTORY, Readiness.READY, "local"),
            com.example.core.caps.Abilities.APP_USAGE to CapabilityFact(com.example.core.caps.Abilities.APP_USAGE, Readiness.SETUP, "not granted"),
            "sensor.selection" to CapabilityFact("sensor.selection", Readiness.SETUP, "select locally")
        ))
        assertTrue(GoalAssessment.step(GoalStep("s1", "phone_info", mapOf("section" to "device")), actions, evidence).ready)
        val usage = GoalAssessment.step(GoalStep("s1", "phone_info", mapOf("section" to "usage")), actions, evidence)
        assertFalse(usage.ready)
        assertTrue(usage.issues.any { it.id == com.example.core.caps.Abilities.APP_USAGE })
        val monitor = GoalAssessment.step(GoalStep("s1", "sensor_monitor", mapOf("sensor" to "s1.0.0")), actions, evidence)
        assertFalse(monitor.ready)
        assertTrue(monitor.issues.any { it.id == "sensor.selection" })
    }
}
