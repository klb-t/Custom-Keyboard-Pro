package com.example.core

import com.example.core.ai.*
import com.example.core.config.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiTaskProfilesTest {
    @Test fun `legacy writing stays small while planning has its own default`() {
        val s = Settings(aiMaxTokens = 72)
        assertEquals(72, AiConfig.from(s).maxTokens)
        assertEquals(4096, AiConfig.from(s, task = AiRequestTask.GOAL_PLAN).maxTokens)
        assertEquals(24, AiConfig.from(s, maxTokens = 24, task = AiRequestTask.GOAL_PLAN).maxTokens)
    }

    @Test fun `task overrides win over provider parameters and probe limit wins over task`() {
        val s = Settings(aiTaskProfilesJson = """{"goal_plan":{"maxTokens":3072,"temperature":0.2}}""")
        val params = mapOf("maxTokens" to "800", "temperature" to "0.7")
        val goal = AiTaskProfiles.resolve(s, AiRequestTask.GOAL_PLAN, params)
        assertEquals(3072, goal.maxTokens)
        assertEquals(0.2f, goal.temperature, 0.00001f)
        assertEquals("Task profile", goal.tokenSource)
        assertEquals(24, AiTaskProfiles.resolve(s, AiRequestTask.GOAL_PLAN, params, 24).maxTokens)
        assertEquals(800, AiTaskProfiles.resolve(s, AiRequestTask.WRITING, params).maxTokens)
    }

    @Test fun `task presets preserve existing limits even with a configured writing provider`() {
        val oldLimits = mapOf(AiRequestTask.GOAL_PLAN to 4096, AiRequestTask.REWRITE to 800,
            AiRequestTask.THEME to 900, AiRequestTask.OCR to 4096, AiRequestTask.PANEL to 1200,
            AiRequestTask.INLINE to 24, AiRequestTask.VOICE_COMMAND to 200)
        oldLimits.forEach { (task, expected) ->
            assertEquals(expected, AiTaskProfiles.resolve(Settings(), task, mapOf("maxTokens" to "1024")).maxTokens)
            val changed = Settings(aiTaskProfilesJson = AiTaskProfiles.write(mapOf(task to AiTaskParameters(maxTokens = 64))))
            assertEquals(64, AiTaskProfiles.resolve(changed, task, emptyMap()).maxTokens)
        }
    }

    @Test fun `removing a field restores inheritance without changing the other task`() {
        val raw = """{"writing":{"maxTokens":96},"goal_plan":{"maxTokens":2048,"temperature":0.4}}"""
        val changed = AiTaskProfiles.update(raw, AiRequestTask.GOAL_PLAN, AiTaskParameters(temperature = 0.4f))
        val s = Settings(aiTaskProfilesJson = changed)
        assertEquals(96, AiConfig.from(s).maxTokens)
        assertEquals(4096, AiConfig.from(s, task = AiRequestTask.GOAL_PLAN).maxTokens)
        assertEquals(0.4f, AiConfig.from(s, task = AiRequestTask.GOAL_PLAN).temperature, 0.00001f)
        assertEquals(changed, SettingsStore.fromJson(SettingsStore.toJson(s)).aiTaskProfilesJson)
    }

    @Test fun `invalid imported profiles fail closed and schema rejects them`() {
        val invalid = listOf(
            """{"goal_plan":{"maxTokens":999999}}""",
            """{"goal_plan":{"temperature":-1}}""",
            """{"goal_plan":{"maxTokens":"4096"}}""",
            """{"goal_plan":{"maxTokens":24,"maxTokens":4096}}""",
            """{"goal_plan":{"messages":[]}}""",
            """{"unknown":{}}""", "[]", "null",
            """{"goal_plan":{"temperature":null}}"""
        )
        invalid.forEach { raw ->
            assertTrue(raw, runCatching { AiTaskProfiles.parse(raw) }.isFailure)
            assertTrue(raw, SettingsSchema.validatedValue("aiTaskProfilesJson", raw).isFailure)
            val config = AiConfig.from(Settings(aiTaskProfilesJson = raw))
            assertNotNull(config.configurationProblem)
            assertFalse(config.isUsable)
        }
    }

    @Test fun `profile is expert application data without key or layout overrides`() {
        val spec = SettingsSchema.spec("aiTaskProfilesJson")!!
        assertEquals(SettingsOwner.APPLICATION, spec.owner)
        assertEquals(SettingsLevel.EXPERT, spec.minimumLevel)
        assertEquals(setOf(SettingsScope.KEYBOARD_DEFAULTS), spec.applicableScopes)
        assertFalse(spec.secret)
        assertTrue(SettingsSchema.validatedValue(spec.key, "{}").isSuccess)
    }
}
