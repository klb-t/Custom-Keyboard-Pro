package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.example.core.config.SettingsStore
import com.example.core.config.SettingsHierarchy
import com.example.core.config.SettingsLevel
import com.example.ui.settings.BasicKeyboardSettingsScreen
import com.example.ui.settings.RuntimeDebuggerScreen
import com.example.core.layout.LayoutRepository
import com.example.core.config.SettingsSchema
import com.example.core.discovery.ModelDiscovery
import com.example.core.discovery.AiCapability
import com.example.core.data.WordLists
import com.example.core.discovery.ProviderCatalog
import com.example.core.panels.PanelGenerator
import com.example.ui.settings.CapabilitiesScreen
import com.example.ui.settings.MediaHubScreen
import com.example.ui.settings.VaultScreen
import com.example.ui.settings.SettingsNavigation
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
        const val EXTRA_SETTINGS_QUERY = SettingsNavigation.QUERY
        const val EXTRA_SETTINGS_REQUEST = SettingsNavigation.REQUEST
        const val ROUTE_MEDIA = "media"
        const val ROUTE_VAULT = "vault"
        const val ROUTE_CAPABILITIES = "capabilities"
        const val ROUTE_HOME = "home"
        const val ROUTE_APPEARANCE = "appearance"
        const val ROUTE_TYPING = "typing"
        const val ROUTE_LAYOUTS = "layouts"
        const val ROUTE_AI = "ai"
        const val ROUTE_VOICE = "voice"
        const val ROUTE_DICTIONARY = "dictionary"
        const val ROUTE_ABOUT = "about"
        const val ROUTE_DIAGNOSTICS = "diagnostics"
        const val ROUTE_DEBUGGER = "debugger"
        const val ROUTE_PERMISSIONS = "permissions"
        const val ROUTE_ALL_SETTINGS = "all"
        const val ROUTE_REQUEST_PANEL = "request"
        const val ROUTE_THEME_EDITOR = "theme"
        const val ROUTE_SETUP = "setup"
        /** A panel the user asked for: "panel:<id>". */
        const val ROUTE_PANEL_PREFIX = "panel:"
    }

    private var micPermissionGranted by mutableStateOf(false)
    private var route by mutableStateOf(ROUTE_HOME)
    private var settingsQuery by mutableStateOf("")
    private var settingsRequest by mutableStateOf("")

    private fun navigate(next: String) {
        settingsQuery = ""
        settingsRequest = ""
        route = next
    }

    private fun receiveDestination(incoming: Intent?) {
        if (incoming?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            @Suppress("DEPRECATION")
            val component = incoming.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)
            settingsQuery = if (component?.className == "com.example.io.PocketLockTile") "pocket" else "keyboard"
            settingsRequest = ""
            route = ROUTE_ALL_SETTINGS
        } else {
            route = incoming?.getStringExtra(EXTRA_ROUTE)
                ?: if (SettingsStore.current.setupDone) ROUTE_HOME else ROUTE_SETUP
            settingsQuery = incoming?.getStringExtra(EXTRA_SETTINGS_QUERY).orEmpty()
            settingsRequest = incoming?.getStringExtra(EXTRA_SETTINGS_REQUEST).orEmpty()
        }
    }

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
        receiveDestination(intent)
        enableEdgeToEdge()
        AppLogger.d("Settings", "onCreate stores ready")

        setContent {
            val settings by SettingsStore.state.collectAsState()
            val storageError by SettingsStore.persistenceError.collectAsState()
            val persistencePending by SettingsStore.persistencePending.collectAsState()
            BackHandler(enabled = route != ROUTE_HOME) { navigate(ROUTE_HOME) }

            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(titleFor(route))
                                    Text(if (persistencePending) "Saving settings…" else "", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            navigationIcon = {
                                if (route != ROUTE_HOME) {
                                    IconButton(onClick = { navigate(ROUTE_HOME) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        )
                    }
                ) { inner ->
                    Column(Modifier.fillMaxSize().padding(inner)) {
                        storageError?.let { error ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Settings storage: $error", color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = { SettingsStore.retryPersistence() }) { Text("Retry saving") }
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                        when (route) {
                            ROUTE_APPEARANCE -> if (SettingsHierarchy.level(settings) == SettingsLevel.BASIC)
                                BasicKeyboardSettingsScreen(settings, domain = "appearance") else AppearanceScreen(settings)
                            ROUTE_TYPING -> if (SettingsHierarchy.level(settings) == SettingsLevel.BASIC)
                                BasicKeyboardSettingsScreen(settings, domain = "typing") else TypingSettingsScreen(settings)
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
                            ROUTE_DEBUGGER -> RuntimeDebuggerScreen(settings, SettingsHierarchy.level(settings) == SettingsLevel.DEBUGGER)
                            ROUTE_ALL_SETTINGS -> AllSettingsScreen(settings, initialQuery = settingsQuery, onNavigate = ::navigate)
                            ROUTE_REQUEST_PANEL -> RequestPanelScreen(settings, initialRequest = settingsRequest)
                            ROUTE_MEDIA -> MediaHubScreen()
                            ROUTE_VAULT -> VaultScreen()
                            ROUTE_CAPABILITIES -> CapabilitiesScreen(onNavigate = ::navigate)
                            ROUTE_THEME_EDITOR -> ThemeEditorScreen(settings)
                            ROUTE_SETUP -> SetupWizardScreen(
                                settings = settings,
                                onDone = { navigate(ROUTE_HOME) },
                                onNavigate = ::navigate
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
                                            navigate(ROUTE_HOME)
                                        }
                                    )
                                } else {
                                    HomeScreen(
                                        settings = settings,
                                        onNavigate = ::navigate,
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.d("Settings", "onNewIntent (route=${intent.getStringExtra(EXTRA_ROUTE)})")
        setIntent(intent)
        receiveDestination(intent)
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
        // Engines and voices are only known once the speech engine has started.
        com.example.io.Speaker.warmUp(this)
        SettingsSchema.dynamicOptions["ttsEngine"] = { listOf("") + com.example.io.Speaker.engines() }
        SettingsSchema.dynamicOptions["ttsVoice"] = { listOf("") + com.example.io.Speaker.voices() }
        SettingsSchema.dynamicOptions["ttsProvider"] = {
            listOf("") + com.example.core.discovery.ProviderCatalog
                .serving(com.example.core.discovery.AiCapability.SPEECH, SettingsStore.current).map { it.id }
        }
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

private fun titleFor(route: String): String = com.example.core.config.SettingsDestinations.title(route)
