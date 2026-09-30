package com.example.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.core.discovery.ProviderCatalog
import com.example.ui.settings.AllSettingsScreen
import com.example.ui.settings.CapabilitySetupSection
import com.example.ui.settings.MediaHubScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real Compose reachability checks, with optional review captures (no golden files).
 * Record: testDebugUnitTest --tests '*SettingsSurfaceSmokeTest' -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSurfaceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepare() {
        compose.activity.getSharedPreferences("media_hub_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.replaceAll(Settings()).getOrThrow()
        ProviderCatalog.init(compose.activity)
    }

    @Test fun `empty media hub exposes both source routes and view controls`() {
        compose.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { MediaHubScreen() }
            }
        }
        compose.onNodeWithText("Add folder").assertIsDisplayed()
        compose.onNodeWithText("Add files").assertIsDisplayed()
        compose.onNodeWithText("No sources yet. Add a folder or select files to begin.").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/media_hub_empty.png")
        compose.onNodeWithText("View options").performScrollTo().performClick()
        compose.onNodeWithText("Show hidden names").assertIsDisplayed()
        compose.onNodeWithText("Show folders").assertIsDisplayed()
        compose.onNodeWithText("Show hidden names").performClick()
        compose.runOnIdle { assertTrue(SettingsStore.current.mediaShowHidden) }
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/media_hub_view_options.png")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Add folder").assertIsDisplayed()
    }

    @Test fun `pocket query renders independent controls and changes the selected policy`() {
        SettingsStore.update { SettingsHierarchy.selectLevel(it, SettingsLevel.EXPERT) }.getOrThrow()
        compose.setContent {
            val settings by SettingsStore.state.collectAsState()
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { AllSettingsScreen(settings, initialQuery = "pocket") }
            }
        }
        compose.onNodeWithText("Search every setting").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Block media keys"))
        compose.onNodeWithText("Block media keys").performClick()
        compose.runOnIdle {
            assertTrue(SettingsStore.current.pocketBlockMediaKeys)
            assertTrue(SettingsStore.current.pocketBlockTouch)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Search every setting"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/expert_pocket_query.png")
    }

    @Test fun `provider without an account exposes its setup steps without enabling sending`() {
        compose.setContent {
            val settings by SettingsStore.state.collectAsState()
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        CapabilitySetupSection(settings)
                    }
                }
            }
        }
        compose.onNodeWithText("Model or provider").assertIsDisplayed()
        compose.onNodeWithText("Or choose a provider directly").performScrollTo().performClick()
        compose.onNodeWithText("Google Gemini").performClick()
        compose.onNodeWithText("Set up Google Gemini").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("API key").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(SettingsStore.current.aiEnabled) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/provider_no_account.png")
    }
}
