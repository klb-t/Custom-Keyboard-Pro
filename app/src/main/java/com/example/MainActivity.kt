package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutRepository
import com.example.core.config.SettingsSchema
import com.example.core.discovery.ModelDiscovery
import com.example.core.discovery.AiCapability
import com.example.core.data.WordLists
import com.example.core.discovery.ProviderCatalog
import com.example.core.panels.PanelGenerator
import com.example.ui.settings.AboutScreen
import com.example.ui.settings.AiSettingsScreen
import com.example.ui.settings.AllSettingsScreen
import com.example.ui.settings.AppearanceScreen
import com.example.ui.settings.DiagnosticsScreen
import com.example.ui.settings.DictionaryScreen
import com.example.ui.settings.GeneratedPanelScreen
import com.example.ui.settings.RequestPanelScreen
import com.example.ui.settings.SetupWizardScreen
import com.example.ui.settings.ThemeEditorScreen
import com.example.ui.kb.ThemeStore
import com.example.ui.settings.HomeScreen
import com.example.ui.settings.LayoutStudioScreen
import com.example.ui.settings.TypingSettingsScreen
import com.example.ui.settings.VoiceSettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppLogger

/**
 * The settings app.
 *
 * It is the keyboard's only configuration surface, and it is also where the keyboard
 * sends the user when it needs something it cannot do itself — granting the microphone
 * permission, or editing a layout.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_HOME = "home"
        const val ROUTE_APPEARANCE = "appearance"
        const val ROUTE_TYPING = "typing"
        const val ROUTE_LAYOUTS = "layouts"
        const val ROUTE_AI = "ai"
        const val ROUTE_VOICE = "voice"
        const val ROUTE_DICTIONARY = "dictionary"
        const val ROUTE_ABOUT = "about"
        const val ROUTE_DIAGNOSTICS = "diagnostics"
        const val ROUTE_PERMISSIONS = "permissions"
        const val ROUTE_ALL_SETTINGS = "all"
        const val ROUTE_REQUEST_PANEL = "request"
        const val ROUTE_THEME_EDITOR = "theme"
        const val ROUTE_SETUP = "setup"
        /** A panel the user asked for: "panel:<id>". */
        const val ROUTE_PANEL_PREFIX = "panel:"
    }

    private var micPermissionGranted by mutableStateOf(false)

    /**
     * Registered as a field, which is what makes it work at all: the result APIs
     * refuse a registration made after the activity has started, and a field
     * initialiser runs before `onCreate`. It also means a failure here happens before
     * anything this class could log from — which is why the crash log is installed by
     * the Application instead.
     */
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLogger.d("Settings.mic", "result: granted=$granted")
        micPermissionGranted = granted
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d("Settings", "onCreate start (route=${intent?.getStringExtra(EXTRA_ROUTE)})")
        AppLogger.init(applicationContext)
        SettingsStore.init(this)
        LayoutRepository.init(this)
        ProviderCatalog.init(this)
        WordLists.init(this)
        registerDynamicOptions()
        enableEdgeToEdge()
        AppLogger.d("Settings", "onCreate stores ready")

        setContent {
            val settings by SettingsStore.state.collectAsState()
            // A first launch opens the wizard; every later one opens the home
            // screen. The wizard marks itself done even when skipped, so it never
            // greets the same person twice.
            var route by remember {
                mutableStateOf(
                    intent?.getStringExtra(EXTRA_ROUTE)
                        ?: if (SettingsStore.current.setupDone) ROUTE_HOME else ROUTE_SETUP
                )
            }

            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(titleFor(route)) },
                            navigationIcon = {
                                if (route != ROUTE_HOME) {
                                    IconButton(onClick = { route = ROUTE_HOME }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        )
                    }
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        when (route) {
                            ROUTE_APPEARANCE -> AppearanceScreen(settings)
                            ROUTE_TYPING -> TypingSettingsScreen(settings)
                            ROUTE_LAYOUTS -> LayoutStudioScreen(settings)
                            ROUTE_AI -> AiSettingsScreen(settings)
                            ROUTE_VOICE, ROUTE_PERMISSIONS -> VoiceSettingsScreen(
                                settings = settings,
                                micGranted = micPermissionGranted,
                                onRequestMic = {
                                    AppLogger.d("Settings.mic", "requesting RECORD_AUDIO")
                                    runCatching { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
                                        .onFailure {
                                            // Launching can throw when the activity is
                                            // in a state the result API refuses; that
                                            // must not take the whole app down with it.
                                            AppLogger.e("Settings.mic", "could not launch the request", it)
                                        }
                                }
                            )
                            ROUTE_DICTIONARY -> DictionaryScreen()
                            ROUTE_ABOUT -> AboutScreen()
                            ROUTE_DIAGNOSTICS -> DiagnosticsScreen()
                            ROUTE_ALL_SETTINGS -> AllSettingsScreen(settings)
                            ROUTE_REQUEST_PANEL -> RequestPanelScreen(settings)
                            ROUTE_THEME_EDITOR -> ThemeEditorScreen(settings)
                            ROUTE_SETUP -> SetupWizardScreen(
                                settings = settings,
                                onDone = { route = ROUTE_HOME },
                                onNavigate = { route = it }
                            )
                            else -> {
                                // A generated panel's route carries its id, so routes do
                                // not have to be known at compile time — which is the
                                // whole point of a panel that did not exist then either.
                                val panel = if (route.startsWith(ROUTE_PANEL_PREFIX)) {
                                    PanelGenerator.saved(settings)
                                        .firstOrNull { it.id == route.removePrefix(ROUTE_PANEL_PREFIX) }
                                } else null

                                if (panel != null) {
                                    GeneratedPanelScreen(
                                        panel = panel,
                                        settings = settings,
                                        onDelete = {
                                            PanelGenerator.delete(panel.id)
                                            route = ROUTE_HOME
                                        }
                                    )
                                } else {
                                    HomeScreen(
                                        settings = settings,
                                        onNavigate = { route = it },
                                        onEnableKeyboard = { openImeSettings() },
                                        onChooseKeyboard = { showImePicker() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.d("Settings", "onNewIntent (route=${intent.getStringExtra(EXTRA_ROUTE)})")
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLogger.d("Settings", "onResume")
        micPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // A layout may have been added or edited by another part of the app.
        LayoutRepository.reload()
    }

    /**
     * Teaches the schema the option lists only the running app knows.
     *
     * The schema is derived from the settings codec, which can say "themeId is a
     * string" but not which themes exist right now — that depends on the built-in
     * list plus whatever the user has made. Registering the lookups here keeps the
     * core free of any dependency on the UI layer while still letting a generated
     * panel offer a real list of choices rather than a free-text box.
     */
    private fun registerDynamicOptions() {
        SettingsSchema.dynamicOptions["themeId"] = { ThemeStore.allThemes().map { it.id } }
        SettingsSchema.dynamicOptions["activeLayoutId"] = { LayoutRepository.all().map { it.id } }
        SettingsSchema.dynamicOptions["fieldlessLayoutId"] = { listOf("") + LayoutRepository.all().map { it.id } }
        SettingsSchema.dynamicOptions["aiModel"] = {
            ModelDiscovery.cachedModels(SettingsStore.current)
        }
        SettingsSchema.dynamicOptions["aiProvider"] = { ProviderCatalog.allIds(SettingsStore.current) }
        // Asked of the catalogue rather than listed here, so a provider added as data
        // — by the user, or by a model writing one — appears in this dropdown without
        // anything in the app knowing it exists.
        SettingsSchema.dynamicOptions["asrProvider"] = {
            ProviderCatalog.serving(AiCapability.TRANSCRIBE, SettingsStore.current).map { it.id }
        }
        // The same question, asked about continuing text rather than transcribing it.
        // A blank entry first because blank means "use the AI provider", and a
        // dropdown with no way back to the default is a dropdown that traps you.
        SettingsSchema.dynamicOptions["ocrProvider"] = {
            listOf("") + ProviderCatalog.serving(AiCapability.OCR, SettingsStore.current).map { it.id }
        }
        SettingsSchema.dynamicOptions["completionProvider"] = {
            listOf("") + ProviderCatalog.serving(AiCapability.COMPLETE, SettingsStore.current).map { it.id }
        }
        SettingsSchema.dynamicOptions["completionModel"] = {
            ModelDiscovery.cachedModels(SettingsStore.current)
        }
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            AppLogger.e("Settings", "Could not open input method settings", e)
        }
    }

    private fun showImePicker() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        } catch (e: Exception) {
            AppLogger.e("Settings", "Could not open the input method picker", e)
        }
    }
}

@Composable
private fun titleFor(route: String): String = when (route) {
    MainActivity.ROUTE_APPEARANCE -> "Size, shape & theme"
    MainActivity.ROUTE_TYPING -> "Typing"
    MainActivity.ROUTE_LAYOUTS -> "Layouts"
    MainActivity.ROUTE_AI -> "AI"
    MainActivity.ROUTE_VOICE, MainActivity.ROUTE_PERMISSIONS -> "Dictation"
    MainActivity.ROUTE_DICTIONARY -> "Dictionary & shortcuts"
    MainActivity.ROUTE_ABOUT -> "About & help"
    MainActivity.ROUTE_DIAGNOSTICS -> "Diagnostics"
    MainActivity.ROUTE_ALL_SETTINGS -> "Every setting"
    MainActivity.ROUTE_REQUEST_PANEL -> "Ask for a panel"
    MainActivity.ROUTE_THEME_EDITOR -> "Theme editor"
    MainActivity.ROUTE_SETUP -> "Setting up"
    else -> if (route.startsWith(MainActivity.ROUTE_PANEL_PREFIX)) "Your panel" else "Custom Keyboard Pro"
}
